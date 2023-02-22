import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalanative.loop.EventLoop.loop

object Main {
  val logger = new nlog.Logger()

  val operational = Future
    .sequence(
      List(
        Datastore.loadingPendingTransactions,
        Datastore.loadingPendingBlocks
      )
    )
    .map(_ => ())

  def main(args: Array[String]): Unit = {
    CLN.run()
  }
}
