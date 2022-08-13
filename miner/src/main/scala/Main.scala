import java.io.ByteArrayInputStream
import scala.util.{Try, Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.ChainingOps
import scala.scalanative.loop.EventLoop.loop
import scala.scalanative.loop.{Poll, Timer}
import com.github.lolgab.httpclient.{Request, Method}
import scodec.bits.ByteVector
import ujson._
import scoin._
import scoin.ScriptElt.OP_RETURN
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
                "name" -> "publish",
                "usage" -> "hash fee",
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
      case "publish" => {
        val hash = ByteVector.fromValidHex(params(0).str)
        val fee = Satoshi(params(1).num.toLong)

        publishBlock(hash, fee)
      }
    }
  }

  def publishBlock(blockHash: ByteVector, fee: Satoshi): Future[Unit] = {
    val outputs = rpc("listfunds")
      .map(_("outputs").arr)
      .filter(utxo =>
        utxo("status").str == "confirmed" &&
          utxo("reserved").bool == false
      )
    val nextPsbt = Request()
      .method(Method.GET)
      .url("http://localhost:10738")
      .future()
      .map(_.body)
      .map(ujson.read(_))
      .map(_("next")("psbt").str)
      .map(Psbt.fromBase64(_))
    val ourScriptPubKey = rpc("dev-listaddr", ujson.Obj("bip32_max_index" -> 0))
      .map(_("addresses")(0)("pubkey").str)
      .map(ByteVector.fromValidHex(_))
      .map(Crypto.hash160(_))
      .map(pubkeyhash =>
        ByteVector.concat(
          List(ByteVector.fromByte(0), ByteVector.fromByte(20), pubkeyhash)
        )
      )

    Future.sequence(List(outpus, nextPsbt, ourScriptPubKey)).onComplete {
      case (List(
            Success(nextPsbt),
            Success(outputs),
            Success(ourScriptPubKey)
          )) => {
        val inputSum =
          MilliSatoshi(outputs.map(_("amount_msat").num)).toSatoshi

        val psbt = Psbt(
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
                TxOut(Satoshi(0L), Script.write(OP_RETURN :: blockHash)),

                // add our change
                TxOut(inputSum - fee, ourScriptPubKey)
              )
            )
        )
          .pipe(interm =>
            interm.copy(inputs = nextPsbt.inputs(0) +: interm.inputs.drop(1))
          )
          .pipe(Psbt.toBase64(_))

        rpc("reserveinputs", ujson.Obj("psbt" -> psbt))
          .flatMap { _ =>
            rpc("signpsbt", ujson.Obj("psbt" -> psbt))
          }
          .flatMap { resp =>
            val signedPsbt = resp("signed_psbt").str
            rpc("sendpsbt", ujson.Obj("psbt" -> signedPsbt))
          }
          .onComplete {
            case Success(res) =>
              logger.info
                .item("tx", res("tx").str)
                .item("txid", res("txid").str)
                .msg("tx published")
            case Failure(err) =>
              logger.err
                .item("err", err)
                .msg("something went wrong when mining block")
          }
      }
      case something =>
        logger.err
          .item("something", something)
          .msg("something went wrong when preparing the tx for publication")
    }
  }
}
