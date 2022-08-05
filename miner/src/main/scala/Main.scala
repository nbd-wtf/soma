import java.io.ByteArrayInputStream
import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import ujson._
import scoin._
import scodec.bits.ByteVector

import unixsocket.UnixSocket

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
      params: ujson.Obj = ujson.Obj()
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

  def handleRPC(line: String): Unit = {
    val req = ujson.read(line)
    val params = req("params")
    def reply(result: ujson.Value) = answer(req)(result)
    def replyError(err: String) = answer(req)(err)

    req("method").str match {
      case "getmanifest" =>
        reply(
          ujson.Obj(
            "dynamic" -> false, // custom features can only be set on non-dynamic
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure",
              "connect",
              "disconnect"
            ),
            "hooks" -> ujson.Arr(
              // ujson.Obj("name" -> "htlc_accepted")
            ),
            "rpcmethods" -> ujson.Arr(
              ujson.Obj(
                "name" -> "psbt",
                "usage" -> "psbt",
                "description" -> "something"
              )
            ),
            "notifications" -> ujson.Arr(),
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
      }
      case "htlc_accepted" => {
        val htlc = params("htlc")
        logger.debug.item("htlc", htlc).msg("htlc_accepted")
      }
      case "psbt" => {
        Psbt
          .fromBase64(params("psbt").str)
          .map { receivedPsbt =>
            logger.debug
              .item("inputs", receivedPsbt.inputs)
              .item("outputs", receivedPsbt.outputs)
              .msg("psbt parsed")

            val psbt = Psbt(
              receivedPsbt.global.tx
                .addInput(
                  TxIn(
                    outPoint = OutPoint(
                      ByteVector32(
                        ByteVector
                          .fromValidHex(
                            "e40f8d3a2a2db58684b3e7fff74e354be8abdae20bf2d4c232c6353192cc33d7"
                          )
                          .reverse
                      ),
                      0
                    ),
                    sequence = 0,
                    signatureScript = ByteVector.empty
                  )
                )
            )

            psbt.copy(inputs = receivedPsbt.inputs(0) +: psbt.inputs.drop(1))
          } match {
          case Success(psbt) =>
            logger.debug.item(Psbt.toBase64(psbt)).msg("new")
          case Failure(err) => logger.err.item(err).msg("failed")
        }
      }
    }
  }
}
