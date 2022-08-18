import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec

trait WireMessage

object WireMessage {
  val codec = discriminated[WireMessage]
    .by(uint16)
    .typecase(1, RequestBlock.codec)
    .typecase(2, AnswerBlock.codec)
}

case class RequestBlock(hash: ByteVector) extends WireMessage

object RequestBlock {
  val codec: Codec[RequestBlock] =
    (("hash" | bytes(32))).as[RequestBlock]
}

case class AnswerBlock(hash: ByteVector, block: Option[Block])
    extends WireMessage

object AnswerBlock {
  val codec: Codec[AnswerBlock] =
    (("hash" | bytes(32)) :: optional(bool(8), Block.codec)).as[AnswerBlock]
}
