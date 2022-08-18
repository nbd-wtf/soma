import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scodec.bits.ByteVector
import scodec.DecodeResult
import upickle.default._

object HttpServer {
  def start(): Unit = {
    http.createServer(handleRequest).listen(9036)
  }

  def handleRequest(req: Request, res: Response): Unit = {
    var id: ujson.Value = ujson.Null
    var body = ""
    req.on("data", (chunk: String) => body += chunk)
    req.on(
      "end",
      (_: Unit) =>
        try {
          val data = ujson.read(body)
          id = data("id")

          val method = data("method").str
          val params = data("params").obj

          val result: ujson.Value = method match {
            case "info" =>
              ujson.Obj(
                "latestKnownBlock" -> Database
                  .getLatestKnownBlock()
                  .map(block => ujson.read(write(block)))
                  .getOrElse(ujson.Null),
                "latestBMMTx" -> Database
                  .getLatestTx()
                  .map[ujson.Value](_._1)
                  .getOrElse(ujson.Null)
              )

            case "getblock" =>
              Database
                .getBlock(ByteVector.fromValidHex(params("hash").str))
                .map(block => ujson.read(write(block)))
                .getOrElse(ujson.Null)

            // to be used by the user
            case "buildtx" =>
              val asset = ByteVector.fromValidHex(params("asset").str)
              val to = ByteVector.fromValidHex(params("to").str)
              val privateKey = ByteVector.fromValidHex(params("privateKey").str)
              write(Tx.build(asset, to, privateKey))

            // to be used by the miner
            case "validatetx" =>
              ujson.Obj("ok" -> read[Tx](params("tx")).validate())

            case "makeblock" =>
              Block.makeBlock(read[List[Tx]](params("txs"))) match {
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
