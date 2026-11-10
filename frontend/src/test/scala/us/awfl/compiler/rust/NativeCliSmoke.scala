package us.awfl.compiler.rust

import java.nio.file.{Files, Paths}
import us.awfl.compiler.rust.Ir.CompileError

object NativeCliSmoke {
  def main(args: Array[String]): Unit = {
    val temp = Files.createTempDirectory("awfl-native-cli-")
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val results = NativeMain.compile(
      Array("us.awfl.compiler.rust.NativeCliFixture", temp.toString, runtime.toString))
    assert(results.map(_.workflowName) == List("cli-fixture-increment"))
    val artifact = results.head.directory
    assert(Files.isRegularFile(artifact.resolve("src/main.rs")))
    assert(Files.readString(artifact.resolve("manifest.json")).contains("cli-fixture-increment"))
    assert(results.head.emitted.source.contains("ops::add"))
    val error = try {
      NativeMain.compile(Array("us.awfl.compiler.rust.NativeCliFixture", temp.toString, runtime.toString, "unexpected"))
      throw new AssertionError("expected CLI argument rejection")
    } catch {
      case e: IllegalArgumentException => e.getMessage
    }
    assert(error.contains("Usage:"))
    println("Native CLI loaded existing core.Workflow and emitted Rust")
  }
}
