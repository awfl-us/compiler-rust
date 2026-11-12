package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Generated executable built from the original DSL's CelFunc and Value API.
  * No AST changes, textual CEL parsing, or Google YAML runtime fallback.
  */
object CelStdlibSmoke {
  def main(args: Array[String]): Unit = {
    // Scala uses time.now() to avoid shadowing scala.sys. The generated
    // expression keeps the Google standard-library identifier sys.now.
    assert(time.now() == CelFunc(CelFunctions.Time.Now))
    assert(CelFunctions.Time.Now == "sys.now")
    assert(FunctionId.resolve("sys.now").contains(FunctionId.ClockNow))
    assert(FunctionId.resolve("clock.now").isEmpty)
    assert(FunctionId.resolve("map.get").contains(FunctionId.MapGet))
    val clock = Normalize(Workflow((List.empty[Step[?, ?]],
      Value[Double](time.now()))))
    val clockSource = RustEmitter.emit(clock).source
    assert(clockSource.contains("ops::time_now()"))
    val uuidProgram = Normalize(Workflow((List.empty[Step[?, ?]],
      Value[String](uuid.generate()))))
    assert(RustEmitter.emit(uuidProgram).source.contains("ops::uuid_generate()"))

    val source = "input"
    val present = Value[Int](CelFunc(CelFunctions.MapGet,
      CelConst(s"$source.nested"), CelStr("present")))
    val missing = Value[String](CelFunc(CelFunctions.MapGet,
      CelConst(s"$source.nested"), CelStr("missing")))
    val keyPath = CelValue(obj(List("outer", "answer")))
    val nested = Value[Int](CelFunc(CelFunctions.MapGet,
      CelConst(s"$source.nested"), keyPath))
    val fallback = Value[String](CelFunc(CelFunctions.Default,
      missing.cel, CelStr("fallback")))
    val keepFalse = Value[Boolean](CelFunc(CelFunctions.Default,
      CelConst("false"), CelConst("true")))
    val typeName = Value[String](CelFunc(CelFunctions.TypeOf,
      CelConst(s"$source.items")))
    val merge = Value[Map[String, Any]](CelFunc(CelFunctions.MapMerge,
      CelConst(s"$source.left"), CelConst(s"$source.right")))
    val appended = Value[List[Any]](CelFunc(CelFunctions.ListConcat,
      CelConst(s"$source.items"), CelConst(s"$source.append")))
    val decoded = Value[Map[String, String]](CelFunc(CelFunctions.JsonDecode,
      CelConst(s"$source.json")))
    val encoded = Value[String](CelFunc(CelFunctions.JsonEncodeString,
      CelConst(s"$source.left")))
    val bytesRoundtrip = Value[Map[String, Any]](CelFunc(CelFunctions.JsonDecode,
      CelFunc(CelFunctions.JsonEncodeBytes, CelConst(s"$source.left"))))
    val encodedUrl = Value[String](text.url_encode(CelConst(s"$source.urlText")))
    val output = obj(Map[String, BaseValue[?]](
      "present" -> present, "missing" -> missing, "nested" -> nested,
      "fallback" -> fallback, "keepFalse" -> keepFalse,
      "typeName" -> typeName, "merged" -> merge, "appended" -> appended,
      "decoded" -> decoded, "encoded" -> encoded,
      "bytesRoundtrip" -> bytesRoundtrip, "urlEncoded" -> encodedUrl
    ))
    val program = Normalize(Workflow((List.empty[Step[?, ?]], output),
      Some("cel-standard-library")))
    val sourceRust = RustEmitter.emit(program).source
    for (symbol <- List("map_get", "default_value", "type_of", "map_merge",
          "list_concat", "json_decode", "json_encode_string", "json_encode_bytes", "text_url_encode")) {
      assert(sourceRust.contains(s"ops::$symbol"), s"missing Rust direct call $symbol")
    }
    val base = Paths.get("../fixtures/native-cel-stdlib").toAbsolutePath.normalize
    val runtime = Paths.get("../runtime").toAbsolutePath.normalize
    val result = RustEmitter.write(program, base, runtime)
    assert(result.manifest.contains("\"runtime_abi\":3"))
    assert(result.manifest.contains("\"effects\":[]"))

    val malformed = Value[Int](CelFunc(CelFunctions.MapGet,
      CelConst("input.nested"), CelStr("a"), CelStr("b")))
    val wrongArity = try {
      Normalize(Workflow((List.empty[Step[?, ?]], malformed)))
      throw new AssertionError("map.get should require exactly two arguments")
    } catch { case e: Ir.CompileError => e.getMessage }
    assert(wrongArity.contains("expects 2-2 arguments"))
    println("CEL standard library: native Scala lowering and arity verified")
  }
}
