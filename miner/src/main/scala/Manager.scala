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

      // this debouncing works both for skipping the initial burst of blocks
      //   and for giving the node time to catch up with the most recent chain
  lazy val onBlock = Helpers.debounce(onBlock_, 10.seconds)

  def onBlock_(bitcoinHeight: Int): Future[Unit] = {
    logger.debug
      .item("latest", latestSeen)
      .item("pending-blocks", Publish.pendingPublishedBlocks.keySet)
      .msg("checking if the bmm chain has advanced")

    // check if new blocks were mined in the parallel chain
    Node
      .getBmmSince(latestSeen.height)
      .onComplete {
        case Failure(err) =>
          logger.err.item(err).msg("failed to get bmm entries")
        case Success(bmms) =>
          bmms.map { bmm =>
            logger.debug.item("bmm", bmm).msg("got new bmm transaction")

            bmm.hash match {
              case None =>
                logger.debug.msg("no hash on this bmm tx")
                // if there is no hash we assume this wasn't published by us
                //   so all our pending bitcoin transactions can be discarded
                Publish.pendingPublishedBlocks.clear()
                // but in principle our pending transactions are still valid,
                //   so we try to publish a block again
                publishBlock().onComplete {
                  case Success(_) =>
                  case Failure(err) =>
                    logger.warn.item(err).msg("failed to publish")
                }
              case Some(bmmHash)
                  if Publish.pendingPublishedBlocks.contains(bmmHash) =>
                logger.debug.msg("this bmm tx was published by us")
                for {
                  // publish the corresponding block
                  _ <- Node.registerBlock(
                    Publish.pendingPublishedBlocks(bmmHash)
                  )

                  //   check which of our pending transactions were
                  //   included and settle the corresponding lightning invoices
                  blockOpt <- Node.getBlock(bmmHash)
                } yield blockOpt match {
                  case None =>
                    throw new Exception(
                      "we've failed to publish our own block?"
                    )
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
                logger.debug.msg(
                  "a bmm tx with a block hash from someone else"
                )
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
                        .msg(
                          "failed to validate transactions after new block"
                        )
                  }
                }
            }

            logger.debug.item("bmm", bmm).msg("store this as latest")
            latestSeen = bmm

            // finally, fail all the transactions and cancel the lightning invoices
            //   if they're over their threshold bitcoin block height (we can't
            //   hold payments for too long)
            pendingTransactions.filterInPlace {
              case (otxid, (promise, _, _, height)) =>
                if (height.toInt < bitcoinHeight) {
                  // it's over the threshold, so
                  logger.debug.item("tx", otxid).msg("dropping")
                  promise.success(false) // fail the payment
                  false // remove it from list
                } else {
                  // it is still ok, so
                  true // keep it
                }
            }
          }
      }

    Future {}
  }

  def acceptPayment(hash: String): Future[Boolean] = {
    // a payment has arrived asking us to include a tx,
    //   we will hold it as we try to publish a block
    val promise = Promise[Boolean]()

    val operation = for {
      res <- rpc("listinvoices", ujson.Obj("payment_hash" -> hash))
      fee = MilliSatoshi(res("invoices")(0)("msatoshi").num.toLong)

      // parse the tx hex from the invoice description
      otx = res("invoices")(0)("description").str
        .dropWhile(_ != '<')
        .drop(1)
        .takeWhile(_ != '>')
        .pipe(ByteVector.fromValidHex(_))

      // get id/hash for this transaction
      otxid = Crypto.sha256(otx.dropRight(32)).toHex

      currentBitcoinBlock <- rpc("getchaininfo")
        .map(_("headercount").num.toLong)
        .map(BlockHeight(_))

      // check if this same transaction was already here and cancel it
      _ = pendingTransactions.get(otxid).foreach { case (p, _, _, _) =>
        p.success(false)
      }

      // add this new one
      _ = {
        logger.debug.item("txid", otxid).msg("adding new pending tx")

        pendingTransactions +=
          (otxid -> (promise, otx, fee.truncateToSatoshi, currentBitcoinBlock + 104))
      }

      _ <- publishBlock()
    } yield ()

    operation.onComplete {
      case Failure(err) =>
        logger.warn.item(err).msg("failed to publish bmm hash")
        promise.failure(err)
      case Success(res) =>
    }

    promise.future
  }

  def publishBlock(): Future[Unit] = for {
    // get the block to publish containing all our pending txs from the node
    oblock <- Node.getNextBlock(
      pendingTransactions.map(_._2._2).toList
    )

    // republish block bmm tx
    txid <- Publish.publishBmmHash(oblock, totalFees)

    _ = logger.debug.item("bitcoin-txid", txid).msg("published bmm hash")
  } yield ()

  def addNewTransaction(otx: ByteVector, amount: Satoshi): Future[String] =
    for {
      ok <- Node.validateTx(otx)
      _ = require(ok, "transaction is not valid")
      otxid = Crypto.sha256(otx).toHex
      inv <- rpc(
        "invoice",
        ujson.Obj(
          "amount_msat" -> amount.toMilliSatoshi.toLong,
          "label" -> s"miner:${otxid.take(6)}#${Crypto.randomBytes(3).toHex}",
          "description" -> s"publish <${otx.toHex}>",
          "cltv" -> 144
        )
      )
    } yield inv("bolt11").str

  def totalFees: Satoshi =
    pendingTransactions.map(_._2._3).fold(Satoshi(0))(_ + _)
}
