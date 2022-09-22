enablePlugins(ScalaJSPlugin, EsbuildPlugin)

name := "openchain-wallet"
scalaVersion := "3.2.0"

scalaJSUseMainModuleInitializer := true

libraryDependencies ++= Seq(
  "io.circe" %%% "circe-core" % "0.14.1",
  "io.circe" %%% "circe-generic" % "0.14.1",
  "io.circe" %%% "circe-parser" % "0.14.1",
  "com.raquo" %%% "laminar" % "0.14.2",
  "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0",
  "com.softwaremill.sttp.client3" %%% "core" % "3.8.0"
)

Compile / npmDependencies ++= Seq(
  "lnsocket" -> "0.3.2",
  "kjua" -> "0.9.0"
)

esbuildOptions ++= Seq(
  "--external:crypto",
  "--external:path",
  "--external:net",
  "--external:fs",
  "--tree-shaking=true"
)
esPackageManager := Yarn

scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
