package us.awfl.compiler.rust

import java.nio.file.{Files, Paths}

/** Integration test loads an unmodified production workflow Scala source file.
  * The workflow source comes from pinned awfl-us/workflows checkout in CI;
  * this repository does not copy or rewrite its DSL code.
  */
object RealWorkflowSmoke {
  def main(args: Array[String]): Unit = {
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val output = Paths.get("../fixtures/real-load-history").toAbsolutePath.normalize
    val results = NativeMain.compile(Array(
      "us.awfl.workflows.LoadConversationHistory", output.toString,
      runtime.toString
    ))
    assert(results.size == 1)
    val artifact = results.head
    assert(artifact.program.effects.map(_.id) == Vector(CallId.HttpPost))
    assert(artifact.emitted.manifest.contains("\"runtime_abi\":3"))
    assert(artifact.emitted.manifest.contains("http.post"))
    assert(artifact.emitted.source.contains("Outcome::Suspended"))
    assert(Files.isRegularFile(artifact.directory.resolve("src/main.rs")))
    println(s"REAL_WORKFLOW_ARTIFACT=${artifact.directory}")
    println(s"REAL_WORKFLOW_NAME=${artifact.workflowName}")
  }
}
