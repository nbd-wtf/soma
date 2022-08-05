package main

import (
	"bytes"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
	"path"
	"strings"
	"time"

	"github.com/Dexconv/go-bitcoind"
	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/btcsuite/btcd/btcutil"
	"github.com/btcsuite/btcd/btcutil/psbt"
	"github.com/btcsuite/btcd/chaincfg"
	"github.com/btcsuite/btcd/chaincfg/chainhash"
	"github.com/btcsuite/btcd/txscript"
	"github.com/btcsuite/btcd/wire"
	"github.com/jmoiron/sqlx"
	"github.com/mitchellh/go-homedir"
	"github.com/rs/zerolog"
	_ "modernc.org/sqlite"
)

var chainParams = &chaincfg.RegressionNetParams

var (
	log               = zerolog.New(os.Stderr).Output(zerolog.ConsoleWriter{Out: os.Stderr})
	chainKey          *btcec.PrivateKey
	chainPubKeyHash   []byte
	chainPubKeyScript []byte
	db                *sqlx.DB
	bc                *bitcoind.Bitcoind
)

var (
	configDir, _ = homedir.Expand("~/.config/openchain/guardian")
	configPath   = path.Join(configDir, "keys.json")
)

// init config dir
var _ = os.MkdirAll(configDir, 0700)

type Config struct {
	ChainKey string `json:"chainkey"`
}

func main() {
	zerolog.SetGlobalLevel(zerolog.DebugLevel)

	// open sqlite
	sqlitedsn := path.Join(configDir, "db.sqlite")
	if dbconn, err := sqlx.Open("sqlite", sqlitedsn); err != nil {
		log.Fatal().Err(err).Str("path", sqlitedsn).Msg("can't open sqlite")
		return
	} else {
		db = dbconn
	}

	// create tables
	db.Exec(`
        CREATE TABLE kv (
          key TEXT PRIMARY KEY,
          value TEXT
        );
        CREATE TABLE chain_block_tx (
          idx INT,
          txid TEXT PRIMARY KEY
        );
    `)

	// start bitcoind RPC
	if bitcoindRPC, err := bitcoind.New("127.0.0.1", 18443, "fiatjaf", "fiatjaf", false); err != nil {
		log.Fatal().Err(err).Msg("can't connect to bitcoind")
		return
	} else {
		bc = bitcoindRPC
	}

	// init config and keys
	if b, err := ioutil.ReadFile(configPath); err != nil {
		if strings.HasSuffix(err.Error(), "no such file or directory") {
			// create a new private key
			fmt.Println("creating private key and storing it on ", configPath)
			if chainKey, err = btcec.NewPrivateKey(); err != nil {
				log.Fatal().Err(err).Msg("error creating chain key")
				return
			}

			jconfig, _ := json.Marshal(Config{
				ChainKey: hex.EncodeToString(chainKey.Serialize()),
			})
			if err := os.WriteFile(configPath, jconfig, 0600); err != nil {
				log.Fatal().Err(err).Msg("error saving config key")
				return
			}

			// determine that we are starting now so we don't have to scan the entire chain
			if count, err := bc.GetBlockCount(); err != nil {
				log.Fatal().Err(err).Msg("error getting block count")
				return
			} else {
				db.MustExec("INSERT INTO kv VALUES ('blockheight', $1)", count)
			}
		} else {
			log.Fatal().Err(err).Msg("error reading config file")
			return
		}
	} else {
		var config Config
		json.Unmarshal(b, &config)
		c, _ := hex.DecodeString(config.ChainKey)
		chainKey, _ = btcec.PrivKeyFromBytes(c)

		if chainKey == nil {
			log.Fatal().Err(err).Msg("error parsing config json")
		}
	}

	// print addresses
	chainPubKeyHash = btcutil.Hash160(chainKey.PubKey().SerializeCompressed())
	chainAddress, _ := btcutil.NewAddressWitnessPubKeyHash(chainPubKeyHash, chainParams)
	fmt.Println("chain address: ", chainAddress.EncodeAddress())
	chainPubKeyScript = append([]byte{0, 20}, chainPubKeyHash...)

	// handle commands
	http.HandleFunc("/", handleInfo)
	go http.ListenAndServe(":10738", nil)

	// inspect blocks
	inspectBlocks()
}

func inspectBlocks() {
	var currentBlock uint64
	if err := db.Get(&currentBlock, "SELECT value FROM kv WHERE key = 'blockheight'"); err != nil {
		log.Fatal().Err(err).Msg("failed to get current block from db")
		return
	}

	// start at the next
	currentBlock++

	for {
		log.Debug().Uint64("height", currentBlock).Msg("inspecting block")

		// get block from bitcoind
		hash, err := bc.GetBlockHash(currentBlock)
		if err != nil && strings.HasPrefix(err.Error(), "-8:") {
			time.Sleep(1 * time.Minute)
			continue
		} else if err != nil {
			log.Fatal().Err(err).Uint64("height", currentBlock).Msg("no block")
			return
		}

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
				"SELECT idx FROM chain_block_tx WHERE txid = $1",
				input.PreviousOutPoint.Hash.String(),
			); err == sql.ErrNoRows {
				// this was just a dummy output that doesn't reference the chain tx, ignore
				log.Warn().Str("txid", tx.Hash().String()).
					Msg("tx sent to chain address but not part of the canonical chain")
				continue
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

			fmt.Println("new openchain tip found", tx.Hash().String())
		}
	}

	if _, err := txn.Exec("UPDATE kv SET value = $1 WHERE key = 'blockheight'", blockHeight); err != nil {
		return fmt.Errorf("failed to update blockheight: %w", err)
	}

	return txn.Commit()
}

func handleInfo(w http.ResponseWriter, r *http.Request) {
	var current struct {
		TxCount uint64 `db:"idx" json:"tx_count,omitempty"`
		TipTx   string `db:"txid" json:"tip_tx,omitempty"`
	}
	if err := db.Get(
		&current,
		"SELECT idx, txid FROM chain_block_tx ORDER BY idx DESC LIMIT 1",
	); err != nil {
		log.Error().Err(err).Msg("error fetching txid tip on / -- is there a genesis tx registered on the db?")
		w.WriteHeader(501)
		return
	}

	// make the next presigned tx
	tip, _ := chainhash.NewHashFromStr(current.TipTx)
	packet, _ := psbt.New(
		[]*wire.OutPoint{{Hash: *tip, Index: 0}},
		[]*wire.TxOut{{Value: 738, PkScript: chainPubKeyScript}},
		2,
		0,
		[]uint32{1},
	)

	// sign previous chain tip
	// we will use this txscript thing to build the signature for us, then we will take it and apply to the psbt
	witnessProgram, _ := txscript.NewScriptBuilder().AddOp(txscript.OP_0).AddData(chainPubKeyHash).Script()
	fetcher := txscript.NewCannedPrevOutputFetcher(chainPubKeyScript, 738)
	sigHashes := txscript.NewTxSigHashes(packet.UnsignedTx, fetcher)
	signature, err := txscript.RawTxInWitnessSignature(
		packet.UnsignedTx,
		sigHashes,
		0,
		738,
		witnessProgram,
		txscript.SigHashSingle|txscript.SigHashAnyOneCanPay,
		chainKey,
	)
	if err != nil {
		log.Fatal().Err(err).Msg("failed to compute")
		return
	}

	upd, _ := psbt.NewUpdater(packet)
	upd.AddInWitnessUtxo(wire.NewTxOut(738, chainPubKeyScript), 0)
	upd.AddInSighashType(txscript.SigHashSingle|txscript.SigHashAnyOneCanPay, 0)

	if _, err := upd.Sign(
		0,
		signature,
		chainKey.PubKey().SerializeCompressed(),
		nil,
		nil,
	); err != nil {
		log.Fatal().Err(err).Msg("failed to add signature to psbt")
	}
	if err := psbt.Finalize(upd.Upsbt, 0); err != nil {
		log.Fatal().Err(err).Msg("failed to finalize psbt input")
	}

	upd.Upsbt.UnsignedTx.TxIn[0].Witness = [][]byte{signature, chainKey.PubKey().SerializeCompressed()}

	// finally build the next output
	t := &bytes.Buffer{}
	upd.Upsbt.UnsignedTx.Serialize(t)

	b64psbt, _ := packet.B64Encode()

	next := struct {
		Raw  string `json:"raw"`
		PSBT string `json:"psbt"`
	}{
		hex.EncodeToString(t.Bytes()),
		b64psbt,
	}

	// and return the response
	json.NewEncoder(w).Encode(struct {
		Current interface{} `json:"current"`
		Next    interface{} `json:"next"`
	}{current, next})
}
