enablePlugins(ScalaJSPlugin)

name := "openchain node"
scalaVersion := "3.1.3"

libraryDependencies ++= Seq(
  "com.lihaoyi" %%% "upickle" % "1.6.0",
  "org.scodec" %%% "scodec-core" % "2.2.0",
  "org.scodec" %%% "scodec-bits" % "1.1.34",
  "com.softwaremill.sttp.client3" %%% "core" % "3.7.4",
  "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0",
)

scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }

scalaJSUseMainModuleInitializer := true
