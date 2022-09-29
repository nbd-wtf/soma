package main

import (
	_ "embed"
	"encoding/hex"
	"io/ioutil"
	"net/http"
	"os"
	"path"

	lnsocket "github.com/jb55/lnsocket/go"
	"github.com/mitchellh/go-homedir"
	"github.com/webview/webview"
)

//go:embed index.html
var index string

//go:embed target/esbuild/bundle.js
var script []byte

//go:embed target/esbuild/bundle.js.map
var sourcemap []byte

var (
	configDir, _ = homedir.Expand("~/.config/openchain/wallet")
	keysFile     = path.Join(configDir, "key")
)

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "text/html")
		w.Write([]byte(index))
	})
	http.HandleFunc("/bundle.js", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "application/javascript")
		w.Write(script)
	})
	http.HandleFunc("/bundle.js.map", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "application/javascript")
		w.Write(sourcemap)
	})
	go http.ListenAndServe(":43234", nil)

	w := webview.New(true)
	defer w.Destroy()
	w.SetTitle("openchain wallet")
	w.SetSize(1024, 768, webview.HintMin)
	w.Bind("commando", commando)
	w.Bind("storeKey", storeKey)
	w.Bind("loadKey", loadKey)
	w.SetHtml(index)
	w.Run()
}

func commando(nodeid string, host string, rune string, method string, params string) string {
	ln := lnsocket.LNSocket{}
	ln.GenKey()

	err := ln.ConnectAndInit(host, nodeid)
	if err != nil {
		return `{"error": "failed to connect to node"}`
	}
	defer ln.Disconnect()

	body, err := ln.Rpc(rune, "invoice", params)
	if err != nil {
		return `{"error": "failed to call commando"}`
	}

	return body
}

func storeKey(key string) {
	b, err := hex.DecodeString(key)
	if err != nil {
		panic(err)
	}
	os.WriteFile(keysFile, b, 0644)
}

func loadKey() string {
	b, err := ioutil.ReadFile(keysFile)
	if err != nil {
		return ""
	}
	if len(b) != 32 {
		return ""
	}
	return hex.EncodeToString(b)
}
