#!/usr/bin/env node

const esbuild = require('esbuild')

const prod = process.argv.indexOf('prod') !== -1

esbuild
  .build({
    entryPoints: ['globals.js'],
    outfile: 'globals.bundle.js',
    bundle: true,
    sourcemap: prod ? false : 'inline',
    minify: prod
  })
  .then(() => console.log('build success.'))
