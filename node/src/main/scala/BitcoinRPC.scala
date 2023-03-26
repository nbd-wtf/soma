import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import sttp.client3.*
import sttp.model.Uri

object BitcoinRPC {
  @js.native
  @JSImport("node-fetch", JSImport.Namespace)
  val nodeFetch: js.Dynamic = js.native

  private val g = scalajs.js.Dynamic.global.globalThis
  g.fetch = nodeFetch.default
  g.Headers = nodeFetch.Headers
  g.Request = nodeFetch.Request

  private val backend = FetchBackend()

  def call(
      method: String,
      params: ujson.Arr = ujson.Arr()
  ): Future[ujson.Value] =
    basicRequest
      .header("Authorization", Config.bitcoinAuthorizationHeader)
      .post(Uri(Config.bitcoinHost, Config.bitcoinPort))
      .body(
        ujson.write(
          ujson.Obj(
            "id" -> "0",
            "jsonrpc" -> "2.0",
            "method" -> method,
            "params" -> params
          )
        )
      )
      .send(backend)
      .map(_.body.toOption.get)
      .map(ujson.read(_))
      .map(_("result"))
}
