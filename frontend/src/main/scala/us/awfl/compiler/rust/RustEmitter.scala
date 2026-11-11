package us.awfl.compiler.rust

import us.awfl.compiler.rust.Ir.*
import java.nio.file.Path

/** Stable public facade. ABI 2 remains backward compatible while the
  * generalized control-flow backend is introduced separately.
  */
object RustEmitter {
  final case class Emitted(source: String, manifest: String)

  def emit(program: Program): Emitted = {
    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError("workflow=" + program.name,
        "workflow requires exactly one terminal return after control flow")
    val source =
      if (program.effects.nonEmpty) LegacyEffectEmitter.effectfulSource(program)
      else PureRustEmitter.source(program)
    ArtifactWriter.pack(program, source)
  }

  def write(program: Program, directory: Path, runtime: Path): Emitted =
    ArtifactWriter.write(directory, runtime, emit(program))

  /** Explicit opt-in to the generic CFG backend. Existing ABI 1/2 callers
    * retain the original wire contract until server migration is verified.
    */
  def emitV2(program: Program): Emitted = {
    if (program.steps.isEmpty || !program.steps.last.isInstanceOf[Finish] ||
        program.steps.init.exists(_.isInstanceOf[Finish]))
      throw CompileError("workflow=" + program.name,
        "workflow requires exactly one terminal return after control flow")
    ArtifactWriter.pack(program, StateMachineEmitter.source(program), runtimeAbi = 3)
  }

  def writeV2(program: Program, directory: Path, runtime: Path): Emitted =
    ArtifactWriter.write(directory, runtime, emitV2(program))
}
