## Building

To build this, it is necessary to have `libuv`, `libcurl` and `libsecp256k1` with `schnorrsig` and `extrakeys` modules.

For `libsecp256k1` you can use this oneliner to build and generate some symlinks that were necessary in my case:

```
git clone https://github.com/bitcoin-core/secp256k1 && cd secp256k1 && ./autogen.sh && ./configure --enable-module-schnorrsig --enable-module-recovery && make && sudo make install && sudo ln -s /usr/local/lib/libsecp256k1.* /usr/lib/
```

Then run `sbt nativeLink`.

## Running

Start `lightningd` with this as a plugin, i.e. using the following option:

```
--plugin=/path/to/soma/miner/target/scala-3.2.0/soma-miner-out
```

These two optional parameters may be set, below are the defaults:

```
--overseer-url=http://localhost:10738
--node-url=http://localhost:9036
```
