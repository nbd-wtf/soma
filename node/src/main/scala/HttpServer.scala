import util.chaining.scalaUtilChainingOps
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scodec.bits.ByteVector
import scodec.DecodeResult
import upickle.default._
import scala.scalajs.js.typedarray.Uint8Array

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
                  .map { case (blockheight, txid, bmmheight, bmmhash) =>
                    ujson.Obj(
                      "blockheight" -> blockheight,
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
                .pipe(ujson.Arr(_))
            case "getblock" =>
              Database
                .getBlock(ByteVector.fromValidHex(params("hash").str))
                .map(block => ujson.read(write(block)))
                .getOrElse(ujson.Null)
            case "getassetowner" =>
              Database
                .getAssetOwner(ByteVector.fromValidHex(params("asset").str))
                .map[ujson.Value](_.toHex)
                .getOrElse(ujson.Null)
            case "getaccountassets" =>
              Database
                .getAccountAssets(ByteVector.fromValidHex(params("pubkey").str))
                .map(_.toHex)

            // to be called by the user
            case "buildtx" =>
              val asset = ByteVector.fromValidHex(params("asset").str)
              val to = ByteVector.fromValidHex(params("to").str)
              val privateKey = ByteVector.fromValidHex(params("privateKey").str)
              ujson.Obj(
                "hex" -> Tx.codec
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
                  .map(p => ByteVector.fromValidHex(p.str))
              ) match {
                case Left(err) => throw new Exception(err)
                case Right(block) =>
                  ujson.Obj(
                    "hex" -> Block.codec.encode(block).toOption.get.toHex
                  )
              }

            case "registerblock" =>
              Block.codec
                .decode(ByteVector.fromValidHex(params("hex").str).toBitVector)
                .toOption match {
                case Some(DecodeResult(block, remaining))
                    if remaining.isEmpty =>
                  Database.insertBlock(block.hash, block)
                  ujson.Obj("ok" -> "true", "hash" -> block.hash.toHex)
                case _ => throw new Exception("failed to parse block hex")
              }
          }

          res.end(ujson.write(ujson.Obj("id" -> id, "result" -> result)))
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
  def end(response: String): Unit = js.native
}
