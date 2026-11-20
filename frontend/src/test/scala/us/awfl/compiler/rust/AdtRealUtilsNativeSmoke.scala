package us.awfl.compiler.rust

import us.awfl.dsl.*
import us.awfl.dsl.auto.given
import us.awfl.utils.{KalaVibhaga, Locks, TopicContextYoj}
import java.nio.file.Paths

/** Compile the *actual* wip-old-informed workflow-utils usage sites to native
  * Rust. This test requires an updated workflow-utils package; during the
  * unreleased integration we override to a locally published snapshot.
  */
object AdtRealUtilsNativeSmoke:
  def main(args: Array[String]): Unit =
    val base = Paths.get("../fixtures").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val kala = Value[KalaVibhaga]("input")

    val lock = Locks.buildSessionKey("benchmark", kala)
    val lockProgram = Normalize(Workflow(
      (List[Step[?, ?]](lock), lock.resultValue), Some("adt-utils-lock")))
    RustEmitter.write(lockProgram, base.resolve("native-adt-utils-lock"), runtime)
    println("REAL_UTILS_MATCH_NATIVE_GENERATED:lock")

    val encoded = TopicContextYoj.encodeKalaVal(kala)
    val ctxProgram = Normalize(Workflow(
      (List[Step[?, ?]](encoded), encoded.resultValue), Some("adt-utils-context")))
    assert(ctxProgram.effects.nonEmpty == false)
    RustEmitter.write(ctxProgram, base.resolve("native-adt-utils-context"), runtime)
    println("REAL_UTILS_MATCH_NATIVE_GENERATED:context")
