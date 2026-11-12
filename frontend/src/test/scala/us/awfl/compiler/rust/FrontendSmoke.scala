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
      "score" -> Value[Int](input.cel + CelConst("7")),
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
    assert(generated.manifest.contains("\"runtime_abi\":3"))
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
    // Production HTTP helpers use the original dotted input-env reference.
    val dotted = Workflow((List.empty[Step[?, ?]],
      Value[String]("input.env.BASE_URL")))
    val dottedRust = RustEmitter.emit(Normalize(dotted)).source
    assert(dottedRust.contains("ops::field"))
    assert(dottedRust.contains("\"env\""))
    assert(dottedRust.contains("\"BASE_URL\""))


    // Effects compile to a versioned suspension request; no HTTP runs locally.
    val call = Call[Map[String, String], String]("fetch", CallOperations.HttpPost, obj(Map("url" -> "https://example.org")))
    val effectFlow = Workflow((List(call), call.resultValue))
    val effect = Normalize(effectFlow)
    assert(effect.effects.map(_.id.wireId) == Vector(CallOperations.HttpPost))
    assert(effect.effects.map(_.resultSlot) == Vector("s0"))
    assert(effect == Normalize(effectFlow))
    val protocol = RustEmitter.write(effect,
      Paths.get("../fixtures/native-effect").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)
    assert(protocol.source.contains("Outcome::Suspended"))
    assert(protocol.manifest.contains("http.post"))
    assert(protocol.manifest.contains("\"runtime_abi\":3"))

    // Existing FlatMap wrappers must neither drop nor dispatch their
    // underlying Call twice. The projection is a pure result expression.
    val projected = FlatMap[String, String, Value[String]]("project",
      call, str(call.resultValue.cel + CelStr("!")))
    val projectedFlow = Workflow((List[Step[?, ?]](projected),
      projected.resultValue), Some("flatmap-projection"))
    val projectedIr = Normalize(projectedFlow)
    assert(projectedIr.effects.map(_.id) == Vector(CallId.HttpPost))
    assert(projectedIr.effects.map(_.resultSlot) == Vector("s0"))
    assert(projectedIr.effects.map(_.nodeId) == Vector("0.0.0"))
    assert(RustEmitter.emit(projectedIr).source.contains("ops::add"))
    assert(projectedIr == Normalize(projectedFlow))
    RustEmitter.write(projectedIr,
      Paths.get("../fixtures/native-flatmap").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)

    // The existing CEL/Call constructors work without a parallel typed AST.
    val known = Value[Int](CelFunc(CelFunctions.Length, CelConst("input")))
    assert(RustEmitter.emit(Normalize(Workflow((List.empty[Step[?, ?]], known))))
      .source.contains("ops::length"))
    val unknownCall = Call[Map[String, String], String]("custom",
      "provider.unclassified", obj(Map("x" -> "y")))
    val callDiagnostic = try {
      Normalize(Workflow((List(unknownCall), unknownCall.resultValue)))
      throw new AssertionError("expected unknown Call rejection")
    } catch { case e: CompileError => e.getMessage }
    assert(callDiagnostic.contains("provider.unclassified"))
    assert(callDiagnostic.contains("CallOperations"))

    // The published DSL's own len(...) helper currently emits literal
    // "len". This exact internal spelling must compile without changing
    // public DSL code, but unrelated textual CEL names still fail closed.
    val dslLen = Value[Int](CelFunc("len", CelConst("input")))
    val dslLenSource = RustEmitter.emit(
      Normalize(Workflow((List.empty[Step[?, ?]], dslLen)))).source
    assert(dslLenSource.contains("ops::length"))
    val functionDiagnostic = try {
      Normalize(Workflow((List.empty[Step[?, ?]],
        Value[Int](CelFunc("provider.unclassified", CelConst("input"))))))
      throw new AssertionError("expected unknown CEL function rejection")
    } catch { case e: CompileError => e.getMessage }
    assert(functionDiagnostic.contains("CelFunctions"))
    assert(functionDiagnostic.contains("provider.unclassified"))

    val operatorDiagnostic = try {
      Normalize(Workflow((List.empty[Step[?, ?]],
        Value[Int](CelOp(CelConst("1"), "**", CelConst("2"))))))
      throw new AssertionError("expected unknown CEL operator rejection")
    } catch { case e: CompileError => e.getMessage }
    assert(operatorDiagnostic.contains("unknown CEL operator '**'"))

    // Existing CelOp syntax remains valid for native Rust generation.
    assert(RustEmitter.emit(Normalize(Workflow((List.empty[Step[?, ?]],
      Value[Int](CelOp(CelConst("1"), "+", CelConst("2")))))))
      .source.contains("ops::add"))

    val scratch = java.nio.file.Files.createTempDirectory("awfl-native-frontend-")
    val artifacts = NativeCompiler.compile(List(wf), "batch", scratch, runtime)
    assert(artifacts.map(_.workflowName) == List("batch-math-fixture"))
    assert(artifacts.head.program.name == "batch-math-fixture")
    assert(artifacts.head.program.copy(name = ir.name) == ir)
    assert(artifacts.head.emitted.manifest.contains("\"runtime_abi\":3"))

    println("Scala frontend native smoke passed")
  }
}
