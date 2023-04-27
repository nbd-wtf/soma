import cats.data.*
import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.{Event => _, *}
import scoin.*

case class Store(
    sk: SignallingRef[IO, PrivateKey],
    pk: SignallingRef[IO, XOnlyPublicKey]
)

object Store {
  def apply(window: Window[IO]): Resource[IO, Store] = {
    val key = "sw-web-secret-key"
    val defaultPrivateKey = scoin.PrivateKey(scoin.randomBytes32())

    for {
      sk <- SignallingRef[IO]
        .of(defaultPrivateKey)
        .toResource
      pk <- SignallingRef[IO].of(defaultPrivateKey.publicKey.xonly).toResource

      _ <- Resource.eval {
        OptionT(window.localStorage.getItem(key))
          .foreachF(v => sk.set(scoin.PrivateKey(ByteVector32.fromValidHex(v))))
      }

      _ <- window.localStorage
        .events(window)
        .foreach {
          case Storage.Event.Updated(`key`, _, value, _) =>
            sk.set(scoin.PrivateKey(ByteVector32.fromValidHex(value)))
          case _ => IO.unit
        }
        .compile
        .drain
        .background

      _ <- sk.discrete
        .evalTap(sk =>
          IO.cede *> window.localStorage.setItem(key, sk.value.toHex)
        )
        .evalTap(sk => pk.set(sk.publicKey.xonly))
        .compile
        .drain
        .background
    } yield Store(sk, pk)
  }
}

enum Selection:
  case Neither, Mint, Send, Decode;

case class SendParams(
    asset: Int = 0,
    counter: Int = 0,
    to: XOnlyPublicKey = XOnlyPublicKey(ByteVector32.Zeroes)
)
