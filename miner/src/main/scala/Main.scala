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
import scoin.Crypto.PrivateKey.apply

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
      case "psbt" =>
        rpc("listfunds").onComplete { resp =>
          for {
            funds <- resp
            outputs = funds("outputs").arr
            receivedPsbt <- Psbt.fromBase64(params("psbt").str)
            _ = logger.debug
              .item("inputs", receivedPsbt.inputs)
              .item("outputs", receivedPsbt.outputs)
              .msg("psbt parsed")

            psbt = Psbt(
              receivedPsbt.global.tx
                .copy(
                  txIn = List(
                    receivedPsbt.global.tx.txIn(0)
                  ) ++
                    outputs.toList
                      .filter(utxo =>
                        utxo("status").str == "confirmed" &&
                          utxo("reserved").bool == false
                      )
                      .map(utxo =>
                        TxIn(
                          outPoint = OutPoint(
                            ByteVector32(
                              ByteVector
                                .fromValidHex(utxo("txid").str)
                                .reverse
                            ),
                            utxo("output").num.toInt
                          ),
                          sequence = 0,
                          signatureScript = ByteVector.empty
                        )
                      )
                )
            )
            finalPsbt = psbt.copy(inputs =
              receivedPsbt.inputs(0) +: psbt.inputs.drop(1)
            )
          } yield reply(Psbt.toBase64(finalPsbt))
        }
    }
  }
}
