package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Compile the existing Scala standard-library API to real native Rust. */
object TimeFormatSmoke {
  def main(args: Array[String]): Unit = {
    val from = CelConst("input.timestamp")
    val default = Value[String](time.format(from))
    val zone = Value[String](time.format(from, CelConst("input.zone")))
    val output = obj(Map[String, BaseValue[?]]("utc" -> default, "zoned" -> zone))
    val program = Normalize(Workflow(
      (List.empty[Step[?, ?]], output), Some("native-time-format")))
    val source = RustEmitter.emit(program).source
    assert(source.contains("ops::time_format"))
    RustEmitter.write(program,
      Paths.get("../fixtures/native-time-format").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)

    val bad = Value[String](CelFunc(CelFunctions.Time.Format))
    val error = try {
      Normalize(Workflow((List.empty[Step[?, ?]], bad)))
      throw new AssertionError("expected strict time.format arity validation")
    } catch { case e: Ir.CompileError => e.getMessage }
    assert(error.contains("expects 1-2 arguments"), error)
    println("NATIVE_TIME_FORMAT_SMOKE_PASS")
  }
}
