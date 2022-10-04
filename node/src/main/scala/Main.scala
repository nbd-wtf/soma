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

  // ~
  // do this so SecureRandom from scoin works on ESModule
  @js.native
  @JSImport("crypto", JSImport.Namespace)
  val crypto: js.Dynamic = js.native
  private val g = scalajs.js.Dynamic.global.globalThis
  g.crypto = crypto
  // until https://github.com/scala-js/scala-js-java-securerandom/issues/8 is fixed
  // ~
}
