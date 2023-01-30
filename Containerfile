FROM alpine:3.16
RUN apk add bash git clang binutils-gold cmake make libgcc musl-dev gcc g++ libc6-compat libtool automake autoconf
RUN apk add sbt --repository=http://dl-cdn.alpinelinux.org/alpine/edge/testing
RUN git clone https://github.com/libuv/libuv && cd libuv && ./autogen.sh && ./configure && make && make install
RUN git clone https://github.com/bitcoin-core/secp256k1 && cd secp256k1 && ./autogen.sh && ./configure --enable-module-schnorrsig --enable-module-recovery && make && make install
RUN apk add curl-dev
COPY . /openchain
RUN cd /openchain && sbt clean compile
ENV SN_LINK=static
CMD cd /openchain && sbt clean 'miner/nativeLink'
