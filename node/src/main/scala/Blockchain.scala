import util.chaining.scalaUtilChainingOps
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec
import upickle.default._
import scoin.{Crypto, ByteVector32, ByteVector64}
import scoin.CommonCodecs.{bytes32, bytes64, xonlypublickey}

import Picklers.given

case class Block(header: BlockHeader, txs: List[Tx]) {
  def hash: ByteVector32 = header.hash

  def validate(): Boolean =
    Tx.validateTxs(txs.toSet) &&
      header.merkleRoot == Tx.merkleRoot(txs)
}

object Block {
  val codec: Codec[Block] =
    (("header" | BlockHeader.codec) ::
      ("txs" | list(Tx.codec))).as[Block]

  given ReadWriter[Block] = macroRW

  def makeBlock(
      txs: Seq[Tx],
      parent: Option[ByteVector32] = None
  ): Either[String, Block] =
    if (Tx.validateTxs(txs.toSet)) Left("one or more transactions are invalid")
    else {
      val previous = parent
        .orElse(
          Database
            .getLatestKnownBlock()
            .map { case (_, block) => block.header.previous }
        )
        .getOrElse(ByteVector32.Zeroes) // default to 32 zeroes
      val block = Block(
        header = BlockHeader(previous, Tx.merkleRoot(txs)),
        txs = txs.toList
      )
      Right(block)
    }
}

case class BlockHeader(previous: ByteVector32, merkleRoot: ByteVector32) {
  def hash: ByteVector32 = Crypto.sha256(previous ++ merkleRoot)
}

object BlockHeader {
  val codec: Codec[BlockHeader] =
    (("previous" | bytes32) ::
      ("merkleRoot" | bytes32)).as[BlockHeader]

  given ReadWriter[BlockHeader] = macroRW
}

case class Tx(
    counter: Int,
    asset: ByteVector32,
    from: Crypto.XOnlyPublicKey,
    to: Crypto.XOnlyPublicKey,
    signature: ByteVector64 = ByteVector64.Zeroes
) {
  def hash: ByteVector32 =
    Crypto.sha256(Tx.codec.encode(this).toOption.get.toByteVector)

  def messageToSign: ByteVector = Tx.codec
    .encode(copy(signature = ByteVector64.Zeroes))
    .toOption
    .get
    .toByteVector

  def withSignature(privateKey: Crypto.PrivateKey): Tx = {
    require(
      privateKey.publicKey.xonly == from,
      "must sign tx with `from` key."
    )

    copy(signature =
      Crypto
        .signSchnorr(
          Crypto.sha256(messageToSign),
          privateKey,
          None
        )
    )
  }

  def signatureValid(): Boolean =
    Crypto.verifySignatureSchnorr(Crypto.sha256(messageToSign), signature, from)

  def validate(pendingTxs: Set[Tx] = Set.empty): Boolean =
    // check signature
    signatureValid() &&
      // check if this asset really belongs to this person
      (Database.verifyAssetOwnerAndCounter(
        asset,
        from,
        counter - 1
      ) ||
        // or it's a new asset they are creating
        (counter == 1 && Database.verifyAssetDoesntExist(asset))) &&
      // and also this same asset can't be moving in this same block
      !pendingTxs.exists(tx => tx.asset == this.asset)
}

object Tx {
  val codec: Codec[Tx] =
    (("counter" | uint16) ::
      ("asset" | bytes32) ::
      ("from" | xonlypublickey) ::
      ("to" | xonlypublickey) ::
      ("signature" | bytes64)).as[Tx]

  given ReadWriter[Tx] = macroRW

  def build(
      asset: ByteVector32,
      to: Crypto.XOnlyPublicKey,
      privateKey: Crypto.PrivateKey
  ): Tx = Tx(
    asset = asset,
    to = to,
    from = privateKey.publicKey.xonly,
    counter = Database.getNextCounter(asset)
  ).withSignature(privateKey)

  def merkleRoot(txs: Seq[Tx]): ByteVector32 =
    txs.map(_.hash).pipe(merkle(_))

  def merkle(hashes: Seq[ByteVector32]): ByteVector32 =
    hashes
      .grouped(2)
      .map(_.toList match {
        case leaf1 :: leaf2 :: Nil => Crypto.sha256(leaf1 ++ leaf2)
        case singleleaf :: Nil     => singleleaf
        case _ =>
          throw new Exception(
            "list bigger than 2 elements or empty? shouldn't happen."
          )
      })
      .pipe(hashes => merkle(hashes.toList))

  def validateTxs(txs: Set[Tx]): Boolean =
    txs.exists(thisTx =>
      thisTx.validate(txs.filterNot(_ == thisTx).toSet) == false
    )
}

object Picklers {
  given ReadWriter[ByteVector] =
    readwriter[String].bimap[ByteVector](_.toHex, ByteVector.fromValidHex(_))

  given ReadWriter[ByteVector32] =
    readwriter[ByteVector].bimap[ByteVector32](_.bytes, ByteVector32(_))

  given ReadWriter[ByteVector64] =
    readwriter[ByteVector].bimap[ByteVector64](_.bytes, ByteVector64(_))

  given ReadWriter[Crypto.XOnlyPublicKey] =
    readwriter[ByteVector32]
      .bimap[Crypto.XOnlyPublicKey](_.value, Crypto.XOnlyPublicKey(_))

  given ReadWriter[Tx] =
    readwriter[ujson.Value].bimap[Tx](
      tx => writeJs(tx).obj.addOne("id" -> tx.hash.toHex),
      json => read(json)
    )
}
