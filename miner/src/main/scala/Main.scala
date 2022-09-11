import java.io.ByteArrayInputStream
import util.chaining._
import scala.util.{Try, Success, Failure}
import scala.annotation.nowarn
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import scodec.bits.ByteVector
import ujson._
import scoin._
import unixsocket.UnixSocket
import upack.Obj.apply

object Main {
  val logger = new nlog.Logger()

  private var rpcAddr: String = ""
  private var nextId = 0

  def main(args: Array[String]): Unit = {
    Poll(0).startReadWrite { _ =>
      val line = scala.io.StdIn.readLine().trim
      if (line.size > 0) {
        handleRPC(line)
      }
    }
  }

  def rpc(
      method: String,
      params: ujson.Obj | ujson.Arr = ujson.Obj()
  ): Future[ujson.Value] = {
    if (rpcAddr == "") {
      return Future.failed(Exception("rpc address is not known yet"))
    }

    nextId += 1

    val payload =
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> nextId,
          "method" -> method,
          "params" -> params
        )
      )

    UnixSocket
      .call(rpcAddr, payload)
      .future
      .map(ujson.read(_))
      .flatMap(read =>
        if (read.obj.contains("error")) {
          Future.failed(Exception(read("error")("message").str))
        } else {
          Future.successful(read("result"))
        }
      )
  }

  def answer(req: ujson.Value)(result: ujson.Value): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "result" -> result
        )
      )
    )
  }

  def answer(req: ujson.Value)(errorMessage: String): Unit = {
    System.out.println(
      ujson.write(
        ujson.Obj(
          "jsonrpc" -> "2.0",
          "id" -> req("id").num,
          "error" -> ujson.Obj(
            "message" -> errorMessage
          )
        )
      )
    )
  }

  @nowarn
  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val params = req("params")
    def reply(result: ujson.Value) = answer(req)(result)
    def replyError(err: String) = answer(req)(err)

    req("method").str match {
      case "getmanifest" =>
        reply(
          ujson.Obj(
            "dynamic" -> true,
            "options" -> ujson.Arr(),
            "hooks" -> ujson.Arr(
              ujson.Obj("name" -> "invoice_payment")
            ),
            "rpcmethods" -> ujson.Arr(
              ujson.Obj(
                "name" -> "openchain-status",
                "usage" -> "",
                "description" -> "returns a bunch of data -- this is supposed to be called by the public through commando"
              ),
              ujson.Obj(
                "name" -> "openchain-invoice",
                "usage" -> "tx msatoshi",
                "description" -> "takes the transaction you want to publish plus how much in fees you intend to contribute -- this is supposed to be called by the public through commando"
              )
            ),
            "options" -> ujson.Arr(
              ujson.Obj(
                "name" -> "overseer-url",
                "type" -> "string",
                "default" -> "http://localhost:10738",
                "description" -> "URL of the openchain overseer."
              ),
              ujson.Obj(
                "name" -> "node-url",
                "type" -> "string",
                "default" -> "http://127.0.0.1:9036",
                "description" -> "URL of the openchain node."
              )
            ),
            "notifications" -> ujson.Arr("block_processed"),
            "featurebits" -> ujson.Obj()
          )
        )
      case "init" => {
        reply(
          ujson.Obj(
            "jsonrpc" -> "2.0",
            "id" -> req("id").num,
            "result" -> ujson.Obj()
          )
        )

        val lightningDir = params("configuration")("lightning-dir").str
        rpcAddr = lightningDir + "/" + params("configuration")("rpc-file").str
        Node.nodeUrl = params("options")("node-url").str
        Publish.overseerURL = params("options")("overseer-url").str
      }
      case "block_processed" => {
        val height = params("block_processed")("height").num.toInt
        logger.debug.item("height", height).msg("a block has arrived")
        Manager.onBlock(height)
      }
      case "invoice_payment" => {
        val label = params("payment")("label").str
        if (label.startsWith("miner:")) {
          logger.debug.item("label", label).msg("invoice payment arrived")
          Manager.onPayment(label).onComplete {
            case Success(true) =>
              reply(ujson.Obj("result" -> "continue"))
            case Success(false) =>
              reply(ujson.Obj("result" -> "reject"))
            case Failure(_) =>
              reply(ujson.Obj("result" -> "reject"))
          }
        } else reply(ujson.Obj("result" -> "continue"))
      }
      case "openchain-status" =>
        reply(
          ujson.Obj(
            "pending_txs" -> Manager.pendingTransactions.size,
            "acc_fees" -> Manager.totalFees.toLong
          )
        )
      case "openchain-invoice" =>
        val (tx, amount) = (params match {
          case o: Obj => (o("tx").str, o("msatoshi").num)
          case a: Arr => (a(0).str, a(1).num)
        }).pipe(v => (ByteVector.fromValidHex(v._1), MilliSatoshi(v._2.toLong)))

        Manager.addNewTransaction(tx, amount).onComplete {
          case Success(bolt11) =>
            reply(
              ujson.Obj(
                "invoice" -> bolt11
              )
            )
          case Failure(err) => replyError(err.toString)
        }
    }
  }
}
