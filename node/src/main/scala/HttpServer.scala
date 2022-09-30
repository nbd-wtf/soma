import util.chaining._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scodec.bits.ByteVector
import scodec.DecodeResult
import upickle.default._
import scala.scalajs.js.typedarray.Uint8Array
import scoin.{Crypto, ByteVector32}

import Picklers._

object HttpServer {
  def start(): Unit = {
    http.createServer(handleRequest).listen(Config.port)
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
          val data = ujson.read(body).obj
          id = data.getOrElse("id", "0")

          val method = data.get("method").get.str
          val params = data.get("params").getOrElse(ujson.Obj())

          val result: ujson.Value = method match {
            case "info" =>
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
            case "getbmmsince" =>
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
            case "getblock" =>
              ((params.obj.get("hash"), params.obj.get("height")) match {
                case (Some(hash), _) =>
                  Database.getBlock(ByteVector.fromValidHex(hash.str))
                case (_, Some(height)) =>
                  Database.getBlockAtHeight(height.num.toInt)
                case _ => throw new Exception("provide either hash or height")
              })
                .map(block => writeJs(block))
                .getOrElse(ujson.Null)
            case "getassetowner" =>
              Database
                .getAssetOwner(
                  ByteVector32(ByteVector.fromValidHex(params("asset").str))
                )
                .map[ujson.Value](_.toHex)
                .getOrElse(ujson.Null)
            case "getaccountassets" =>
              Database
                .getAccountAssets(
                  Crypto.XOnlyPublicKey(
                    ByteVector32(
                      ByteVector.fromValidHex(params("pubkey").str)
                    )
                  )
                )
                .map(_.toHex)
            case "listallassets" =>
              Database
                .listAllAssets()
                .map((asset, owner) => (asset.toHex, owner.toHex))
            case "decodeblock" =>
              Block.codec
                .decode(
                  ByteVector.fromValidHex(params("hex").str).bits
                )
                .require
                .value
                .pipe(writeJs(_))
            case "decodetx" =>
              Tx.codec
                .decode(
                  ByteVector.fromValidHex(params("hex").str).bits
                )
                .require
                .value
                .pipe(writeJs(_))

            // to be called by the user
            case "buildtx" =>
              val asset =
                ByteVector32(ByteVector.fromValidHex(params("asset").str))
              val to = Crypto.XOnlyPublicKey(
                ByteVector32(ByteVector.fromValidHex(params("to").str))
              )
              val privateKey = Crypto.PrivateKey(
                ByteVector32(ByteVector.fromValidHex(params("privateKey").str))
              )
              val tx = Tx.build(asset, to, privateKey)
              ujson.Obj(
                "hash" -> tx.hash.toHex,
                "tx" -> Tx.codec
                  .encode(tx)
                  .require
                  .toHex
              )

            // to be called by the miner
            case "validatetx" =>
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
              ujson.Obj("hash" -> tx.hash.toHex, "ok" -> tx.validate(others))

            case "makeblock" =>
              Block.makeBlock(
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

            case "registerblock" =>
              Block.codec
                .decode(
                  ByteVector.fromValidHex(params("block").str).toBitVector
                )
                .toOption match {
                case Some(DecodeResult(block, _)) =>
                  val ok = Database.insertBlock(block.hash, block)

                  if (ok) {
                    // notify our peers that we have this block now
                    PeerManager.sendAll(
                      WireMessage.codec
                        .encode(AnswerBlock(block.hash, Some(block)))
                        .require
                        .bytes
                    )
                  }

                  println(s"block registered: ${block.hash}")

                  ujson.Obj("ok" -> ok, "hash" -> block.hash.toHex)
                case _ =>
                  ujson.Obj("ok" -> false)
              }
          }

          res.setHeader("Access-Control-Allow-Origin", "*")
          res.setHeader("Access-Control-Allow-Methods", "OPTIONS, POST")
          res.end(ujson.write(ujson.Obj("id" -> id, "result" -> result)))
        } catch {
          case err: Throwable =>
            System.err.println(
              s"RPC command resulted in error"
            )
            System.err.println(err)
            System.err.println(
              "  " +
                err.getStackTrace().take(12).map(_.toString()).mkString("\n  ")
            )
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
