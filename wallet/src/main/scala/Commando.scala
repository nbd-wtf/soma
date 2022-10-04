import scala.util.Success
import scala.util.chaining._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scala.concurrent.Future
import scala.scalajs.js
import io.circe.Json
import io.circe.syntax._
import io.circe.scalajs._

class Commando(nodeId: String, host: String, rune: String) {
  def rpc(
      method: String,
      params: Map[String, Json] = Map.empty
  ): Future[Json] =
    js.Dynamic.global
      .commando(
        nodeId,
        host,
        rune,
        method,
        params.asJson.toString
      )
      .asInstanceOf[js.Promise[String]]
      .toFuture
      .map(
        io.circe.parser
          .parse(_)
          .toTry
          .get
          .hcursor
          .downField("result")
          .focus
          .tap(println(_))
          .getOrElse(Json.Null)
      )
}
