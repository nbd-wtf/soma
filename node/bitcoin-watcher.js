import fetch from 'node-fetch'

import {addBMMTransaction} from './db'

const bitcoinChain = process.env.BITCOIN_CHAIN || 'mainnet'
const bitcoinHost = process.env.BITCOIND_HOST || '127.0.0.1'
const bitcoinPort =
  process.env.BITCOIN_PORT ||
  {mainnet: 8332, testnet: 18332, signet: 38332, regtest: 18443}[bitcoinChain]
const bitcoinUser = process.env.BITCOIND_USER
const bitcoinPassword = process.env.BITCOIND_PASSWORD

async function rpc(method, params = []) {
  try {
    let r = await fetch(`http://${bitcoinHost}:${bitcoinPort}`, {
      method: 'POST',
      headers: {
        Authorization:
          'Basic ' +
          Buffer.from(bitcoinUser + ':' + bitcoinPassword).toString('base64')
      },
      body: JSON.stringify({id: '0', jsonrpc: '2.0', method, params})
    })
    if (r.status >= 300) {
      throw new Error(`got bad response from bitcoind: ${r.status}`)
    }
    let body = await r.text()
    let resp
    try {
      resp = JSON.parse(body)
    } catch (err) {
      throw new Error(`response is not JSON: ${body}`)
    }
    if (!resp.result) throw new Error(resp.error)
    return resp.result
  } catch (err) {
    throw new Error(`${method}(${params}): ${err}`)
  }
}

export function watchBitcoin(genesisBitcoinTx) {
  let txCallbacks = []
  let tipCallbacks = []

  start(genesisBitcoinTx, txCallbacks, tipCallbacks)

  return {
    onTx(cb) {
      txCallbacks.push(cb)
      return this
    },
    onTip(cb) {
      tipCallbacks.push(cb)
      return this
    }
  }
}

async function start(genesisBitcoinTx, txCallbacks, tipCallbacks) {
  const genesisTx = await rpc('getrawtransaction', [genesisBitcoinTx, 2])
  const {height} = await rpc('getblock', [genesisTx.blockhash])

  let tip = genesisTx
  let h = height + 1
  let bmmHash = null

  while (true) {
    const tips = await rpc('getchaintips')
    if (tips[0].height < h) {
      tipCallbacks.forEach(cb => cb(tip, bmmHash))
      await new Promise(resolve => setTimeout(resolve, 60000))
      continue
    }

    console.log('inspecting bitcoin block', h)
    const hash = await rpc('getblockhash', [h])
    const block = await rpc('getblock', [hash, 2])
    for (let t = 1; t < block.tx.length; t++) {
      let tx = block.tx[t]
      if (tx.vin[0].txid === tip.txid && tx.vin[0].vout === 0) {
        if (
          tx.vout.length > 1 &&
          tx.vout[1].scriptPubKey.asm.startsWith('OP_RETURN')
        ) {
          bmmHash = Buffer.from(
            tx.vout[1].scriptPubKey.asm.split(' ')[1],
            'hex'
          )
        } else {
          bmmHash = null
        }
        addBMMTransaction(block.height, tx.txid, bmmHash)
        txCallbacks.forEach(cb => cb(tx, bmmHash))
        tip = tx
        break
      }
    }

    h++
  }
}
