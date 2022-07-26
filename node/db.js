import Database from 'better-sqlite3'

export const blocks = new Database('blocks.sqlite')
export const state = new Database('state.sqlite')

blocks.exec(`
  CREATE TABLE IF NOT EXISTS blocks (height INTEGER PRIMARY KEY, hash BLOB, bitcoin_height INTEGER, serialized BLOB);
  create unique index if not exists block_hash on blocks (hash);
`)

state.exec(`
  CREATE TABLE IF NOT EXISTS assets (id BLOB PRIMARY KEY, counter INT, owner BLOB);
`)

const getLatestBlockStmt = blocks.prepare(
  `SELECT * FROM blocks ORDER by height DESC LIMIT 1`
)
export function getLatestBlock() {
  return getLatestBlockStmt.get()
}

const getBlockStmt = blocks.prepare(`SELECT * FROM blocks WHERE height = ?`)
export function getBlock(height) {
  return getBlockStmt.get(height)
}

const getAssetStateStmt = state.prepare(`SELECT * FROM assets WHERE id = $id`)
const updateAssetStmt = state.prepare(
  `UPDATE assets SET owner = $owner, counter = $counter WHERE id = $id`
)
export function updateStateInTransaction(updateFunction) {
  return state.transaction(tx => {
    let assetState = getAssetStateStmt.get({$id: tx.asset})
    updateFunction(assetState, tx) // updates in-place
    updateAssetStmt.run({
      $owner: assetState.owner,
      $counter: assetState.counter,
      $id: tx.asset
    })
  })
}
