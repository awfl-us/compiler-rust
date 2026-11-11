package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import java.nio.file.Paths

/** Integration fixture: actual DSL trees become executable native Rust.
  * The Python integration harness builds and executes each generated crate.
  */
object ControlFlowSmoke {
  def main(args: Array[String]): Unit = {
    val base = Paths.get("../fixtures").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize

    val input = Value[Int]("input")
    val branch = Switch("choice", List(
      (input.cel > CelInteger(4)) -> (List.empty[Step[?, ?]] -> obj(100)),
      CelBoolean(true) -> (List.empty[Step[?, ?]] -> obj(200))
    ))
    val switchFlow = Workflow((List(branch), branch.resultValue), Some("switch"))
    val switchIR = Normalize(switchFlow)
    assert(switchIR.steps.head.isInstanceOf[Choose])
    assert(switchIR.nodeIds == Vector("0.0", "0.return"))
    RustEmitter.write(switchIR, base.resolve("native-switch"), runtime)

    val unmatched = Switch("no-match", List(
      (input.cel > CelInteger(10)) -> (List.empty[Step[?, ?]] -> obj(100))
    ))
    RustEmitter.write(Normalize(Workflow((List(unmatched), unmatched.resultValue), Some("no-match"))),
      base.resolve("native-unmatched"), runtime)

    val range = ForRange[Int]("range", CelInteger(-2), CelInteger(2)) { i =>
      List.empty[Step[?, ?]] -> Value[Int](i + CelInteger(10))
    }
    val rangeIR = Normalize(Workflow((List(range), range.resultValue), Some("range")))
    assert(rangeIR.steps.head.isInstanceOf[RepeatRange])
    RustEmitter.write(rangeIR, base.resolve("native-range"), runtime)

    val iterable = us.awfl.dsl.ListValue[Int](CelConst("input"))
    val each = us.awfl.dsl.For[Int, Int]("each", iterable) { item =>
      List.empty[Step[?, ?]] -> Value[Int](item.cel + CelInteger(3))
    }
    val eachIR = Normalize(Workflow((List(each), each.resultValue), Some("each")))
    assert(eachIR.steps.head.isInstanceOf[RepeatEach])
    RustEmitter.write(eachIR, base.resolve("native-each"), runtime)

    val nested = ForRange[Int]("outer", CelInteger(0), CelInteger(4)) { index =>
      val choose = Switch("inner", List(
        (index > CelInteger(1)) -> (List.empty[Step[?, ?]] -> obj(9)),
        CelBoolean(true) -> (List.empty[Step[?, ?]] -> Value[Int](index + CelInteger(2)))
      ))
      List(choose) -> choose.resultValue
    }
    val nestedIR = Normalize(Workflow((List(nested), nested.resultValue), Some("nested")))
    assert(nestedIR.nodeIds == Vector("0.0", "0.0.each.0", "0.return"))
    RustEmitter.write(nestedIR, base.resolve("native-nested"), runtime)

    val fold = Fold[Int, Int]("sum", obj(10), us.awfl.dsl.ListValue[Int](CelConst("input"))) {
      (acc, item) =>
        List.empty[Step[?, ?]] -> Value[Int](acc.cel + item.cel)
    }
    val foldIR = Normalize(Workflow((List(fold), fold.resultValue), Some("fold")))
    assert(foldIR.steps.head.isInstanceOf[ReduceEach])
    assert(foldIR.nodeIds == Vector("0.0", "0.return"))
    RustEmitter.write(foldIR, base.resolve("native-fold"), runtime)

    // Explicit Return exits the workflow even from inside a nested branch.
    val early = Switch("early", List(
      (input.cel > CelInteger(0)) ->
        (List[Step[?, ?]](Return("stop", obj(777))) -> obj(0)),
      CelBoolean(true) -> (List.empty[Step[?, ?]] -> obj(42))
    ))
    val earlyIR = Normalize(Workflow((List(early), early.resultValue), Some("early-return")))
    assert(earlyIR.nodeIds.contains("0.0.case0.0"))
    RustEmitter.write(earlyIR, base.resolve("native-early-return"), runtime)

    // A top-level Return precedes the implicit terminal DSL result.
    val topReturn = Return("stop", obj(55))
    RustEmitter.write(Normalize(Workflow((List[Step[?, ?]](topReturn), obj(99)),
      Some("top-level-return"))), base.resolve("native-top-return"), runtime)

    // Even a conditional effect must be rejected, not hidden in unselected branches.
    val effect = TypedCall[Map[String, String], String](
      "request", CallId.HttpPost, obj(Map("url" -> "https://example.org")))
    val unsafe = Switch("unsafe", List(
      CelBoolean(false) -> (List(effect) -> effect.resultValue),
      CelBoolean(true) -> (List.empty[Step[?, ?]] -> obj("safe"))
    ))
    val denied = try {
      RustEmitter.emit(Normalize(Workflow((List(unsafe), unsafe.resultValue))))
      throw new AssertionError("nested external effect was not rejected")
    } catch {
      case e: CompileError => e.getMessage
    }
    assert(denied.contains("nested effect"))

    // A preceding compiled branch is checkpointed once and restored on resume.
    val beforeEffect = Switch("before-effect", List(
      (input.cel > CelInteger(0)) -> (List.empty[Step[?, ?]] -> obj(8)),
      CelBoolean(true) -> (List.empty[Step[?, ?]] -> obj(3))
    ))
    val topEffect = TypedCall[Map[String, String], String](
      "external", CallId.HttpPost, obj(Map("url" -> "https://example.org")))
    val sequence = Workflow((List[Step[?, ?]](beforeEffect, topEffect),
      topEffect.resultValue), Some("resume-after-branch"))
    RustEmitter.write(Normalize(sequence), base.resolve("native-resume"), runtime)

    val firstCall = TypedCall[Map[String, String], String](
      "first", CallId.HttpPost, obj(Map("url" -> "https://example.org/first")))
    val secondCall = TypedCall[Map[String, String], String](
      "second", CallId.HttpPost, obj(Map("url" -> "https://example.org/second")))
    RustEmitter.write(Normalize(Workflow((List[Step[?, ?]](firstCall, secondCall),
      secondCall.resultValue), Some("two-effects"))),
      base.resolve("native-two-effects"), runtime)

    // A negative-based range tests ordinal iteration IDs, durable prefix
    // results and step-zero resumptions across real native subprocesses.
    val effectRange = ForRange[String]("effect-range", CelInteger(-2), CelInteger(1)) { _ =>
      val nestedCall = TypedCall[Map[String, String], String](
        "nested-call", CallId.HttpPost, obj(Map("url" -> "https://example.org/range")))
      List[Step[?, ?]](nestedCall) -> nestedCall.resultValue
    }
    val rangeProgram = Normalize(Workflow((List[Step[?, ?]](effectRange), effectRange.resultValue),
      Some("range-effects")))
    assert(rangeProgram.effects.size == 1)
    val rangeGenerated = RustEmitter.write(rangeProgram, base.resolve("native-range-effect"), runtime)
    assert(rangeGenerated.source.contains("invalid native range continuation prefix"))
    assert(rangeGenerated.source.contains("call_0_"))

    // Multiple effect sites cannot share the same per-iteration frame.
    val doubleRange = ForRange[String]("double-range", CelInteger(0), CelInteger(2)) { _ =>
      val a = TypedCall[Map[String, String], String](
        "a", CallId.HttpPost, obj(Map("url" -> "https://example.org/a")))
      val b = TypedCall[Map[String, String], String](
        "b", CallId.HttpPost, obj(Map("url" -> "https://example.org/b")))
      List[Step[?, ?]](a, b) -> b.resultValue
    }
    val rejectedMultiple = try {
      RustEmitter.emit(Normalize(Workflow((List[Step[?, ?]](doubleRange), doubleRange.resultValue))))
      throw new AssertionError("multiple nested Calls were not rejected")
    } catch { case e: CompileError => e.getMessage }
    assert(rejectedMultiple.contains("exactly one direct Call"))

    println("Scala control-flow fixtures passed")
  }
}
