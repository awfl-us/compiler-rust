package us.awfl.compiler.rust

import java.nio.file.{Path, Paths}
import us.awfl.compiler.WorkflowLoader
import us.awfl.core.{Workflow as CoreWorkflow}
import us.awfl.dsl.{Workflow as DslWorkflow}
import us.awfl.compiler.rust.Ir.CompileError

/** Opt-in native target CLI. Uses the same workflow-loader implementation as
  * awfl/compiler, without emitting Google YAML as an intermediate format.
  *
  * Emits only ABI 3 / IPC protocol 2. The regular compiler CLI can
  * delegate here after coordinated publication.
  */
object NativeMain {
  private val usage =
    "Usage: NativeMain <WorkflowClassName> [OutputDirectory] [RustRuntimeDirectory] [--only=<FullWorkflowName>]"

  def compile(args: Array[String]): List[NativeCompiler.Artifact] = {
    val selector = args.lastOption.filter(_.startsWith("--only="))
    val positional = if (selector.nonEmpty) args.dropRight(1) else args
    if (positional.isEmpty || positional.length > 3 ||
        positional.exists(_.startsWith("--")) ||
        selector.exists(_ == "--only="))
      throw IllegalArgumentException(usage)
    val loaded = WorkflowLoader.loadFirst(WorkflowLoader.resolveCandidates(positional(0)))
    val fqcn = loaded.fqcn
    val module = loaded.module
    if (module == null || !classOf[CoreWorkflow].isAssignableFrom(module.getClass))
      throw CompileError(fqcn, "requires a Scala object extending core.Workflow")
    val workflow = module.asInstanceOf[CoreWorkflow]
    val workflows = workflow.workflows.asInstanceOf[List[DslWorkflow[?]]]
    if (workflows.isEmpty)
      throw CompileError(fqcn, "workflow builder produced no workflows")
    val output = Paths.get(positional.lift(1).getOrElse("rust_gens"))
    val runtime = Paths.get(positional.lift(2).getOrElse("../runtime"))
    NativeCompiler.compile(workflows, workflow.workflowName, output, runtime,
      selector.map(_.stripPrefix("--only=")))
  }

  def main(args: Array[String]): Unit = {
    val artifacts = compile(args)
    artifacts.foreach(a => println(
      s"Compiled ${a.workflowName}: ${a.directory.resolve("src/main.rs")}"))
  }
}
