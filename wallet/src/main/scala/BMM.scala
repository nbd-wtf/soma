import scala.util.chaining._
import scala.concurrent.Future
import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import com.raquo.laminar.api.L._
import sttp.client3._
import io.circe._
import io.circe.syntax._
import io.circe.parser._

object BMM {

  def render(): HtmlElement =
    div(
      cls := "mr-3 py-3 px-4 my-3 bg-orange-700 text-white rounded-md shadow-lg w-auto",
      div(
        cls := "py-2",
        cls := "text-xl text-ellipsis overflow-hidden",
        "Blind Mining"
      ),
      div(
        cls := "mb-3",
        div(
          b("Last Bitcoin Tx: "),
          child <-- Main.info.map(
            _.latestBmmTx.txid.pipe(txid =>
              a(
                href := s"https://mempool.space/tx/$txid",
                target := "_blank",
                cls := "text-lg text-sky-100 hover:text-sky-300",
                s"${txid.take(4)}...${txid.takeRight(4)}"
              )
            )
          )
        ),
        div(
          b("Mined Hash: "),
          child <-- Main.info.map(
            _.latestBmmTx.bmmHash
              .map(h => s"${h.take(4)}...${h.takeRight(4)}")
              .getOrElse("<none>")
              .pipe(t => code(t))
          )
        )
      )
    )

  def renderAsset(asset: String): HtmlElement =
    div(
      "\"",
      code(cls := "text-teal-300", asset),
      "\""
    )
}
