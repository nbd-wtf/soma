import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec

trait WireMessage

object WireMessage {
  val codec = discriminated[WireMessage]
    .by(uint16)
    .typecase(1, RequestBlock.codec)
}

case class RequestBlock(hash: ByteVector) extends WireMessage

object RequestBlock {
  val codec: Codec[RequestBlock] =
    (("hash" | bytes)).as[RequestBlock]
}

case class AnswerBlock(hash: ByteVector, block: Option[Block])
