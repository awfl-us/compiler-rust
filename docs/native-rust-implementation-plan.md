# Native Rust workflow compiler and server execution plan

Status: actively implemented; pure GCP canary verified, broader parity and rollout remain gated.
Original plan: 2026-11-10; progress review: 2026-11-11.
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

## 2. Backend-neutral operation names WITHOUT changing the existing DSL AST

**Architecture decision (2026-11-11, supersedes the original typed-DSL plan):**
`awfl-us/dsl` must retain **exactly** its existing `CelFunc(name: String,
params: List[Cel])`, `CelOp(left, op: String, right)` and `Call(name, call:
String, args)` representation, along with its original global Value
placeholders, CEL operators, and helpers. Do not add `TypedCall`, `CelFunction`,
`CelBinary`, `CelUnary`, `CelGlobal`, new typed literal AST, DSL-level
`OperationRegistry`, or operator/return-value rewrites. The commit that added
these classes was an overextension and has been corrected on the DSL PR.

The **only DSL functionality added** for Rust interoperability is a pair of
ordinary backend-neutral string constant namespaces:

```scala
CelFunc(CelFunctions.Length, myList.cel)
Call("send", CallOperations.HttpPost, obj(args))
```

Both constructors are the *original* constructors. Migration of existing
workflows consists of replacing their literal function/call-name strings with
these constants, without changing the expression/step shape or Scala type
signatures. `CelOp` is unchanged and remains string-operator based. Helpers in
`dsl/Value.scala` also only substitute name constants. The original
`dsl/Global.scala` placeholders remain unchanged; the Rust compiler handles
trusted context separately, without imposing new DSL classes.

`compiler-yaml` maps each constant's semantic wire string to its original
Google CEL function or connector name (`value.length` -> `len`, `workflow.run`
-> `googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run`).
During migration it **continues to accept the existing literal Google names**.
The Rust compiler matches the constant strings to an **internal-only**
operation enum with validated arity and native implementation support.
Unknown names fail with a workflow/node diagnostic. The Rust compiler does
not run a general CEL interpreter and does not silently fall back to YAML.

Keep operation semantics, arity/type checks, runtime capabilities, native
source generation and error parity in the compilers and native runtime, **not
in the public DSL**. New semantics are added there when backed by tests.

### Expression inventory and exact proposed mapping

| Existing name | Proposed ID | Required compatibility |
| --- | --- | --- |
| default | value.default | Null handling, evaluation/error behavior |
| get_type | value.type | Google-compatible type labels, including bytes |
| int | value.to_int | Conversion failures, truncation, range |
| string | value.to_string | Supported input types and formatting |
| len | value.length | Lists, maps, strings, bytes as supported |
| map.get | map.get | Single key and key-path list; missing versus null |
| map.merge | map.merge | Merge direction and shallow behavior |
| list.concat | list.concat | Append exactly **one element** to a copy of a list (even when that element is a list); never flatten |
| json.decode | json.decode | String/bytes, numeric representation, errors |
| json.encode | json.encode_bytes | Produces bytes, not a JSON string |
| json.encode_to_string | json.encode_string | Escaping and representation |
| base64.encode | bytes.base64_encode | Byte input/output string |
| text.encode | text.encode | Explicit/default encoding |
| text.decode | text.decode | Byte input and decode errors |
| text.split | text.split | Empty separator, empty segments, Unicode |
| text.substring | text.substring | Index units, bounds, end exclusion |
| text.to_lower | text.lowercase | Unicode behavior |
| text.url_encode | text.url_encode | Reserved characters; avoid double encoding |
| time.format | time.format | Epoch units, timezone/DST and formatting |
| uuid.generate | uuid.generate | Journal each evaluated occurrence |
| sys.now (raw strings) | clock.now | Epoch seconds; journal each occurrence |
| sys.get_env (backend-generated) | environment.get | Explicit allowed bindings, not arbitrary worker environment |

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
unbounded legacy CEL lookup. Product's exception-path `log.write` effect is
retained in the manifest and awaits a restricted server dispatcher.

**Remaining:** numerical/string conversion, base64, text helpers, time/UUID,
environment binding, and wider CEL operators are deliberately unsupported
until individually implemented and tested. The current JSON implementation
rejects bytes *as a value being encoded* and treats text/bytes as input to
decode; exact Google error and encoding-edge parity still need differential
tests. No existing workflows were migrated to new constants.

Twenty distinct literal CelFunc names occur in the four scanned Scala repositories. clock.now and environment.get additionally cover raw expression/backend-generated behavior. list.concat is also emitted directly by compiler-yaml. The source-location appendix below records all literal CelFunc sites, including comments where present; compiled-AST validation will be the authoritative coverage check.

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
- Resolve all legacy hash-derived result references to slots during the same frontend run. Never recompute hashCode on resume. Detect colliding/unresolved symbols rather than guessing.
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
- Try/Raise: **compiler implemented** using statically linked exceptional CFG edges,
  not lexical exception frames. Native Rust emits deterministic error catch
  routes and recoverable-effect continuation metadata (ABI 3/IPC v2). A
  confirmed remote HTTP failure must be journaled and atomically redirected
  to the nearest compiled catch in the server; uncertain effects, cancellation
  and internal infrastructure failures must never be silently caught. Full
  Google exception taxonomy and crash/reconciliation tests remain required.
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

### Implementation checkpoint — 2026-11-11 (tested, still experimental)

The architectural plan below is the target design, **not** a claim of completed
features. The Scala frontend emits pure native `Switch`, `ForRange`, `For`,
`Fold`, nested expressions, and explicit early `Return`.
The **single CFG backend** emits compiled Rust for nested `Switch`, `For`,
`Fold`, `ForRange`, pure operations and effectful `Call` steps. At an external
Call it returns a durable effect intent, successor CFG program counter and
live tagged slots. The server persists the result, journals the operation
and resumes the same compiled binary without replaying previous branches.
There is only runtime ABI **3** / IPC protocol **2**; no v1 emitters or
compatibility modes. The implementation and tests include dependent calls,
effectful loops/folds, nested branches and malformed continuation rejection.
See `docs/native-ipc-v2.md` for the wire contract. `Try`/`Raise`, `Parallel`
and complete CEL/Google semantics remain unsupported.
Companion draft PRs are
[dsl #2](https://github.com/awfl-us/dsl/pull/2) and
[compiler-yaml #1](https://github.com/awfl-us/compiler-yaml/pull/1).

[server PR #72](https://github.com/awfl-us/server/pull/72) is merged and
its opt-in native path is deployed. The server's existing shared launcher now
selects an IAM-published GCS artifact only for opted-in native workflows,
then creates a pinned Firestore execution and a single Cloud Task for the
OIDC-protected Jobs worker. Ordinary API/Jobs revisions still have native
execution **disabled**. Firestore persists bounded values and a per-effect
journal with epoch fencing, cancellation, and checkpoint recovery.

**November 11 verified GCP checkpoint:** the first live pilot request
reached Jobs but failed because its glibc-linked ELF could not spawn in
Alpine. [Workflows PR #3](https://github.com/awfl-us/workflows/pull/3)
merged the verified musl-static publisher. `NativePilot` now pins the static
SHA-256 `109b82a73175a46ada8b4c5f5059b724f40017a8321b2c76edec9e62396f984e`.
A second live Cloud Tasks → tagged Jobs → Firestore canary **completed**
with `[2,3,9,9]`, 6,836 ms observed end-to-end in one sample; duplicate
delivery did not advance the completed execution. The route had no external
effects. This proves the isolated pure canary only, **not** the public
end-user-authenticated launcher or workflow parity. The scripted canary is
in `scripts/live_native_pilot.py`; detailed evidence and remaining gates
are in `docs/implementation-status.md`.

**Enforced next gate:** end-user-authenticated shared-launcher/status tests,
crash/retry reconciliation, secure OS process sandbox, real authenticated HTTP
effect semantics, callback/child orchestration, `Try`/`Parallel` parity and
meaningful differential performance measurements. Do not enable native
production routing until these gates pass.

**Compiler API follow-through (November 11):** the existing `NativeMain`
CLI reuses `WorkflowLoader` and emits **only** ABI 3 / IPC protocol 2
crates. The old emitters and wire-v1 runtime were removed. CI compiles
and executes the actual CLI-produced crate to prevent future publisher
incompatibility. This is distinct from the still-unimplemented shared
`compiler --target rust` CLI dispatch, which requires dependency graph
restructuring or a versioned frontend boundary to avoid a compiler cycle.

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
- Introduce normalization/IR, stable slots/IDs, Rust emitter and runtime value/error library.
- Extend compiler target selection and preserve schemas/definitions.
- Support sequential control flow, projection, switch, loops/fold and exceptions with mock effects.
Gate: generated Rust compiles and executes conformance fixtures; constant-only workflows supported; deterministic manifest/source maps.

### Phase 3 — Server execution and persistence
- Add backend dispatch, artifact registry, worker IPC, leases, checkpoint/value/effect storage and traces.
- Add HTTP/log adapters, legacy retry policy and clock/UUID journaling.
- Test crash points before dispatch, after dispatch, before commit and after commit.
Gate: resume yields correct results, committed effects do not repeat, stale workers cannot commit, unknown outcomes are visible.

### Phase 4 — Callbacks, children, parallelism and connectors
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
- Full dependency closure and published-version mismatch: compiler currently pins 0.3.1 dependencies while this review reads repository HEADs.

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

