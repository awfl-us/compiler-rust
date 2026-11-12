# Native Rust execution: verified state, compatibility matrix, and roadmap

## November 12 — server-side distributed fork admission follow-up

The paired **draft** [server PR #82](https://github.com/awfl-us/server/pull/82)
now has per-root, cross-process **eight-slot Firestore lease admission** for
compiled native parallel workflow execution. This closes the previous gap
where each Node/Jobs process independently enforced its own eight-permit
in-memory budget. The existing local FIFO semaphore remains, while durable
admission uses eight independently transacted slot documents per root, with
owner/epoch fencing, root authorization, 120-second leases, 10-second renewal,
and fail-closed acquisition timeouts. Nested fork coordinators do not hold
slots while they wait for child effects.

**Local verification:** all seven dedicated budget tests pass; all eight real
Firestore emulator tests pass, including two independent processes racing
for one slot and a crash with successful post-expiry takeover. The existing
three-level Scala-generated Rust fork also passes with distributed permits
enabled. The full server native Node suite reports **72 passed, 32 skipped,
0 failed**, with emulator tests run separately rather than skipped overall.

**Important limits:** These slots bound admitted work *within one root* across
processes, not aggregate fleet-wide work across separate workflows. Expiring
an in-flight permit cannot undo a previously dispatched HTTP request or
forcibly kill a stuck subprocess; ambiguous effect outcomes still require
reconciliation. Finished roots currently leave a bounded number of idle
permit documents, pending a lifecycle cleanup policy. Live Cloud Tasks/Jobs
canary, workflow differential parity and global fleet controls remain gates;
the production native routing flag stays disabled. The compiler itself and
the public Scala DSL were not changed by this server-side admission work.

**Evidence date:** 2026-11-12 (original baseline below: 2026-11-11). **Status:** experimental, opt-in backend; **not production-ready**. This document distinguishes *merged*, *verified on a feature branch*, and *unimplemented*. It does not claim that passing tests equals Google Workflows parity.

## November 12 — genuine three-level nested Parallel and recursive fork fencing

New draft compiler fixture `ParallelIrSmoke` constructs **unchanged Scala DSL**
`Parallel` nodes at three nesting levels with deliberately repeated `Call`
labels. That exposed a real Rust normalizer gap: the original DSL's
`Parallel` is a `Step[NoValueT, NoValue]` *statement*. The compiler had
incorrectly tried to resolve its phantom `resultValue` as if it were a
registered Call result when `Parallel` itself was an outer parallel branch.
`Normalize` now lowers that nested branch's result to `null`, without
inventing a global name, exporting an undeclared binding or changing the DSL.

**Local verification:** `ParallelIrSmoke` emits the nested crate, Cargo
1.99.1 builds it, Clippy `-D warnings` passes, and the Python native-IPC
regression traverses all three fork levels and four effects using the **real
generated ELF**. The paired server's worker executes the same ELF using
isolated child execution records: all four effects overlap as permitted,
the root completes `nested-complete`, and a repeat delivery sends no extra
effects. These checks use synthetic effect handlers, not live Google services.

The paired [server PR #82](https://github.com/awfl-us/server/pull/82) also
adds bounded, transactionally checked full ancestor-fork lineage. It passes
**6/6** real Firestore emulator tests locally (including actual worker process
death/recovery and a three-level stale-root-fork fence) and **20/20** focused
unit tests. The stale-root case currently constructs nested Firestore records;
it is distinct from the newly compiled nested ELF worker test. No production
native routing was enabled. Cross-process concurrency admission, orphan
cleanup, live effects, and unchanged-workflow Google/YAML differential parity
remain release gates.

## Latest verified compatibility checkpoint — November 12 (supersedes 55/66 below)

**Parallel durability follow-through:** Paired server draft
[#82](https://github.com/awfl-us/server/pull/82) (`d776d76`) extended the
four-test real Firestore emulator suite: a separate Node process with its own
Firestore client receives a lease conflict while a parent is forking, and
observes the terminal result after parent join. All four tests passed locally
with actual generated Rust binaries. This verifies **cross-process active
parent lease exclusion**, *not* process-crash lease takeover, cross-process
concurrency permits, recursive cancellation or production Jobs durability.

**65 of 66 constructed AWFL workflow definitions now emit Rust source (98.5%).**
The read-only audit uses unchanged `workflows/main` at `c84270f`, rebuilt
against helper sources whose tree matches `workflow-utils/main` at `08bda2d`.
The compiler frontend reflection scanner **also** uses the same newly
published local snapshot (`AWFL_WORKFLOW_UTILS_SNAPSHOT=0.1.0-SNAPSHOT`),
rather than silently loading its old `0.3.5` test dependency. Thus the
historical 55/66 result was measuring older helper code and is **not** the
remaining gap in current sources. A first aligned audit produced **56/66**
with nine `time.format` blockers and one out-of-scope `initEnv` reference.

The new native `time.format(seconds[,timezone])` implements an integer/float
epoch to ISO 8601 with exactly six fractional digits, UTC `Z` or a tzdb/IANA
offset (including DST, e.g. `America/New_York`); non-finite timestamps,
invalid zone names and wrong types fail. Both the Scala-generated real Rust
ELF and Rust unit tests pass the UTC, timezone, DST, negative fractional
epoch, invalid-type and replay fixtures. **After this addition the identical
audit reports 65 emitted, 1 blocked, 0 class-load errors and 0 unknown
operation names.** The only first blocker is
`evals-experiments-SweBenchLite` referring to `initEnvResult_71e5323f`
outside its lexical scope; do **not** promote undeclared variables to globals
or modify the application's `initEnv` structure without deciding its proper
control-flow scope.

CI now pins the merged helper revision, publishes its test-only local
snapshot, compiles the unchanged workflow sources with that dependency, and
forces the scanner to use the same library. Reproduce using
`scripts/audit-real-workflows.sh` with `AWFL_WORKFLOW_UTILS_SNAPSHOT` and
`AWFL_WORKFLOWS_ROOT`. These numbers concern **Rust source emission, not
65 independently built/tested workflow executables, Google differential
semantics, effect safety or native production readiness**.

## November 12 parallel implementation checkpoint — supersedes earlier parallel status

### Follow-on source-compatibility audit and native text.substring

Rerunning `AWFL_WORKFLOWS_ROOT=/workspace/workflows-raw-audit scripts/audit-real-workflows.sh`
against the **unchanged, cached compiled workflow classes** after adding the
existing `text.substring` operation to native Rust gives **66 constructed
definitions, 55 Rust sources emitted, 11 first-blocking definitions, zero
load errors, zero unknown operation IDs**. This improves the immediately
preceding branch audit from **54 emitted / 12 blocked**; the newly emitted
definition is `evals-SweBenchLiteEval`. The 11 remaining first blockers are
invalid/raw CEL references (`example` in nine constructed graphs and two
cache-result symbols). The fixed `text.substring` implements the exact required
three-argument Workflows signature, Unicode character offsets, negative and
clamped indexes; standalone generated ELF and Rust unit/replay/invalid-type
tests pass. Neither source-emission count nor those fixtures proves execution
of every emitted workflow or Google differential parity. The workflow classes
were precompiled from a historical `workflows` checkout; rerun this audit
against an explicitly pinned fresh `workflows/main` checkout before using
these counts as a release gate.

**Dependency-provenance finding:** The same **55/66** count was independently
confirmed by the GitHub Actions `real-workflow-compatibility` job checking out
`workflows/main` on this compiler branch. Both audits still resolve an old
`workflow-utils` artifact. The nine unbound `example` CEL paths come from
`Llm.chatJson` constructing a `Spec.init(Resolver("example"))` as a runtime
expression; the other two come from `Firebase.read`'s 404 handler returning the
failed request's out-of-scope result. These defects were already fixed at
their source in merged [workflow-utils PR #6](https://github.com/awfl-us/workflow-utils/pull/6)
(literal schema examples and null-on-404, merged November 12). The compiler
frontend still resolves `workflow-utils:0.3.5` in test scope, while the
workflows build also uses its older local snapshot. **Next action is a
reproducible release/dependency alignment and a fresh constructed-AST audit,
not loosening compiler name lookup to accept undefined CEL variables.**

The original November 11 compatibility figures below are **historical baseline snapshots**; in particular, rows stating that `Parallel`/`ParallelFor` are not implemented refer to the then-merged baseline, **not** current draft branches. Do not change those old audit counts without rerunning the identical constructed-AST audit against the newer compiler. The current changes are in **draft** [compiler-rust #18](https://github.com/awfl-us/compiler-rust/pull/18) and [server #82](https://github.com/awfl-us/server/pull/82) (`67e1184`), not on `main`.

- **Implemented in drafts:** original Scala `Parallel` and `ParallelFor` lower to separate Rust entry PCs and branch-local slot snapshots, with a version-pinned `native.parallel` fork. The Jobs worker creates deterministic, digest-pinned child records, dispatches bounded concurrent child effects through the existing journal, records one parent join, and reconstructs indexed loop results. Parent `Try` handles **confirmed** failed children, not uncertain remote outcomes. A parent fork lease is refreshed during execution; a shared *in-process* permit queue limits nested work without reserving permits while awaiting children.
- **Verified:** Scala `ParallelIrSmoke`; four generated Rust crates built and Clippy-clean; a real compiled binary + worker + transaction-shaped in-memory Firestore store test covers overlapping HTTP effects, zero/one/many indexed lanes, crash after committed children before parent journal, replay, caught/uncaught `Raise`, and uncertain sibling outcomes. Server native discovery suite **85 tests: 63 passed, 0 failed, 22 skipped** (prior to the added emulator cases). **New:** the compiled Rust ELF suites passed **4/4 tests against the actual local Firestore emulator**—concurrent branches + fresh-store replay with duplicate-parent-lease rejection, takeover after injected post-child/pre-parent crash, zero/one/many indexed `ParallelFor` iterations, and parent cancellation fencing. Compiler GitHub Actions and paired server GitHub Actions passed on the prior revisions, including the first three emulator cases; updated four-case CI is being verified on the draft.
- **Not yet verified:** separate-process lease expiry/takeover after an actual worker death (active-process lease conflicts are emulator-tested); recursive cancellation/orphaned sibling policy; cross-process admission control; long-running lease/fork heartbeat races; nested fanout and real workflow differential semantics; live effectful Cloud Tasks/Jobs routing. `AWFL_NATIVE_EXECUTION_ENABLED` remains disabled in normal production. Emulator tests are **not** a GCP production rollout.

**Next gate order:** (1) maintain the four real Firestore emulator tests in CI; extend to child cancellation/orphan cleanup and independent *process* takeover, (2) nested fanout, uncertain-effect and cancellation propagation with bounded resources across server processes, (3) full constructed-AST and unchanged real-workflow audit and YAML differential fixtures, (4) authenticated deployed Jobs canary with artifact pinning and rollback, then latency/cost benchmarks. Details and acceptance checklist: [durable parallel fork/join](durable-parallel-fork-join.md).

## November 12, 2026 — authenticated native globals and URL encoding (feature branch)

The compiler's canonical DSL globals `WORKFLOW_ID`,
`WORKFLOW_EXECUTION_ID`, `PROJECT_ID`, and `LOCATION` now lower to
lookup of a separately supplied, strictly validated native IPC-v2
`trusted_globals` map. `environment.get` / `sys.get_env` allow the
same four Google Workflows built-in names, plus an explicit fallback string;
unknown names never read process environment or caller `input.env`.
The companion server feature branch sources these fields from the
authenticated execution and its server-owned Google Cloud configuration,
not from the tenant project ID. Production native routing remains disabled.

The native runtime now implements `text.url_encode` using UTF-8
percent-encoding with Unicode, spaces and reserved characters covered
by generated-binary tests. The current read-only **66-workflow constructed
AST** audit generates Rust source for **41 definitions** (formerly 4);
**25 still block**: 15 Parallel/ParallelFor and 10 raw CEL/reference
cases. The raw cases include nine `example` references generated by
the existing LLM JSON-schema helper and one cache result reference.
These cannot safely be treated as arbitrary literals or guessed variables.
No class-loading failures or unknown operations were observed.
The results measure **source generation only**, not working binaries
or deployed end-to-end executions. The audit is available as a CI
artifact in the compiler-rust feature-branch runs.

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
| `Parallel`, `ParallelFor` | **Draft implementation; not merged or production-validated** | Compiler #18/server #82 pass compiled-ELF/fake-store and four local Firestore emulator tests; descendant cancellation, independent multi-process workers and deployed Jobs remain gates |
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
