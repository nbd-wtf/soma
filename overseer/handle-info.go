package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"net/http"

	"github.com/Dexconv/go-bitcoind"
	"github.com/btcsuite/btcd/btcutil/psbt"
	"github.com/btcsuite/btcd/chaincfg/chainhash"
	"github.com/btcsuite/btcd/txscript"
	"github.com/btcsuite/btcd/wire"
)

func handleInfo(w http.ResponseWriter, r *http.Request) {
	log.Debug().Msg("http call")

	var genesis string
	if err := db.Get(
		&genesis,
		"SELECT txid FROM chain_block_tx LIMIT 1",
	); err != nil {
		w.WriteHeader(404)
		json.NewEncoder(w).Encode(struct {
			Message string `json:"message"`
			Amount  int    `json:"amount"`
			Address string `json:"address"`
		}{
			"Genesis transaction not found. To bootstrap this chain send the canonical amount of satoshis to the canonical address.",
			CANONICAL_AMOUNT,
			chainAddress,
		})
		return
	}

	var current struct {
		TxCount uint64 `db:"idx" json:"tx_count,omitempty"`
		TipTx   string `db:"txid" json:"tip_tx,omitempty"`
	}
	if err := db.Get(
		&current,
		"SELECT idx, txid FROM chain_block_tx ORDER BY idx DESC LIMIT 1",
	); err != nil {
		w.WriteHeader(502)
		return
	}

	// our output is always the 0th, except in the first tx after the genesis, when it could be anywhere
	outputIdx := 0
	if current.TxCount < 2 {
		itx, err := bc.GetRawTransaction(current.TipTx, true)
		if err != nil {
			w.WriteHeader(503)
			return
		}
		tx := itx.(bitcoind.RawTransaction)
		for idx, output := range tx.Vout {
			if output.ScriptPubKey.Hex == hex.EncodeToString(chainPubKeyScript) {
				outputIdx = idx
			}
		}
	}

	// make the next presigned tx
	tip, _ := chainhash.NewHashFromStr(current.TipTx)
	packet, _ := psbt.New(
		[]*wire.OutPoint{{Hash: *tip, Index: uint32(outputIdx)}},
		[]*wire.TxOut{{Value: CANONICAL_AMOUNT, PkScript: chainPubKeyScript}},
		2,
		0,
		[]uint32{1},
	)

	// sign previous chain tip
	// we will use this txscript thing to build the signature for us, then we will take it and apply to the psbt
	witnessProgram, _ := txscript.NewScriptBuilder().AddOp(txscript.OP_0).AddData(chainPubKeyHash).Script()
	fetcher := txscript.NewCannedPrevOutputFetcher(chainPubKeyScript, CANONICAL_AMOUNT)
	sigHashes := txscript.NewTxSigHashes(packet.UnsignedTx, fetcher)
	signature, err := txscript.RawTxInWitnessSignature(
		packet.UnsignedTx,
		sigHashes,
		0,
		CANONICAL_AMOUNT,
		witnessProgram,
		txscript.SigHashSingle|txscript.SigHashAnyOneCanPay,
		chainKey,
	)
	if err != nil {
		log.Fatal().Err(err).Msg("failed to compute")
		return
	}

	upd, _ := psbt.NewUpdater(packet)
	upd.AddInWitnessUtxo(wire.NewTxOut(CANONICAL_AMOUNT, chainPubKeyScript), 0)
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
		Genesis string      `json:"genesis"`
		Current interface{} `json:"current"`
		Next    interface{} `json:"next"`
	}{genesis, current, next})
}
