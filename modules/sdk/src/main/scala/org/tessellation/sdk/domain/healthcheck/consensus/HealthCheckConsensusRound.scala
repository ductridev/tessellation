package org.tessellation.sdk.domain.healthcheck.consensus

import cats.Applicative
import cats.effect.{Clock, Ref, Spawn}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.consensus.types._

class HealthCheckConsensusRound[F[_]: Spawn: Clock, A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]](
  key: HealthCheckKey,
  roundId: HealthCheckRoundId,
  driver: HealthCheckConsensusDriver[A, B],
  startedAt: FiniteDuration,
  peers: Ref[F, Set[PeerId]],
  roundIds: Ref[F, Set[HealthCheckRoundId]],
  proposals: Ref[F, Map[PeerId, B]],
  gossip: Gossip[F],
  selfId: PeerId
) {

  def start: F[Unit] =
    Spawn[F].start {
      sendProposal
    }.void

  def getPeers: F[Set[PeerId]] = peers.get

  def managePeers(currentPeers: Set[Peer]): F[Unit] = {
    def absentPeers = NodeState.absent(currentPeers).map(_.id)

    proposals.get.flatMap { received =>
      peers.update { roundPeers =>
        def missingPeers = roundPeers -- currentPeers.map(_.id)
        def toRemove = (absentPeers ++ missingPeers) -- received.keySet

        roundPeers -- toRemove
      }
    }
  }

  def isFinished: F[Boolean] = allProposalsReceived

  def processProposal(proposal: B): F[Unit] =
    if (proposal.key == key) {
      proposals.modify { m =>
        m.get(proposal.owner)
          .fold {
            (m + (proposal.owner -> proposal), proposal.some)
          } { _ =>
            (m, none)
          }
      }.flatMap {
        _.fold { Applicative[F].unit } { proposal =>
          roundIds.update(_ + proposal.roundId).flatMap { _ =>
            peers.update(_ + proposal.owner)
          }
        }
      }
    } else Applicative[F].unit

  def getRoundIds: F[Set[HealthCheckRoundId]] = roundIds.get

  def addParallelRounds(key: HealthCheckKey)(roundIds: Set[HealthCheckRoundId]): F[Unit] = ???

  def calculateOutcome: F[HealthCheckConsensusDecision] = {
    def status: A = ???

    (proposals.get, peers.get).mapN { (_proposals, _peers) =>
      def received = _proposals.view.filterKeys(_peers.contains).values.toList

      driver.calculateConsensusOutcome(key, status, selfId, received)
    }
  }

  def manage: F[Unit] =
    Clock[F].monotonic.flatMap { currentTime =>
      sendProposal // Note: check elapsed time and execute additional tasks
    }

  def generateHistoricalData(decision: HealthCheckConsensusDecision): F[HistoricalRound] = ???

  def ownConsensusHealthStatus: F[B] = {
    def status: A = ???
    Applicative[F].pure(driver.consensusHealthStatus(key, status, roundId, selfId))
  }

  private def sendProposal: F[Unit] =
    ownConsensusHealthStatus.flatMap(gossip.spread)

  private def allProposalsReceived: F[Boolean] =
    (peers.get, proposals.get.map(_.keySet))
      .mapN(_ -- _)
      .map(_.isEmpty)
}

object HealthCheckConsensusRound {

  def make[F[_]: Ref.Make: Spawn: Clock, A <: HealthCheckStatus, B <: ConsensusHealthStatus[A]](
    key: HealthCheckKey,
    roundId: HealthCheckRoundId,
    initialPeers: Set[PeerId],
    driver: HealthCheckConsensusDriver[A, B],
    gossip: Gossip[F],
    selfId: PeerId
  ): F[HealthCheckConsensusRound[F, A, B]] = {

    def mkStartedAt = Clock[F].monotonic
    def mkPeers = Ref.of[F, Set[PeerId]](initialPeers)
    def mkRoundIds = Ref.of[F, Set[HealthCheckRoundId]](Set(roundId))
    def mkProposals = Ref.of[F, Map[PeerId, B]](Map.empty)

    (mkStartedAt, mkPeers, mkRoundIds, mkProposals).mapN { (startedAt, peers, roundIds, proposals) =>
      new HealthCheckConsensusRound[F, A, B](
        key,
        roundId,
        driver,
        startedAt,
        peers,
        roundIds,
        proposals,
        gossip,
        selfId
      )
    }
  }
}
