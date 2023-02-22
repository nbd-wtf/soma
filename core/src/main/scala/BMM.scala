package soma

import java.nio.file.{Paths, Path, Files}
import scala.collection.mutable.ArrayBuffer
import scodec.bits.ByteVector
import scoin._
import scoin.Protocol._

class BMM(
    val totalSize: Int = 144 * 365 * 100, // 100 years
    val sequence: Int = 1, // minimum of 1 block of relative interval
    val amount: Satoshi = 1871.sat
) {
  val priv = Crypto.PrivateKey(1)
  val pub = priv.publicKey

  var txs: Seq[Transaction] = Seq.empty
  var startingAt: Int = _

  def getGenesis(): Transaction = {
    require(
      startingAt == 0,
      "can't get genesis if the 0th tx isn't precomputed"
    )
    txs(0)
  }

  def get(height: Int, prevOutTxId: ByteVector32): Psbt = {
    require(
      height >= startingAt && txs.size > height - startingAt,
      s"requested transaction ($height) out of range ($startingAt-${startingAt + txs.size}), must load or precompute before"
    )
    val tx = txs(height - startingAt)
    val bound =
      // update here since only at runtime we know the previous tx id
      tx.copy(
        txIn = Seq(
          tx.txIn(0)
            .copy(outPoint =
              tx.txIn(0).outPoint.copy(hash = prevOutTxId.reverse)
            )
        )
      )

    Psbt(bound)
      .updateWitnessInputTx(txs(height - startingAt - 1), 0)
      .get
      .finalizeWitnessInput(0, bound.txIn(0).witness)
      .get
      .updateWitnessOutput(0)
      .get
  }

  case class FloatingTransaction(
      tx: Transaction,
      sig: ByteVector64
  ) {
    def bind(
        input: Transaction,
        script: List[ScriptElt],
        controlBlock: ByteVector
    ): Transaction =
      tx.copy(txIn =
        List(
          TxIn(
            OutPoint(input, 0),
            signatureScript = ByteVector.empty,
            1,
            witness = ScriptWitness(
              List(
                Script.write(script),
                controlBlock
              )
            )
          )
        )
      )
  }

  def precompute(startAt: Int, count: Int): Unit = {
    var currIdx = totalSize
    var currTx = lastTx

    // regress until we reach the highest bmmtx number we want to keep
    while (currIdx >= startAt + count) {
      val (previous, _) = regress(currTx)
      currTx = previous
      currIdx = currIdx - 1
    }

    // now regress until the lowest number and this time store everything
    val stored = ArrayBuffer.empty[Transaction]
    while (currIdx >= startAt) {
      val (previous, next) = regress(currTx)
      stored += next
      currTx = previous
      currIdx = currIdx - 1
    }

    txs = stored.reverse.toSeq
    startingAt = startAt
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
      val firstGroup = txs.take(firstGroupSize)
      val groups =
        firstGroup +: txs.drop(firstGroupSize).grouped(GROUP_SIZE).toSeq
      groups.zipWithIndex.map { (tx, i) =>
        val groupStart = i * GROUP_SIZE + startingAt / GROUP_SIZE
        val groupEnd = groupStart + GROUP_SIZE
        (tx, s"$groupStart-$groupEnd")
      }
    }

    groups.foreach { (group, name) =>
      System.err.println(s"storing $name with ${group.size} txs")
      val file = dir.resolve(name).toFile()
      Protocol.writeCollection(
        group,
        new java.io.FileOutputStream(file),
        Protocol.PROTOCOL_VERSION
      )
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

      txs = Protocol.readCollection[Transaction](
        new java.io.FileInputStream(dir.resolve(firstFilename).toFile()),
        Protocol.PROTOCOL_VERSION
      )
      startingAt = firstElement

      System.err.println(s"loaded ${txs.size} starting at $startingAt")
    }
  }

  private def lastTx: FloatingTransaction = {
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

    val sig = Crypto.signSchnorr(hash, priv, None)

    FloatingTransaction(tx, sig)
  }

  def regress(next: FloatingTransaction): (FloatingTransaction, Transaction) = {
    // the script contains the signature of the next transaction
    val script = List(
      OP_PUSHDATA(
        next.sig ++ ByteVector
          .fromInt((SIGHASH_ANYPREVOUTANYSCRIPT | SIGHASH_SINGLE), 1)
      ),
      OP_1,
      OP_CHECKSIG
    )

    // simple script tree with a single element
    val scriptTree = ScriptTree.Leaf(
      ScriptLeaf(0, Script.write(script), Script.TAPROOT_LEAF_TAPSCRIPT)
    )
    val merkleRoot = ScriptTree.hash(scriptTree)

    val internalPubkey = pub.xonly
    val tweakedKey = internalPubkey.outputKey(Some(merkleRoot))
    val parity = tweakedKey.publicKey.isOdd

    val controlBlock = ByteVector(
      (Script.TAPROOT_LEAF_TAPSCRIPT + (if (parity) 1 else 0)).toByte
    ) ++ internalPubkey.value

    val tx = Transaction(
      version = 2,
      txIn = List(TxIn.placeholder(sequence)),
      txOut = List(TxOut(amount, List(OP_1, OP_PUSHDATA(tweakedKey)))),
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

    val sig = Crypto.signSchnorr(hash, priv, None)
    val nextTx = next.bind(tx, script, controlBlock)

    (FloatingTransaction(tx, sig), nextTx)
  }
}
