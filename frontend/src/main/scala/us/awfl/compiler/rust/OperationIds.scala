package us.awfl.compiler.rust

import us.awfl.dsl.{CelFunctions, CallOperations}

/** Internal function names match the CEL standard library verbatim;
  * Call identifiers remain backend-neutral. Neither introduces new DSL AST
  * variants. This is the boundary to statically compiled Rust.
  */
enum FunctionId(val wireId: String, val minArity: Int, val maxArity: Int) {
  case Default extends FunctionId(CelFunctions.Core.Default, 2, 2)
  case TypeOf extends FunctionId(CelFunctions.Core.TypeOf, 1, 1)
  case ToInt extends FunctionId(CelFunctions.Core.ToInt, 1, 1)
  case ToString extends FunctionId(CelFunctions.Core.ToString, 1, 1)
  case Length extends FunctionId(CelFunctions.Core.Length, 1, 1)
  case MapGet extends FunctionId(CelFunctions.Map.Get, 2, 2)
  case MapMerge extends FunctionId(CelFunctions.Map.Merge, 2, 2)
  case ListConcat extends FunctionId(CelFunctions.List.Concat, 2, 2)
  case JsonDecode extends FunctionId(CelFunctions.Json.Decode, 1, 1)
  case JsonEncodeBytes extends FunctionId(CelFunctions.Json.Encode, 1, 1)
  case JsonEncodeString extends FunctionId(CelFunctions.Json.EncodeToString, 1, 1)
  case Base64Encode extends FunctionId(CelFunctions.Base64.Encode, 1, 2)
  case TextEncode extends FunctionId(CelFunctions.Text.Encode, 1, 2)
  case TextDecode extends FunctionId(CelFunctions.Text.Decode, 1, 2)
  case TextSplit extends FunctionId(CelFunctions.Text.Split, 2, 2)
  // Google Workflows requires both start and end. The DSL's two-argument
  // convenience overload is not a deployable Workflows call.
  case TextSubstring extends FunctionId(CelFunctions.Text.Substring, 3, 3)
  case TextLowercase extends FunctionId(CelFunctions.Text.ToLower, 1, 1)
  case TextUrlEncode extends FunctionId(CelFunctions.Text.UrlEncode, 1, 1)
  case TimeFormat extends FunctionId(CelFunctions.Time.Format, 1, 2)
  case UuidGenerate extends FunctionId(CelFunctions.Uuid.Generate, 0, 0)
  case ClockNow extends FunctionId(CelFunctions.Time.Now, 0, 0)
  case EnvironmentGet extends FunctionId(CelFunctions.Environment.Get, 1, 2)
}
object FunctionId {
  private val byName = values.map(id => id.wireId -> id).toMap
  // Standard-library names are already the canonical function identifiers.
  // No translation table or Google-specific alias is needed for CEL.
  def resolve(name: String): Option[FunctionId] = byName.get(name)
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
