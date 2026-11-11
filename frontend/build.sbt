ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "us.awfl"
name := "compiler-rust-frontend"
version := "0.1.0-SNAPSHOT"
libraryDependencies ++= Seq(
  "us.awfl" %% "dsl" % "0.3.4",
  // Reuse the existing Scala workflow loader instead of maintaining a
  // separate reflection/class-name convention for the native compiler.
  "us.awfl" %% "compiler" % "0.3.1",
  // Only test scope: compile an unchanged real workflow from awfl-us/workflows.
  "us.awfl" %% "workflow-utils" % "0.3.3" % Test,
  "io.circe" %% "circe-core" % "0.14.7",
  "io.circe" %% "circe-parser" % "0.14.7"
)
// Override older transitive DSL versions pulled by the existing workflow
// loader and workflow-utils; v0.3.4 only adds operation-name constants.
dependencyOverrides += "us.awfl" %% "dsl" % "0.3.4"
scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions", "-Werror")
