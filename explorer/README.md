## Building

Compile with

```
sbt 'explorer/fullLinkJS/esBuild' -DdefaultNodeUrl='http://127.0.0.1:9036' -DdefaultTxExplorerUrl='https://mempool.space/tx/'
```

(Replace parameters above with the correct values for your setup, above are the defaults.)

(You can also set these values manually at `localStorage` once the app is running at `nodeUrl` and `txExplorerUrl` keys.)

Then serve the entire directory as static files. Actually you only need `index.html` and `target/esbuild/bundle.js`, but the later is expected to be exactly on that path.

## Development

To develop, just run `sbt '~explorer/fastLinkJS/esBuild'` to keep rebuilding once things change and run a static file server on the side, like with `python -m http.server 8000` or similar commands.
