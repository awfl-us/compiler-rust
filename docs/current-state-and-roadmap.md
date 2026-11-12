# Native Rust execution: verified state, compatibility matrix, and roadmap

**Evidence date:** 2026-11-11. **Status:** experimental, opt-in backend; **not production-ready**. This document distinguishes *merged*, *verified on a feature branch*, and *unimplemented*. It does not claim that passing tests equals Google Workflows parity.

## November 12, 2026 — scoped step/result symbols (newer audit)

The source DSL intentionally allows repeated step labels. The YAML backend
globally uniquifies *step labels* using numeric suffixes; its result
references are still textual. Rust now gives every result, Try-error and
iterator occurrence a distinct compiler-private slot, and uniquely suffixes
human-readable step labels. Textual value references are resolved at
normalization time within the active lexical scope, with branch/loop
shadowing and restoration. Structural node IDs identify external Calls and
continuations independently of labels. No public DSL or special persistence
changes are required.

After removing the previous 18 first-blocking symbol collisions, the
66-definition constructed-AST audit has 0 collision failures, 0 unknown
operations and 0 class-loading errors; 4 emit Rust source and 62 first
block on 37 trusted execution globals, 15 Parallel/ParallelFor nodes and
10 unsupported raw CEL/reference cases. These are first blockers only; no
claim of 66 full native executables is made. The older audit below and
the existing per-workflow table remain historical pre-fix snapshots.

Compiler tests verify repeated Call labels, structurally equal step results,
independent branch bindings, repeated Try-error symbols and nested iterator
shadowing. The generated Rust process test checks two same-named Calls
preserve distinct slots through two resumptions.

## November 12, 2026 — canonical operation migration update

The sections below document the original November 11 execution foundation
and are retained for historical context. **The newer source-compatibility
measurement is in [canonical-operation-compatibility.md](canonical-operation-compatibility.md).**
After switching AWFL workflows and helpers to top-level standard CEL
helpers and generic Call operation IDs, the constructed-AST audit now finds
**4 of 66 workflows emit Rust source, 62 remain blocked, and 0 have class
loading errors or unknown CEL/Call identifiers** (prior baseline: 2/66).
First blockers: 29 trusted execution globals, 18 step-result symbol collisions,
14 Parallel/ParallelFor, and 1 raw CEL reference.
Clock/UUID functions are compiled directly after PR #14 and require
no special journal; existing materialized result slots are checkpointed.
This is source emission only—not completed native binaries or live workflows.

DSL v0.3.5, YAML compiler v0.3.2, and migrated workflow-utils v0.3.5
preserve the old DSL AST while matching canonical standard function names.
The YAML backend resolves generic connector IDs to Google names.
The migrated application workflow source is merged in workflows PR #8.
Future changes to Scala workflow code must remain name-level substitutions;
remaining compiler/runtime work belongs in the respective backends and server.

## Executive view

- **Working native pipeline:** unchanged Scala AWFL DSL objects -> compiler-private normalized expressions and IR -> one statically generated Rust CFG -> content-addressed Cargo/ELF artifact with `runtime_abi: 3` -> `protocol_version: 2` JSON IPC -> server-managed durable external effects. No YAML/CEL interpreter is involved in native execution.
- **Original public DSL remains intact:** `CelFunc(name, params)`, `CelOp(left, op, right)`, `Call(name, call, args)`, `Try`, `Raise`, `For`, `Fold`, `Switch`, `Parallel` and `ParallelFor` retain their shapes. Only string namespaces `CelFunctions` and `CallOperations` were added. Never add public typed AST variants as part of this compiler. The name-only application source migration is in workflows PR #8.
- **Verified real workflows:** unchanged `LoadConversationHistory.scala` compiled to native Rust and exercised HTTP intent/checkpoint/replay with no actual HTTP; unchanged `evals/Product.scala` compiled to three real Rust binaries returning 2 (single), 4 (Cartesian), and 0 (empty) results. The `Product` test covers its pure success path; its failure-path logging is tested with a separate real DSL-built `Try` fixture.
- **Durable effects:** effect intent is persisted before dispatch; outcome/error and resumed checkpoint are journaled together in Firestore under lease-epoch fencing. Successful outcomes aren't redispatched after a committed journal entry. Confirmed HTTP 4xx/5xx errors can enter a compiled `Try` catch; ambiguous transport outcomes cannot safely be treated as a confirmed failure.
- **Single live GCP pilot:** an isolated, **pure** statically linked `NativePilot` ran Cloud Tasks -> tagged Jobs -> Firestore, returned `[2,3,9,9]`, and survived duplicate delivery. Observed time was **6,836 ms for one cold-path sample**, not a useful percentile or a representative speedup measurement.
- **Production:** regular API/Jobs native routing remains disabled (`AWFL_NATIVE_EXECUTION_ENABLED=0`); Google Workflows remains the default. No user workflow migration or live effect traffic was authorized.

## Repository and change-state ledger

| Repository | Verified merged foundation | Latest work as of this snapshot | Deployment implications |
| --- | --- | --- | --- |
| `awfl-us/dsl` | PR [#2](https://github.com/awfl-us/dsl/pull/2), constants only, public AST unchanged | Application source name substitution deliberately deferred | No schema or DSL-API migration required for Rust fixtures |
| `awfl-us/compiler-yaml` | PR [#1](https://github.com/awfl-us/compiler-yaml/pull/1), canonical names map back to Google while old literals still work | Continue YAML differential fixtures | No YAML routing changes |
| `awfl-us/compiler-rust` | PRs [#1–#7](https://github.com/awfl-us/compiler-rust/pulls?q=is%3Apr+is%3Amerged), ABI3 frontend, CEL subset, literals, `in`/`//`, `Try`/`Raise`, unchanged Product, catch-only-PC fix and default-DSL logger regression | PR #7 merged as [`588dc2f`](https://github.com/awfl-us/compiler-rust/commit/588dc2f0a96ef037e35d55333b33b445cfb03f7a); full local suite and GitHub CI passed | No native production routing switch |
| `awfl-us/server` | PR [#72](https://github.com/awfl-us/server/pull/72), native launcher/persistence; PR [#78](https://github.com/awfl-us/server/pull/78), confirmed-HTTP-error recovery; PR [#79](https://github.com/awfl-us/server/pull/79), bounded `log.write`, capability gates and real Rust/Node/Firestore-compatible recovery tests | PR #79 merged as [`250f57e`](https://github.com/awfl-us/server/commit/250f57e58ff1e543dcd0efc4e3f5c005a899596e); post-journal crash test is green | Code merge is not live-traffic approval; keep native routing off |
| `awfl-us/workflows` | PR [#3](https://github.com/awfl-us/workflows/pull/3), private GCS static-musl native publisher and `NativePilot` | No application workflows migrated; published pilot is no-effect only | Keep Google workflow deployments active; GCS state and native artifact bucket are separate from cloud-env storage |

## Executable feature matrix

| Feature | Current disposition | Evidence / limitation |
| --- | --- | --- |
| Scala DSL loader and native ELF generation | **Verified** | Scala frontend, `NativeMain`, Cargo, manifest SHA-256 and strict Clippy |
| One CFG backend / IPC 2 / ABI 3 | **Verified** | Generated match arms and tagged-slot continuations; compiler emits no ABI 1/2 |
| `Switch`, `Return`, `ForRange`, `For`, `Fold`, `FlatMap`, nested effects | **Verified tested subset** | Process-level nested CFG, repeated suspension/resume, structural effect IDs, deterministic replay |
| List/map JSON literals, `in`, signed `//` | **Verified tested subset** | Actual emitted Rust executable and invalid-type/zero/overflow regression |
| `len`, `default`, type, map get/merge, list concat, JSON decode/encode | **Verified tested subset** | Rust value operations; remaining CEL overload and edge-case differences are not fully parity-tested |
| `Try`/`Raise` and nested rethrows | **Merged + tested** | CFG exception edges, explicit terminal `Failed`, pure CEL errors and confirmed HTTP failure recovery |
| Existing DSL default `Try` logger | **Merged and locally verified** | Compiler PR #7 fixes catch-only PC; server PR #79 journals bounded `log.write`; post-journal crash restart tested |
| Ordinary trusted HTTP GET/POST/PATCH/DELETE | **Local adapter and bounded integration tests** | Jobs IAM/OIDC, identity override, allowlist, HTTP-error catch; no production live-effect approval, uncertain outcome reconciliation missing |
| `Parallel`, `ParallelFor` | **Not implemented** | Need deterministic branch/iteration IDs, bounded scheduling, isolated state, durable join, failure/cancel semantics |
| Callback create/await, child workflow `workflow.run` | **Not implemented** | Need durable callback inbox, child-start idempotency, timeout and cancellation propagation |
| Storage object get, Pub/Sub publish | **Not implemented** | Existing Google connector argument/return contracts need explicit server-side adapters |
| Conversion/text/bytes/time/UUID/environment and arbitrary CEL | **Partial or not implemented** | Native clock/UUID are direct expressions; remaining overloads and authenticated env bindings need implementation; unknown names fail compile |
| Google equivalence and whole agent graph | **Not established** | No complete ProjectManager dependency closure, differential error/retry/parallel ordering or traced live comparison |
| Production isolation and operations | **Not established** | OS/network sandbox, lease maintenance, orphan sweeper, monitoring, benchmarks, rollback and failure drills |

## Latest verified test evidence and interpretation

1. `compiler-rust/scripts/test-local.sh --full` in the **AWFL scala GKE consumer** passed on the catch-only implementation now merged as PR #7, including Scala frontend, Rustfmt/unit tests/Clippy, unchanged `LoadConversationHistory` and `Product`, generated ABI3 crates, compiled CEL/loop tests, seven `Try` fixtures (including `default-log`) and effect/replay process tests. `AWFL_NATIVE_LOCAL_TESTS_PASS (--full)` recorded.
2. Node native server test selection including the **real Rust `default-log` executable**: **37 tests, 34 passed, 0 failed, 3 skipped**. Skipped tests depend on *other* optional binary env variables; not a 37/37 claim. The tests use an in-memory Firestore API with transaction semantics, not an actual remote Firestore load test.
3. Default `Try` integration: Scala `Raise` -> native catch -> `log.write` intent -> authenticated, bounded local log handler -> fenced `recordEffect` to a null result slot -> native resume -> original error persisted as terminal `Failed`. Tenant mismatch and a simulated crash **after journal commit** did not redispatch the log.
4. **Important limitation:** an output log is not globally exactly once. A crash **after the logger emits but before the journal commit** can duplicate the log; stable Cloud Logging `insertId` supports downstream deduplication. A synthetic post-journal crash does **not** prove all network or Firestore failure windows.
5. Rust and Node tests **do not** validate live production HTTPS transport, live Firestore contention, public authenticated app/MCP control flow, or latency parity against Google.

## Ordered next steps and acceptance gates

### P0 — Compiler/server `Try` logging integration (merged; rollout still gated)

- **Completed:** compiler PR #7 merged after Rust/Scala CI; fixed the catch-only CFG PC and generated/clippy-tested the real default-DSL-`Try` fixture. Server PR #79 merged after CI pinned to the merged compiler SHA, with bounded `log.write`, capability gates and Native Rust → Node journaling/terminal-error/replay tests.
- **Still required before live traffic:** verify the regular Jobs/API revisions keep `AWFL_NATIVE_EXECUTION_ENABLED=0`, monitor the post-merge deployment, exercise the public-authenticated launch route in a controlled test, and prohibit deployment of unsupported effect capabilities. A code merge must **not** flip the routing flag.

**Gate:** compiler and server PR tests green (achieved); production switch remains independently blocked pending P1–P4.

### P1 — Build a constructed-AST compatibility and Google comparison matrix

**Progress (November 11, after P0):** read-only scanner and reproducible
[compatibility report](constructed-ast-compatibility.md) now cover 66
instantiated definitions from unchanged application bytecode. Two emit Rust
source (`LoadConversationHistory`, `helpers-ToolDefs`); 64 fail closed at a
recorded first unsupported operation. The compiler now lowers existing
`FlatMap` wrappers without DSL/application edits. This is **source-emission**
coverage, not 2/66 executable conformance or Google differential parity.

**Following pure-operation increment:** the native emitter and runtime now
handle `ToInt`, `ToString`, and Unicode `TextLowercase` by generic DSL
operation ID, with generated Rust process and runtime unit tests. This does
not alter the last *unchanged-application-source* 2/66 compatibility inventory;
legacy name-only substitutions are deliberately separate from the compiler
implementation, and connector/clock/fork-join readiness gates remain open.

**Byte/text increment:** the native compiler also supports the existing
generic Base64 encoder and UTF-8/US-ASCII text encode/decode functions.
Remaining IANA charsets fail closed. This closes common pure dependencies
of queue/storage helpers but does not make storage, Pub/Sub or child-workflow
connector effects runnable.

- Enumerate *actual instantiated* workflows and dependencies from `workflows`, `workflow-utils`, DSL and compiler: `CelFunc`, `CelOp`, raw `CelConst`, callbacks and all `Call` names; include names hidden inside helpers. Preserve original DSL; migrate constants in application sources **later** as a simple name-only operation.
- Select increasingly realistic unchanged workflows (queue helpers, dispatcher, task/callback workflows, then full `ProjectManager` dependency graph). Label each as compiles, executes purely, executes with mock effects, differential-conformant, or blocked, with the exact missing operation and source location.
- Build golden YAML-vs-native fixtures for result, exceptions, retries, null/missing/bytes, JSON encoding, integer edges, Unicode, clock/UUID, globals, and HTTP 4xx/5xx vs uncertain failure. Use deterministic mocked services and avoid duplicate live agent/LLM/tool side effects.

**Gate:** prioritized workflows have reproducible green/broken rows and fail-closed compiler diagnostics; backend drift has an explicit, reviewable exception list rather than being hidden by fallback.

### P2 — Complete durable effects and reliability

- Implement per-operation policy for HTTP retry, idempotency, unknown outcome reconciliation, and recovery after worker deaths both before and after an external effect. Distinguish provider operation failure from infrastructure failure. Add live Firestore emulator / test-project contention, lease expiry/takeover, cancellation, and orphan sweeps.
- Implement approved `workflow.run` sync/async child behavior and `callback.create`/`callback.await` with atomic inbox and event de-duplication; integrate existing session history and cancellation semantics. Then add storage and Pub/Sub provider adapters with exact envelopes.
- Separate native executor into a restricted OS container/job with no mounted credentials, dedicated least-privilege identity, network policy, CPU/memory/process limits, artifact verification, and audited release handling.

**Gate:** crash-injection matrix and tenant isolation tests pass for every supported effect, including concurrent takeovers and actual remote uncertainty; no untrusted native process inherits server credentials.

### P3 — Durable `Parallel` and `ParallelFor`

- Keep original DSL nodes. Normalize them to compiler-private fork/join operations with stable branch/iteration identifiers and isolated frame slots.
- Store branch scheduling, cancellation, result/error states and join completion in server-owned durable records; use bounded concurrency rather than sequentializing or spawning unconstrained threads. Define legacy Google list ordering and shared-error semantics with differential fixtures.
- Verify duplicate delivery, repeated child branch launch, a crash before/after join, partial completion, sibling cancellation and large fan-out, without repeating committed effects.

**Gate:** a real unchanged workflow using `Parallel`/`ParallelFor` compiles and correctly resumes after multi-worker takeover and passes differential concurrency/order/error tests.

### P4 — Performance, canary and controlled rollout

- Benchmark warm/cold native startup separately from engine cost. Compare Google and Rust with equivalent durability, simulated effects and at least one agent workflow; report p50/p95/p99, operations, Firestore writes/bytes, CPU/RSS, cost and effect latency. The one 6,836 ms cold pilot is *not* a speedup claim.
- Validate GCS static-musl artifact publisher, pinned active manifests, authenticated `/workflows/execute`, session history, result/status/cancel, rollback of new starts and old-run continuation compatibility.
- Only then enable a feature-flagged, small IAM-approved native canary with explicit rollback, monitoring and retained Google fallback. Do not auto-migrate in-flight Google executions.

**Gate:** published runbook, measured baseline, demonstrated rollback, recoverable pinned artifacts and explicit sign-off on compatibility/security and operator ownership.

## Operational invariants

1. Users authenticate to Workflows; Jobs trusts only verified IAM/OIDC calls and server-carried tenant identity. The end user does not deploy binaries or invoke Jobs directly.
2. The server owns durable effect and result persistence. Rust performs pure CFG work, returning intents/continuations; no direct Google credentials or arbitrary network execution in the ELF.
3. Artifact digest, ABI, protocol and allowed operations are pinned per execution. No code change can reinterpret an active run's checkpoint with an incompatible artifact.
4. A prepared but **unknown** remote outcome is not silently retried or caught as a definitive error. Firestore epoch fencing is not remote exactly-once delivery.
5. The previous Google compiler and runtime stay available during a measured, reversible migration.

## Source documents

- [Implementation status](implementation-status.md)
- [Full implementation plan](native-rust-implementation-plan.md)
- [Compiler CFG design](native-control-flow-refactor-plan.md)
- [IPC ABI 3 / protocol 2](native-ipc-v2.md)
- [Server native execution guide](https://github.com/awfl-us/server/blob/main/workflows/native/README.md)
- [Workflows repository](https://github.com/awfl-us/workflows)


**Native clock and UUID increment (November 12, 2026):** Scala CEL
functions time.now() and uuid.generate() emit direct Rust calls using system
Unix seconds and random UUIDv4. Values are evaluated at each expression use;
repeated occurrences within a step may differ. Persisted step-result slots
continue to use the existing CFG continuation and server checkpoint, without
any new journal. TimeUuidSmoke and the native generated-executable test
verify the actual process output.
