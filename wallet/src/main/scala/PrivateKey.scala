import scala.util.Random
import org.scalajs.dom
import com.raquo.laminar.api.L._

object PrivateKey {
  lazy val key: String = {
    Option(dom.window.localStorage.getItem("key")) match {
      case Some(key) => key
      case None => {
        val k = List
          .fill[Int](32)(Random.nextInt(16))
          .map(_.toHexString)
          .map(x => if (x.size == 2) x else s"0$x")
          .mkString
        dom.window.localStorage.setItem("key", k)
        k
      }
    }
  }

  def render(): HtmlElement =
    div(
      cls := "py-3 px-4 my-3 bg-red-800 text-white rounded-md shadow-lg w-auto",
      div(
        cls := "py-2",
        cls := "text-xl text-ellipsis overflow-hidden",
        "Keys"
      ),
      div(b("Private Key: "), code(key))
    )
}
