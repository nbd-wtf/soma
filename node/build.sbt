enablePlugins(ScalaJSPlugin)

name := "openchain node"
scalaVersion := "3.1.3"

libraryDependencies ++= Seq(
  "org.scodec" %%% "scodec-core" % "2.2.0",
  "org.scodec" %%% "scodec-bits" % "1.1.34",
  "com.lihaoyi" %%% "upickle" % "1.6.0",
  "com.fiatjaf" %%% "scoin" % "0.3.1-SNAPSHOT",
  "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0",
  "com.softwaremill.sttp.client3" %%% "core" % "3.7.4",
)

scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }

scalaJSUseMainModuleInitializer := true
