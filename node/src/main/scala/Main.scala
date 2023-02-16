import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object Main {
  def main(args: Array[String]): Unit = {
    Database.init()
    BitcoinManager.start()
    PeerManager.start()
    StateManager.start()
    HttpServer.start()
  }
}
