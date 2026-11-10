package us.awfl.compiler.rust

import us.awfl.dsl.{CallId, FunctionId, GlobalId, OperatorId}

object Ir {
  final case class CompileError(location: String, explanation: String)
      extends RuntimeException(s"$location: $explanation")

  sealed trait Expr
  case object NullValue extends Expr
  final case class BoolValue(value: Boolean) extends Expr
  final case class IntValue(value: Long) extends Expr
  final case class FloatValue(value: Double) extends Expr
  final case class StringValue(value: String) extends Expr
  final case class ListValue(values: Vector[Expr]) extends Expr
  final case class MapValue(fields: Vector[(String, Expr)]) extends Expr
  case object Input extends Expr
  final case class SlotRef(id: String) extends Expr
  final case class Field(value: Expr, field: String) extends Expr
  final case class Index(value: Expr, index: Expr) extends Expr
  final case class Global(id: GlobalId) extends Expr
  final case class Binary(left: Expr, id: OperatorId, right: Expr) extends Expr
  final case class Unary(id: OperatorId, operand: Expr) extends Expr
  final case class Function(id: FunctionId, arguments: Vector[Expr]) extends Expr

  sealed trait Instruction {
    def nodeId: String
    def displayName: String
  }
  final case class Effect(nodeId: String, displayName: String, id: CallId,
                          arguments: Expr, resultSlot: String) extends Instruction
  final case class ChoiceCase(condition: Expr, body: Vector[Instruction], result: Expr)
  /** Ordered first-match branch. If no branch matches, Google YAML initializes
    * the result to the empty object, so that is the native default as well.
    */
  final case class Choose(nodeId: String, displayName: String, cases: Vector[ChoiceCase],
                          resultSlot: String) extends Instruction
  /** End is exclusive, as with the AWFL DSL ForRange(from, to) builder. */
  final case class RepeatRange(nodeId: String, displayName: String, from: Expr, until: Expr,
                               indexSlot: String, body: Vector[Instruction],
                               result: Expr, resultSlot: String) extends Instruction
  final case class RepeatEach(nodeId: String, displayName: String, iterable: Expr,
                              itemSlot: String, body: Vector[Instruction],
                              result: Expr, resultSlot: String) extends Instruction
  /** Sequential left fold; accumulator is visible to its own body and updated
    * only after that iteration's child steps finish.
    */
  final case class ReduceEach(nodeId: String, displayName: String, initial: Expr,
                              iterable: Expr, itemSlot: String,
                              body: Vector[Instruction], result: Expr,
                              resultSlot: String) extends Instruction
  /** Explicit DSL Return stops the entire workflow, including nested control flow. */
  final case class EarlyReturn(nodeId: String, displayName: String, value: Expr) extends Instruction
  final case class Finish(nodeId: String, displayName: String, value: Expr) extends Instruction

  final case class Program(name: String, steps: Vector[Instruction], slots: Vector[String]) {
    val irVersion: Int = 2
    def nodeIds: Vector[String] = {
      def walk(all: Vector[Instruction]): Vector[String] = all.flatMap {
        case choice: Choose => Vector(choice.nodeId) ++ choice.cases.flatMap(c => walk(c.body))
        case loop: RepeatRange => Vector(loop.nodeId) ++ walk(loop.body)
        case loop: RepeatEach => Vector(loop.nodeId) ++ walk(loop.body)
        case loop: ReduceEach => Vector(loop.nodeId) ++ walk(loop.body)
        case other => Vector(other.nodeId)
      }
      walk(steps)
    }
    def effects: Vector[Effect] = {
      def walk(all: Vector[Instruction]): Vector[Effect] = all.flatMap {
        case effect: Effect => Vector(effect)
        case choice: Choose => choice.cases.flatMap(c => walk(c.body))
        case loop: RepeatRange => walk(loop.body)
        case loop: RepeatEach => walk(loop.body)
        case loop: ReduceEach => walk(loop.body)
        case _ => Vector.empty
      }
      walk(steps)
    }
  }
}
