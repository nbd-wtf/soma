package soma

import scala.collection.mutable.ArrayBuffer
import scodec.bits.ByteVector
import scoin._

class BMM(
    totalSize: Int = 144 * 365 * 100, // 100 years
    sequence: Int = 1, // minimum of 1 block of relative interval
    amount: Satoshi = 1871.sat
) {
  val priv = Crypto.PrivateKey(1)
  val pub = priv.publicKey

  case class FloatingTransaction(
      tx: Transaction,
      sig: ByteVector64
  ) {
    def fill(
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

  def precompute(startAt: Int, count: Int): Seq[Transaction] = {
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
      stored.append(next)
      currTx = previous
      currIdx = currIdx - 1
    }

    stored.reverse.toSeq
  }

  def lastTx: FloatingTransaction = {
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

    val internalPubkey = Crypto.XOnlyPublicKey(pub)
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
    val nextTx = next.fill(tx, script, controlBlock)

    (FloatingTransaction(tx, sig), nextTx)
  }
}
