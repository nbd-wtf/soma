package main

import (
	"encoding/hex"
	"encoding/json"
	"io/ioutil"
	"net/http"
	"os"
	"path"
	"strings"

	"github.com/Dexconv/go-bitcoind"
	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/btcsuite/btcd/btcutil"
	"github.com/btcsuite/btcd/chaincfg"
	"github.com/jmoiron/sqlx"
	"github.com/kelseyhightower/envconfig"
	"github.com/mitchellh/go-homedir"
	"github.com/rs/zerolog"
	_ "modernc.org/sqlite"
)

type Settings struct {
	BitcoinChain string `envconfig:"BITCOIN_CHAIN" default:"mainnet"`

	BitcoindHost     string `envconfig:"BITCOIND_HOST" default:"127.0.0.1"`
	BitcoindPort     int    `envconfig:"BITCOIND_PORT"`
	BitcoindUser     string `envconfig:"BITCOIND_USER"`
	BitcoindPassword string `envconfig:"BITCOIND_PASSWORD"`

	ConfigPath string `json:"CONFIG_PATH" default:"~/.config/openchain/overseer"`
}

var chainParams = &chaincfg.MainNetParams

const CANONICAL_AMOUNT = 738

// configs
var (
	s          Settings
	configDir  string
	configPath string
	sqlitedsn  string
)

// global instances
var (
	log = zerolog.New(os.Stderr).Output(zerolog.ConsoleWriter{Out: os.Stderr})
	db  *sqlx.DB
	bc  *bitcoind.Bitcoind
)

// runtime global values
var (
	chainKey          *btcec.PrivateKey
	chainPubKeyHash   []byte
	chainPubKeyScript []byte
	chainAddress      string
	chainHasStarted   bool
)

type Config struct {
	ChainKey string `json:"chainkey"`
}

func main() {
	zerolog.SetGlobalLevel(zerolog.DebugLevel)

	// environment variables
	err := envconfig.Process("", &s)
	if err != nil {
		log.Fatal().Err(err).Msg("couldn't process envconfig.")
		return
	}

	// configs
	switch s.BitcoinChain {
	case "mainnet":
		chainParams = &chaincfg.MainNetParams
	case "testnet":
		chainParams = &chaincfg.TestNet3Params
	case "signet":
		chainParams = &chaincfg.SigNetParams
	case "regtest":
		chainParams = &chaincfg.RegressionNetParams
	}

	if s.BitcoindPort == 0 {
		switch s.BitcoinChain {
		case "mainnet":
			s.BitcoindPort = 8332
		case "testnet":
			s.BitcoindPort = 18332
		case "signet":
			s.BitcoindPort = 38332
		case "regtest":
			s.BitcoindPort = 18443
		}
	}

	// paths
	configDir, _ = homedir.Expand(s.ConfigPath)
	configPath = path.Join(configDir, "keys.json")
	sqlitedsn = path.Join(configDir, "db.sqlite")
	os.MkdirAll(configDir, 0700)

	// create tables
	db = sqlx.MustOpen("sqlite", sqlitedsn)
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

	// check if the chain has started
	db.Get(&chainHasStarted, "SELECT true FROM chain_block_tx")

	// start bitcoind RPC
	if bitcoindRPC, err := bitcoind.New(s.BitcoindHost, s.BitcoindPort, s.BitcoindUser, s.BitcoindPassword, false); err != nil {
		log.Fatal().Err(err).Msg("can't connect to bitcoind")
		return
	} else {
		bc = bitcoindRPC
	}

	// init config and keys
	if b, err := ioutil.ReadFile(configPath); err != nil {
		if strings.HasSuffix(err.Error(), "no such file or directory") {
			// create a new private key
			log.Info().Str("path", configPath).Msg("creating private key and storing it")
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

	// if we don't have any block data in the database,
	// determine that we are starting now so we don't have to scan the entire chain
	if count, err := bc.GetBlockCount(); err != nil {
		log.Fatal().Err(err).Msg("error getting block count")
		return
	} else {
		// we just do this, it will fail if the key is already set
		db.Exec("INSERT INTO kv VALUES ('blockheight', $1)", count)
	}

	// print addresses
	chainPubKeyHash = btcutil.Hash160(chainKey.PubKey().SerializeCompressed())
	p2wpkhAddress, _ := btcutil.NewAddressWitnessPubKeyHash(chainPubKeyHash, chainParams)
	chainAddress = p2wpkhAddress.String()
	log.Info().
		Str("address", chainAddress).
		Int("canonical-amount", CANONICAL_AMOUNT).
		Msg("send the canonical amount to this address to start the chain")
	chainPubKeyScript = append([]byte{0, 20}, chainPubKeyHash...)

	// handle commands
	http.HandleFunc("/", handleInfo)
	go http.ListenAndServe(":10738", nil)
	log.Info().Int("port", 10738).Msg("listening")

	// inspect blocks
	inspectBlocks()
}
