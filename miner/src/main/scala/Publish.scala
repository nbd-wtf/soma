import scala.collection.mutable.Map
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.chaining._
import scodec.bits.ByteVector
import com.github.lolgab.httpclient.{Request, Method}
import ujson._
import scoin._

object Publish {
  import Main.rpc

  var overseerURL: String = ""

  // { bmmHash: block }
  val pendingPublishedBlocks: Map[ByteVector32, ByteVector] = Map.empty

  def publishBlock(block: ByteVector, fee: Satoshi): Future[Unit] = {
    val blockHash = Crypto.sha256(block)

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
          utxo("status").str == "confirmed" &&
            utxo("reserved").bool == false
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
        .map(utxo => MilliSatoshi(utxo("amount_msat").num.toLong).toSatoshi)
        .fold(Satoshi(0))(_ + _)

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

    for {
      psbt <- finalPsbt
      _ <- rpc("reserveinputs", ujson.Obj("psbt" -> psbt))
      signedPsbt <- rpc("signpsbt", ujson.Obj("psbt" -> psbt))
        .map(_("signed_psbt").str)
      res <- rpc("sendpsbt", ujson.Obj("psbt" -> signedPsbt))
    } yield {
      val txid = res("txid").str

      // save these as an attempted tx-block publish
      pendingPublishedBlocks += (blockHash -> block)
    }
  }
}
