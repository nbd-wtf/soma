package main

import (
	"bytes"
	"database/sql"
	"encoding/hex"
	"fmt"
	"strings"

	"github.com/btcsuite/btcd/btcutil"
)

func processBlock(blockHeight uint64, blockHex string) error {
	txn := db.MustBegin()
	defer txn.Rollback()

	raw, err := hex.DecodeString(blockHex)
	if err != nil {
		return fmt.Errorf("block hex broken: %w", err)
	}

	block, err := btcutil.NewBlockFromBytes(raw)
	if err != nil {
		return fmt.Errorf("failed to parse block: %w", err)
	}

	for _, tx := range block.Transactions() {
		// chain tx relevant inputs and outputs are always on the first index
		input := tx.MsgTx().TxIn[0]
		output := tx.MsgTx().TxOut[0]

		if bytes.HasSuffix(output.PkScript, chainPubKeyHash) && len(output.PkScript) == 22 {
			// check if the chain has moved
			var index uint64
			if err := txn.Get(
				&index,
				"SELECT idx + 1 FROM chain_block_tx WHERE txid = $1",
				input.PreviousOutPoint.Hash.String(),
			); err == sql.ErrNoRows && chainHasStarted {
				// this was just a dummy output that doesn't reference the chain tx,
				// just ignore it
				log.Warn().Str("txid", tx.Hash().String()).
					Msg("got tx but not part of the canonical chain")
				continue
			} else if err == sql.ErrNoRows && !chainHasStarted {
				// the chain hasn't started yet, so we will take the first output we
				// can get that matches the canonical amount and it will be the
				// genesis block
				if output.Value != CANONICAL_AMOUNT {
					log.Warn().
						Str("txid", tx.Hash().String()).
						Int64("sats", output.Value).
						Msg("got tx but can't be the genesis since the amount is wrong")
					continue
				} else {
					// it's ok, we will use this one, just proceed
					index = 0
				}
			} else if err != nil {
				return fmt.Errorf("failed to read chain_block_tx: %w", err)
			}

			if _, err := txn.Exec(
				"INSERT INTO chain_block_tx (idx, txid) VALUES ($1, $2)",
				index+1, tx.Hash().String(),
			); err != nil {
				if strings.HasPrefix(err.Error(), "constraint failed: UNIQUE constraint") {
					// no problem, just skip
					continue
				}

				return fmt.Errorf("failed to insert into chain_block_tx: %w", err)
			}

			log.Info().Str("txid", tx.Hash().String()).Msg("new openchain tip found")
		}
	}

	if _, err := txn.Exec("UPDATE kv SET value = $1 WHERE key = 'blockheight'", blockHeight); err != nil {
		return fmt.Errorf("failed to update blockheight: %w", err)
	}

	return txn.Commit()
}
