import scala.concurrent.Future
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import sttp.client3._
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._
import scoin._
import scoin.Crypto.{PublicKey, PrivateKey}

object Node {
  def getInfo(): Future[NodeInfo] =
    call("info").map(_.as[NodeInfo].toTry.get)

  def getAssets(key: PublicKey): Future[List[String]] =
    call("getaccountassets", Map("pubkey" -> key.value.drop(1).toHex.asJson))
      .map(_.as[List[String]].toTry.get)

  def buildTx(
      asset: ByteVector32,
      to: ByteVector32,
      privateKey: PrivateKey
  ): Future[BuiltTx] = call(
    "buildtx",
    Map(
      "asset" -> asset.toHex.asJson,
      "to" -> to.toHex.asJson,
      "privateKey" -> privateKey.value.toHex.asJson
    )
  ).map(_.as[BuiltTx].toTry.get)

  // ---
  val backend = FetchBackend()
  def call(
      method: String,
      params: Map[String, Json] = Map.empty
  ): Future[io.circe.Json] =
    basicRequest
      .post(uri"http://127.0.0.1:9036/")
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
}

case class NodeInfo(
    latestBmmTx: BmmTx
)

object NodeInfo {
  given Decoder[NodeInfo] = new Decoder[NodeInfo] {
    final def apply(c: HCursor): Decoder.Result[NodeInfo] =
      c.downField("latest_bmm_tx").as[BmmTx].map(NodeInfo(_))
  }

  def empty = NodeInfo(BmmTx("", 0, None))
}

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

case class BuiltTx(tx: String, hash: String)
