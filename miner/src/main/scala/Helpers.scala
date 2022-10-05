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

  class PromisePool[A] {
    val pool = scala.collection.mutable.Map.empty[String, Set[Promise[A]]]

    // we keep this so people that arrive after we have just resolved a promise
    //   can get a response immediately
    val responseCache = scala.collection.mutable.Map.empty[String, A]

    def add(key: String): Future[A] = {
      responseCache.get(key) match {
        case None =>
          val p = Promise[A]()
          pool.updateWith(key) {
            case None           => Some(Set(p))
            case Some(promises) => Some(promises + p)
          }

          // this future will always resolve when the status changes
          p.future

        case Some(v) =>
          // this is final and we have a cached value
          Future.successful(v)
      }
    }

    def resolve(key: String, value: A, isFinal: Boolean = false): Unit = {
      pool.updateWith(key) {
        case None => None
        case Some(promises) =>
          promises.foreach(_.success(value))
          None
      }

      // regardless of whether we have listeners or not we should keep this
      //   for the listeners that may appear later
      if (isFinal)
        responseCache += (key -> value)
    }

    // every once in a while delete response caches
    Timer.repeat(3.minutes) { () =>
      val keysToDelete = responseCache.keySet

      Timer.timeout(20.seconds) { () =>
        keysToDelete.foreach { responseCache.remove(_) }
      }
    }
  }
}
