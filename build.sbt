name := """convai-testing-system"""

version := "0.1"

scalaVersion := "2.12.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.18",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.18" % Test,
  "org.scalatest" %% "scalatest" % "3.0.3" % Test,
  "info.mukel" %% "telegrambot4s" % "3.0.0-SNAPSHOT",
  "org.slf4j" % "slf4j-log4j12" % "1.7.25"
)

enablePlugins(JavaAppPackaging)
