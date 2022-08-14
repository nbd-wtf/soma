import Database from 'better-sqlite3'

export const blocks = new Database('blocks.sqlite')
export const state = new Database('state.sqlite')

// initialization
blocks.exec(`
  CREATE TABLE IF NOT EXISTS bmm (
    bitcoin_height INTEGER PRIMARY KEY,
    txid TEXT NOT NULL,
    bmm_hash BLOB
  );
  CREATE TABLE IF NOT EXISTS blocks (
    height INTEGER PRIMARY KEY,
    prev BLOB NOT NULL,
    hash BLOB NOT NULL,
    serialized BLOB NOT NULL
  );
  CREATE UNIQUE INDEX IF NOT EXISTS block_hash ON blocks (hash);
`)

state.exec(
  `CREATE TABLE IF NOT EXISTS assets (id BLOB PRIMARY KEY, counter INT, owner BLOB);`
)

// db functions
const addBMMTransactionStmt = blocks.prepare(
  `INSERT INTO bmm (bitcoin_height, txid, bmm_hash) VALUES ($bitcoinHeight, $txid, $bmmHash)`
)
export function addBMMTransaction(bitcoinHeight, txid, bmmHash) {
  addBMMTransactionStmt.run({
    $bitcoinHeight: bitcoinHeight,
    $txid: txid,
    $bmmHash: bmmHash
  })
}

const getBlockForBmmHashStmt = blocks.prepare(
  `SELECT * FROM blocks WHERE hash = $bmmHash`
)
export function getBlockForBmmHash(bmmHash) {
  return getBlockForBmmHashStmt.get({$bmmHash: bmmHash})
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
