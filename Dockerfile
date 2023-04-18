FROM node:lts-bullseye

# prepare
RUN apt update
RUN apt install git clang binutils build-essential libtool automake autoconf wget unzip python3 openjdk-11-jdk libcurl4 libgmp-dev libsqlite3-dev python3 python3-pip net-tools zlib1g-dev libsodium-dev gettext tmux -y
RUN git clone https://github.com/libuv/libuv && cd libuv && ./autogen.sh && ./configure && make && make install
RUN cp /usr/local/lib/libuv* /usr/lib/x86_64-linux-gnu/
RUN git clone https://github.com/bitcoin-core/secp256k1 && cd secp256k1 && ./autogen.sh && ./configure --enable-module-schnorrsig --enable-module-recovery && make && make install
RUN cp /usr/local/lib/libsecp256k1* /usr/lib/x86_64-linux-gnu/

# sbt
RUN mkdir /sbt
RUN cd /sbt && wget https://github.com/sbt/sbt/releases/download/v1.8.2/sbt-1.8.2.zip
RUN cd /sbt && unzip sbt-1.8.2.zip

# scoin
RUN git clone https://github.com/fiatjaf/scoin
RUN cd scoin && git checkout anyprevout && /sbt/sbt/bin/sbt 'scoinJS/publishLocal' 'scoinNative/publishLocal'

# bitcoind
RUN wget https://github.com/bitcoin-inquisition/bitcoin/releases/download/inq-v24.0/bitcoin-inq-v24.0-x86_64-linux-gnu.tar.gz
RUN tar -xf bitcoin-inq-v24.0-x86_64-linux-gnu.tar.gz

# lightning node
RUN pip3 install --upgrade pip
RUN pip3 install --user poetry
RUN git clone https://github.com/ElementsProject/lightning
RUN cd lightning && pip3 install --upgrade pip && pip3 install mako && ./configure && make && make install

# base source
RUN mkdir -p /soma/project
COPY project/build.properties /soma/project/build.properties
COPY project/plugins.sbt /soma/project/plugins.sbt
COPY build.sbt /soma/build.sbt
COPY core/ /soma/core

# miner
RUN wget https://github.com/ElementsProject/lightning/releases/download/v23.02.2/clightning-v23.02.2-Ubuntu-22.04.tar.xz
RUN tar -xf clightning-v23.02.2-Ubuntu-22.04.tar.xz -C /
COPY miner/ /soma/miner
RUN cd /soma && /sbt/sbt/bin/sbt 'miner/nativeLink'
RUN cp /soma/miner/target/scala-3.2.2/soma-miner-out /

# explorer
COPY explorer/ /soma/explorer
RUN cd /soma && /sbt/sbt/bin/sbt 'explorer/fullLinkJS/esBuild' -DdefaultNodePort='9036' -DdefaultTxExplorerUrl='https://mempool.space/signet/tx/'

# sw
COPY sw/ /soma/sw
RUN cd /soma && /sbt/sbt/bin/sbt 'sw/nativeLink'
RUN cp /soma/sw/target/scala-3.2.2/soma-cli-wallet-out /usr/local/bin/sw

# node
COPY node/ /soma/node
RUN cd /soma && /sbt/sbt/bin/sbt 'node/fullLinkJS'
RUN cd /soma/node && npm install

CMD tmux new-session \; \
set -g mouse on \; \
  rename-window bitcoind \; \
  send-keys './bitcoin-inq-v24.0/bin/bitcoind -signet -txindex=1 -rpcuser=x -rpcpassword=x -addnode=81.204.239.212 -addnode=209.141.62.48 -addnode=49.12.208.214 -addnode=135.181.215.237 -addnode=128.199.252.50 -addnode=95.217.184.148 -addnode=172.105.179.233 -addnode=45.79.105.203 -addnode=103.16.128.63 -addnode=209.141.62.48 -addnode=inquisition.bitcoin-signet.net' C-m \; \
new-window -n lightningd \; \
  send-keys 'lightningd --network signet --min-capacity-sat=1000 --database-upgrade=true --plugin /soma/miner/target/scala-3.2.2/soma-miner-out --bitcoin-cli /bitcoin-inq-v24.0/bin/bitcoin-cli' \; \
new-window -c /soma -n somad \; \
  send-keys 'BITCOIN_CHAIN=signet BITCOIND_USER=x BITCOIND_PASSWORD=x GENESIS_TX=60b4190b2ae6d4cb5e4d366e2867f726be401adbc0b2a6fa2a1e8fe55fb0fb70 node ./node/target/scala-3.2.2/soma-node-opt/main.js' \; \
new-window -c /soma -n misc \; \
new-window -c /soma -n wallet \; \
  send-keys './sw/target/scala-3.2.2/soma-cli-wallet-out mint' \; \
  split \; \
    send-keys 'lightning-cli --network signet newaddr ' \; \
  split \; \
    send-keys 'lightning-cli --network signet pay ' \; \
new-window -c soma/explorer -n explorer \; \
  send-keys 'python3 -m http.server 8080' C-m \; \
prev \; \
prev
