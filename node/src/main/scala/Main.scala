import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import util.chaining.scalaUtilChainingOps
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}
import scoin.ByteVector32

object Main {
  def main(args: Array[String]): Unit = {
    Database.init()
    BitcoinManager.start()
    PeerManager.start()
    StateManager.start()
    HttpServer.start()
  }

  // ~
  // do this so SecureRandom from scoin works on ESModule
  @js.native
  @JSImport("crypto", JSImport.Namespace)
  val crypto: js.Dynamic = js.native
  private val g = scalajs.js.Dynamic.global.globalThis
  g.crypto = crypto
  // until https://github.com/scala-js/scala-js-java-securerandom/issues/8 is fixed
  // ~
}

object PeerManager {
  var peers: List[Conn] = List.empty
  val h = new Hyperswarm()

  def start(): Unit = {
    h.join(ByteVector.fromValidHex(Config.genesisTx).toUint8Array)
    h.on("connection", onConnection)
  }

  def onConnection(peer: Conn): Unit = {
    println(s"got connection")
    peers = peers :+ peer

    peer.on("data", msg => onPeerMessage(peer, msg))
    peer.on("error", (_: Any) => onPeerDisconnect(peer))
    peer.on("close", (_: Any) => onPeerDisconnect(peer))

    // try to get blocks we don't have (but we have the bmm hash)
    Database
      .getMissingBlocks()
      .foreach(bmmHash =>
        peer.write(
          WireMessage.codec
            .encode(RequestBlock(bmmHash))
            .toOption
            .get
            .toByteVector
            .toUint8Array
        )
      )
  }

  def onPeerDisconnect(peer: Conn): Unit = {
    peers = peers.filter(_ != peer)
  }

  def onPeerMessage(peer: Conn, data: Uint8Array): Unit = {
    val msg = ByteVector.fromUint8Array(data)
    WireMessage.codec.decode(msg.toBitVector) match {
      case Attempt.Successful(DecodeResult(value, _)) => {
        println(s"got message: $value")
        value match {
          case RequestBlock(hash) =>
            peer.write(
              WireMessage.codec
                .encode(
                  AnswerBlock(
                    hash,
                    Database.getBlock(hash)
                  )
                )
                .toOption
                .get
                .toByteVector
                .toUint8Array
            )

          case AnswerBlock(hash, Some(block))
              if block.hash != hash || !block.validate() =>
            println(
              s"got an invalid block for ${hash.toHex}: ${block.hash} != $hash OR valid? ${block.validate()}"
            )

          case AnswerBlock(hash, Some(block)) =>
            val ok = Database.insertBlock(hash, block)
            if (ok) println("block inserted") else println("failed to insert")

          case AnswerBlock(hash, None) => // peer doesn 't have this block
        }
      }
      case Attempt.Failure(err) => println(s"got unknown message $msg")
    }
  }

  def sendAll(data: ByteVector): Unit = {
    peers.foreach { _.write(data.toUint8Array) }
  }
}

object BitcoinManager {
  def start(): Unit = {
    // start scanning at the genesis tx if we don't have anything in the database
    val (txid, bmmHeight, bmmHash) =
      Database.getLatestTx().getOrElse((Config.genesisTx, 1, None))

    for {
      tipTx <- BitcoinRPC.call(
        "getrawtransaction",
        ujson.Arr(txid, 2)
      )
      btcBlock <- BitcoinRPC.call("getblock", ujson.Arr(tipTx("blockhash").str))
    } yield {
      Database.addTx(bmmHeight, tipTx("txid").str, getBmmHash(tipTx.obj))
      inspectNextBlocks(
        bmmHeight + 1,
        tipTx("txid").str,
        btcBlock("height").num.toInt + 1
      )
    }
  }

  def getBmmHash(tx: ujson.Obj): Option[ByteVector32] = {
    if (
      tx("vout").arr.size > 1 && tx("vout")(1)("scriptPubKey")("asm").str
        .startsWith("OP_RETURN")
    ) {
      val hex = tx("vout")(1)("scriptPubKey")("asm").str.split(" ")(1)
      val bmmHash = ByteVector.fromValidHex(hex)
      if (bmmHash.size == 32) Some(ByteVector32(bmmHash)) else None
    } else None
  }

  def inspectNextBlocks(bmmHeight: Int, tipTxid: String, height: Int): Unit =
    BitcoinRPC
      .call("getchaintips")
      .foreach { tips =>
        if (tips(0)("height").num.toInt < height) {
          js.timers.setTimeout(10000) {
            inspectNextBlocks(bmmHeight, tipTxid, height)
          }
        } else {
          // print(s"inspecting bitcoin block $height... ")
          BitcoinRPC
            .call("getblockhash", ujson.Arr(height))
            .map(_.str)
            .flatMap { hash =>
              BitcoinRPC.call("getblock", ujson.Arr(hash, 2))
            }
            .foreach { block =>
              block("tx").arr
                .drop(1) // drop coinbase
                .find(
                  _("vin")(0).pipe(fvin =>
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

                  // give some time for block to go from miner to their node before we request
                  bmmHashOpt.foreach { bmmHash =>
                    js.timers.setTimeout(5000) {
                      PeerManager.sendAll(
                        WireMessage.codec
                          .encode(RequestBlock(bmmHash))
                          .toOption
                          .get
                          .toByteVector
                      )
                    }
                  }
                  Future { inspectNextBlocks(bmmHeight + 1, txid, height + 1) }
                case None =>
                  // println("didn't find")
                  Future { inspectNextBlocks(bmmHeight, tipTxid, height + 1) }
              }
            }
        }
      }
}

object StateManager {
  def start(): Unit = {
    val (height, bmmHash) = Database.getCurrentTip()
    processBlocksFrom(height + 1, bmmHash)
  }

  def processBlocksFrom(height: Int, bmmHash: ByteVector32): Unit = {
    Database.getBlockAtHeight(height) match {
      case Some(block) if (block.header.previous == bmmHash) => {
        println(s"processing block at $height")

        // process this
        Database.processBlock(block)

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
              .foreach { Database.processBlock(_) }

            // now proceed from there
            processBlocksFrom(highestHeight + 1, blocks.last.hash)
        }
      case None =>
        println(s"no block at $height")
        println(s"  waiting a little before trying again")
        js.timers.setTimeout(60000) {
          processBlocksFrom(height, bmmHash)
        }
    }
  }
}
