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
      "CREATE TABLE IF NOT EXISTS state (asset BLOB PRIMARY KEY, owner BLOB NOT NULL, counter INT NOT NULL)"
    )
  }

  private lazy val getLatestTxStmt = db.prepare(
    "SELECT bmmheight, txid FROM blocks ORDER BY bmmheight DESC LIMIT 1"
  )
  def getLatestTx(): Option[(Int, String)] = getLatestTxStmt
    .get()
    .toOption
    .map(row =>
      (
        row.selectDynamic("bmmheight").asInstanceOf[Double].toInt,
        row.selectDynamic("txid").asInstanceOf[String]
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
      bmmHash.map[SQLiteValue](_.toUint8Array).getOrElse[SQLiteValue](null)
    )

  private lazy val getBlockStmt =
    db.prepare("SELECT block FROM blocks WHERE bmmHash = ?")
  def getBlock(bmmHash: ByteVector): Option[Block] =
    getBlockStmt
      .get(bmmHash.toUint8Array)
      .toOption
      .flatMap(row =>
        Block.codec
          .decode(
            ByteVector
              .fromUint8Array(
                row.selectDynamic("block").asInstanceOf[Uint8Array]
              )
              .toBitVector
          )
          .toOption
          .map(_.value)
      )

  private lazy val insertBlockStmt =
    db.prepare("UPDATE blocks SET block = ?, blockheight = ? WHERE bmmhash = ?")
  def insertBlock(hash: ByteVector, block: Block) =
    Block.codec.encode(block).toOption.foreach { bs =>
      insertBlockStmt
        .run(
          bs.toByteVector.toUint8Array,
          getBlockHeight(block.header.previous)
            .map[SQLiteValue](x => x)
            .getOrElse[SQLiteValue](null),
          hash.toUint8Array
        )
    }

  private lazy val getBlockHeightStmt =
    db.prepare("SELECT blockheight FROM blocks WHERE bmmhash = ?")
  def getBlockHeight(hash: ByteVector): Option[Int] = getBlockHeightStmt
    .get(hash.toUint8Array)
    .toOption
    .map(_.selectDynamic("blockheight").asInstanceOf[Double].toInt)

  private lazy val getLatestKnownBlockStmt = db.prepare(
    "SELECT block FROM blocks WHERE block IS NOT NULL ORDER BY blockheight DESC LIMIT 1"
  )
  def getLatestKnownBlock(): Option[Block] = getLatestKnownBlockStmt
    .get()
    .toOption
    .map(_.selectDynamic("block").asInstanceOf[Uint8Array])
    .map(ByteVector.fromUint8Array(_).toBitVector)
    .flatMap(Block.codec.decode(_).toOption)
    .map(_.value)

  private lazy val verifyAssetOwnerAndCounterStmt = db.prepare(
    "SELECT 1 FROM state WHERE asset = ? AND owner = ? AND counter = ?"
  )
  def verifyAssetOwnerAndCounter(
      asset: ByteVector,
      owner: ByteVector,
      counter: Int
  ): Boolean =
    verifyAssetOwnerAndCounterStmt
      .get(asset.toUint8Array, owner.toUint8Array, counter)
      .isDefined

  private lazy val verifyAssetDoesntExistStmt = db.prepare(
    "SELECT 1 FROM state WHERE asset = ?"
  )
  def verifyAssetDoesntExist(asset: ByteVector): Boolean =
    verifyAssetDoesntExistStmt
      .get(asset.toUint8Array)
      .isEmpty

  private lazy val getCurrentCounterStmt = db.prepare(
    "SELECT counter FROM state WHERE asset = ?"
  )
  def getNextCounter(asset: ByteVector): Int =
    getCurrentCounterStmt
      .get(asset.toUint8Array)
      .map(_.selectDynamic("counter").asInstanceOf[Int])
      .getOrElse(1)
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
