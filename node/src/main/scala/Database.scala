import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scodec.bits.ByteVector

object Database {
  private var db: Database = _

  def init(): Unit = {
    val home = homedir()

    mkdirSync(
      s"$home/.config/openchain/node/",
      js.Dictionary("recursive" -> true).asInstanceOf[js.Object]
    )
    db = new Database(s"$home/.config/openchain/node/openchain.sqlite")
    db.exec(
      "CREATE TABLE IF NOT EXISTS blocks (bmmheight INT UNIQUE NOT NULL, txid TEXT UNIQUE NOT NULL, bmmhash BLOB, block BLOB, blockheight INT)"
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
        row.selectDynamic("bmmheight").asInstanceOf[Int],
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
  ): Unit = {
    addTxStmt.run(bmmHeight, txid, bmmHash.map(_.toUint8Array).getOrElse(null))
  }
}

@js.native
@JSImport("better-sqlite3", JSImport.Default)
class Database(path: String) extends js.Object {
  def prepare(sql: String): Statement = js.native
  def exec(sql: String): Unit = js.native
}

@js.native
trait Statement extends js.Object {
  def run(params: js.Any*): Unit = js.native
  def get(params: js.Any*): js.UndefOr[js.Dynamic] = js.native
  def all(params: js.Any*): js.Array[js.Dynamic] = js.native
}

@js.native
@JSImport("node:fs", "mkdirSync")
object mkdirSync extends js.Object {
  def apply(path: String, options: js.Object): js.Promise[Unit] =
    js.native
}

@js.native
@JSImport("node:os", "homedir")
object homedir extends js.Object {
  def apply(): String = js.native
}
