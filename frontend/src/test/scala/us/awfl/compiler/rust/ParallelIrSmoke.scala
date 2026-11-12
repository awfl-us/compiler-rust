package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.compiler.rust.Ir.*

/** Compile real Scala Parallel and ParallelFor ASTs to a durable native fork ABI. */
object ParallelIrSmoke {
  def main(args: Array[String]): Unit = {
    val a = Call[String, String]("sameName", CallOperations.HttpPost, obj("first"))
    val b = Call[String, String]("sameName", CallOperations.HttpPost, obj("second"))
    val paired = Parallel("fork", List(a, b))
    val result = obj(Map("a" -> a.resultValue, "b" -> b.resultValue))
    val program = Normalize(Workflow((List[Step[?, ?]](paired), result)))
    val fork = program.steps.head.asInstanceOf[Fork]
    assert(fork.branches.size == 2)
    assert(fork.branches.map(_.joinedSlot).distinct.size == 2)
    assert(fork.branches.map(_.nodeId) == Vector("0.0.branch0", "0.0.branch1"))
    assert(fork.branches.forall(_.body.head.isInstanceOf[Effect]))
    assert(program.effects.map(_.nodeId).distinct.size == 2)
    assert(program == Normalize(Workflow((List[Step[?, ?]](paired), result))))
    val generated = RustEmitter.emit(program)
    assert(generated.source.contains("native.parallel"))
    assert(generated.source.contains("pc_"))
    assert(generated.manifest.contains("parallel_version"))
    assert(generated.manifest.contains("native.parallel"))
    RustEmitter.write(program,
      java.nio.file.Paths.get("../fixtures/native-parallel-branches").toAbsolutePath.normalize,
      java.nio.file.Paths.get("../runtime").toAbsolutePath.normalize)

    val forEach = ParallelFor[String, String]("forEach", us.awfl.dsl.ListValue[String](Resolver("input"))) { item =>
      List.empty[Step[?, ?]] -> item
    }
    val loopIR = Normalize(Workflow((List[Step[?, ?]](forEach), forEach.resultValue)))
    val fanout = loopIR.steps.head.asInstanceOf[ForkEach]
    assert(fanout.itemSlot != fanout.resultSlot)
    assert(fanout.iterable == Input)
    assert(fanout.result == SlotRef(fanout.itemSlot))
    assert(loopIR == Normalize(Workflow((List[Step[?, ?]](forEach), forEach.resultValue))))
    val loopGenerated = RustEmitter.emit(loopIR)
    assert(loopGenerated.source.contains("native.parallel"))
    RustEmitter.write(loopIR,
      java.nio.file.Paths.get("../fixtures/native-parallel-for").toAbsolutePath.normalize,
      java.nio.file.Paths.get("../runtime").toAbsolutePath.normalize)

    // A confirmed failure in a child becomes a journaled fork failure and
    // follows the parent Try handler. An unhandled Raise fails its root.
    val failure = Raise("branch-failed",
      obj(Error(str(CelStr("definitive branch failure")), OptValue[Int](CelConst("null")))))
    val failingFork = Parallel("failing-fork", List(failure))
    val wrapped = Try("catch-fork",
      List[Step[?, ?]](failingFork) -> str(CelStr("unreachable")),
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    val withCatch = Normalize(Workflow(
      (List[Step[?, ?]](wrapped), wrapped.resultValue),
      Some("native-parallel-caught-failure")))
    val caught = RustEmitter.write(withCatch,
      java.nio.file.Paths.get("../fixtures/native-parallel-caught").toAbsolutePath.normalize,
      java.nio.file.Paths.get("../runtime").toAbsolutePath.normalize)
    assert(caught.source.contains("native.parallel"))
    assert(caught.source.contains("error_slot"))
    val withoutCatch = Normalize(Workflow(
      (List[Step[?, ?]](failingFork), str(CelStr("unreachable"))),
      Some("native-parallel-uncaught-failure")))
    RustEmitter.write(withoutCatch,
      java.nio.file.Paths.get("../fixtures/native-parallel-uncaught").toAbsolutePath.normalize,
      java.nio.file.Paths.get("../runtime").toAbsolutePath.normalize)

    // Three genuine nested Scala Parallel levels. The same source step label
    // is reused deliberately: compiler-private structural IDs, not labels,
    // must keep every child effect and fork generation separate.
    val deepA = Call[String, String]("sameName", CallOperations.HttpPost, obj("deep-a"))
    val deepB = Call[String, String]("sameName", CallOperations.HttpPost, obj("deep-b"))
    val nestedDeep = Parallel("nestedDeep", List(deepA, deepB))
    val middleCall = Call[String, String]("sameName", CallOperations.HttpPost, obj("middle"))
    val nestedMiddle = Parallel("nestedMiddle", List(nestedDeep, middleCall))
    val outerCall = Call[String, String]("sameName", CallOperations.HttpPost, obj("outer"))
    val nestedOuter = Parallel("nestedOuter", List(nestedMiddle, outerCall))
    val nestedProgram = Normalize(Workflow((
      List[Step[?, ?]](nestedOuter), str(CelStr("nested-complete"))),
      Some("native-parallel-nested")))
    val nestedEffects = nestedProgram.effects.map(_.nodeId)
    assert(nestedEffects.size == 4 && nestedEffects.distinct.size == 4)
    val nestedGenerated = RustEmitter.write(nestedProgram,
      java.nio.file.Paths.get("../fixtures/native-parallel-nested").toAbsolutePath.normalize,
      java.nio.file.Paths.get("../runtime").toAbsolutePath.normalize)
    assert(nestedGenerated.source.contains("native.parallel"))
    assert(nestedGenerated.manifest.contains("parallel_version"))

    val sibling = Call[Map[String, Value[String]], String]("sibling", CallOperations.HttpPost,
      obj(Map("earlier" -> a.resultValue)))
    val dependent = Parallel("invalid", List(a, sibling))
    val diagnostic = try {
      Normalize(Workflow((List[Step[?, ?]](dependent), sibling.resultValue)))
      throw new AssertionError("cross-branch dependency should fail closed")
    } catch { case e: CompileError => e.getMessage }
    assert(diagnostic.contains("unsupported raw CEL/reference"), diagnostic)
    println("PARALLEL_IR_SMOKE_PASS")
  }
}
