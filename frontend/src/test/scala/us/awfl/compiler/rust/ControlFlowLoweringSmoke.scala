package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.compiler.rust.ControlFlowIr.*

/** Deterministic CFG, nested control-flow and liveness regression checks. */
object ControlFlowLoweringSmoke {
  def main(args: Array[String]): Unit = {
    val first = Effect("0.0.case0.each.0", "first", CallId.HttpPost,
      MapValue(Vector("index" -> SlotRef("s1"))), "s2")
    val second = Effect("0.0.case0.each.1", "second", CallId.HttpPost,
      SlotRef("s2"), "s3")
    val inner = RepeatEach("0.0.case0.each", "for", ListValue(Vector(
      IntValue(1), IntValue(2))), "s1", Vector(first, second),
      SlotRef("s3"), "s4")
    val choice = Choose("0.0", "branch", Vector(
      ChoiceCase(BoolValue(true), Vector(inner), SlotRef("s4")),
      ChoiceCase(BoolValue(false), Vector.empty, StringValue("other"))
    ), "s5")
    val program = Program("nested-test",
      Vector(choice, Finish("0.return", "return", SlotRef("s5"))),
      Vector("s1", "s2", "s3", "s4", "s5"))
    val plan = ControlFlowLowering(program)
    assert(plan == ControlFlowLowering(program), "lowering must be deterministic")
    assert(plan.entry >= 0 && plan.blocks.size > 8)
    assert(plan.blocks.map(_.id) == plan.blocks.indices.toVector)
    assert(plan.validate() == plan)
    val calls = plan.blocks.collect { case Block(id, _, invoke: Invoke) => id -> invoke }
    assert(calls.size == 2)
    assert(calls.map(_._2.ancestors).forall(_.size == 1))
    assert(calls.map(_._2.effect.nodeId).distinct.size == 2)
    calls.foreach { (label, invoke) =>
      assert(invoke.next != label)
      assert(plan.blocks(invoke.next).id == invoke.next)
    }
    assert(plan.blocks.exists(_.op.isInstanceOf[BeginEach]))
    assert(plan.blocks.exists(_.op.isInstanceOf[CheckEach]))
    assert(plan.blocks.exists(_.op.isInstanceOf[AppendEach]))

    val analysis = ContinuationAnalysis(plan)
    val firstCall = calls.find(_._2.effect.resultSlot == "s2").get
    assert(analysis.liveIn(firstCall._1).contains("s1"))
    assert(analysis.liveIn(firstCall._1).exists(_.endsWith("_ordinal")))
    assert(analysis.liveIn(firstCall._1).exists(_.endsWith("_values")))
    val secondCall = calls.find(_._2.effect.resultSlot == "s3").get
    assert(analysis.liveIn(secondCall._1).contains("s2"))

    val range = RepeatRange("0.0", "range", IntValue(-2), IntValue(2),
      "s0", Vector(first), SlotRef("s2"), "s4")
    val rangePlan = ControlFlowLowering(Program("range",
      Vector(range, Finish("0.return", "return", SlotRef("s4"))), Vector.empty))
    assert(rangePlan.blocks.exists(_.op.isInstanceOf[BeginRange]))
    assert(rangePlan.blocks.exists(_.op.isInstanceOf[AppendRange]))
    assert(rangePlan.blocks.exists(_.op.isInstanceOf[CheckRange]))

    val fold = ReduceEach("0.0", "fold", IntValue(0),
      ListValue(Vector(IntValue(1), IntValue(2))), "s0",
      Vector(first), SlotRef("s2"), "s3")
    val foldPlan = ControlFlowLowering(Program("fold",
      Vector(fold, Finish("0.return", "return", SlotRef("s3"))), Vector.empty))
    assert(foldPlan.blocks.exists(_.op.isInstanceOf[BeginFold]))
    assert(foldPlan.blocks.exists(_.op.isInstanceOf[UpdateFold]))
    assert(foldPlan.blocks.exists(_.op.isInstanceOf[CheckFold]))

    // Unconditional early returns make the lexical Finish unreachable;
    // canonicalization removes it instead of leaving a resumable dead PC.
    val returned = ControlFlowLowering(Program("return", Vector(
      EarlyReturn("0.0", "return", IntValue(17)),
      Finish("0.return", "return", IntValue(99))
    ), Vector.empty))
    assert(returned.blocks.size == 1)
    assert(returned.blocks.head.op == Done(IntValue(17)))

    val invalid = Plan(0, Vector(
      Block(0, "bad", Bind("s0", IntValue(1), 999))))
    assert(scala.util.Try(invalid.validate()).failed.get.getMessage.contains("successor"))
    println("Control-flow lowering, liveness and verifier checks passed")
  }
}
