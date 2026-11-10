ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "us.awfl"
name := "compiler-rust-frontend"
version := "0.1.0-SNAPSHOT"
libraryDependencies ++= Seq(
  "us.awfl" %% "dsl" % "0.1.0-SNAPSHOT",
  // Reuse the existing Scala workflow loader instead of maintaining a
  // separate reflection/class-name convention for the native compiler.
  "us.awfl" %% "compiler" % "0.3.1",
  "io.circe" %% "circe-core" % "0.14.7",
  "io.circe" %% "circe-parser" % "0.14.7"
)
// The published compiler still resolves the previous DSL release. The native
// frontend needs the typed operation IDs until the coordinated releases land.
dependencyOverrides += "us.awfl" %% "dsl" % "0.1.0-SNAPSHOT"
scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions", "-Werror")
