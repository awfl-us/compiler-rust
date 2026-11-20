package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import java.nio.file.Paths

/** Distinguish two values of the same ADT constructor by comparing JSON
  * candidates with literal fields (no `$awflType` discriminator).
  */
sealed trait RoleEvent extends IsAdt
case class RoleCreated(role: String, id: Value[String]) extends RoleEvent
case class RoleDeleted(reason: String, id: Value[String]) extends RoleEvent

object AdtLiteralNativeSmoke:
  def route(in: Value[RoleEvent]): Step[String, Value[String]] =
    Match("routeByRole", in) {
      case RoleCreated("admin", id) => List() -> id
      case RoleCreated("user", id) => List() -> str("user")
      case RoleDeleted("admin", id) => List() -> str("deleted")
      case _ => List() -> str("other")
    }

  def main(args: Array[String]): Unit =
    val step = route(Value[RoleEvent]("input"))
    val ir = Normalize(Workflow((List[Step[?, ?]](step), step.resultValue),
      Some("adt-match-literal")))
    assert(ir.steps.nonEmpty)
    assert(ir.effects.isEmpty)
    RustEmitter.write(ir,
      Paths.get("../fixtures/native-adt-match-literal").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)
    println("DSL_ADT_NATIVE_GENERATED:adt-match-literal")
