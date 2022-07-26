import Hyperswarm from 'hyperswarm'
import {getBlock, getLatestBlock} from './db.js'

export function listenForBlocks() {
  var listeners = []

  const h = new Hyperswarm()
  h.on('connection', conn => {
    console.log('got connection', conn)
    console.log('writing ?latest')
    conn.write('?latest')
    conn.on('data', data => {
      let msg = data.toString()
      console.log(`got msg: ${msg}`)

      switch (msg[0]) {
        case '!': {
          if (msg.startsWith('!latest')) {
            let resp = JSON.parse(msg.slice(7))
            let latest = getLatestBlock()
            if (
              resp.height > latest.height &&
              resp.bitcoinHeight > latest.bitcoinHeight
            ) {
              // this node knows something we don't
              console.log(`writing ?block:${resp.height}`)
              conn.write(`?block:${resp.height}`)
            }
          }
          break
        }
        case '?': {
          if (msg === '?blocks') {
            let latest = getLatestBlock()
            conn.write(
              `!latest${JSON.stringify({
                height: latest.height,
                bitcoinHeight: latest.bitcoinHeight
              })}`
            )
          } else if (msg.startsWith('?block:')) {
            let height = parseInt(msg.slice(7), 10)
            let block = getBlock(height)
            console.log(`writing !block:${height}${JSON.stringify(block)}`)
            conn.write(`!block:${height}${JSON.stringify(block)}`)
          }
          break
        }
      }
    })
  })

  console.log('listening for peers and blocks')

  return {
    onBlock(cb) {
      listeners.push(cb)
    }
  }
}
