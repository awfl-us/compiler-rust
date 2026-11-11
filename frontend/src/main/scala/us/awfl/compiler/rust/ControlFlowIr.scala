package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*

/** Versioned, explicitly linked control-flow for compiled native executions.
  *
  * Every statement is a statically compiled Rust basic block. The runtime
  * persists a label after an Invoke, not a lexical replay frame. Compiled
  * branches and loop backedges therefore use the same resume mechanism.
  */
object ControlFlowIr {
  sealed trait Op {
    def targets: Vector[Int]
  }
  case class Bind(slot: String, value: Expr, next: Int) extends Op {
    def targets = Vector(next)
  }
  case class Test(condition: Expr, yes: Int, no: Int) extends Op {
    def targets = Vector(yes, no)
  }
  case class BeginRange(from: Expr, until: Expr, start: String, end: String,
                        ordinal: String, result: String, next: Int) extends Op {
    def targets = Vector(next)
  }
  case class CheckRange(start: String, end: String, ordinal: String, item: String,
                        body: Int, done: Int) extends Op {
    def targets = Vector(body, done)
  }
  case class AppendRange(value: Expr, result: String, ordinal: String, next: Int) extends Op {
    def targets = Vector(next)
  }
  case class BeginEach(iterable: Expr, values: String, ordinal: String,
                       result: String, next: Int) extends Op {
    def targets = Vector(next)
  }
  case class CheckEach(values: String, ordinal: String, item: String,
                       body: Int, done: Int) extends Op {
    def targets = Vector(body, done)
  }
  case class AppendEach(value: Expr, result: String, ordinal: String, next: Int) extends Op {
    def targets = Vector(next)
  }
  case class BeginFold(initial: Expr, iterable: Expr, values: String,
                       ordinal: String, result: String, next: Int) extends Op {
    def targets = Vector(next)
  }
  case class CheckFold(values: String, ordinal: String, item: String,
                       body: Int, done: Int) extends Op {
    def targets = Vector(body, done)
  }
  case class UpdateFold(value: Expr, result: String, ordinal: String, next: Int) extends Op {
    def targets = Vector(next)
  }
  /** Enclosing loops are listed outermost first for stable invocation IDs. */
  case class Invoke(effect: Effect, ancestors: Vector[String], next: Int) extends Op {
    def targets = Vector(next)
  }
  case class Done(value: Expr) extends Op {
    def targets = Vector.empty
  }

  case class Block(id: Int, sourceNode: String, op: Op)

  case class Plan(entry: Int, blocks: Vector[Block]) {
    val version: Int = 1

    /** Prune blocks rendered unreachable by unconditional early Return.
      * Renumber deterministically while preserving the order of surviving
      * labels; v2 PC values must never refer to dead compiler instructions.
      */
    def compact: Plan = {
      val reached = scala.collection.mutable.Set.empty[Int]
      def visit(pc: Int): Unit = {
        if (reached.add(pc)) blocks(pc).op.targets.foreach(visit)
      }
      visit(entry)
      val kept = reached.toVector.sorted
      val remap = kept.zipWithIndex.toMap
      def mapped(op: Op): Op = op match {
        case o: Bind => o.copy(next = remap(o.next))
        case o: Test => o.copy(yes = remap(o.yes), no = remap(o.no))
        case o: BeginRange => o.copy(next = remap(o.next))
        case o: CheckRange => o.copy(body = remap(o.body), done = remap(o.done))
        case o: AppendRange => o.copy(next = remap(o.next))
        case o: BeginEach => o.copy(next = remap(o.next))
        case o: CheckEach => o.copy(body = remap(o.body), done = remap(o.done))
        case o: AppendEach => o.copy(next = remap(o.next))
        case o: BeginFold => o.copy(next = remap(o.next))
        case o: CheckFold => o.copy(body = remap(o.body), done = remap(o.done))
        case o: UpdateFold => o.copy(next = remap(o.next))
        case o: Invoke => o.copy(next = remap(o.next))
        case d: Done => d
      }
      Plan(remap(entry), kept.map { old =>
        val block = blocks(old)
        Block(remap(old), block.sourceNode, mapped(block.op))
      }).validate()
    }

    def validate(): Plan = {
      if (blocks.isEmpty || entry < 0 || entry >= blocks.size)
        throw CompileError("control-flow", "invalid entry label")
      blocks.zipWithIndex.foreach { (block, index) =>
        if (block.id != index) throw CompileError("control-flow", "noncanonical CFG label")
        block.op.targets.foreach { to =>
          if (to < 0 || to >= blocks.size)
            throw CompileError(s"node=${block.sourceNode}",
              s"invalid CFG successor $to from label $index")
        }
      }
      val reachable = scala.collection.mutable.Set.empty[Int]
      def visit(pc: Int): Unit = {
        if (reachable.add(pc)) blocks(pc).op.targets.foreach(visit)
      }
      visit(entry)
      if (reachable.size != blocks.size)
        throw CompileError("control-flow",
          s"unreachable generated CFG labels: ${(blocks.indices.toSet -- reachable).toVector.sorted.mkString(",")}")
      if (!reachable.exists(pc => blocks(pc).op.isInstanceOf[Done]))
        throw CompileError("control-flow", "no reachable terminal result")
      this
    }
  }
}
