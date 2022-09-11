import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scodec.bits.ByteVector
import com.github.lolgab.httpclient.{Request, Method}
import ujson._
import scoin._

object Node {
  var nodeUrl: String = ""

  def getNextBlock(txs: Seq[ByteVector]): Future[ByteVector] =
    Request()
      .method(Method.POST)
      .url(nodeUrl)
      .body(
        ujson.write(
          ujson.Obj(
            "method" -> "makeblock",
            "params" -> ujson.Obj(
              "txs" -> ujson.Arr(txs.map(_.toHex))
            )
          )
        )
      )
      .future()
      .map(r => ujson.read(r.body)("result")("hex").str)
      .map(ByteVector.fromValidHex(_))

  def validateTx(tx: ByteVector): Future[Boolean] =
    Request()
      .method(Method.POST)
      .url(nodeUrl)
      .body(
        ujson.write(
          ujson.Obj(
            "method" -> "validatetx",
            "params" -> ujson.Obj("tx" -> tx.toHex)
          )
        )
      )
      .future()
      .map(r => ujson.read(r.body)("result")("ok").bool)

  def getBmmSince(bmmHeight: Int): Future[List[Bmm]] =
    Request()
      .method(Method.POST)
      .url(nodeUrl)
      .body(
        ujson.write(
          ujson.Obj(
            "method" -> "getbmmsince",
            "params" -> ujson.Obj("bmmheight" -> bmmHeight)
          )
        )
      )
      .future()
      .map(r => ujson.read(r.body)("result"))
      .map(r =>
        r.arr.toList.map(bmm =>
          Bmm(
            bmm("txid").str,
            bmm("bmmheight").num.toInt,
            bmm("bmmhash").strOpt
              .map(h => ByteVector32(ByteVector.fromValidHex(h)))
          )
        )
      )

  def getBlock(bmmHash: ByteVector32): Future[Option[ujson.Obj]] =
    Request()
      .method(Method.POST)
      .url(nodeUrl)
      .body(
        ujson.write(
          ujson.Obj(
            "method" -> "getblock",
            "params" -> ujson.Obj("hash" -> bmmHash.toHex)
          )
        )
      )
      .future()
      .map(r => ujson.read(r.body)("result"))
      .map(r => r.objOpt.map(ujson.Obj(_)))

  def registerBlock(block: ByteVector): Future[Unit] =
    Request()
      .method(Method.POST)
      .url(nodeUrl)
      .body(
        ujson.write(
          ujson.Obj(
            "method" -> "registerblock",
            "params" -> ujson.Obj("hex" -> block.toHex)
          )
        )
      )
      .future()
      .map(r => ujson.read(r.body)("result"))
      .map(r => r.objOpt.map(ujson.Obj(_)))
}
