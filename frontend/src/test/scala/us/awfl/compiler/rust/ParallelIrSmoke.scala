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
