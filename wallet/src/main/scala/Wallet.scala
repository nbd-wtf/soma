import scala.concurrent.Future
import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import com.raquo.laminar.api.L._
import sttp.client3._
import io.circe._
import io.circe.syntax._
import io.circe.parser._

object Wallet {
  val info: Signal[NodeInfo] = EventStream
    .periodic(10000)
    .flatMap(_ => EventStream.fromFuture(getInfo()))
    .toSignal(NodeInfo.empty)

  val assets =
    info.changes.flatMap(_ => EventStream.fromFuture(getAssets(PrivateKey.key)))

  def render(): HtmlElement =
    div(
      cls := "mr-3 py-3 px-4 my-3 bg-cyan-700 text-white rounded-md shadow-lg w-auto",
      div(
        cls := "py-2",
        cls := "text-xl text-ellipsis overflow-hidden",
        "Wallet"
      ),
      div(
        cls := "mb-3",
        div(
          b("assets:"),
          children <-- assets.map(_.map(renderAsset(_)))
        )
      )
    )

  def renderAsset(asset: String): HtmlElement =
    div(
      "\"",
      code(cls := "text-teal-300", asset),
      "\""
    )

  def getInfo(): Future[NodeInfo] =
    call("info").map(_.as[NodeInfo].toOption.get)

  def getAssets(key: String): Future[List[String]] =
    call("getaccountassets", Map("privkey" -> key.asJson))
      .map(_.as[List[String]].toOption.get)

  // ---
  val backend = FetchBackend()
  def call(
      method: String,
      params: Map[String, Json] = Map.empty
  ): Future[io.circe.Json] =
    basicRequest
      .post(uri"http://127.0.0.1:9036")
      .body(
        Map[String, Json](
          "method" -> method.asJson,
          "params" -> params.asJson
        ).asJson.toString
      )
      .send(backend)
      .map(_.body.toOption.get)
      .map(parse(_).toOption.get)
      .map(_.hcursor.downField("result").as[Json].toOption.get)
}
