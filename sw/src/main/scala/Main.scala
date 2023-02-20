import cats.effect.{IO, ExitCode, IOApp}

object SomaWallet extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    Config.get.flatMap { config =>
      Commands.run(config, args) *> IO(ExitCode(0))
    }
}
