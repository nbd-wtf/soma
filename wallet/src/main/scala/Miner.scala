import scala.util.{Success, Failure}
import scala.util.chaining._
import scala.concurrent.duration._
import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import com.raquo.laminar.api.L._
import io.circe.generic.auto._

class Miner(pubkey: String, address: String, rune: String) {
  val commando = new Commando(pubkey, address, rune)

  val status: Signal[MinerStatus] = EventStream
    .periodic(20000)
    .flatMap(_ => EventStream.fromFuture(commando.rpc("openchain-status")))
    .map(_.as[MinerStatus].toOption.get)
    .toSignal(MinerStatus.empty)
  val showInvoice: Var[Option[MinerInvoice]] = Var(None)
  val nextTx: Var[String] = Var("")
  val nextFee: Var[Int] = Var(0)
  val invoicePaid = showInvoice.signal.changes
    .collect { case Some(mi) => mi.hash }
    .flatMap(hash =>
      EventStream.fromFuture(
        commando
          .rpc("openchain-waitinvoice", js.Dictionary("payment_hash" -> hash))
      )
    )
    .map(_ => true)
    .toSignal(false)

  def render(): HtmlElement =
    div(
      cls := "mr-3 py-3 px-4 my-3 bg-sky-600 text-white rounded-md shadow-lg w-auto max-w-xs",
      div(
        cls := "py-2",
        div(cls := "text-xl text-ellipsis overflow-hidden", pubkey),
        div(cls := "text-lg", address)
      ),
      div(
        cls := "mb-3",
        div(
          b("pending txs: "),
          child <-- status.map(_.pendingTxs)
        ),
        div(
          b("accumulated fees: "),
          child <-- status.map(_.accFees)
        )
      ),
      child <-- Signal.combine(showInvoice.signal, invoicePaid).map {
        case (Some(_), true) =>
          div("payment received, trying to publish transaction")
        case (Some(inv), false) =>
          div(
            div(
              cls := "flex justify-center my-2",
              onMountCallback { ctx =>
                ctx.thisNode.ref.appendChild(
                  kjua(
                    js.Dictionary(
                      "text" -> inv.invoice.toUpperCase(),
                      "size" -> 300
                    )
                  )
                )
              }
            ),
            code(
              cls := "block p-2 whitespace-pre-wrap break-all",
              inv.invoice.toLowerCase()
            )
          )
        case (None, _) =>
          form(
            onSubmit.preventDefault --> { _ =>
              commando
                .rpc(
                  "openchain-invoice",
                  js.Dictionary(
                    "tx" -> nextTx.now(),
                    "msatoshi" -> nextFee.now()
                  )
                )
                .map(_.as[MinerInvoice].toOption.get)
                .onComplete {
                  case Success(inv) =>
                    println(s"got invoice from miner: $inv")
                    showInvoice.set(Some(inv))
                  case Failure(err) =>
                    println(s"error getting invoice from miner: $err")
                }
            },
            label(
              cls := "block mb-1",
              "tx: ",
              textArea(
                cls := "block text-black px-1 h-64 w-full",
                onInput.mapToValue.setAsValue --> nextTx.writer
              )
            ),
            label(
              cls := "block",
              "fee to pay (sat): ",
              input(
                cls := "block text-black px-1 w-full",
                onInput.mapToValue.setAsValue --> nextFee.writer
                  .contramap[String](_.toIntOption.map(_ * 1000).getOrElse(0))
              )
            ),
            button(
              cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
              "publish"
            )
          )
      }
    )
}
