package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Pure Google CEL operators compiled from the unchanged CelOp DSL node. */
object CelOperatorsSmoke {
  def main(args: Array[String]): Unit = {
    val data = obj(Map[String, BaseValue[?]](
      "positiveFloor" -> Value[Int](CelOp(CelConst("5"), "//", CelConst("2"))),
      "negativeFloor" -> Value[Int](CelOp(CelConst("-5"), "//", CelConst("2"))),
      "negativeDivisorFloor" -> Value[Int](CelOp(CelConst("5"), "//", CelConst("-2"))),
      "bothNegative" -> Value[Int](CelOp(CelConst("-5"), "//", CelConst("-2"))),
      "runtimeFloor" -> Value[Int](CelOp(CelConst("-9"), "//", CelConst("input"))),
      "listMember" -> Value[Boolean](CelOp(CelConst("input"), "in",
        CelValue(obj(List(1, 2, 3))))),
      "nestedListMember" -> Value[Boolean](CelOp(
        CelValue(obj(List(3))), "in", CelValue(obj(List(List(1), List(3)))))),
      "mapMember" -> Value[Boolean](CelOp(CelStr("alpha"), "in",
        CelValue(obj(Map("alpha" -> 1, "beta" -> 2))))),
      "mapMissing" -> Value[Boolean](CelOp(CelStr("missing"), "in",
        CelValue(obj(Map("alpha" -> 1)))))
    ))
    val program = Normalize(Workflow((List.empty[Step[?, ?]], data),
      Some("cel-operators")))
    val emitted = RustEmitter.write(program,
      Paths.get("../fixtures/native-cel-operators").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)
    assert(emitted.source.contains("ops::contains"))
    assert(emitted.source.contains("ops::floor_divide"))
    assert(emitted.manifest.contains("\"runtime_abi\":3"))
    println("Original CEL membership and floor-division operators compiled to Rust")
  }
}
