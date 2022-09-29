enablePlugins(ScalaJSPlugin, EsbuildPlugin)

name := "openchain-wallet"
scalaVersion := "3.2.0"

scalaJSUseMainModuleInitializer := true

libraryDependencies ++= Seq(
  "io.circe" %%% "circe-core" % "0.14.3",
  "io.circe" %%% "circe-generic" % "0.14.3",
  "io.circe" %%% "circe-parser" % "0.14.3",
  "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.0",
  "com.softwaremill.sttp.client3" %%% "core" % "3.8.0",
  "com.raquo" %%% "laminar" % "0.14.5",

  // use these until everybody updates to scala-3.2.0
  "org.typelevel" %%% "cats-core" % "2.9-826466b-SNAPSHOT",
)

Compile / npmDependencies ++= Seq(
  "kjua" -> "0.9.0"
)

esbuildOptions ++= Seq(
  "--target=es6"
)
esPackageManager := Yarn

scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }

resolvers += "s01" at "https://s01.oss.sonatype.org/content/repositories/snapshots/"
