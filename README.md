Spacechain-inspired Open Market for Assets
==========================================

This is a demo Spacechain. [Spacechains](https://gist.github.com/RubenSomsen/c9f0a92493e06b0e29acced61ca9f49a#spacechains) can be used for all your wild ideas that need a blockchain, as long as they don't require a native currency (such as a bitcoin). In this demo, we have in our hands a fully-fledged blockchain that does one very boring thing: let's you issue and transfer non-fungible assets, each having a deterministically-generated unique identifier.

To run the demo, start your Soma toolkit by running `docker run -it -p 8080:8080 -p 39735:39735 -p 9036:9036 -v ~/soma:/root --name soma --rm fiatjaf/soma`. This will start a container running `bitcoind` on signet, and ready to start a CLN `lightningd` node and a `soma` node daemon. The data will be saved at `~/soma`, in case you want to delete it later.

After starting, you'll be dropped in a `tmux` interface. It has a bunch of pre-created tabs and you can use your mouse to click around. The first tab will have `bitcoind`. You should wait for it to stop syncing before you start `lightningd` on the second tab (the command will be prefilled so you just have to press enter). The same is valid for the third tab, where you can start the `soma` daemon.

## Setting up your Lightning node

With `lightningd` running you can jump to the next tab and run some commands to get a Lightning channel to one of the Spacechain miners.

1. First you'll need to get some signet coins: `cln newaddr` will print an address, take it and paste it on https://signetfaucet.com/
2. After you got the coins, connect and open a channel to this miner (or use some other miner if you know any): `cln connect 035aa926b4b467fc89819cade52f2639abe9d3909f0d0d6282d34bd3e068d78110@107.189.30.195:39735 && cln fundchannel 035aa926b4b467fc89819cade52f2639abe9d3909f0d0d6282d34bd3e068d78110 all 1500perkb`

While the channel is being published we can explore other things.

## Exploring the spacechain

Your container should have been running a server hosting a simple chain explorer page for the spacechain. You can open your browser at http://127.0.0.1:8080 to see it. That explorer fetches data directly from your `soma` daemon. There is a public explorer at http://turgot.fiatjaf.com:8080/.

## Minting an asset

Just call `sw mint`, this will create a keypair for you and print a transaction, hex-encoded, minting an asset to it. That transaction is not mined on the spacechain yet, for that it must be given to a miner to include it in a block. To do that, run the following command: `cln commando 035aa926b4b467fc89819cade52f2639abe9d3909f0d0d6282d34bd3e068d78110 soma-invoice '{"tx": "<paste-tx-hex-here>", "amount_msat": 500000}' 1WlbzV1PyJI0rs-EPUZUpOCNudFQVaqmD2CIcFX9Mow9MiZtZXRob2Q9c29tYS1pbnZvaWNl`

This will ask the specific miner you're connected to (change if necessary) to give you a 500sat invoice for mining the spacechain transaction. You can change the amount you want to pay. The miner doesn't care, it will accept any amount. It will aggregate amounts from all pending transactions it has, so if there are a lot of people mining transaction you can probably get away paying very little, but 500sat is fine for the test.

After calling that you will receive an invoice back, which you can just pay with `cln pay <invoice>`. The payment will be pending while the miner tries to publish your transaction. If it fails -- or if it is published by another miner -- the miner will politely fail the payment so you don't lose any money. You can attempt to mine the same transaction in multiple different miners.

## Sending an asset to someone else

Just like you did with `sw mint`, you can use `sw send` to build a transaction sending an asset you own to someone else.

To check what assets you own you can call `soma getaccountassets pubkey=<your-pubkey>`. To get your pubkey do `sw info`. (You can also list all assets that exist with `soma listallassets`.)

After confirming that you own an asset you'll also need its current `counter` -- which you can get from the `getaccountassets` call above, it is just a dummy number -- then call `sw send <asset-id> <counter> <target-pubkey>`. This will give you a transaction encoded as hex, which you can mine in the same way as above.

## Cheatsheet of useful commands

- `sw mint` generates a transaction that mints an asset.
- `sw send <asset> <counter> <target-pubkey>` generates a transaction that sends an existing asset from you to someone else.
- `soma getaccountassets pubkey=<your-pubkey>` lists all your assets.
- `soma listallassets` lists all assets that exist.
- `sw decode <tx>` decodes transactions from hex into meaningful JSON.
- `soma info` displays the current state of the chain and of the merge-mining.

## Talking to a human

Join https://t.me/spacechains
