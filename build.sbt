ThisBuild / scalaVersion        := "3.2.0"
ThisBuild / organization        := "com.fiatjaf"
ThisBuild / homepage            := Some(url("https://github.com/fiatjaf/openchain"))
ThisBuild / licenses            += License.MIT
ThisBuild / developers          := List(tlGitHubDev("fiatjaf", "fiatjaf"))

ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / tlSonatypeUseLegacyHost := false

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .settings(
    name := "openchain-core",
    libraryDependencies ++= Seq(
      "com.fiatjaf" %%% "scoin" % "0.4.0-SNAPSHOT"
    )
  )
  .in(file("core"))

lazy val miner = project
  .settings(
    name := "openchain-miner",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "1.6.0",
      "com.lihaoyi" %%% "ujson" % "1.6.0",
      "com.fiatjaf" %%% "nlog" % "0.1.0",
      "com.fiatjaf" %%% "sn-unixsocket" % "0.2.0",
      "com.github.lolgab" %%% "httpclient" % "0.0.1",
      "com.github.lolgab" %%% "native-loop-core" % "0.2.1",
    )
  )
  .dependsOn(core.native)
  .enablePlugins(ScalaNativePlugin)
  .in(file("miner"))

lazy val node = project
  .settings(
    name := "openchain-node",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "1.6.0",
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0",
      "com.softwaremill.sttp.client3" %%% "core" % "3.7.4"
    ),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
  )
  .dependsOn(core.js)
  .enablePlugins(ScalaJSPlugin)
  .in(file("node"))

lazy val wallet = project
  .settings(
    name := "openchain-wallet",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % "0.14.3",
      "io.circe" %%% "circe-generic" % "0.14.3",
      "io.circe" %%% "circe-parser" % "0.14.3",
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.0",
      "com.softwaremill.sttp.client3" %%% "core" % "3.8.0",
      "com.raquo" %%% "laminar" % "0.14.5",

      // use these until everybody updates to scala-3.2.0
      "org.typelevel" %%% "cats-core" % "2.9-826466b-SNAPSHOT",
    ),
    Compile / npmDependencies ++= Seq(
      "kjua" -> "0.9.0"
    ),
    esbuildOptions += "--target=es2020",
    esPackageManager := Yarn,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    resolvers += "s01" at "https://s01.oss.sonatype.org/content/repositories/snapshots/"
  )
  .dependsOn(core.js)
  .enablePlugins(EsbuildPlugin, ScalaJSPlugin)
  .in(file("wallet"))

lazy val explorer = project
  .settings(
    name := "openchain-explorer",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % "0.14.3",
      "io.circe" %%% "circe-generic" % "0.14.3",
      "io.circe" %%% "circe-parser" % "0.14.3",
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.0",
      "com.softwaremill.sttp.client3" %%% "core" % "3.8.0",
      "com.raquo" %%% "laminar" % "0.14.5",

      // use these until everybody updates to scala-3.2.0
      "org.typelevel" %%% "cats-core" % "2.9-826466b-SNAPSHOT",
    ),
    esPackageManager := Yarn,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    resolvers += "s01" at "https://s01.oss.sonatype.org/content/repositories/snapshots/"
  )
  .dependsOn(core.js)
  .enablePlugins(EsbuildPlugin, ScalaJSPlugin)
  .in(file("explorer"))
