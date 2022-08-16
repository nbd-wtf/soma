import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec

case class Block(previous: ByteVector, txs: List[Tx])

object Block {
  val codec: Codec[Block] = (("previous" | bytes) :: ("txs" | txs)).as[Block]
}

case class Tx(
    counter: Int,
    asset: ByteVector,
    from: ByteVector,
    to: ByteVector,
    signature: ByteVector
)
