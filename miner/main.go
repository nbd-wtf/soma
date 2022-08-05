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
	"github.com/btcsuite/btcd/wire"
	"github.com/jmoiron/sqlx"
	"github.com/mitchellh/go-homedir"
	"github.com/rs/zerolog"
	_ "modernc.org/sqlite"
)

var chainParams = &chaincfg.RegressionNetParams

var (
	log            = zerolog.New(os.Stderr).Output(zerolog.ConsoleWriter{Out: os.Stderr})
	walletKey      *btcec.PrivateKey
	walletPkScript []byte
	chainKey       *btcec.PrivateKey
	chainPkScript  []byte
	db             *sqlx.DB
	bc             *bitcoind.Bitcoind
)

var (
	configDir, _ = homedir.Expand("~/.config/openchain/guardian")
	configPath   = path.Join(configDir, "keys.json")
)

// init config dir
var _ = os.MkdirAll(configDir, 0700)

type Config struct {
	WalletKey string `json:"walletkey"`
	ChainKey  string `json:"chainkey"`

	BlockHeight uint64 `json:"blockheight"`
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
        CREATE TABLE wallet_output (
          txid TEXT,
          n INT,
          sat INT,
          spent BOOLEAN,
          UNIQUE (txid, n)
        );
        CREATE TABLE chain_block_tx (
          index INT,
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
			fmt.Println("creating private keys and storing them on ", configPath)
			if walletKey, err = btcec.NewPrivateKey(); err != nil {
				log.Fatal().Err(err).Msg("error creating wallet key")
				return
			}
			if chainKey, err = btcec.NewPrivateKey(); err != nil {
				log.Fatal().Err(err).Msg("error creating chain key")
				return
			}

			jconfig, _ := json.Marshal(Config{
				WalletKey: hex.EncodeToString(walletKey.Serialize()),
				ChainKey:  hex.EncodeToString(chainKey.Serialize()),
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
		w, _ := hex.DecodeString(config.WalletKey)
		walletKey, _ = btcec.PrivKeyFromBytes(w)
		c, _ := hex.DecodeString(config.ChainKey)
		chainKey, _ = btcec.PrivKeyFromBytes(c)

		if walletKey == nil || chainKey == nil {
			log.Fatal().Err(err).Msg("error parsing config json")
		}
	}

	// print addresses
	walletPubkeyHash := btcutil.Hash160(walletKey.PubKey().SerializeCompressed())
	walletAddress, _ := btcutil.NewAddressWitnessPubKeyHash(walletPubkeyHash, chainParams)
	fmt.Println("wallet address: ", walletAddress.EncodeAddress())
	walletPkScript = append([]byte{0, 20}, walletPubkeyHash...)

	chainPubkeyHash := btcutil.Hash160(chainKey.PubKey().SerializeCompressed())
	chainAddress, _ := btcutil.NewAddressWitnessPubKeyHash(chainPubkeyHash, chainParams)
	fmt.Println("chain address: ", chainAddress.EncodeAddress())
	chainPkScript = append([]byte{0, 20}, chainPubkeyHash...)

	// handle commands
	http.HandleFunc("/", handleInfo)

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
		return err
	}

	block, err := btcutil.NewBlockFromBytes(raw)
	if err != nil {
		return err
	}

	// check if we have new utxos
	for _, tx := range block.Transactions() {
		for n, output := range tx.MsgTx().TxOut {
			if bytes.Compare(output.PkScript, walletPkScript) == 0 {
				if _, err := txn.Exec(
					"INSERT INTO wallet_output (txid, n, sat, spent) VALUES ($1, $2, $3, $4)",
					tx.Hash().String(), n, output.Value, false,
				); err != nil {
					if strings.HasPrefix(err.Error(), "constraint failed: UNIQUE constraint") {
						// no problem, just skip
						continue
					}

					return err
				}

				fmt.Println("found new output for wallet:", tx.Hash().String(), n)
			}
		}
	}

	// check if we have spent a utxo
	for _, tx := range block.Transactions() {
		for _, input := range tx.MsgTx().TxIn {
			if _, err := txn.Exec(
				"UPDATE wallet_output SET spent = true WHERE txid = $1 AND n = $2",
				input.PreviousOutPoint.Hash.String(), input.PreviousOutPoint.Index,
			); err != nil {
				return err
			}

			fmt.Println("wallet utxo was spent:", input.PreviousOutPoint.Hash.String(), input.PreviousOutPoint.Index)
		}
	}

	for _, tx := range block.Transactions() {
		// chain tx relevant inputs and outputs are always on the first index
		input := tx.MsgTx().TxIn[0]
		output := tx.MsgTx().TxOut[0]

		if bytes.Compare(output.PkScript, chainPkScript) == 0 {
			// check if the chain has moved
			var index uint64
			if err := txn.Get(
				&index,
				"SELECT index FROM chain_block_tx WHERE txid = $1",
				input.PreviousOutPoint.Hash.String(),
			); err == sql.ErrNoRows {
				// this was just a dummy output that doesn't reference the chain tx, ignore
				log.Warn().Str("txid", tx.Hash().String()).
					Msg("tx sent to chain address but not part of the canonical chain")
				continue
			} else if err != nil {
				return err
			}

			if _, err := txn.Exec(
				"INSERT INTO chain_block_tx (index, txid) VALUES ($1, $2)",
				index+1, tx.Hash().String(),
			); err != nil {
				if strings.HasPrefix(err.Error(), "constraint failed: UNIQUE constraint") {
					// no problem, just skip
					continue
				}

				return err

			}

			fmt.Println("new openchain tip found", tx.Hash().String())
		}
	}

	if _, err := txn.Exec("UPDATE kv SET value = $1 WHERE key = 'blockheight'"); err != nil {
		return err
	}

	return txn.Commit()
}

func handleInfo(w http.ResponseWriter, r *http.Request) {
	var current struct {
		TxCount uint64 `db:"index" json:"tx_count,omitempty"`
		TipTx   string `db:"txid" json:"tip_tx,omitempty"`
	}
	if err := db.Get(
		&current,
		"SELECT index, txid FROM chain_block_tx ORDER BY index DESC LIMIT 1",
	); err != nil && err != sql.ErrNoRows {
		log.Error().Err(err).Msg("error fetching txid tip on /")
		w.WriteHeader(501)
		return
	}

	// get our usable utxos
	var utxos []struct {
		Txid string `db:"txid"`
		N    uint32 `db:"n"`
        Sat uint64 `db:"sat"`
	}
	if err := db.Select(&utxos, "SELECT txid, n, sat FROM wallet_output WHERE NOT spent"); err != nil {
		log.Error().Err(err).Msg("failed to fetch wallet outputs on /")
		w.WriteHeader(502)
		return
	}

	// make the next unsigned tx
	var inputs []*wire.OutPoint
	if current.TipTx == "" {
		inputs = make([]*wire.OutPoint, len(utxos))

		// will conjure the genesis block, spend only from wallet
		for i, utxo := range utxos {
			txid, _ := chainhash.NewHashFromStr(utxo.Txid)
			inputs[i] = wire.NewOutPoint(txid, utxo.N)
		}

	} else {
		inputs = make([]*wire.OutPoint, len(utxos)+1)

		// spend from the previous output, so add it here as the first
		tip, _ := chainhash.NewHashFromStr(current.TipTx)
		inputs = append(
			[]*wire.OutPoint{wire.NewOutPoint(tip, 0)},
			inputs...,
		)

		for i, utxo := range utxos {
			txid, _ := chainhash.NewHashFromStr(utxo.Txid)
			inputs[i+1] = wire.NewOutPoint(txid, utxo.N)
		}
	}

	// now the only output is the new chain tip
	outputs := []*wire.TxOut{wire.NewTxOut(738 /* it's always 738 sats */, walletPkScript)}

	// and the nSequence is always 1
	nSequences := []uint32{1}

    // now if there is any change for us, add that
    sumInputs := 0
    for _, utxo := range utxos {
        sumInputs += utxo.Sat
    }
    

	// then we make a psbt
	packet, err := psbt.New(inputs, outputs, 2, 0, nSequences)
	if err != nil {
		log.Error().Err(err).Msg("failed to create psbt on /")
		w.WriteHeader(503)
		return
	}

    // and sign it for all inputs we have
    updater, _ := psbt.NewUpdater(packet)
    

    // finally build the next output
	b64psbt, _ := packet.B64Encode()
	next := struct {
		PresignedTx string `json:"presigned_tx"`
		PSBT        string `json:"psbt"`
	}{, b64psbt}

    // and return the response
	json.NewEncoder(w).Encode(struct {
		Current interface{} `json:"current"`
		Next    interface{} `json:"next"`
	}{current, next})
}
