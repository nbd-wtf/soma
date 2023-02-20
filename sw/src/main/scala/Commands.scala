import cats.effect.IO
import cats.effect.std.Console
import org.http4s.client.Client
import com.monovore.decline._
import io.circe.generic.auto._
import io.circe.syntax._
import scoin.ln

sealed trait CommandMethod
case object Info extends CommandMethod
case class AddMiner(uri: String) extends CommandMethod
case class RemoveMiner(uri: String) extends CommandMethod

object Commands {
  val root = Command[CommandMethod](
    "sw",
    "soma wallet is a very simple wallet that interacts with the soma node daemon and with external miners."
  ) {
    Opts.subcommands(
      Command("info", "Displays wallet info.") {
        Opts.unit.map(_ => Info)
      },
      Command("miner", "Manage miners.") {
        Opts.subcommands(
          Command("add", "Adds a miner to your list.") {
            Opts
              .argument[String](metavar = "node uri")
              .map(uri => AddMiner(uri))
          },
          Command("remove", "Remove a miner from your list.") {
            Opts
              .argument[String](metavar = "node uri")
              .map(uri => RemoveMiner(uri))
          }
        )
      }
    )
  }

  def run(config: Config, client: Client[IO], args: List[String]): IO[Unit] =
    Commands.root.parse(
      PlatformApp.ambientArgs.getOrElse(args),
      PlatformApp.ambientEnvs.getOrElse(sys.env)
    ) match {
      case Left(help)              => Console[IO].errorln(help)
      case Right(Info)             => displayInfo(config)
      case Right(AddMiner(uri))    => addMiner(config, uri)
      case Right(RemoveMiner(uri)) => removeMiner(config, uri)
    }

  def displayInfo(config: Config): IO[Unit] =
    Console[IO].println(config.asJson.spaces2)

  def addMiner(config: Config, uri: String): IO[Unit] =
    "([a-fA-F0-9]{66})@([a-zA-Z0-9:.\\-_]+):([0-9]+)".r.unapplySeq(uri) match {
      case Some(x) =>
        Console[IO].println(x) *> Config.set(
          config.copy(miners = config.miners :+ uri)
        )
      case None => Console[IO].println("Invalid node address.")
    }

  def removeMiner(config: Config, uri: String): IO[Unit] =
    Config.set(config.copy(miners = config.miners.filter(_ != uri)))

}
