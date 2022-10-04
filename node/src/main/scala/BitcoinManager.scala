import scala.util.chaining._
import scala.concurrent.{Future, Promise}
import concurrent.duration._
import scala.util.{Success, Failure}
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scodec.bits.ByteVector
import scoin.ByteVector32

object BitcoinManager {
  private var p = Promise[Unit]()
  private var startedP = p.future // never completes

  private def started =
    !startedP.isCompleted // if completed means we're not working

  // start returns a future that resolves once there are not more bitcoin blocks to scan
  def start(): Future[Unit] =
    if (started) Future.successful(())
    else {
      p = Promise[Unit]()
      startedP = p.future

      // start scanning at the genesis tx if we don't have anything in the database
      val (txid, bmmHeight, bmmHash) =
        Database.getLatestTx().getOrElse((Config.genesisTx, 1, None))

      (for {
        tipTx <- BitcoinRPC.call(
          "getrawtransaction",
          ujson.Arr(txid, 2)
        )
        btcBlock <- BitcoinRPC.call(
          "getblock",
          ujson.Arr(tipTx("blockhash").str)
        )
      } yield {
        println(s"starting scan from ${tipTx("txid").str}")
        Database.addTx(bmmHeight, tipTx("txid").str, getBmmHash(tipTx.obj))
        inspectNextBlocks(
          bmmHeight + 1,
          tipTx("txid").str,
          btcBlock("height").num.toInt + 1
        )
      }).onComplete {
        case Success(_) =>
        case Failure(err) =>
          println(s"failed to get genesis transaction: $err")
      }

      startedP.andThen { _ =>
        // schedule to retry in 15 seconds
        js.timers.setTimeout(15.seconds) { start() }
      }
    }

  private def getBmmHash(tx: ujson.Obj): Option[ByteVector32] = {
    if (
      tx("vout").arr.size > 1 && tx("vout")(1)("scriptPubKey")("asm").str
        .startsWith("OP_RETURN")
    ) {
      val hex = tx("vout")(1)("scriptPubKey")("asm").str.split(" ")(1)
      val bmmHash = ByteVector.fromValidHex(hex)
      if (bmmHash.size == 32) Some(ByteVector32(bmmHash)) else None
    } else None
  }

  private def inspectNextBlocks(
      bmmHeight: Int,
      tipTxid: String,
      height: Int
  ): Unit =
    BitcoinRPC
      .call("getchaintips")
      .foreach { tips =>
        if (tips(0)("height").num.toInt < height) {
          // we got to the tip (i.e. the requested height is greater than the chain tip)
          //   stop here (will be tried again in 15 seconds -- or when required)
          p.success(())
        } else {
          println(s"inspecting bitcoin block $height for bmm $bmmHeight... ")
          BitcoinRPC
            .call("getblockhash", ujson.Arr(height))
            .map(_.str)
            .flatMap { hash =>
              BitcoinRPC.call("getblock", ujson.Arr(hash, 2))
            }
            .foreach { block =>
              block("tx").arr
                .drop(1) // drop coinbase
                .find(tx =>
                  tx("vin")(0).pipe(fvin =>
                    fvin("txid").str == tipTxid &&
                      // at the bmm transaction that spends from genesis the output
                      //   may be at any index; but after that it will always be the first
                      (if bmmHeight == 2 then true else fvin("vout").num == 0)
                  ) && tx("vout")(0)("value").num == 0.00000738
                ) match {
                case Some(foundTx) =>
                  val txid = foundTx("txid").str
                  println(
                    s"found bmm $txid at bitcoin block $height, this is at bmm height $bmmHeight"
                  )
                  val bmmHashOpt = getBmmHash(foundTx.obj)
                  Database.addTx(bmmHeight, txid, bmmHashOpt)

                  Future { inspectNextBlocks(bmmHeight + 1, txid, height + 1) }
                case None =>
                  Future { inspectNextBlocks(bmmHeight, tipTxid, height + 1) }
              }
            }
        }
      }
}
