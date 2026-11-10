ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "us.awfl"
name := "compiler-rust-frontend"
version := "0.1.0-SNAPSHOT"
libraryDependencies ++= Seq(
  "us.awfl" %% "dsl" % "0.1.0-SNAPSHOT",
  "io.circe" %% "circe-core" % "0.14.7",
  "io.circe" %% "circe-parser" % "0.14.7"
)
scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions", "-Werror")
