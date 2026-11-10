package us.awfl.compiler.rust

import us.awfl.dsl.{Workflow as DslWorkflow}
import us.awfl.compiler.rust.Ir.*
import java.nio.file.Path

/** Backend entrypoint for the existing awfl/compiler loader.
  *
  * The existing compiler should keep loading Scala singleton workflow builders
  * and emitting schemas/definitions; pass its resulting DSL workflows here for
  * native normalization + codegen when the requested target is Rust.
  */
object NativeCompiler {
  final case class Artifact(workflowName: String, directory: Path, program: Program,
                            emitted: RustEmitter.Emitted)

  def compile(workflows: List[DslWorkflow[?]], baseName: String,
              outputDirectory: Path, runtimeDirectory: Path): List[Artifact] = {
    val named = workflows.zipWithIndex.map { (workflow, i) =>
      val name = workflow.name.map(n => s"$baseName-$n").getOrElse(baseName)
      if (name.isBlank) throw CompileError(s"workflow[$i]", "workflow name must not be empty")
      val safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_")
      if (safeName == "." || safeName == ".." || safeName.isBlank)
        throw CompileError(s"workflow[$i]", "workflow artifact name invalid")
      (workflow, name, safeName)
    }
    val duplicates = named.groupBy(_._3).collect { case (name, values) if values.size > 1 => name }
    if (duplicates.nonEmpty)
      throw CompileError("artifact names", s"duplicate artifact names: ${duplicates.mkString(", ")}")
    named.map { (workflow, name, safeName) =>
      val program = Normalize(workflow, name)
      val destination = outputDirectory.resolve(safeName)
      val emitted = RustEmitter.write(program, destination, runtimeDirectory)
      Artifact(name, destination, program, emitted)
    }
  }
}
