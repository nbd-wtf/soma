import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, JSGlobal}
import scodec.bits.ByteVector

object Config {
  private val home = homedir()
  def datadir = process.env.DATADIR.toOption
    .map(_.replace("~", home))
    .getOrElse(s"$home/.config/openchain/node/")

  mkdirSync(
    datadir,
    js.Dictionary("recursive" -> true).asInstanceOf[js.Object]
  )

  def genesisTx: String = process.env.GENESIS_TX
  def bitcoinHost: String =
    process.env.BITCOIND_HOST.toOption.getOrElse("127.0.0.1")
  def bitcoinPort: Int =
    process.env.BITCOIND_PORT.toOption.map(_.toInt).getOrElse {
      process.env.BITCOIN_CHAIN match {
        case "mainnet" => 8332
        case "testnet" => 18332
        case "signet"  => 38332
        case "regtest" => 18443
      }
    }
  def bitcoinAuthorizationHeader: String = {
    val a = s"${process.env.BITCOIND_USER}:${process.env.BITCOIND_PASSWORD}"
    val encoded = ByteVector(a.getBytes()).toBase64
    s"Basic $encoded"
  }

  def port: Int = process.env.PORT.map(_.toInt).getOrElse(9036)
}

@js.native
@JSGlobal
object process extends js.Object {
  def env: Env = js.native
}

@js.native
trait Env extends js.Object {
  val DATADIR: js.UndefOr[String] = js.native
  val GENESIS_TX: String = js.native
  val BITCOIN_CHAIN: String = js.native
  val BITCOIND_HOST: js.UndefOr[String] = js.native
  val BITCOIND_PORT: js.UndefOr[String] = js.native
  val BITCOIND_USER: String = js.native
  val BITCOIND_PASSWORD: String = js.native
  var PORT: js.UndefOr[String] = js.native
}

@js.native
@JSImport("node:os", "homedir")
object homedir extends js.Object {
  def apply(): String = js.native
}

@js.native
@JSImport("node:fs", "mkdirSync")
object mkdirSync extends js.Object {
  def apply(path: String, options: js.Object): js.Promise[Unit] =
    js.native
}
