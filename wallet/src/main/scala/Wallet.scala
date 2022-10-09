import scala.concurrent.Future
import scala.util.chaining._
import scala.util.{Success, Failure}
import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scodec.bits.ByteVector
import com.raquo.laminar.api.L._
import sttp.client3._
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import scoin._
import scoin.Crypto.{XOnlyPublicKey, PrivateKey}
import openchain._

object Wallet {
  val assets = EventStream
    .combine(Main.info.changes, Keys.pubkey)
    .flatMap((info, pk) => EventStream.fromFuture(Node.getAssets(pk)))

  val transferringAsset: Var[Option[(AssetInfo, Option[XOnlyPublicKey])]] =
    Var(None)

  def render(): HtmlElement =
    div(
      cls := "mr-3 py-3 px-4 my-3 bg-cyan-700 text-white rounded-md shadow-lg w-auto",
      maxWidth := "396px",
      div(
        cls := "py-2",
        cls := "text-xl text-ellipsis overflow-hidden",
        "Wallet"
      ),
      div(
        cls := "mb-3",
        div(
          b("assets:"),
          code("["),
          children <-- assets
            .map(x => x.tap(println(_)))
            .map(_.map(renderAsset(_))),
          code("]")
        ),
        child <-- Main.txToMine.signal.map {
          case Some(tx) =>
            div(
              b("generated tx:"),
              div(
                cls := "p-2 whitespace-pre-wrap break-all",
                code(tx)
              )
            )
          case None => div()
        }
      ),
      child <-- transferringAsset.signal
        .map {
          case None =>
            div(
              cls := "mt-3 flex justify-center",
              button(
                cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
                onClick.preventDefault --> { _ =>
                  {
                    for {
                      pk <- Keys.pubkey.value.get
                      sk <- Keys.privkey.value.get
                    } yield {
                      val tx = mintAsset(pk)
                      Main.txToMine.set(Some(Tx.codec.encode(tx).require.toHex))
                    }
                  }
                },
                "mint new asset"
              )
            )
          case Some((AssetInfo(asset, counter), to)) =>
            renderTransferForm(asset, counter, to)
        }
    )

  def renderAsset(ai: AssetInfo): HtmlElement =
    code(
      cls := "block ml-3 text-ellipsis overflow-hidden",
      "\"",
      span(
        cls := "text-teal-300 cursor-pointer",
        onClick.preventDefault --> transferringAsset.writer.contramap { _ =>
          Main.txToMine.set(None)
          transferringAsset.now() match {
            case Some((curr, _)) if curr == ai => None
            case _                             => Some(ai, None)
          }
        },
        ai.asset.toHexString
      ),
      "\""
    )

  def renderMintForm(asset: ByteVector32): HtmlElement =
    form(
      div(
        cls := "mt-1 flex justify-end",
        button(
          cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
          "mint"
        )
      )
    )

  def renderTransferForm(
      asset: Int,
      counter: Int,
      to: Option[XOnlyPublicKey]
  ): HtmlElement =
    form(
      onSubmit.preventDefault --> { _ =>
        Keys.privkey.value.foreach { sk =>
          val tx = buildTx(
            asset,
            to.get,
            counter,
            sk.get
          )
          Main.txToMine.set(Some(Tx.codec.encode(tx).require.toHex))
        }
      },
      label(
        cls := "block",
        "asset id: ",
        input(
          cls := "block bg-gray-300 text-black px-1 w-full",
          readOnly := true,
          defaultValue := asset.toHexString
        )
      ),
      label(
        cls := "block",
        "transfer to: ",
        input(
          cls := "block text-black px-1 w-full",
          onInput.mapToValue
            .map(ByteVector.fromHex(_))
            .collect {
              case Some(b) if b.size == 32 =>
                Some(
                  (
                    AssetInfo(asset, counter),
                    Some(XOnlyPublicKey(ByteVector32(b)))
                  )
                )
            } --> transferringAsset.writer
        )
      ),
      div(
        cls := "mt-1 flex justify-end",
        button(
          cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
          "transfer"
        )
      )
    )

  def buildTx(
      asset: Int,
      to: XOnlyPublicKey,
      counter: Int,
      privateKey: PrivateKey
  ): Tx = Tx(
    asset = asset,
    to = to,
    from = privateKey.publicKey.xonly,
    counter = counter
  ).withSignature(privateKey)

  def mintAsset(to: XOnlyPublicKey): Tx = Tx(
    asset = 0,
    to = to,
    counter = 0,

    // make this random so the tx id is random
    from = XOnlyPublicKey(randomBytes32())
  )
}
