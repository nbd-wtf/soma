import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@JSImport("hyperswarm", JSImport.Default)
class Hyperswarm extends js.Object {
  def join(topic: Uint8Array): Unit = js.native
  def on(event: String, cb: js.Function): Unit = js.native
}

@js.native
trait Conn extends js.Object {
  def on(event: String, cb: js.Function): Unit = js.native
  def write(data: Uint8Array): Unit = js.native
  def end(): Unit = js.native
}
