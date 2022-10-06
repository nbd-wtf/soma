import scodec.bits.ByteVector
import scoin.{Crypto, ByteVector32, ByteVector64}
import openchain._

object Blockchain {
  def makeBlock(
      txs: Seq[Tx],
      parent: Option[ByteVector32] = None
  ): Either[String, Block] =
    if (!validateTxs(txs.toSet))
      Left("one or more of the transaction is invalid")
    else {
      val previous = parent
        .orElse(
          Database
            .getLatestKnownBlock()
            .map { case (_, block) => block.hash }
        )
        .getOrElse(ByteVector32.Zeroes) // default to 32 zeroes
      val block = Block(
        header = BlockHeader(previous, Tx.merkleRoot(txs)),
        txs = txs.toList
      )
      Right(block)
    }

  def validateBlock(block: Block): Boolean =
    validateTxs(block.txs.toSet) &&
      block.header.merkleRoot == Tx.merkleRoot(block.txs)

  def validateTxs(txs: Set[Tx]): Boolean =
    txs.forall(thisTx =>
      validateTx(thisTx, txs.filterNot(_ == thisTx).toSet) == true
    )

  def validateTx(tx: Tx, otherTxsInTheBlock: Set[Tx] = Set.empty): Boolean = {
    val ownerCorrect = Database.verifyAssetOwnerAndCounter(
      tx.asset,
      tx.from,
      tx.counter
    )
    val isNewAsset =
      tx.counter == 1 && Database.verifyAssetDoesntExist(tx.asset)
    val assetNotBeingTransactedAlready =
      !otherTxsInTheBlock.exists(_.asset == tx.asset)

    (ownerCorrect || isNewAsset) &&
    assetNotBeingTransactedAlready &&
    tx.signatureValid()
  }
}
