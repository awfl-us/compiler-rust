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

    /** The existing DSL writes literal lists as JSON-like text in CelConst.
      * Parse only strict data literals, not arbitrary CEL syntax; all dynamic
      * references and operations must remain structured Cel nodes.
      */
    def collectionLiteral(json: io.circe.Json, where: String): Expr =
      json.fold[Expr](
        NullValue,
        BoolValue.apply,
        number => {
          val text = number.toString
          if (text.matches("-?(0|[1-9][0-9]*)"))
            text.toLongOption.map(IntValue(_)).getOrElse(
              fail(where, s"integer outside signed 64-bit range: $text"))
          else
            text.toDoubleOption.filter(_.isFinite).map(FloatValue(_)).getOrElse(
              fail(where, s"non-finite/invalid floating-point literal: $text"))
        },
        StringValue.apply,
        values => Ir.ListValue(values.zipWithIndex.map { (value, i) =>
          collectionLiteral(value, s"$where[$i]")
        }),
        fields => MapValue(fields.keys.toVector.sorted.map { key =>
          key -> collectionLiteral(fields(key).getOrElse(
            fail(where, s"invalid literal map key '$key'")), s"$where.$key")
        })
      )

    def raw(rawValue: String, where: String): Expr = rawValue match {
      case "input" => Input
      case "null" => NullValue
      case "true" => BoolValue(true)
      case "false" => BoolValue(false)
      case name if symbols.contains(name) => SlotRef(symbols(name))
      // Existing AWFL `Value("input.env")` references are dotted paths in
      // CelConst, not a different DSL AST. Resolve only known input/step
      // symbols, and validate path segments to avoid interpreting CEL code.
      case path if path.contains('.') && {
        val segments = path.split("\\.").toVector
        segments.size > 1 &&
          (segments.head == "input" || symbols.contains(segments.head)) &&
          segments.forall(_.matches("[A-Za-z_][A-Za-z0-9_]*"))
      } =>
        val segments = path.split("\\.").toVector
        val root: Expr = if (segments.head == "input") Input
                         else SlotRef(symbols(segments.head))
        segments.tail.foldLeft(root)((value, field) => Field(value, field))
      // List/Map literal strings produced by the original DSL List.cel and
      // ListValue.empty. Do not evaluate raw identifiers or CEL functions.
      case text if text.trim.startsWith("[") || text.trim.startsWith("{") =>
        io.circe.parser.parse(text).fold(
          err => fail(where, s"invalid literal collection (not JSON data): ${err.message}"),
          json => collectionLiteral(json, where)
        )
      case text if text.matches("-?(0|[1-9][0-9]*)") =>
        text.toLongOption.map(IntValue(_))
          .getOrElse(fail(where, s"integer outside signed 64-bit range: $text"))
      case text if text.matches("-?(0|[1-9][0-9]*)\\.[0-9]+([eE][+-]?[0-9]+)?") =>
        text.toDoubleOption.filter(_.isFinite).map(FloatValue(_))
          .getOrElse(fail(where, s"non-finite/invalid floating-point literal: $text"))
      case other => fail(where, s"unsupported raw CEL/reference '$other'; use valid DSL values or expressions")
    }

    def cel(node: Cel, where: String): Expr = node match {
      case CelConst(value) => raw(value, where)
      case CelStr(value) => GlobalId.resolve(value).map(Global(_)).getOrElse(StringValue(value))
      case CelValue(value) => valueExpr(value, where)
      case CelFunc(name, params) =>
        val id = FunctionId.resolve(name).getOrElse(
          fail(where, s"unknown native CEL function '$name'; use CelFunctions constants"))
        function(id, params, where)
      case CelOp(CelConst("not"), "", value) =>
        Unary(OperatorId.Not, cel(value, where))
      case CelOp(left, symbol, right) =>
        val id = OperatorId.resolve(symbol, 2).getOrElse(
          fail(where, s"unknown CEL operator '$symbol'"))
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
      if (params.size < id.minArity || params.size > id.maxArity)
        fail(where, s"${id.wireId} expects ${id.minArity}-${id.maxArity} arguments, got ${params.size}")
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

    def local(name: String, where: String): String = {
      if (symbols.contains(name)) fail(where, s"colliding iterator symbol: $name")
      val slot = s"s${slots.size}"
      slots += slot
      symbols(name) = slot
      slot
    }

    def scoped[A](f: => A): A = {
      val before = symbols.toMap
      try f
      finally {
        symbols.clear()
        symbols ++= before
      }
    }

    def steps(all: List[Step[?, ?]], scope: String): Vector[Instruction] = {
      all.zipWithIndex.flatMap { (step, i) =>
        val nodeId = s"$scope.$i"
        val where = s"workflow=$workflowName node=$nodeId step='${step.name}'"
        step match {
          case call: Call[?, ?] =>
            val id = CallId.resolve(call.call).getOrElse(
              fail(where, s"unknown native Call '${call.call}'; use CallOperations constants"))
            val args = valueExpr(call.args, s"$where.args")
            val slot = register(call.resultValue, where)
            Vector(Effect(nodeId, step.name, id, args, slot))
          case ret: Return[?] =>
            Vector(EarlyReturn(nodeId, step.name, valueExpr(ret.value, where)))
          case block: Block[?, ?] =>
            steps(block.steps, nodeId)
          case branch: Switch[?, ?] =>
            // The second Switch type parameter is a match type (ValueType[T,V]).
            // At this boundary, all variants are BaseValue; the local erasure
            // avoids inventing an unconstrained wildcard for its upper bound.
            val entries = branch.cases.asInstanceOf[
              List[(Cel, (List[Step[?, ?]], BaseValue[?]))]
            ]
            val cases = entries.zipWithIndex.map { case ((condition, (run, out)), caseIdx) =>
              val cond = cel(condition, s"$where.case[$caseIdx].condition")
              scoped {
                val body = steps(run, s"$nodeId.case$caseIdx")
                ChoiceCase(cond, body, valueExpr(out, s"$where.case[$caseIdx].result"))
              }
            }.toVector
            val slot = register(branch.resultValue, where)
            Vector(Choose(nodeId, step.name, cases, slot))
          case loop: ForRange[?] =>
            val from = cel(loop.from, s"$where.from")
            val until = cel(loop.to, s"$where.until")
            val (indexSlot, body, result) = scoped {
              val idxSlot = local(loop.idx.value, where)
              val inner = steps(loop.each._1, s"$nodeId.each")
              (idxSlot, inner, valueExpr(loop.each._2, s"$where.each.result"))
            }
            val slot = register(loop.resultValue, where)
            Vector(RepeatRange(nodeId, step.name, from, until, indexSlot, body, result, slot))
          case loop: us.awfl.dsl.For[?, ?] =>
            val iterable = valueExpr(loop.in, s"$where.in")
            val (itemSlot, body, result) = scoped {
              val itemName = loop.item.resolver.path.path match {
                case CelConst(name) :: Nil => name
                case other => fail(where, s"invalid iterator value path: $other")
              }
              val slot = local(itemName, where)
              val inner = steps(loop.each._1, s"$nodeId.each")
              (slot, inner, valueExpr(loop.each._2, s"$where.each.result"))
            }
            val slot = register(loop.resultValue, where)
            Vector(RepeatEach(nodeId, step.name, iterable, itemSlot, body, result, slot))
          case fold: Fold[?, ?, ?] =>
            val initial = valueExpr(fold.b, s"$where.initial")
            val iterable = valueExpr(fold.list, s"$where.list")
            // Fold's result is also its mutable accumulator. Bind it before
            // normalizing the body, so references resolve to the same slot.
            val resultSlot = register(fold.resultValue, where)
            val (itemSlot, body, result) = scoped {
              val itemName = fold.item.resolver.path.path match {
                case CelConst(name) :: Nil => name
                case other => fail(where, s"invalid fold item path: $other")
              }
              val itemSlot = local(itemName, where)
              val body = steps(fold.run._1, s"$nodeId.each")
              (itemSlot, body, valueExpr(fold.run._2, s"$where.each.result"))
            }
            Vector(ReduceEach(nodeId, step.name, initial, iterable, itemSlot,
              body, result, resultSlot))
          case other =>
            fail(where, s"${other.getClass.getSimpleName} requires native lowering before Rust emission; cannot silently interpret YAML")
        }
      }.toVector
    }

    val compiled = steps(workflow.steps._1, "0")
    val output = compiled :+ Finish("0.return", "return", valueExpr(workflow.steps._2, s"workflow=$workflowName return"))
    Program(workflow.name.getOrElse(workflowName), output, slots.toVector)
  }
}
