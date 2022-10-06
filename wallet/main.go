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
	"os/exec"
	"path"
	"runtime"

	lnsocket "github.com/jb55/lnsocket/go"
	"github.com/mitchellh/go-homedir"
)

//go:embed index.html
var html string

//go:embed target/esbuild/bundle.js
var script []byte

var (
	configDir, _ = homedir.Expand("~/.config/openchain/wallet")
	keysFile     = path.Join(configDir, "key")
)

func main() {
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
	http.HandleFunc("/key", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case "POST":
			b, _ := ioutil.ReadAll(r.Body)
			if len(b) > 0 {
				hex := string(b)
				storeKey(hex)
			}
		case "GET":
			w.Write([]byte(loadKey()))
		}
	})

	var url string
	if len(os.Args) > 1 && os.Args[1] == "dev" {
		http.Handle("/", http.FileServer(http.Dir(".")))
		fmt.Println("listening at http://127.0.0.1:8000")
		go http.ListenAndServe(":8000", nil)

		url = "http://localhost:8000"
	} else {
		http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("content-type", "text/html")
			w.Write([]byte(html))
		})
		http.HandleFunc("/target/esbuild/bundle.js", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("content-type", "application/javascript")
			w.Write(script)
		})
		go http.ListenAndServe(":43222", nil)

		url = "http://localhost:43222"
	}

	// open browser
	var err error
	switch runtime.GOOS {
	case "linux":
		err = exec.Command("xdg-open", url).Start()
	case "windows":
		err = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		err = exec.Command("open", url).Start()
	default:
		err = fmt.Errorf("unsupported platform")
	}
	if err != nil {
		log.Fatal(err)
	}

	// wait
	<-make(chan struct{})
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
