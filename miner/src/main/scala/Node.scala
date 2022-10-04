import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scodec.bits.ByteVector
import com.github.lolgab.httpclient.{Request, Method}
import ujson._
import scoin._

object Node {
  import Main.logger

  var nodeUrl: String = ""

  private def call(
      method: String,
      params: ujson.Obj = ujson.Obj()
  ): Future[ujson.Value] =
    Request()
      .method(Method.POST)
      .url(nodeUrl)
      .body(
        ujson.write(ujson.Obj("method" -> method, "params" -> params))
      )
      .future()
      .andThen { case Failure(err) =>
        logger.err
          .item(err)
          .item("method", method)
          .item("params", params)
          .msg("failed to make request to node")
        scala.sys.exit(1)
      }
      .map { r =>
        if (r.code == 0) throw new Exception("no response from node")
        else if (r.code < 300 && r.body.size > 0) {
          val b = ujson.read(r.body).obj
          if b.contains("result") then b("result")
          else throw new Exception(b("error").toString)
        } else
          throw new Exception(s"bad response from node (${r.code}): ${r.body}")
      }

  def getNextBlock(txs: Seq[ByteVector]): Future[(ByteVector32, ByteVector)] =
    call(
      "makeblock",
      ujson.Obj(
        "txs" -> txs.map(_.toHex)
      )
    )
      .map(row =>
        (
          ByteVector32(ByteVector.fromValidHex(row("hash").str)),
          ByteVector.fromValidHex(row("block").str)
        )
      )

  def validateTx(
      tx: ByteVector,
      others: Set[ByteVector] = Set.empty
  ): Future[(String, Boolean)] =
    call(
      "validatetx",
      ujson.Obj("tx" -> tx.toHex, "others" -> others.map(_.toHex))
    )
      .map(res => (res("hash").str, res("ok").bool))

  def getBmmSince(bmmHeight: Int): Future[List[Bmm]] =
    call("getbmmsince", ujson.Obj("bmmheight" -> bmmHeight))
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
    call("getblock", ujson.Obj("hash" -> bmmHash.toHex))
      .map(r => r.objOpt.map(ujson.Obj(_)))

  def registerBlock(block: ByteVector): Future[ujson.Obj] =
    call("registerblock", ujson.Obj("block" -> block.toHex)).map(r => r.obj)
}
