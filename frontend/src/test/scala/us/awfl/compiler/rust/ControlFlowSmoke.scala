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
    assert(denied.contains("continuation"))

    println("Scala control-flow fixtures passed")
  }
}
