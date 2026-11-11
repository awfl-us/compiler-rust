package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import ExpressionEmitter.render

/** Direct lexical Rust lowering for pure structured statements. */
object StructuredEmitter {
  private def unsupported(node: Instruction, detail: String): Nothing =
    throw CompileError(s"node=${node.nodeId} step='${node.displayName}'", detail)

  def emitStep(instruction: Instruction, nesting: Int,
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

}
