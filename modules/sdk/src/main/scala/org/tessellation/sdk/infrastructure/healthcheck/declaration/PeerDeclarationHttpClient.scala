package org.tessellation.sdk.infrastructure.healthcheck.declaration

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.option._

import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.domain.healthcheck.consensus.types.HealthCheckRoundId
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse

import org.http4s.Method.POST
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait PeerDeclarationHttpClient[F[_], K] {
  def requestProposal(roundIds: Set[HealthCheckRoundId], ownProposal: Status[K]): PeerResponse[F, Option[Status[K]]]
}

object PeerDeclarationHttpClient {

  def make[F[_]: Async: KryoSerializer, K](client: Client[F], session: Session[F]): PeerDeclarationHttpClient[F, K] =
    new PeerDeclarationHttpClient[F, K] with Http4sClientDsl[F] {

      def requestProposal(roundIds: Set[HealthCheckRoundId], ownProposal: Status[K]): PeerResponse[F, Option[Status[K]]] =
        PeerResponse(s"healthcheck/peer-declaration", POST)(client, session) { (req, c) =>
          c.expect[Status[K]](req.withEntity((roundIds, ownProposal)))
        }.map(_.some).handleError(_ => none)
    }
}
