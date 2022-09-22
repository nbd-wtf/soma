import com.raquo.laminar.api.L._
import org.scalajs.dom

object Main {
  val miners = Var(
    List(
      Miner(
        "02855372fc5d61dfbe939aa04edb662beccc314d693d6ed65a92293137e9cf657b",
        "ws://127.0.0.1:9739",
        "tG5ixNeDcl2FxhY6Abi7jL_BauD-Ss1f-XfX0zKNNGg9MCZtZXRob2Reb3BlbmNoYWluLQ=="
      )
    )
  )

  val app = div(
    cls := "p-8",
    h1(cls := "text-xl", "openchain wallet"),
    PrivateKey.render(),
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
}
