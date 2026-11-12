package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.compiler.rust.ControlFlowIr.*

/** Standard backward fixed-point slot liveness over the compiled CFG.
  * Successor edges include loop backedges, so nested loops and branches
  * require no special case in the analysis.
  */
object ContinuationAnalysis {
  def references(expr: Expr): Set[String] = expr match {
    case SlotRef(slot) => Set(slot)
    case ListValue(xs) => xs.iterator.flatMap(references).toSet
    case MapValue(xs) => xs.iterator.flatMap((_, value) => references(value)).toSet
    case Field(value, _) => references(value)
    case Index(value, index) => references(value) ++ references(index)
    case Binary(left, _, right) => references(left) ++ references(right)
    case Unary(_, value) => references(value)
    case Function(_, arguments) => arguments.iterator.flatMap(references).toSet
    case _ => Set.empty
  }

  private def uses(op: Op): Set[String] = op match {
    case Bind(_, value, _) => references(value)
    case Test(value, _, _) => references(value)
    case BeginRange(from, until, _, _, _, _, _) =>
      references(from) ++ references(until)
    case CheckRange(from, until, ordinal, _, _, _) => Set(from, until, ordinal)
    case AppendRange(value, result, ordinal, _) =>
      references(value) ++ Set(result, ordinal)
    case BeginEach(iterable, _, _, _, _) => references(iterable)
    case CheckEach(values, ordinal, _, _, _) => Set(values, ordinal)
    case AppendEach(value, result, ordinal, _) =>
      references(value) ++ Set(result, ordinal)
    case BeginFold(initial, iterable, _, _, _, _) =>
      references(initial) ++ references(iterable)
    case CheckFold(values, ordinal, _, _, _) => Set(values, ordinal)
    case UpdateFold(value, result, ordinal, _) =>
      references(value) ++ Set(result, ordinal)
    case Invoke(effect, ancestors, _) => references(effect.arguments) ++ ancestors
    case ForkInvoke(_, _, iterable, _, ancestors, _) =>
      iterable.map(references).getOrElse(Set.empty) ++ ancestors
    case Done(value) => references(value)
    case ThrowError(value) => references(value)
  }

  private def defines(op: Op): Set[String] = op match {
    case Bind(slot, _, _) => Set(slot)
    case BeginRange(_, _, from, until, ordinal, result, _) =>
      Set(from, until, ordinal, result)
    case CheckRange(_, _, _, item, _, _) => Set(item)
    case AppendRange(_, result, ordinal, _) => Set(result, ordinal)
    case BeginEach(_, values, ordinal, result, _) => Set(values, ordinal, result)
    case CheckEach(_, _, item, _, _) => Set(item)
    case AppendEach(_, result, ordinal, _) => Set(result, ordinal)
    case BeginFold(_, _, values, ordinal, result, _) =>
      Set(values, ordinal, result)
    case CheckFold(_, _, item, _, _) => Set(item)
    case UpdateFold(_, result, ordinal, _) => Set(result, ordinal)
    case Invoke(effect, _, _) => Set(effect.resultSlot)
    case ForkInvoke(_, _, _, resultSlot, _, _) => Set(resultSlot)
    case _ => Set.empty
  }

  case class Result(liveIn: Vector[Set[String]], liveOut: Vector[Set[String]]) {
    def resumeSlots(label: Int): Set[String] = liveIn(label)
  }

  def apply(plan: Plan): Result = {
    plan.validate()
    val in = Array.fill(plan.blocks.size)(Set.empty[String])
    val out = Array.fill(plan.blocks.size)(Set.empty[String])
    var changed = true
    while (changed) {
      changed = false
      for (pc <- plan.blocks.indices.reverse) {
        val op = plan.blocks(pc).op
        val outgoing = plan.blocks(pc).successors.iterator.flatMap(in(_)).toSet
        val incoming = uses(op) ++ (outgoing -- defines(op))
        if (outgoing != out(pc) || incoming != in(pc)) {
          out(pc) = outgoing
          in(pc) = incoming
          changed = true
        }
      }
    }
    Result(in.toVector, out.toVector)
  }
}
