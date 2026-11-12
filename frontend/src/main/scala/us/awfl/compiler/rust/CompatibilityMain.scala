package us.awfl.compiler.rust

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.IdentityHashMap
import scala.jdk.CollectionConverters.*
import scala.collection.mutable
import scala.util.control.NonFatal
import us.awfl.dsl.*
import us.awfl.core.{Workflow as CoreWorkflow}
import us.awfl.compiler.rust.Ir.CompileError

/** Read-only audit of instantiated AWFL DSL graphs, including helper-expanded
  * nodes. Produces source-emission results; never executes remote effects.
  */
object CompatibilityMain {
  final case class Occurrence(kind: String, name: String, path: String) {
    def json: Json = Json.obj(
      "kind" -> Json.fromString(kind),
      "name" -> Json.fromString(name),
      "path" -> Json.fromString(path))
  }

  final case class Result(workflow: String, source: String, status: String,
                          reason: Option[String], occurrences: Vector[Occurrence]) {
    def json: Json = Json.obj(
      "workflow" -> Json.fromString(workflow),
      "source" -> Json.fromString(source),
      "status" -> Json.fromString(status),
      "reason" -> reason.fold(Json.Null)(Json.fromString),
      "occurrences" -> Json.fromValues(occurrences.map(_.json)))
  }

  /** Paths identify instantiated DSL nodes, not original helper source lines. */
  def inspect(workflow: us.awfl.dsl.Workflow[?], name: String, source: String): Result = {
    val seen = mutable.ArrayBuffer.empty[Occurrence]
    val active = new IdentityHashMap[AnyRef, java.lang.Boolean]()

    def add(kind: String, value: String, path: String): Unit =
      seen += Occurrence(kind, value.take(512), path)

    def fields(p: Product, path: String, depth: Int): Unit =
      p.productElementNames.zip(p.productIterator).foreach { (field, value) =>
        walk(value, s"$path.$field", depth + 1)
      }

    def walk(value: Any, path: String, depth: Int = 0): Unit = {
      if (value == null || depth > 256) return
      value match {
        case _: String | _: Number | _: Boolean | _: Char => return
        case _ => ()
      }
      val ref = value.asInstanceOf[AnyRef]
      if (active.containsKey(ref)) return
      active.put(ref, java.lang.Boolean.TRUE)
      try {
        value match {
          case s: Step[?, ?] =>
            add("step", s.getClass.getSimpleName.stripSuffix("$"), path)
            s match {
              case c: Call[?, ?] => add("call", c.call, s"$path.call")
              case _ => ()
            }
            fields(s.asInstanceOf[Product], path, depth)
          case c: CelFunc =>
            add("function", c.name, path)
            c.params.zipWithIndex.foreach((v, i) => walk(v, s"$path.arg[$i]", depth + 1))
          case c: CelOp =>
            add("operator", if (c.op.isEmpty) "not" else c.op, path)
            walk(c.left, s"$path.left", depth + 1)
            walk(c.right, s"$path.right", depth + 1)
          case c: CelConst => add("raw-cel", c.value, path)
          case c: CelStr =>
            if (GlobalId.resolve(c.content).nonEmpty) add("global", c.content, path)
          case c: CelAt =>
            walk(c.list, s"$path.list", depth + 1)
            walk(c.i, s"$path.index", depth + 1)
          case c: CelPath =>
            c.path.zipWithIndex.foreach((v, i) => walk(v, s"$path.segment[$i]", depth + 1))
          case c: CelValue => walk(c.value, s"$path.value", depth + 1)
          case o: Option[?] => o.foreach(v => walk(v, path, depth + 1))
          case m: scala.collection.Map[?, ?] =>
            m.toVector.sortBy(_._1.toString).foreach { (k, v) =>
              walk(v, s"$path[${k.toString.take(60)}]", depth + 1)
            }
          case xs: Iterable[?] =>
            xs.zipWithIndex.foreach((v, i) => walk(v, s"$path[$i]", depth + 1))
          case p: Product => fields(p, path, depth)
          case _ => ()
        }
      } finally active.remove(ref)
    }

    walk(workflow.steps, "steps")
    val (status, reason) = try {
      val program = Normalize(workflow, name)
      RustEmitter.emit(program) // CFG/Rust source generated, no executable built
      ("emitted", None)
    } catch {
      case e: CompileError => ("blocked", Some(e.getMessage))
      case NonFatal(e) =>
        ("error", Some(s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))
    }
    Result(name, source, status, reason, seen.toVector)
  }

  def report(results: Seq[Result], loadErrors: Seq[(String, String)]): Json = {
    val names = results.flatMap(r => r.occurrences.map(o => (r.workflow, o)))
      .groupBy { case (_, o) => (o.kind, o.name) }.toVector
      .sortBy { case ((kind, name), _) => (kind, name) }
      .map { case ((kind, name), rows) =>
        Json.obj(
          "kind" -> Json.fromString(kind),
          "name" -> Json.fromString(name),
          "count" -> Json.fromInt(rows.size),
          "workflow_count" -> Json.fromInt(rows.map(_._1).distinct.size))
      }
    val unknown = results.flatMap(r => r.occurrences.collect {
      case o if o.kind == "function" && FunctionId.resolve(o.name).isEmpty =>
        (r.workflow, o)
      case o if o.kind == "call" && CallId.resolve(o.name).isEmpty =>
        (r.workflow, o)
    }).map { (workflow, o) =>
      Json.obj("workflow" -> Json.fromString(workflow),
        "kind" -> Json.fromString(o.kind),
        "name" -> Json.fromString(o.name),
        "path" -> Json.fromString(o.path))
    }
    Json.obj(
      "schema_version" -> Json.fromInt(1),
      "scope" -> Json.fromString("constructed DSL AST and CFG/Rust source emission; no Cargo or effects executed"),
      "workflow_count" -> Json.fromInt(results.size),
      "emitted" -> Json.fromInt(results.count(_.status == "emitted")),
      "blocked" -> Json.fromInt(results.count(_.status != "emitted")),
      "load_errors" -> Json.fromValues(loadErrors.map { (name, reason) =>
        Json.obj("class" -> Json.fromString(name), "reason" -> Json.fromString(reason))
      }),
      "unknown_operations" -> Json.fromValues(unknown),
      "operation_counts" -> Json.fromValues(names),
      "workflows" -> Json.fromValues(results.map(_.json)))
  }

  /** CompatibilityMain <report.json> <fqcn>... or --classes-dir <compiled-classes> */
  def main(args: Array[String]): Unit = {
    require(args.length >= 2,
      "Usage: CompatibilityMain <report.json> <fqcn>... | --classes-dir <compiled-classes>")
    val output = Paths.get(args(0))
    val errors = mutable.ArrayBuffer.empty[(String, String)]
    val results = mutable.ArrayBuffer.empty[Result]
    val candidates =
      if (args.length == 3 && args(1) == "--classes-dir") {
        val root = Paths.get(args(2)).toAbsolutePath.normalize
        require(Files.isDirectory(root), s"compiled workflow classes directory missing: $root")
        val stream = Files.walk(root)
        try stream.iterator.asScala
          .filter(Files.isRegularFile(_))
          .map(root.relativize(_).toString.replace('\\', '/'))
          .filter(_.endsWith("$.class"))
          .map(_.stripSuffix("$.class"))
          .filterNot(_.contains("$")) // top-level Scala singletons, not nested companion modules
          .map(_.replace('/', '.')).toVector
        finally stream.close()
      } else args.drop(1).toVector

    candidates.distinct.sorted.foreach { name =>
      try {
        // Preflight without initializing unrelated helper/companion objects.
        val cls = Class.forName(name + "$", false,
          Thread.currentThread().getContextClassLoader)
        if (classOf[CoreWorkflow].isAssignableFrom(cls)) {
          val module = cls.getField("MODULE$").get(null).asInstanceOf[CoreWorkflow]
          module.workflows.asInstanceOf[List[us.awfl.dsl.Workflow[?]]]
            .zipWithIndex.foreach { (workflow, i) =>
              val child = workflow.name.map(n => s"${module.workflowName}-$n")
                .getOrElse(module.workflowName)
              val result = inspect(workflow, child, name)
              results += result
              println(s"COMPAT ${result.status} $child ${result.reason.getOrElse("")}")
            }
        }
      } catch {
        case NonFatal(e) =>
          val message = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
          errors += ((name, message))
          println(s"LOAD_ERROR $name $message")
        case e: LinkageError =>
          val message = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
          errors += ((name, message))
          println(s"LOAD_ERROR $name $message")
      }
    }
    Files.createDirectories(output.toAbsolutePath.getParent)
    Files.writeString(output, report(results.toVector, errors.toVector).spaces2 + "\n",
      StandardCharsets.UTF_8)
    println(s"COMPATIBILITY_REPORT=${output.toAbsolutePath}")
    println(s"WORKFLOWS=${results.size} EMITTED=${results.count(_.status == "emitted")} " +
      s"BLOCKED=${results.count(_.status != "emitted")} LOAD_ERRORS=${errors.size}")
  }
}
