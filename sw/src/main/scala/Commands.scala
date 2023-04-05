import cats.implicits.*
import cats.data.Validated
import cats.effect.IO
import cats.effect.std.Console
import com.monovore.decline.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import scodec.bits.*
import scoin.*
import soma.*

sealed trait CommandMethod
case class Invalid(reason: String) extends CommandMethod
case object Info extends CommandMethod
case class Decode(stuff: String) extends CommandMethod
case object Mint extends CommandMethod
case class Send(asset: Int, counter: Int, to: XOnlyPublicKey)
    extends CommandMethod

object Commands {
  val root = Command[CommandMethod](
    "sw",
    "soma wallet is a very simple wallet that interacts with the soma node daemon and with external miners."
  ) {
    Opts.subcommands(
      Command("info", "Displays wallet info.") {
        Opts.unit.map(_ => Info)
      },
      Command("decode", "Decodes a transaction.") {
        Opts
          .argument[String]("tx")
          .map(tx => Decode(tx))
      },
      Command("mint", "Mint a new asset.") {
        Opts.unit.map(_ => Mint)
      },
      Command("send", "Send an asset to someone.") {
        (
          Opts
            .argument[Int]("asset"),
          Opts
            .argument[Int]("counter"),
          Opts
            .argument[String]("target pubkey")
            .mapValidated { arg =>
              ByteVector
                .fromHex(arg)
                .map(scoin.ByteVector32(_))
                .map(XOnlyPublicKey(_)) match {
                case Some(xonlypubkey) => Validated.valid(xonlypubkey)
                case _ =>
                  Validated.invalidNel(
                    s"invalid public key '$arg', must be 32 bytes in hex"
                  )
              }
            }
        )
          .mapN((asset, counter, target) => Send(asset, counter, target))
      }
    )
  }

  def run(implicit
      config: Config,
      args: List[String]
  ): IO[Int] =
    Commands.root.parse(
      PlatformApp.ambientArgs.getOrElse(args),
      PlatformApp.ambientEnvs.getOrElse(sys.env)
    ) match {
      case Left(help)                => Console[IO].errorln(help).as(1)
      case Right(Invalid(message))   => Console[IO].errorln(message).as(2)
      case Right(Info)               => displayInfo().as(0)
      case Right(Decode(stuff))      => decodeStuff(stuff).as(0)
      case Right(Mint)               => mintNewAsset().as(0)
      case Right(Send(asset, c, to)) => sendAsset(asset, c, to).as(0)
    }

  def displayInfo()(implicit config: Config): IO[Unit] = {
    val info = JsonObject(
      "privkey" -> config.priv.value.toHex.asJson,
      "pubkey" -> config.pub.toHex.asJson
    )
    Console[IO].println(info.asJson.spaces2)
  }

  def decodeStuff(stuff: String)(implicit config: Config): IO[Unit] =
    BitVector.fromHex(stuff).flatMap(Tx.codec.decodeValue(_).toOption) match {
      case None => Console[IO].errorln(s"invalid tx: $stuff")
      case Some(tx) => {
        val info = JsonObject(
          "asset" -> tx.asset.asJson,
          "to" -> tx.to.toHex.asJson,
          "from" -> tx.from.toHex.asJson,
          "counter" -> tx.counter.asJson,
          "signature" -> tx.signature.bytes.toHex.asJson
        )
        Console[IO].println(info.asJson.spaces2)
      }
    }

  def mintNewAsset()(implicit config: Config): IO[Unit] =
    Console[IO].println(
      Tx(
        asset = 0,
        to = config.pub,
        counter = 0,

        // make this random so the tx id is random
        from = XOnlyPublicKey(scoin.randomBytes32())
      ).encoded.toHex
    )

  def sendAsset(asset: Int, counter: Int, to: XOnlyPublicKey)(implicit
      config: Config
  ): IO[Unit] =
    Console[IO].println(
      Tx(
        asset = asset,
        to = to,
        from = config.pub,
        counter = counter
      ).withSignature(config.priv).encoded.toHex
    )
}
