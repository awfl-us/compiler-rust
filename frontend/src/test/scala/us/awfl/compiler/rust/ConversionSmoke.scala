package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Existing generic operation-name constants -> real Cargo-built Rust. */
object ConversionSmoke {
  def main(args: Array[String]): Unit = {
    def converted[T: Spec](name: String, arg: String): BaseValue[T] =
      Value[T](CelFunc(name, CelConst(s"input.$arg")))
    val output = obj(Map[String, BaseValue[?]](
      "intText" -> converted[Int](CelFunctions.ToInt, "intText"),
      "intDouble" -> converted[Int](CelFunctions.ToInt, "double"),
      "stringInt" -> converted[String](CelFunctions.ToString, "integer"),
      "stringDouble" -> converted[String](CelFunctions.ToString, "double"),
      "stringBool" -> converted[String](CelFunctions.ToString, "boolean"),
      "lower" -> converted[String](CelFunctions.TextLowercase, "text")
    ))
    val program = Normalize(Workflow((List.empty[Step[?, ?]], output), Some("conversion")))
    val generated = RustEmitter.emit(program)
    assert(generated.source.contains("ops::to_int"))
    assert(generated.source.contains("ops::to_string"))
    assert(generated.source.contains("ops::text_lowercase"))
    assert(generated.manifest.contains("\"runtime_abi\":3"))
    RustEmitter.write(program, Paths.get("../fixtures/native-conversion").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)

    val invalidArity = Value[Int](CelFunc(CelFunctions.ToInt,
      CelConst("input.intText"), CelConst("input.double")))
    val diagnostic = try {
      Normalize(Workflow((List.empty[Step[?, ?]], invalidArity)))
      throw new AssertionError("expected one-argument check")
    } catch { case e: Ir.CompileError => e.getMessage }
    assert(diagnostic.contains("expects 1-1 arguments"), diagnostic)
    println("NATIVE_CONVERSION_FRONTEND_PASS")
  }
}
