import scala.concurrent.Future
import scala.util.chaining._
import scala.util.Success
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scala.scalajs.js.annotation._
import scala.scalajs.js

class Commando(nodeId: String, wsAddress: String, rune: String) {
  val lnF = for {
    ln <- LNSocket().toFuture
    _ = ln.genkey()
    _ <- ln.connect_and_init(nodeId, wsAddress).toFuture
  } yield ln

  def rpc(
      method: String,
      params: js.Dictionary[Any] = js.Dictionary.empty
  ): Future[io.circe.Json] = for {
    ln <- lnF
    msg = ln.make_commando_msg(
      js.Object
        .fromEntries(
          js.Array(
            ("rune" -> rune),
            ("method" -> method),
            ("params" -> params)
          )
        )
        .asInstanceOf[js.Object]
    )
    _ = ln.write(msg)
    res <- ln.read_all_rpc().toFuture
  } yield io.circe.parser
    .parse(res)
    .toOption
    .get
    .hcursor
    .downField("result")
    .focus
    .get
}

@js.native
@JSImport("lnsocket", JSImport.Default)
object LNSocket extends js.Object {
  def apply(): js.Promise[LN] = js.native
}

@js.native
trait LN extends js.Any {
  def genkey(): Unit = js.native
  def connect_and_init(
      nodeId: String,
      wsAddress: String
  ): js.Promise[Unit] = js.native
  def make_commando_msg(opts: js.Object): js.Any = js.native
  def write(msg: js.Any): Unit = js.native
  def read_all_rpc(): js.Promise[String] = js.native
}
