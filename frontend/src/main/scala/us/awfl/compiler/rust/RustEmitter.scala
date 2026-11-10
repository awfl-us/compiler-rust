package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.dsl.{FunctionId, OperatorId}
import io.circe.Json
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Scala -> native Rust source generation. Emits concrete Rust statements, not a graph interpreter.
  * Initial executable gate is deliberately pure-only: effects must wait for server-managed
  * preparation/checkpoint/resume. Never run a Call in the compiler process.
  */
object RustEmitter {
  final case class Emitted(source: String, manifest: String)
  private def unsupported(node: Instruction, detail: String): Nothing =
    throw CompileError(s"node=${node.nodeId} step='${node.displayName}'", detail)

  private def q(s: String): String = {
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

  private def render(value: Expr, where: String): String = {
    def inner(v: Expr): String = render(v, where)
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
      case SlotRef(id) => s"$id.clone()"
      case Field(v, name) => s"ops::field(&(${inner(v)}), ${q(name)})?"
      case Index(v, index) => s"ops::index(&(${inner(v)}), &(${inner(index)}))?"
      case Global(id) => throw CompileError(where, s"global ${id.wireId} requires trusted server bindings; not available in pure runner")
      case Binary(left, id, right) =>
        val l = inner(left); val r = inner(right)
        id match {
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
      case Function(id, _) =>
        throw CompileError(where, s"function ${id.wireId} lacks tested native semantics")
    }
  }

  private def emitStep(instruction: Instruction, nesting: Int): String = {
    val pad = " " * nesting
    val where = s"node=${instruction.nodeId} step='${instruction.displayName}'"
    def expr(value: Expr): String = render(value, where)
    def children(steps: Vector[Instruction]): String =
      steps.map(child => emitStep(child, nesting + 4)).mkString("\n")
    instruction match {
      case Finish(nodeId, name, value) =>
        if (nesting != 4) throw CompileError(where, "nested return requires early-exit lowering")
        s"""$pad// AWFL $nodeId $name
${pad}Ok(${expr(value)})"""
      case Choose(nodeId, name, cases, resultSlot) =>
        val branches = cases.zipWithIndex.map { (branch, i) =>
          val prefix = if (i == 0) "if" else "else if"
          val cond = expr(branch.condition)
          val body = children(branch.body)
          s"""$prefix ops::bool_value(&($cond))? {
$body
${pad}    ${expr(branch.result)}
${pad}}"""
        }.mkString(" ")
        val default = "AwflValue::Map(std::collections::BTreeMap::new())"
        val assigned = if (cases.isEmpty) default else
          s"$branches else { $default }"
        s"""$pad// AWFL $nodeId $name
${pad}let $resultSlot = $assigned;"""
      case RepeatRange(nodeId, name, from, until, indexSlot, body, result, resultSlot) =>
        val start = expr(from)
        val end = expr(until)
        val local = s"index_$indexSlot"
        val items = s"${resultSlot}_items"
        s"""$pad// AWFL $nodeId $name
${pad}let mut $items: Vec<AwflValue> = Vec::new();
${pad}for $local in ops::int_value(&($start))?..ops::int_value(&($end))? {
${pad}    let $indexSlot = AwflValue::Integer($local);
${children(body)}
${pad}    $items.push(${expr(result)});
${pad}}
${pad}let $resultSlot = AwflValue::List($items);"""
      case RepeatEach(nodeId, name, iterable, itemSlot, body, result, resultSlot) =>
        val local = s"item_$itemSlot"
        val items = s"${resultSlot}_items"
        s"""$pad// AWFL $nodeId $name
${pad}let mut $items: Vec<AwflValue> = Vec::new();
${pad}for $local in ops::list_value(&(${expr(iterable)}))? {
${pad}    let $itemSlot = $local;
${children(body)}
${pad}    $items.push(${expr(result)});
${pad}}
${pad}let $resultSlot = AwflValue::List($items);"""
      case ReduceEach(nodeId, name, initial, iterable, itemSlot, body, result, resultSlot) =>
        val local = s"item_$itemSlot"
        s"""$pad// AWFL $nodeId $name
${pad}let mut $resultSlot = ${expr(initial)};
${pad}for $local in ops::list_value(&(${expr(iterable)}))? {
${pad}    let $itemSlot = $local;
${children(body)}
${pad}    $resultSlot = ${expr(result)};
${pad}}"""
      case effect: Effect =>
        unsupported(effect, s"effect ${effect.id.wireId} needs server-managed continuation, checkpoint and outcome handling")
    }
  }

  def emit(program: Program): Emitted = {
    // Do not silently omit effects or pretend externally visible calls are replay-safe.
    program.effects.headOption.foreach { effect =>
      unsupported(effect, s"effect ${effect.id.wireId} needs server-managed continuation, checkpoint and outcome handling")
    }
    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError(s"workflow=${program.name}",
        "workflow requires exactly one terminal return after control flow")
    val body = program.steps.map(step => emitStep(step, 4)).mkString("\n")
    val source = s"""// Generated by awfl compiler-rust Scala frontend. Do not edit.
use std::io::{self, Read};
use awfl_native_runtime::{AwflValue, operations as ops};

fn run(input: &AwflValue) -> Result<AwflValue, Box<dyn std::error::Error>> {
    let _ = input; // workflows with constant inputs should remain warning-free
$body
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut text = String::new();
    io::stdin().read_to_string(&mut text)?;
    let json: serde_json::Value = serde_json::from_str(&text)?;
    let input = AwflValue::from_plain_json(&json)?;
    let result = run(&input)?;
    println!("{}", result.to_plain_json()?);
    Ok(())
}
"""
    val sha = MessageDigest.getInstance("SHA-256").digest(source.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
    val manifest = Json.obj(
      "schema_version" -> Json.fromInt(program.irVersion),
      "workflow" -> Json.fromString(program.name),
      "source_sha256" -> Json.fromString(sha),
      "runtime_abi" -> Json.fromInt(1),
      "node_ids" -> Json.arr(program.nodeIds.map(Json.fromString)*),
      "effects" -> Json.arr()
    ).noSpaces
    Emitted(source, manifest)
  }

  def write(program: Program, directory: Path, runtime: Path): Emitted = {
    val generated = emit(program)
    Files.createDirectories(directory.resolve("src"))
    val relative = directory.toAbsolutePath.normalize.relativize(runtime.toAbsolutePath.normalize)
    // Each generated binary has its own deterministic Cargo package name.
    // A shared build target must never return a different workflow's binary.
    val artifactName = directory.getFileName.toString
    val shortName = artifactName.toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_").take(48)
    val shortHash = MessageDigest.getInstance("SHA-256")
      .digest(artifactName.getBytes(UTF_8)).take(4).map(b => f"${b & 0xff}%02x").mkString
    val crateName = s"awfl_${if (shortName.isEmpty) "workflow" else shortName}_$shortHash"
    // Cargo accepts forward-slash relative paths on Unix; path is controlled by caller.
    val cargo = s"""[package]
name = "$crateName"
version = "0.1.0"
edition = "2024"
publish = false

[dependencies]
awfl-native-runtime = { path = "${relative.toString.replace('\\', '/')}" }
serde_json = "1"

[workspace]
"""
    Files.writeString(directory.resolve("Cargo.toml"), cargo, UTF_8)
    Files.writeString(directory.resolve("src/main.rs"), generated.source, UTF_8)
    Files.writeString(directory.resolve("manifest.json"), generated.manifest + "\n", UTF_8)
    generated
  }
}
