package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.compiler.rust.Ir.*
import us.awfl.services.Llm.ToolDefProperty

/** Preserve the Google YAML compiler's OptList schema semantics.
  * In particular, CREATE_TASK enums/required must be JSON lists, not
  * the internal OptList(resolver, spec) Scala product representation.
  */
object OptListJsonParitySmoke {
  private def member(value: Expr, name: String): Expr = value match {
    case MapValue(fields) => fields.toMap.getOrElse(name,
      throw new AssertionError(s"missing key $name in $fields"))
    case other => throw new AssertionError(s"expected map for $name, got $other")
  }

  private def defaulted(value: Expr, expected: Expr): Unit = value match {
    case Function(FunctionId.Default, Vector(actual, NullValue)) =>
      assert(actual == expected, s"list should remain structured: $actual != $expected")
    case other => throw new AssertionError(s"expected default(list, null), got $other")
  }

  def main(args: Array[String]): Unit = {
    val statuses = Vector("Queued", "In Progress", "Done", "Stuck")
    val createTask = ToolDefProperty("object",
      properties = Map(
        "status" -> ToolDefProperty("string",
          `enum` = OptList[String](CelConst("""["Queued", "In Progress", "Done", "Stuck"]"""))),
        "title" -> ToolDefProperty("string")),
      required = OptList[String](CelConst("""["title", "status"]""")))
    val dynamic = ToolDefProperty("string",
      `enum` = OptList[String](CelConst("input.allowedStatuses")))
    val output = obj(Map("createTask" -> createTask, "dynamic" -> dynamic))
    val program = Normalize(Workflow((List.empty[Step[?, ?]], output),
      Some("optlist-json-parity")))
    val body = program.steps.last match {
      case Finish(_, _, value) => value
      case other => throw new AssertionError(s"expected native finish, got $other")
    }

    val create = member(body, "createTask")
    val status = member(member(create, "properties"), "status")
    defaulted(member(status, "enum"), Ir.ListValue(statuses.map(StringValue.apply)))
    defaulted(member(create, "required"),
      Ir.ListValue(Vector("title", "status").map(StringValue.apply)))

    // Unspecified optional lists must compile to null, not a resolver map.
    defaulted(member(member(member(create, "properties"), "title"), "enum"), NullValue)
    defaulted(member(member(member(create, "properties"), "title"), "required"), NullValue)
    defaulted(member(create, "enum"), NullValue)

    // A runtime-valued optional list retains the CEL reference and null fallback.
    defaulted(member(member(body, "dynamic"), "enum"),
      Field(Input, "allowedStatuses"))

    val generated = RustEmitter.emit(program).source
    assert(generated.contains("ops::default_value"))
    assert(generated.contains("AwflValue::List(vec!["))
    for (status <- statuses) assert(generated.contains(status), status)
    assert(!generated.contains("resolver"), "leaked DSL resolver into native JSON")
    assert(!generated.contains("safe"), "leaked DSL wrapper into native JSON")
    println("OPTLIST_JSON_PARITY_PASS: enum, required, optional null and dynamic references")
  }
}
