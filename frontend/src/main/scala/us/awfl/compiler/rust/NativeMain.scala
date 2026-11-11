package us.awfl.compiler.rust

import java.nio.file.{Path, Paths}
import us.awfl.compiler.WorkflowLoader
import us.awfl.core.{Workflow as CoreWorkflow}
import us.awfl.dsl.{Workflow as DslWorkflow}
import us.awfl.compiler.rust.Ir.CompileError

/** Opt-in native target CLI. Uses the same workflow-loader implementation as
  * awfl/compiler, without emitting Google YAML as an intermediate format.
  *
  * Defaults to the durable, publishable native ABI 3 / IPC protocol 2.
  * The legacy ABI 1/2 emitters remain opt-in for differential fixtures.
  * The regular compiler CLI can delegate here after coordinated publication.
  */
object NativeMain {
  private val usage =
    "Usage: NativeMain <WorkflowClassName> [OutputDirectory] [RustRuntimeDirectory] [--abi=3|--abi=legacy]"

  def compile(args: Array[String]): List[NativeCompiler.Artifact] = {
    val (flags, positional) = args.toVector.partition(_.startsWith("--"))
    if (positional.isEmpty || positional.size > 3 || flags.size > 1 ||
        flags.exists(flag => flag != "--abi=3" && flag != "--abi=legacy"))
      throw IllegalArgumentException(usage)
    val backend = if (flags.contains("--abi=legacy")) NativeCompiler.Backend.Legacy
                  else NativeCompiler.Backend.DurableV2
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
    NativeCompiler.compile(workflows, workflow.workflowName, output, runtime, backend)
  }

  def main(args: Array[String]): Unit = {
    val artifacts = compile(args)
    artifacts.foreach(a => println(
      s"Compiled ${a.workflowName}: ${a.directory.resolve("src/main.rs")}"))
  }
}
