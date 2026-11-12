package us.awfl.compiler.rust

import us.awfl.core.{Workflow as CoreWorkflow}
import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.{Workflow as DslWorkflow}

/** One usable child and one unsupported child, as in a real multi-workflow builder. */
object NativeMultiCliFixture extends CoreWorkflow {
  type Input = Int
  type Result = Int
  override val inputVal: Value[Int] = Value[Int]("input")
  override def workflowName: String = "multi-fixture"
  override def workflows: List[DslWorkflow[?]] = List(
    DslWorkflow(List.empty[Step[?, ?]] ->
      Value[Int](inputVal.cel + CelConst("1")), Some("supported")),
    DslWorkflow(List.empty[Step[?, ?]] ->
      Value[Int](CelFunc("provider.unsupported", CelConst("input"))), Some("unsupported"))
  )
}
