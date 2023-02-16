## Building for production

This must be compiled first with

```
sbt 'wallet/fullLinkJS/esBuild' -DdefaultNodeUrl='http://127.0.0.1:9036' -DdefaultTxExplorerUrl='https://mempool.space/tx/' -DdefaultMiner='nodePubkey:commandoRune@ipaddress:port'
```

(Replace parameters above with the correct values for your setup, above are the defaults, except for default miner for which there is no default.)

(You can also set these values manually at `localStorage` once the app is running at `nodeUrl` and `txExplorerUrl` keys.)

After compiling, do a `go build` to pack everything into a single Go executable called `wallet`. When ran, the Go executable will start a webserver and open a page in the default browser to view it.

## Development

When in development, run with `./wallet dev` and it will not use the packaged assets, but instead the assets from the filesystem directly, so you can edit them and refresh to see the results.

While developing, it's sane to run `sbt '~wallet/fastLinkJS/esBuild'` to keep rebuilding once things change.
