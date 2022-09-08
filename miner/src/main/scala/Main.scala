import java.io.ByteArrayInputStream
import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.ChainingOps
import util.chaining.scalaUtilChainingOps
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import com.github.lolgab.httpclient.{Request, Method}
import scodec.bits.ByteVector
import ujson._
import scoin._
import unixsocket.UnixSocket

case class Tx(id: String, hex: String)

object Main {
  val logger = new nlog.Logger()

  private var rpcAddr: String = ""
  private var overseerURL: String = ""
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
            "dynamic" -> true,
            "options" -> ujson.Arr(),
            "subscriptions" -> ujson.Arr(
              "sendpay_success",
              "sendpay_failure",
              "connect",
              "disconnect"
            ),
            "hooks" -> ujson.Arr(
              ujson.Obj("name" -> "invoice_payment")
            ),
            "rpcmethods" -> ujson.Arr(
              ujson.Obj(
                "name" -> "publish",
                "usage" -> "hash fee",
                "description" -> "something"
              )
            ),
            "options" -> ujson.Arr(
              ujson.Obj(
                "name" -> "overseer-url",
                "type" -> "string",
                "default" -> "http://localhost:10738",
                "description" -> "URL of the openchain overseer."
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
        overseerURL = params("options")("overseer-url").str
      }
      case "invoice_payment" => {
        val label = params("payment")("label").str
        logger.debug.item("label", label).msg("invoice payment received")
        reply(ujson.Obj("result" -> "continue"))
      }
      case "publish" => {
        val hash = ByteVector.fromValidHex(params(0).str)
        val fee = Satoshi(params(1).num.toLong)

        publishBlock(hash, fee)
          .onComplete {
            case Success(Tx(id, hex)) =>
              reply(ujson.Obj("txid" -> id, "tx" -> hex))
            case Failure(err) => replyError(s"$err")
          }
      }
    }
  }

  def publishBlock(blockHash: ByteVector, fee: Satoshi): Future[Tx] = {
    val finalPsbt = for {
      listfunds <- rpc("listfunds")
      overseerResponse <- Request()
        .method(Method.GET)
        .url(overseerURL)
        .future()
      listaddrs <- rpc("dev-listaddrs", ujson.Obj("bip32_max_index" -> 0))
    } yield {
      val outputs = listfunds("outputs").arr
        .filter(utxo =>
          utxo("status").str == "confirmed" &&
            utxo("reserved").bool == false
        )
      val nextPsbt = overseerResponse
        .pipe(_.body)
        .pipe(ujson.read(_))
        .pipe(_("next")("psbt").str)
        .pipe(Psbt.fromBase64(_))
        .get
      val ourScriptPubKey = listaddrs
        .pipe(_("addresses")(0)("pubkey").str)
        .pipe(ByteVector.fromValidHex(_))
        .pipe(Crypto.hash160(_))
        .pipe(pubkeyhash =>
          ByteVector.concat(
            List(ByteVector.fromByte(0), ByteVector.fromByte(20), pubkeyhash)
          )
        )

      val inputSum = outputs
        .map(utxo => MilliSatoshi(utxo("amount_msat").num.toLong).toSatoshi)
        .fold(Satoshi(0))(_ + _)

      if (inputSum < fee)
        throw new Exception(
          s"not enough unreserved and confirmed outputs to pay fee of $fee"
        )

      Psbt(
        nextPsbt.global.tx
          .copy(
            txIn = List(
              nextPsbt.global.tx.txIn(0)
            ) ++
              // add our inputs
              outputs
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
                ),
            txOut = List(
              // the canonical output
              nextPsbt.global.tx.txOut(0),

              // add our OP_RETURN
              TxOut(
                Satoshi(0L),
                Script.write(List(OP_RETURN, OP_PUSHDATA(blockHash)))
              ),

              // add our change
              TxOut(inputSum - fee, ourScriptPubKey)
            )
          )
      )
        .pipe(interm =>
          interm.copy(inputs = nextPsbt.inputs(0) +: interm.inputs.drop(1))
        )
        .pipe(Psbt.toBase64(_))
    }

    for {
      psbt <- finalPsbt
      _ <- rpc("reserveinputs", ujson.Obj("psbt" -> psbt))
      signedPsbt <- rpc("signpsbt", ujson.Obj("psbt" -> psbt))
        .map(_("signed_psbt").str)
      res <- rpc("sendpsbt", ujson.Obj("psbt" -> signedPsbt))
    } yield Tx(res("txid").str, res("tx").str)
  }
}
