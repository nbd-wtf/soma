import scodec.bits.ByteVector
import scoin.{Crypto, ByteVector32, ByteVector64}
import openchain._

object Blockchain {
  def makeBlock(
      txs: Seq[Tx],
      parent: Option[ByteVector32] = None
  ): Either[String, Block] =
    if (!Tx.validateTxs(txs.toSet))
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
    Tx.validateTxs(txs.toSet) && ■ value validateTxs is not a member of o
    header.merkleRoot == Tx.merkleRoot(txs)

  def validateTxs(txs: Set[Tx]): Boolean =
    txs.forall(thisTx =>
      validate(thisTx, txs.filterNot(_ == thisTx).toSet) == true ■ value v
    )

  def validateTx(tx: Tx, otherTxsInTheBlock: Set[Tx] = Set.empty): Boolean = {
    val ownerCorrect = Database.verifyAssetOwnerAndCounter(
      asset,
      from,
      counter
    )
    val isNewAsset = counter == 1 && Database.verifyAssetDoesntExist(asset)
    val assetNotBeingTransactedAlready =
      !otherTxsInTheBlock.exists(tx => tx.asset == this.asset)

    (ownerCorrect || isNewAsset) && assetNotBeingTransactedAlready && signatureValid()
  }

  def buildTx(
      asset: ByteVector32,
      to: Crypto.XOnlyPublicKey,
      privateKey: Crypto.PrivateKey
  ): Tx = Tx(
    asset = asset,
    to = to,
    from = privateKey.publicKey.xonly,
    counter = Database.getNextCounter(asset)
  ).withSignature(privateKey)
}
