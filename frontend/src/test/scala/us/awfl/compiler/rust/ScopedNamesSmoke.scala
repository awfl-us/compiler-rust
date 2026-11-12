package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.compiler.rust.Ir.*

/** Repeated Scala step labels and hash-derived DSL symbols are valid.
  * Each binding has its own slot; references are resolved lexically.
  */
object ScopedNamesSmoke {
  def main(args: Array[String]): Unit = {
    val first = Call[Map[String, String], String]("request", CallOperations.HttpPost,
      obj(Map("url" -> "https://example.org/a")))
    val second = Call[Map[String, String], String]("request", CallOperations.HttpPost,
      obj(Map("url" -> "https://example.org/b")))
    val dependent = Call[String, String]("request", CallOperations.HttpPost,
      second.resultValue)
    val pair = Normalize(Workflow(
      (List[Step[?, ?]](first, second, dependent), dependent.resultValue)))
    assert(pair.effects.map(_.nodeId) == Vector("0.0", "0.1", "0.2"))
    assert(pair.effects.map(_.displayName) == Vector("request", "request_1", "request_2"))
    assert(pair.effects.map(_.resultSlot).distinct.size == 3)
    assert(pair.effects.last.arguments == SlotRef(pair.effects(1).resultSlot))
    RustEmitter.emit(pair)

    // Same case-class equality/hash => identical DSL result symbols.
    // The second occurrence shadows the first without reusing its slot.
    val repeated = Call[Map[String, String], String]("same", CallOperations.HttpGet,
      obj(Map("url" -> "https://example.org/same")))
    val identical = Normalize(Workflow(
      (List[Step[?, ?]](repeated, repeated), repeated.resultValue)))
    assert(identical.effects.map(_.resultSlot).distinct.size == 2)
    assert(identical.steps.last.asInstanceOf[Finish].value ==
      SlotRef(identical.effects.last.resultSlot))
    RustEmitter.emit(identical)

    // The same step object used in two separate lexical branches binds its
    // result locally, rather than leaking a binding to the sibling branch.
    val sameCall = Call[Map[String, String], String]("nested", CallOperations.HttpGet,
      obj(Map("url" -> "https://example.org/nested")))
    val choice = Switch("choice", List(
      CelConst("true") -> (List[Step[?, ?]](sameCall) -> sameCall.resultValue),
      CelConst("false") -> (List[Step[?, ?]](sameCall) -> sameCall.resultValue)
    ))
    val branchProgram = Normalize(Workflow(
      (List[Step[?, ?]](choice), choice.resultValue)))
    val cases = branchProgram.steps.head.asInstanceOf[Choose].cases
    val left = cases(0).body.head.asInstanceOf[Effect]
    val right = cases(1).body.head.asInstanceOf[Effect]
    assert(left.resultSlot != right.resultSlot)
    assert(cases(0).result == SlotRef(left.resultSlot))
    assert(cases(1).result == SlotRef(right.resultSlot))
    RustEmitter.emit(branchProgram)

    // Reused Try names generate exactly the same public "checkError" symbol,
    // but each Try has a distinct native error slot and result slot.
    def recovering = Try("check",
      List.empty[Step[?, ?]] -> str(CelStr("good")),
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    val one = recovering
    val two = recovering
    val tries = Normalize(Workflow(
      (List[Step[?, ?]](one, two), two.resultValue)))
    val rescues = tries.steps.take(2).map(_.asInstanceOf[Rescue])
    assert(rescues(0).errorSlot != rescues(1).errorSlot)
    assert(rescues(0).resultSlot != rescues(1).resultSlot)
    assert(rescues(0).recovery == Field(SlotRef(rescues(0).errorSlot), "message"))
    assert(rescues(1).recovery == Field(SlotRef(rescues(1).errorSlot), "message"))
    assert(tries.steps.last.asInstanceOf[Finish].value == SlotRef(rescues(1).resultSlot))
    RustEmitter.emit(tries)

    // A nested loop iterator shadows its parent's symbol, and restores it
    // after the inner loop is normalized.
    val outer = ForRange[Int]("index", 0, 2) { outerIdx =>
      val inner = ForRange[Int]("index", 0, 2) { innerIdx =>
        List.empty[Step[?, ?]] -> Value[Int](innerIdx)
      }
      List[Step[?, ?]](inner) -> Value[Int](outerIdx)
    }
    val loops = Normalize(Workflow((List[Step[?, ?]](outer), outer.resultValue)))
    val outerRange = loops.steps.head.asInstanceOf[RepeatRange]
    val innerRange = outerRange.body.head.asInstanceOf[RepeatRange]
    assert(outerRange.indexSlot != innerRange.indexSlot)
    assert(outerRange.result == SlotRef(outerRange.indexSlot))
    assert(innerRange.result == SlotRef(innerRange.indexSlot))
    RustEmitter.emit(loops)
    assert(Normalize(Workflow((List[Step[?, ?]](outer), outer.resultValue))) == loops)

    // Produce a real Rust binary; effects with the same user-facing name
    // retain distinct continuation slots across two process restarts.
    val executable = Normalize(Workflow(
      (List[Step[?, ?]](first, second),
        obj(Map("first" -> first.resultValue, "second" -> second.resultValue))),
      Some("scoped-step-values")))
    val runtime = java.nio.file.Paths.get("../runtime").toAbsolutePath.normalize
    val target = java.nio.file.Paths.get("../fixtures/native-scoped-names")
      .toAbsolutePath.normalize
    RustEmitter.write(executable, target, runtime)
    println("SCOPED_STEP_RESULT_SYMBOLS_PASS")
  }
}
