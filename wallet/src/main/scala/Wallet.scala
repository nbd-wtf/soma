import scala.concurrent.Future
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

object Wallet {
  val assets = EventStream
    .combine(Main.info.changes, Keys.pubkey)
    .flatMap((info, pk) => EventStream.fromFuture(Node.getAssets(pk)))

  val mintingAsset: Var[Option[ByteVector32]] = Var(
    Some(ByteVector32(Crypto.randomBytes(32)))
  )

  def render(): HtmlElement =
    div(
      cls := "mr-3 py-3 px-4 my-3 bg-cyan-700 text-white rounded-md shadow-lg w-auto",
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
          children <-- assets.map(_.map(renderAsset(_))),
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
      form(
        onSubmit.preventDefault --> { _ =>
          {
            for {
              asset <- mintingAsset.tryNow()
              pk <- Keys.pubkey.value.get
              sk <- Keys.privkey.value.get
            } yield {
              Node
                .buildTx(asset.get, ByteVector32(pk.value.drop(1)), sk)
                .onComplete {
                  case Success(v) =>
                    Main.txToMine.set(Some(v.tx))
                    mintingAsset.set(None)
                  case Failure(err) => println(s"fail to build tx: $err")
                }
            }
          }
        },
        label(
          cls := "block",
          "asset id: ",
          input(
            cls := "block text-black px-1 w-full",
            defaultValue := mintingAsset.now().get.toHex,
            onInput.mapToValue.setAsValue
              .map[Option[ByteVector32]](hex =>
                ByteVector
                  .fromHex(hex)
                  .flatMap(b =>
                    if b.size == 32 then Some(ByteVector32(b)) else None
                  )
              )
              --> mintingAsset.writer
          )
        ),
        div(
          cls := "mt-1 flex justify-end",
          button(
            cls := "p-1 pb-2 mt-2 bg-white text-black rounded-md shadow-lg",
            disabled <-- mintingAsset.signal.map(_.isEmpty),
            "Mint"
          )
        )
      )
    )

  def renderAsset(asset: String): HtmlElement =
    code(
      cls := "block ml-2",
      "\"",
      span(cls := "text-teal-300", asset),
      "\""
    )
}
