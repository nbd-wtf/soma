package main

import (
	"strings"
	"time"
)

func inspectBlocks() {
	var currentBlock uint64
	if err := db.Get(&currentBlock, "SELECT value FROM kv WHERE key = 'blockheight'"); err != nil {
		log.Fatal().Err(err).Msg("failed to get current block from db")
		return
	}

	// start at the next
	currentBlock++

	for {
		// get block from bitcoind
		hash, err := bc.GetBlockHash(currentBlock)
		if err != nil && strings.HasPrefix(err.Error(), "-8:") {
			time.Sleep(10 * time.Second)
			continue
		} else if err != nil {
			log.Fatal().Err(err).Uint64("height", currentBlock).Msg("no block")
			return
		}

		log.Debug().Uint64("height", currentBlock).Msg("inspecting block")
		blockHex, err := bc.GetRawBlock(hash)
		if err != nil {
			log.Fatal().Err(err).Str("hash", hash).Msg("no block")
			return
		}

		// process block and save it
		if err := processBlock(currentBlock, blockHex); err != nil {
			log.Fatal().Err(err).Str("hash", hash).Uint64("height", currentBlock).Msg("failed to process block")
			return
		}

		// jump to the next block
		currentBlock++
	}
}
