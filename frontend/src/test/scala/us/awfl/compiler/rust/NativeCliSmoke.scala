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
    assert(results.head.emitted.manifest.contains("\"runtime_abi\":3"))
    assert(results.head.emitted.source.contains("protocol v2"))
    val error = try {
      NativeMain.compile(Array("us.awfl.compiler.rust.NativeCliFixture", temp.toString, runtime.toString, "unexpected"))
      throw new AssertionError("expected CLI argument rejection")
    } catch {
      case e: IllegalArgumentException => e.getMessage
    }
    assert(error.contains("Usage:"))
    val invalidAbi = try {
      NativeMain.compile(Array("us.awfl.compiler.rust.NativeCliFixture", "--abi=2"))
      throw new AssertionError("expected unsupported ABI rejection")
    } catch {
      case e: IllegalArgumentException => e.getMessage
    }
    assert(invalidAbi.contains("Usage:"))
    val selected = NativeMain.compile(Array(
      "us.awfl.compiler.rust.NativeMultiCliFixture", temp.toString,
      runtime.toString, "--only=multi-fixture-supported"))
    assert(selected.map(_.workflowName) == List("multi-fixture-supported"))
    assert(Files.isRegularFile(selected.head.directory.resolve("src/main.rs")))
    assert(!Files.exists(temp.resolve("multi-fixture-unsupported")))
    val missing = try {
      NativeMain.compile(Array("us.awfl.compiler.rust.NativeMultiCliFixture",
        temp.toString, runtime.toString, "--only=missing"))
      throw new AssertionError("expected unknown selection to fail")
    } catch { case e: CompileError => e.getMessage }
    assert(missing.contains("multi-fixture-supported"))
    assert(missing.contains("multi-fixture-unsupported"))
    val allVariants = try {
      NativeMain.compile(Array("us.awfl.compiler.rust.NativeMultiCliFixture",
        temp.toString, runtime.toString))
      throw new AssertionError("expected unsupported sibling to fail")
    } catch { case e: CompileError => e.getMessage }
    assert(allVariants.contains("provider.unsupported"))
    val emptySelector = try {
      NativeMain.compile(Array("us.awfl.compiler.rust.NativeCliFixture", "--only="))
      throw new AssertionError("expected empty selection to fail")
    } catch { case e: IllegalArgumentException => e.getMessage }
    assert(emptySelector.contains("Usage:"))
    println("Native CLI emitted only ABI 3 / protocol 2 Rust")
  }
}
