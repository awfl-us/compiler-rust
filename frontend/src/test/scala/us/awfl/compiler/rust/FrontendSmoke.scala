package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import java.nio.file.Paths

/** A real constructed-DSL -> Scala normalization -> generated Rust fixture. */
object FrontendSmoke {
  def main(args: Array[String]): Unit = {
    val input = Value[Int]("input")
    val output = obj(Map(
      "score" -> Value[Int](input.cel + CelInteger(7)),
      "name" -> obj("native"),
      "valid" -> obj(true)
    ))
    val wf = Workflow((List.empty[Step[?, ?]], output), Some("math-fixture"))
    val ir = Normalize(wf)
    assert(ir.slots.isEmpty)
    assert(ir.steps.map(_.nodeId) == Vector("0.return"))
    assert(ir == Normalize(wf))
    val dir = Paths.get("../fixtures/native-smoke").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val generated = RustEmitter.write(ir, dir, runtime)
    assert(generated.source.contains("ops::add"))
    assert(generated.manifest.contains("math-fixture"))
    assert(generated == RustEmitter.emit(Normalize(wf)))

    val bad = Workflow((List.empty[Step[?, ?]], Value[String](CelConst("sys.now()"))))
    val diagnostic = try {
      Normalize(bad)
      throw new AssertionError("expected unsupported raw CEL diagnostic")
    } catch {
      case e: CompileError => e.getMessage
    }
    assert(diagnostic.contains("raw CEL"))

    val echo = Workflow((List.empty[Step[?, ?]], Value[Int]("input")))
    assert(Normalize(echo).steps.map(_.nodeId) == Vector("0.return"))
    assert(RustEmitter.emit(Normalize(echo)).source.contains("input.clone()"))

    // Constructed AST with an effect is recognized but not falsely compiled as pure Rust.
    val call = TypedCall[Map[String, String], String]("fetch", CallId.HttpPost, obj(Map("url" -> "https://example.org")))
    val effectFlow = Workflow((List(call), call.resultValue))
    val effect = Normalize(effectFlow)
    assert(effect.effects.map(_.id) == Vector(CallId.HttpPost))
    assert(effect.effects.map(_.resultSlot) == Vector("s0"))
    assert(effect == Normalize(effectFlow))
    val denied = try {
      RustEmitter.emit(effect)
      throw new AssertionError("should not silently issue external effects")
    } catch {
      case e: CompileError => e.getMessage
    }
    assert(denied.contains("continuation"))

    val invalidCall = Call[Map[String, String], String]("custom", "provider.unclassified", obj(Map("x" -> "y")))
    val diagnostic2 = try {
      Normalize(Workflow((List(invalidCall), invalidCall.resultValue)))
      throw new AssertionError("unknown legacy target should be rejected for Rust")
    } catch {
      case e: CompileError => e.getMessage
    }
    assert(diagnostic2.contains("provider.unclassified"))

    val scratch = java.nio.file.Files.createTempDirectory("awfl-native-frontend-")
    val artifacts = NativeCompiler.compile(List(wf), "batch", scratch, runtime)
    assert(artifacts.map(_.workflowName) == List("batch-math-fixture"))
    assert(artifacts.head.program == ir)

    println("Scala frontend native smoke passed")
  }
}
