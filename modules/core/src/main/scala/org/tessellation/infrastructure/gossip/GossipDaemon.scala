package org.tessellation.infrastructure.gossip

import cats.effect.std.{Queue, Random}
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.applicativeError._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.show._
import cats.syntax.traverseFilter._
import cats.{Applicative, Parallel}

import org.tessellation.config.types.GossipDaemonConfig
import org.tessellation.domain.Daemon
import org.tessellation.domain.cluster.storage.ClusterStorage
import org.tessellation.domain.gossip.RumorStorage
import org.tessellation.ext.crypto._
import org.tessellation.http.p2p.clients.GossipClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GossipDaemon[F[_]] extends Daemon[F]

object GossipDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel](
    rumorStorage: RumorStorage[F],
    rumorQueue: Queue[F, RumorBatch],
    clusterStorage: ClusterStorage[F],
    gossipClient: GossipClient[F],
    rumorHandler: RumorHandler[F],
    nodeId: PeerId,
    cfg: GossipDaemonConfig
  ): GossipDaemon[F] = new GossipDaemon[F] {

    private val logger = Slf4jLogger.getLogger[F]

    implicit private val signedRumorOrdering: Ordering[Signed[Rumor]] = Signed.order[Rumor].toOrdering

    def start: F[Unit] =
      for {
        _ <- Spawn[F].start(spreadActiveRumors.foreverM).void
        _ <- Spawn[F].start(consumeRumors)
      } yield ()

    private def consumeRumors: F[Unit] =
      Stream
        .fromQueueUnterminated(rumorQueue)
        .evalMap(validateHash)
        .evalMap(validateSignature)
        .evalMap(rumorStorage.addRumors)
        .map(_.sortBy(_._2))
        .flatMap(Stream.iterable)
        .filter { case (_, signedRumor) => signedRumor.value.origin =!= nodeId }
        .evalMap(handleRumor)
        .compile
        .drain

    private def validateHash(batch: RumorBatch): F[RumorBatch] =
      batch.filterA {
        case (hash, signedRumor) =>
          signedRumor.value.hashF
            .map(_ === hash)
            .flatTap { valid =>
              if (valid) Applicative[F].unit
              else
                logger.warn(
                  s"Discarding rumor ${signedRumor.value.show} with hash ${hash.show} due to invalid hash."
                )
            }
      }

    private def validateSignature(batch: RumorBatch): F[RumorBatch] =
      batch.filterA {
        case (hash, signedRumor) =>
          signedRumor.hasValidSignature.flatTap { valid =>
            if (valid) Applicative[F].unit
            else
              logger.warn(
                s"Discarding rumor ${signedRumor.value.show} with hash ${hash.show} due to invalid hash signature."
              )
          }
      }

    private def handleRumor(har: HashAndRumor): F[Unit] = har match {
      case (hash, signedRumor) =>
        rumorHandler
          .run((signedRumor.value, rumorStorage))
          .getOrElseF {
            logger.warn(s"Unhandled rumor ${signedRumor.value.show} with hash ${hash.show}.")
          }
          .handleErrorWith { err =>
            logger.error(err)(
              s"Error handling rumor ${signedRumor.value.show} with hash ${hash.show}."
            )
          }
    }

    private def spreadActiveRumors: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        activeHashes <- rumorStorage.getActiveHashes
        _ <- if (activeHashes.nonEmpty) runGossipRounds(activeHashes)
        else Applicative[F].unit
      } yield ()

    private def runGossipRounds(activeHashes: List[Hash]): F[Unit] =
      for {
        seenHashes <- rumorStorage.getSeenHashes
        peers <- clusterStorage.getPeers
        selectedPeers <- Random[F].shuffleList(peers.toList).map(_.take(cfg.fanout))
        _ <- selectedPeers.parTraverse { peer =>
          runGossipRound(activeHashes, seenHashes, peer).handleErrorWith { err =>
            logger.error(err)(s"Error running gossip round with peer ${peer.show}")
          }
        }
      } yield ()

    private def runGossipRound(activeHashes: List[Hash], seenHashes: List[Hash], peer: Peer): F[Unit] =
      for {
        _ <- Applicative[F].unit
        startRequest = StartGossipRoundRequest(activeHashes)
        startResponse <- gossipClient.startGossiping(startRequest).run(peer)
        inquiry = startResponse.offer.diff(seenHashes)
        answer <- rumorStorage.getRumors(startResponse.inquiry)
        endRequest = EndGossipRoundRequest(answer, inquiry)
        endResponse <- gossipClient.endGossiping(endRequest).run(peer)
        _ <- rumorQueue.offer(endResponse.answer)
      } yield ()
  }

}