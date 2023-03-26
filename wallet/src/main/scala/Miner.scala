import scala.util.{Success, Failure, Try}
import scala.util.chaining.*
import scala.concurrent.duration.{span => *, *}
import scala.concurrent.Future
import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import sttp.model.Uri
import com.raquo.laminar.api.L.*
import io.circe.*
import io.circe.syntax.*
import io.circe.parser.*
import io.circe.generic.auto.*
import scodec.bits.{ByteVector, BitVector}
import scoin.{Satoshi, NumericSatoshi, ByteVector32}
import soma.*

case class Miner(pubkey: String, host: String, rune: String) {
  val commando = new Commando(pubkey, host, rune)

  val refetchStatusBus = new EventBus[Unit]
  val status: Signal[MinerStatus] = EventStream
    .merge(
      refetchStatusBus.events,
      EventStream.periodic(5000).map(_ => ())
    )
    .flatMap(_ => EventStream.fromFuture(commando.rpc("soma-status")))
    .map(_.as[MinerStatus].toTry.getOrElse(MinerStatus.empty))
    .toSignal(MinerStatus.empty)

  val nextTx: Var[Option[Tx]] = Var(None)
  val nextFee: Var[Int] = Var(0)
  val activeInvoice: Var[Option[(ByteVector32, MinerInvoice)]] = Var(None)
  val invoicePaid =
    activeInvoice.signal.changes
      .flatMap {
        case Some(txid, _) => status.changes.map(_.isTxPaid(txid))
        case None          => EventStream.fromValue(false)
      }
      .toSignal(false)

  def render(): HtmlElement =
    div(
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
      //   and hide whatever invoice we had before
      Main.concreteTxToMine --> activeInvoice.writer.contramap(_ => None),

      // ~
      cls := "py-3 px-4",
      div(
        cls := "mb-3",
        div(
          b("Pending Txs: "),
          child <-- status.map(_.pendingTxs.size)
        ),
        div(
          b("Accumulated Fees: "),
          child <-- status.map(_.accFees.toString)
        ),
        div(
          b("Last Bitcoin Tx Published: "),
          child <-- status.map(
            _.lastPublishedTxid
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
      child <-- Signal
        .combine(activeInvoice.signal, invoicePaid)
        .map {
          case (Some(txid, inv), false) => renderInvoice(txid, inv)
          case _                        => renderForm()
        },
      div(
        h2(cls := "text-lg my-1", "Last Transactions:"),
        div(
          children <-- Signal
            .combine(activeInvoice.signal, invoicePaid)
            .map {
              case (Some(txid, _), false) =>
                Some(renderInflight(txid, Status.Unpaid))
              case _ => None
            }
            .map(_.toList),
          children <-- status.map(st =>
            st.pendingTxs.values
              .filterNot((tx, _) =>
                st.lastPublishedBlock.map(_.txs.contains(tx)).getOrElse(false)
              )
              .filterNot((tx, _) =>
                st.lastRegisteredBlock.map(_.txs.contains(tx)).getOrElse(false)
              )
              .map((tx, sats) => renderInflight(tx.hash, Status.Pending))
              .toList
          ),
          children <-- status.map(st =>
            st.lastPublishedBlock.toList
              .flatMap(
                _.txs
                  .filterNot(tx =>
                    st.lastRegisteredBlock
                      .map(_.txs.contains(tx))
                      .getOrElse(false)
                  )
                  .map(tx => renderInflight(tx.hash, Status.Unconfirmed))
              )
          ),
          children <-- status.map(
            _.lastRegisteredBlock.toList
              .flatMap(
                _.txs.map(tx => renderInflight(tx.hash, Status.Confirmed))
              )
          )
        )
      )
    )

  enum Status(val classes: String):
    case Unpaid extends Status("bg-amber-100 w-1/6")
    case Pending extends Status("bg-yellow-300 w-1/4")
    case Unconfirmed extends Status("bg-cyan-400 w-3/4")
    case Confirmed extends Status("bg-emerald-600 w-full")

  def renderInflight(txid: ByteVector32, status: Status): HtmlElement =
    div(
      cls := "w-full rounded mb-1",
      div(cls := status.classes, cls := "h-3"),
      div(
        cls := "text-ellipsis overflow-hidden -mt-1",
        txid.toHex
      )
    )

  def renderInvoice(txid: ByteVector32, inv: MinerInvoice): HtmlElement =
    div(
      h2(
        cls := "text-lg my-1 break-normal text-ellipsis overflow-hidden",
        s"Invoice for ${txid}"
      ),
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
            "soma-invoice",
            Map(
              "tx" -> nextTx.now().get.encoded.toHex.asJson,
              "msatoshi" -> nextFee.now().asJson
            )
          )
          .map(_.as[MinerInvoice].toTry.get)
          .onComplete {
            case Success(inv) =>
              activeInvoice.set(Some((nextTx.now().get.hash, inv)))
              nextTx.set(None)
            case Failure(err) =>
              println(s"error getting invoice from miner: $err")
          }
      },
      h2(cls := "text-lg my-1", "Publish Arbitrary Transaction:"),
      label(
        cls := "block mb-1",
        "tx hex: ",
        textArea(
          cls := "block text-black px-1 h-32 w-full",
          controlled(
            value <-- nextTx.signal.map(_.map(_.encoded.toHex).getOrElse("")),
            onInput.mapToValue
              .map(ByteVector.fromHex(_))
              .map(_.map(_.toBitVector))
              .collect { case Some(v) => v }
              .map(Tx.codec.decodeValue(_).toOption) --> nextTx.writer
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
      .map { miners =>
        if (miners.size > 0) miners
        else
          Uri
            .parse(
              js.Dynamic.global.DEFAULT_MINER
                .asInstanceOf[String]
                .pipe(s => s"tcp://$s")
            )
            .toOption
            .flatMap(uri =>
              Try(
                List(
                  Miner(
                    uri.userInfo.get.username,
                    s"${uri.host.get}:${uri.port.get}",
                    uri.userInfo.get.password.get
                  )
                )
              ).toOption
            )
            .getOrElse(List.empty)
      }

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
    isConnected: Boolean,
    pendingTxs: Map[String, (Tx, Satoshi)],
    lastPublishedTxid: Option[String],
    lastPublishedBlock: Option[Block],
    lastRegisteredBlock: Option[Block]
) {
  def accFees = pendingTxs.values.map(_._2).sum

  def isTxPaid(txid: ByteVector32): Boolean =
    pendingTxs.contains(txid.toHex) || lastPublishedBlock
      .map(_.txs.exists(_.hash == txid))
      .getOrElse(false) || lastRegisteredBlock
      .map(_.txs.exists(_.hash == txid))
      .getOrElse(false)
}

object MinerStatus {
  given Decoder[MinerStatus] = new Decoder[MinerStatus] {
    final def apply(c: HCursor): Decoder.Result[MinerStatus] = Try(
      MinerStatus(
        true,
        c
          .downField("pending_txs")
          .as[Map[String, Json]]
          .toTry
          .get
          .view
          .mapValues { txsats =>
            val tc = txsats.hcursor
            val txHex = tc.downN(0).as[String].toTry.get
            val sats = tc.downN(1).as[Long].toTry.get
            (
              Tx.codec.decode(BitVector.fromValidHex(txHex)).require.value,
              Satoshi(sats)
            )
          }
          .toMap,
        c
          .downField("last_published_txid")
          .as[String]
          .map(Some(_))
          .getOrElse(None),
        c
          .downField("last_published_block")
          .as[String]
          .map(Some(_))
          .getOrElse(None)
          .map(ByteVector.fromValidHex(_).toBitVector)
          .flatMap(Block.codec.decode(_).toOption)
          .map(_.value),
        c
          .downField("last_registered_block")
          .as[String]
          .map(Some(_))
          .getOrElse(None)
          .map(ByteVector.fromValidHex(_).toBitVector)
          .flatMap(Block.codec.decode(_).toOption)
          .map(_.value)
      )
    ) match {
      case Success(v) => Right(v)
      case Failure(err) =>
        Left(
          DecodingFailure(
            DecodingFailure.Reason.CustomReason(s"invalid data: $err"),
            c
          )
        )
    }
  }

  def empty = MinerStatus(false, Map.empty, None, None, None)
}

case class MinerInvoice(
    invoice: String,
    hash: String
)
