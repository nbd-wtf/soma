import cats.effect.{IO, ExitCode, IOApp}
import org.http4s.ember.client.EmberClientBuilder

object SomaWallet extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    Config.get.flatMap { config =>
      EmberClientBuilder
        .default[IO]
        .build
        .use { client => Commands.run(config, client, args) }
        .map(_ => ExitCode(0))
    }
}
