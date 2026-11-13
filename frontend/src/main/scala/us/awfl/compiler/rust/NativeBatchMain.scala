package us.awfl.compiler.rust

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Compile a dependency set in one JVM/sbt invocation.
  *
  * One target may generate several native workflows (e.g. -tools and
  * -prompts); the batch driver consumes all of their emitted manifests.
  */
object NativeBatchMain {
  def main(args: Array[String]): Unit = {
    if (args.length != 3)
      throw new IllegalArgumentException(
        "Usage: NativeBatchMain <classes-file> <output-dir> <runtime-dir>")
    val classes = Files.readAllLines(Paths.get(args(0)), StandardCharsets.UTF_8)
      .asScala.iterator.map(_.trim)
      .filter(name => name.nonEmpty && !name.startsWith("#")).toList
    if (classes.isEmpty || classes.size > 256 || classes.distinct.size != classes.size ||
        classes.exists(name => !name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
      throw new IllegalArgumentException("invalid native batch classes file")
    var generated = Set.empty[String]
    classes.foreach { module =>
      val artifacts = try NativeMain.compile(Array(module, args(1), args(2)))
      catch {
        case error: Exception =>
          System.err.println(s"Native batch failed to compile $module: ${error.getMessage}")
          throw error
      }
      artifacts.foreach { artifact =>
        if (generated.contains(artifact.workflowName))
          throw new IllegalArgumentException(
            s"duplicate native workflow output: ${artifact.workflowName}")
        generated += artifact.workflowName
        println(s"AWFL_NATIVE_COMPILED ${artifact.workflowName}")
      }
    }
    // Complete artifact list; consumers prune stale crates only on success.
    Files.writeString(Paths.get(args(1)).resolve(".awfl-native-workflows"),
      generated.toList.sorted.mkString("", "\n", "\n"), StandardCharsets.UTF_8)
    println(s"AWFL_NATIVE_BATCH_PASS classes=${classes.length} workflows=${generated.size}")
  }
}
