import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import util.chaining.scalaUtilChainingOps
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

object Main {
  def main(args: Array[String]): Unit = {
    Database.init()
    BitcoinManager.start()
    PeerManager.start()
    StateManager.start()
    HttpServer.start()
  }
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
              if block.validate() && block.header.hash == hash =>
            Database.insertBlock(hash, block)
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
    val (bmmHeight, txid) =
      Database.getLatestTx().getOrElse((1, Config.genesisTx))

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

  def getBmmHash(tx: ujson.Obj): Option[ByteVector] = {
    if (
      tx("vout").arr.size > 1 && tx("vout")(1)("scriptPubKey")("asm").str
        .startsWith("OP_RETURN")
    ) {
      val hex = tx("vout")(1)("scriptPubKey")("asm").str.split(" ")(1)
      val bmmHash = ByteVector.fromValidHex(hex)
      if (bmmHash.size == 32) Some(bmmHash) else None
    } else None
  }

  def inspectNextBlocks(bmmHeight: Int, tipTxid: String, height: Int): Unit =
    BitcoinRPC
      .call("getchaintips")
      .foreach { tips =>
        if (tips(0)("height").num.toInt < height) {
          // println("waiting for the next block")
          js.timers.setTimeout(60000) {
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
                  println(s"found $txid")
                  val bmmHashOpt = getBmmHash(foundTx.obj)
                  Database.addTx(bmmHeight, txid, bmmHashOpt)
                  bmmHashOpt.foreach { bmmHash =>
                    PeerManager.sendAll(
                      WireMessage.codec
                        .encode(RequestBlock(bmmHash))
                        .toOption
                        .get
                        .toByteVector
                    )
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
    val (bmmHeight, bmmHash) = Database.getCurrentTip()
    processBlocksFrom(bmmHeight, bmmHash)
  }

  def processBlocksFrom(bmmHeight: Int, bmmHash: ByteVector): Unit = {
    Database.getBlockAtBmmHeight(bmmHeight + 1) match {
      case Some(block) if (block.header.previous == bmmHash) => {
        // process this
        Database.processBlock(block)

        // ask for the next
        processBlocksFrom(bmmHeight + 1, block.hash)
      }
      // case TODO block's previous is a different hash than the current, i.e. we have a chain split
      case _ =>
        // check if we have other txs after this one
        if (Database.getLatestTx().isEmpty) {
          // stop here and try again later
          js.timers.setTimeout(60000) {
            processBlocksFrom(bmmHeight, bmmHash)
          }
        } else {
          // go to the next
          processBlocksFrom(bmmHeight + 1, bmmHash)
        }
    }
  }
}
