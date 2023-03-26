import scala.concurrent.Future
import scala.util.Random
import scala.util.chaining.*
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import com.raquo.laminar.api.L.*
import scodec.bits.ByteVector
import scoin.*

object Keys {
  val privkey: Future[PrivateKey] =
    js.Dynamic.global
      .loadKey()
      .asInstanceOf[js.Promise[String]]
      .toFuture
      .map {
        case nothing if nothing == "" => {
          val k = Crypto.randomBytes(32).toHex
          js.Dynamic.global.storeKey(k)
          k
        }
        case key => key
      }
      .map(hex => PrivateKey(ByteVector32(ByteVector.fromValidHex(hex))))

  val pubkey = privkey.map(_.publicKey.xonly)

  def render(): HtmlElement =
    div(
      cls := "py-3 px-4 my-3 mr-3 bg-red-800 text-white rounded-md shadow-lg w-auto break-all",
      maxWidth := "20rem",
      div(
        cls := "py-2",
        cls := "text-xl text-ellipsis overflow-hidden",
        "Keys"
      ),
      div(
        b("Private Key: "),
        child <-- EventStream
          .fromFuture(privkey)
          .map(sk => code(sk.value.toHex))
          .toSignal("")
      ),
      div(
        b("Public Key: "),
        child <-- EventStream
          .fromFuture(pubkey)
          .map(pk => code(pk.value.toHex))
          .toSignal("")
      )
    )
}
