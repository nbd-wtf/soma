import scodec.bits.ByteVector
import scoin.{randomBytes32, ByteVector32, ByteVector64}
import soma.*

object Blockchain {
  def makeBlock(
      txs: Seq[Tx],
      parent: Option[ByteVector32] = None,
      arbitrary: Option[ByteVector32] = None
  ): Either[String, Block] =
    if (!validateTxs(txs.toSet))
      Left("one or more of the transaction is invalid")
    else {
      val previous = parent
        .flatMap(Database.getBlock(_))
        .orElse(
          Database
            .getLatestKnownBlock()
            .map { case (_, block) => block }
        )

      val previousHash = previous
        .map(_.hash)
        .getOrElse(ByteVector32.Zeroes) // default to 32 zeroes

      val previousHeight = previous.map(_.height).getOrElse(0L)

      val block = Block(
        previousHeight + 1,
        header = BlockHeader(
          previousHash,
          Tx.merkleRoot(txs),
          arbitrary.getOrElse(randomBytes32())
        ),
        txs = txs.toList
      )
      Right(block)
    }

  def validateBlock(block: Block): Boolean =
    // block doesn't mint more assets than it should
    block.txs.filter(_.isNewAsset).size <= Block.MaxMintsPerBlock &&
      // all transactions are valid
      validateTxs(block.txs.toSet) &&
      // merkle root is valid
      block.header.merkleRoot == Tx.merkleRoot(block.txs) &&
      // block height is valid
      (
        block.height == 1 && block.header.previous == ByteVector32.Zeroes ||
          Database
            .getBlockHeight(block.header.previous)
            .map(_ + 1 == block.height)
            .getOrElse(false)
      )

  def validateTxs(txs: Set[Tx]): Boolean =
    txs.forall(thisTx =>
      validateTx(thisTx, txs.filterNot(_ == thisTx).toSet) == true
    )

  def validateTx(tx: Tx, otherTxsInTheBlock: Set[Tx] = Set.empty): Boolean =
    tx.isNewAsset || {
      val ownerCorrect = Database.verifyAssetOwnerAndCounter(
        tx.asset,
        tx.from,
        tx.counter
      )

      val assetNotBeingTransactedAlready =
        !otherTxsInTheBlock.exists(_.asset == tx.asset)

      ownerCorrect &&
      assetNotBeingTransactedAlready &&
      tx.signatureValid()
    }
}
