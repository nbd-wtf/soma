import scala.collection.mutable.Map
import scala.concurrent.{Promise, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.chaining._
import scala.util.{Try, Success, Failure}
import scalanative.unsigned._
import scala.scalanative.loop.Timer
import scodec.bits.ByteVector
import scoin._

case class Bmm(
    txid: String,
    height: Int,
    hash: Option[ByteVector32]
)

object Manager {
  import Main.{rpc, logger}

  var latestSeen = Bmm("", 0, None)
  val pendingTransactions
      : Map[String, (Promise[Boolean], ByteVector, Satoshi, BlockHeight)] =
    Map.empty

  def onBlock(bitcoinHeight: Int): Unit =
    // check if new blocks were mined in the parallel chain
    Node
      .getBmmSince(latestSeen.height)
      .foreach(_.map { bmm =>
        bmm.hash match {
          case None =>
            // if there is no hash we assume this wasn't published by us
            //   so all our pending bitcoin transactions can be discarded
            //   but in principle our pending transactions are still valid.
            Publish.pendingPublishedBlocks.clear()
          case Some(bmmHash)
              if Publish.pendingPublishedBlocks.contains(bmmHash) =>
            // this hash was published by us
            for {
              // publish the corresponding block
              _ <- Node.registerBlock(Publish.pendingPublishedBlocks(bmmHash))

              //   check which of our pending transactions were
              //   included and settle the corresponding lightning invoices
              blockOpt <- Node.getBlock(bmmHash)
            } yield blockOpt match {
              case None =>
                throw new Error("we've failed to publish our own block?")
              case Some(block) =>
                val txs = block("txs").arr.map(tx => tx("id").str)
                pendingTransactions.filterInPlace {
                  case (id, (promise, _, _, _)) =>
                    if (txs.contains(id)) {
                      // this was included, so we
                      promise.success(true) // settle the payment
                      false // exclude it from the list of pending
                    } else {
                      // this wasn't included, so we
                      true // keep it in the list of pending
                    }
                }
            }
          case Some(bmmHash) =>
            // otherwise, give it a time for the block to be published,
            Timer.timeout(5.seconds) { () =>
              // then
              //   check which of our pending transactions are still valid
              //   after that block and fail the lightning payments
              //   corresponding to the invalid ones
              val validations =
                pendingTransactions.mapValues { case (_, otx, _, _) =>
                  Node.validateTx(otx)
                }

              Future.sequence(validations.values).onComplete {
                case Success(_) =>
                  // if we got here we can safely assume all futures
                  //   have completed successfully
                  pendingTransactions
                    .filterInPlace { case (id, (promise, _, _, _)) =>
                      if (validations(id).value.get.get) {
                        // this is still valid, so we
                        true // keep it in the list
                      } else {
                        // this isn't valid anymore, so we
                        promise.success(false) // fail the payment
                        false // remove it from the list
                      }
                    }
                case Failure(err) =>
                  logger.debug
                    .item(err)
                    .msg("failed to validate transactions after new block")
              }
            }
        }

        // finally, fail all the transactions and cancel the lightning invoices
        //   if they're over their threshold bitcoin block height (we can't
        //   hold payments for too long)
        pendingTransactions.filterInPlace { case (_, (promise, _, _, height)) =>
          if (height.toInt < bitcoinHeight) {
// it's over the threshold, so
            promise.success(false) // fail the payment
            false // remove it from list
          } else {
// it is still ok, so
            true // keep it
          }
        }
      })

  def onPayment(label: String): Future[Boolean] = {
    // a payment has arrived, we will hold it
    // as we try to publish a block containing it
    val promise = Promise[Boolean]()

    (for {
      res <- rpc("listinvoices", ujson.Obj("label" -> label))
      fee = MilliSatoshi(res("invoices")(0)("msatoshi").num.toLong)
      otx = res("invoices")(0)("description").str
        .dropWhile(_ != ':')
        .pipe(ByteVector.fromValidHex(_))
      otxid = Crypto.sha256(otx.dropRight(32)).toHex
      oblock <- Node.getNextBlock(
        pendingTransactions.map(_._2._2).toList
      )
      currentBitcoinBlock <- rpc("getchaininfo")
        .map(_("headercount").num.toLong)
        .map(BlockHeight(_))
    } yield {
      // check if this same transaction was already here and cancel it
      pendingTransactions.get(otxid).foreach { case (p, _, _, _) =>
        p.success(false)
      }

      // add this new one
      pendingTransactions += (otxid -> (promise, otx, fee.truncateToSatoshi, currentBitcoinBlock + 104))

      // republish block bmm tx
      Publish.publishBlock(oblock, totalFees)
    }).onComplete {
      case Failure(err) => promise.failure(err)
      case Success(res) =>
    }

    promise.future
  }

  def addNewTransaction(otx: ByteVector, amount: Satoshi): Future[String] =
    for {
      ok <- Node.validateTx(otx)
      _ = require(ok, "transaction is not valid")
      otxid = Crypto.sha256(otx).toHex
      inv <- rpc(
        "invoice",
        ujson.Obj(
          "amount_msat" -> amount.toMilliSatoshi.toLong,
          "label" -> s"miner:${otxid}",
          "description" -> s"publish the following transaction: ${otx.toHex}",
          "cltv" -> 144
        )
      )
    } yield inv("bolt11").str

  def totalFees: Satoshi =
    pendingTransactions.map(_._2._3).fold(Satoshi(0))(_ + _)
}
