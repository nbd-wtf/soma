package soma

import utest._
import scoin._

object BMMTest extends TestSuite {
  val tests = Tests {
    test("build spacechain bmm of length 10") {
      val bmm = new BMM(theEnd = 100, amount = 1234.sat, sequence = 3)
      val txs = bmm.precompute(1, 7)

      txs.size ==> 7
      txs
    }
  }
}
