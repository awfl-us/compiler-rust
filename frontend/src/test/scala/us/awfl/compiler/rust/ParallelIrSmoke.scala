package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.compiler.rust.Ir.*

/** Pure Scala normalization tests; server fork/join runtime is intentionally gated. */
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
    val blocked = try {
      RustEmitter.emit(program)
      throw new AssertionError("must not silently serialize effects")
    } catch { case e: CompileError => e.getMessage }
    assert(blocked.contains("durable parallel fork/join scheduler"))

    val forEach = ParallelFor[String, String]("forEach", us.awfl.dsl.ListValue[String](Resolver("input"))) { item =>
      List.empty[Step[?, ?]] -> item
    }
    val loopIR = Normalize(Workflow((List[Step[?, ?]](forEach), forEach.resultValue)))
    val fanout = loopIR.steps.head.asInstanceOf[ForkEach]
    assert(fanout.itemSlot != fanout.resultSlot)
    assert(fanout.iterable == Input)
    assert(fanout.result == SlotRef(fanout.itemSlot))
    assert(loopIR == Normalize(Workflow((List[Step[?, ?]](forEach), forEach.resultValue))))

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
