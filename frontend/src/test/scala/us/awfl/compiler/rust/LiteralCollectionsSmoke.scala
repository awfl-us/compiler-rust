package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*
import us.awfl.dsl.CelOps.*

/** Literal JSON-shaped CEL collection strings already produced by the DSL.
  * No new Cel classes or CEL expression interpreter are introduced.
  */
object LiteralCollectionsSmoke {
  def main(args: Array[String]): Unit = {
    val values = obj(Map[String, BaseValue[?]](
      "emptyList" -> Value[List[Int]](CelConst("[]")),
      "emptyMap" -> Value[Map[String, Int]](CelConst("{}")),
      "scalaList" -> Value[List[Int]](List(1, 2, 3).cel),
      "nestedList" -> Value[List[Any]](CelConst("""[1, "é🙂", null, [true, 2.5]]""")),
      "nestedMap" -> Value[Map[String, Any]](
        CelConst("""{"z":{"array":[false, "ok"]}, "a":-12}""")),
      "nestedGet" -> Value[Int](CelFunc(CelFunctions.MapGet,
        CelConst("""{"outer":{"leaf":7}}"""),
        CelValue(obj(List("outer", "leaf")))))
    ))
    val workflow = Workflow((List.empty[Step[?, ?]], values), Some("literal-collections"))
    val program = Normalize(workflow)
    assert(program == Normalize(workflow))
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val source = RustEmitter.write(program,
      Paths.get("../fixtures/native-literal-collections").toAbsolutePath.normalize,
      runtime)
    assert(source.source.contains("AwflValue::List"))
    assert(source.source.contains("AwflValue::Map"))
    assert(source.source.contains("ops::map_get"))
    assert(source.manifest.contains("\"runtime_abi\":3"))

    def diagnostic(raw: String): String = try {
      Normalize(Workflow((List.empty[Step[?, ?]], Value[List[Any]](CelConst(raw)))))
      throw new AssertionError(s"should reject raw CEL: $raw")
    } catch { case e: Ir.CompileError => e.getMessage }

    assert(diagnostic("[input.user]").contains("invalid literal collection"))
    assert(diagnostic("[1,]").contains("invalid literal collection"))
    assert(diagnostic("[9223372036854775808]").contains("signed 64-bit"))
    assert(diagnostic("[1e999]").contains("non-finite"))
    println("Original DSL CelConst list/map literals compile to structured native Rust")
  }
}
