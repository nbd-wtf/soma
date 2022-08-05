import java.nio.file.Path
import scala.util.Try
import upickle.default._

object Config {
  // def fromFile(basePath: Path): Try[Config] =
  //   Try(read[Config](basePath.resolve("config.json")))
  //     .map(_.copy(basePath = Some(basePath)))

  def defaults: Config = Config()
}

case class Config(
    // path
    basePath: Option[Path] = None,

    // settings
    isDev: Boolean = true
)
