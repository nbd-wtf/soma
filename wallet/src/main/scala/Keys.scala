import scala.concurrent.Future
import scala.util.Random
import scala.util.chaining._
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import com.raquo.laminar.api.L._
import scodec.bits.ByteVector
import scoin._

object Keys {
  val privkey: Future[Crypto.PrivateKey] =
    js.Dynamic.global
      .loadKey()
      .asInstanceOf[js.Promise[String]]
      .toFuture
      .map(_.tap(println(_)))
      .map {
        case nothing if nothing == "" => {
          val k = Crypto.randomBytes(32).toHex
          js.Dynamic.global.storeKey(k)
          k
        }
        case key => key
      }
      .map(hex => Crypto.PrivateKey(ByteVector32(ByteVector.fromValidHex(hex))))

  val pubkey = privkey.map(_.publicKey)

  def render(): HtmlElement =
    div(
      cls := "py-3 px-4 my-3 bg-red-800 text-white rounded-md shadow-lg w-auto",
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
