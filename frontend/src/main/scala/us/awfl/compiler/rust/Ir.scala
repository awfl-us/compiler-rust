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
  final case class Finish(nodeId: String, displayName: String, value: Expr) extends Instruction

  final case class Program(name: String, steps: Vector[Instruction], slots: Vector[String]) {
    val irVersion: Int = 1
    def effects: Vector[Effect] = steps.collect { case effect: Effect => effect }
  }
}
