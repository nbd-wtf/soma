package soma

import scala.util.chaining.*
import scodec.bits.ByteVector
import scodec.codecs.*
import scodec.Codec
import scoin.*
import scoin.CommonCodecs.{bytes32, bytes64, xonlypublickey}

case class Block(height: Long, header: BlockHeader, txs: List[Tx]) {
  def hash: ByteVector32 = header.hash
  def encoded: ByteVector = Block.codec.encode(this).require.toByteVector
}

object Block {
  val MaxMintsPerBlock = 500

  val codec: Codec[Block] =
    (("height" | uint32) ::
      ("header" | BlockHeader.codec) ::
      ("txs" | list(Tx.codec))).as[Block]
}

case class BlockHeader(
    previous: ByteVector32,
    merkleRoot: ByteVector32,
    arbitrary: ByteVector32
) {
  def hash: ByteVector32 = Crypto.sha256(previous ++ merkleRoot ++ arbitrary)
}

object BlockHeader {
  val codec: Codec[BlockHeader] =
    (("previous" | bytes32) ::
      ("merkleRoot" | bytes32) ::
      ("arbitrary" | bytes32)).as[BlockHeader]
}

case class Tx(
    counter: Int,
    asset: Int,
    from: XOnlyPublicKey,
    to: XOnlyPublicKey,
    signature: ByteVector64 = ByteVector64.Zeroes
) {
  def hash: ByteVector32 =
    Crypto.sha256(Tx.codec.encode(this).toOption.get.toByteVector)
  def encoded: ByteVector = Tx.codec.encode(this).require.toByteVector

  // to mind a new asset, make blank transaction
  def isNewAsset = counter == 0 && asset == 0

  def assetName(blockHeight: Long, txIndex: Int): String =
    if !isNewAsset then asset.toHexString
    else (blockHeight * Block.MaxMintsPerBlock + txIndex).toHexString

  private def messageToSign: ByteVector = Tx.codec
    .encode(copy(signature = ByteVector64.Zeroes))
    .toOption
    .get
    .toByteVector

  def withSignature(privateKey: PrivateKey): Tx = {
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
    Crypto.verifySignatureSchnorr(signature, Crypto.sha256(messageToSign), from)
}

object Tx {
  val codec: Codec[Tx] =
    (("counter" | uint16) ::
      ("asset" | int32) ::
      ("from" | xonlypublickey) ::
      ("to" | xonlypublickey) ::
      ("signature" | bytes64)).as[Tx]

  def merkleRoot(txs: Seq[Tx]): ByteVector32 =
    if txs.size == 0 then ByteVector32.Zeroes
    else txs.map(_.hash).pipe(merkle(_))

  def merkle(hashes: Seq[ByteVector32]): ByteVector32 =
    if hashes.size == 1 then hashes(0)
    else
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
