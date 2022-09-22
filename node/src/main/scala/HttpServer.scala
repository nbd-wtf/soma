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
              Database
                .getBlock(ByteVector.fromValidHex(params("hash").str))
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
                  (params.obj.get("pubkey"), params.obj.get("privkey")) match {
                    case (_, Some(priv)) =>
                      Crypto
                        .PrivateKey(
                          ByteVector32(
                            ByteVector.fromValidHex(priv.str)
                          )
                        )
                        .publicKey
                        .xonly
                    case (Some(pub), _) =>
                      Crypto.XOnlyPublicKey(
                        ByteVector32(
                          ByteVector.fromValidHex(pub.str)
                        )
                      )
                    case _ =>
                      throw new Exception("pubkey or privkey must be provided")
                  }
                )
                .map(_.toHex)

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
              ujson.Obj(
                "tx" -> Tx.codec
                  .encode(Tx.build(asset, to, privateKey))
                  .require
                  .toHex
              )

            // to be called by the miner
            case "validatetx" =>
              ujson.Obj(
                "ok" -> ByteVector
                  .fromHex(params("tx").str)
                  .map(_.toBitVector)
                  .flatMap(Tx.codec.decode(_).toOption)
                  .map(_.value.validate())
                  .getOrElse(false)
              )

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
