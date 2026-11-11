package us.awfl.compiler.rust

import us.awfl.core.{Workflow as CoreWorkflow}
import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*
import us.awfl.dsl.{Workflow as DslWorkflow}

object NativeCliFixture extends CoreWorkflow {
  type Input = Int
  type Result = Int
  override val inputVal: Value[Int] = Value[Int]("input")
  override def workflowName: String = "cli-fixture"
  override def workflows: List[DslWorkflow[?]] =
    List(DslWorkflow(
      List.empty[Step[?, ?]] -> Value[Int](inputVal.cel + CelConst("1")),
      Some("increment")))
}
