package main

import (
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	"os"
	"path"

	lnsocket "github.com/jb55/lnsocket/go"
	"github.com/mitchellh/go-homedir"
	"github.com/webview/webview"
)

//go:embed app.html
var html string

//go:embed target/esbuild/bundle.js
var script []byte

//go:embed target/esbuild/bundle.js.map
var sourcemap []byte

var (
	configDir, _ = homedir.Expand("~/.config/openchain/wallet")
	keysFile     = path.Join(configDir, "key")
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "webapp" {
		http.HandleFunc("/commando/", func(w http.ResponseWriter, r *http.Request) {
			var params struct {
				NodeId string `json:"nodeId"`
				Host   string `json:"host"`
				Rune   string `json:"rune"`
				Method string `json:"method"`
				Params string `json:"params"`
			}
			json.NewDecoder(r.Body).Decode(&params)
			w.Write([]byte(commando(params.NodeId, params.Host, params.Rune, params.Method, params.Params)))
		})
		http.Handle("/", http.FileServer(http.Dir(".")))
		fmt.Println("listening at http://127.0.0.1:8000")
		http.ListenAndServe(":8000", nil)
	} else {
		http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("content-type", "text/html")
			w.Write([]byte(html))
		})
		http.HandleFunc("/bundle.js", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("content-type", "application/javascript")
			w.Write(script)
		})
		http.HandleFunc("/bundle.js.map", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("content-type", "application/javascript")
			w.Write(sourcemap)
		})
		go http.ListenAndServe(":43222", nil)
		w := webview.New(true)
		defer w.Destroy()
		w.SetTitle("wallet")
		w.SetSize(1024, 768, webview.HintMin)
		w.Bind("storeKey", storeKey)
		w.Bind("loadKey", loadKey)
		w.Bind("commando", commando)
		w.Bind("getMiners", getMiners)
		w.SetHtml(html)
		w.Run()
	}
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

func commando(nodeid string, host string, rune string, method string, params string) string {
	ln := lnsocket.LNSocket{}
	ln.GenKey()

	err := ln.ConnectAndInit(host, nodeid)
	if err != nil {
		log.Print(err)
		return `{"error": "failed to connect to node: ` + err.Error() + `"}`
	}
	defer ln.Disconnect()

	body, err := ln.Rpc(rune, method, params)
	if err != nil {
		return `{"error": "failed to call commando: ` + err.Error() + `"}`
	}

	return body
}

func getMiners() string {
	j, _ := json.Marshal([]struct {
		Pubkey string `json:"pubkey"`
		Host   string `json:"host"`
		Port   int    `json:"port"`
		Rune   string `json:"rune"`
	}{
		// signet
		// {
		//   Pubkey: "02855372fc5d61dfbe939aa04edb662beccc314d693d6ed65a92293137e9cf657b",
		//   Host: "127.0.0.1:9730",
		//   Rune: "tG5ixNeDcl2FxhY6Abi7jL_BauD-Ss1f-XfX0zKNNGg9MCZtZXRob2Reb3BlbmNoYWluLQ=="
		// }

		// regtest
		{
			Pubkey: "021b7a3a371b81f2f94a1998d76e5d28598d942003290f078e34bf335d2d28642a",
			Host:   "127.0.0.1:9730",
			Rune:   "NxuE4lr_ywfDrSSjRR-a8SFBBkRXTssSXpCPnD3U6t09MSZtZXRob2Reb3BlbmNoYWluLQ==",
		},
		{
			Pubkey: "03786e2997686741ce9cd55c0344b7f5a6f1bc53a1b078740a25fbbb92586dbcc6",
			Host:   "127.0.0.1:9730",
			Rune:   "hd6Q5vvUO6ex68JcIPh2NG8jTKjYs9xYDOeRbK_b82w9MCZtZXRob2Reb3BlbmNoYWluLQ==",
		},
	})
	return string(j)
}
