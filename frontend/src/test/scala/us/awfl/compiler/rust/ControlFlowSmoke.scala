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
    assert(RustEmitter.emit(effectChoice).manifest.contains("\"runtime_abi\":3"))

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
    assert(RustEmitter.emit(nestedChoiceProgram).manifest.contains("\"runtime_abi\":3"))

    // A third nested Switch is supported by compiled CFG labels; no replay
    // frame templates or depth-specific backends are required.
    val tooDeep = Switch("third-depth", List(
      CelBoolean(true) -> (List[Step[?, ?]](outerChoice) -> outerChoice.resultValue)
    ))
    val deepIR = Normalize(Workflow((List[Step[?, ?]](tooDeep),
      tooDeep.resultValue), Some("third-depth-effects")))
    assert(deepIR.effects.size == 3)
    assert(RustEmitter.emit(deepIR).manifest.contains("\"runtime_abi\":3"))

    // A preceding compiled branch is checkpointed once and restored on resume.
    val beforeEffect = Switch("before-effect", List(
      (input.cel > CelInteger(0)) -> (List.empty[Step[?, ?]] -> obj(8)),
      CelBoolean(true) -> (List.empty[Step[?, ?]] -> obj(3))
    ))
    val topEffect = TypedCall[Map[String, String], String](
      "external", CallId.HttpPost, obj(Map("url" -> "https://example.org")))
    val sequence = Workflow((List[Step[?, ?]](beforeEffect, topEffect),
      topEffect.resultValue), Some("resume-after-branch"))
    assert(RustEmitter.emit(Normalize(sequence)).manifest.contains("\"runtime_abi\":3"))

    val firstCall = TypedCall[Map[String, String], String](
      "first", CallId.HttpPost, obj(Map("url" -> "https://example.org/first")))
    val secondCall = TypedCall[Map[String, String], String](
      "second", CallId.HttpPost, obj(Map("url" -> "https://example.org/second")))
    assert(RustEmitter.emit(Normalize(Workflow((List[Step[?, ?]](firstCall, secondCall),
      secondCall.resultValue), Some("two-effects")))).manifest.contains("\"runtime_abi\":3"))

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
    assert(RustEmitter.emit(rangeProgram).source.contains("__awfl_effect_id"))

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
    assert(RustEmitter.emit(multiRange).manifest.contains("\"runtime_abi\":3"))

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
    assert(RustEmitter.emit(nestedRangeIR).manifest.contains("\"runtime_abi\":3"))

    // Mixed effectful outer steps and nested ranges use a single compiled CFG.
    val mixedNestedRange = ForRange[String]("mixed-outer-range",
      CelInteger(0), CelInteger(1)) { _ =>
      val unrelated = TypedCall[Map[String, String], String](
        "unrelated", CallId.HttpPost, obj(Map("url" -> "https://example.org/extra")))
      List[Step[?, ?]](nestedRange, unrelated) -> nestedRange.resultValue
    }
    val mixedNestedIR = Normalize(Workflow((List[Step[?, ?]](mixedNestedRange),
      mixedNestedRange.resultValue), Some("mixed-nested-range")))
    assert(mixedNestedIR.effects.size == 3)
    assert(RustEmitter.emit(mixedNestedIR).manifest.contains("\"runtime_abi\":3"))

    // ABI 3 no longer needs a separate continuation template for each loop
    // type. Exercise effectful For/Fold and a Switch inside a ForRange.
    val effectfulEach = us.awfl.dsl.For[String, String]("each-effects",
      us.awfl.dsl.ListValue[String](CelConst("input"))) { item =>
      val call = TypedCall[String, String]("each-call", CallId.HttpPost, item)
      List[Step[?, ?]](call) -> call.resultValue
    }
    val eachEffectsIR = Normalize(Workflow((List[Step[?, ?]](effectfulEach),
      effectfulEach.resultValue), Some("each-effects")))
    assert(eachEffectsIR.effects.size == 1)

    val effectfulFold = Fold[String, String]("fold-effects", obj("start"),
      us.awfl.dsl.ListValue[String](CelConst("input"))) { (acc, _) =>
      val call = TypedCall[String, String]("fold-call", CallId.HttpPost, acc)
      List[Step[?, ?]](call) -> call.resultValue
    }
    val foldEffectsIR = Normalize(Workflow((List[Step[?, ?]](effectfulFold),
      effectfulFold.resultValue), Some("fold-effects")))
    assert(foldEffectsIR.effects.size == 1)

    val mixedRange = ForRange[String]("range-with-branch",
      CelInteger(0), CelInteger(2)) { index =>
      val branchCall = TypedCall[Map[String, String], String](
        "branch-call", CallId.HttpPost, obj(Map("url" -> "https://example.org/range-branch")))
      val choose = Switch("choice-in-range", List(
        (index > CelInteger(0)) -> (List[Step[?, ?]](branchCall) -> branchCall.resultValue),
        CelBoolean(true) -> (List.empty[Step[?, ?]] -> obj("skipped"))
      ))
      List[Step[?, ?]](choose) -> choose.resultValue
    }
    val mixedRangeIR = Normalize(Workflow((List[Step[?, ?]](mixedRange),
      mixedRange.resultValue), Some("range-switch-effects")))
    assert(mixedRangeIR.effects.size == 1)

    // Every generated native workflow shares one ABI 3/protocol 2 contract.
    // Its explicit CFG resumes directly after effects, with no replay frames.
    val v2Fixtures = Vector(
      "native-v2-switch-effects" -> effectChoice,
      "native-v2-nested-switch-effects" -> nestedChoiceProgram,
      "native-v2-range-two-effects" -> multiRange,
      "native-v2-nested-range-effects" -> nestedRangeIR,
      "native-v2-each" -> eachIR,
      "native-v2-fold" -> foldIR,
      "native-v2-each-effects" -> eachEffectsIR,
      "native-v2-fold-effects" -> foldEffectsIR,
      "native-v2-range-switch-effects" -> mixedRangeIR,
      "native-v2-pure-nested" -> nestedIR,
      "native-v2-deep-switch-effects" -> deepIR,
      "native-v2-mixed-nested-range" -> mixedNestedIR
    )
    v2Fixtures.foreach { (name, workflow) =>
      val emitted = RustEmitter.write(workflow, base.resolve(name), runtime)
      assert(emitted.manifest.contains("\"runtime_abi\":3"))
      assert(emitted.source.contains("native ABI 3"))
      assert(emitted.source.contains("request.validate("))
    }

    println("Scala control-flow fixtures passed")
  }
}
