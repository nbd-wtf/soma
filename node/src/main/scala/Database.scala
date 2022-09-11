import scala.util.chaining._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scala.scalajs.js.JSConverters._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scodec.bits.ByteVector

object Database {
  private var db: Database = _

  def init(): Unit = {
    db = new Database(s"${Config.datadir}/openchain.sqlite")
    db.exec(
      "CREATE TABLE IF NOT EXISTS blocks (bmmheight INT UNIQUE NOT NULL, txid TEXT UNIQUE NOT NULL, bmmhash BLOB, block BLOB, blockheight INT)"
    )
    db.exec(
      "CREATE TABLE IF NOT EXISTS current (bmmheight INT NOT NULL REFERENCES blocks (bmmheight), bmmhash BLOB NOT NULL)"
    )
    db.exec(
      "CREATE TABLE IF NOT EXISTS state (asset BLOB PRIMARY KEY, owner BLOB NOT NULL, counter INT NOT NULL)"
    )
  }

  private lazy val getLatestTxStmt = db.prepare(
    "SELECT blockheight, txid, bmmheight, bmmhash FROM blocks ORDER BY bmmheight DESC LIMIT 1"
  )
  def getLatestTx(): Option[(Int, String, Int, Option[ByteVector])] =
    getLatestTxStmt
      .get()
      .toOption
      .map(row =>
        (
          row.selectDynamic("blockheight").asInstanceOf[Double].toInt,
          row.selectDynamic("txid").asInstanceOf[String],
          row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
          row
            .selectDynamic("bmmhash")
            .asInstanceOf[js.UndefOr[Uint8Array]]
            .toOption
            .map(ByteVector.fromUint8Array(_))
        )
      )

  private lazy val getMissingBlocksStmt = db.prepare(
    "SELECT bmmhash FROM blocks WHERE bmmhash IS NOT NULL AND block IS NULL"
  )
  def getMissingBlocks(): List[ByteVector] = getMissingBlocksStmt
    .all()
    .toList
    .map(row =>
      ByteVector
        .fromUint8Array(row.selectDynamic("bmmhash").asInstanceOf[Uint8Array])
    )

  private lazy val getBmmTxsSinceStmt = db.prepare(
    "SELECT txid, bmmheight, bmmhash FROM blocks WHERE bmmheight > ? ORDER BY bmmheight"
  )
  def getBmmTxsSince(bmmheight: Int): List[(String, Int, Option[ByteVector])] =
    getBmmTxsSinceStmt
      .all(bmmheight)
      .toList
      .map { row =>
        (
          row.selectDynamic("txid").asInstanceOf[String],
          row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
          row
            .selectDynamic("bmmhash")
            .asInstanceOf[js.UndefOr[Uint8Array]]
            .toOption
            .map(ByteVector.fromUint8Array(_))
        )
      }

  private lazy val addTxStmt =
    db.prepare(
      "INSERT OR IGNORE INTO blocks (bmmheight, txid, bmmhash) VALUES (?, ?, ?)"
    )
  def addTx(
      bmmHeight: Int,
      txid: String,
      bmmHash: Option[ByteVector]
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
          .asInstanceOf[js.UndefOr[Uint8Array]]
          .toOption
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
      "UPDATE blocks SET block = ?, blockheight = ? WHERE bmmhash = ? AND block IS NULL"
    )
  def insertBlock(hash: ByteVector, block: Block) =
    Block.codec.encode(block).toOption.foreach { bs =>
      insertBlockStmt
        .run(
          bs.toByteVector,
          if block.header.previous == ByteVector.fill(32)(0) then 1
          else
            getBlockHeight(block.header.previous)
              .map[SQLiteValue](_ + 1)
              .getOrElse[SQLiteValue](null)
          ,
          hash
        )

    // TODO update all blockheight of the following bmm blocks that match this one recursively
    }

  private lazy val getBlockHeightStmt =
    db.prepare("SELECT blockheight FROM blocks WHERE bmmhash = ?")
  def getBlockHeight(hash: ByteVector): Option[Int] = getBlockHeightStmt
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
      asset: ByteVector,
      owner: ByteVector,
      counter: Int
  ): Boolean =
    verifyAssetOwnerAndCounterStmt
      .get(asset, owner, counter)
      .isDefined

  private lazy val verifyAssetDoesntExistStmt = db.prepare(
    "SELECT 1 FROM state WHERE asset = ?"
  )
  def verifyAssetDoesntExist(asset: ByteVector): Boolean =
    verifyAssetDoesntExistStmt
      .get(asset)
      .isEmpty

  private lazy val getCurrentCounterStmt = db.prepare(
    "SELECT counter FROM state WHERE asset = ?"
  )
  def getNextCounter(asset: ByteVector): Int =
    getCurrentCounterStmt
      .get(asset)
      .map(_.selectDynamic("counter").asInstanceOf[Int])
      .getOrElse(1)

  private lazy val getAccountAssetsStmt = db.prepare(
    "SELECT asset FROM state WHERE owner = ?"
  )
  def getAccountAssets(pubkey: ByteVector): List[ByteVector] =
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
  def getAssetOwner(asset: ByteVector): Option[ByteVector] = getAssetOwnerStmt
    .get()
    .toOption
    .map(_.selectDynamic("owner").asInstanceOf[Uint8Array])
    .map(ByteVector.fromUint8Array(_))

  private lazy val getCurrentTipStmt =
    db.prepare("SELECT bmmheight, bmmhash FROM current")
  def getCurrentTip(): (Int, ByteVector) = getCurrentTipStmt
    .get()
    .toOption
    .map(row =>
      (
        row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
        row
          .selectDynamic("bmmhash")
          .asInstanceOf[js.UndefOr[Uint8Array]]
          .toOption
          .map(ByteVector.fromUint8Array(_))
          .getOrElse(ByteVector.empty)
      )
    )
    .getOrElse((0, ByteVector.empty))

  private lazy val updateCurrentTipStmt =
    db.prepare("UPDATE current SET bmmheight = bmmheight + 1, bmmhash = ?")
  private lazy val updateAssetOwnershipStmt =
    db.prepare(
      "UPDATE state SET owner = ?, counter = counter + 1 WHERE asset = ?"
    )

  def processBlock(block: Block): Unit = {
    db.exec("BEGIN TRANSACTION")
    updateCurrentTipStmt.run(block.hash)

    block.txs.foreach { tx =>
      updateAssetOwnershipStmt.run(tx.to, tx.asset, tx.counter)
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
