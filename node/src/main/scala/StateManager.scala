import scala.util.chaining.*
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import scoin.ByteVector32
import soma.*

object StateManager {
  private var started = false

  def start(): Unit = if (!started) {
    started = true
    js.timers.setTimeout(1) {
      val (height, bmmHash) = Database.getCurrentTip()
      processBlocksFrom(height + 1, bmmHash)
    }
  }

  private def processBlocksFrom(height: Int, bmmHash: ByteVector32): Unit = {
    Database.getBlockAtHeight(height) match {
      case Some(block) if (block.header.previous == bmmHash) => {
        println(s"processing block at $height")

        // process this
        Database.processBlock(height, block)

        // ask for the next
        processBlocksFrom(height + 1, block.hash)
      }
      case Some(block) =>
        // block's previous is a different hash than the current, i.e. we have a chain split
        println(s"chain split at $height!")
        // instead of walking in the dark let's check if we have a winner from the split after all
        Database.getUniqueHighestBlockHeight() match {
          case None =>
            // none? there must be two candidates or more, so let's wait
            println("  waiting a little for the split to resolve itself")
            js.timers.setTimeout(60000) {
              processBlocksFrom(height, bmmHash)
            }
          case Some(highestHeight) =>
            println(
              s"  the highest height we have is $highestHeight, getting the common blocks between here and there"
            )
            val blocks = Database
              .getBlocksBetweenHereAndThere(height, bmmHash, highestHeight)

            // process all these in order
            blocks
              .zip(List.range(height + 1, highestHeight + 1))
              .foreach((block, height) => Database.processBlock(height, block))

            // now proceed from there
            processBlocksFrom(highestHeight + 1, blocks.last.hash)
        }
      case None =>
        // no blocks after this one, we'll stop here and wait for a new block to arrive
        started = false
    }
  }
}
