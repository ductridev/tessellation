package org.tesselation.infrastructure.trust

import cats.Parallel
import cats.effect.std.Random
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.config.types.TrustDaemonConfig
import org.tesselation.domain.Daemon
import org.tesselation.domain.trust.storage.TrustStorage
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema.peer.PeerId
import org.tesselation.schema.trust.TrustInfo
import org.tesselation.security.SecurityProvider

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait TrustDaemon[F[_]] extends Daemon[F]

object TrustDaemon {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel](
    cfg: TrustDaemonConfig,
    trustStorage: TrustStorage[F],
    selfPeerId: PeerId
  ): TrustDaemon[F] = new TrustDaemon[F] {

    private val logger = Slf4jLogger.getLogger[F]

    def start: F[Unit] =
      for {
        _ <- Spawn[F].start(modelUpdate.foreverM).void
      } yield ()

    private def calculatePredictedTrust(trust: Map[PeerId, TrustInfo]): Map[PeerId, Double] = {
      val selfTrustLabels = trust.flatMap { case (peerId, trustInfo) => trustInfo.publicTrust.map(peerId -> _) }
      val allNodesTrustLabels = trust.view.mapValues(_.peerLabels).toMap + (selfPeerId -> selfTrustLabels)

      val peerIdToIdx = allNodesTrustLabels.keys.zipWithIndex.toMap
      val idxToPeerId = peerIdToIdx.map(_.swap)

      val trustNodes = allNodesTrustLabels.map {
        case (peerId, labels) =>
          TrustNode(peerIdToIdx(peerId), 0, 0, labels.map {
            case (pid, label) =>
              TrustEdge(peerIdToIdx(peerId), peerIdToIdx(pid), label, peerId == selfPeerId)
          }.toList)
      }.toList

      SelfAvoidingWalk
        .runWalkFeedbackUpdateSingleNode(peerIdToIdx(selfPeerId), trustNodes)
        .edges
        .map(e => idxToPeerId(e.dst) -> e.trust)
        .toMap
    }

    private def modelUpdate: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        predictedTrust <- trustStorage.getTrust.map(calculatePredictedTrust)
        _ <- trustStorage.updatePredictedTrust(predictedTrust)
      } yield ()

  }
}