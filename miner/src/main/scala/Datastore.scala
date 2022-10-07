import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}
import scodec.bits.ByteVector
import scoin._

object Datastore {
  import Main.{logger, rpc}

  // ~
  // pending transactions

  final val pendingTransactionsKey = List("openchain", "pending-transactions")
  val loadingPendingTransactions: Future[Unit] =
    getJSON(pendingTransactionsKey)
      .andThen {
        case Failure(err) =>
          logger.warn.item(err).msg("failed to load pending transactions")
        case Success(None) =>
          logger.info.msg("no pending transactions found")
        case Success(Some(v: ujson.Obj)) =>
          logger.info.item(v).msg("loaded pending transactions")

          v.value.foreach { (id, data) =>
            for {
              metadata <- data.arrOpt
              if metadata.size == 3
              tx <- metadata(0).strOpt.flatMap(ByteVector.fromHex(_))
              fee <- metadata(1).numOpt.map(n => Satoshi(n.toLong))
              bitcoinHeight <- metadata(2).numOpt.map(n =>
                BlockHeight(n.toLong)
              )
            } yield {
              Manager.pendingTransactions += id -> (tx, fee, bitcoinHeight)
            }
          }
      }
      .map(_ => ())

  def storePendingTransactions(): Future[Unit] =
    writeJSON(
      pendingTransactionsKey,
      ujson.Obj.from(
        Manager.pendingTransactions.toList
          .map { case (id, (tx, fee, bitcoinHeight)) =>
            (
              id,
              ujson.Arr(
                tx.toHex,
                fee.toLong.toInt,
                bitcoinHeight.toLong.toInt
              )
            )
          }
      )
    )

  // ~
  // pending blocks

  final val pendingBlocksKey = List("openchain", "pending-blocks")
  val loadingPendingBlocks: Future[Unit] =
    getJSON(pendingBlocksKey)
      .andThen {
        case Failure(err) =>
          logger.warn.item(err).msg("failed to load pending blocks")
        case Success(None) =>
          logger.info.msg("no pending blocks found")
        case Success(Some(v: ujson.Obj)) =>
          logger.info.item(v).msg("loaded pending blocks")

          val bitcoinTxSpent = v("bitcoinTxSpent").str
          val blocks = v("blocks").obj.value
            .map { (bmmHashHex, value) =>
              for {
                blockHex <- value.strOpt
                bmmHash <- ByteVector.fromHex(bmmHashHex)
                if bmmHash.size == 32
                bmmHash32 = ByteVector32(bmmHash)
                block <- ByteVector.fromHex(blockHex)
              } yield {
                bmmHash32 -> block
              }
            }
            .collect { case Some(t) => t }
            .toMap

          Publish.pendingPublishedBlocks = (bitcoinTxSpent, blocks)
      }
      .map(_ => ())

  def storePendingBlocks(): Future[Unit] = {
    val (bitcoinTxSpent, blocks) = Publish.pendingPublishedBlocks

    writeJSON(
      pendingBlocksKey,
      ujson.Obj(
        "bitcoinTxSpent" -> bitcoinTxSpent,
        "blocks" -> ujson.Obj.from(
          blocks.toList
            .map((bmmHash, block) => (bmmHash.toHex, block.toHex))
        )
      )
    )
  }

  // ~
  // utils
  private def getJSON(key: List[String]): Future[Option[ujson.Value]] =
    rpc("listdatastore", ujson.Obj("key" -> key))
      .map(res =>
        res("datastore").arr.headOption
          .map(entry =>
            ByteVector
              .fromValidHex(entry("hex").str)
              .decodeStringLenient()(UTF_8)
          )
          .map(ujson.read(_))
      )

  private def writeJSON(key: List[String], value: ujson.Value): Future[Unit] =
    rpc(
      "datastore",
      ujson.Obj(
        "mode" -> "create-or-replace",
        "key" -> key,
        "hex" -> ByteVector(ujson.writeToByteArray(value)).toHex
      )
    ).map(_ => ())
}
