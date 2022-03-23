package org.tessellation.sdk.infrastructure.cluster.storage

import cats.effect.IO
import cats.syntax.option._

import org.tessellation.schema.generators._
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.security.hex.Hex

import com.comcast.ip4s.IpLiteralSyntax
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ClusterStorageSuite extends SimpleIOSuite with Checkers {

  test("getPeers returns an empty Set") {
    for {
      cs <- ClusterStorage.make[IO]()
      get <- cs.getPeers
    } yield expect.same(get, Set.empty[Peer])
  }

  test("getPeers returns provided peers") {
    forall(peersGen()) { peers =>
      for {
        cs <- ClusterStorage.make[IO](peers.toList.map(p => p.id -> p).toMap)
        p <- cs.getPeers
      } yield expect.same(p, peers)
    }
  }

  test("getPeer follows addPeer") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO]()
        _ <- cs.addPeer(peer)
        result <- cs.getPeer(peer.id)
      } yield expect.same(result, peer.some)
    }
  }

  test("hasPeerId returns true if peer with provided Id exists") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](Map(peer.id -> peer))
        hasPeerId <- cs.hasPeerId(peer.id)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerId returns false if peer with provided Id does not exist") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](Map(peer.id -> peer))
        hasPeerId <- cs.hasPeerId(PeerId(Hex("unknown")))
      } yield expect(!hasPeerId)
    }
  }

  test("hasPeerHostPort returns true if peer with provided host and port exists") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](Map(peer.id -> peer))
        hasPeerId <- cs.hasPeerHostPort(peer.ip, peer.p2pPort)
      } yield expect(hasPeerId)
    }
  }

  test("hasPeerHostPort returns false if peer with provided host and port does not exist") {
    forall(peerGen) { peer =>
      for {
        cs <- ClusterStorage.make[IO](Map(peer.id -> peer))
        hasPeerHostPort <- cs.hasPeerHostPort(host"0.0.0.1", port"1")
      } yield expect(!hasPeerHostPort)
    }
  }
}
