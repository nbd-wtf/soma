package openchain

import scala.util.chaining._
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec
import scoin.{Crypto, ByteVector32, ByteVector64}
import scoin.CommonCodecs.{bytes32, bytes64, xonlypublickey}

case class Block(header: BlockHeader, txs: List[Tx]) {
  def hash: ByteVector32 = header.hash
}

object Block {
  val codec: Codec[Block] =
    (("header" | BlockHeader.codec) ::
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
}

object Tx {
  val codec: Codec[Tx] =
    (("counter" | uint16) ::
      ("asset" | bytes32) ::
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
