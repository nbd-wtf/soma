import scala.util.{Success, Failure}
import scala.util.chaining._
import scala.concurrent.duration.{span => _, _}
import scala.concurrent.Future
import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import com.raquo.laminar.api.L._
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._

case class Miner(pubkey: String, host: String, rune: String) {
  val commando = new Commando(pubkey, host, rune)

  val refetchStatusBus = new EventBus[Unit]
  val status: Signal[MinerStatus] = EventStream
    .merge(
      refetchStatusBus.events,
      EventStream.periodic(20000).map(_ => ())
    )
    .flatMap(_ => EventStream.fromFuture(commando.rpc("openchain-status")))
    .map(_.as[MinerStatus].toTry.getOrElse(MinerStatus.empty))
    .toSignal(MinerStatus.empty)

  val activeInvoice: Var[Option[MinerInvoice]] = Var(None)
  val nextTx: Var[Option[String]] = Var(None)
  val nextFee: Var[Int] = Var(0)

  val invoiceStatusesRestartBus = new EventBus[Unit]
  val invoiceStatusesResultBus = new EventBus[Unit]
  val invoiceStatus: Signal[String] = EventStream
    .merge(
      EventStream
        .combine(
          activeInvoice.signal.changes,
          EventStream
            .merge(EventStream.fromValue(()), invoiceStatusesResultBus.events)
        )
        .collect { case (Some(mi), _) => mi.hash }
        .flatMap { hash =>
          EventStream.fromFuture(
            commando
              .rpc("openchain-waitinvoice", Map("payment_hash" -> hash.asJson))
              .map(
                _.hcursor
                  .downField("status")
                  .as[String]
                  .toTry
                  .getOrElse("holding")
              )
          )
        },
      invoiceStatusesRestartBus.events.map(_ => "waitingpayment")
    )
    .map(_.tap(println(_)))
    .toSignal("waitingpayment")

  def render(): HtmlElement =
    div(
      // only restart the invoice awaiter when "holding"
      invoiceStatus.changes
        .collect { case "holding" => () } --> invoiceStatusesResultBus.writer,
      // when a tx changes status refetch miner global settings
      invoiceStatus.changes.map(_ => ()) --> refetchStatusBus.writer,
      // when a tx goes resolved, refetch status and assets
      invoiceStatus.changes.collect { case "resolved" => () }
        --> Main.refetchInfoBus.writer,

      // ~
      cls := "mr-3 my-3 bg-sky-600 text-white rounded-md shadow-lg w-auto max-w-xs relative",
      minWidth := "15rem",
      div(
        cls := "py-3 px-4",
        div(
          cls := "py-2",
          cls := "text-xl text-bold",
          "Miner"
        ),
        div(
          cls := "py-2",
          div(cls := "text-xl text-ellipsis overflow-hidden", pubkey),
          div(cls := "text-lg", host)
        )
      ),
      child <-- status.map(_.isConnected).map {
        case true => renderConnected()
        case false =>
          div(
            cls := "pt-6 w-full h-full rounded-md flex justify-center absolute inset-0 uppercase text-xl font-bold z-10",
            backgroundColor := "rgba(1, 1, 1, 0.5)",
            "disconnected"
          )
      },
      div(
        cls := "top-2.5 right-2.5 absolute flex items-center justify-center w-6 h-6 cursor-pointer rounded-lg z-20 hover:bg-red-200",
        onClick.preventDefault --> { _ =>
          Miner.loadMiners().onComplete {
            case Failure(err) =>
              println(s"failed to load miners: $err")
            case Success(miners) =>
              val newMiners = miners.filterNot(_ == this)
              Miner.storeMiners(newMiners)
              Main.miners.set(newMiners)
          }
        },
        "×"
      )
    )

  def renderConnected(): HtmlElement =
    div(
      // effects:
      // when a transaction is generated, force the miner value to it,
      Main.concreteTxToMine.map(Some(_)) --> nextTx,
      //   erase whatever invoice status we had before
      Main.concreteTxToMine.map(_ => ()) --> invoiceStatusesRestartBus,
      //   and hide whatever invoice we had before
      Main.concreteTxToMine --> activeInvoice.writer.contramap(_ => None),

      // ~
      cls := "py-3 px-4",
      div(
        cls := "mb-3",
        div(
          b("pending txs: "),
          child <-- status.map(_.pendingTxs.size)
        ),
        div(
          b("accumulated fees: "),
          child <-- status.map(_.accFees)
        ),
        div(
          b("last bitcoin tx: "),
          child <-- status.map(
            _.last_published_txid
              .map(txid =>
                a(
                  href := s"${Main.txExplorerUrl}$txid",
                  target := "_blank",
                  cls := "text-lg text-sky-100 hover:text-sky-300",
                  s"${txid.take(4)}...${txid.takeRight(4)}"
                )
              )
              .getOrElse(span())
          )
        )
      ),
      child <-- activeInvoice.signal.map {
        case Some(inv) =>
          div(
            child <-- invoiceStatus.map(status =>
              div(
                b("status: "),
                div(cls := "inline", fontFamily := "monospace", status)
              )
            ),
            div(
              cls := "relative",
              child <-- invoiceStatus.map {
                case "waitingpayment" =>
                  renderInvoice(inv)
                case "holding" =>
                  div(cls := "block h-64 w-1/2 bg-yellow-300")
                case "failed" | "unexpected" | "givenup" =>
                  div(cls := "block h-64 w-full bg-red-300")
                case "resolved" =>
                  div(cls := "block h-64 w-full bg-green-300")
              }
            )
          )
        case None =>
          renderForm()
      }
    )

  def renderInvoice(inv: MinerInvoice): HtmlElement =
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

  def renderForm(): HtmlElement =
    form(
      onSubmit.preventDefault --> { _ =>
        commando
          .rpc(
            "openchain-invoice",
            Map(
              "tx" -> nextTx.now().get.asJson,
              "msatoshi" -> nextFee.now().asJson
            )
          )
          .map(_.as[MinerInvoice].toTry.get)
          .onComplete {
            case Success(inv) =>
              activeInvoice.set(Some(inv))
              nextTx.set(None)
            case Failure(err) =>
              println(s"error getting invoice from miner: $err")
          }
      },
      label(
        cls := "block mb-1",
        "tx: ",
        textArea(
          cls := "block text-black px-1 h-32 w-full",
          controlled(
            value <-- nextTx.signal.map(_.getOrElse("")),
            onInput.mapToValue.map(Some(_)) --> nextTx.writer
          )
        )
      ),
      label(
        cls := "block",
        "fee to pay (sat): ",
        input(
          cls := "block text-black px-1 w-full",
          onInput.mapToValue --> nextFee.writer
            .contramap[String](_.toIntOption.map(_ * 1000).getOrElse(0))
        )
      ),
      div(
        cls := "mt-1 flex justify-end",
        button(
          cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
          disabled <-- Signal
            .combine(nextTx.signal, nextFee.signal)
            .map(_.isEmpty && _ > 0),
          "publish"
        )
      )
    )
}

object Miner {
  def loadMiners(): Future[List[Miner]] =
    js.Dynamic.global
      .loadMiners()
      .asInstanceOf[js.Promise[String]]
      .toFuture
      .map(parse(_).toTry.get)
      .map(_.as[List[Miner]].toTry.getOrElse(List.empty))

  def storeMiners(miners: List[Miner]): Unit =
    js.Dynamic.global.storeMiners(miners.asJson.toString)

  val nextMinerPubKey: Var[String] = Var("")
  val nextMinerHost: Var[String] = Var("")
  val nextMinerRune: Var[String] = Var("")

  def renderAddMinerForm(): HtmlElement =
    div(
      cls := "my-3 px-4 py-2 bg-orange-600 text-white rounded-md shadow-lg w-auto max-w-xs",
      div(
        cls := "py-2 text-xl",
        "Add Miner"
      ),
      form(
        onSubmit.preventDefault --> { _ =>
          Miner.loadMiners().onComplete {
            case Failure(err) =>
              println(s"failed to load miners: $err")
            case Success(miners) =>
              val miner = Miner(
                nextMinerPubKey.now(),
                nextMinerHost.now(),
                nextMinerRune.now()
              )
              val newMiners = miners :+ miner
              Miner.storeMiners(newMiners)
              Main.miners.set(newMiners)
          }
        },
        label(
          cls := "block mb-1",
          "pubkey: ",
          input(
            cls := "block text-black px-1 w-full",
            onInput.mapToValue --> nextMinerPubKey.writer
          )
        ),
        label(
          cls := "block mb-1",
          "host (with port): ",
          input(
            cls := "block text-black px-1 w-full",
            onInput.mapToValue --> nextMinerHost.writer
          )
        ),
        label(
          cls := "block mb-1",
          "rune: ",
          input(
            cls := "block text-black px-1 w-full",
            onInput.mapToValue --> nextMinerRune.writer
          )
        ),
        div(
          cls := "mt-1 flex justify-end",
          button(
            cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
            disabled <-- Signal
              .combine(
                nextMinerPubKey.signal,
                nextMinerHost.signal,
                nextMinerRune.signal
              )
              .map(_ == "" || _ == "" || _ == ""),
            "add"
          )
        )
      )
    )
}

case class MinerStatus(
    pendingTxs: List[String],
    accFees: Int,
    last_published_txid: Option[String]
) {
  def isConnected = accFees >= 0
}

object MinerStatus {
  given Decoder[MinerStatus] = new Decoder[MinerStatus] {
    final def apply(c: HCursor): Decoder.Result[MinerStatus] =
      for {
        pendingTxs <- c.downField("pending_txs").as[List[String]]
        accFees <- c.downField("acc_fees").as[Int]
        last = c.downField("last_published_txid")
        lastTx = last.as[String].map(Some(_)).getOrElse(None)
      } yield MinerStatus(pendingTxs, accFees, lastTx)
  }

  def empty = MinerStatus(List.empty, -1, None)
}

case class MinerInvoice(
    invoice: String,
    hash: String
)
