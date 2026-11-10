package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.compiler.rust.Ir.*
import scala.collection.mutable

/** Normalize constructed AWFL DSL values, not serialized Google Workflow YAML.
  * Allocate structural node and slot IDs in traversal order; legacy hash-derived
  * result references are only resolved transiently within this compiler invocation.
  */
object Normalize {
  def apply(workflow: us.awfl.dsl.Workflow[?], workflowName: String = "workflow"): Program = {
    val symbols = mutable.Map.empty[String, String]
    val slots = mutable.ArrayBuffer.empty[String]
    val output = mutable.ArrayBuffer.empty[Instruction]

    def fail(where: String, why: String): Nothing = throw CompileError(where, why)

    def obj(value: Any, where: String): Expr = value match {
      case null => NullValue
      case v: String => StringValue(v)
      case v: Boolean => BoolValue(v)
      case v: Byte => IntValue(v.toLong)
      case v: Short => IntValue(v.toLong)
      case v: Int => IntValue(v.toLong)
      case v: Long => IntValue(v)
      case v: Float if v.isFinite => FloatValue(v.toDouble)
      case v: Double if v.isFinite => FloatValue(v)
      case v: Float => fail(where, s"non-finite float $v")
      case v: Double => fail(where, s"non-finite float $v")
      case v: scala.List[?] => Ir.ListValue(v.zipWithIndex.map((x, i) => obj(x, s"$where[$i]")).toVector)
      case v: Seq[?] => Ir.ListValue(v.zipWithIndex.map((x, i) => obj(x, s"$where[$i]")).toVector)
      case v: Map[?, ?] =>
        val fields = v.toVector.map {
          case (key: String, item) => key -> obj(item, s"$where.$key")
          case (key, _) => fail(where, s"expected String map key, got $key")
        }.sortBy(_._1)
        MapValue(fields)
      case v: Option[?] => v.map(obj(_, where)).getOrElse(NullValue)
      case v: BaseValue[?] => valueExpr(v, where)
      case v: Product =>
        val fields = v.productElementNames.zip(v.productIterator).toVector
          .map((key, item) => key -> obj(item, s"$where.$key"))
        MapValue(fields)
      case v => fail(where, s"unsupported literal value: ${v.getClass.getName}")
    }

    def raw(rawValue: String, where: String): Expr = rawValue match {
      case "input" => Input
      case "null" => NullValue
      case "true" => BoolValue(true)
      case "false" => BoolValue(false)
      case name if symbols.contains(name) => SlotRef(symbols(name))
      case text if text.matches("-?(0|[1-9][0-9]*)") =>
        text.toLongOption.map(IntValue(_))
          .getOrElse(fail(where, s"integer outside signed 64-bit range: $text"))
      case text if text.matches("-?(0|[1-9][0-9]*)\\.[0-9]+([eE][+-]?[0-9]+)?") =>
        text.toDoubleOption.filter(_.isFinite).map(FloatValue(_))
          .getOrElse(fail(where, s"non-finite/invalid floating-point literal: $text"))
      case other => fail(where, s"unsupported raw CEL/reference '$other'; use typed literals, globals and operations")
    }

    def cel(node: Cel, where: String): Expr = node match {
      case CelConst(value) => raw(value, where)
      case CelStr(value) => StringValue(value)
      case CelString(value) => StringValue(value)
      case CelInteger(value) => IntValue(value)
      case CelBoolean(value) => BoolValue(value)
      case CelNull => NullValue
      case CelList(xs) => Ir.ListValue(xs.zipWithIndex.map((x, i) => cel(x, s"$where[$i]")).toVector)
      case CelValue(value) => valueExpr(value, where)
      case CelGlobal(id) => Global(id)
      case CelFunction(id, params) => function(id, params, where)
      case CelFunc(name, params) =>
        val id = OperationRegistry.resolveLegacyFunction(name, where)
          .fold(msg => fail(where, msg), identity)
        function(id, params, where)
      case CelBinary(left, id, right) => Binary(cel(left, where), id, cel(right, where))
      case CelUnary(id, value) => Unary(id, cel(value, where))
      case CelOp(left, name, right) =>
        val id = OperatorId.fromLegacy(name, 2)
          .getOrElse(fail(where, s"unknown legacy binary operator '$name'"))
        Binary(cel(left, where), id, cel(right, where))
      case CelPath(path) =>
        path match {
          case Nil => fail(where, "empty reference path")
          case head :: tail =>
            tail.foldLeft(cel(head, where)) {
              case (expr, CelConst(field)) => Field(expr, field)
              case (_, other) => fail(where, s"unsupported field path member $other")
            }
        }
      case CelAt(list, index) => Index(valueExpr(list, where), cel(index, where))
    }

    def function(id: FunctionId, params: List[Cel], where: String): Expr = {
      val signature = OperationRegistry.functions(id)
      if (!signature.acceptsArity(params.size))
        fail(where, s"${id.wireId} expects ${signature.minArity}-${signature.maxArity} arguments, got ${params.size}")
      Function(id, params.zipWithIndex.map((x, i) => cel(x, s"$where.arg$i")).toVector)
    }

    def valueExpr(value: BaseValue[?], where: String): Expr = value match {
      case resolved: Resolved[?] => cel(resolved.resolver.path, where)
      case Obj(value) => obj(value, where)
    }

    def register(result: Resolved[?], where: String): String = {
      val path = result.resolver.path.path
      val name = path match {
        case CelConst(name) :: Nil => name
        case _ => fail(where, s"unresolvable step result symbol: $path")
      }
      if (symbols.contains(name)) fail(where, s"colliding step result symbol: $name")
      val slot = s"s${slots.size}"
      symbols(name) = slot
      slots += slot
      slot
    }

    def steps(all: List[Step[?, ?]], scope: String): Unit = {
      all.zipWithIndex.foreach { (step, i) =>
        val nodeId = s"$scope.$i"
        val where = s"workflow=$workflowName node=$nodeId step='${step.name}'"
        step match {
          case call: Call[?, ?] =>
            val id = OperationRegistry.resolveLegacyCall(call.call, where)
              .fold(msg => fail(where, msg), identity)
            val args = valueExpr(call.args, s"$where.args")
            val slot = register(call.resultValue, where)
            output += Effect(nodeId, step.name, id, args, slot)
          case call: TypedCall[?, ?] =>
            val args = valueExpr(call.args, s"$where.args")
            val slot = register(call.resultValue, where)
            output += Effect(nodeId, step.name, call.id, args, slot)
          case ret: Return[?] =>
            output += Finish(nodeId, step.name, valueExpr(ret.value, where))
          case block: Block[?, ?] =>
            steps(block.steps, nodeId)
          case other =>
            fail(where, s"${other.getClass.getSimpleName} requires native lowering before Rust emission; cannot silently interpret YAML")
        }
      }
    }

    steps(workflow.steps._1, "0")
    output += Finish("0.return", "return", valueExpr(workflow.steps._2, s"workflow=$workflowName return"))
    Program(workflow.name.getOrElse(workflowName), output.toVector, slots.toVector)
  }
}
