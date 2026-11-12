package us.awfl.compiler.rust

import java.nio.file.Paths
import io.circe.parser.parse
import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.compiler.rust.Ir.*

/** The preexisting DSL's `str(CelValue(obj(...)))` JSON-text semantics. */
object ObjectStringSmoke {
  case class Schema(title: Value[String], summary: Value[String])

  def main(args: Array[String]): Unit = {
    val example = summon[Spec[Schema]].init(Resolver("example"))
    val stringValue = str(CelValue(obj(example)))
    val program = Normalize(Workflow((List.empty[Step[?, ?]], stringValue), Some("schema-text")))
    val actual = program.steps.last match {
      case Finish(_, _, StringValue(value)) => value
      case other => throw new AssertionError(s"expected literal JSON text, got $other")
    }
    val parsed = parse(actual).fold(throw _, identity)
    assert(parsed.hcursor.get[String]("title").toOption.contains("${example.title}"), actual)
    assert(parsed.hcursor.get[String]("summary").toOption.contains("${example.summary}"), actual)

    // The same structure without the String wrapper remains executable and
    // must not silently accept unbound references.
    val rejected = try {
      Normalize(Workflow((List.empty[Step[?, ?]], obj(example)), Some("schema-object")))
      throw new AssertionError("unbound runtime object unexpectedly compiled")
    } catch { case e: CompileError => e.getMessage }
    assert(rejected.contains("example"), rejected)

    val ordinary = Normalize(Workflow((List.empty[Step[?, ?]],
      obj(Map("title" -> "fixed", "items" -> List(1, 2)))), Some("ordinary-object")))
    assert(ordinary.steps.last.isInstanceOf[Finish])
    assert(ordinary.steps.last.asInstanceOf[Finish].value.isInstanceOf[MapValue])

    val rust = RustEmitter.write(program,
      Paths.get("../fixtures/native-schema-text").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)
    assert(rust.source.contains("example.title"))
    assert(rust.manifest.contains("\"runtime_abi\":3"))
    println("OBJECT_STRING_SMOKE_PASS")
  }
}
