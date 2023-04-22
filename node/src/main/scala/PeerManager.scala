import scala.util.chaining.*
import scala.util.{Success, Failure}
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}
import scoin.ByteVector32
import soma.*

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
            val block = Database.getBlock(hash)
            println(s"sending block $hash: ${block.isDefined}")
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

          case AnswerBlock(hash, Some(block)) if block.hash != hash =>
            println(s"got an invalid block message for ${hash.toHex}")

          case AnswerBlock(hash, Some(block)) if block.hash != hash =>
            println(s"got an invalid block message for ${hash.toHex}")

          case AnswerBlock(hash, Some(block))
              if !Blockchain.validateBlock(block) =>
            println(s"got an invalid block for ${hash.toHex}")

          case AnswerBlock(hash, Some(block)) =>
            println(s"got block $hash")
            Database.insertBlock(hash, block) match {
              case true =>
                println(s"block $hash inserted at height ${block.height}")
                if (Blockchain.blockIsNext(block)) {
                  StateManager.start()
                }
              case false => println("failed to insert")
            }

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
