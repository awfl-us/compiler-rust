package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*

/** Real Scala DSL -> native Rust fixtures for in-process workflow.run. */
object LocalSubworkflowSmoke {
  def main(args: Array[String]): Unit = {
    val fixtures = Paths.get("../fixtures").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize

    def workflowArgs(workflow: String, value: BaseValue[Int]) = RunWorkflowArgs[Int](
      str(workflow), value, str("us-central1"), str("awfl-us"))

    val leaf = Workflow((List.empty[Step[?, ?]],
      Value[Int](CelOp(CelConst("input"), "+", CelConst("3")))),
      Some("LocalLeaf"))

    val callLeaf = Call[RunWorkflowArgs[Int], Int]("callLeaf",
      CallOperations.WorkflowRun, obj(workflowArgs("LocalLeaf", Value[Int]("input"))))
    val child = Workflow((List[Step[?, ?]](callLeaf), callLeaf.resultValue),
      Some("LocalChild"))

    val repeated = ForRange[Int]("repeatChild", CelConst("0"), CelConst("20")) { i =>
      val invoke = Call[RunWorkflowArgs[Int], Int]("localCall",
        CallOperations.WorkflowRun,
        obj(workflowArgs("LocalChild", Value[Int](i))))
      List[Step[?, ?]](invoke) -> invoke.resultValue
    }
    val parent = Workflow((List[Step[?, ?]](repeated), repeated.resultValue),
      Some("LocalParent"))

    val http = Call[Map[String, String], String]("httpEffect",
      CallOperations.HttpPost, obj(Map("url" -> "https://example.org/local")))
    val httpChild = Workflow((List[Step[?, ?]](http), http.resultValue),
      Some("LocalHttpChild"))
    val callHttp = Call[RunWorkflowArgs[Int], String]("callHttp",
      CallOperations.WorkflowRun, obj(workflowArgs("LocalHttpChild", obj(1))))
    val httpParent = Workflow((List[Step[?, ?]](callHttp), callHttp.resultValue),
      Some("LocalHttpParent"))

    for ((workflow, dir) <- List(
      leaf -> "native-local-leaf",
      child -> "native-local-child",
      parent -> "native-local-parent",
      httpChild -> "native-local-http-child",
      httpParent -> "native-local-http-parent")) {
      val ir = Normalize(workflow)
      val written = RustEmitter.write(ir, fixtures.resolve(dir), runtime)
      assert(written.manifest.contains("\"runtime_abi\":3"))
      if (ir.effects.exists(_.id == CallId.WorkflowRun)) {
        assert(written.manifest.contains("workflow.run"))
      }
      println(s"LOCAL_WORKFLOW_CRATE=$dir")
    }
  }
}
