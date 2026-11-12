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
    var syntheticSlotOrdinal = program.slots.size
    def syntheticSlot(): String = {
      val slot = "s" + syntheticSlotOrdinal.toString
      syntheticSlotOrdinal += 1
      slot
    }
    def reserve(): Int = {
      val id = blocks.size
      blocks += None
      id
    }
    def set(label: Int, node: String, op: Op,
            handler: Option[Catch]): Unit = {
      if (blocks(label).nonEmpty)
        throw CompileError(s"node=$node", "CFG label assigned twice")
      blocks(label) = Some(Block(label, node, op, handler))
    }
    def place(node: String, op: Op, handler: Option[Catch]): Int = {
      val label = reserve()
      set(label, node, op, handler)
      label
    }
    def sequence(all: Vector[Instruction], next: Int,
                 loops: Vector[String], handler: Option[Catch]): Int =
      all.foldRight(next)((instruction, after) => lower(instruction, after, loops, handler))

    def lower(instruction: Instruction, next: Int, loops: Vector[String],
              handler: Option[Catch]): Int = {
      val node = instruction.nodeId
      instruction match {
        case e: Effect => place(node, Invoke(e, loops, next), handler)
        case c: Choose =>
          val fallback = place(node, Bind(c.resultSlot, MapValue(Vector.empty), next), handler)
          c.cases.foldRight(fallback) { (branch, otherwise) =>
            val end = place(node, Bind(c.resultSlot, branch.result, next), handler)
            val selected = sequence(branch.body, end, loops, handler)
            place(node, Test(branch.condition, selected, otherwise), handler)
          }
        case fork: Fork =>
          // The fork result is an ordered list, journaled by the server; bind
          // each declared child result into its parent-visible slot AFTER join.
          // Sibling branches compile to independently resumable CFG entry PCs.
          val resultSlot = syntheticSlot()
          val afterJoin = fork.branches.zipWithIndex.foldRight(next) {
            case ((branch, index), after) =>
              place(node, Bind(branch.joinedSlot,
                Index(SlotRef(resultSlot), IntValue(index.toLong)), after), handler)
          }
          val entries = fork.branches.map { branch =>
            val finish = place(branch.nodeId, Done(branch.result), None)
            val entry = sequence(branch.body, finish, Vector.empty, None)
            ForkLane(entry)
          }
          place(node, ForkInvoke(node, entries, None, resultSlot,
            loops, afterJoin), handler)
        case fork: ForkEach =>
          val finish = place(node, Done(fork.result), None)
          val entry = sequence(fork.body, finish, Vector.empty, None)
          place(node, ForkInvoke(node, Vector(ForkLane(entry, Some(fork.itemSlot))),
            Some(fork.iterable), fork.resultSlot, loops, next), handler)
        case r: RepeatRange =>
          val initLabel = reserve()
          val checkLabel = reserve()
          val cursor = s"__awfl_pc_${initLabel}_ordinal"
          val start = s"__awfl_pc_${initLabel}_from"
          val end = s"__awfl_pc_${initLabel}_until"
          val append = place(node, AppendRange(r.result, r.resultSlot, cursor, checkLabel), handler)
          val body = sequence(r.body, append, loops :+ cursor, handler)
          set(checkLabel, node, CheckRange(start, end, cursor, r.indexSlot, body, next), handler)
          set(initLabel, node, BeginRange(r.from, r.until, start, end,
            cursor, r.resultSlot, checkLabel), handler)
          initLabel
        case e: RepeatEach =>
          val initLabel = reserve()
          val checkLabel = reserve()
          val cursor = s"__awfl_pc_${initLabel}_ordinal"
          val values = s"__awfl_pc_${initLabel}_values"
          val append = place(node, AppendEach(e.result, e.resultSlot, cursor, checkLabel), handler)
          val body = sequence(e.body, append, loops :+ cursor, handler)
          set(checkLabel, node, CheckEach(values, cursor, e.itemSlot, body, next), handler)
          set(initLabel, node, BeginEach(e.iterable, values, cursor, e.resultSlot, checkLabel), handler)
          initLabel
        case f: ReduceEach =>
          val initLabel = reserve()
          val checkLabel = reserve()
          val cursor = s"__awfl_pc_${initLabel}_ordinal"
          val values = s"__awfl_pc_${initLabel}_values"
          val update = place(node, UpdateFold(f.result, f.resultSlot, cursor, checkLabel), handler)
          val body = sequence(f.body, update, loops :+ cursor, handler)
          set(checkLabel, node, CheckFold(values, cursor, f.itemSlot, body, next), handler)
          set(initLabel, node, BeginFold(f.initial, f.iterable, values,
            cursor, f.resultSlot, checkLabel), handler)
          initLabel
        case rescued: Rescue =>
          // The handler itself sees only the enclosing Try handler. A Raise
          // inside except must propagate to an outer rescue, never recatch.
          val recovery = place(node, Bind(rescued.resultSlot, rescued.recovery, next), handler)
          val rescueEntry = sequence(rescued.handler, recovery, loops, handler)
          val catchHere = Some(Catch(rescueEntry, rescued.errorSlot))
          val success = place(node, Bind(rescued.resultSlot, rescued.success, next), catchHere)
          sequence(rescued.attempt, success, loops, catchHere)
        case thrown: Throw => place(node, ThrowError(thrown.value), handler)
        case r: EarlyReturn => place(node, Done(r.value), handler)
        case f: Finish => place(node, Done(f.value), handler)
      }
    }

    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError(s"workflow=${program.name}",
        "workflow requires exactly one final Finish step")
    val end = lower(program.steps.last, 0, Vector.empty, None)
    val entry = sequence(program.steps.init, end, Vector.empty, None)
    Plan(entry, blocks.toVector.map(_.getOrElse(
      throw CompileError("control-flow", "unfilled CFG block")))).compact
  }
}
