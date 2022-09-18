import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec
import scoin.ByteVector32
import scoin.CommonCodecs.bytes32

trait WireMessage

object WireMessage {
  val codec = discriminated[WireMessage]
    .by(uint16)
    .typecase(1, RequestBlock.codec)
    .typecase(2, AnswerBlock.codec)
}

case class RequestBlock(hash: ByteVector32) extends WireMessage

object RequestBlock {
  val codec: Codec[RequestBlock] =
    (("hash" | bytes32)).as[RequestBlock]
}

case class AnswerBlock(hash: ByteVector32, block: Option[Block])
    extends WireMessage

object AnswerBlock {
  val codec: Codec[AnswerBlock] =
    (("hash" | bytes32) :: optional(bool(8), Block.codec)).as[AnswerBlock]
}
