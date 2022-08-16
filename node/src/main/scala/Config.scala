import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scodec.bits.ByteVector

object Config {
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
}

@js.native
@JSGlobal
object process extends js.Object {
  def env: Env = js.native
}

@js.native
trait Env extends js.Object {
  val GENESIS_TX: String = js.native
  val BITCOIN_CHAIN: String = js.native
  val BITCOIND_HOST: js.UndefOr[String] = js.native
  val BITCOIND_PORT: js.UndefOr[String] = js.native
  val BITCOIND_USER: String = js.native
  val BITCOIND_PASSWORD: String = js.native
}
