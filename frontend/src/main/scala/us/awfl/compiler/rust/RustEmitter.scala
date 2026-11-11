package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import java.nio.file.Path

/** One native backend: a statically compiled CFG with ABI 3 / IPC v2.
  * No interpreted YAML or alternate compiler/runtime protocol.
  */
object RustEmitter {
  final case class Emitted(source: String, manifest: String)

  def emit(program: Program): Emitted = {
    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError("workflow=" + program.name,
        "workflow requires exactly one terminal return after control flow")
    ArtifactWriter.pack(program, StateMachineEmitter.source(program))
  }

  def write(program: Program, directory: Path, runtime: Path): Emitted =
    ArtifactWriter.write(directory, runtime, emit(program))
}
