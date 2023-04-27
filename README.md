Spacechain-inspired Open Market for Assets
==========================================

This is a demo Spacechain. [Spacechains](https://gist.github.com/RubenSomsen/c9f0a92493e06b0e29acced61ca9f49a#spacechains) can be used for all your wild ideas that need a blockchain, as long as they don't require a native currency (such as a bitcoin). In this demo, we have in our hands a fully-fledged blockchain that does one very boring thing: let's you issue and transfer non-fungible assets, each having a deterministically-generated unique identifier.

And it does that without requiring any shitcoins to be created in the process or spam to be dumped into the Bitcoin chain! All it needs is [SIGHASH_ANYPREVOUT](https://anyprevout.xyz) to be activated on Bitcoin -- luckily it is activated on [signet](https://mempool.space/signet).

There are two ways to run this demo:
 - if you want to get into the internals you can run the magic Docker container that has everything you need prepackaged. Just follow [this tutorial](tutorial-docker.md).
 - if you are a lazy bum and just want to see something happen, you can use the hosted tools. Follow [this tutorial](tutorial-web.md).
