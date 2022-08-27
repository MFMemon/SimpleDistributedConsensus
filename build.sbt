scalaVersion := "3.1.0"

scalacOptions ++= Seq(
    "-feature",
    "-unchecked",
    "-encoding", "UTF-8",
    "deprecation"
)

run / fork := true
run / connectInput := true

val akkaVersion = "2.6.19"
libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "org.scodec" %% "scodec-core" % "2.1.0",
    "ch.qos.logback" % "logback-classic" % "1.2.10"
)
