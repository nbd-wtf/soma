import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.scalanative.loop.Timer

object Helpers {
  class DebouncedFunctionCanceled
      extends Exception("debounced function canceled")

  def debounce[A, B](
      fn: Function[A, B],
      duration: FiniteDuration
  ): Function[A, Future[B]] = {
    var timer = Timer.timeout(0.seconds) { () => }
    var promise = Promise[B]()

    def debounced(arg: A): Future[B] = {
      // every time the debounced function is called

      // clear the timeout that might have existed from before
      timer.clear()

      // fail the promise that might be pending from before
      //   a failed promise just means this call was canceled and replaced
      //   by a more a recent one
      if (!promise.isCompleted) promise.failure(new DebouncedFunctionCanceled)

      // create a new promise and a new timer
      promise = Promise[B]()
      timer = Timer.timeout(duration) { () =>
        // actually run the function when the timer ends
        promise.success(fn(arg))
      }

      // if this was the last time this function was called in rapid succession
      //   the last promise will be fulfilled
      promise.future
    }

    debounced
  }
}
