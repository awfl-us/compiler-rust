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

    // A selected branch is pinned across subprocess restarts. Its second
    // Call depends on the first; the other branch has an independent Call.
    val branchFirst = TypedCall[Map[String, String], String](
      "first-branch", CallId.HttpPost, obj(Map("url" -> "https://example.org/branch-a")))
    val branchSecond = TypedCall[String, String](
      "second-branch", CallId.HttpPost, branchFirst.resultValue)
    val otherBranch = TypedCall[Map[String, String], String](
      "other-branch", CallId.HttpPost, obj(Map("url" -> "https://example.org/branch-b")))
    val choiceEffects = Switch("effects-switch", List(
      (input.cel > CelInteger(0)) ->
        (List[Step[?, ?]](branchFirst, branchSecond) -> branchSecond.resultValue),
      (input.cel < CelInteger(0)) ->
        (List[Step[?, ?]](otherBranch) -> otherBranch.resultValue)
    ))
    val effectChoice = Normalize(Workflow((List[Step[?, ?]](choiceEffects),
      choiceEffects.resultValue), Some("effectful-switch")))
    assert(effectChoice.effects.size == 3)
    val choiceGenerated = RustEmitter.write(
      effectChoice, base.resolve("native-switch-effects"), runtime)
    assert(choiceGenerated.source.contains("invalid native Switch continuation site"))

    // Nested Switch: both branch choices are pinned while a dependent second
    // Call resumes; unrelated outer branches do not dispatch effects.
    val deepFirst = TypedCall[Map[String, String], String](
      "deep-first", CallId.HttpPost, obj(Map("url" -> "https://example.org/deep-a")))
    val deepSecond = TypedCall[String, String](
      "deep-second", CallId.HttpPost, deepFirst.resultValue)
    val deepAlternate = TypedCall[Map[String, String], String](
      "deep-alternate", CallId.HttpPost, obj(Map("url" -> "https://example.org/deep-b")))
    val innerChoice = Switch("inner-choice", List(
      (input.cel > CelInteger(4)) ->
        (List[Step[?, ?]](deepFirst, deepSecond) -> deepSecond.resultValue),
      CelBoolean(true) ->
        (List[Step[?, ?]](deepAlternate) -> deepAlternate.resultValue)
    ))
    val outerChoice = Switch("outer-choice", List(
      (input.cel > CelInteger(0)) ->
        (List[Step[?, ?]](innerChoice) -> innerChoice.resultValue),
      CelBoolean(true) ->
        (List.empty[Step[?, ?]] -> obj("no-effect"))
    ))
    val nestedChoiceProgram = Normalize(Workflow((List[Step[?, ?]](outerChoice),
      outerChoice.resultValue), Some("nested-switch-effects")))
    assert(nestedChoiceProgram.effects.size == 3)
    val nestedChoiceSource = RustEmitter.write(nestedChoiceProgram,
      base.resolve("native-nested-switch-effects"), runtime)
    assert(nestedChoiceSource.source.contains("invalid nested Switch continuation site"))
    assert(nestedChoiceSource.source.contains("state.frames.len() != 3"))

    // A third branch depth must fail closed until the frame path is lowered.
    val tooDeep = Switch("third-depth", List(
      CelBoolean(true) -> (List[Step[?, ?]](outerChoice) -> outerChoice.resultValue)
    ))
    val denied = try {
      RustEmitter.emit(Normalize(Workflow((List[Step[?, ?]](tooDeep),
        tooDeep.resultValue))))
      throw new AssertionError("third nested Switch was not rejected")
    } catch { case e: CompileError => e.getMessage }
    assert(denied.contains("nested effectful Switch branches require only direct Calls"))

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

    // Multiple direct effect sites have distinct structural IDs, even
    // inside the same iteration. The second Call depends on the first.
    val doubleRange = ForRange[String]("double-range", CelInteger(-1), CelInteger(1)) { _ =>
      val a = TypedCall[Map[String, String], String](
        "a", CallId.HttpPost, obj(Map("url" -> "https://example.org/a")))
      val b = TypedCall[String, String](
        "b", CallId.HttpPost, a.resultValue)
      List[Step[?, ?]](a, b) -> b.resultValue
    }
    val multiRange = Normalize(Workflow((List[Step[?, ?]](doubleRange), doubleRange.resultValue),
      Some("range-two-effects")))
    assert(multiRange.effects.size == 2)
    val multiGenerated = RustEmitter.write(multiRange, base.resolve("native-range-two-effects"), runtime)
    assert(multiGenerated.source.contains("invalid native range continuation site"))
    assert(multiGenerated.source.contains("state.frames.len() != 2"))

    // A real effectful nested range retains completed outer and inner
    // prefixes, and resumes a dependent second Call within the same inner
    // iteration. The negative outer bound tests ordinal (not raw) IDs.
    val nestedRange = ForRange[String]("outer-effect-range",
      CelInteger(-1), CelInteger(1)) { _ =>
      val innerRange = ForRange[String]("inner-effect-range",
        CelInteger(0), CelInteger(2)) { _ =>
        val first = TypedCall[Map[String, String], String](
          "inner-first", CallId.HttpPost, obj(Map("url" -> "https://example.org/nested-range")))
        val second = TypedCall[String, String](
          "inner-second", CallId.HttpPost, first.resultValue)
        List[Step[?, ?]](first, second) -> second.resultValue
      }
      List[Step[?, ?]](innerRange) -> innerRange.resultValue
    }
    val nestedRangeIR = Normalize(Workflow((List[Step[?, ?]](nestedRange),
      nestedRange.resultValue), Some("nested-range-effects")))
    assert(nestedRangeIR.effects.size == 2)
    val nestedRangeSource = RustEmitter.write(nestedRangeIR,
      base.resolve("native-nested-range-effects"), runtime)
    assert(nestedRangeSource.source.contains("invalid nested range continuation site"))
    assert(nestedRangeSource.source.contains("__awfl_loop_0_inner_items"))

    // Additional steps in the outer effectful range are still unsupported.
    val invalidNestedRange = ForRange[String]("mixed-outer-range",
      CelInteger(0), CelInteger(1)) { _ =>
      val unrelated = TypedCall[Map[String, String], String](
        "unrelated", CallId.HttpPost, obj(Map("url" -> "https://example.org/extra")))
      List[Step[?, ?]](nestedRange, unrelated) -> nestedRange.resultValue
    }
    val deniedMixedRange = try {
      RustEmitter.emit(Normalize(Workflow((List[Step[?, ?]](invalidNestedRange),
        invalidNestedRange.resultValue))))
      throw new AssertionError("mixed nested range effects were not rejected")
    } catch { case e: CompileError => e.getMessage }
    assert(deniedMixedRange.contains("exactly one inner ForRange"))

    println("Scala control-flow fixtures passed")
  }
}
