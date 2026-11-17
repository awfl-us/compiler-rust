package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Proves the public text.split DSL lowers to a supported native function. */
object TextSplitSmoke {
  def main(args: Array[String]): Unit = {
    val value = Value[List[String]](text.split(
      CelConst("input.source"), CelConst("input.separator")))
    val program = Normalize(Workflow(
      (List.empty[Step[?, ?]], value), Some("native-text-split")))
    val source = RustEmitter.emit(program).source
    assert(source.contains("ops::text_split"), source)

    RustEmitter.write(program,
      Paths.get("../fixtures/native-text-split").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)

    val malformed = Value[List[String]](CelFunc("text.split", CelStr("only-one")))
    val error = try {
      Normalize(Workflow((List.empty[Step[?, ?]], malformed)))
      throw new AssertionError("text.split must reject wrong arity")
    } catch { case e: Ir.CompileError => e.getMessage }
    assert(error.contains("expects 2-2 arguments"), error)
    println("NATIVE_TEXT_SPLIT_SMOKE_PASS")
  }
}
