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
      case Field(v, name) => s"ops::field(&(${inner(v)}), ${q(name)})?"
      case Index(v, index) => s"ops::index(&(${inner(v)}), &(${inner(index)}))?"
      case Global(id) => throw CompileError(where, s"global ${id.wireId} requires trusted server bindings; not available in pure runner")
      case Binary(left, id, right) =>
        val l = inner(left); val r = inner(right)
        id match {
          case OperatorId.Contains => s"ops::contains(&($l), &($r))?"
          case OperatorId.DivideInt => s"ops::floor_divide(&($l), &($r))?"
          case OperatorId.Add => s"ops::add($l, $r)?"
          case OperatorId.Subtract => s"ops::subtract($l, $r)?"
          case OperatorId.Multiply => s"ops::multiply($l, $r)?"
          case OperatorId.Equal => s"AwflValue::Bool(($l) == ($r))"
          case OperatorId.NotEqual => s"AwflValue::Bool(($l) != ($r))"
          case OperatorId.Greater => s"AwflValue::Bool(ops::compare(&($l), &($r))? == std::cmp::Ordering::Greater)"
          case OperatorId.GreaterEq => s"AwflValue::Bool(ops::compare(&($l), &($r))? != std::cmp::Ordering::Less)"
          case OperatorId.Less => s"AwflValue::Bool(ops::compare(&($l), &($r))? == std::cmp::Ordering::Less)"
          case OperatorId.LessEq => s"AwflValue::Bool(ops::compare(&($l), &($r))? != std::cmp::Ordering::Greater)"
          case OperatorId.And =>
            s"AwflValue::Bool(if ops::bool_value(&($l))? { ops::bool_value(&($r))? } else { false })"
          case OperatorId.Or =>
            s"AwflValue::Bool(if ops::bool_value(&($l))? { true } else { ops::bool_value(&($r))? })"
          case _ => throw CompileError(where, s"operator ${id.wireId} lacks tested native semantics")
        }
      case Unary(OperatorId.Not, value) => s"AwflValue::Bool(!ops::bool_value(&(${inner(value)}))?)"
      case Unary(id, _) => throw CompileError(where, s"unary operator ${id.wireId} unsupported")
      case Function(FunctionId.Length, Vector(arg)) => s"ops::length(&(${inner(arg)}))?"
      case Function(FunctionId.Default, Vector(value, fallback)) =>
        s"ops::default_value(${inner(value)}, ${inner(fallback)})"
      case Function(FunctionId.TypeOf, Vector(value)) =>
        s"ops::type_of(&(${inner(value)}))"
      case Function(FunctionId.MapGet, Vector(map, path)) =>
        s"ops::map_get(&(${inner(map)}), &(${inner(path)}))?"
      case Function(FunctionId.MapMerge, Vector(left, right)) =>
        s"ops::map_merge(&(${inner(left)}), &(${inner(right)}))?"
      case Function(FunctionId.ListConcat, Vector(list, element)) =>
        s"ops::list_concat(&(${inner(list)}), ${inner(element)})?"
      case Function(FunctionId.JsonDecode, Vector(value)) =>
        s"ops::json_decode(&(${inner(value)}))?"
      case Function(FunctionId.JsonEncodeString, Vector(value)) =>
        s"ops::json_encode_string(&(${inner(value)}))?"
      case Function(FunctionId.JsonEncodeBytes, Vector(value)) =>
        s"ops::json_encode_bytes(&(${inner(value)}))?"
      case Function(id, _) =>
        throw CompileError(where, s"function ${id.wireId} lacks tested native semantics")
    }
  }

}
