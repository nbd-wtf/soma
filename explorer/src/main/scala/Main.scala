import scala.util.{Try, Success}
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import org.scalajs.dom
import sttp.model.Uri
import com.raquo.laminar.api.L._
import openchain._

object Main {
  val info: Signal[NodeInfo] = EventStream
    .periodic(25000)
    .flatMap(_ => EventStream.fromFuture(Node.getInfo()))
    .toSignal(NodeInfo.empty)

  val app = div(
    cls := "p-8",
    fontFamily := "monospace",
    h1(cls := "text-2xl", "explorer"),
    BlockView.render(),
    AssetView.render()
  )

  def main(args: Array[String]): Unit = {
    documentEvents.onDomContentLoaded.foreach { _ =>
      render(dom.document.getElementById("main"), app)
    }(unsafeWindowOwner)
  }

  AirstreamError.registerUnhandledErrorCallback { err =>
    println(s"airstream unhandled error: $err")
  }

  val txExplorerUrl = Try(
    Uri.parse(dom.window.localStorage.getItem("txExplorerUrl"))
  ) match {
    case Success(Right(s)) => s
    case _ =>
      Uri.unsafeParse(js.Dynamic.global.TX_EXPLORER_URL.asInstanceOf[String])
  }
}
