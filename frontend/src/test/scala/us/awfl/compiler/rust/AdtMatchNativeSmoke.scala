package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.auto.given
import java.nio.file.Paths

// Mirror both real wip-old utility shapes: constructor-field binders in
// Locks.buildSessionKey, and typed binders in TopicContextYoj.encodeKalaVal.
sealed trait MatchKala extends IsAdt
case class MatchSegKala(sessionId: Value[String], end: Value[Double], windowSeconds: Value[Double]) extends MatchKala
case class MatchSessionKala(sessionId: Value[String], sessionEnd: Value[Double]) extends MatchKala
case class MatchWeekKala(weekEnd: Value[Double]) extends MatchKala
case class MatchTermKala(begin: Value[Double], end: Value[Double]) extends MatchKala

object AdtMatchNativeSmoke:
  def sessionKey(k: Value[MatchKala]): Step[String, Value[String]] =
    Match("matchId", k) {
      case MatchSegKala(sessionId, _, _) => List() -> sessionId
      case MatchSessionKala(sessionId, _) => List() -> sessionId
      case MatchWeekKala(_) => List() -> str("global")
      case MatchTermKala(_, _) => List() -> str("global")
    }

  def toKind(k: Value[MatchKala]): Step[String, Value[String]] =
    Match("encodeKalaVal", k) {
      case seg: MatchSegKala => List() -> str(seg.sessionId.cel + CelStr("@segment"))
      case sesh: MatchSessionKala => List() -> str(sesh.sessionId.cel + CelStr("@session"))
      case week: MatchWeekKala => List() -> str(CelStr("week:" ) + week.weekEnd.cel)
      case term: MatchTermKala => List() -> str(CelStr("term:" ) + term.end.cel)
    }

  // Prove that a workflow effect in one branch never leaks into another.
  def effectMatch(k: Value[MatchKala]): Step[String, Value[String]] =
    Match("matchEffects", k) {
      case seg: MatchSegKala =>
        val effect = Call[Map[String, String], String](
          "selectedEffect", CallOperations.HttpPost,
          obj(Map("url" -> "https://example.org/adt-matching")))
        List[Step[?, ?]](effect) -> effect.resultValue
      case MatchSessionKala(_, _) => List() -> str("without-effect")
      case MatchWeekKala(_) => List() -> str("without-effect")
      case MatchTermKala(_, _) => List() -> str("without-effect")
    }

  // Original source-order semantics: first matching candidate wins, and a
  // wildcard pattern is a deliberate total match rather than an error.
  def priorityMatch(k: Value[MatchKala]): Step[String, Value[String]] =
    Match("matchPriority", k) {
      case MatchWeekKala(_) => List() -> str("specific-first")
      case _ => List() -> str("fallback")
    }

  def main(args: Array[String]): Unit =
    val base = Paths.get("../fixtures").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val in = Value[MatchKala]("input")
    for (name, build) <- List[(String, Value[MatchKala] => Step[String, Value[String]])](
      "adt-match-session" -> sessionKey,
      "adt-match-typed" -> toKind
    ) do
      val step = build(in)
      val result = Normalize(Workflow((List[Step[?, ?]](step), step.resultValue), Some(name)))
      assert(result.steps.nonEmpty)
      assert(result.effects.isEmpty)
      val generated = RustEmitter.write(result, base.resolve("native-" + name), runtime)
      assert(generated.source.contains("ops::json_encode_string"))
      println(s"DSL_ADT_NATIVE_GENERATED:$name")

    val effectStep = effectMatch(in)
    val effectFlow = Normalize(Workflow(
      (List[Step[?, ?]](effectStep), effectStep.resultValue), Some("adt-match-effects")))
    assert(effectFlow.effects.size == 1)
    RustEmitter.write(effectFlow, base.resolve("native-adt-match-effects"), runtime)
    println("DSL_ADT_NATIVE_GENERATED:adt-match-effects")

    val priority = priorityMatch(in)
    val priorityFlow = Normalize(Workflow((List[Step[?, ?]](priority),
      priority.resultValue), Some("adt-match-priority")))
    RustEmitter.write(priorityFlow, base.resolve("native-adt-match-priority"), runtime)
    println("DSL_ADT_NATIVE_GENERATED:adt-match-priority")
