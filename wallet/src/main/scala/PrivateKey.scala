import scala.util.Random
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import com.raquo.laminar.api.L._

object PrivateKey {
  val key = EventStream
    .fromFuture {
      js.Dynamic.global
        .loadKey()
        .asInstanceOf[js.Promise[String]]
        .toFuture
        .map {
          case nothing if nothing == "" => {
            val k = List
              .fill[Int](32)(Random.nextInt(16))
              .map(_.toHexString)
              .map(x => if (x.size == 2) x else s"0$x")
              .mkString
            js.Dynamic.global.storeKey(k)
            k
          }
          case key => key
        }
    }
    .toSignal("")

  def render(): HtmlElement =
    div(
      cls := "py-3 px-4 my-3 bg-red-800 text-white rounded-md shadow-lg w-auto",
      div(
        cls := "py-2",
        cls := "text-xl text-ellipsis overflow-hidden",
        "Keys"
      ),
      div(
        b("Private Key: "),
        child <-- key.map(code(_))
      )
    )
}
