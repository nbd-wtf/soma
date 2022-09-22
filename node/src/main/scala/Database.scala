import scala.util.chaining._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scala.scalajs.js.JSConverters._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scodec.bits.ByteVector
import scoin.{Crypto, ByteVector32, ByteVector64}

object Database {
  private var db: Database = _

  def init(): Unit = {
    db = new Database(
      s"${Config.datadir}/db-${Config.genesisTx.take(5)}.sqlite"
    )
    db.exec(
      "CREATE TABLE IF NOT EXISTS blocks (bmmheight INT UNIQUE NOT NULL, txid TEXT UNIQUE NOT NULL, bmmhash BLOB, block BLOB, blockheight INT)"
    )
    db.exec(
      "CREATE TABLE IF NOT EXISTS current (k TEXT PRIMARY KEY, blockheight INT NOT NULL, bmmhash BLOB NOT NULL REFERENCES blocks (bmmhash))"
    )
    db.exec(
      "CREATE TABLE IF NOT EXISTS state (asset BLOB PRIMARY KEY, owner BLOB NOT NULL, counter INT NOT NULL)"
    )
  }

  private lazy val getLatestTxStmt = db.prepare(
    "SELECT txid, bmmheight, bmmhash FROM blocks ORDER BY bmmheight DESC LIMIT 1"
  )
  def getLatestTx(): Option[(String, Int, Option[ByteVector32])] =
    getLatestTxStmt
      .get()
      .toOption
      .map(row =>
        (
          row.selectDynamic("txid").asInstanceOf[String],
          row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
          row
            .selectDynamic("bmmhash")
            .asInstanceOf[Uint8Array]
            .pipe(v => if v == null then None else Some(v))
            .map(ByteVector.fromUint8Array(_).pipe(ByteVector32(_)))
        )
      )

  private lazy val getMissingBlocksStmt = db.prepare(
    "SELECT DISTINCT bmmhash FROM blocks WHERE bmmhash IS NOT NULL AND block IS NULL"
  )
  def getMissingBlocks(): List[ByteVector32] = getMissingBlocksStmt
    .all()
    .toList
    .map(row =>
      ByteVector
        .fromUint8Array(row.selectDynamic("bmmhash").asInstanceOf[Uint8Array])
        .pipe(ByteVector32(_))
    )

  private lazy val getUniqueHighestBlockHeightStmt = db.prepare(
    "SELECT blockheight FROM blocks WHERE blockheight = max(blockheight)"
  )
  def getUniqueHighestBlockHeight(): Option[Int] = {
    val rows = getUniqueHighestBlockHeightStmt.all()
    if rows.size != 1 then None
    else Some(rows(0).selectDynamic("blockheight").asInstanceOf[Double].toInt)
  }

  private lazy val getBlocksBetweenHereAndThereStmt = db.prepare(
    "SELECT block, blockheight FROM blocks WHERE blockheight > ? AND blockheight <= ? ORDER BY blockheight"
  )
  def getBlocksBetweenHereAndThere(
      here: Int,
      hereHash: ByteVector32,
      there: Int
  ): Iterable[Block] =
    getBlocksBetweenHereAndThereStmt
      .all(here, there)
      .map(row =>
        (
          asBlockOpt(row),
          row.selectDynamic("blockheight").asInstanceOf[Double].toInt
        )
      )
      .groupBy((_, h) => h) // group together blocks at the same height

      // discard the height
      .map((h, entries) => entries.map((blocks, _) => blocks))

      // for all groups of the same height, pick the block whose .header.previous
      //   matches the previous block we had picked, starting with the bmmhash
      //   provided as an argument
      .scanLeft[(ByteVector32, Option[Block])]((hereHash, None))(
        (prev, group) =>
          group
            .find(block =>
              (prev._1, block) match {
                case (prevHash, Some(curr)) =>
                  curr.header.previous == prevHash
                case _ => false;
              }
            )
            .flatten
            .pipe(blockOpt =>
              (blockOpt.map(_.hash).getOrElse(ByteVector32.Zeroes) -> blockOpt)
            )
      )
      .drop(1) // drop first dummy block we've started the scan with
      .map((_, blockOpt) => blockOpt)
      .takeWhile(_.isDefined) // stop whenever we find a block we don't have
      .map(_.get) // return the blocks

  private lazy val getBmmTxsSinceStmt = db.prepare(
    "SELECT txid, bmmheight, bmmhash FROM blocks WHERE bmmheight > ? ORDER BY bmmheight"
  )
  def getBmmTxsSince(
      bmmheight: Int
  ): List[(String, Int, Option[ByteVector32])] =
    getBmmTxsSinceStmt
      .all(bmmheight)
      .toList
      .map { row =>
        (
          row.selectDynamic("txid").asInstanceOf[String],
          row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
          row
            .selectDynamic("bmmhash")
            .asInstanceOf[Uint8Array]
            .pipe(v => if v == null then None else Some(v))
            .map(
              ByteVector
                .fromUint8Array(_)
                .pipe(ByteVector32(_))
            )
        )
      }

  private lazy val addTxStmt =
    db.prepare(
      "INSERT OR IGNORE INTO blocks (bmmheight, txid, bmmhash) VALUES (?, ?, ?)"
    )
  def addTx(
      bmmHeight: Int,
      txid: String,
      bmmHash: Option[ByteVector32]
  ): Unit =
    addTxStmt.run(
      bmmHeight,
      txid,
      bmmHash.map[SQLiteValue](x => x).getOrElse[SQLiteValue](null)
    )

  private lazy val getBlockStmt =
    db.prepare("SELECT block FROM blocks WHERE bmmHash = ?")
  def getBlock(bmmHash: ByteVector): Option[Block] =
    asBlockOpt(getBlockStmt.get(bmmHash))

  private lazy val getBlockAtHeightStmt =
    db.prepare("SELECT block FROM blocks WHERE blockheight = ?")
  def getBlockAtHeight(height: Int): Option[Block] =
    asBlockOpt(getBlockAtHeightStmt.get(height))

  private lazy val getBlockAtBmmHeightStmt =
    db.prepare("SELECT block FROM blocks WHERE bmmheight = ?")
  def getBlockAtBmmHeight(bmmHeight: Int): Option[Block] =
    asBlockOpt(getBlockAtBmmHeightStmt.get(bmmHeight))

  private[this] def asBlockOpt(row: js.UndefOr[js.Dynamic]): Option[Block] =
    row.toOption
      .flatMap(row =>
        row
          .selectDynamic("block")
          .asInstanceOf[Uint8Array]
          .pipe(v => if v == null then None else Some(v))
          .flatMap(uint8arr =>
            Block.codec
              .decode(
                ByteVector
                  .fromUint8Array(uint8arr)
                  .toBitVector
              )
              .toOption
              .map(_.value)
          )
      )

  private lazy val insertBlockStmt =
    db.prepare(
      "UPDATE blocks SET block = ?, blockheight = ? WHERE bmmhash = ? AND block IS NULL RETURNING 1"
    )
  def insertBlock(hash: ByteVector32, block: Block): Boolean = {
    Block.codec
      .encode(block)
      .toOption
      .flatMap { bs =>
        val r = insertBlockStmt
          .get(
            bs.toByteVector,
            if block.header.previous == ByteVector32.Zeroes then 1
            else
              getBlockHeight(block.header.previous)
                .map[SQLiteValue](_ + 1)
                .getOrElse[SQLiteValue](null)
            ,
            hash.bytes.toUint8Array
          )
        r.toOption

        // TODO update all blockheight of the following bmm blocks that match this one recursively
      }
      .isDefined
  }

  private lazy val getBlockHeightStmt =
    db.prepare("SELECT blockheight FROM blocks WHERE bmmhash = ?")
  def getBlockHeight(hash: ByteVector32): Option[Int] = getBlockHeightStmt
    .get(hash)
    .toOption
    .map(_.selectDynamic("blockheight").asInstanceOf[Double].toInt)

  private lazy val getLatestKnownBlockStmt = db.prepare(
    "SELECT bmmheight, block FROM blocks WHERE block IS NOT NULL ORDER BY blockheight DESC LIMIT 1"
  )
  def getLatestKnownBlock(): Option[(Int, Block)] = getLatestKnownBlockStmt
    .get()
    .toOption
    .map { row =>
      (
        row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
        row
          .selectDynamic("block")
          .asInstanceOf[Uint8Array]
          .pipe(ByteVector.fromUint8Array(_).toBitVector)
          .pipe(Block.codec.decode(_).require.value)
      )
    }

  private lazy val verifyAssetOwnerAndCounterStmt = db.prepare(
    "SELECT 1 FROM state WHERE asset = ? AND owner = ? AND counter = ?"
  )
  def verifyAssetOwnerAndCounter(
      asset: ByteVector32,
      owner: Crypto.XOnlyPublicKey,
      counter: Int
  ): Boolean =
    verifyAssetOwnerAndCounterStmt
      .get(asset, owner, counter)
      .isDefined

  private lazy val verifyAssetDoesntExistStmt = db.prepare(
    "SELECT 1 FROM state WHERE asset = ?"
  )
  def verifyAssetDoesntExist(asset: ByteVector32): Boolean =
    verifyAssetDoesntExistStmt
      .get(asset)
      .isEmpty

  private lazy val getCurrentCounterStmt = db.prepare(
    "SELECT counter FROM state WHERE asset = ?"
  )
  def getNextCounter(asset: ByteVector32): Int =
    getCurrentCounterStmt
      .get(asset)
      .map(_.selectDynamic("counter").asInstanceOf[Int])
      .getOrElse(1)

  private lazy val getAccountAssetsStmt = db.prepare(
    "SELECT asset FROM state WHERE owner = ?"
  )
  def getAccountAssets(pubkey: Crypto.XOnlyPublicKey): List[ByteVector] =
    getAccountAssetsStmt
      .all(pubkey)
      .toList
      .map(row =>
        ByteVector
          .fromUint8Array(row.selectDynamic("asset").asInstanceOf[Uint8Array])
      )

  private lazy val getAssetOwnerStmt = db.prepare(
    "SELECT owner FROM state WHERE asset = ?"
  )
  def getAssetOwner(asset: ByteVector32): Option[Crypto.XOnlyPublicKey] =
    getAssetOwnerStmt
      .get(asset)
      .toOption
      .map(_.selectDynamic("owner").asInstanceOf[Uint8Array])
      .map(
        ByteVector
          .fromUint8Array(_)
          .pipe(ByteVector32(_))
          .pipe(Crypto.XOnlyPublicKey(_))
      )

  private lazy val getCurrentTipStmt =
    db.prepare("SELECT blockheight, bmmhash FROM current")
  def getCurrentTip(): (Int, ByteVector32) = getCurrentTipStmt
    .get()
    .toOption
    .map(row =>
      (
        row.selectDynamic("blockheight").asInstanceOf[Double].toInt,
        row
          .selectDynamic("bmmhash")
          .asInstanceOf[Uint8Array]
          .pipe(v => ByteVector32(ByteVector.fromUint8Array(v)))
      )
    )
    .getOrElse((0, ByteVector32.Zeroes))

  private lazy val updateCurrentTipStmt =
    db.prepare(
      "INSERT INTO current (k, blockheight, bmmhash) VALUES ('processed', 1, ?) ON CONFLICT (k) DO UPDATE SET blockheight = blockheight + ?, bmmhash = ?"
    )
  private lazy val updateAssetOwnershipStmt =
    db.prepare(
      "INSERT INTO state (owner, asset, counter) VALUES (?, ?, 1) ON CONFLICT (asset) DO UPDATE SET owner = ?, counter = ? WHERE asset = ? AND counter = ?"
    )

  def processBlock(block: Block): Unit = {
    db.exec("BEGIN TRANSACTION")

    println(s"processing block ${block.hash}")
    updateCurrentTipStmt.run(block.hash, 1, block.hash)

    block.txs.foreach { tx =>
      println(s"  ~ tx ${tx.hash.toHex.take(5)}: ${tx.asset.toHex
          .take(5)} from ${tx.from.toHex.take(5)} to ${tx.to.toHex.take(5)}")
      updateAssetOwnershipStmt.run(
        tx.to,
        tx.asset,
        tx.to,
        tx.counter + 1,
        tx.asset,
        tx.counter
      )
    }

    db.exec("COMMIT")
  }

  def rewindBlock(block: Block): Unit = {
    db.exec("BEGIN TRANSACTION")

    println(s"rewinding block ${block.hash}")
    updateCurrentTipStmt.run(block.header.previous, -1, block.header.previous)

    block.txs.foreach { tx =>
      println(s"  ~ tx ${tx.hash.toHex.take(5)}: ${tx.asset.toHex
          .take(5)} from ${tx.from.toHex.take(5)} to ${tx.to.toHex.take(5)}")
      updateAssetOwnershipStmt.run(
        tx.from,
        tx.asset,
        tx.from,
        tx.counter,
        tx.asset,
        tx.counter + 1
      )
    }

    db.exec("COMMIT")
  }
}

sealed trait SQLiteValue extends js.Any

object SQLiteValue {
  implicit def fromDouble(x: Double): SQLiteValue = x.asInstanceOf[SQLiteValue]
  implicit def fromInt(x: Int): SQLiteValue = x.toDouble
  implicit def fromString(x: String): SQLiteValue = x.asInstanceOf[SQLiteValue]
  implicit def fromBoolean(x: Boolean): SQLiteValue =
    x.asInstanceOf[SQLiteValue]
  implicit def fromUint8Array(x: Uint8Array): SQLiteValue =
    x.asInstanceOf[SQLiteValue]
  implicit def fromByteVector(x: ByteVector): SQLiteValue = x.toUint8Array
  implicit def fromByteVector32(x: ByteVector32): SQLiteValue =
    x.bytes.toUint8Array
  implicit def fromByteVector64(x: ByteVector64): SQLiteValue =
    x.bytes.toUint8Array
  implicit def fromXOnlyPublicKey(x: Crypto.XOnlyPublicKey): SQLiteValue =
    x.value.bytes
  implicit def fromNull(x: Null): SQLiteValue = x.asInstanceOf[SQLiteValue]
}

@js.native
@JSImport("better-sqlite3", JSImport.Default)
class Database(path: String) extends js.Object {
  def prepare(sql: String): Statement = js.native
  def exec(sql: String): Unit = js.native
}

@js.native
trait Statement extends js.Object {
  def run(params: SQLiteValue*): Unit = js.native
  def get(params: SQLiteValue*): js.UndefOr[js.Dynamic] = js.native
  def all(params: SQLiteValue*): js.Array[js.Dynamic] = js.native
}
