import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import org.scalajs.dom
import com.raquo.laminar.api.L._
import soma._

object AssetView {
  val assets: Signal[Map[String, String]] = Main.info.changes
    .flatMap(_ => EventStream.fromFuture(Node.listAllAssets()))
    .toSignal(Map.empty)

  def render(): HtmlElement =
    div(
      cls := "my-3",
      div(cls := "text-xl", "assets"),
      div(
        cls := "flex flex-wrap bg-amber-100 text-black pl-2 pt-2",
        children <-- assets.map(
          _.map(renderAsset(_, _)).toList
        )
      )
    )

  def renderAsset(asset: String, owner: String): HtmlElement =
    div(
      cls := "mb-2 mr-2 py-3 pl-2 pr-2 border-2 border-indigo-400",
      styleAttr := "max-width: 45%",
      div(
        cls := "w-full",
        "asset: ",
        div(
          cls := s"text-indigo-600 w-full text-ellipsis overflow-hidden ml-1",
          asset
        )
      ),
      div(
        cls := "w-full",
        "owner: ",
        div(
          cls := s"text-blue-600 w-full text-ellipsis overflow-hidden ml-1",
          owner
        )
      )
    )
}
