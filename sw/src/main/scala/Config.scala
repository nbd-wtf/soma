import java.nio.charset.StandardCharsets
import cats.effect.IO
import cats.effect.std.Console
import fs2.{Stream, text}
import fs2.io.file.{Files, Path}
import com.monovore.decline.*
import io.circe.*
import io.circe.parser.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import scodec.bits.*
import scoin.*

object Config {
  def get: IO[Config] =
    Files[IO].createDirectories(datadir) *> Files[IO]
      .readUtf8(configpath)
      .compile
      .string
      .handleErrorWith { _ =>
        Stream(default.asJson.spaces2)
          .through(text.encode(StandardCharsets.UTF_8))
          .through(Files[IO].writeAll(configpath))
          .compile
          .drain
          .as(default.asJson.toString)
      }
      .map(decode[Config](_))
      .rethrow

  def default = Config(
    privateKey = scoin.randomBytes32().toHex
  )

  def set(newConfig: Config): IO[Unit] =
    Stream(newConfig.asJson.spaces2)
      .evalTap(Console[IO].println)
      .through(text.encode(StandardCharsets.UTF_8))
      .through(Files[IO].writeAll(configpath))
      .compile
      .drain

  private def configpath = datadir.resolve("config.json")

  private def datadir = {
    val home = PlatformApp.ambientEnvs.getOrElse(sys.env).get("HOME").get
    Path(s"$home/.config/soma/wallet/")
  }
}

case class Config(
    privateKey: String
) {
  lazy val priv = PrivateKey(ByteVector.fromValidHex(privateKey))
  lazy val pub = priv.publicKey.xonly
}
