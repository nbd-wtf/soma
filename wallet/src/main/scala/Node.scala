import scala.util.{Try, Success}
import scala.util.chaining._
import scala.concurrent.Future
import scala.scalajs.js
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import org.scalajs.dom
import sttp.client3._
import sttp.model.Uri
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._
import scodec.bits.ByteVector
import scoin._
import scoin.Crypto.{XOnlyPublicKey, PrivateKey}

import JSON.given

object Node {
  def getInfo(): Future[NodeInfo] =
    call("info").map(_.as[NodeInfo].toTry.get)

  def getBlock(hash: String): Future[Block] =
    call("getblock", Map("hash" -> hash.asJson))
      .map(_.as[Block].toTry.get)

  def getAssets(key: XOnlyPublicKey): Future[List[AssetInfo]] =
    call("getaccountassets", Map("pubkey" -> key.value.toHex.asJson))
      .map(_.as[List[AssetInfo]].toTry.get)

  // ---
  val backend = FetchBackend()
  def call(
      method: String,
      params: Map[String, Json] = Map.empty
  ): Future[io.circe.Json] =
    basicRequest
      .post(nodeUrl)
      .body(
        Map[String, Json](
          "method" -> method.asJson,
          "params" -> params.asJson
        ).asJson.toString
      )
      .send(backend)
      .map(_.body.toOption.get)
      .map(parse(_).toTry.get)
      .map(_.hcursor.downField("result").as[Json].toTry.get)

  def nodeUrl = Try(
    Uri.parse(
      dom.window.localStorage
        .getItem("nodeUrl")
        .pipe(s => if s.startsWith("http") then s else s"http://$s")
    )
  ) match {
    case Success(Right(s)) => s
    case _ =>
      Uri.unsafeParse(
        js.Dynamic.global.NODE_URL
          .asInstanceOf[String]
          .pipe(s => if s.startsWith("http") then s else s"http://$s")
      )
  }
}

case class NodeInfo(
    latestKnownBlock: Option[BlockInfo],
    latestBmmTx: BmmTx
)

object NodeInfo {
  given Decoder[NodeInfo] = new Decoder[NodeInfo] {
    final def apply(c: HCursor): Decoder.Result[NodeInfo] = for {
      bmm <- c.downField("latest_bmm_tx").as[BmmTx]
      block = c.downField("latest_known_block").as[BlockInfo] match {
        case Right(b) => Some(b)
        case Left(_)  => None
      }
    } yield NodeInfo(block, bmm)
  }

  def empty = NodeInfo(None, BmmTx("", 0, None))
}

case class BlockInfo(hash: String, height: Int)

case class BmmTx(
    txid: String,
    bmmHeight: Int,
    bmmHash: Option[String]
)

object BmmTx {
  given Decoder[BmmTx] = new Decoder[BmmTx] {
    final def apply(c: HCursor): Decoder.Result[BmmTx] =
      for {
        txid <- c.downField("txid").as[String]
        bmmHeight <- c.downField("bmmheight").as[Int]
        bmmHash <- c.downField("bmmhash").as[Option[String]]
      } yield {
        BmmTx(txid, bmmHeight, bmmHash)
      }
  }
}

case class AssetInfo(asset: Int, counter: Int)

object AssetInfo {
  given Decoder[AssetInfo] = new Decoder[AssetInfo] {
    final def apply(c: HCursor): Decoder.Result[AssetInfo] =
      for {
        asset <- c.downField("asset").as[Int]
        counter <- c.downField("counter").as[Int]
      } yield AssetInfo(asset, counter)
  }
}

object JSON {
  given Decoder[XOnlyPublicKey] = new Decoder[XOnlyPublicKey] {
    final def apply(c: HCursor): Decoder.Result[XOnlyPublicKey] =
      c.as[ByteVector32].map(XOnlyPublicKey(_))
  }

  given Decoder[ByteVector32] = new Decoder[ByteVector32] {
    final def apply(c: HCursor): Decoder.Result[ByteVector32] =
      c.as[ByteVector].map(ByteVector32(_))
  }

  given Decoder[ByteVector64] = new Decoder[ByteVector64] {
    final def apply(c: HCursor): Decoder.Result[ByteVector64] =
      c.as[ByteVector].map(ByteVector64(_))
  }

  given Decoder[ByteVector] = new Decoder[ByteVector] {
    final def apply(c: HCursor): Decoder.Result[ByteVector] =
      c.as[String]
        .flatMap(
          ByteVector
            .fromHex(_)
            .toRight(DecodingFailure("invalid hex", c.history))
        )
  }
}
