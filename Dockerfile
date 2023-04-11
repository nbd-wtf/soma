FROM node:lts-alpine3.16

# prepare
RUN apk add tar gzip bash git clang binutils-gold cmake make libgcc musl-dev gcc g++ libc6-compat libtool automake autoconf wget curl-dev libcurl unzip openjdk11 xz python3 bash
RUN git clone https://github.com/libuv/libuv && cd libuv && ./autogen.sh && ./configure && make && make install
RUN git clone https://github.com/bitcoin-core/secp256k1 && cd secp256k1 && ./autogen.sh && ./configure --enable-module-schnorrsig --enable-module-recovery && make && make install
RUN mkdir /soma
COPY build.sbt /soma/build.sbt
COPY core/ /soma/core
COPY miner/ /soma/miner
COPY node/ /soma/node
COPY explorer/ /soma/explorer
COPY sw/ /soma/sw

# sbt
RUN mkdir /sbt
RUN cd /sbt && wget https://github.com/sbt/sbt/releases/download/v1.8.2/sbt-1.8.2.zip
RUN cd /sbt && unzip sbt-1.8.2.zip

# scoin
RUN git clone https://github.com/fiatjaf/scoin
RUN cd scoin && git checkout anyprevout && /sbt/sbt/bin/sbt 'scoinJS/publishLocal' 'scoinNative/publishLocal'

# bitcoind
RUN wget https://github.com/bitcoin-inquisition/bitcoin/releases/download/inq-v24.0/bitcoin-inq-v24.0-x86_64-linux-gnu.tar.gz
RUN tar -xf bitcoin-inq-v24.0-x86_64-linux-gnu.tar.gz -C /

# lightning node and miner
RUN wget https://github.com/ElementsProject/lightning/releases/download/v23.02.2/clightning-v23.02.2-Ubuntu-22.04.tar.xz
RUN tar -xf clightning-v23.02.2-Ubuntu-22.04.tar.xz -C /
RUN cd /soma && /sbt/sbt/bin/sbt 'miner/nativeLink'
RUN cp /soma/miner/target/scala-3.2.2/soma-miner-out /

# explorer
RUN cd /soma && /sbt/sbt/bin/sbt 'explorer/fullLinkJS/esBuild' -DdefaultNodeUrl='http://127.0.0.1:9036' -DdefaultTxExplorerUrl='https://mempool.space/signet/tx/'

# sw
RUN cd /soma && /sbt/sbt/bin/sbt 'sw/nativeLink'
RUN cp /soma/sw/target/scala-3.2.2/soma-cli-wallet-out /usr/local/bin/sw

# node
RUN cd /soma && /sbt/sbt/bin/sbt 'node/fullLinkJS'
RUN cd /soma/node && npm install

CMD ["bash"]
