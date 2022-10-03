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

  // { invoice_hash -> waitinvoice_promise }
  val invoiceWaiters: Map[String, Set[Promise[Unit]]] = Map.empty

  // { txid -> full_tx }
  val idToFullTx: Map[String, ByteVector] = Map.empty

  // paid invoices but not settled
  val pendingTransactions: Map[String, (ByteVector, Satoshi, BlockHeight)] =
    Map.empty
  val pendingHtlcs: Map[String, Promise[Boolean]] = Map.empty

  // this debouncing works both for skipping the initial burst of blocks
  //   and for giving the node time to catch up with the most recent chain
  lazy val onBlock = Helpers.debounce(onBlock_, 15.seconds)

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
          logger.debug.item("bmms", bmms.size).msg("got new bmm entries")
          bmms.foreach { bmm =>
            logger.debug.item("bmm", bmm).msg("  ")

            bmm.hash match {
              case None => logger.debug.msg("no hash on this bmm tx")
              case Some(bmmHash)
                  if Publish.pendingPublishedBlocks.contains(bmmHash) =>
                val block = Publish.pendingPublishedBlocks(bmmHash)
                logger.debug
                  .item("hash", bmmHash)
                  .item("block", block.toHex)
                  .msg(
                    "this bmm tx was published by us -- registering our pending block"
                  )
                val register = for {
                  // register the corresponding block at the node
                  res <- Node.registerBlock(block)
                  _ = logger.debug.item("ok", res("ok").bool).msg("registered")

                  //   check which of our pending transactions were
                  //   included and settle the corresponding lightning invoices
                  blockOpt <- Node.getBlock(bmmHash)
                } yield blockOpt match {
                  case None =>
                    throw new Exception(
                      "we couldn't get our own block we had just registered?"
                    )
                  case Some(block) =>
                    logger.debug.item(block).msg("the block we just registered")
                    val txs = block("txs").arr.map(tx => tx("id").str)
                    pendingTransactions.filterInPlace { case (id, _) =>
                      if (txs.contains(id)) {
                        logger.info
                          .item("tx", id)
                          .msg("transaction included in a block")

                        // settle the payment
                        pendingHtlcs
                          .get(id)
                          .map(_.success(true))

                        false // exclude it from the list of pending
                      } else {
                        logger.info
                          .item("tx", id)
                          .msg(
                            "transaction not included, keeping it for the next"
                          )
                        true // keep it in the list of pending
                      }
                    }
                }

                register.onComplete {
                  case Success(_) =>
                  case Failure(err) =>
                    logger.err.item(err).msg("failed to register our own block")

                }
              case Some(bmmHash) =>
                logger.debug.msg(
                  "a bmm tx with a block hash from someone else"
                )
            }

            logger.debug.item("bmm", bmm).msg("store this as latest")
            latestSeen = bmm

            // fail all the transactions and cancel the lightning invoices
            //   if they're over their threshold bitcoin block height (we can't
            //   hold payments for too long)
            pendingTransactions.filterInPlace { case (id, (_, _, height)) =>
              if (height.toInt < bitcoinHeight) {
                // it's over the threshold, so
                logger.debug
                  .item("tx", id)
                  .msg(
                    "dropping transaction since it has been here for too long"
                  )
                // fail the payment
                pendingHtlcs
                  .get(id)
                  .map(_.success(false))
                false // remove it from list
              } else {
                // it is still ok, so
                true // keep it
              }
            }
            Datastore.storePendingTransactions()

            // give some time for the node to process the new block (if any)
            Timer.timeout(15.seconds) { () =>
              // then
              //   check which of our pending transactions are still valid
              //   after that block and fail the lightning payments
              //   corresponding to the invalid ones
              pendingTransactions.foreach { case (txid, (tx, _, _)) =>
                Node.validateTx(tx).onComplete {
                  case Success((_, ok)) if ok =>
                    logger.info
                      .item("tx", txid)
                      .msg(
                        "transaction still valid, keeping it and trying again in the next block"
                      )
                  case _ =>
                    logger.info
                      .item("tx", txid)
                      .msg("transaction not valid anymore, dropping it")
                    pendingHtlcs
                      .get(txid)
                      .map(_.success(false))
                    pendingTransactions.remove(txid)
                    Datastore.storePendingTransactions()
                }
              }

              // after we've gone through all the latest bmms
              //   in principle our pending transactions are still valid,
              //   so we try to publish a block again
              if (pendingTransactions.size > 0)
                logger.debug
                  .item("pending", pendingTransactions)
                  .msg(
                    "we still have pending transactions, try to publish a new block"
                  )

              publishBlock().onComplete {
                case Success(_) =>
                case Failure(err) =>
                  logger.warn.item(err).msg("failed to publish")
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

    rpc("listinvoices", ujson.Obj("payment_hash" -> hash))
      .map(res => res("invoices")(0))
      .onComplete {
        case Failure(err) =>
          logger.warn.item(err).msg("failed to call listinvoices")
          promise.failure(err)

        case Success(invoice) if !invoice("label").str.startsWith("miner:") =>
          logger.warn
            .item("label", invoice("label"))
            .msg("invoice not for our mining purposes")
          promise.success(false)

        case Success(invoice) =>
          // notify any listeners we might have
          invoiceWaiters
            .getOrElse(invoice("payment_hash").str, Set.empty)
            .foreach { p =>
              p.success(())
            }

          val fee = MilliSatoshi(invoice("msatoshi").num.toLong)

          // parse the txid from the invoice description and get the full tx from our cache
          val txid = invoice("description").str
            .dropWhile(_ != '<')
            .drop(1)
            .takeWhile(_ != '>')

          idToFullTx.get(txid) match {
            case None =>
              // we don't know about this invoice, but what if this is an HTLC being replayed by lightningd on us?
              pendingTransactions.get(txid) match {
                case Some(_) =>
                  // we must keep track of this htlc, although we have already processed this transaction
                  //   so don't do anything else
                  pendingHtlcs += txid -> promise
                case None =>
                  // yeah, we really don't know what this is
                  logger.warn.msg("unknown htlc, discarding")
                  promise.success(false)
              }

            case Some(tx) =>
              val operation = for {
                currentBitcoinBlock <- rpc("getchaininfo")
                  .map(_("headercount").num.toLong)
                  .map(BlockHeight(_))

                // check if this same transaction was already here and cancel it
                //   (i.e. they're just increasing the fee paid)
                _ = pendingHtlcs.get(txid).foreach { p =>
                  pendingHtlcs.remove(txid)
                  p.success(false)
                }

                // check if this is valid
                (_, ok) <- Node.validateTx(
                  tx,
                  pendingTransactions
                    // filter out this same one (in case it's a replacement)
                    .filter((id, _) => id != txid)
                    .values
                    .map((v, _, _) => v)
                    .toSet
                )

                _ = require(ok, "transaction is not valid anymore")

                // add this new one
                _ = {
                  logger.debug.item("txid", txid).msg("adding new pending tx")
                  pendingHtlcs += (txid -> promise)
                  pendingTransactions +=
                    (txid -> (tx, fee.truncateToSatoshi, currentBitcoinBlock + 104))
                  Datastore.storePendingTransactions()
                }

                _ <- publishBlock()
              } yield ()

              operation.onComplete {
                case Failure(err)
                    if err
                      .toString()
                      .contains("bad-txns-inputs-missingorspent") ||
                      err
                        .toString()
                        .contains("insufficient fee, rejecting replacement") =>
                  logger.debug
                    .item(err)
                    .msg("failed to publish bmm hash, but will try again later")

                case Failure(err) =>
                  logger.warn
                    .item(err)
                    .item("txid", txid)
                    .msg(
                      "failed to publish bmm hash, rejecting this transaction"
                    )
                  pendingTransactions.remove(txid)
                  Datastore.storePendingTransactions()
                  promise.failure(err)

                case Success(_) =>
              }
          }
      }

    promise.future
  }

  def publishBlock(): Future[Unit] = for {
    // get the block to publish containing all our pending txs from the node
    (bmmhash, block) <- Node.getNextBlock(
      pendingTransactions.values.map((tx, _, _) => tx).toList
    )

    // republish block bmm tx
    bmmTxid <- Publish.publishBmmHash(bmmhash, block, totalFees)

    _ = logger.debug.item("bmm-txid", bmmTxid).msg("published bmm hash")
  } yield ()

  def makeInvoiceForTransaction(
      tx: ByteVector,
      amount: Satoshi
  ): Future[(String, String)] =
    for {
      (txid, isValid) <- Node.validateTx(
        tx,
        pendingTransactions.values.map((tx, _, _) => tx).toSet
      )
      isReplacement = pendingTransactions.contains(txid)
      _ = require(isValid || isReplacement, "transaction is not valid")
      inv <- rpc(
        "invoice",
        ujson.Obj(
          "amount_msat" -> amount.toMilliSatoshi.toLong,
          "label" -> s"miner:${txid.take(6)}#${Crypto.randomBytes(3).toHex}",
          "description" -> s"publish <${txid}>",
          "cltv" -> 144
        )
      )
      bolt11 = inv("bolt11").str
      hash = inv("payment_hash").str
    } yield {
      idToFullTx += (txid -> tx)
      (bolt11, hash)
    }

  def totalFees: Satoshi =
    pendingTransactions.values.map((_, fees, _) => fees).sum
}
