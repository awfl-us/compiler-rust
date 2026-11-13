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
    // Resolve textual DSL references in the active scope only; repeated
    // definitions use separate backend slots, even when names/hash repeat.
    val symbols = mutable.Map.empty[String, String]
    val slots = mutable.ArrayBuffer.empty[String]
    val usedLabels = mutable.Set.empty[String]
    val nextLabelSuffix = mutable.Map.empty[String, Int]

    // Readable labels are unique at compile time; node IDs determine actual
    // effect/continuation identity. Counters avoid quadratic scans when
    // a shared helper emits hundreds of identically named steps.
    def uniqueLabel(name: String): String = {
      var suffix = nextLabelSuffix.getOrElse(name, 0)
      var candidate = if (suffix == 0) name else name + "_" + suffix
      while (usedLabels.contains(candidate)) {
        suffix += 1
        candidate = name + "_" + suffix
      }
      usedLabels += candidate
      nextLabelSuffix(name) = suffix + 1
      candidate
    }

    def fail(where: String, why: String): Nothing = throw CompileError(where, why)

    /** Legacy YAML stringifies an Obj nested in str(CelValue(obj(...))) as
      * JSON text. Resolved fields become literal `${...}` placeholders,
      * not references that must be bound in the workflow.
      */
    def templateCel(node: Cel, where: String): String = node match {
      case CelConst(value) => value
      case CelStr(value) => s"\"${CelStr(value).safe.content}\""
      case CelValue(value: Resolved[?]) => templateCel(value.resolver.path, where)
      case CelValue(value: Obj[?]) => templateJson(value.value, where).toString
      case CelPath(path) => path.map(templateCel(_, where)).mkString(".")
      case CelOp(left, op, right) =>
        s"(${templateCel(left, where)} $op ${templateCel(right, where)})"
      case CelFunc(name, params) =>
        s"$name(${params.map(templateCel(_, where)).mkString(", ")})"
      case CelAt(list, index) =>
        s"${templateCel(list.resolver.path, where)}[${templateCel(index, where)}]"
      case other => fail(where, s"unsupported CEL inside literal JSON template: $other")
    }

    def templateJson(value: Any, where: String): io.circe.Json = {
      import io.circe.Json
      value match {
        case null => Json.Null
        case resolved: Resolved[?] =>
          Json.fromString("${" + templateCel(resolved.resolver.path, where) + "}")
        case opt: OptResolved[?] => templateJson(opt.getOrElse(Value.nil(opt.spec)), where)
        case opt: OptList[?] => templateJson(opt.getOrElse(us.awfl.dsl.ListValue.nil(opt.spec)), where)
        case OptObj(v) => templateJson(v.value, where)
        case Obj(v) => templateJson(v, where)
        case Some(v) => templateJson(v, where)
        case None => Json.fromString("${null}")
        case values: scala.List[?] => Json.fromValues(values.zipWithIndex.map { (v, i) =>
          templateJson(v, s"$where[$i]")
        })
        case values: scala.collection.Map[?, ?] =>
          val fields = values.toSeq.map {
            case (name: String, v) => name -> templateJson(v, s"$where.$name")
            case (key, _) => fail(where, s"invalid JSON template key: $key")
          }
          Json.fromFields(fields)
        case product: Product =>
          Json.fromFields(product.productElementNames.zip(product.productIterator).map {
            (name, field) => name -> templateJson(field, s"$where.$name")
          }.toSeq)
        case v: String => Json.fromString(v)
        case v: Boolean => Json.fromBoolean(v)
        case v: Int => Json.fromInt(v)
        case v: Long => Json.fromLong(v)
        case v: Double => Json.fromDoubleOrNull(v)
        case v: Float => Json.fromFloatOrNull(v)
        case v => fail(where, s"unsupported JSON template value: ${v.getClass.getName}")
      }
    }

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
      // Existing DSL optional field wrappers are not normal product maps.
      // They encode the inner value or null, not {"resolver": ...}.
      case v: OptResolved[?] => cel(v.resolver.path, where)
      case v: OptObj[?] => obj(v.obj.value, where)
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
      // workflow-utils 0.3.5 still emits this historical CEL literal in
      // Strider/Prakriya. Lower only this well-known expression; never
      // interpret arbitrary raw CEL strings.
      case "uuid.generate()" => Function(FunctionId.UuidGenerate, Vector.empty)
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
      // `str(CelValue(obj(...)))` means JSON text, not a runtime map. Only
      // string-typed wrappers take this path; ordinary Obj stays executable.
      case resolved: Value[?] if resolved.spec == stringSpec =>
        resolved.resolver.path.path match {
          case CelValue(Obj(data)) :: Nil => StringValue(templateJson(data, where).toString)
          case _ => cel(resolved.resolver.path, where)
        }
      case resolved: Resolved[?] => cel(resolved.resolver.path, where)
      case Obj(value) => obj(value, where)
    }

    def register(result: Resolved[?], where: String): String = {
      val path = result.resolver.path.path
      val name = path match {
        case CelConst(name) :: Nil => name
        case _ => fail(where, s"unresolvable step result symbol: $path")
      }
      // The latest binding shadows an earlier same-named result in this
      // scope. Earlier expressions have already captured their slot IDs.
      val slot = s"s${slots.size}"
      symbols(name) = slot
      slots += slot
      slot
    }

    def local(name: String, where: String): String = {
      // Nested loop items/indices can shadow their parents temporarily.
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
        val label = uniqueLabel(step.name)
        step match {
          case call: Call[?, ?] =>
            val id = CallId.resolve(call.call).getOrElse(
              fail(where, s"unknown native Call '${call.call}'; use CallOperations constants"))
            val args = valueExpr(call.args, s"$where.args")
            val slot = register(call.resultValue, where)
            Vector(Effect(nodeId, label, id, args, slot))
          case ret: Return[?] =>
            Vector(EarlyReturn(nodeId, label, valueExpr(ret.value, where)))
          case block: Block[?, ?] =>
            steps(block.steps, nodeId)
          case mapped: FlatMap[?, ?, ?] =>
            // FlatMap is an existing DSL projection wrapper, not a new
            // instruction. Its resultVal points into the wrapped step's
            // value, so lower the wrapped step exactly once at this node.
            // Its projected result is evaluated by the parent/return site.
            steps(List(mapped.step), nodeId)
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
                // A Raise or explicit Return unconditionally terminates the
                // branch. Its declared output cannot execute and need not
                // resolve stale/out-of-scope DSL references (Cache uses this
                // pattern for its fallback error branch).
                val terminal = body.lastOption.exists {
                  case _: Throw | _: EarlyReturn => true
                  case _ => false
                }
                val result = if (terminal) NullValue
                  else valueExpr(out, s"$where.case[$caseIdx].result")
                ChoiceCase(cond, body, result)
              }
            }.toVector
            val slot = register(branch.resultValue, where)
            Vector(Choose(nodeId, label, cases, slot))
          case attempted: us.awfl.dsl.Try[?, ?] =>
            // The public DSL's ValueType match type erases to BaseValue here;
            // all normalization remains compiler-private.
            val attempt = attempted.run.asInstanceOf[
              (List[Step[?, ?]], BaseValue[?])]
            val rescue = attempted.except.asInstanceOf[
              (List[Step[?, ?]], BaseValue[?])]
            val errSlot = register(attempted.error, where)
            val (attemptSteps, result) = scoped {
              val compiled = steps(attempt._1, s"$nodeId.try")
              (compiled, valueExpr(attempt._2, s"$where.try.result"))
            }
            val (rescueSteps, recovery) = scoped {
              val compiled = steps(rescue._1, s"$nodeId.except")
              (compiled, valueExpr(rescue._2, s"$where.except.result"))
            }
            val resultSlot = register(attempted.resultValue, where)
            Vector(Rescue(nodeId, label, attemptSteps, result,
              errSlot, rescueSteps, recovery, resultSlot))
          case raised: us.awfl.dsl.Raise =>
            Vector(Throw(nodeId, label, valueExpr(raised.raise, where)))
          case parallel: us.awfl.dsl.Parallel =>
            // All branches see the same pre-fork bindings: no branch may
            // accidentally depend on results of an earlier sibling.
            val snapshot = symbols.toMap
            val branches = parallel.branches.zipWithIndex.map { (branch, branchIdx) =>
              val branchNode = s"$nodeId.branch$branchIdx"
              val (body, result) = scoped {
                symbols.clear()
                symbols ++= snapshot
                val code = steps(List(branch), branchNode)
                // Raise/Return terminate the lane before its nominal result
                // can be evaluated. Do not resolve an unreachable result
                // reference, but retain strict validation in reachable lanes.
                val terminal = code.lastOption.exists {
                  case _: Throw | _: EarlyReturn => true
                  case _ => false
                }
                // Parallel is an existing DSL *statement* with NoValueT,
                // unlike a value-producing Call or ParallelFor. Its nested
                // effects run and join, but it has no implicit result symbol
                // to resolve or export to this containing fork.
                val result = if (terminal || branch.isInstanceOf[us.awfl.dsl.Parallel])
                  NullValue
                else valueExpr(branch.resultValue, s"$where.branch[$branchIdx].result")
                (code, result)
              }
              val destination = s"s${slots.size}"
              slots += destination
              val exposed = branch match {
                case _: us.awfl.dsl.Parallel => None
                case _ => branch.resultValue match {
                case resolved: Resolved[?] =>
                  resolved.resolver.path.path match {
                    case CelConst(resultName) :: Nil => Some(resultName)
                    case _ => None // No stable result-root alias to export.
                  }
                case _ => None
                }
              }
              (ForkBranch(branchNode, body, result, destination), exposed)
            }.toVector
            // Only explicit branch results cross the join. Internal values
            // and iterator bindings do not escape their lexical scope.
            branches.foreach { (branch, exposed) =>
              exposed.foreach(name => symbols(name) = branch.joinedSlot)
            }
            Vector(Fork(nodeId, label, branches.map(_._1)))
          case parallel: us.awfl.dsl.ParallelFor[?, ?] =>
            val iterable = valueExpr(parallel.in, s"$where.in")
            val (itemSlot, body, result) = scoped {
              val itemName = parallel.item.resolver.path.path match {
                case CelConst(name) :: Nil => name
                case other => fail(where, s"invalid parallel iterator path: $other")
              }
              val slot = local(itemName, where)
              val code = steps(parallel.each._1, s"$nodeId.each")
              (slot, code, valueExpr(parallel.each._2, s"$where.each.result"))
            }
            val resultSlot = register(parallel.resultValue, where)
            Vector(ForkEach(nodeId, label, iterable, itemSlot, body, result, resultSlot))
          case loop: ForRange[?] =>
            val from = cel(loop.from, s"$where.from")
            val until = cel(loop.to, s"$where.until")
            val (indexSlot, body, result) = scoped {
              val idxSlot = local(loop.idx.value, where)
              val inner = steps(loop.each._1, s"$nodeId.each")
              (idxSlot, inner, valueExpr(loop.each._2, s"$where.each.result"))
            }
            val slot = register(loop.resultValue, where)
            Vector(RepeatRange(nodeId, label, from, until, indexSlot, body, result, slot))
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
            Vector(RepeatEach(nodeId, label, iterable, itemSlot, body, result, slot))
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
            Vector(ReduceEach(nodeId, label, initial, iterable, itemSlot,
              body, result, resultSlot))
          case null => fail(where, "null workflow step")
        }
      }.toVector
    }

    val compiled = steps(workflow.steps._1, "0")
    val output = compiled :+ Finish("0.return", uniqueLabel("return"), valueExpr(workflow.steps._2, s"workflow=$workflowName return"))
    Program(workflow.name.getOrElse(workflowName), output, slots.toVector)
  }
}
