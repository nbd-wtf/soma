import upickle.default.*
import scodec.bits.ByteVector
import scoin.{Block => *, BlockHeader => *, *}
import soma.*

object Picklers {
  given ReadWriter[Block] = ReadWriter.join(
    macroR,
    writer[ujson.Value].comap[Block](b =>
      ujson.Obj(
        "id" -> writeJs(b.hash),
        "height" -> writeJs(b.height.toInt),
        "header" -> writeJs(b.header),
        "txs" -> writeJs(b.txs)
      )
    )
  )

  given ReadWriter[BlockHeader] = macroRW

  given ReadWriter[Tx] = ReadWriter.join(
    macroR,
    writer[ujson.Value].comap[Tx](tx =>
      ujson.Obj(
        "id" -> writeJs(tx.hash),
        "counter" -> writeJs(tx.counter),
        "asset" -> writeJs(tx.asset),
        "from" -> writeJs(tx.from),
        "to" -> writeJs(tx.to),
        "signature" -> writeJs(tx.signature)
      )
    )
  )

  given ReadWriter[ByteVector] =
    readwriter[String].bimap[ByteVector](_.toHex, ByteVector.fromValidHex(_))
  given ReadWriter[ByteVector32] =
    readwriter[ByteVector].bimap[ByteVector32](_.bytes, ByteVector32(_))
  given ReadWriter[ByteVector64] =
    readwriter[ByteVector].bimap[ByteVector64](_.bytes, ByteVector64(_))
  given ReadWriter[XOnlyPublicKey] =
    readwriter[ByteVector32]
      .bimap[XOnlyPublicKey](_.value, XOnlyPublicKey(_))
}
