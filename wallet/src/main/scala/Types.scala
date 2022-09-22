import io.circe.{Decoder, HCursor, Json}

// miner
case class MinerStatus(
    pendingTxs: Int,
    accFees: Int
)

object MinerStatus {
  given Decoder[MinerStatus] = new Decoder[MinerStatus] {
    final def apply(c: HCursor): Decoder.Result[MinerStatus] =
      for {
        pendingTxs <- c.downField("pending_txs").as[Int]
        accFees <- c.downField("acc_fees").as[Int]
      } yield {
        MinerStatus(pendingTxs, accFees)
      }
  }

  def empty = MinerStatus(0, 0)
}

case class MinerInvoice(
    invoice: String,
    hash: String
)

// node
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
