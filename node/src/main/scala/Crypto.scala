import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import scodec.bits.ByteVector

object Crypto {
  def sha256(msg: ByteVector): ByteVector =
    ByteVector.fromValidHex(
      HashJS.sha256().update(msg.toHex, "hex").digest("hex")
    )
}

@js.native
@JSImport("@noble/secp256k1", "schnorr")
object Secp256k1Schnorr extends js.Object {
  def getPublicKey(
      privateKey: Uint8Array
  ): Uint8Array = js.native
  def signSync(
      msg: Uint8Array,
      privateKey: Uint8Array
  ): Uint8Array = js.native
  def verifySync(
      sig: Uint8Array,
      msg: Uint8Array,
      pubkey: Uint8Array
  ): Boolean = js.native
}

object monkeyPatch {
  def sha256Sync(msg: Uint8Array): Uint8Array =
    ByteVector
      .fromValidHex(
        HashJS.sha256().update(ByteVector.view(msg).toHex, "hex").digest("hex")
      )
      .toUint8Array

  def hmacSha256Sync(key: Uint8Array, msg: Uint8Array): Uint8Array =
    ByteVector
      .fromValidHex(
        HashJS
          .hmac(HashJS.sha256, ByteVector.view(key).toHex, "hex")
          .update(ByteVector.view(msg).toHex, "hex")
          .digest("hex")
      )
      .toUint8Array

  Secp256k1Schnorr.asInstanceOf[js.Dynamic].sha256Sync = sha256Sync
  Secp256k1Schnorr.asInstanceOf[js.Dynamic].hmacSha256Sync = hmacSha256Sync
}

@js.native
@JSImport("hash.js", JSImport.Default)
object HashJS extends js.Object {
  def sha256(): Hash = js.native
  def hmac(hash: () => Hash, key: String, enc: String): Hash = js.native
}

@js.native
trait Hash extends js.Object {
  def update(msg: String, enc: String): Hash = js.native
  def digest(enc: String): String = js.native
}
