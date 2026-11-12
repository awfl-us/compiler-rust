package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Compile the original DSL's text.substring into a standalone native Rust ELF. */
object TextSubstringSmoke {
  def main(args: Array[String]): Unit = {
    val value = Value[String](text.substring(
      CelConst("input.source"), CelConst("input.start"), CelConst("input.end")))
    val program = Normalize(Workflow(
      (List.empty[Step[?, ?]], value), Some("native-text-substring")))
    val source = RustEmitter.emit(program).source
    assert(source.contains("ops::text_substring"))
    RustEmitter.write(program,
      Paths.get("../fixtures/native-text-substring").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)

    // Workflows deploy rejects missing end, even though the DSL still has
    // its old two-argument helper. We do not change that public API.
    val unsupported = Value[String](text.substring(CelStr("hello"), CelConst("1")))
    val error = try {
      Normalize(Workflow((List.empty[Step[?, ?]], unsupported)))
      throw new AssertionError("expected strict start/end arity validation")
    } catch { case e: Ir.CompileError => e.getMessage }
    assert(error.contains("expects 3-3 arguments"), error)
    println("NATIVE_TEXT_SUBSTRING_SMOKE_PASS")
  }
}
