# Native Rust workflow compiler and server execution plan

Status (2026-11-12): Scala-to-Rust ABI 3 implemented for sequential CFG,
original `Try`/`Raise`, CEL subset and resumable HTTP effects; isolated pure
GCP canary passed. **Native parallel fork/join is implemented and CI-tested
on draft compiler #18 and server #82, but not merged or production validated.**
Callbacks, nested child workflows, provider connectors, distributed admission,
uncertain remote-effect reconciliation and OS sandbox are not ready.
Production routing remains disabled.
Original plan: 2026-11-10; verification reviews: 2026-11-11 and 2026-11-12.
For the authoritative merged-versus-branch inventory, tests, remaining risk,
prioritized milestones and acceptance gates, see
[current-state-and-roadmap.md](current-state-and-roadmap.md).
Goal: preserve Scala workflow authoring, compile workflow control flow and expressions to native Rust, and move durable execution, step/value persistence, callbacks and recovery into AWFL server.

## 1. Findings from the current source

Reviewed every src/**/*.scala file in dsl (6), compiler (14), workflow-utils (31), and workflows (52), plus all four compiler-yaml source files. Searched literal and multiline CelFunc constructors, Call constructors, raw Value/CelConst expressions, and deployment substitutions. This is a source inventory, not proof of runtime parity or coverage of other repositories/branches. Generated YAML is an output, not the source for the new backend.

Source snapshots (Git tree SHAs, not asserted commit SHAs):
- dsl: e6e2b4faba09c82bc505e4e0686006feba13abab
- compiler: 54920a42e0f78c9abf232a5b16cc475d1d16d1c1
- compiler-yaml: 4b183a005e214049ce0b5bd75043130e59a03a31
- workflow-utils: 28f30e47b81fcf188ba58d6dc8ad9fb120cf3480
- workflows: f549d1fe7dec1679b886e82257d9f3dcb870bfdd
- server: fbf75ca5d70b14e90b975fd741b7e1623325dd35
- compiler-rust initially contains LICENSE only (tree d5fdd45b7a4ac0cc697cb328b8bfecd70c654ce4).

### Current architecture
- dsl/Cel.scala: CelFunc(name: String, params), string operators, CelConst raw strings, CelPath, CelAt and CelValue.
- dsl/Steps.scala: Call(name, call: String, args), Return, For, ForRange, Fold, Switch, Try, Raise, Block, FlatMap, Parallel and ParallelFor. There is no public Assign DSL case; assignment is introduced by the YAML backend.
- Step.resultValue uses name plus hashCode; Fold also calculates a hash-derived result name. These are unsuitable as the durable identity contract across compiler versions.
- Global.scala defines four ordinary Value[String] placeholders. compiler-yaml/Constants.scala substitutes them at encoding time. They are not a general global-variable store.
- compiler/Main.scala loads a Scala singleton implementing us.awfl.core.Workflow, reads its workflows and inputVal, generates YAML, and emits definitions/JSON schemas. Reuse that frontend and schema pipeline.
- compiler/YamlGen.scala currently excludes workflows with no steps. The Rust backend must support a constant/input-return-only workflow.
- compiler-yaml/Compiler.scala lowers high-level loops and branches to generated assignments/blocks, globally disambiguates display step names, and wraps every Call in http.default_retry. That blanket policy includes POSTs and callback/connector calls.
- workflow-utils contains the HTTP, callback, child workflow, storage and execution-registration adapters. Application workflows also directly construct Google connector Calls.
- workflows deployment performs textual WORKFLOW_ENV substitution after YAML generation. Rust needs explicit deployment binding; it must not ship those strings unresolved.
- server/workflows/services/executeWorkflow.js is the common web/Agents MCP launcher, currently using ExecutionsClient.createExecution. It constructs trusted env last.
- server already stores workflowExecsBySession, workflowExecLinks and workflowExecStatus, but the inspected execution routes do not implement durable per-step continuations/value storage.
- server/workflows/callbacks/index.js already dispatches mcp_request callbacks locally, while workflow callbacks forward to Google. Extend the common callback service with a Rust execution kind.
- server/workflows/exec/stop.js traverses execution relationships and cancels Google executions. Backend-aware cancellation belongs here, not in a second MCP-specific executor.

## 2. Standard CEL names and backend-neutral Call connectors

**Architecture decision (November 11, 2026; refined November 12):**
Retain the original Scala `CelFunc(name: String, params: List[Cel])`,
`CelOp`, and `Call(name, call: String, args)` AST nodes. No additional public
AST variants, typed operations, or rewritten workflow control flow.

**Standard-library functions use their existing CEL names verbatim.** The DSL
exposes ordinary string constants organized by module and a separate
top-level `us.awfl.dsl` objects whose helpers return the original `CelFunc`:

```scala
import us.awfl.dsl.*

val now = Value[Double](time.now())      // CelFunc(CelFunctions.Time.Now)
val id = map.get(CelConst("payload"), "id") // CelFunc("map.get", ...)
val lower = text.to_lower(id)              // CelFunc("text.to_lower", ...)
```

`CelFunctions.Time.Now` is the string `"sys.now"` — not `"clock.now"`. Scala `time.now()` avoids shadowing Scala `sys`.
`CelFunctions.Map.Get` is `"map.get"`. Existing flat constants remain as
transitional source-compatibility aliases, but now point to the original CEL
names. `compiler-yaml` can emit these directly through its existing
`CelFunc` encoder; Rust resolves the exact names in a private enum and
validates support and arity. The helpers introduce no evaluated time, state,
or new step type on their own.

**Connector names in `Call` are different:** `CallOperations.HttpPost`,
`CallOperations.WorkflowRun`, `CallOperations.CallbackAwait`, etc. remain
backend-neutral IDs. The YAML backend maps them to Google connectors, while
the native backend maps them to server-owned operations.

This change supersedes the earlier proposed `value.length`,
`bytes.base64_encode`, `text.lowercase`, and `clock.now` aliases for
standard functions. Raw `Value("sys.now()")` occurrences can be replaced by
`Value(time.now())` without changing their surrounding workflow logic; native
clock evaluation still needs persisted nondeterministic results on resume.

### Expression inventory (standard names, no renaming)

| Standard CEL name | DSL constant | Required compatibility |
| --- | --- | --- |
| default | CelFunctions.Core.Default | Null handling and evaluation/error behavior |
| get_type | CelFunctions.Core.TypeOf | Google-compatible type labels, including bytes |
| int | CelFunctions.Core.ToInt | Conversion failures, truncation and range |
| string | CelFunctions.Core.ToString | Supported input types and formatting |
| len | CelFunctions.Core.Length | Lists, maps, strings, bytes as supported |
| map.get | CelFunctions.Map.Get | Key paths, missing values and null |
| map.merge | CelFunctions.Map.Merge | Shallow merge direction and precedence |
| list.concat | CelFunctions.List.Concat | Append one element, never flatten |
| json.decode | CelFunctions.Json.Decode | String/bytes, numeric representation and errors |
| json.encode | CelFunctions.Json.Encode | Produces JSON bytes, not a JSON string |
| json.encode_to_string | CelFunctions.Json.EncodeToString | JSON escaping and formatting |
| base64.encode | CelFunctions.Base64.Encode | Byte input and padded/unpadded output |
| text.encode / text.decode | CelFunctions.Text.Encode / Decode | Character sets and errors |
| text.split / text.substring | CelFunctions.Text.Split / Substring | Unicode/index behavior |
| text.to_lower | CelFunctions.Text.ToLower | Unicode case conversion |
| text.url_encode | CelFunctions.Text.UrlEncode | Reserved characters and escaping |
| time.format | CelFunctions.Time.Format | Epoch units, timezone/DST and formatting |
| uuid.generate | CelFunctions.Uuid.Generate | Native UUIDv4; materialized step results use existing checkpoints |
| sys.now (Scala `time.now()`) | CelFunctions.Time.Now | Epoch seconds; materialized step results use existing checkpoints |
| sys.get_env (Scala `environment.get()`) | CelFunctions.Environment.Get | Explicit server-trusted bindings |

**Implemented native CEL subset (November 11, 2026):** `len`, `default`,
`get_type`, `map.get` (including nested key paths), `map.merge` (shallow),
`list.concat` (append one), `json.decode`, `json.encode` (bytes), and
`json.encode_to_string` are direct compiler-emitted Rust helpers. The
eight newly added operations have runtime unit tests and an actual Cargo-
compiled Scala DSL fixture (`CelStdlibSmoke` + `test_cel_stdlib.py`) run from
the AWFL Scala consumer using `scripts/test-local.sh --full`. `map.get`
accepts exactly two arguments, with the second being a key or list of
string keys; it is a safe lookup rather than strict field access.

**Also implemented:** original `CelOp` `in` list/map membership and
signed integer `//` floor division, with checked zero/overflow semantics,
Scala→Cargo generated executable tests and Rust unit tests (16 total).

**Verified real nested workflow:** unchanged `awfl-us/workflows/evals/Product.scala`
compiles through the published DSL to native ABI 3. Three actual Rust
executables verify `Try`/`For`/`Fold`/`join`, list merging, zero-result input,
and two-variable Cartesian-product results. The current DSL itself still
emits `sys.log`, `len`, `default`, and `json.encode_to_string`; the native
compiler maps only these DSL-owned internal spellings, without implementing
unbounded legacy CEL lookup. Product's exception-path `log.write` is retained
in the manifest. A separate native `Try` fixture proves it through a
restricted Node logging dispatcher with journal/resume/crash-after-commit tests
merged in server PR #79; this is **not** yet live-effect validation.

**Remaining for native runtime:** implement `sys.get_env` trust boundaries,
`time.format`, broader text functions and Google-differential behavior;
child workflow/callback connectors and parallel scheduling. The existing
constructed-AST scan predates workflow source migration. The proposed
`clock.now` and `environment.get` aliases are no longer used.

### Call inventory and exact proposed mapping

| Existing target | Proposed ID | Primary locations / handling |
| --- | --- | --- |
| http.get | http.get | workflow-utils/utils/Http.scala |
| http.post | http.post | Same; structured body and auth |
| http.patch | http.patch | Same |
| http.delete | http.delete | Same |
| sys.log | log.write | dsl/Steps.scala Log helper |
| events.create_callback_endpoint | callback.create | workflow-utils/utils/Callback.scala |
| events.await_callback | callback.await | Same; durable suspend/resume |
| googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run | workflow.run | core.Workflow.execute, striders, Chain, ToolDispatcher, Agent, CliTools, Queue, Tasks, ToolDefs, ExtractLesson and others |
| googleapis.storage.v1.objects.get | storage.object_get | workflow-utils/services/Storage.scala |
| googleapis.pubsub.v1.projects.topics.publish | messaging.publish | workflows/helpers/Queue.scala |

These are the ten observed target spellings, not a promise to implement the entire Google connector library. Preserve provider-specific adapters behind storage.object_get and messaging.publish: their current bucket/topic/response semantics remain Google-specific until deliberately generalized. Do not disguise a provider-specific argument contract as universal.

HTTP results must retain body and also support code/headers for future consumers. Match current auth type/audience and x-user-id/x-project-id behavior through trusted server identity, refreshing tokens without persisting access tokens in workflow snapshots.

workflow.run needs synchronous result and asynchronous skip_polling modes. Normalize the Google workflow_id/argument/location/project_id/connector_params shape to a native contract with a compatibility adapter; define and test the asynchronous return shape against the old connector before rollout.

### Globals, operators and raw syntax
- Preserve the four original DSL Value placeholders unchanged (`WORKFLOW_ID`, `WORKFLOW_EXECUTION_ID`, `PROJECT_ID`, `LOCATION`). YAML substitutes them as before; the native compiler recognizes their existing value shapes and defers to trusted server bindings. Never add a DSL `CelGlobal` class.
- Preserve the distinction between GCP PROJECT_ID and tenant Env.projectId. Inject both from server-owned context. A resumed execution retains its workflow/execution/deployment identity.
- Normalize existing `CelOp` syntax (`==`, `!=`, `>`, `>=`, `<`, `<=`, `+`, `-`, `*`, `//`, `in`, `and`, `or` and special unary `not`) into **compiler-internal** binary/unary IR. Do not replace CEL operators in the DSL.
- Preserve short-circuit boolean evaluation. Define integer division, overflow, mixed number comparison, negative indexing behavior and errors through conformance tests.
- Replace Value("sys.now()"), CelConst("sys.now()") and Value("uuid.generate()") in utilities and application workflows.
- **Done for strict literal data:** recognize existing `CelConst` JSON-shaped
  lists/maps (including empty collections and nested data), `List.cel`, signed
  numeric literals, booleans, null, dotted input references, and quoted strings
  in the Rust *normalizer*. `LiteralCollectionsSmoke` + real Cargo executable
  test exact results and reject malformed/non-finite/overflowing data.
  **Not done:** expression-valued lists/maps containing arbitrary CEL source;
  do not interpret or silently reinterpret these strings. No DSL AST changes.
- CelStr.safe pre-escapes content for YAML. Normalize string contents once and let each backend escape its output; prevent double escaping.
- Unknown raw CEL fails Rust compilation with a migration suggestion. A small compatibility parser for the known literal/reference subset is acceptable during migration; a general CEL interpreter is not required.
- Replace WORKFLOW_ENV text substitutions with manifest/deployment bindings, including child workflow names and workflow-file bucket names.

## 3. Compiler design: Scala frontend, native Rust output

Keep authoring in Scala. compiler-rust should initially contain a Scala backend library plus a small Rust support crate. Rewriting Scala object loading/reflection in Rust would duplicate the frontend unnecessarily.

Pipeline:
1. Existing compiler loads and evaluates the Scala workflow builder.
2. Normalize DSL objects into a versioned backend-neutral IR: typed values, stable slots, operations, effects, blocks and source metadata.
3. Validate scope, references, supported operations, schemas and capabilities.
4. Lower into resumable basic blocks and continuations; emit actual Rust control flow/functions.
5. Cargo builds a native binary and immutable manifest. Runtime does not parse YAML/CEL or interpret an entire workflow graph.

Proposed repository layout:
- frontend/ (Scala normalization, validation and Rust emission)
- runtime/ (Rust value semantics, protocol and generated-code support)
- fixtures/ (DSL/IR/input/expected result and error cases)
- conformance/ (backend comparisons)
- docs/ (architecture, operation registry, protocol and benchmark results)

Extract shared normalization/registry support into dsl or a small shared compiler module once both backends use it; do not make compiler-rust depend on YAML serialization. Add --target yaml|rust to compiler while preserving its existing CLI entrypoint and schema/definition outputs.

### Identity and value model
- Allocate structural node IDs and symbol/slot IDs deterministically during normalization. Preserve DSL display names separately; branch/loop paths disambiguate repetitions.
- Resolve legacy hash-derived result references to compiler-private slots during the frontend run. Never recompute hashCode on resume. Repeated step labels and result/error/iterator references are legal: allocate a new stable slot for each binding, shadow its previous textual name only in the active lexical scope, and restore parent bindings after branches/loops. Unresolvable raw references still fail closed. Compiler-internal structural node IDs distinguish effects and checkpoints independently of display step names.
- Runtime instance key: execution ID + artifact digest + node ID + nested loop indices + branch path + invocation occurrence. Retry attempt is separate from logical effect identity.
- Use a tagged AwflValue for null, bool, signed integers, doubles, strings, bytes, lists and maps. serde_json::Value alone loses byte distinctions and may blur integer semantics.
- Persist a versioned tagged encoding; expose ordinary JSON only at API boundaries. Reject unsupported/non-finite values explicitly.
- Retain optional/missing distinctions and structured errors (message, optional code, tags/body/details) needed by existing catch logic.
- Pin compiled artifact, IR schema, runtime ABI and operation registry versions for every execution.

### Control-flow lowering
- Call: evaluate arguments once per logical attempt, persist effect intent, yield to server, resume into result/error slot.
- Return: finish the appropriate workflow scope. Test explicit returns inside nested constructs.
- For/ForRange: persist source list/range, cursor and accumulator. Scala upper bound is exclusive; YAML currently subtracts one. Cover empty and reversed ranges.
- Fold: initialize accumulator once; checkpoint cursor/accumulator without duplicating updates after resume.
- Switch: first true case, record chosen branch; preserve current no-match semantics initially and diagnose type mismatches.
- Try/Raise: **compiler and confirmed-HTTP server path implemented** using
  statically linked exceptional CFG edges, not lexical exception frames.
  Native Rust emits catch routes and recoverable-effect metadata (ABI 3/IPC2).
  Merged server PR #78 atomically journals received HTTP 4xx/5xx failures and
  redirects to the compiled handler; uncertain outcomes, cancellation and
  infrastructure failures are never silently caught. Compiler PR #7 fixes
  the special catch-only program-counter case. A separate unmerged server
  PR #79 adds the original DSL's `sys.log` -> `log.write` handler, verified
  through real Rust + Node + mock-transaction tests. Google exception/error
  taxonomy, remote uncertainty and **all** crash-window parity remain gated.
- Block/FlatMap: preserve output aliasing; FlatMap is a projection of an existing step, not an additional remote invocation.
- Parallel: isolated branch frames, shared result slots and durable join.
- ParallelFor: bounded concurrency, per-iteration frame, atomic result collection. Existing YAML appends into a shared list, so do not assume input-order results. Preserve observed ordering semantics or introduce an explicitly versioned ordered mode.
- Do not persist language futures, stack pointers or native memory. Persist explicit continuation/frame data only.

## 4. Server owns durable execution

Extend existing server execution services with a backend interface: start, inspect, cancel, resume and receiveCallback. Store backend and artifact digest on registrations/status records. Route web, Agents MCP and nested execution through it; retain Google as a backend during migration.

The Rust binary runs as a server-managed worker process, initially a child process alongside the execution worker to use local framed IPC. The public API may remain separately deployed. Reuse existing producer/consumer scheduling infrastructure where appropriate, but keep workflow continuation state distinct from terminal-command request records. The project consumer lock is not an execution lease.

Proposed protocol:
- Start/Resume: execution identity, pinned manifest, trusted global bindings, checkpoint version, frame/slots and pending effect result.
- Yield: effect intent(s), changed values, continuation, trace batch and expected checkpoint version.
- Continue: durable acknowledgment and effect outcomes.
- Suspend: callback/child/retry deadline and committed continuation.
- Complete/Fail/Cancel: final state and outputs/errors.

Use length-delimited structured messages with request IDs and size limits. Rust computes pure blocks locally; it communicates at durable boundaries, not every arithmetic/map operation. Server validates capability/version/ownership before accepting messages. stdout is protocol-only; stderr carries logs.

### Persistence contract
Start with existing Firestore and object storage through server adapters. Benchmark before committing to a different database.

Proposed project-scoped records:
- executions/{id}: backend, artifact/version, tenant/session/parent, state, checkpoint version, lease owner/epoch/expiry, deadline and terminal result/error.
- executions/{id}/checkpoints: continuation, branch frames, loop cursors, live slot values or references, nondeterministic observations, committed sequence.
- executions/{id}/effects/{effectKey}: prepared/running/completed/failed/unknown outcome, input digest, attempt history, idempotency key, output/error.
- callback inbox and child links: durable payload/delivery sequence, waiter and deadline.
- trace chunks/value versions: per-step started/completed/failed events and changed values, indexed by logical node instance.

The server is the persistence authority. Keep small live values inline; store large bodies/blobs by immutable digest reference before committing the pointer. Chunk traces and values to avoid unbounded documents/hot spots. Define retention/redaction separately from recovery state.

Persist completed logical-step results and value changes in batched trace segments; require a durable checkpoint before external effects, suspend, yield and completion. Pure steps since the last checkpoint may recompute after a crash. For users requiring every step immediately durable, offer a strict mode and measure its latency. Do not promise immediate per-step durability while buffering writes.

### Crash/replay invariants
1. Persist prepared intent and continuation before dispatching an external effect.
2. Persist completed effect output and the next checkpoint atomically when feasible.
3. Resume reads completed outputs instead of reissuing effects.
4. A monotonically increasing lease epoch/fencing token rejects stale worker commits; checkpoint updates use compare-and-swap.
5. Record clock/UUID observations by expression occurrence and dynamic instance. Each new evaluation may produce a new value, but replay of the same occurrence reuses it.
6. Callback payloads are committed before acknowledging delivery; arrival before await must be retained.
7. Terminal states are monotonic. Cancellation races cannot resurrect a completed/cancelled execution.

Exactly-once arbitrary HTTP effects are impossible when the target succeeds but the worker dies before recording the response. Use receiver-supported idempotency keys and reconciliation for AWFL endpoints. Otherwise expose unknown outcome and an explicit retry policy; never claim universal exactly-once delivery. Persist retry deadlines/attempts and avoid rerunning earlier completed steps.

### Server integration details
- Reuse createWorkflowExecution trusted-env assembly, selecting backend from deployed workflow metadata.
- Retain workflowExecsBySession/workflowExecLinks/workflowExecStatus as compatibility projections. Server performs registration/link/status transitions transactionally. Migrate corresponding DSL-generated HTTP bookkeeping to semantic operations or suppress only explicitly marked scaffolding; never delete arbitrary calls based on display names.
- Add a rust_execution callback kind beside mcp_request and Google forwarding. Preserve the existing callback ID API and http_request.body result envelope.
- Implement buffered delivery slots, timeout, duplicate handling and repeated awaits intentionally; do not silently reduce Google callback behavior to a single-use promise.
- Waiting releases compute resources; persisted wakeups resume the same artifact/frame. Callback, child completion and timer wakeups must survive server restarts and duplicate delivery.
- workflow.run creates/link-registers the child once using the parent's effect key. Parent waits durably or returns the documented async handle; dynamic child names resolve through the deployment registry.
- backend-aware stop routes cancel descendants according to existing requested scope; release session locks only when ownership is verified. Preserve terminal success rather than converting every not-found/already-completed response into Cancelled.
- HTTP, storage and Pub/Sub adapters execute under server-controlled identity with existing credential refresh. Do not embed credentials in generated binaries or traces.
- Expose existing status/history APIs plus step timeline, durable values and artifact/version metadata. Extend existing auth scope checks to all native execution reads/writes.
- Configure deployment, queues, service accounts, storage/indexes and worker resources through Terraform in server/infra.

## 5. Compatibility and performance strategy

This is native code generation with durable server-managed effects, not one HTTP RPC per DSL instruction. Persistent worker connections, cached binaries and pooled clients avoid process/container creation per step. Fuse pure computation while retaining logical source-map events.

Current all-Call retry wrapping is a migration concern. Model an explicit legacy retry policy to preserve old workflows; add a separately reviewed safer per-operation policy for new versions. Google documents http.default_retry as an idempotent-target policy (five retries with backoff), not a blanket guarantee for POST. Do not silently alter retry behavior during backend replacement.

Verify supported behavior with official reference contracts and small backend differential fixtures. In particular, test byte/string distinction, map/list semantics, missing fields, short-circuiting, errors, callback buffering and parallel assignments. Unknown capabilities fail deployment, not a late production step.

Performance measurements must separate:
- compile/build/deploy time from cold worker start and warm execution;
- local computation from IPC, persistence commits, queues and external service time;
- checkpoint frequency/write amplification from total workflow latency.

Run equal-input benchmarks: 1,000 pure operations, 100 local mock HTTP steps, nested child workflows, callback round trip, sequential/parallel loops, representative ProjectManager agent turn with deterministic stub LLM/tool responses. Report p50/p95/p99, throughput, storage operations/bytes, CPU/RSS and recovery time. Proposed acceptance goals (not measured claims): warm pure 1,000-step fixture under 100 ms excluding trace flush; at least 5x lower orchestration-only p95 than Google on the mock-effect fixture, with equivalent durability. If Firestore dominates, measure batching and a server-owned WAL/database adapter before relaxing guarantees.

## 6. Implementation phases and completion gates

### Latest checkpoint — aligned helpers and native time.format (Nov 12)

The prior 55/66 constructed-AST audit was a **stale helper classpath**:
compiler-rust's test runner loaded published `workflow-utils:0.3.5` even
when `workflows/main` classes were rebuilt with locally fixed helpers.
Merged workflow-utils #6 already repaired unbound `example` prompts and
undefined cache-404 results. An audit with identical helper artifacts in
both workflow compilation and scanner initially gave **56/66 emitted**;
the remaining nine first blockers were the already-declared `time.format`.

The native compiler now lowers `time.format` to pure Rust ISO-8601 formatting
with optional IANA timezone, DST and microsecond precision. Real generated
ELF, Rust unit, Scala compiler, replay and error tests passed. The **new
constructed-AST result is 65/66 source-emitted**, 0 unknown operation IDs,
0 class-loading errors, leaving only an `initEnvResult_*` lexical-scope
reference in `evals-experiments-SweBenchLite`. Do not silently widen scope.
Reconsider that workflow's structure only after a Google YAML scope audit.
CI now pins the matching helper commit and uses its test-only local snapshot
in both compilation and reflection scanning. **Source emission does not
establish full native executable compatibility or production readiness.**

### Newer checkpoint — November 12, 2026 (draft parallel implementation)

**Follow-on compatibility increment:** Native `text.substring(source,start,end)`
now has a compiled Rust implementation with bounds/negative-index and Unicode
tests, rejecting missing end and incorrect types without changing the public
Scala DSL. A new standalone generated executable passes process-level tests,
and compiler CI covers it. Constructed-AST source emission against cached
unchanged classes improved **54/66 to 55/66**, leaving 11 first-blocking
raw references (`example` and cache helper result symbols). Next investigate
the scope and actual value lifetime of those references, compare with YAML,
and only then decide whether to correct compiler scoping or workflow helper
definitions. The emitted sources are not evidence of full runtime parity.

**Reference blocker diagnosis:** All 11 first blockers trace to two already
corrected helper patterns: unbound `Spec.init(Resolver("example"))` in LLM
schema prompts (nine definitions), and an out-of-scope failed Firebase read
result on 404 (two definitions). [workflow-utils PR #6](https://github.com/awfl-us/workflow-utils/pull/6)
merged literal-schema and null-on-404 fixes. The workflow audit still uses
older `workflow-utils` dependencies, even in CI on the latest workflows main.
Align the released helper package and consuming classpaths, then repeat the
exact same AST audit; **do not** relax native undefined-symbol validation.

The historical November 11 checkpoint following this section records what was
merged **then**, not the present state of the new parallel feature branches.
Compiler-rust [#18](https://github.com/awfl-us/compiler-rust/pull/18)
(`f4f4ff7`) and server [#82](https://github.com/awfl-us/server/pull/82)
(`67e1184`) implement independent Rust branch entries, isolated lexical
snapshots, bounded child worker concurrency, pinned durable lane records,
parent join/fork journals, deterministic indexed `ParallelFor` results,
confirmed failure propagation and replay after committed child effects.
Actual generated Rust binaries execute through the native Jobs worker.

Compiler and server CI are green on the preceding commits; local native server
discovery reports **85 tests, 63 passing, 0 failing, 22 skipped** before the
new emulator cases. **Four real Firestore emulator tests** now pass with generated Rust
ELFs: concurrent child effects, duplicate-lease rejection and fresh-store
replay; recovery after a parent crash following child commits; empty/one/many
indexed `ParallelFor`; and terminal parent cancellation fencing. This does **not** establish separate-process takeover, recursive
child cancellation, distributed Jobs or general-workflow correctness.

The next acceptance gates are: real emulator transactions/lease contention
and crash windows; child/root cancellation plus unresolved remote outcome
policy; nested and cross-process fanout budget; compatibility against
unchanged real workflows and YAML error semantics; authenticated GCP canary;
then latency, throughput, cost and rollback evaluation. Parallel production
routing remains disabled. See [parallel implementation design and gate
checklist](durable-parallel-fork-join.md).

### Implementation checkpoint — 2026-11-11 (tested, still experimental)

**Merged / completed within tested scope:** original DSL constant-only change
and YAML mapping; compiler-rust #1–#6 (one Scala-normalized native CFG,
Rust ABI 3/IPC 2, `Switch`/`ForRange`/`For`/`Fold`/`Return`, constrained
CEL/map/list/JSON, raw literal collections, `in` and checked `//`,
`Try`/`Raise`, typed live slots and deterministic effect IDs); server #72/#78
(authenticated shared launcher, GCS artifact mapping, Cloud Tasks, Firestore
journal/leases and confirmed HTTP 4xx/5xx catch); workflows #3 (static
musl publisher). Unchanged production-source `LoadConversationHistory` and
`Product` compile and run the supported paths as real Rust executables.

**Latest merged exception-handling increment:** compiler-rust
[#7](https://github.com/awfl-us/compiler-rust/pull/7) fixes the
exception-only CFG PC and generates a default-DSL-`Try` `log.write` fixture;
server [#79](https://github.com/awfl-us/server/pull/79) implements a restricted
authenticated log handler and extends the native operation allowlists.
The AWFL Scala consumer's `scripts/test-local.sh --full` and GitHub Rust/Scala
jobs passed. Local real Rust binary/Node server tests returned **34 pass,
0 fail, 3 skipped**, including log journaling, original rethrow and
post-journal-commit worker interruption. This tests a *subset* of failure
windows, not exactly-once external log delivery or live production traffic.

**Isolated live GCP canary:** a static-musl pure `NativePilot` completed
Cloud Tasks -> tagged Jobs -> Firestore with `[2,3,9,9]` and one observed
6,836 ms enqueue-to-result interval, including cold startup. It did not
exercise live HTTP, callbacks, child workflows, user-facing APIs, native
logging, or general agent performance. The Cloud Run Jobs normal revision
retains `AWFL_NATIVE_EXECUTION_ENABLED=0`.

**Still unimplemented or materially unverified at the November 11 baseline:** `Parallel`/`ParallelFor`,
callbacks, child workflow launch/await, storage/Pub/Sub adapters, many CEL
functions/overloads, version-safe environment globals,
Google differential error/retry semantics, remote-uncertain outcome
reconciliation, authenticated shared-launcher end-to-end audit, safe OS
sandbox, lease/worker/orphan reliability, and representative performance.
No workflow source migration and no regular production traffic switch.

**Next gate order:** (P0) compiler/server pair **merged** with green CI;
(P1) constructed AST compatibility inventory plus differential fixtures;
(P2) server effect/child/callback reliability and sandbox; (P3) real durable
parallel scheduling; (P4) latency/throughput/security acceptance and a
rollback-capable native canary. See
[current-state-and-roadmap.md](current-state-and-roadmap.md) for detailed
per-stage acceptance tests and dependencies.

**Compiler CLI follow-through:** `NativeMain` reuses existing `WorkflowLoader`
for ABI3/IPC2 artifacts, while a shared `compiler --target rust` entrypoint
remains unimplemented to avoid a cross-module compiler dependency cycle.

### Phase 0 — Freeze baseline and executable inventory
- Capture exact commit SHAs and dependency versions for a reproducible fixture bundle.
- Enumerate operations by walking constructed ASTs, including nested BaseValue/Product/list/map/optional values.
- Check source strings/raw expressions as a supplementary migration lint.
- Record current YAML results/errors and latency for synthetic fixtures and one agent workflow.
Gate: every observed operation/raw expression classified; baseline artifacts reproducible.

### Phase 1 — Add operation constants only; preserve the DSL API
- Add only `CelFunctions` and `CallOperations` constant names to the DSL and substitute them in existing helpers. Revert all introduced typed AST classes and the DSL operation registry.
- Update YAML to map these constants back to Google names while preserving literal Google names for existing, not-yet-migrated workflows.
- Migrate application workflows and workflow-utils **by substituting operation-name constants**, not by changing AST constructors or CEL operator nodes.
- Match constants in the Rust frontend and translate the existing `CelOp` form into compiler-internal IR; keep unknown operations explicitly unsupported.
- Audit hidden clock/UUID/raw expressions and deployment substitutions separately without modifying DSL AST types.
Gate: YAML fixture behavior unchanged, existing workflow Scala code compiles, native generated Rust conforms, and unsupported raw syntax produces actionable diagnostics.

### Phase 2 — Native backend MVP
**Implemented for tested sequential/exception subset**, not complete
Google semantics or the shared compiler CLI.
- Introduce normalization/IR, stable slots/IDs, Rust emitter and runtime value/error library.
- Extend compiler target selection and preserve schemas/definitions.
- Support sequential control flow, projection, switch, loops/fold and exceptions with mock effects.
Gate: generated Rust compiles and executes conformance fixtures; constant-only workflows supported; deterministic manifest/source maps.

### Phase 3 — Server execution and persistence
**Partially completed:** effect intents/journaling/lease fencing and
confirmed HTTP catches. Logging is merged and locally tested;
remote uncertain-outcome reconciliation remains unsolved.
- Add backend dispatch, artifact registry, worker IPC, leases, checkpoint/value/effect storage and traces.
- Add HTTP/log adapters and legacy retry policy; use the existing slot checkpoints for step results.
- Test crash points before dispatch, after dispatch, before commit and after commit.
Gate: resume yields correct results, committed effects do not repeat, stale workers cannot commit, unknown outcomes are visible.

### Phase 4 — Callbacks, children, parallelism and connectors
**Partially implemented on draft branches (#18/#82):** native parallel branch
dispatch and fork/join have real compiled-binary integration tests, but
callbacks, children, cancellation and distributed reliability remain pending.
IR lowering alone is not sufficient for production readiness.
- Implement callback inbox/timers, nested workflow sync/async execution, cancellation propagation.
- Add storage and messaging adapters with existing response envelopes.
- Add parallel branches/loops, bounded scheduling, atomic joins/results and persisted errors.
Gate: early/duplicate/late callbacks, timeout/restart, duplicate child-start, racing branches and cancellation fixtures pass.

### Phase 5 — Integrate and pilot
- Build and publish digest-pinned native artifacts through existing GCP IAM/WIF deployment authority, never an end-user-facing server registration API. Static musl publication and a pure canary are verified; automated manual-dispatch access remains to be confirmed.
- Keep YAML/native backend choice per workflow deployment; pin active execution versions.
- Start with a deterministic small workflow, then ProjectManager with its complete helper dependency closure.
- Avoid duplicating live LLM/tool side effects in shadow runs; replay captured effects or use isolated test targets.
Gate: web + Agents MCP start/history/instruction/cancel flows work, performance goals assessed, no semantic blockers.

### Phase 6 — Rollout and optimize
- Route new executions gradually to Rust; keep old Google executions running to completion.
- Roll back routing for new executions without reinterpreting existing native checkpoints.
- Optimize measured bottlenecks, retain artifact/runtime versions for resumable executions, then retire Google deployment paths when unused.
Gate: recovery drills, operational dashboards and rollback procedure demonstrated.

## 7. Focused test matrix and decisions to settle in implementation

Must-have tests: each registry operation and error shape; all DSL step kinds; duplicate step names and identical subtrees; nested loops/parallel branches; missing/null/bytes/large integers/Unicode; timezone DST; expression nondeterminism; input/output schema fidelity; HTTP auth/timeout/non-2xx; retry persistence; artifact pinning; worker death/fencing; callback races; child launch deduplication; tenant isolation; terminal cancellation; large-value blob integrity.

Open points requiring measured evidence, not assumptions:
- Exact observed Google conversion/formatting/error behavior for the used overloads.
- Parallel list result ordering relied upon by actual workflows.
- Async child connector return shape and caller expectations.
- Firestore throughput/latency under the selected durability policy.
- Best worker hosting choice within existing server infrastructure; avoid a new cold-start-per-step system.
- Whether to expose strict per-step durability as a deployment option or always use batched pure-step trace persistence.
- Full dependency closure and published-version mismatch: the native frontend
  currently pins published DSL v0.3.4 and a fixed workflows commit for tested
  sources; check the actual application dependency lock before claims about
  the current head of every workflow repo.

## 8. Reference sources

Repository paths referenced above are under https://github.com/awfl-us/{repo}/blob/main/.
Key files: dsl/{Cel,Steps,Value,Global,Workflow}.scala under src/main/scala/us/awfl/dsl; compiler/{Main,WorkflowLoader,YamlGen}.scala under src/main/scala/us/awfl/compiler; compiler-yaml/{Compiler,ValueEncoders,Constants,Workflow}.scala under src/main/scala/us/awfl/yaml.

Official contracts to use for conformance:
- https://docs.cloud.google.com/workflows/docs/reference/stdlib/overview
- https://docs.cloud.google.com/workflows/docs/reference/stdlib/http/default_retry
- https://docs.cloud.google.com/workflows/docs/reference/syntax/retrying
- https://docs.cloud.google.com/workflows/docs/creating-callback-endpoints
- https://docs.cloud.google.com/workflows/docs/reference/syntax/parallel-steps

The plan preserves the distinction between source-observed behavior, proposed architecture, and compatibility details that still require executable tests.

## Appendix: literal CelFunc source locations

### base64.encode

- [workflows/src/main/scala/us/awfl/workflows/helpers/Queue.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Queue.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala)

### default

- [dsl/src/main/scala/us/awfl/dsl/Value.scala](https://github.com/awfl-us/dsl/blob/main/src/main/scala/us/awfl/dsl/Value.scala)
- [workflow-utils/src/main/scala/us/awfl/utils/Exec.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Exec.scala)
- [workflow-utils/src/main/scala/us/awfl/utils/strider/StriderObj.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/strider/StriderObj.scala)
- [workflows/src/main/scala/us/awfl/workflows/EventHandler.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/EventHandler.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/Tasks.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Tasks.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/tasks/TaskRunners.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/tasks/TaskRunners.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/Agent.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/Agent.scala)

### get_type

- [workflow-utils/src/main/scala/us/awfl/services/Storage.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Storage.scala)
- [workflow-utils/src/main/scala/us/awfl/utils/Callback.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Callback.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/Tasks.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Tasks.scala)

### int

- [workflows/src/main/scala/us/awfl/workflows/evals/Eval.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Eval.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala)

### json.decode

- [workflow-utils/src/main/scala/us/awfl/ista/Convo.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/ista/Convo.scala)
- [workflow-utils/src/main/scala/us/awfl/services/Llm.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Llm.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/Datasets.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Datasets.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala)
- [workflows/src/main/scala/us/awfl/workflows/traits/ToolWorkflow.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/traits/ToolWorkflow.scala)

### json.encode

- [workflows/src/main/scala/us/awfl/workflows/helpers/Queue.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Queue.scala)

### json.encode_to_string

- [dsl/src/main/scala/us/awfl/dsl/Value.scala](https://github.com/awfl-us/dsl/blob/main/src/main/scala/us/awfl/dsl/Value.scala)
- [workflow-utils/src/main/scala/us/awfl/services/Storage.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Storage.scala)
- [workflow-utils/src/main/scala/us/awfl/utils/Prakriya.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Prakriya.scala)
- [workflows/src/main/scala/us/awfl/workflows/context/ContextCollapser.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/context/ContextCollapser.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/Eval.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Eval.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/Experiment.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Experiment.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/Agent.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/Agent.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/CliTools.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/CliTools.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala)
- [workflows/src/main/scala/us/awfl/workflows/traits/ToolWorkflow.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/traits/ToolWorkflow.scala)

### len

- [dsl/src/main/scala/us/awfl/dsl/Value.scala](https://github.com/awfl-us/dsl/blob/main/src/main/scala/us/awfl/dsl/Value.scala)
- [workflow-utils/src/main/scala/us/awfl/services/Llm.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Llm.scala)
- [workflow-utils/src/main/scala/us/awfl/utils/Exec.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Exec.scala)
- [workflows/src/main/scala/us/awfl/workflows/EventHandler.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/EventHandler.scala)
- [workflows/src/main/scala/us/awfl/workflows/Summaries.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/Summaries.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/Eval.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Eval.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/Queue.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Queue.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/Tasks.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Tasks.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/ToolDefs.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/ToolDefs.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/tasks/TaskRunners.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/tasks/TaskRunners.scala)

### list.concat

- [workflow-utils/src/main/scala/us/awfl/services/Llm.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Llm.scala)
- [workflows/src/main/scala/us/awfl/workflows/EventHandler.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/EventHandler.scala)

### map.get

- [dsl/src/main/scala/us/awfl/dsl/Value.scala](https://github.com/awfl-us/dsl/blob/main/src/main/scala/us/awfl/dsl/Value.scala)
- [workflow-utils/src/main/scala/us/awfl/ista/Convo.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/ista/Convo.scala)
- [workflow-utils/src/main/scala/us/awfl/utils/Callback.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Callback.scala)
- [workflows/src/main/scala/us/awfl/workflows/EventHandler.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/EventHandler.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/Eval.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Eval.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/Tasks.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Tasks.scala)
- [workflows/src/main/scala/us/awfl/workflows/traits/ToolWorkflow.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/traits/ToolWorkflow.scala)

### map.merge

- [workflows/src/main/scala/us/awfl/workflows/evals/Product.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Product.scala)

### string

- [workflow-utils/src/main/scala/us/awfl/utils/Locks.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Locks.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/Tasks.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Tasks.scala)
- [workflows/src/main/scala/us/awfl/workflows/helpers/tasks/TaskRunners.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/tasks/TaskRunners.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/Agent.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/Agent.scala)
- [workflows/src/main/scala/us/awfl/workflows/tools/Tasks.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/Tasks.scala)

### text.decode

- [workflow-utils/src/main/scala/us/awfl/services/Storage.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Storage.scala)

### text.encode

- [workflows/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/tools/SpawnCrawlers.scala)

### text.split

- [workflows/src/main/scala/us/awfl/workflows/evals/Datasets.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Datasets.scala)

### text.substring

- [workflows/src/main/scala/us/awfl/workflows/evals/Eval.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/Eval.scala)

### text.to_lower

- [workflows/src/main/scala/us/awfl/workflows/helpers/Files.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/helpers/Files.scala)

### text.url_encode

- [workflow-utils/src/main/scala/us/awfl/services/Storage.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/services/Storage.scala)
- [workflows/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala)

### time.format

- [workflow-utils/src/main/scala/us/awfl/utils/Prakriya.scala](https://github.com/awfl-us/workflow-utils/blob/main/src/main/scala/us/awfl/utils/Prakriya.scala)

### uuid.generate

- [workflows/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala](https://github.com/awfl-us/workflows/blob/main/src/main/scala/us/awfl/workflows/evals/experiments/SweBenchLite.scala)

