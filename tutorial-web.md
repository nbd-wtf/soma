# Minting an asset and sending it to someone else using crappy web apps

Start by creating a keypair for yourself. You can do that by visiting https://gistcdn.githack.com/fiatjaf/3b33d6196749c3866dcf1e0a15df9fd4/raw/9bb9ef4d0f9f4cc7735a25f4873d263c86fcf383/index.html. That page will generate a keypair and store it locally on the browser as you open it.

![sw-web](https://user-images.githubusercontent.com/1653275/234933215-ae63c29d-fdaa-417d-bc6d-9d22aa33626c.png)

Now we must generate a `MINT` transaction. Do that by clicking on **"mint"**.

![sw-web-mint](https://user-images.githubusercontent.com/1653275/234933219-30dac96f-9e56-45d3-b84b-bdb66f618c7c.png)

In the next step we must send that transaction to a miner so it can include it in the next `soma` block. We can do that at the miner interaction web interface page at http://rawcdn.githack.com/nbd-wtf/soma/80e14c562da85d681b5c9c08b4a3c848e6b1fac1/miner-web-interface/index.html. The page will be prefilled with data from the first miner to be active on the network, which probably will work.

![miner-web](https://user-images.githubusercontent.com/1653275/234933225-541045f6-7613-4274-9946-5735b3cd1ecb.png)

Take your `MINT` transaction hex and paste it there, then click on **"submit transaction"** (beware, it will not work on `https://`, only on `http://`). You should see the response from the miner below the form.

![miner-web-invoice](https://user-images.githubusercontent.com/1653275/234933227-28f625f0-116e-46b3-859d-91510c03cbe9.png)

The next step is to pay the invoice. Since you probably don't have a signet Lightning node, we provide a webpage that pays signet invoices for you. Visit http://198.98.50.157:5556/ to access it, then just paste the invoice there.

![invoicepayer](https://user-images.githubusercontent.com/1653275/234933230-48c89168-0d8a-47e1-9183-c98f283ca94f.png)

Now the invoice payment will hang until the miner successfully publishes the block -- after which he will resolve the payment and take your money -- or after the miner fails to publish the block or your transaction -- in which case it will fail the lightning payment and you'll get your money back (well, in this case you get nothing since it's just the invoicepayer faucet doing all the things). You can keep this tab open while you watch the result.

Meanwhile you can go back to http://rawcdn.githack.com/nbd-wtf/soma/80e14c562da85d681b5c9c08b4a3c848e6b1fac1/miner-web-interface/index.html and this time call **"get status"**. This will result in a response containing the status of the next block the miner is trying to publish.

![miner-web-status](https://user-images.githubusercontent.com/1653275/234933236-cddb7677-f877-4fff-8120-bbc0f52258c6.png)

You can copy that `last_published_txid` and search for it on https://mempool.space/signet to see when it is mined (you may not see it before it is actually mined unless the mempool.space have upgraded their Bitcoin node to not reject `ANYPREVOUT` transactions, but you'll see it after it is successfully mined).

After the block is mined, the miner will broadcast his pending `soma` block to all `soma` nodes and thus it will be reflected on the explorer, which you can see at http://198.98.50.157:8080/.

![explorer](https://user-images.githubusercontent.com/1653275/234933239-073802e9-cec5-4956-803f-9e7af4d7b0b4.png)

Now that you own this new asset you can go back to https://gistcdn.githack.com/fiatjaf/3b33d6196749c3866dcf1e0a15df9fd4/raw/9bb9ef4d0f9f4cc7735a25f4873d263c86fcf383/index.html and `TRANSFER` it by clicking on **"send"**. The parameters required for making that transaction are the _asset id_, which you can get from the explorer, the _destination public key_ and a _counter_. The _counter_  will be `1` the first time you transfer an asset, `2` the second time and so on. That information is available in the `soma` node but currently not exposed in the explorer.

![sw-web-send](https://user-images.githubusercontent.com/1653275/234933892-913abf06-b395-41e8-b30d-41793b3c815f.png)
