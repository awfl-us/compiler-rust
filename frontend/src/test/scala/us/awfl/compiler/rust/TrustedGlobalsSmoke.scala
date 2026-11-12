package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Existing DSL globals and standard sys.get_env produce native Rust that
  * reads only the separate server-supplied execution context.
  */
object TrustedGlobalsSmoke {
  def main(args: Array[String]): Unit = {
    val result = obj(Map(
      "workflow" -> WORKFLOW_ID,
      "execution" -> WORKFLOW_EXECUTION_ID,
      "project" -> PROJECT_ID,
      "region" -> LOCATION,
      "fromEnv" -> Value[String](
        environment.get(CelStr("GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID"))),
      "fallback" -> Value[String](
        environment.get(CelStr("UNRECOGNIZED_RUNTIME_ENV"), CelStr("default"))),
      "requestInput" -> Value[String]("input")
    ))
    val program = Normalize(Workflow(
      (List.empty[Step[?, ?]], result), Some("native-trusted-globals")))
    val source = RustEmitter.emit(program).source
    assert(source.contains("ops::trusted_global"))
    assert(source.contains("ops::environment_get"))
    assert(source.contains("trusted_globals"))
    val target = Paths.get("../fixtures/native-trusted-globals")
      .toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    RustEmitter.write(program, target, runtime)
    println("TRUSTED_GLOBALS_COMPILE_PASS")
  }
}
