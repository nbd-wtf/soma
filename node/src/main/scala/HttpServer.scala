import scala.util.chaining._
import scala.util.{Success, Failure}
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scodec.bits.ByteVector
import scodec.DecodeResult
import upickle.default._
import scala.scalajs.js.typedarray.Uint8Array
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scoin.ByteVector32
import scoin.Crypto.XOnlyPublicKey
import openchain._

import Picklers.given

object HttpServer {
  def start(): Unit = {
    http.createServer(handleRequest).listen(Config.port)
    println(s"listening at port ${Config.port}")
  }

  def handleRequest(req: Request, res: Response): Unit = {
    var id: ujson.Value = ujson.Null
    var body = ""
    req.on(
      "data",
      (chunk: Uint8Array) =>
        body += ByteVector.fromUint8Array(chunk).decodeUtf8.toOption.get
    )
    req.on(
      "end",
      (_: Unit) =>
        try {
          res.setHeader("Access-Control-Allow-Origin", "*")
          res.setHeader("Access-Control-Allow-Methods", "OPTIONS, POST")

          val data = ujson.read(body).obj
          id = data.getOrElse("id", "0")

          val method = data.get("method").get.str
          val params = data.get("params").getOrElse(ujson.Obj())

          val result: Future[ujson.Value] = method match {
            case "info" =>
              Future {
                ujson.Obj(
                  "latest_known_block" -> Database
                    .getLatestKnownBlock()
                    .map { case (bmmheight, block) =>
                      ujson.Obj(
                        "height" -> bmmheight,
                        "hash" -> block.hash.toHex
                      )
                    }
                    .getOrElse(ujson.Null),
                  "latest_bmm_tx" -> Database
                    .getLatestTx()
                    .map { case (txid, bmmheight, bmmhash) =>
                      ujson.Obj(
                        "txid" -> txid,
                        "bmmheight" -> bmmheight,
                        "bmmhash" -> bmmhash
                          .map[ujson.Value](_.toHex)
                          .getOrElse(ujson.Null)
                      )
                    }
                    .getOrElse(ujson.Null)
                )
              }
            case "getbmmsince" =>
              Future {
                Database
                  .getBmmTxsSince(params("bmmheight").num.toInt)
                  .map { case (txid, bmmheight, bmmhash) =>
                    ujson.Obj(
                      "txid" -> txid,
                      "bmmheight" -> bmmheight,
                      "bmmhash" -> bmmhash
                        .map[ujson.Value](_.toHex)
                        .getOrElse(ujson.Null)
                    )
                  }
              }
            case "getblock" =>
              Future {
                ((params.obj.get("hash"), params.obj.get("height")) match {
                  case (Some(hash), _) =>
                    Database.getBlock(
                      ByteVector32(ByteVector.fromValidHex(hash.str))
                    )
                  case (_, Some(height)) =>
                    Database.getBlockAtHeight(height.num.toInt)
                  case _ => throw new Exception("provide either hash or height")
                })
                  .map(block => writeJs(block))
                  .getOrElse(ujson.Null)
              }
            case "getassetowner" =>
              Future {
                Database
                  .getAssetOwner(params("asset").num.toInt)
                  .map[ujson.Value](_.toHex)
                  .getOrElse(ujson.Null)
              }
            case "getaccountassets" =>
              Future {
                Database
                  .getAccountAssets(
                    XOnlyPublicKey(
                      ByteVector32(
                        ByteVector.fromValidHex(params("pubkey").str)
                      )
                    )
                  )
                  .map((asset, counter) =>
                    ujson.Obj(
                      "asset" -> asset,
                      "counter" -> counter
                    )
                  )
              }
            case "listallassets" =>
              Future {
                Database
                  .listAllAssets()
                  .map((asset, owner) => (asset.toHexString, owner.toHex))
              }
            case "decodeblock" =>
              Future {
                Block.codec
                  .decode(
                    ByteVector.fromValidHex(params("hex").str).bits
                  )
                  .require
                  .value
                  .pipe(writeJs(_))
              }
            case "decodetx" =>
              Future {
                Tx.codec
                  .decode(
                    ByteVector.fromValidHex(params("hex").str).bits
                  )
                  .require
                  .value
                  .pipe(writeJs(_))
              }

            // to be called by the miner
            case "validatetx" =>
              Future {
                val tx = Tx.codec
                  .decode(ByteVector.fromValidHex(params("tx").str).bits)
                  .require
                  .value
                val others = params.obj
                  .get("others")
                  .map(
                    _.arr.toSet.map(h =>
                      Tx.codec
                        .decode(ByteVector.fromValidHex(h.str).bits)
                        .require
                        .value
                    )
                  )
                  .getOrElse(Set.empty)

                ujson.Obj(
                  "hash" -> tx.hash.toHex,
                  "ok" -> (
                    Blockchain.validateTx(tx, others) && (others + tx)
                      .filter(_.isNewAsset)
                      .size <= Block.MaxMintsPerBlock
                  )
                )
              }

            case "makeblock" =>
              Future {
                Blockchain.makeBlock(
                  params("txs").arr.toList
                    .map(_.str)
                    .map(
                      ByteVector
                        .fromValidHex(_)
                        .toBitVector
                        .pipe(Tx.codec.decode(_).require.value)
                    ),
                  params.obj
                    .get("parent")
                    .map(p => ByteVector32(ByteVector.fromValidHex(p.str)))
                ) match {
                  case Left(err) => throw new Exception(err)
                  case Right(block) =>
                    ujson.Obj(
                      "block" -> Block.codec.encode(block).toOption.get.toHex,
                      "hash" -> block.hash.toHex
                    )
                }
              }

            case "registerblock" =>
              // we scan the bitcoin chain until the to make sure we have all the bmm hashes before trying to insert
              BitcoinManager.start().map { _ =>
                (for {
                  res <- Block.codec
                    .decode(
                      ByteVector.fromValidHex(params("block").str).toBitVector
                    )
                    .toOption
                  block = res.value

                  _ = println(s"registering block ${block.hash}: $block")
                  ok = Database.insertBlock(block.hash, block)
                } yield {
                  if (ok) {
                    // notify our peers that we have this block now
                    PeerManager.sendAll(
                      WireMessage.codec
                        .encode(AnswerBlock(block.hash, Some(block)))
                        .require
                        .bytes
                    )
                    println(s"block registered at height ${block.height}")

                    // restarting state manager, it will do the right thing
                    StateManager.start()
                  }

                  ujson.Obj("ok" -> ok, "hash" -> block.hash.toHex)
                }).getOrElse {
                  println(
                    s"failed to register block (it was probably already registered)"
                  )
                  ujson.Obj("ok" -> false)
                }
              }
          }

          result.onComplete {
            case Failure(err) =>
              System.err.println(s"RPC command resulted in error")
              System.err.println(err)
              System.err.println(
                "|  " + err
                  .getStackTrace()
                  .take(12)
                  .map(_.toString())
                  .mkString("\n|  ")
              )
              res.end(
                ujson.write(ujson.Obj("id" -> id, "error" -> err.toString))
              )
            case Success(r) =>
              res.end(ujson.write(ujson.Obj("id" -> id, "result" -> r)))
          }
        } catch {
          case err: Throwable =>
            res.end(ujson.write(ujson.Obj("id" -> id, "error" -> err.toString)))
        }
    )
  }
}

@js.native
@JSImport("http", JSImport.Default)
object http extends js.Object {
  def createServer(listener: js.Function): Server = js.native
}

@js.native
trait Server extends js.Object {
  def listen(port: Int): Unit = js.native
}

@js.native
trait Request extends js.Object {
  def on(event: String, cb: js.Function): Unit = js.native
}

@js.native
trait Response extends js.Object {
  def setHeader(k: String, v: String): Unit = js.native
  def end(response: String): Unit = js.native
}
