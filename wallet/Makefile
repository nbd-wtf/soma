files = $(shell find . -name "*.go")

all: dist/wallet_linux_x64 dist/wallet_darwin_arm dist/wallet_darwin_x64 dist/wallet.exe

dist/wallet_linux_x64: $(files)
	mkdir -p dist
	GOOS=linux GOARCH=amd64 go build -o $@

dist/wallet_darwin_arm: $(files)
	mkdir -p dist
	GOOS=darwin GOARCH=arm64 go build -o $@

dist/wallet_darwin_x64: $(files)
	mkdir -p dist
	GOOS=darwin GOARCH=amd64 go build -o $@

dist/wallet.exe: $(files)
	mkdir -p dist
	GOOS=windows GOARCH=amd64 go build -o $@
