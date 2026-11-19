package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*

/** Centralized typed expression and string-literal Rust rendering. */
object ExpressionEmitter {
  def q(s: String): String = {
    val out = new StringBuilder("\"")
    s.foreach {
      case '\\' => out.append("\\\\")
      case '"' => out.append("\\\"")
      case '\n' => out.append("\\n")
      case '\r' => out.append("\\r")
      case '\t' => out.append("\\t")
      case c if c < ' ' => out.append(s"\\u{${c.toInt.toHexString}}")
      case c => out.append(c)
    }
    out.append('"').toString
  }

  def render(value: Expr, where: String,
             slot: String => String = id => s"$id.clone()"): String = {
    def inner(v: Expr): String = render(v, where, slot)
    val diagnosticNode = where.stripPrefix("node=")
    // Context is attached to ValueError, preserving its concrete error type
    // for native Try/Catch. Successful expressions incur no logging.
    def traced(operation: String, call: String): String =
      s"($call).map_err(|error| error.at_node(${q(diagnosticNode)}, ${q(operation)}))?"
    value match {
      case NullValue => "AwflValue::Null"
      case BoolValue(v) => s"AwflValue::Bool($v)"
      case IntValue(v) => s"AwflValue::Integer(${v}i64)"
      case FloatValue(v) =>
        if (!v.isFinite) throw CompileError(where, "non-finite float")
        s"AwflValue::Float(${v}f64)"
      case StringValue(v) => s"AwflValue::String(${q(v)}.to_owned())"
      case ListValue(xs) => s"AwflValue::List(vec![${xs.map(inner).mkString(", ")}])"
      case MapValue(xs) =>
        val pairs = xs.map((k, v) => s"(${q(k)}.to_owned(), ${inner(v)})").mkString(", ")
        s"AwflValue::Map(std::collections::BTreeMap::from([$pairs]))"
      case Input => "input.clone()"
      case SlotRef(id) => slot(id)
      case Field(v, name) =>
        traced("field", s"ops::field(&(${inner(v)}), ${q(name)})")
      case Index(v, index) =>
        traced("index", s"ops::index(&(${inner(v)}), &(${inner(index)}))")
      case Global(id) =>
        traced("trusted_global", s"ops::trusted_global(trusted_globals, ${q(id.wireId)})")
      case Binary(left, id, right) =>
        val l = inner(left); val r = inner(right)
        // Evaluate each operand once; capture only static type names before
        // moving them into arithmetic helpers. No successful-path clones.
        // Nested evaluation errors retain their own inner-node context.
        def binary(operation: String, call: String): String =
          s"""{
              let __awfl_left = $l;
              let __awfl_right = $r;
              let __awfl_left_type = __awfl_left.type_name();
              let __awfl_right_type = __awfl_right.type_name();
              ($call).map_err(|error| error.at_operand_types(${q(diagnosticNode)}, ${q(operation)},
                  __awfl_left_type, __awfl_right_type))?
            }"""
        id match {
          case OperatorId.Contains => binary("contains", "ops::contains(&__awfl_left, &__awfl_right)")
          case OperatorId.DivideInt => binary("divide_int", "ops::floor_divide(&__awfl_left, &__awfl_right)")
          case OperatorId.Add => binary("add", "ops::add(__awfl_left, __awfl_right)")
          case OperatorId.Subtract => binary("subtract", "ops::subtract(__awfl_left, __awfl_right)")
          case OperatorId.Multiply => binary("multiply", "ops::multiply(__awfl_left, __awfl_right)")
          case OperatorId.Equal => s"AwflValue::Bool(($l) == ($r))"
          case OperatorId.NotEqual => s"AwflValue::Bool(($l) != ($r))"
          case OperatorId.Greater => s"AwflValue::Bool(${binary("greater", "ops::compare(&__awfl_left, &__awfl_right)")} == std::cmp::Ordering::Greater)"
          case OperatorId.GreaterEq => s"AwflValue::Bool(${binary("greater_eq", "ops::compare(&__awfl_left, &__awfl_right)")} != std::cmp::Ordering::Less)"
          case OperatorId.Less => s"AwflValue::Bool(${binary("less", "ops::compare(&__awfl_left, &__awfl_right)")} == std::cmp::Ordering::Less)"
          case OperatorId.LessEq => s"AwflValue::Bool(${binary("less_eq", "ops::compare(&__awfl_left, &__awfl_right)")} != std::cmp::Ordering::Greater)"
          case OperatorId.And =>
            s"AwflValue::Bool(if ${traced("and.left", s"ops::bool_value(&($l))")} { ${traced("and.right", s"ops::bool_value(&($r))")} } else { false })"
          case OperatorId.Or =>
            s"AwflValue::Bool(if ${traced("or.left", s"ops::bool_value(&($l))")} { true } else { ${traced("or.right", s"ops::bool_value(&($r))")} })"
          case _ => throw CompileError(where, s"operator ${id.wireId} lacks tested native semantics")
        }
      case Unary(OperatorId.Not, value) =>
        s"AwflValue::Bool(!${traced("not", s"ops::bool_value(&(${inner(value)}))")})"
      case Unary(id, _) => throw CompileError(where, s"unary operator ${id.wireId} unsupported")
      case Function(FunctionId.EnvironmentGet, Vector(name)) =>
        traced("environment_get", s"ops::environment_get(trusted_globals, &(${inner(name)}), None)")
      case Function(FunctionId.EnvironmentGet, Vector(name, fallback)) =>
        traced("environment_get", s"ops::environment_get(trusted_globals, &(${inner(name)}), Some(&(${inner(fallback)})))")
      case Function(FunctionId.ClockNow, Vector()) =>
        traced("time_now", "ops::time_now()")
      case Function(FunctionId.TimeFormat, Vector(seconds)) =>
        traced("time_format", s"ops::time_format(&(${inner(seconds)}), None)")
      case Function(FunctionId.TimeFormat, Vector(seconds, timezone)) =>
        traced("time_format", s"ops::time_format(&(${inner(seconds)}), Some(&(${inner(timezone)})))")
      case Function(FunctionId.UuidGenerate, Vector()) => "ops::uuid_generate()"
      case Function(FunctionId.Length, Vector(arg)) =>
        traced("length", s"ops::length(&(${inner(arg)}))")
      case Function(FunctionId.Default, Vector(value, fallback)) =>
        s"ops::default_value(${inner(value)}, ${inner(fallback)})"
      case Function(FunctionId.TypeOf, Vector(value)) =>
        s"ops::type_of(&(${inner(value)}))"
      case Function(FunctionId.ToString, Vector(value)) =>
        traced("to_string", s"ops::to_string(&(${inner(value)}))")
      case Function(FunctionId.ToInt, Vector(value)) =>
        traced("to_int", s"ops::to_int(&(${inner(value)}))")
      case Function(FunctionId.TextUrlEncode, Vector(value)) =>
        traced("text_url_encode", s"ops::text_url_encode(&(${inner(value)}))")
      case Function(FunctionId.TextLowercase, Vector(value)) =>
        traced("text_lowercase", s"ops::text_lowercase(&(${inner(value)}))")
      case Function(FunctionId.TextSplit, Vector(source, separator)) =>
        traced("text_split", s"ops::text_split(&(${inner(source)}), &(${inner(separator)}))")
      case Function(FunctionId.TextSubstring, Vector(source, start, end)) =>
        traced("text_substring", s"ops::text_substring(&(${inner(source)}), &(${inner(start)}), &(${inner(end)}))")
      case Function(FunctionId.MapGet, Vector(map, path)) =>
        traced("map_get", s"ops::map_get(&(${inner(map)}), &(${inner(path)}))")
      case Function(FunctionId.MapMerge, Vector(left, right)) =>
        traced("map_merge", s"ops::map_merge(&(${inner(left)}), &(${inner(right)}))")
      case Function(FunctionId.ListConcat, Vector(list, element)) =>
        traced("list_concat", s"ops::list_concat(&(${inner(list)}), ${inner(element)})")
      case Function(FunctionId.JsonDecode, Vector(value)) =>
        traced("json_decode", s"ops::json_decode(&(${inner(value)}))")
      case Function(FunctionId.JsonEncodeString, Vector(value)) =>
        traced("json_encode_string", s"ops::json_encode_string(&(${inner(value)}))")
      case Function(FunctionId.JsonEncodeBytes, Vector(value)) =>
        traced("json_encode_bytes", s"ops::json_encode_bytes(&(${inner(value)}))")
      case Function(FunctionId.TextEncode, Vector(value)) =>
        traced("text_encode", s"ops::text_encode(&(${inner(value)}), None)")
      case Function(FunctionId.TextEncode, Vector(value, charset)) =>
        traced("text_encode", s"ops::text_encode(&(${inner(value)}), Some(&(${inner(charset)})))")
      case Function(FunctionId.TextDecode, Vector(value)) =>
        traced("text_decode", s"ops::text_decode(&(${inner(value)}), None)")
      case Function(FunctionId.TextDecode, Vector(value, charset)) =>
        traced("text_decode", s"ops::text_decode(&(${inner(value)}), Some(&(${inner(charset)})))")
      case Function(FunctionId.Base64Encode, Vector(value)) =>
        traced("base64_encode", s"ops::base64_encode(&(${inner(value)}), None)")
      case Function(FunctionId.Base64Encode, Vector(value, padding)) =>
        traced("base64_encode", s"ops::base64_encode(&(${inner(value)}), Some(&(${inner(padding)})))")
      case Function(id, _) =>
        throw CompileError(where, s"function ${id.wireId} lacks tested native semantics")
    }
  }

}
