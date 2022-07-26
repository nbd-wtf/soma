import {listenForBlocks} from './block-fetcher.js'
import {processBlock} from './process-block.js'

async function main() {
  listenForBlocks().onBlock(block => processBlock(block))
}

main()
