import scala.util.{Success, Failure}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.Ordering.Implicits._
import scala.util.chaining._
import scodec.bits.ByteVector
import scoin._
import soma.BMM

object Publish {
  import Main.logger
  import CLN.rpc

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

  // precomputed bmm txs
  val bmm = new BMM(totalSize = 100, amount = 1234.sat, sequence = 1)
  bmm.load()
  if (bmm.txs.size == 0) {
    bmm.precompute(0, 50)
    bmm.store()
  }

  def publishBmmHash(
      blockHash: ByteVector32,
      bmmheight: Int,
      block: ByteVector,
      fee: Satoshi,
      bmmprevtxid: ByteVector32
  ): Future[String] = {
    logger.info
      .item("bmm-hash", blockHash.toHex)
      .item("fee", fee)
      .msg("publishing bmm tx!")

    val precomputed = bmm.get(bmmheight, bmmprevtxid)
    System.err.println(
      s"precomputed $bmmheight: ${bmm.get(bmmheight, bmmprevtxid).global.tx}"
    )

    addInputsAndChange(
      Psbt(
        precomputed.global.tx.copy(txOut =
          List(
            // the canonical output
            precomputed.global.tx.txOut(0),

            // add our OP_RETURN
            TxOut(
              Satoshi(0L),
              Script.write(List(OP_RETURN, OP_PUSHDATA(blockHash)))
            )
          )
        )
      ),
      fee
    )
      .map(pp => pp.copy(inputs = precomputed.inputs(0) +: pp.inputs.drop(1)))
      .flatMap { psbt =>
        // save these as an attempted tx-block publish
        val bitcoinTxSpent = psbt.global.tx.txIn(0).outPoint.txid.toHex
        pendingPublishedBlocks = pendingPublishedBlocks match {
          case (btxid, blocks) if btxid == bitcoinTxSpent =>
            (btxid, blocks + (blockHash -> block))
          case _ =>
            (bitcoinTxSpent, Map(blockHash -> block))
        }
        Datastore.storePendingBlocks()

        // return the patched psbt ready for CLN to sign as base64
        publishPsbt(psbt).map { txid =>
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

  def publishGenesisTx(fee: Satoshi): Future[String] = {
    val genesis = Psbt(bmm.getGenesis())

    // make a psbt with our inputs
    addInputsAndChange(
      Psbt(genesis.global.tx.copy(txIn = List.empty)),
      fee + bmm.amount
    )
      .map(pp => pp.copy(inputs = pp.inputs))
      .flatMap(publishPsbt)
  }

  def addInputsAndChange(
      psbt: Psbt,
      fee: Satoshi
  ): Future[Psbt] = for {
    listfunds <- rpc("listfunds")
    listaddrs <- rpc("dev-listaddrs", ujson.Obj("bip32_max_index" -> 0))
  } yield {
    val outputs = listfunds("outputs").arr
      .filter(utxo => utxo("status").str == "confirmed")

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

    require(
      inputSum >= fee,
      s"not enough unreserved and confirmed outputs to pay fee of $fee"
    )

    Psbt(
      psbt.global.tx.copy(
        // add our inputs
        txIn = psbt.global.tx.txIn ++
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
        txOut =
          // add our change
          if (inputSum - fee > 500.sat)
            psbt.global.tx.txOut
              .appended(TxOut(inputSum - fee, ourScriptPubKey))
          else psbt.global.tx.txOut
      )
    )
  }

  def publishPsbt(psbt: Psbt): Future[String] = {
    val b64psbt = Psbt.toBase64(psbt)

    for {
      _ <- rpc(
        "reserveinputs",
        ujson.Obj(
          "psbt" -> b64psbt,
          "reserve" -> 1,
          "exclusive" -> false
        )
      )
      signedPsbt <- rpc("signpsbt", ujson.Obj("psbt" -> b64psbt))
        .map(_("signed_psbt").str)

      res <- rpc("sendpsbt", ujson.Obj("psbt" -> signedPsbt))
      _ = logger.debug.item("res", res).msg("")
    } yield res("txid").str
  }
}
