enablePlugins(ScalaNativePlugin)

name                  := "openchain-miner"
organization          := "fiatjaf"
scalaVersion          := "3.1.3"
version               := "0.1.0-SNAPSHOT"
libraryDependencies   ++= Seq(
  "com.lihaoyi" %%% "upickle" % "1.6.0",
  "com.lihaoyi" %%% "ujson" % "1.6.0",
  "com.fiatjaf" %%% "scoin" % "0.1.0-SNAPSHOT",
  "com.fiatjaf" %%% "nlog" % "0.1.0",
  "com.fiatjaf" %%% "sn-unixsocket" % "0.1.0",
  "com.github.lolgab" %%% "httpclient" % "0.0.1",
)
testFrameworks  += new TestFramework("utest.runner.Framework")
nativeLinkStubs := true

// _for-release_ nativeMode := "release-fast"
// _for-release_ nativeLTO := "thin"
// _for-armv6_ nativeConfig ~= { _.withTargetTriple("armv6-pc-linux-unknown") }