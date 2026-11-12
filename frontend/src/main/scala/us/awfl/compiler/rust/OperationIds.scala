package us.awfl.compiler.rust

import us.awfl.dsl.{CelFunctions, CallOperations}

/** Compiler-internal operations. The public DSL exposes only String constants;
  * no new DSL AST or overloads are needed. This is the sole interpretation
  * boundary between existing CEL/Call nodes and statically compiled Rust.
  */
enum FunctionId(val wireId: String, val minArity: Int, val maxArity: Int) {
  case Default extends FunctionId(CelFunctions.Default, 2, 2)
  case TypeOf extends FunctionId(CelFunctions.TypeOf, 1, 1)
  case ToInt extends FunctionId(CelFunctions.ToInt, 1, 1)
  case ToString extends FunctionId(CelFunctions.ToString, 1, 1)
  case Length extends FunctionId(CelFunctions.Length, 1, 1)
  case MapGet extends FunctionId(CelFunctions.MapGet, 2, 2)
  case MapMerge extends FunctionId(CelFunctions.MapMerge, 2, 2)
  case ListConcat extends FunctionId(CelFunctions.ListConcat, 2, 2)
  case JsonDecode extends FunctionId(CelFunctions.JsonDecode, 1, 1)
  case JsonEncodeBytes extends FunctionId(CelFunctions.JsonEncodeBytes, 1, 1)
  case JsonEncodeString extends FunctionId(CelFunctions.JsonEncodeString, 1, 1)
  case Base64Encode extends FunctionId(CelFunctions.Base64Encode, 1, 2)
  case TextEncode extends FunctionId(CelFunctions.TextEncode, 1, 2)
  case TextDecode extends FunctionId(CelFunctions.TextDecode, 1, 2)
  case TextSplit extends FunctionId(CelFunctions.TextSplit, 2, 2)
  case TextSubstring extends FunctionId(CelFunctions.TextSubstring, 2, 3)
  case TextLowercase extends FunctionId(CelFunctions.TextLowercase, 1, 1)
  case TextUrlEncode extends FunctionId(CelFunctions.TextUrlEncode, 1, 1)
  case TimeFormat extends FunctionId(CelFunctions.TimeFormat, 2, 2)
  case UuidGenerate extends FunctionId(CelFunctions.UuidGenerate, 0, 0)
  case ClockNow extends FunctionId(CelFunctions.ClockNow, 0, 0)
  case EnvironmentGet extends FunctionId(CelFunctions.EnvironmentGet, 1, 2)
}
object FunctionId {
  private val byName = values.map(id => id.wireId -> id).toMap
  // These exact literals are still emitted internally by the published
  // DSL's `len`, `encodeJson`, and safe-resolver helpers. This is not
  // general old-name support for application workflow CEL functions.
  private val dslBuiltins = Map(
    "len" -> Length,
    "default" -> Default,
    "json.encode_to_string" -> JsonEncodeString
  )
  def resolve(name: String): Option[FunctionId] =
    byName.get(name).orElse(dslBuiltins.get(name))
}

enum CallId(val wireId: String) {
  case HttpGet extends CallId(CallOperations.HttpGet)
  case HttpPost extends CallId(CallOperations.HttpPost)
  case HttpPatch extends CallId(CallOperations.HttpPatch)
  case HttpDelete extends CallId(CallOperations.HttpDelete)
  case LogWrite extends CallId(CallOperations.LogWrite)
  case CallbackCreate extends CallId(CallOperations.CallbackCreate)
  case CallbackAwait extends CallId(CallOperations.CallbackAwait)
  case WorkflowRun extends CallId(CallOperations.WorkflowRun)
  case StorageGet extends CallId(CallOperations.StorageGet)
  case MessagingPublish extends CallId(CallOperations.MessagingPublish)
}
object CallId {
  private val byName = values.map(id => id.wireId -> id).toMap
  // The current, unchanged DSL `Log` constructor emits sys.log itself.
  // Recognize that exact built-in operation without broad legacy aliases
  // or changing the public DSL; the emitted native operation stays log.write.
  def resolve(name: String): Option[CallId] =
    if (name == "sys.log") Some(LogWrite) else byName.get(name)
}

enum GlobalId(val wireId: String, val placeholder: String) {
  case WorkflowId extends GlobalId("workflow.id", "WORKFLOW_ID")
  case ExecutionId extends GlobalId("execution.id", "WORKFLOW_EXECUTION_ID")
  case CloudProjectId extends GlobalId("cloud.project_id", "PROJECT_ID")
  case Region extends GlobalId("cloud.region", "LOCATION")
}
object GlobalId {
  private val byPlaceholder = values.map(id => id.placeholder -> id).toMap
  def resolve(name: String): Option[GlobalId] = byPlaceholder.get(name)
}

enum OperatorId(val wireId: String, val symbol: String, val arity: Int) {
  case Equal extends OperatorId("compare.eq", "==", 2)
  case NotEqual extends OperatorId("compare.ne", "!=", 2)
  case Greater extends OperatorId("compare.gt", ">", 2)
  case GreaterEq extends OperatorId("compare.gte", ">=", 2)
  case Less extends OperatorId("compare.lt", "<", 2)
  case LessEq extends OperatorId("compare.lte", "<=", 2)
  case Add extends OperatorId("math.add", "+", 2)
  case Subtract extends OperatorId("math.subtract", "-", 2)
  case Multiply extends OperatorId("math.multiply", "*", 2)
  case DivideInt extends OperatorId("math.divide_int", "//", 2)
  case Contains extends OperatorId("collection.in", "in", 2)
  case And extends OperatorId("boolean.and", "and", 2)
  case Or extends OperatorId("boolean.or", "or", 2)
  case Not extends OperatorId("boolean.not", "not", 1)
}
object OperatorId {
  def resolve(symbol: String, arity: Int): Option[OperatorId] =
    values.find(id => id.symbol == symbol && id.arity == arity)
}
