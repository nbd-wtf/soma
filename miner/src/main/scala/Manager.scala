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

  // { otxid -> full_otx }
  val idToFullTx: Map[String, ByteVector] = Map.empty

  // paid invoices but not settled
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
                if (pendingTransactions.size > 0)
                  publishBlock().onComplete {
                    case Success(_) =>
                    case Failure(err) =>
                      logger.warn.item(err).msg("failed to publish")
                  }
              case Some(bmmHash)
                  if Publish.pendingPublishedBlocks.contains(bmmHash) =>
                val block = Publish.pendingPublishedBlocks(bmmHash)
                logger.debug
                  .item("block", block.toHex)
                  .msg(
                    "this bmm tx was published by us -- publishing our pending block"
                  )
                val publish = for {
                  // publish the corresponding block
                  res <- Node.registerBlock(block)
                  _ = logger.debug.item("ok", res("ok").bool).msg("published")

                  //   check which of our pending transactions were
                  //   included and settle the corresponding lightning invoices
                  blockOpt <- Node.getBlock(bmmHash)
                } yield blockOpt match {
                  case None =>
                    throw new Exception(
                      "we couldn't get our own block we had just published?"
                    )
                  case Some(block) =>
                    val txs = block("txs").arr.map(tx => tx("id").str)
                    pendingTransactions.filterInPlace {
                      case (id, (promise, _, _, _)) =>
                        if (txs.contains(id)) {
                          logger.info
                            .item("tx", id)
                            .msg("transaction included in a block")
                          promise.success(true) // settle the payment
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

                publish.onComplete {
                  case Success(_) =>
                  case Failure(err) =>
                    logger.err.item(err).msg("failed to publish our own block")

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
                    case Success(res) =>
                      System.err.println(s"pending futures ${res.toList}")
                      // if we got here we can safely assume all futures
                      //   have completed successfully
                      pendingTransactions
                        .filterInPlace { case (id, (promise, _, _, _)) =>
                          if (validations(id).value.get.getOrElse(false)) {
                            logger.info
                              .item("tx", id)
                              .msg(
                                "keeping transaction and trying again in the next block"
                              )
                            // this is still valid, so we
                            true // keep it in the list
                          } else {
                            logger.info
                              .item("tx", id)
                              .msg("transaction not valid anymore, dropping it")
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
                  logger.debug
                    .item("tx", otxid)
                    .msg(
                      "dropping transaction since it has been here for too long"
                    )
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

          // parse the otxid from the invoice description and get the full tx from our cache
          val otxid = invoice("description").str
            .dropWhile(_ != '<')
            .drop(1)
            .takeWhile(_ != '>')

          idToFullTx.get(otxid) match {
            case None =>
              // TODO we should actually be persisting our pending transactions to disk at all times
              //      otherwise we risk losing funds
              logger.warn.msg("we don't know about this, discard the payment")
              promise.success(false)

            case Some(otx) =>
              val operation = for {
                currentBitcoinBlock <- rpc("getchaininfo")
                  .map(_("headercount").num.toLong)
                  .map(BlockHeight(_))

                // check if this same transaction was already here and cancel it
                _ = pendingTransactions.get(otxid).foreach {
                  case (p, _, _, _) =>
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
                case Success(_) =>
              }
          }
      }

    promise.future
  }

  def publishBlock(): Future[Unit] = for {
    // get the block to publish containing all our pending txs from the node
    (bmmhash, oblock) <- Node.getNextBlock(
      pendingTransactions.map(_._2._2).toList
    )

    // republish block bmm tx
    txid <- Publish.publishBmmHash(bmmhash, oblock, totalFees)

    _ = logger.debug.item("bitcoin-txid", txid).msg("published bmm hash")
  } yield ()

  def makeInvoiceForTransaction(
      otx: ByteVector,
      amount: Satoshi
  ): Future[(String, String)] =
    for {
      ok <- Node.validateTx(otx)
      _ = require(ok, "transaction is not valid")
      otxid = Crypto.sha256(otx.dropRight(32)).toHex
      inv <- rpc(
        "invoice",
        ujson.Obj(
          "amount_msat" -> amount.toMilliSatoshi.toLong,
          "label" -> s"miner:${otxid.take(6)}#${Crypto.randomBytes(3).toHex}",
          "description" -> s"publish <${otxid}>",
          "cltv" -> 144
        )
      )
      bolt11 = inv("bolt11").str
      hash = inv("payment_hash").str
    } yield {
      idToFullTx += (otxid -> otx)
      (bolt11, hash)
    }

  def totalFees: Satoshi =
    pendingTransactions.map(_._2._3).fold(Satoshi(0))(_ + _)
}
