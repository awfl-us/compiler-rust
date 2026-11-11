package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.compiler.rust.ControlFlowIr.*
import ExpressionEmitter.{q, render}

/** Native ABI 3: one generic, statically compiled Rust block per CFG node.
  * The next PC is checkpointed after each external effect, so nested control
  * flow never has to be replayed to find the continuation.
  */
object StateMachineEmitter {
  def source(program: Program): String =
    source(ControlFlowLowering(program))

  def source(plan: Plan): String = {
    val liveness = ContinuationAnalysis(plan)
    // A CFG whose blocks only suspend or complete never mutates the PC.
    // Emit a direct match so Clippy does not flag an unnecessary loop or
    // final-position `return` in the generated Rust.
    val hasErrors = plan.blocks.exists(b =>
      b.onError.nonEmpty || b.op.isInstanceOf[ThrowError])
    val advancesPc = plan.blocks.exists(_.op match {
      case _: Done | _: Invoke | _: ThrowError => false
      case _ => true
    })
    val terminalPrefix = if (hasErrors) "Ok(Some(Outcome::"
                         else if (advancesPc) "return Ok(Outcome::"
                         else "Ok(Outcome::"
    val terminalSuffix = if (hasErrors) "))"
                         else if (advancesPc) ");" else ")"
    def slot(id: String): String =
      s"""slots.get(${q(id)}).cloned().ok_or(${q("missing continuation slot: " + id)})?"""
    def expr(value: Expr, node: String): String =
      render(value, s"node=$node", slot)
    def advance(label: Int): String = s"pc = $label;"
    def put(name: String, expression: String): String =
      s"slots.insert(${q(name)}.to_owned(), $expression);"
    def number(name: String): String = s"ops::int_value(&(${slot(name)}))?"
    def bumped(ordinal: String): String =
      s"""${put(ordinal, s"AwflValue::Integer(${number(ordinal)}.checked_add(1).ok_or(\"loop ordinal overflow\")?)")}"""
    def listUpdate(value: Expr, result: String, node: String): String =
      s"""let __awfl_value = ${expr(value, node)};
                    match slots.get_mut(${q(result)}) {
                        Some(AwflValue::List(items)) => items.push(__awfl_value),
                        _ => return Err("invalid native list accumulator".into()),
                    }"""
    def checkItems(values: String, ordinal: String, item: String,
                   body: Int, done: Int): String =
      s"""let __awfl_index = usize::try_from(${number(ordinal)})?;
                    let __awfl_item = match slots.get(${q(values)}) {
                        Some(AwflValue::List(items)) => items.get(__awfl_index).cloned(),
                        _ => return Err("invalid native iterable".into()),
                    };
                    if let Some(item) = __awfl_item {
                        ${put(item, "item")}
                        ${advance(body)}
                    } else { ${advance(done)} }"""
    def renderBlock(block: Block): String = {
      val code = block.op match {
        case Bind(name, value, next) =>
          s"${put(name, expr(value, block.sourceNode))}\n                    ${advance(next)}"
        case Test(condition, yes, no) =>
          s"""if ops::bool_value(&(${expr(condition, block.sourceNode)}))? {
                        ${advance(yes)}
                    } else { ${advance(no)} }"""
        case BeginRange(from, until, start, end, ordinal, result, next) =>
          s"""${put(start, expr(from, block.sourceNode))}
                    ${put(end, expr(until, block.sourceNode))}
                    ${put(ordinal, "AwflValue::Integer(0)")}
                    ${put(result, "AwflValue::List(Vec::new())")}
                    ${advance(next)}"""
        case CheckRange(start, end, ordinal, item, body, done) =>
          s"""let __awfl_from = ${number(start)};
                    let __awfl_until = ${number(end)};
                    let __awfl_ordinal = ${number(ordinal)};
                    if __awfl_ordinal < 0 { return Err("negative native range ordinal".into()); }
                    let __awfl_cursor = (__awfl_from as i128) + (__awfl_ordinal as i128);
                    if __awfl_cursor < (__awfl_until as i128) {
                        ${put(item, "AwflValue::Integer(i64::try_from(__awfl_cursor)?)")}
                        ${advance(body)}
                    } else { ${advance(done)} }"""
        case AppendRange(value, result, ordinal, next) =>
          s"""${listUpdate(value, result, block.sourceNode)}
                    ${bumped(ordinal)}
                    ${advance(next)}"""
        case BeginEach(iterable, values, ordinal, result, next) =>
          s"""${put(values, s"AwflValue::List(ops::list_value(&(${expr(iterable, block.sourceNode)}))?)")}
                    ${put(ordinal, "AwflValue::Integer(0)")}
                    ${put(result, "AwflValue::List(Vec::new())")}
                    ${advance(next)}"""
        case CheckEach(values, ordinal, item, body, done) =>
          checkItems(values, ordinal, item, body, done)
        case AppendEach(value, result, ordinal, next) =>
          s"""${listUpdate(value, result, block.sourceNode)}
                    ${bumped(ordinal)}
                    ${advance(next)}"""
        case BeginFold(initial, iterable, values, ordinal, result, next) =>
          s"""${put(values, s"AwflValue::List(ops::list_value(&(${expr(iterable, block.sourceNode)}))?)")}
                    ${put(ordinal, "AwflValue::Integer(0)")}
                    ${put(result, expr(initial, block.sourceNode))}
                    ${advance(next)}"""
        case CheckFold(values, ordinal, item, body, done) =>
          checkItems(values, ordinal, item, body, done)
        case UpdateFold(value, result, ordinal, next) =>
          s"""${put(result, expr(value, block.sourceNode))}
                    ${bumped(ordinal)}
                    ${advance(next)}"""
        case Invoke(effect, ancestors, next) =>
          val effectId = q("call_" + effect.nodeId.replace('.', '_'))
          val dynamicId = ancestors.map { id =>
            s"""__awfl_effect_id.push('_');
                    let __awfl_ordinal = ${number(id)};
                    if __awfl_ordinal < 0 { return Err("invalid effect iteration ordinal".into()); }
                    __awfl_effect_id.push_str(&__awfl_ordinal.to_string());"""
          }.mkString("\n                    ")
          // The checkpoint must have enough state for either the successful
          // next CFG block or the statically compiled exception handler.
          val onError = block.onError
          val keep = (liveness.resumeSlots(next) ++
            onError.toSet.flatMap(c => liveness.resumeSlots(c.next)))
            .toVector.sorted
          val saved = keep.map(id =>
            s"""if let Some(value) = slots.get(${q(id)}) {
                        __awfl_saved.insert(${q(id)}.to_owned(), value.clone());
                    }""").mkString("\n                    ")
          val effectIdBinding = if (ancestors.isEmpty) "let" else "let mut"
          val savedBinding = if (keep.isEmpty) "let" else "let mut"
          val errorHandler = onError match {
            case Some(c) => s"Some(EffectErrorHandler { next_step: ${c.next}, error_slot: ${q(c.slot)}.to_owned() })"
            case None => "None"
          }
          s"""let __awfl_args = (${expr(effect.arguments, block.sourceNode)}).to_plain_json()?;
                    $effectIdBinding __awfl_effect_id = String::from($effectId);
                    $dynamicId
                    $savedBinding __awfl_saved = std::collections::BTreeMap::new();
                    $saved
                    ${terminalPrefix}Suspended {
                        effect: EffectIntent {
                            effect_id: __awfl_effect_id,
                            node_id: ${q(effect.nodeId)}.to_owned(),
                            operation: ${q(effect.id.wireId)}.to_owned(),
                            result_slot: ${q(effect.resultSlot)}.to_owned(),
                            args: __awfl_args,
                            on_error: $errorHandler,
                        },
                        continuation: Continuation {
                            next_step: $next, slots: __awfl_saved, frames: vec![],
                        },
                    }$terminalSuffix"""
        case Done(value) =>
          s"""${terminalPrefix}Completed {
                        result: (${expr(value, block.sourceNode)}).to_plain_json()?,
                    }$terminalSuffix"""
        case ThrowError(value) =>
          s"""Err(Box::new(AwflRaisedError(${expr(value, block.sourceNode)}))
                        as Box<dyn std::error::Error>)"""
      }
      val tail = if (hasErrors && !block.op.isInstanceOf[Done] &&
                     !block.op.isInstanceOf[Invoke] &&
                     !block.op.isInstanceOf[ThrowError]) "Ok(None)" else ""
      s"""            ${block.id} => {
                    // AWFL ${block.sourceNode}
                    $code
                    $tail
                }"""
    }
    val arms = plan.blocks.map(renderBlock).mkString("\n")
    // Single terminal/effect blocks return without changing the program
    // counter; generating a loop would be both unnecessary and a Clippy
    // `never_loop` error. These are common for constant-return workflows.
    val errorRoutes = plan.blocks.flatMap { block =>
      block.onError.map { route =>
        s"""${block.id} => {
                  slots.insert(${q(route.slot)}.to_owned(), __awfl_error);
                  pc = ${route.next};
                }"""
      }
    }.mkString("\n                ")
    val extraTypes = if (hasErrors) """
#[derive(Debug)]
struct AwflRaisedError(AwflValue);
impl std::fmt::Display for AwflRaisedError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("workflow raised an error")
    }
}
impl std::error::Error for AwflRaisedError {}

fn awfl_error_value(error: &(dyn std::error::Error + 'static)) -> AwflValue {
    if let Some(explicit) = error.downcast_ref::<AwflRaisedError>() {
        return explicit.0.clone();
    }
    AwflValue::Map(BTreeMap::from([
        ("message".to_owned(), AwflValue::String(error.to_string())),
        ("code".to_owned(), AwflValue::Null),
    ]))
}
""" else ""
    val mutatesSlots = errorRoutes.nonEmpty || plan.blocks.exists(_.op match {
      case _: Done | _: Invoke | _: Test | _: ThrowError => false
      case _ => true
    })
    val slotsBinding = if (mutatesSlots) "let mut slots" else "let slots"
    val pcBinding = if (advancesPc) "let mut pc" else "let pc"
    val failureRoute = if (errorRoutes.isEmpty)
      """return Ok(Outcome::Failed { error: __awfl_error.to_plain_json()? });"""
    else s"""match pc {
                    $errorRoutes
                    _ => return Ok(Outcome::Failed {
                        error: __awfl_error.to_plain_json()?,
                    }),
                }"""
    // Per-block closure is deliberate: it confines Rust `?` propagation to
    // the statically chosen exception handler rather than terminating main.
    val generatedClippy = if (hasErrors) "#[allow(clippy::redundant_closure_call)]\n" else ""
    val dispatch = if (hasErrors) s"""for _awfl_budget in 0..1_000_000 {
        let __awfl_step: Result<Option<Outcome>, Box<dyn std::error::Error>> =
            (|| -> Result<Option<Outcome>, Box<dyn std::error::Error>> {
            match pc {
$arms
                _ => Err("invalid native CFG program counter".into()),
            }
        })();
        match __awfl_step {
            Ok(Some(outcome)) => return Ok(outcome),
            Ok(None) => {},
            Err(error) => {
                // Only explicit Raise and CEL value evaluation failures are
                // catchable. Missing/corrupt continuation state, ID overflow
                // and runtime invariant violations must not become user errors.
                if error.downcast_ref::<AwflRaisedError>().is_none() &&
                   error.downcast_ref::<awfl_native_runtime::ValueError>().is_none() {
                    return Err(error);
                }
                let __awfl_error = awfl_error_value(error.as_ref());
                $failureRoute
            },
        }
    }
    Err("native CFG instruction budget exceeded".into())"""
    else if (advancesPc) s"""for _awfl_budget in 0..1_000_000 {
        match pc {
$arms
            _ => return Err("invalid native CFG program counter".into()),
        }
    }
    Err("native CFG instruction budget exceeded".into())"""
    else s"""match pc {
$arms
            _ => Err("invalid native CFG program counter".into()),
        }"""
    s"""// Generated by AWFL Scala compiler, native ABI 3, protocol v2.
use std::io::{self, Read};
use std::collections::BTreeMap;
use awfl_native_runtime::AwflValue;
#[allow(unused_imports)]
use awfl_native_runtime::operations as ops;
#[allow(unused_imports)]
use awfl_native_runtime::protocol::{
    Request, Response, Outcome, EffectIntent, EffectErrorHandler, Continuation,
};
$extraTypes

#[allow(clippy::never_loop)]
${generatedClippy}fn run(input: &AwflValue, resume: Option<&Continuation>) -> Result<Outcome, Box<dyn std::error::Error>> {
    let _ = input;
    $slotsBinding: BTreeMap<String, AwflValue> =
        resume.map_or_else(BTreeMap::new, |c| c.slots.clone());
    // Some pure terminal CFGs need no slots; keep the schema but suppress
    // needless unused-variable warnings in the generated executable.
    let _ = &slots;
    $pcBinding = resume.map_or(${plan.entry}, |c| c.next_step);
    $dispatch
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut text = String::new();
    io::stdin().read_to_string(&mut text)?;
    let request: Request = serde_json::from_str(&text)?;
    request.validate(${plan.blocks.size}).map_err(|error|
        io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let input = AwflValue::from_plain_json(&request.input)?;
    let outcome = run(&input, request.continuation.as_ref())?;
    println!("{}", serde_json::to_string(&Response::new(outcome))?);
    Ok(())
}
""".linesIterator.map(_.stripTrailing()).mkString("\n") + "\n"
  }
}
