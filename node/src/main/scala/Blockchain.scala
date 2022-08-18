import util.chaining.scalaUtilChainingOps
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec
import upickle.default._

import Picklers.given

case class Block(header: BlockHeader, txs: List[Tx]) {
  def hash: ByteVector = header.hash
}

object Block {
  val codec: Codec[Block] =
    (("header" | BlockHeader.codec) ::
      ("txs" | list(Tx.codec))).as[Block]

  given ReadWriter[Block] = macroRW

  def makeBlock(txs: List[Tx]): Either[String, Block] =
    if (txs.exists(_.validate() == false))
      Left("the transactions must be valid")
    else {
      val previous = Database
        .getLatestKnownBlock()
        .map(block => block.header.previous)
        .getOrElse(ByteVector.fill(0)(32)) // default to 32 zeroes
      val block = Block(
        header = BlockHeader(previous, Tx.merkleRoot(txs)),
        txs = txs.toList
      )
      Right(block)
    }
}

case class BlockHeader(previous: ByteVector, merkleRoot: ByteVector) {
  def hash: ByteVector = Crypto.sha256(previous ++ merkleRoot)
}

object BlockHeader {
  val codec: Codec[BlockHeader] =
    (("previous" | bytes(32)) ::
      ("merkleRoot" | bytes(32))).as[BlockHeader]

  given ReadWriter[BlockHeader] = macroRW
}

case class Tx(
    counter: Int,
    asset: ByteVector,
    from: ByteVector,
    to: ByteVector,
    signature: ByteVector = ByteVector.empty
) {
  def hash: ByteVector =
    Crypto.sha256(Tx.codec.encode(this).toOption.get.toByteVector)

  def messageToSign: ByteVector = Tx.codec
    .encode(copy(signature = ByteVector.empty))
    .toOption
    .get
    .toByteVector

  def withSignature(privateKey: ByteVector): Tx = {
    require(
      ByteVector.fromUint8Array(
        Secp256k1Schnorr.getPublicKey(privateKey.toUint8Array)
      ) == from,
      "must sign tx with `from` key."
    )

    copy(signature =
      ByteVector.fromUint8Array(
        Secp256k1Schnorr.signSync(
          messageToSign.toUint8Array,
          privateKey.toUint8Array
        )
      )
    )
  }

  def signatureValid(): Boolean = Secp256k1Schnorr.verifySync(
    signature.toUint8Array,
    messageToSign.toUint8Array,
    from.toUint8Array
  )

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
      ("asset" | bytes(32)) ::
      ("from" | bytes(32)) ::
      ("to" | bytes(32)) ::
      ("signature" | bytes(32))).as[Tx]

  given ReadWriter[Tx] = macroRW

  def build(asset: ByteVector, to: ByteVector, privateKey: ByteVector): Tx = Tx(
    asset = asset,
    to = to,
    from = ByteVector.fromUint8Array(
      Secp256k1Schnorr.getPublicKey(privateKey.toUint8Array)
    ),
    counter = Database.getNextCounter(asset)
  ).withSignature(privateKey)

  def merkleRoot(txs: List[Tx]): ByteVector =
    txs.map(_.hash).pipe(merkle(_))

  def merkle(hashes: List[ByteVector]): ByteVector =
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
}

object Picklers {
  given ReadWriter[ByteVector] =
    readwriter[String].bimap[ByteVector](_.toHex, ByteVector.fromValidHex(_))
}
