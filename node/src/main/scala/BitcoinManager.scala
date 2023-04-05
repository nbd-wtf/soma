import scala.util.chaining.*
import scala.concurrent.{Future, Promise}
import concurrent.duration.*
import scala.util.{Success, Failure}
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import scodec.bits.ByteVector
import scoin.ByteVector32

object BitcoinManager {
  private var p = Promise[Unit]()
  private var startedP = Future.successful(())
  private var lastBlockScanned: Option[Int] = None

  // start returns a future that resolves once there are not more bitcoin blocks to scan
  def start(): Future[Unit] = {
    p = Promise[Unit]()
    startedP = p.future

    // start scanning at the genesis tx if we don't have anything in the database
    val (txid, bmmHeight, bmmHash) =
      Database.getLatestTx().getOrElse((Config.genesisTx, 0, None))

    (for {
      tipTx <- BitcoinRPC.call(
        "getrawtransaction",
        ujson.Arr(txid, 2)
      )
      scanFromHeight <- lastBlockScanned
        .map(last => Future(last + 1))
        .getOrElse(
          BitcoinRPC
            .call(
              "getblock",
              ujson.Arr(tipTx("blockhash").str)
            )
            .map(_("height").num.toInt + 1)
        )
    } yield {
      Database.addTx(bmmHeight, tipTx("txid").str, getBmmHash(tipTx.obj))
      inspectNextBlocks(
        bmmHeight + 1,
        tipTx("txid").str,
        scanFromHeight
      )
    }).onComplete {
      case Success(_) =>
      case Failure(err) =>
        println(s"failed to get genesis transaction: $err")
    }

    startedP.andThen { _ =>
      js.timers.setTimeout(25.seconds) { start() }
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
          //   stop here (will be tried again in 25 seconds -- or when required)
          println(s"block $height is still not mined on bitcoin, waiting...")
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
              lastBlockScanned = Some(height)
              block("tx").arr
                .drop(1) // drop coinbase
                .find(tx =>
                  tx("vin")(0).pipe(fvin =>
                    // it's always the first output and first input
                    fvin("txid").str == tipTxid && fvin("vout").num == 0
                  )
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
