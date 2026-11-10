package us.awfl.compiler.rust

import java.nio.file.{Path, Paths}
import us.awfl.compiler.WorkflowLoader
import us.awfl.core.{Workflow as CoreWorkflow}
import us.awfl.dsl.{Workflow as DslWorkflow}
import us.awfl.compiler.rust.Ir.CompileError

/** Opt-in native target CLI. Uses the same workflow-loader implementation as
  * awfl/compiler, without emitting Google YAML as an intermediate format.
  *
  * Long-term the regular compiler CLI will delegate here via a published
  * versioned module; until then this is a standalone executable entrypoint.
  */
object NativeMain {
  def compile(args: Array[String]): List[NativeCompiler.Artifact] = {
    if (args.isEmpty || args.length > 3)
      throw IllegalArgumentException(
        "Usage: NativeMain <WorkflowClassName> [OutputDirectory] [RustRuntimeDirectory]")
    val loaded = WorkflowLoader.loadFirst(WorkflowLoader.resolveCandidates(args(0)))
    val fqcn = loaded.fqcn
    val module = loaded.module
    if (module == null || !classOf[CoreWorkflow].isAssignableFrom(module.getClass))
      throw CompileError(fqcn, "requires a Scala object extending core.Workflow")
    val workflow = module.asInstanceOf[CoreWorkflow]
    val workflows = workflow.workflows.asInstanceOf[List[DslWorkflow[?]]]
    if (workflows.isEmpty)
      throw CompileError(fqcn, "workflow builder produced no workflows")
    val output = Paths.get(args.lift(1).getOrElse("rust_gens"))
    val runtime = Paths.get(args.lift(2).getOrElse("../runtime"))
    NativeCompiler.compile(workflows, workflow.workflowName, output, runtime)
  }

  def main(args: Array[String]): Unit = {
    val artifacts = compile(args)
    artifacts.foreach(a => println(
      s"Compiled ${a.workflowName}: ${a.directory.resolve("src/main.rs")}"))
  }
}
