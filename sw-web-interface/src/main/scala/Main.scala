import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.*
import fs2.dom.{Event => _, *}
import calico.*
import calico.html.io.{*, given}
import calico.syntax.*
import io.circe.*
import io.circe.syntax.*
import scodec.bits.*
import scoin.*
import soma.*
import calico.frp.SignallingSortedMapRef

object Main extends IOWebApp {
  def render: Resource[IO, HtmlDivElement[IO]] =
    (
      Store(window),
      SignallingRef[IO].of(Selection.Neither).toResource,
      SignallingRef[IO].of("").toResource,
      SignallingRef[IO].of("").toResource
    )
      .flatMapN { (store, sel, hex, result) =>
        div(
          cls := "flex w-full h-full flex-col items-center justify-center",
          div(
            cls := "w-4/5",
            h1(
              cls := "px-1 py-2 text-xl",
              img(
                cls := "inline-block w-8 mr-2",
                src := "/favicon.ico"
              ),
              "soma wallet web"
            ),
            (
              store.sk: Signal[IO, PrivateKey],
              store.pk: Signal[IO, XOnlyPublicKey]
            ).mapN { (sk, pk) =>
              div(
                div(
                  span(cls := "font-bold", "private key: "),
                  sk.value.toHex
                ),
                div(
                  span(cls := "font-bold", "public key: "),
                  pk.value.toHex
                )
              )
            },
            div(
              cls := "flex my-3",
              store.pk.map { pk =>
                div(
                  button(
                    cls := "shrink bg-yellow-300 hover:bg-yellow-200 text-black font-bold py-2 mx-2 px-4 rounded ",
                    "decode",
                    onClick --> (_.foreach { _ =>
                      sel.set(Selection.Decode) >> result.set("")
                    })
                  ),
                  button(
                    cls := "shrink bg-yellow-300 hover:bg-yellow-200 text-black font-bold py-2 mx-2 px-4 rounded",
                    "mint",
                    onClick --> (_.foreach { _ =>
                      sel.set(Selection.Mint) >> result.set(
                        Tx(
                          asset = 0,
                          to = pk,
                          counter = 0,
                          from = XOnlyPublicKey(scoin.randomBytes32())
                        ).encoded.toHex
                      )
                    })
                  ),
                  button(
                    cls := "shrink bg-yellow-300 hover:bg-yellow-200 text-black font-bold py-2 mx-2 px-4 rounded ",
                    "send",
                    onClick --> (_.foreach { _ =>
                      sel.set(Selection.Send) >> result.set("")
                    })
                  )
                )
              }
            ),
            sel.map {
              case Selection.Send =>
                renderSendForm(store, result)
              case Selection.Decode =>
                renderDecodeForm(store, result)
              case _ => div("")
            },
            pre(
              styleAttr := "font-family: monospace; white-space: pre-wrap; word-wrap: break-word; word-break: break-all;",
              cls := "break-all",
              result
            )
          )
        )
      }

  def renderSendForm(
      store: Store,
      result: SignallingRef[IO, String]
  ): Resource[IO, HtmlFormElement[IO]] =
    SignallingRef[IO].of(SendParams()).toResource.flatMap { params =>
      form(
        cls := "flex flex-col",
        onSubmit --> (_.foreach { ev =>
          ev.preventDefault >>
            (store.sk.get, store.pk.get, params.get).flatMapN((sk, pk, p) =>
              result.set(
                Tx(
                  asset = p.asset,
                  to = p.to,
                  from = pk,
                  counter = p.counter
                ).withSignature(sk).encoded.toHex
              )
            )
        }),
        label(
          "asset:",
          input.withSelf(self =>
            (
              cls := "ml-2 mb-2 text-black px-1",
              onInput --> (_.foreach(_ =>
                self.value.get.flatMap(asset =>
                  params.update(p =>
                    p.copy(
                      asset = ByteVector
                        .fromHex(asset)
                        .map(_.toInt(signed = false))
                        .getOrElse(p.asset)
                    )
                  )
                )
              ))
            )
          )
        ),
        label(
          "counter:",
          input.withSelf(self =>
            (
              cls := "ml-2 mb-2 text-black px-1",
              onInput --> (_.foreach(_ =>
                self.value.get.flatMap(counter =>
                  params.update(p =>
                    p.copy(counter = counter.toIntOption.getOrElse(p.counter))
                  )
                )
              ))
            )
          )
        ),
        label(
          "to:",
          input.withSelf(self =>
            (
              cls := "ml-2 mb-2 text-black px-1",
              onInput --> (_.foreach(_ =>
                self.value.get.flatMap(to =>
                  params.update(p =>
                    p.copy(to =
                      ByteVector
                        .fromHex(to)
                        .filter(_.size == 32)
                        .map(bytes => XOnlyPublicKey(ByteVector32(bytes)))
                        .getOrElse(p.to)
                    )
                  )
                )
              ))
            )
          )
        ),
        params
          .map[Resource[IO, HtmlButtonElement[IO]]] { p =>
            val valid =
              p.asset > 0 && p.counter > 0 && p.to.value != ByteVector32.Zeroes

            val clsStr =
              "shrink text-black font-bold py-1 mx-1 px-2 rounded " + (if valid
                                                                       then "bg-sky-200 hover:bg-sky-300"
                                                                       else
                                                                         "bg-zinc-200 hover:bg-zinc-300"
              )

            button(
              typ := "submit",
              styleAttr := "width: fit-content",
              cls := clsStr,
              disabled := !valid,
              "generate"
            )
          }
      )
    }

  def renderDecodeForm(
      store: Store,
      result: SignallingRef[IO, String]
  ): Resource[IO, HtmlFormElement[IO]] =
    SignallingRef[IO].of("").toResource.flatMap { txHex =>
      form(
        cls := "flex flex-col",
        onSubmit --> (_.foreach { ev =>
          ev.preventDefault >>
            txHex.get.flatMap(txHex =>
              result.set(
                BitVector
                  .fromHex(txHex)
                  .flatMap(Tx.codec.decodeValue(_).toOption) match {
                  case None => "invalid soma tx"
                  case Some(tx) => {
                    JsonObject(
                      "asset" -> tx.asset.asJson,
                      "to" -> tx.to.toHex.asJson,
                      "from" -> tx.from.toHex.asJson,
                      "counter" -> tx.counter.asJson,
                      "signature" -> tx.signature.bytes.toHex.asJson
                    ).asJson.printWith(Utils.jsonPrinter)
                  }
                }
              )
            )
        }),
        label(
          "tx as hex:",
          input.withSelf(self =>
            (
              cls := "ml-2 mb-2 text-black px-1",
              onInput --> (_.foreach(_ =>
                self.value.get.flatMap(
                  txHex.set(_)
                )
              ))
            )
          )
        ),
        button(
          typ := "submit",
          cls :=
            "shrink text-black font-bold py-1 mx-1 px-2 rounded bg-sky-200 hover:bg-sky-300",
          styleAttr := "width: fit-content",
          "decode"
        )
      )
    }
}
