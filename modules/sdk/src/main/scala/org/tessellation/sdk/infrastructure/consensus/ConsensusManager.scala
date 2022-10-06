package org.tessellation.sdk.infrastructure.consensus

import cats.data.Ior.{Both, Right}
import cats.effect._
import cats.effect.std.{Queue, Supervisor}
import cats.kernel.Next
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.{Observing, Ready}
import org.tessellation.schema.peer.Peer.toP2PContext
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Key, Artifact] {

  def startObservingAfter(lastKey: Key, peer: Peer): F[Unit]
  def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact]): F[Unit]
  private[consensus] def facilitateOnEvent: F[Unit]
  private[consensus] def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]
  private[sdk] def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]

}

object ConsensusManager {

  def make[F[_]: Async: Clock: Metrics, Event, Key: Show: Order: Next: TypeTag: Encoder: Decoder, Artifact: Show: TypeTag](
    timeTriggerInterval: FiniteDuration,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key],
    gossip: Gossip[F],
    selfId: PeerId
  )(implicit S: Supervisor[F]): F[ConsensusManager[F, Key, Artifact]] = Queue.unbounded[F, Peer].flatMap { peersForRegistrationExchange =>
    val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    def exchangeRegistration(peer: Peer): F[Unit] = {
      val exchange = for {
        exchangeRequest <- consensusStorage.getOwnRegistration.map(RegistrationExchangeRequest(_))
        exchangeResponse <- consensusClient.exchangeRegistration(exchangeRequest).run(peer)
        maybeResult <- exchangeResponse.maybeKey.traverse(consensusStorage.registerPeer(peer.id, _))
        _ <- (exchangeResponse.maybeKey, maybeResult).traverseN {
          case (key, result) =>
            if (result)
              logger.info(s"Peer ${peer.id.show} registered at ${key.show}")
            else
              logger.warn(s"Peer ${peer.id.show} cannot be registered at ${key.show}")
        }
      } yield ()

      exchange
        .handleErrorWith(err => logger.error(err)(s"Error exchanging registration with peer ${peer.show}"))
    }

    def startRegistrationExchange: F[Unit] =
      S.supervise {
        Stream
          .fromQueueUnterminated(peersForRegistrationExchange)
          .evalMap(exchangeRegistration)
          .compile
          .drain
      }.void

    val manager = new ConsensusManager[F, Key, Artifact] {

      def startObservingAfter(lastKey: Key, peer: Peer): F[Unit] =
        S.supervise {
          val observationKey = lastKey.next
          val facilitationKey = lastKey.nextN(2L)

          consensusStorage.setOwnRegistration(facilitationKey) >>
            startRegistrationExchange >>
            consensusStorage.setLastKey(lastKey) >>
            consensusStorage
              .getResources(observationKey)
              .flatMap { resources =>
                logger.debug(s"Trying to observe consensus {key=${observationKey.show}}") >>
                  consensusStateUpdater.tryObserveConsensus(observationKey, lastKey, resources, peer.id).flatMap {
                    case Some(_) =>
                      internalCheckForStateUpdate(observationKey, resources)
                    case None => Applicative[F].unit
                  }
              }
              .handleErrorWith(logger.error(_)(s"Error observing consensus {key=${observationKey.show}}"))
        }.void

      def facilitateOnEvent: F[Unit] =
        S.supervise {
          internalFacilitateWith(EventTrigger.some)
            .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
        }.void

      def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact]): F[Unit] =
        consensusStorage.setLastKeyAndArtifact(lastKey, lastArtifact) >>
          consensusStorage.setOwnRegistration(lastKey.next) >>
          startRegistrationExchange >>
          scheduleFacility

      private def scheduleFacility: F[Unit] =
        Clock[F].monotonic.map(_ + timeTriggerInterval).flatMap { nextTimeValue =>
          consensusStorage.setTimeTrigger(nextTimeValue) >>
            S.supervise {
              val condTriggerWithTime = for {
                maybeTimeTrigger <- consensusStorage.getTimeTrigger
                currentTime <- Clock[F].monotonic
                _ <- Applicative[F]
                  .whenA(maybeTimeTrigger.exists(currentTime >= _))(internalFacilitateWith(TimeTrigger.some))
              } yield ()

              Temporal[F].sleep(timeTriggerInterval) >> condTriggerWithTime
                .handleErrorWith(logger.error(_)(s"Error triggering consensus with time trigger"))
            }.void
        }

      def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
        S.supervise {
          internalCheckForStateUpdate(key, resources)
            .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
        }.void

      def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
        internalCheckForStateUpdate(key, resources)

      private def internalFacilitateWith(
        trigger: Option[ConsensusTrigger]
      ): F[Unit] =
        consensusStorage.getLastKeyAndArtifact.flatMap { maybeLastKeyAndArtifact =>
          maybeLastKeyAndArtifact.traverse {
            case (lastKey, Some(lastArtifact)) =>
              val nextKey = lastKey.next

              consensusStorage
                .getResources(nextKey)
                .flatMap { resources =>
                  logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                    consensusStateUpdater.tryFacilitateConsensus(nextKey, lastKey, lastArtifact, trigger, resources).flatMap {
                      case Some(_) =>
                        internalCheckForStateUpdate(nextKey, resources)
                      case None => Applicative[F].unit
                    }
                }
            case _ => Applicative[F].unit
          }.void
        }

      private def internalCheckForStateUpdate(
        key: Key,
        resources: ConsensusResources[Artifact]
      ): F[Unit] =
        consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
          case Some(state) =>
            state.status match {
              case Finished(signedArtifact, majorityTrigger) =>
                Metrics[F].recordTime("dag_consensus_duration", state.statusUpdatedAt.minus(state.createdAt)) >>
                  consensusStorage
                    .tryUpdateLastKeyAndArtifactWithCleanup(state.lastKey, key, signedArtifact)
                    .ifM(
                      afterConsensusFinish(majorityTrigger),
                      logger.info("Skip triggering another consensus")
                    ) >> nodeStorage.tryModifyStateGetResult(Observing, Ready).void
              case _ =>
                internalCheckForStateUpdate(key, resources)
            }
          case None => Applicative[F].unit
        }

      private def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
        majorityTrigger match {
          case EventTrigger => afterEventTrigger
          case TimeTrigger  => afterTimeTrigger
        }

      private def afterEventTrigger: F[Unit] =
        for {
          maybeTimeTrigger <- consensusStorage.getTimeTrigger
          currentTime <- Clock[F].monotonic
          containsTriggerEvent <- consensusStorage.containsTriggerEvent
          _ <-
            if (maybeTimeTrigger.exists(currentTime >= _))
              internalFacilitateWith(TimeTrigger.some)
            else if (containsTriggerEvent)
              internalFacilitateWith(EventTrigger.some)
            else if (maybeTimeTrigger.isEmpty)
              internalFacilitateWith(none) // when there's no time trigger scheduled yet, trigger again with nothing
            else
              Applicative[F].unit
        } yield ()

      private def afterTimeTrigger: F[Unit] =
        scheduleFacility >> consensusStorage.containsTriggerEvent
          .ifM(internalFacilitateWith(EventTrigger.some), Applicative[F].unit)
    }

    S.supervise(
      nodeStorage.nodeStates
        .filter(NodeState.leaving.contains)
        .evalTap { _ =>
          (consensusStorage.getLastKey.map(_.map(_.next)), consensusStorage.getOwnRegistration).tupled.flatMap {
            _.mapN(Order[Key].max)
              .map(Deregistration(_))
              .traverse(gossip.spread[Deregistration[Key]])
          }
        }
        .compile
        .drain
    ) >>
      S.supervise(
        clusterStorage.peerChanges.mapFilter {
          case Both(_, peer) if peer.state === NodeState.Observing =>
            peer.some
          case Right(peer) if NodeState.inConsensus.contains(peer.state) =>
            peer.some
          case _ =>
            none[Peer]
        }
          .filter(_.isResponsive)
          .filter(selfId < _.id)
          .enqueueUnterminated(peersForRegistrationExchange)
          .compile
          .drain
      ).as(manager)
  }
}
