import Hyperswarm from 'hyperswarm'

import {listenForBlocks} from './block-fetcher.js'
import {processBlock} from './process-block.js'
import {watchBitcoin} from './bitcoin-watcher.js'

const genesis = process.env.GENESIS_TX

async function main() {
  if (!genesis) {
    console.log('we need GENESIS_TX to be set otherwise we cannot do anything')
    process.exit(2)
  }

  const h = new Hyperswarm()

  const topic = Buffer.alloc(32).fill(genesis)
  h.join(topic)

  listenForBlocks(h).onBlock(block => processBlock(block))

  watchBitcoin(genesis)
    .onTx((tx, bmmHash) => {
      console.log('found new openchain tx', tx.txid)
    })
    .onTip((tx, bmmHash) => {})
}

main()
