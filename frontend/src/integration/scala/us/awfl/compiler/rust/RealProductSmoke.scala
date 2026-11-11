package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Compiles the *unchanged* Product.scala combinator from awfl-us/workflows.
  * It combines original DSL Try, Fold, For, Switch and join helpers.
  */
object RealProductSmoke {
  def main(args: Array[String]): Unit = {
    // Only the exact names internally emitted by the current DSL helpers
    // are special-cased; arbitrary textual CEL remains rejected.
    assert(FunctionId.resolve("len").contains(FunctionId.Length))
    assert(FunctionId.resolve("default").contains(FunctionId.Default))
    assert(FunctionId.resolve("json.encode_to_string")
      .contains(FunctionId.JsonEncodeString))
    assert(CallId.resolve("sys.log").contains(CallId.LogWrite))
    assert(FunctionId.resolve("google.auth.fake").isEmpty)
    assert(CallId.resolve("googleapis.workflows.fake").isEmpty)
    val root = Paths.get("../fixtures/real-product").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    def color(values: String) = ListValue[String](CelConst(values))
    def exercise(name: String, vars: Map[String, ListValue[String]]): Unit = {
      val product = us.awfl.workflows.evals.Product(vars)
      val program = Normalize(Workflow((List[Step[?, ?]](product), product.resultValue),
        Some(s"real-evals-product-$name")))
      val plan = ControlFlowLowering(program)
      assert(plan.blocks.exists(_.onError.nonEmpty),
        s"$name: original DSL Try must retain a recoverable error edge")
      assert(program.effects.exists(_.id == CallId.LogWrite),
        s"$name: built-in error logging must remain a real effect")
      val result = RustEmitter.write(program, root.resolve(name), runtime)
      assert(result.manifest.contains("\"runtime_abi\":3"))
      assert(result.manifest.contains("log.write"))
      assert(result.source.contains("ops::map_merge") || vars.isEmpty,
        s"$name: generated Rust must compile map merging, not skip it")
      println(s"REAL_PRODUCT_NATIVE_$name=${root.resolve(name)}")
    }
    exercise("single", Map("color" -> color("[\"red\",\"blue\"]")))
    exercise("cartesian", Map(
      "color" -> color("[\"red\",\"blue\"]"),
      "size" -> color("[\"S\",\"M\"]")
    ))
    exercise("empty", Map("color" -> color("[]")))
    println("Unchanged Product.scala compiled to three ABI 3 Rust executables")
  }
}
