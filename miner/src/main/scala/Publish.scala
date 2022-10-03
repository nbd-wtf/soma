import scala.util.{Success, Failure}
import scala.collection.mutable.Map
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.Ordering.Implicits._
import scala.util.chaining._
import scodec.bits.ByteVector
import com.github.lolgab.httpclient.{Request, Method}
import scoin._

object Publish {
  import Main.{logger, rpc}

  var overseerURL: String = ""

  // { bmmHash: block }
  val pendingPublishedBlocks: Map[ByteVector32, ByteVector] = Map.empty

  def publishBmmHash(
      blockHash: ByteVector32,
      block: ByteVector,
      fee: Satoshi
  ): Future[String] = {
    logger.info
      .item("bmm-hash", blockHash.toHex)
      .item("fee", fee)
      .msg("publishing bmm block!")

    val finalPsbt = for {
      listfunds <- rpc("listfunds")
      overseerResponse <- Request()
        .method(Method.GET)
        .url(overseerURL)
        .future()
      listaddrs <- rpc("dev-listaddrs", ujson.Obj("bip32_max_index" -> 0))
    } yield {
      val outputs = listfunds("outputs").arr
        .filter(utxo =>
          utxo("status").str == "confirmed"
          // && utxo("reserved").bool == false
        )
      val nextPsbt = overseerResponse
        .pipe(_.body)
        .pipe(ujson.read(_))
        .pipe(_("next")("psbt").str)
        .pipe(Psbt.fromBase64(_))
        .get
      val ourScriptPubKey = listaddrs
        .pipe(_("addresses")(0)("pubkey").str)
        .pipe(ByteVector.fromValidHex(_))
        .pipe(Crypto.hash160(_))
        .pipe(pubkeyhash =>
          ByteVector.concat(
            List(ByteVector.fromByte(0), ByteVector.fromByte(20), pubkeyhash)
          )
        )
      val inputSum = outputs
        .map(utxo =>
          MilliSatoshi(
            utxo("amount_msat").numOpt
              .map(_.toLong)
              .getOrElse(utxo("amount_msat").str.takeWhile(_.isDigit).toLong)
          ).toSatoshi
        )
        .sum

      if (inputSum < fee)
        throw new Exception(
          s"not enough unreserved and confirmed outputs to pay fee of $fee"
        )

      Psbt(
        nextPsbt.global.tx
          .copy(
            txIn = List(
              nextPsbt.global.tx.txIn(0)
            ) ++
              // add our inputs
              outputs
                .map(utxo =>
                  TxIn(
                    outPoint = OutPoint(
                      ByteVector32(
                        ByteVector
                          .fromValidHex(utxo("txid").str)
                          .reverse
                      ),
                      utxo("output").num.toInt
                    ),
                    sequence = 0,
                    signatureScript = ByteVector.empty
                  )
                ),
            txOut = List(
              // the canonical output
              nextPsbt.global.tx.txOut(0),

              // add our OP_RETURN
              TxOut(
                Satoshi(0L),
                Script.write(List(OP_RETURN, OP_PUSHDATA(blockHash)))
              ),

              // add our change
              TxOut(inputSum - fee, ourScriptPubKey)
            )
          )
      )
        .pipe(interm =>
          interm.copy(inputs = nextPsbt.inputs(0) +: interm.inputs.drop(1))
        )
        .pipe(Psbt.toBase64(_))
    }

    val done = for {
      psbt <- finalPsbt
      _ = logger.debug.item("psbt", psbt).msg("")
      _ <- rpc(
        "reserveinputs",
        ujson.Obj(
          "psbt" -> psbt,
          "reserve" -> 1,
          "exclusive" -> false
        )
      )
      signedPsbt <- rpc("signpsbt", ujson.Obj("psbt" -> psbt))
        .map(_("signed_psbt").str)
      _ = logger.debug.item("signed", signedPsbt).msg("")

      // save these as an attempted tx-block publish
      _ = { pendingPublishedBlocks += (blockHash -> block) }
      _ <- Datastore.storePendingBlocks()

      res <- rpc("sendpsbt", ujson.Obj("psbt" -> signedPsbt))
      _ = logger.debug.item("res", res).msg("")
    } yield {
      val txid = res("txid").str
      logger.info
        .item("bmm-hash", blockHash.toHex)
        .item("bitcoin-txid", txid)
        .msg("published bmm tx")

      txid
    }

    done.andThen {
      case Failure(err) =>
        logger.err.item(err).msg("failed to publish bitcoin tx")
      case Success(_) =>
    }
  }
}
