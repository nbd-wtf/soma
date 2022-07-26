import crypto from 'crypto'
import {getLatestBlock, updateStateInTransaction} from './db.js'

export function processBlock(serialized) {
  let block = parseBlock(serialized)

  let latestBlock = getLatestBlock()
  if (block.previousBlock !== latestBlock.hash) return

  updateStateInTransaction((assetState, tx) => {
    if (assetState.owner !== tx.from)
      throw new Error(`${tx.hash}: ${tx.asset} is not owned by ${tx.from}`)

    if (assetState.counter !== tx.counter)
      throw new Error(
        `${tx.hash}: expected counter ${assetState.counter}, got ${tx.counter}`
      )

    assetState.owner = tx.to
    assetState.counter++

    return assetState
  })(block.txs)
}

function parseBlock(serialized) {
  let bitcoinTx = serialized.subarray(0, 32)
  let previousBlock = serialized.subarray(32, 64)
  let rawTxsConcat = serialized.subarray(64)
  let rawTxs = []
  let txs = []
  let offset = 0
  while (offset < rawTxsConcat.length) {
    let size = rawTxsConcat.readUint16BE(offset)
    offset += 2
    let rawTx = rawTxsConcat.subarray(offset, offset + size)
    offset += size
    rawTxs.push(rawTx)
    txs.push(parseTx(rawTx))
  }

  return {
    bitcoinTx,
    previousBlock,
    txs,
    hash: hashBlock({previousBlock, rawTxs})
  }
}

function parseTx(rawTx) {
  return {
    hash: crypto.createHash('sha256').update(rawTx).digest('hex'),
    counter: rawTx.readUint16BE(0),
    asset: rawTx.subarray(2, 34).toString('hex'),
    from: rawTx.subarray(34, 66).toString('hex'),
    to: rawTx.subarray(66, 98).toString('hex'),
    signature: rawTx.subarray(98, 162).toString('hex')
  }
}

function hashBlock({previousBlock, rawTxs}) {
  return crypto
    .createHash('sha256')
    .update(previousBlock)
    .update(
      Buffer.concat(
        rawTxs.map(tx => {
          // TODO this should actually be a merkle tree?
          let b = Buffer.alloc(tx.length + 2 /* size */)
          b.writeUint16BE(tx.length)
          b.write(tx, 2)
        })
      )
    )
    .digest()
}
