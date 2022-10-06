import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import org.scalajs.dom
import com.raquo.laminar.api.L._

object Main {
  val refetchInfoBus = new EventBus[Unit]
  val info: Signal[NodeInfo] = EventStream
    .merge(
      refetchInfoBus.events,
      EventStream.periodic(20000).map(_ => ())
    )
    .flatMap(_ => EventStream.fromFuture(Node.getInfo()))
    .toSignal(NodeInfo.empty)

  val miners: Var[List[Miner]] = Var(List.empty)
  Miner.loadMiners().foreach(miners.set(_))

  val txToMine: Var[Option[String]] = Var(None)
  val concreteTxToMine: EventStream[String] = txToMine.signal.changes.collect {
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
}
