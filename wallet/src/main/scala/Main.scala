import scala.util.{Try, Success}
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import org.scalajs.dom
import sttp.model.Uri
import com.raquo.laminar.api.L._
import openchain._

object Main {
  val refetchInfoBus = new EventBus[Unit]
  val info: Signal[NodeInfo] = EventStream
    .merge(
      refetchInfoBus.events,
      EventStream.periodic(20000).map(_ => ())
    )
    .flatMap(_ => EventStream.fromFuture(Node.getInfo()))
    .toSignal(NodeInfo.empty)

  val currentBlock = info
    .map(_.latestKnownBlock)
    .changes
    .collect { case Some(b) => b }
    .flatMap(b => EventStream.fromFuture(Node.getBlock(b.hash)))

  val miners: Var[List[Miner]] = Var(List.empty)
  Miner.loadMiners().foreach(miners.set(_))

  val txToMine: Var[Option[Tx]] = Var(None)
  val concreteTxToMine: EventStream[Tx] = txToMine.signal.changes.collect {
    case Some(h) => h
  }

  val app = div(
    cls := "p-8",
    h1(cls := "text-xl", "wallet"),
    div(
      cls := "flex",
      BMM.render(),
      Keys.render(),
      Miner.renderAddMinerForm()
    ),
    div(
      cls := "flex",
      Wallet.render(),
      children <-- miners.signal.map(_.map(_.render()))
    )
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
