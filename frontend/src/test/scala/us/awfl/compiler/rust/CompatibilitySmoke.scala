package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*

/** Audit should see constructor-created operations through FlatMap and fail closed. */
object CompatibilitySmoke {
  def main(args: Array[String]): Unit = {
    val post = Call[Map[String, String], String]("call",
      CallOperations.HttpPost, obj(Map("url" -> "https://example.invalid")))
    val wrapped = FlatMap[String, String, Value[String]](
      "mapResult", post, str(post.resultValue.cel + CelStr("-ok")))
    val good = Workflow((List[Step[?, ?]](wrapped), wrapped.resultValue))
    val passing = CompatibilityMain.inspect(good, "wrapped", "fixture")
    assert(passing.status == "emitted", passing.reason)
    assert(passing.occurrences.exists(o => o.kind == "step" && o.name == "FlatMap"))
    assert(passing.occurrences.count(o => o.kind == "call" &&
      o.name == CallOperations.HttpPost) == 1)

    val bad = Workflow((List.empty[Step[?, ?]], Value[String]("sys.now()")))
    val blocked = CompatibilityMain.inspect(bad, "clock", "fixture")
    assert(blocked.status == "blocked")
    assert(blocked.reason.exists(_.contains("sys.now()")))
    assert(blocked.occurrences.exists(o => o.kind == "raw-cel" && o.name == "sys.now()"))

    val summary = CompatibilityMain.report(Vector(passing, blocked), Vector.empty)
    assert(summary.hcursor.downField("emitted").as[Int].toOption.contains(1))
    assert(summary.hcursor.downField("blocked").as[Int].toOption.contains(1))
    println("COMPATIBILITY_AUDIT_SMOKE_PASS")
  }
}
