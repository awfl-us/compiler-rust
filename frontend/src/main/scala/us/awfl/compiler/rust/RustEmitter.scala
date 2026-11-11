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

  private def emitStep(instruction: Instruction, nesting: Int,
                       effectful: Boolean = false): String = {
    val pad = " " * nesting
    val where = s"node=${instruction.nodeId} step='${instruction.displayName}'"
    def expr(value: Expr): String = render(value, where)
    def children(steps: Vector[Instruction]): String =
      steps.map(child => emitStep(child, nesting + 4, effectful)).mkString("\n")
    instruction match {
      case EarlyReturn(nodeId, name, value) =>
        val returned = if (effectful)
          s"Outcome::Completed { result: (${expr(value)}).to_plain_json()? }"
        else expr(value)
        s"""$pad// AWFL $nodeId $name
${pad}return Ok($returned);"""
      case Finish(nodeId, name, value) =>
        if (nesting != 4) throw CompileError(where, "implicit terminal return is only valid at top level")
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
        unsupported(effect, s"nested effect ${effect.id.wireId} needs compiled continuation lowering for this nesting")
    }
  }

  private def topLevelSlot(step: Instruction): Option[String] = step match {
    case s: Choose => Some(s.resultSlot)
    case s: RepeatRange => Some(s.resultSlot)
    case s: RepeatEach => Some(s.resultSlot)
    case s: ReduceEach => Some(s.resultSlot)
    case s: Effect => Some(s.resultSlot)
    case _ => None
  }

  /** Effectful range bodies use an ordinal frame plus, for multiple Calls,
    * a second frame identifying the effect site. Prior results in the current
    * iteration are checkpointed alongside completed iteration outputs.
    * Nested effects in other control flow still fail closed.
    */
  private def emitEffectRange(loop: RepeatRange, index: Int,
                              prior: Vector[String]): String = {
    val where = s"node=${loop.nodeId} step='${loop.displayName}'"
    val nested = Program("range-effects", loop.body, Vector.empty).effects
    val direct = loop.body.collect { case effect: Effect => effect }
    if (direct.isEmpty || nested.size != direct.size)
      throw CompileError(where, "effectful ForRange requires direct Calls only; effects in nested control flow unsupported")
    val multiSite = direct.size > 1
    val expectedFrames = if (multiSite) 2 else 1
    val siteCases = direct.zipWithIndex.map { (e, i) =>
      s"""                    (${q(e.nodeId)}, ${i}u64) => ${i}u64,"""
    }.mkString("\n")
    val siteValidation = if (multiSite)
      s"""let __awfl_site = match (state.frames[1].node_id.as_str(), state.frames[1].iteration) {
$siteCases
                    _ => return Err("invalid native range continuation site".into()),
                };"""
    else "let __awfl_site = 0u64;"
    val itemsKey = q(s"__awfl_loop_${index}_items")
    val local = s"index_${loop.indexSlot}"
    val saved = prior.map(slot =>
      s"""                slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
    val body = loop.body.map {
      case e: Effect =>
        val site = direct.indexWhere(_.nodeId == e.nodeId)
        val resumedSite = if (site == 0) "" else s" && __awfl_resume_site >= ${site}u64"
        val savedEarlier = direct.take(site).map(previous =>
          s"""                slots.insert(${q(previous.resultSlot)}.to_owned(), ${previous.resultSlot}.clone());""").mkString("\n")
        val extraFrame = if (multiSite)
          s""", InvocationFrame {
                            node_id: ${q(e.nodeId)}.to_owned(),
                            iteration: ${site}u64,
                        }"""
        else ""
        s"""            // AWFL ${e.nodeId} ${e.displayName}: dynamic site $site
            let ${e.resultSlot} = if __awfl_resuming_here && __awfl_iter == __awfl_resume_iter$resumedSite {
                resume.ok_or("missing native continuation")?.slot(${q(e.resultSlot)})?
            } else {
                let mut slots = std::collections::BTreeMap::new();
$saved
$savedEarlier
                slots.insert($itemsKey.to_owned(), AwflValue::List(__awfl_items.clone()));
                return Ok(Outcome::Suspended {
                    effect: EffectIntent {
                        effect_id: format!("call_${index}_${e.resultSlot}_{}", __awfl_iter),
                        node_id: ${q(e.nodeId)}.to_owned(),
                        operation: ${q(e.id.wireId)}.to_owned(),
                        result_slot: ${q(e.resultSlot)}.to_owned(),
                        args: (${render(e.arguments, where)}).to_plain_json()?,
                    },
                    continuation: Continuation {
                        next_step: $index, slots,
                        frames: vec![InvocationFrame {
                            node_id: ${q(loop.nodeId)}.to_owned(),
                            iteration: __awfl_iter,
                        }$extraFrame],
                    },
                });
            };"""
      case other => emitStep(other, 12, effectful = true)
    }.mkString("\n")
    s"""    // AWFL ${loop.nodeId} ${loop.displayName} (resumable ForRange)
    let ${loop.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(loop.resultSlot)})?
    } else {
        let __awfl_start = ops::int_value(&(${render(loop.from, where)}))?;
        let __awfl_end = ops::int_value(&(${render(loop.until, where)}))?;
        let __awfl_span = ((__awfl_end as i128) - (__awfl_start as i128)).max(0) as u64;
        let __awfl_resuming_here = resume.is_some_and(|s| !s.frames.is_empty() && pc == $index);
        let (__awfl_resume_iter, __awfl_resume_site, mut __awfl_items): (u64, u64, Vec<AwflValue>) =
            if __awfl_resuming_here {
                let state = resume.ok_or("missing native continuation")?;
                if state.frames.len() != $expectedFrames || state.frames[0].node_id != ${q(loop.nodeId)} {
                    return Err("invalid native range continuation frame".into());
                }
                let iteration = state.frames[0].iteration;
                let items = ops::list_value(&state.slot($itemsKey)?)?;
                if iteration >= __awfl_span || items.len() as u64 != iteration {
                    return Err("invalid native range continuation prefix".into());
                }
                $siteValidation
                (iteration, __awfl_site, items)
            } else { (0, 0, Vec::new()) };
        let __awfl_next = i64::try_from((__awfl_start as i128) + (__awfl_resume_iter as i128))?;
        for $local in __awfl_next..__awfl_end {
            let __awfl_iter = (($local as i128) - (__awfl_start as i128)) as u64;
            let ${loop.indexSlot} = AwflValue::Integer($local);
$body
            __awfl_items.push(${render(loop.result, where)});
        }
        AwflValue::List(__awfl_items)
    };"""
  }

  /** Direct Rust computation with server-managed effect boundaries. */
  private def effectfulSource(program: Program): String = {
    val frameConditions = program.steps.zipWithIndex.collect {
      case (loop: RepeatRange, i) if Program("range-effects", loop.body, Vector.empty).effects.nonEmpty =>
        s"pc == $i && s.frames.len() == ${if (loop.body.collect { case _: Effect => 1 }.size > 1) 2 else 1} && s.frames[0].node_id == ${q(loop.nodeId)}"
    }
    val frameTargets = frameConditions match {
      case Vector() => "false"
      case Vector(one) => one
      case many => many.map(expr => s"($expr)").mkString(" || ")
    }
    val statements = program.steps.zipWithIndex.map { (step, index) =>
      val where = s"node=${step.nodeId} step='${step.displayName}'"
      step match {
        case Finish(_, _, value) =>
          s"""    Ok(Outcome::Completed { result: (${render(value, where)}).to_plain_json()? })"""
        case effect: Effect =>
          val prior = program.steps.take(index).flatMap(topLevelSlot)
          val slotsBinding = if (prior.isEmpty) "let slots" else "let mut slots"
          val saved = prior.map(slot =>
            s"""            slots.insert(${q(slot)}.to_owned(), $slot.clone());""").mkString("\n")
          s"""    // AWFL ${effect.nodeId} ${effect.displayName}
    let ${effect.resultSlot} = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(effect.resultSlot)})?
    } else {
        $slotsBinding = std::collections::BTreeMap::new();
$saved
        return Ok(Outcome::Suspended {
            effect: EffectIntent {
                effect_id: ${q(s"call_$index")}.to_owned(),
                node_id: ${q(effect.nodeId)}.to_owned(),
                operation: ${q(effect.id.wireId)}.to_owned(),
                result_slot: ${q(effect.resultSlot)}.to_owned(),
                args: (${render(effect.arguments, where)}).to_plain_json()?,
            },
            continuation: Continuation { next_step: ${index + 1}, slots, frames: vec![] },
        });
    };"""
        case early: EarlyReturn =>
          s"""    if pc <= $index {
${emitStep(early, 8, effectful = true)}
    }"""
        case loop: RepeatRange =>
          val slot = loop.resultSlot
          val prior = program.steps.take(index).flatMap(topLevelSlot)
          if (Program("range-effects", loop.body, Vector.empty).effects.nonEmpty)
            emitEffectRange(loop, index, prior)
          else s"""    let $slot = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(slot)})?
    } else {
${emitStep(loop, 8, effectful = true)}
        $slot
    };"""
        case other =>
          val slot = topLevelSlot(other).getOrElse(
            throw CompileError(where, "unsupported top-level native statement"))
          s"""    let $slot = if pc > $index {
        resume.ok_or("missing native continuation")?.slot(${q(slot)})?
    } else {
${emitStep(other, 8, effectful = true)}
        $slot
    };"""
      }
    }.mkString("\n")

    s"""// Generated by awfl compiler-rust Scala frontend. Do not edit.
use std::io::{self, Read};
use awfl_native_runtime::AwflValue;
#[allow(unused_imports)]
use awfl_native_runtime::operations as ops;
#[allow(unused_imports)]
use awfl_native_runtime::protocol::{
    Request, Response, Outcome, EffectIntent, Continuation, InvocationFrame,
};

// Generated lexical scopes intentionally bind and return the same AWFL slot.
// The DSL may declare loop index slots that user code never reads.
#[allow(clippy::let_and_return, unreachable_code, unused_variables)]
fn run(input: &AwflValue, resume: Option<&Continuation>) -> Result<Outcome, Box<dyn std::error::Error>> {
    let _ = input;
    let pc = resume.map_or(0, |state| state.next_step);
    if resume.is_some_and(|s| {
        let valid_target = $frameTargets;
        !s.frames.is_empty() && !valid_target
    }) {
        return Err("invalid native continuation frame target".into());
    }
$statements
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut text = String::new();
    io::stdin().read_to_string(&mut text)?;
    let request: Request = serde_json::from_str(&text)?;
    request.validate(${program.steps.size}).map_err(|e|
        io::Error::new(io::ErrorKind::InvalidInput, e))?;
    let input = AwflValue::from_plain_json(&request.input)?;
    let outcome = run(&input, request.continuation.as_ref())?;
    println!("{}", serde_json::to_string(&Response::new(outcome))?);
    Ok(())
}
"""
  }

  def emit(program: Program): Emitted = {
    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError(s"workflow=${program.name}",
        "workflow requires exactly one terminal return after control flow")
    val source = if (program.effects.nonEmpty) effectfulSource(program) else {
    val body = program.steps.map(step => emitStep(step, 4)).mkString("\n")
    s"""// Generated by awfl compiler-rust Scala frontend. Do not edit.
use std::io::{self, Read};
use awfl_native_runtime::AwflValue;
#[allow(unused_imports)]
use awfl_native_runtime::operations as ops;

// DSL Return inside a branch can make its enclosing expression unreachable.
#[allow(unreachable_code)]
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
    }
    val sha = MessageDigest.getInstance("SHA-256").digest(source.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
    val manifest = Json.obj(
      "schema_version" -> Json.fromInt(program.irVersion),
      "workflow" -> Json.fromString(program.name),
      "source_sha256" -> Json.fromString(sha),
      "runtime_abi" -> Json.fromInt(if (program.effects.nonEmpty) 2 else 1),
      "node_ids" -> Json.arr(program.nodeIds.map(Json.fromString)*),
      "effects" -> Json.arr(program.effects.map(e => Json.obj(
        "node_id" -> Json.fromString(e.nodeId),
        "operation" -> Json.fromString(e.id.wireId)
      ))*)
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
