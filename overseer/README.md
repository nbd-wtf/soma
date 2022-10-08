## Building

`make`

## Running

This will call `bitcoind`'s HTTP RPC, so you need to provide these parameters as environment variables:

`BITCOIN_CHAIN=regtest BITCOIND_USER=something BITCOIND_PASSWORD=something ./overseer`

At start, it will create a private key and a database at `~/.config/openchain/overseer`.

It provides a single HTTP endpoint at `/` (`http://localhost:10738/` by default).

To bootstrap the chain the exact canonical amount must be sent to the canonical address, which will be shown at that HTTP endpoint.

After that it just keeps watching the Bitcoin chain and listening for requests for the next presigned transactions in the BMM string.
