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
  /** ABI 3 is the only format accepted by the IAM-authorized native publisher.
    * Legacy output remains available for compatibility and differential tests.
    */
  enum Backend {
    case DurableV2, Legacy
  }

  final case class Artifact(workflowName: String, directory: Path, program: Program,
                            emitted: RustEmitter.Emitted)

  def compile(workflows: List[DslWorkflow[?]], baseName: String,
              outputDirectory: Path, runtimeDirectory: Path,
              backend: Backend = Backend.DurableV2): List[Artifact] = {
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
      // The compiled artifact identifies the fully qualified workflow name,
      // even when the DSL child has a shorter local name.
      val program = Normalize(workflow, name).copy(name = name)
      val destination = outputDirectory.resolve(safeName)
      val emitted = backend match {
        case Backend.DurableV2 => RustEmitter.writeV2(program, destination, runtimeDirectory)
        case Backend.Legacy => RustEmitter.write(program, destination, runtimeDirectory)
      }
      Artifact(name, destination, program, emitted)
    }
  }
}
