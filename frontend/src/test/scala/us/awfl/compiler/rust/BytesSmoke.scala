package us.awfl.compiler.rust

import java.nio.file.Paths
import us.awfl.dsl.*

/** Test unchanged CelFunc shape with generic byte/text operation names. */
object BytesSmoke {
  def main(args: Array[String]): Unit = {
    val msg = CelConst("input.message")
    val encoded = CelFunc(CelFunctions.TextEncode, msg, CelStr("utf-8"))
    val ascii = CelFunc(CelFunctions.TextEncode,
      CelConst("input.ascii"), CelConst("input.charset"))
    val output = obj(Map[String, BaseValue[?]](
      "base64" -> Value[String](CelFunc(CelFunctions.Base64Encode, encoded)),
      "unpadded" -> Value[String](CelFunc(CelFunctions.Base64Encode, encoded, CelConst("false"))),
      "roundtrip" -> Value[String](CelFunc(CelFunctions.TextDecode, encoded)),
      "ascii" -> Value[String](CelFunc(CelFunctions.TextDecode, ascii, CelStr("US-ASCII"))),
      "jsonBase64" -> Value[String](CelFunc(CelFunctions.Base64Encode,
        CelFunc(CelFunctions.JsonEncodeBytes, CelConst("input.data"))))
    ))
    val program = Normalize(Workflow((List.empty[Step[?, ?]], output), Some("bytes-encoding")))
    val emitted = RustEmitter.emit(program)
    for (name <- List("text_encode", "text_decode", "base64_encode", "json_encode_bytes")) {
      assert(emitted.source.contains(s"ops::$name"), name)
    }
    assert(emitted.manifest.contains("\"runtime_abi\":3"))
    RustEmitter.write(program, Paths.get("../fixtures/native-bytes").toAbsolutePath.normalize,
      Paths.get("../runtime").toAbsolutePath.normalize)

    val wrongArity = Value[String](CelFunc(CelFunctions.Base64Encode, msg,
      CelConst("true"), CelConst("true")))
    val diagnostic = try {
      Normalize(Workflow((List.empty[Step[?, ?]], wrongArity)))
      throw new AssertionError("expected arity rejection")
    } catch { case e: Ir.CompileError => e.getMessage }
    assert(diagnostic.contains("expects 1-2 arguments"), diagnostic)
    println("NATIVE_BYTES_FRONTEND_PASS")
  }
}
