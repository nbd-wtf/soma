import {getBlock, getBlockForBmmHash} from './db.js'

export function listenForBlocks(h) {
  let cbs = []
  let conns = []

  h.on('connection', conn => {
    console.log('got connection')
    conns.push(conn)

    conn.on('close', () => {
      let idx = conns.indexOf(conn)
      conns.splice(idx, 1)
    })

    conn.on('data', data => {
      let msg = data.toString()
      console.log(`got msg: ${msg}`)

      const [kind, method, ...extra] = [msg[0], ...msg.slice(1).split(':')]

      switch (kind) {
        case '?': {
          switch (method) {
            case 'bmm': {
              let block = getBlockForBmmHash(Buffer.from(extra[0], 'hex'))
              conn.write(`!block:${JSON.stringify(block)}`)
              break
            }
          }
          break
        }
        case '!': {
          switch (method) {
            case 'block': {
              const block = JSON.parse(extra[0])
              if (block.prev.equals(block.hash)) {
              }
              break
            }
          }
          break
        }
      }
    })
  })

  console.log('listening for peers and blocks')
  return {
    onBlock(cb) {
      cbs.push(cb)
      return this
    },
    askBlock(bmmHash) {}
  }
}
