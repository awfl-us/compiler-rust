package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import io.circe.Json

/** Deterministic manifest and Cargo crate construction. No DSL lowering here. */
object ArtifactWriter {
  def pack(program: Program, source: String): RustEmitter.Emitted = {
    val sha = MessageDigest.getInstance("SHA-256").digest(source.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
    // Parallel is an authorized internal effect, not an added public DSL Call.
    // The release must advertise it explicitly before Jobs can dispatch lanes.
    def parallelNodes(all: Vector[Instruction]): Vector[String] = all.flatMap {
      case fork: Fork => Vector(fork.nodeId) ++
        fork.branches.flatMap(b => parallelNodes(b.body))
      case fork: ForkEach => Vector(fork.nodeId) ++ parallelNodes(fork.body)
      case choice: Choose => choice.cases.flatMap(c => parallelNodes(c.body))
      case rescue: Rescue => parallelNodes(rescue.attempt) ++ parallelNodes(rescue.handler)
      case loop: RepeatRange => parallelNodes(loop.body)
      case loop: RepeatEach => parallelNodes(loop.body)
      case loop: ReduceEach => parallelNodes(loop.body)
      case _ => Vector.empty
    }
    val forks = parallelNodes(program.steps)
    val declaredEffects = program.effects.map(e => Json.obj(
      "node_id" -> Json.fromString(e.nodeId),
      "operation" -> Json.fromString(e.id.wireId)
    )) ++ forks.map(node => Json.obj(
      "node_id" -> Json.fromString(node),
      "operation" -> Json.fromString("native.parallel")
    ))
    val manifest = Json.obj((
      Vector[(String, Json)](
      "schema_version" -> Json.fromInt(program.irVersion),
      "workflow" -> Json.fromString(program.name),
      "source_sha256" -> Json.fromString(sha),
      "runtime_abi" -> Json.fromInt(3),
      "stream_ipc_version" -> Json.fromInt(1),
      "trusted_globals_version" -> Json.fromInt(1),
      "node_ids" -> Json.arr(program.nodeIds.map(Json.fromString)*),
      "effects" -> Json.arr(declaredEffects*)
      ) ++ (if (forks.nonEmpty) Vector("parallel_version" -> Json.fromInt(1))
            else Vector.empty)
    )*).noSpaces
    RustEmitter.Emitted(source, manifest)
  }
  def write(directory: Path, runtime: Path, generated: RustEmitter.Emitted): RustEmitter.Emitted = {
    Files.createDirectories(directory.resolve("src"))
    val relative = directory.toAbsolutePath.normalize.relativize(runtime.toAbsolutePath.normalize)
    // Each generated binary has its own deterministic Cargo package name.
    // A shared build target must never return a different workflow's binary.
    val artifactName = directory.getFileName.toString
    val shortName = artifactName.toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_").take(48)
    val shortHash = MessageDigest.getInstance("SHA-256")
      .digest(artifactName.getBytes(UTF_8)).take(4).map(b => f"${b & 0xff}%02x").mkString
    val crateName = s"awfl_${if (shortName.isEmpty) "workflow" else shortName}_$shortHash"
    // Cargo accepts forward-slash relative paths on Unix; path is controlled by caller.
    val cargo = s"""[package]
name = "$crateName"
version = "0.1.0"
edition = "2024"
publish = false

[dependencies]
awfl-native-runtime = { path = "${relative.toString.replace('\\', '/')}" }
serde_json = "1"

[workspace]
"""
    Files.writeString(directory.resolve("Cargo.toml"), cargo, UTF_8)
    Files.writeString(directory.resolve("src/main.rs"), generated.source, UTF_8)
    Files.writeString(directory.resolve("manifest.json"), generated.manifest + "\n", UTF_8)
    generated
  }
}
