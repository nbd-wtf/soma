import scala.concurrent.Future
import scala.util.chaining._
import scala.util.Success
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scala.scalajs.js.annotation._
import scala.scalajs.js
import org.scalajs.dom
import io.circe.Json
import io.circe.syntax._

class Commando(nodeId: String, host: String, rune: String) {
  def rpc(
      method: String,
      params: Map[String, Json] = Map.empty
  ): Future[Json] = {
    js.Dynamic.global
      .commando(nodeId, host, rune, method, params.asJson.toString)
      .asInstanceOf[js.Promise[String]]
      .toFuture
      .map(
        io.circe.parser
          .parse(_)
          .toOption
          .tap(println(_))
          .get
          .hcursor
          .downField("result")
          .focus
          .get
      )
  }
}

@js.native
@JSImport("kjua", JSImport.Default)
object kjua extends js.Object {
  def apply(params: js.Dictionary[Any]): org.scalajs.dom.Node = js.native
}
