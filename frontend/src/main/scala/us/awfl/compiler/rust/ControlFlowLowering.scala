package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.compiler.rust.ControlFlowIr.*
import scala.collection.mutable.ArrayBuffer

/** Compile structured control flow into a deterministic labeled graph.
  *
  * A Call's continuation points to its successor block. This removes the
  * need to rerun lexical branches or loops merely to find its resume site.
  */
object ControlFlowLowering {
  def apply(program: Program): Plan = {
    val blocks = ArrayBuffer.empty[Option[Block]]
    def reserve(): Int = {
      val id = blocks.size
      blocks += None
      id
    }
    def set(label: Int, node: String, op: Op): Unit = {
      if (blocks(label).nonEmpty)
        throw CompileError(s"node=$node", "CFG label assigned twice")
      blocks(label) = Some(Block(label, node, op))
    }
    def place(node: String, op: Op): Int = {
      val label = reserve()
      set(label, node, op)
      label
    }
    def sequence(all: Vector[Instruction], next: Int,
                 loops: Vector[String]): Int =
      all.foldRight(next)((instruction, after) => lower(instruction, after, loops))

    def lower(instruction: Instruction, next: Int, loops: Vector[String]): Int = {
      val node = instruction.nodeId
      instruction match {
        case e: Effect => place(node, Invoke(e, loops, next))
        case c: Choose =>
          val fallback = place(node, Bind(c.resultSlot, MapValue(Vector.empty), next))
          c.cases.foldRight(fallback) { (branch, otherwise) =>
            val end = place(node, Bind(c.resultSlot, branch.result, next))
            val selected = sequence(branch.body, end, loops)
            place(node, Test(branch.condition, selected, otherwise))
          }
        case r: RepeatRange =>
          val initLabel = reserve()
          val checkLabel = reserve()
          val cursor = s"__awfl_pc_${initLabel}_ordinal"
          val start = s"__awfl_pc_${initLabel}_from"
          val end = s"__awfl_pc_${initLabel}_until"
          val append = place(node, AppendRange(r.result, r.resultSlot, cursor, checkLabel))
          val body = sequence(r.body, append, loops :+ cursor)
          set(checkLabel, node, CheckRange(start, end, cursor, r.indexSlot, body, next))
          set(initLabel, node, BeginRange(r.from, r.until, start, end,
            cursor, r.resultSlot, checkLabel))
          initLabel
        case e: RepeatEach =>
          val initLabel = reserve()
          val checkLabel = reserve()
          val cursor = s"__awfl_pc_${initLabel}_ordinal"
          val values = s"__awfl_pc_${initLabel}_values"
          val append = place(node, AppendEach(e.result, e.resultSlot, cursor, checkLabel))
          val body = sequence(e.body, append, loops :+ cursor)
          set(checkLabel, node, CheckEach(values, cursor, e.itemSlot, body, next))
          set(initLabel, node, BeginEach(e.iterable, values, cursor, e.resultSlot, checkLabel))
          initLabel
        case f: ReduceEach =>
          val initLabel = reserve()
          val checkLabel = reserve()
          val cursor = s"__awfl_pc_${initLabel}_ordinal"
          val values = s"__awfl_pc_${initLabel}_values"
          val update = place(node, UpdateFold(f.result, f.resultSlot, cursor, checkLabel))
          val body = sequence(f.body, update, loops :+ cursor)
          set(checkLabel, node, CheckFold(values, cursor, f.itemSlot, body, next))
          set(initLabel, node, BeginFold(f.initial, f.iterable, values,
            cursor, f.resultSlot, checkLabel))
          initLabel
        case r: EarlyReturn => place(node, Done(r.value))
        case f: Finish => place(node, Done(f.value))
      }
    }

    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError(s"workflow=${program.name}",
        "workflow requires exactly one final Finish step")
    val end = lower(program.steps.last, 0, Vector.empty)
    val entry = sequence(program.steps.init, end, Vector.empty)
    Plan(entry, blocks.toVector.map(_.getOrElse(
      throw CompileError("control-flow", "unfilled CFG block")))).compact
  }
}
