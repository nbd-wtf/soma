## Building

Turning Scala into JavaScript:

```
sbt node/fastLinkJS
```

## Development

The same as above, but prepend the `~` to keep rebuilding on every change.

## Running

This is meant to be run with Node.js.

Before running the dependencies must be installed with `npm install`. They are listed in the `package.json` file.

Then run with

```
BITCOIN_CHAIN=regtest BITCOIND_USER=something BITCOIND_PASSWORD=something GENESIS_TX=<taken-from-overseer-http-output> node --enable-source-maps target/scala-3.2.0/soma-node-fastopt/main.js
```

These params are necessary for talking to `bitcoind`.

A directory will be created under `~/.config/soma/node` with a database containing the chain state and blocks.

Other nodes will be found using [hyperswarm](http://npmjs.com/hyperswarm) and blocks will be exchanged. Other P2P techniques can be added later.
