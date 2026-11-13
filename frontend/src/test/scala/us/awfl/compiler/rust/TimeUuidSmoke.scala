package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Generate a real executable from unmodified DSL function ASTs.
  * Calls within the same step may produce different values.
  */
object TimeUuidSmoke {
  def main(args: Array[String]): Unit = {
    val result = obj(Map[String, BaseValue[?]](
      "now1" -> Value[Double](time.now()),
      "now2" -> Value[Double](time.now()),
      "id1" -> Value[String](uuid.generate()),
      "id2" -> Value[String](uuid.generate()),
      "formattedUTC" -> Value[String](time.format(CelConst("1720788633"))),
      "formattedParis" -> Value[String](time.format(CelConst("1720788633"), CelStr("Europe/Paris")))
    ))
    val program = Normalize(Workflow(
      (List.empty[Step[?, ?]], result), Some("native-time-uuid")))
    val source = RustEmitter.emit(program).source
    assert(source.contains("ops::time_now()"))
    assert(source.contains("ops::uuid_generate()"))
    assert(source.contains("ops::time_format"))
    val output = Paths.get("../fixtures/native-time-uuid").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    RustEmitter.write(program, output, runtime)
    println("Native sys.now and uuid.generate compiled")
  }
}
