package soma

import java.nio.file.{Paths, Path, Files}
import scala.collection.mutable.ArrayBuffer
import scala.util.chaining._
import scodec.bits.ByteVector
import scoin._
import scoin.Crypto.XOnlyPublicKey
import scoin.Protocol._

class BMM(
    val totalSize: Int = 144 * 365 * 100, // 100 years
    val sequence: Int = 1, // minimum of 1 block of relative interval
    val amount: Satoshi = 1871.sat
) {
  val priv = Crypto.PrivateKey(1)
  val pub = priv.publicKey

  var sigs: Seq[ByteVector64] = Seq.empty
  var startingAt: Int = _

  def getGenesis(): Transaction = {
    require(
      startingAt == 0,
      "can't get genesis if the 0th tx isn't precomputed"
    )
    val nextSig = sigs(1)

    Transaction(
      version = 2,
      lockTime = 0,
      txIn =
        List.empty, // must be filled by the initiator of the chain with their own inputs
      txOut = List(getTxOutFromNextSig(nextSig))
    )
  }

  def get(
      height: Int,
      prevOutTxId: ByteVector32
  ): (Transaction, TxOut, ByteVector) = {
    require(
      height >= startingAt && sigs.size > height - startingAt,
      s"requested transaction ($height) out of range ($startingAt-${startingAt + sigs.size}), must load or precompute before"
    )
    val nextSig = sigs(height - startingAt + 1)
    val currentSig = sigs(height - startingAt)

    val tx = Transaction(
      version = 2,
      lockTime = 0,
      txIn = List(
        TxIn(
          OutPoint(hash = prevOutTxId.reverse, index = 0),
          signatureScript = ByteVector.empty,
          1,
          witness = witnessFromSig(currentSig)
        )
      ),
      txOut = List(getTxOutFromNextSig(nextSig))
    )

    (
      tx,
      getTxOutFromNextSig(currentSig),
      tx.txOut(0).publicKeyScript
    )
  }

  val dir =
    Paths.get(System.getProperty("user.home"), ".config", "soma", "precomputed")
  dir.toFile().mkdirs()

  // store precomputed txs on filesystem
  def store(): Unit = {
    val groups = {
      // 6 weeks: the size of the precomputed batch of txs we store on a file and keep in memory
      val GROUP_SIZE = 144 * 7 * 6
      val firstGroupStart = startingAt % GROUP_SIZE
      val firstGroupSize = GROUP_SIZE - firstGroupStart
      val firstGroup = sigs.take(firstGroupSize)
      val groups =
        firstGroup +: sigs.drop(firstGroupSize).grouped(GROUP_SIZE).toSeq
      groups.zipWithIndex.map { (tx, i) =>
        val groupStart = i * GROUP_SIZE + startingAt / GROUP_SIZE
        val groupEnd = groupStart + GROUP_SIZE
        (tx, s"$groupStart-$groupEnd")
      }
    }

    groups.foreach { (group, name) =>
      System.err.println(s"storing $name with ${group.size} txs")
      val file = dir.resolve(name)
      val data = group.map(_.bytes.toArray).flatten
      Files.write(file, data.toArray)
    }
  }

  // load precomputed txs from filesystem
  def load(): Unit = {
    // just load the first file for now
    val sortedFilenames =
      dir.toFile().list().sortBy(_.takeWhile(_.isDigit).toInt)

    if (!sortedFilenames.isEmpty) {
      val firstFilename = sortedFilenames(0)
      val firstElement = firstFilename.takeWhile(_.isDigit).toInt
      val firstGroupName = firstElement.toString()
      val first = dir.resolve(firstGroupName).toFile()

      val bytes =
        ByteVector.view(Files.readAllBytes(dir.resolve(firstFilename)))
      sigs = bytes.grouped(64).map(ByteVector64(_)).toSeq

      System.err.println(s"loaded ${sigs.size} starting at $startingAt")
    }
  }

  def precompute(startAt: Int, count: Int): Unit = {
    var currIdx = totalSize
    var currSig = lastTxSignature()

    // regress until we reach the highest bmmtx number we want to keep
    while (currIdx >= startAt + count) {
      val previous = signTx(currSig)
      currSig = previous
      currIdx = currIdx - 1
    }

    // now regress until the lowest number and this time store everything
    val stored = ArrayBuffer.empty[ByteVector64]
    while (currIdx >= startAt) {
      stored += currSig
      val previous = signTx(currSig)
      currSig = previous
      currIdx = currIdx - 1
    }

    sigs = stored.reverse.toSeq
    startingAt = startAt
  }

  private def lastTxSignature(): ByteVector64 = {
    val tx = Transaction(
      version = 2,
      txIn = List(TxIn.placeholder(sequence)),
      txOut = List(
        TxOut(
          Satoshi(0),
          Script.write(
            List(
              OP_RETURN,
              OP_PUSHDATA(ByteVector.view("fim".getBytes()))
            )
          )
        )
      ),
      lockTime = 0
    )

    // compute the tx hash. since we're using anyprevoutanyscript we don't care about the inputs
    val hash = Transaction.hashForSigningSchnorr(
      tx,
      0,
      List(tx.txOut(0)),
      SIGHASH_ANYPREVOUTANYSCRIPT | SIGHASH_SINGLE,
      SigVersion.SIGVERSION_TAPSCRIPT,
      annex = None,
      tapleafHash = None
    )

    Crypto.signSchnorr(hash, priv, None)
  }

  private def signTx(sigNextTx: ByteVector64): ByteVector64 = {
    val tx = Transaction(
      version = 2,
      txIn = List(TxIn.placeholder(sequence)),
      txOut = List(getTxOutFromNextSig(sigNextTx)),
      lockTime = 0
    )

    // compute the tx hash. since we're using anyprevoutanyscript we don't care about the inputs
    val hash = Transaction.hashForSigningSchnorr(
      tx,
      0,
      List(tx.txOut(0)),
      SIGHASH_ANYPREVOUTANYSCRIPT | SIGHASH_SINGLE,
      SigVersion.SIGVERSION_TAPSCRIPT,
      annex = None,
      tapleafHash = None
    )

    // sign the transaction with the internal private key (not tweaked)
    Crypto.signSchnorr(hash, priv, None)
  }

  private def getTxOutFromNextSig(nextSig: ByteVector64): TxOut = {
    val merkleRoot = merkleRootFromSig(nextSig)

    // tweak the internal pubkey with the merkle root
    val (tweakedKey, parity) = pub.xonly.tapTweak(Some(merkleRoot))

    val controlBlock = ByteVector(
      (Script.TAPROOT_LEAF_TAPSCRIPT + (if (parity) 1 else 0)).toByte
    ) ++ pub.xonly.value

    TxOut(amount, List(OP_1, OP_PUSHDATA(tweakedKey)))
  }

  private def witnessFromSig(sig: ByteVector64): ScriptWitness = {
    val script = scriptFromSig(sig)
    val merkleRoot = merkleRootFromSig(sig)

    val (_, parity) = pub.xonly.tapTweak(Some(merkleRoot))

    val controlBlock = ByteVector(
      (Script.TAPROOT_LEAF_TAPSCRIPT + (if (parity) 1 else 0)).toByte
    ) ++ pub.xonly.value

    ScriptWitness(
      List(
        Script.write(script),
        controlBlock
      )
    )
  }

  private def merkleRootFromSig(sig: ByteVector64): ByteVector32 = {
    val script = scriptFromSig(sig)

    // simple script tree with a single element
    val scriptTree = ScriptTree.Leaf(
      ScriptLeaf(0, Script.write(script), Script.TAPROOT_LEAF_TAPSCRIPT)
    )
    ScriptTree.hash(scriptTree)
  }

  def scriptFromSig(sig: ByteVector64): List[ScriptElt] =
    // the script contains the signature of the next transaction (in case we're just
    // committing to an output with a tweaked key) or the current transaction (in
    // case we're publishing the witness in a txIn)
    List(
      OP_PUSHDATA(
        sig ++ ByteVector
          .fromInt((SIGHASH_ANYPREVOUTANYSCRIPT | SIGHASH_SINGLE), 1)
      ),
      OP_1,
      OP_CHECKSIG
    )
}
