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
    Database.getBlocksAtHeight(height) match {
      case block :: Nil if block.header.previous == bmmHash =>
        println(s"processing block at $height")

        if (Blockchain.validateBlock(block)) {
          // process this
          Database.processBlock(height, block)
          // ask for the next
          processBlocksFrom(height + 1, block.hash)
        } else {
          println(s"block at $height is invalid, stopping here")
          started = false
        }
      case Nil =>
        // no blocks after this one, we'll stop here and wait for a new block to arrive
        started = false
      case _ =>
        // multiple blocks or a block that has an unexpected bmm hash, means we got a chain split
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
    }
  }
}
