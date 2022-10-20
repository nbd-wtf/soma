import scala.util.{Success, Failure}
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

  // (bitcoinTxSpent, { bmmHash: block } )
  //   this thing is a tuple so we can more easily keep track of where we are.
  //   every time we save one pending block we check if there are already other
  //     blocks pending for that same bitcoinTxSpent, if yes we can add to the
  //     same map, otherwise we can delete whatever we had before -- since that
  //     block is already gone -- and create a new tuple.
  var pendingPublishedBlocks: (String, Map[ByteVector32, ByteVector]) =
    ("", Map.empty)

  // these are just informative and not used or relied upon
  var lastBitcoinTx: Option[String] = None
  var lastPublishedBlock: Option[String] = None
  var lastRegisteredBlock: Option[String] = None

  def publishBmmHash(
      blockHash: ByteVector32,
      block: ByteVector,
      fee: Satoshi
  ): Future[String] = {
    logger.info
      .item("bmm-hash", blockHash.toHex)
      .item("fee", fee)
      .msg("publishing bmm tx!")

    val finalPsbt = for {
      listfunds <- rpc("listfunds")
      overseerResponse <- Request()
        .method(Method.GET)
        .url(overseerURL)
        .future()
      listaddrs <- rpc("dev-listaddrs", ujson.Obj("bip32_max_index" -> 0))
    } yield {
      val outputs = listfunds("outputs").arr
        .filter(utxo => utxo("status").str == "confirmed")

      require(
        overseerResponse.code > 0 && overseerResponse.body.size > 0,
        "no response from overseer"
      )
      val nextPsbt = Psbt
        .fromBase64(ujson.read(overseerResponse.body)("next")("psbt").str)
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

      val patchedPsbt = Psbt(
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
      ).pipe(pp => pp.copy(inputs = nextPsbt.inputs(0) +: pp.inputs.drop(1)))

      // save these as an attempted tx-block publish
      val bitcoinTxSpent = patchedPsbt.global.tx.txIn(0).outPoint.txid.toHex
      pendingPublishedBlocks = pendingPublishedBlocks match {
        case (btxid, blocks) if btxid == bitcoinTxSpent =>
          (btxid, blocks + (blockHash -> block))
        case _ =>
          (bitcoinTxSpent, Map(blockHash -> block))
      }
      Datastore.storePendingBlocks()

      // return the patched psbt ready for CLN to sign as base64
      Psbt.toBase64(patchedPsbt)
    }

    for {
      psbt <- finalPsbt
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

      res <- rpc("sendpsbt", ujson.Obj("psbt" -> signedPsbt))
      _ = logger.debug.item("res", res).msg("")
    } yield {
      val txid = res("txid").str
      lastBitcoinTx = Some(txid)
      lastPublishedBlock = Some(block.toHex)
      logger.info
        .item("bmm-hash", blockHash.toHex)
        .item("bitcoin-txid", txid)
        .msg("published bmm tx")

      txid
    }
  }
}
