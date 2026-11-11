package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Unmodified public Try and Raise DSL constructors. Generated Rust owns
  * exceptional CFG edges; Scala only constructs the existing DSL AST.
  */
object TryRaiseSmoke {
  def main(args: Array[String]): Unit = {
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val base = Paths.get("../fixtures/native-try-raise").toAbsolutePath.normalize
    def emitted(name: String, workflow: Workflow[String]): Unit = {
      val normalized = Normalize(workflow)
      val plan = ControlFlowLowering(normalized)
      assert(plan.blocks.nonEmpty)
      assert(plan.blocks.exists(_.onError.nonEmpty) || name == "uncaught")
      val rust = RustEmitter.write(normalized, base.resolve(name), runtime)
      assert(rust.manifest.contains("\"runtime_abi\":3"))
      assert(rust.source.contains("AwflRaisedError"))
    }
    def message(text: String) = obj(Error(str(CelStr(text)), OptValue[Int](CelConst("null"))))
    def thrown(text: String) = Raise("fail", message(text))

    val caught = Try("caught", List[Step[?, ?]](thrown("raised")) -> str(CelStr("not-reached")),
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    emitted("catch-raise", Workflow((List[Step[?, ?]](caught), caught.resultValue), Some("catch-raise")))

    val happy = Try("happy", List.empty[Step[?, ?]] -> str(CelStr("good")),
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    emitted("success", Workflow((List[Step[?, ?]](happy), happy.resultValue), Some("success")))

    val inner = Try("inner", List[Step[?, ?]](thrown("nested")) -> str(CelStr("inner-ok")),
      err => List[Step[?, ?]](Raise("rethrow", err)) -> str(CelStr("unreachable")))
    val outer = Try("outer", List[Step[?, ?]](inner) -> inner.resultValue,
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    emitted("nested-catch", Workflow((List[Step[?, ?]](outer), outer.resultValue), Some("nested-catch")))

    emitted("uncaught", Workflow((List[Step[?, ?]](thrown("uncaught")), str(CelStr("never"))), Some("uncaught")))

    val invalidLookup = str(CelFunc(CelFunctions.MapGet,
      CelConst("input"), CelConst("3")))
    val valueError = Try("runtime-error", List.empty[Step[?, ?]] -> invalidLookup,
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    emitted("runtime-error", Workflow((List[Step[?, ?]](valueError), valueError.resultValue), Some("runtime-error")))

    val api = Call[Map[String, String], String]("request", CallOperations.HttpPost,
      obj(Map("url" -> "https://example.invalid/try")))
    val guardedHttp = Try("guarded-http", List[Step[?, ?]](api) -> str(CelStr("effect-ok")),
      err => List.empty[Step[?, ?]] -> err.flatMap(_.message))
    emitted("effect-catch", Workflow((List[Step[?, ?]](guardedHttp), guardedHttp.resultValue), Some("effect-catch")))
    // Original DSL default Try handler writes sys.log and then rethrows.
    // A catch-only CFG must be mutable even without ordinary control-flow jumps.
    val raised = Raise("raise-and-log", message("deliberately raised"))
    val withDefaultHandler = Try("default-log", List[Step[?, ?]](raised) ->
      str(CelStr("unreachable")))
    val logFlow = Normalize(Workflow((List[Step[?, ?]](withDefaultHandler),
      withDefaultHandler.resultValue), Some("native-log-on-error")))
    assert(logFlow.effects.exists(_.id == CallId.LogWrite))
    val logResult = RustEmitter.write(logFlow,
      base.resolve("default-log"), runtime)
    assert(logResult.manifest.contains("\"runtime_abi\":3"))
    assert(logResult.manifest.contains("log.write"))

    println("Native Try/Raise: explicit, nested, uncaught, runtime error and guarded effect compiled")
  }
}
