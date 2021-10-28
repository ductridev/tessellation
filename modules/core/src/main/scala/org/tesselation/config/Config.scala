package org.tesselation.config

import cats.effect.Async
import cats.syntax.parallel._

import scala.concurrent.duration._

import org.tesselation.config.AppEnvironment.{Mainnet, Testnet}
import org.tesselation.config.types._

import ciris._
import ciris.refined._
import com.comcast.ip4s._
import eu.timepit.refined.types.string.NonEmptyString

object Config {

  val dbConfig = (
    env("CL_DB_DRIVER").default("org.h2.Driver").as[NonEmptyString],
    env("CL_DB_URL").default("jdbc:h2:mem:nodedb").as[NonEmptyString],
    env("CL_DB_USER").default("sa").as[NonEmptyString],
    env("CL_DB_PASSWORD").default("").secret
  ).parMapN(DBConfig)

  val keyConfig = (
    env("CL_KEYSTORE"),
    env("CL_STOREPASS").secret,
    env("CL_KEYPASS").secret,
    env("CL_KEYALIAS").secret
  ).parMapN(KeyConfig)

  val httpConfig = (
    env("CL_EXTERNAL_IP").default("127.0.0.1"),
    env("CL_PUBLIC_HTTP_PORT").default("9000"),
    env("CL_P2P_HTTP_PORT").default("9001"),
    env("CL_CLI_HTTP_PORT").default("9002")
  ).parMapN { (externalIp, publicHttpPort, p2pHttpPort, cliHttpPort) =>
    HttpConfig(
      externalIp = Host.fromString(externalIp).get,
      client = HttpClientConfig(
        timeout = 60.seconds,
        idleTimeInPool = 30.seconds
      ),
      publicHttp = HttpServerConfig(host"0.0.0.0", Port.fromString(publicHttpPort).get),
      p2pHttp = HttpServerConfig(host"0.0.0.0", Port.fromString(p2pHttpPort).get),
      cliHttp = HttpServerConfig(host"127.0.0.1", Port.fromString(cliHttpPort).get)
    )
  }

  val gossipConfig = ConfigValue.default(
    GossipConfig(
      storage = RumorStorageConfig(
        activeRetention = 2.seconds,
        seenRetention = 2.minutes
      ),
      daemon = GossipDaemonConfig(
        fanout = 2,
        interval = 0.2.seconds,
        maxConcurrentHandlers = 20
      )
    )
  )

  def load[F[_]: Async]: F[AppConfig] =
    env("CL_APP_ENV")
      .default("testnet")
      .as[AppEnvironment]
      .flatMap {
        case Testnet => default[F](Testnet)
        case Mainnet => default[F](Mainnet)
      }
      .load[F]

  def default[F[_]](environment: AppEnvironment): ConfigValue[F, AppConfig] =
    (keyConfig, httpConfig, dbConfig, gossipConfig).parMapN { (key, http, db, gossip) =>
      AppConfig(environment, key, http, db, gossip)
    }

}
