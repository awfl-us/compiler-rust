# Native Rust implementation status — November 12, 2026

## Native text.substring / refreshed constructed-AST audit

The existing `CelFunctions.Text.Substring` operation now compiles to a native
Rust helper with the Workflows-required `source, start, end` signature (both
indexes required). String indexes are Unicode character offsets with negative
and out-of-range clamping; reversed bounds yield empty text. Invalid operand
types and a missing end are rejected. This required **no public DSL changes**.
Rust unit tests and real Scala-generated ELF process tests pass; CI and local
test scripts now exercise the same fixture.

The rerun against cached, unchanged constructed workflow classes reported
**55 emitted / 11 blocked / 66 total**, up from **54 / 12 / 66**; newly
emitted: `evals-SweBenchLiteEval`. Zero unknown operations and zero loading
errors. The remaining first blockers are eleven raw CEL references: `example`
in nine workflow graphs and cache-result symbols in two. Do not interpret
source emitted as deployed or end-to-end executed, and re-audit a fresh pinned
workflows revision before claiming whole-repository coverage.

## Latest parallel checkpoint (2026-11-12)

This document retains detailed **November 11 historical test/merge records** below. Those older sections are not a claim that `Parallel` remains unimplemented. Draft [compiler-rust #18](https://github.com/awfl-us/compiler-rust/pull/18) and [server #82](https://github.com/awfl-us/server/pull/82) (`67e1184`) now generate and execute separate native Rust branches with pinned snapshots, bounded concurrent Jobs-worker scheduling, parent fork journaling, `ParallelFor` input ordering, journaled replay, crash recovery after child commit, confirmed `Raise` propagation and a per-root in-process concurrency budget. No Scala DSL AST changes were needed.

**Verified on the draft branches:** compiler GitHub CI and server real-generated-binary integration CI green on prior commits; four parallel Rust fixtures compiled and linted. Server local native discovery suite: 85 tests, 63 pass, 0 fail, 22 skip (before new emulator tests were added). The compiled fake-store tests were supplemented with **4/4 passing real local Firestore emulator tests** (concurrent branch effects and duplicate-lease rejection, replay through a fresh store, post-child/pre-join crash recovery, ordered `ParallelFor` for zero/one/many inputs, and terminal parent cancellation). Those tests use the actual Firestore client/transactions but **not deployed Jobs or separate worker processes**. No general workflow parity, distributed admission, full descendant cancellation policy, or live parallel canary is claimed. Native routing remains off.

**Immediate next gate:** expand the passing emulator coverage to child/descendant cleanup, uncertain external outcomes, and simultaneous cross-process worker takeover; verified journal replay is **not** a guarantee of exactly-once remote effects. Then test nested parallel fanout, distributed admission and real workflow AST compatibility before considering merge. See [current roadmap](current-state-and-roadmap.md) and [parallel acceptance plan](durable-parallel-fork-join.md).

## Follow-on compatibility increment: native bytes and Base64

The existing generic `CelFunctions.TextEncode`, `CelFunctions.TextDecode`
and `CelFunctions.Base64Encode` IDs now lower to native Rust operations.
UTF-8 (the default) and US-ASCII charsets are implemented, with case-insensitive
names. UTF-8 decoding replaces malformed sequences with U+FFFD rather than
crashing. Base64 uses RFC 4648 and supports default padding or explicitly
disabling padding, matching Google's optional boolean argument.

The runtime rejects incompatible value types, unsupported IANA charsets, and
US-ASCII encoding of non-ASCII characters. Additional charsets (including
ISO-8859 variants), provider error taxonomy and Google-vs-Rust differential
fixtures remain prerequisites before declaring full text module parity.
Scala generated Cargo executable and Rust unit tests cover byte round-trip,
Unicode, Base64, padding, JSON-to-Base64, invalid inputs and replay.
No application Scala, public DSL, server, or production routing changes.

## Follow-on compatibility increment: pure conversions

The existing `CelFunctions.ToInt`, `CelFunctions.ToString`, and
`CelFunctions.TextLowercase` operation names now emit native Rust calls,
without extending the public Scala DSL or modifying any application workflow.
The runtime uses checked signed-64-bit numeric conversions, rejects unsupported
operand types instead of applying JSON coercion, and performs Unicode lowercase
conversion. Scala-generated Cargo fixtures execute successful conversions and
invalid-type/overflow paths in a new Rust process and verify deterministic
replay; Rust unit tests cover numeric boundaries and Unicode expansion.

**Scope:** this adds native semantics for already-declared generic IDs. It does
not migrate legacy operation names in `workflows` or `workflow-utils`, implement
`sys.now()` or connector effects, or establish Google differential parity
for every float-to-string formatting edge case or exception message.

**Current authoritative summary:** [verified state and ordered roadmap](current-state-and-roadmap.md).
**Release state:** compiler-rust PRs #1–#7, server PRs #72/#78/#79,
DSL #2, compiler-yaml #1 and workflows #3 are **merged**. Compiler #7 is
`588dc2f`; server #79 is `250f57e`. The restricted `log.write` dispatcher
passed real compiled Rust/Node/journal tests before merge, including a
simulated post-journal crash. **Normal native production routing remains
disabled**; merging code is not a traffic rollout. The live GCP canary was
an isolated pure artifact, not an effectful production test.

**Experimental.** The compiler now has one native backend, **runtime ABI 3 /
IPC protocol 2**. The normal Cloud Run API and Jobs revisions retain native
execution disabled until wider equivalence and safety tests pass.

## November 11: restored original DSL architecture (verified)

- Merged `awfl-us/dsl` PR #2 and released `v0.3.4`, replacing the accidental typed DSL additions with only
  `CelFunctions` / `CallOperations` backend-neutral String constants. The
  original `CelFunc`, `CelOp`, `Call` AST classes and the four global Value
  placeholders are **byte-for-byte unchanged from the pre-Rust DSL baseline**.
  The published DSL helpers retain their original implementation (including
  a handful of Google-named internal calls); no new AST or overloaded
  constructors are required. DSL test `OperationNamesSmoke` passed
  when run by the YAML CI job.
- Merged `awfl-us/compiler-yaml` PR #1 maps 22 function names and 10 call names to
  their exact previous Google Workflows spellings, preserving pre-existing
  literal-name support during migration. Its complete 32-operation name matrix
  and CEL/Call shape tests **passed** in GitHub Actions run
  [43079160824](https://github.com/awfl-us/compiler-yaml/actions/runs/43079160824).
- The compiler-rust frontend now consumes the original DSL objects and
  translates operation-name Strings into **compiler-private** `FunctionId`,
  `CallId`, `OperatorId` and `GlobalId` enums. Existing CEL operators keep
  their original `CelOp` form. Unknown names fail closed with node-context
  errors. There is no separate typed DSL API and no DSL-level registry.
- Full native CI passed in push run
  [43078380650](https://github.com/awfl-us/compiler-rust/actions/runs/43078380650)
  and PR run [43078395004](https://github.com/awfl-us/compiler-rust/actions/runs/43078395004),
  including a real Scala `NativeMain` → Cargo → Rust execution, generated
  Rust Clippy and nested effect continuation tests.
- **Rollout**: the additive DSL `v0.3.4` and updated YAML compiler are now
  merged/published. This native compiler explicitly pins DSL `v0.3.4` and
  no longer needs a locally published development snapshot. The native
  compiler itself remains gated on broader workflow parity; existing
  workflow literals can be migrated later without changing their AST. Native Rust has many registered
  function-name IDs whose actual code-generation semantics are still
  explicitly unsupported; the 32-name YAML matrix does **not** imply full
  Rust function/effect parity.

## November 11: native CEL standard-library expansion

`feat/native-cel-stdlib` adds eight Google-compatible pure operations to the
existing ABI 3 compiler/runtime, without changing the DSL API: `default`,
`get_type`, `map.get`, `map.merge`, `list.concat`, `json.decode`,
`json.encode_to_string`, and `json.encode`. Each matches an existing
backend-neutral `CelFunctions` constant and is emitted directly as compiled
Rust function calls. The compiler now validates `map.get` arity **exactly two**;
its second argument may be a string key or list of nested string keys.

- `map.get` returns `null` for missing keys and non-map intermediate nodes;
  wrong key types still raise errors. `map.merge` is shallow and favors the
  right map. `list.concat` appends **one value**, preserving nested lists.
  `default` selects its fallback *only* for null, not falsy values.
- JSON functions encode **plain** JSON, not AWFL tagged checkpoints; decode
  supports UTF-8 text and bytes, with invalid JSON causing an error. JSON
  output rejects AWFL bytes rather than silently changing their type. The
  exact Google exception taxonomy and JSON encoding edge-case parity still
  require differential tests before declaring complete CEL parity.
- Verified locally: all **14** runtime unit tests, Scala normalizer/codegen
  smoke, generated Cargo executable on representative inputs, deterministic
  replay, invalid JSON rejection, and strict Clippy. Reproduce with
  `scripts/test-local.sh --full` and `python3 scripts/test_cel_stdlib.py`.
- Remaining CEL functions (e.g., numerical/text/base64/time/UUID helpers) are
  deliberately **unsupported** by the Rust emitter; registered constants do
  not imply native implementations. No existing workflows were migrated.

## November 11: literal CEL collections accepted by the native compiler

`Normalize` now recognizes existing DSL `CelConst` **strict JSON data literals**
that begin with `[` or `{`, including `List.cel`, `ListValue.empty`, nested
maps/lists, strings/Unicode, booleans, null, signed-64-bit integers, and finite
floating-point numbers. This is **not** a CEL text interpreter: expressions
containing bare variables, function calls, malformed JSON, overflowing
integers, or non-finite floats are rejected with workflow/node diagnostics.
The existing DSL and YAML compiler are unchanged. Constant values become
statically emitted `AwflValue` Rust expressions, with no runtime parsing of
the DSL syntax.

Verified locally: `LiteralCollectionsSmoke` compiled an unchanged-DSL
literal collection into ABI 3 Rust; Cargo built the binary; strict Clippy
passed; `test_literal_collections.py` verified exact JSON results including
nested maps, list-element preservation, Unicode, numeric types, `map.get`
on literal maps, and deterministic replay. Invalid literal data was rejected
at compilation. The suite is wired into `scripts/test-local.sh --full` and CI.

## November 11: native CEL membership and checked floor division

The original `CelOp` DSL operator nodes (`in` and `//`) are now compiled
directly into native Rust calls. `in` performs value equality membership
in lists or string-key membership in maps (without interpreting substrings).
`//` uses **floor** division on signed 64-bit integers; negative divisors
and dividends round toward negative infinity rather than Rust's default
truncation. Division by zero, `i64::MIN / -1`, non-integer operands and
invalid map keys fail with explicit errors instead of panicking or coercing.

Tested locally with 16 Rust unit tests, a Scala-generated Cargo fixture
(`CelOperatorsSmoke`), an actual Rust binary asserting both positive and
negative inputs (`test_cel_operators.py`), repeatability, zero-division
failure, and strict Clippy. No DSL or YAML compiler modifications, and native
production routing remains disabled. Extended numeric/heterogeneous CEL
operators are still unsupported pending Google differential fixtures.

## November 11: compiled Try/Raise and durable error continuations

The native CFG now lowers **existing DSL** `Try` and `Raise` nodes without
changing the DSL or parsing YAML. Static exception edges are compiled for
nested handlers/rethrows, and runtime CEL errors and explicit raised error
objects take the nearest handler. Handler bodies route rethrows to their
*enclosing* handler. Uncaught raises return a versioned `status: failed`
response; continuation-invalidity and internal infrastructure errors are
not converted into catchable workflow errors.

For HTTP effects inside Try, suspension carries an optional, validated
`on_error: {next_step,error_slot}` alongside the normal success continuation.
Checkpoints preserve the live slots needed by either path. The existing
server-side native engine must **atomically journal confirmed HTTP failures**
with the error slot/handler PC before this behavior is production-ready.
Unknown transport results, authentication failures and timeouts must never be
caught as confirmed HTTP errors or automatically replayed.

Verification: `TryRaiseSmoke` generates **six real Cargo executables**, and
`test_try_raise.py` verifies normal completion, explicit catches, uncaught
errors, nested rethrow, runtime CEL failure, effect intent and both resumed
success/error paths, plus deterministic output. The Python effect-failure
checkpoint is a controlled simulation; server-side integration must be
verified separately before enabling native production routing. CI and
`scripts/test-local.sh --full` exercise all six generated crates under Clippy.

## November 11: unchanged production Product.scala native conformance

The **unmodified** `awfl-us/workflows/src/main/scala/us/awfl/workflows/evals/Product.scala`
now compiles from the original published DSL to real Rust ABI 3 binaries.
Its `Try` error branch uses the DSL's built-in `Log` constructor (`sys.log`),
and the DSL's `join` helper builds `CelFunc("len", ...)`. The compiler now
resolves only these exact **current DSL-owned** spellings (plus `default` and
`json.encode_to_string` from the same DSL source) into the existing private
operation IDs. General unknown names still fail closed; no workflow sources,
DSL AST or YAML compiler were changed.

`RealProductSmoke` loads the **exact pinned** workflow source via sbt
`Test / unmanagedSources`, confirms that nested compiled `Try` handlers
remain live and `log.write` appears in the manifest, then creates three
actual Rust crates. `test_real_product.py` executes them on representative
inputs: single-variable combinations (2), two-variable Cartesian product (4),
and an empty list (0); all match the expected JSON results and are
deterministic. The generated crates pass strict Clippy.

**Scope limitation:** these are happy-path executions. The `log.write`
effect on Product's *exception* path is preserved by the compiler but does
not yet have a production native server dispatcher/allowlist implementation.
Do not enable native routing for this workflow until that effect and
remaining differential semantics are verified. No workflows were migrated.

## November 11: catch-only CFG and original DSL error-logging regression

A workflow with the original DSL's default `Try` exception handler may have
no ordinary CFG jump, only a catch edge. Its recovered Rust program counter
must therefore be mutable **even when no ordinary branch advances it**.
`StateMachineEmitter` now derives this property from the presence of any
exception handler as well as ordinary control-flow transitions.

`TryRaiseSmoke` generates an additional **default-log** ABI 3 crate using
only the original DSL `Try`, `Raise`, and `Error` nodes. Its existing error
handler emits a genuine persisted `log.write` effect, then re-raises the
original error after the effect completes. `test_try_raise.py` asserts the
serialized log intent, checkpoint state, resumed terminal failure, and
repeatability. The crate is compiled and linted in local and CI suites.
The native server's restricted logger/journal integration is verified on a
separate server PR #79, since merged with a real Cargo-compiled Rust binary.
A post-journal-commit simulated crash resumed to terminal `Failed` without
redispatching the log. The focused Node selection passed **34/37 tests**
(three optional binary-dependent tests skipped, zero failures); tests use
in-memory Firestore transaction APIs, **not** live Firestore or Cloud Logging.
The full AWFL Scala/Cargo local suite passed `--full` on the compiler branch.
This is not a global exactly-once log guarantee or production approval.
No DSL or YAML changes, and production native routing remains disabled.

## Compiler: implemented

- Scala 3 `frontend/` loads constructed AWFL DSL workflow objects through the
  existing `awfl-us/compiler.WorkflowLoader`. It normalizes typed expressions
  and step identities, builds deterministic, statically compiled CFG blocks,
  computes live continuation slots, and emits Rust plus source-hash manifest.
  No Google Workflows YAML is parsed or interpreted at runtime. The existing
  `CelFunc`, `CelOp`, and `Call` objects remain the DSL input; constant-name
  CEL functions/calls are resolved to compiler-internal operation IDs, while
  existing CEL operators are translated directly to native expressions.
- **One emitter:** `RustEmitter.emit/write` uses `StateMachineEmitter` for
  *both* pure and effectful workflows. `NativeCompiler.compile` and
  `NativeMain` use this same path. The older standalone emitters, protocol-v1
  runtime, flag-based backend selection, and ABI 1/2 fixture/test trees were
  removed. No existing compiled production workload relies on them.
- ABI 3 supports pure `Return`, typed expression evaluation, nested `Switch`,
  `ForRange`, `For`, sequential `Fold`, and resumable `Call` effects inside
  these structures. Effects are intents returned to the server, not HTTP
  requests issued inside the Rust process. Continuations store a successor
  CFG program counter and only live tagged slots. Stable effect IDs combine
  structural step IDs and enclosing zero-based loop ordinals.
- The unified Rust runtime accepts only JSON `protocol_version: 2`. It
  rejects unsupported versions, invalid PCs, nonempty continuation frames,
  corrupt values and missing slots. The server-facing schema still includes
  an always-empty `frames` array until the server IPC schema is simplified.
- The `NativeMain` smoke fixture is compiled with **real Cargo** in CI,
  checked against the source SHA and run with protocol 2. Scala smoke tests
  cover normalize/loader, CFG/liveness, nested effects and CLI input errors.
  Rust test scripts execute pure output and repeatedly suspend/resume actual
  compiled effects in fresh subprocesses. Nested three-level Switch and a
  nested range followed by a sibling effect are also in the test matrix.
- **Historical CI evidence:** compiler head `2ca8a93` passed both Rust and
  Scala jobs in [push run 43073698052](https://github.com/awfl-us/compiler-rust/actions/runs/43073698052)
  and [PR run 43073715306](https://github.com/awfl-us/compiler-rust/actions/runs/43073715306).
  **Current merged evidence:** compiler PR #7 passed full local
  `scripts/test-local.sh --full` and GitHub Scala/Rust jobs; this includes
  seven generated `Try` fixtures plus Product and LoadConversationHistory.
  Historical head `2ca8a93` is not presented as proof for the latest patch.

## Related repositories and production wiring

- Merged [dsl PR #2](https://github.com/awfl-us/dsl/pull/2) adds only
  `CelFunctions` and `CallOperations` string constants, preserving original
  AST classes and helper interfaces. The earlier typed API/registry was
  reverted. Merged [compiler-yaml PR #1](https://github.com/awfl-us/compiler-yaml/pull/1)
  renders constants into original Google names while retaining unconverted
  literal names for existing workflows. The frontend pins published DSL
  `v0.3.4`; downstream workflow calls can be migrated by replacing strings
  with constants, not constructors.
- Merged [server PR #78](https://github.com/awfl-us/server/pull/78)
  implements original DSL `Try`/`Raise` error recovery across confirmed HTTP
  4xx/5xx effects, atomic error journaling, terminal `Failed` and tenant-safe
  status. The later `log.write` handler is tested **locally on a branch** and
  was subsequently merged in server PR #79.
- [server PR #72](https://github.com/awfl-us/server/pull/72) merged the
  opt-in shared-launcher selection, IAM-owned GCS artifact lookup, Firestore
  checkpoints/effect journal, Cloud Tasks dispatch, OIDC-protected Jobs route,
  status/cancel adapter, and worker epoch fencing. This server work was
  initially developed with both protocol contracts; the new compiler no
  longer emits the old one. Server-only compatibility removal can follow
  once all server test fixtures and callers are aligned.
- [workflows PR #3](https://github.com/awfl-us/workflows/pull/3) merged
  static-musl native publication for Alpine Jobs. The approved pure
  `NativePilot` ABI 3 artifact SHA-256 is
  `109b82a73175a46ada8b4c5f5059b724f40017a8321b2c76edec9e62396f984e`.
  A glibc-linked pilot failed with `ENOENT` at Alpine process startup; the
  corrected executable has no dynamic ELF interpreter.

## November 11: local compiler test environment (verified)

- The `compiler-rust` project consumer was restarted using the `scala`
  image profile instead of `lightweight`. Java 21 and sbt 1.11 are available;
  Rust 1.99.1 / Cargo / Clippy and a verified Zig-backed C linker were
  installed in this consumer to enable local testing. Future Scala images
  will include the toolchain from [server PR #77](https://github.com/awfl-us/server/pull/77).
- Tests were executed **inside the project GKE consumer**, not delegated to
  GitHub Actions: 10 Rust runtime unit tests, Rustfmt and strict Clippy,
  `FrontendSmoke`, `ControlFlowLoweringSmoke`, `ControlFlowSmoke`,
  `NativeCliSmoke`, generated pure Rust executable (three inputs), a real
  unchanged `LoadConversationHistory` compiled to Rust and tested for
  HTTP intent and deterministic checkpoint resume (no live network call),
  and both native control-flow and effect/replay process test suites.
- Reproduce with `scripts/test-local.sh --smoke` or `--full`. The older
  CI flow remains final verification; the local script is the preferred
  feedback loop and does not enable production native routing.

## Verified live GCP pilot

On November 11, a direct trusted GCP canary created a pure `NativePilot`
execution in Firestore and enqueued a Cloud Task with OIDC to the **zero-traffic**
tagged Jobs revision (`jobs-00121-jaz`, `native-rust-pilot`). The compiled
Rust returned `[2,3,9,9]`, persisted in Firestore, in **6,836 ms** from
enqueue to observation (one sample). Duplicate delivery left the completed
result and epoch unchanged. The 6-second elapsed time was dominated by
Cloud Run startup rather than compiled Rust computation. The canary driver
is `scripts/live_native_pilot.py` and the publisher/rollback runbook is in
`awfl-us/workflows/docs/native-rust-publication.md`.

This **does not** verify the public Firebase-authenticated
`/workflows/execute` route, agent session links, actual HTTP effects,
callbacks/child workflows, or representative p50/p95/p99 latency. The
canary was isolated and allowed **zero** external operations. A GitHub App
attempt to trigger the release workflow returned HTTP 403; the validated
artifact was published using the existing injected GCP IAM credentials.

## Outstanding gates (priority order)

1. **Completed current delivery:** compiler [PR #7](https://github.com/awfl-us/compiler-rust/pull/7)
   (catch-only Rust PC) and server [PR #79](https://github.com/awfl-us/server/pull/79)
   (`log.write` and journaled original `Try` rethrow) are merged after CI.
   Both keep the DSL unchanged and **do not** enable native production
   traffic. The `default-log` and post-journal-crash tests are green, while
   pre-journal crash uncertainty and live Cloud Logging remain unproven.
2. **Real workflow inventory and differential parity:** constructed-AST
   coverage and golden Google/native fixtures, including `Product`,
   `LoadConversationHistory`, then dependent queue/dispatcher/agent workflows;
   complete missing CEL/text/base64/time/UUID functions as usage requires.
3. **Durable effect and orchestration coverage:** retries, remote uncertain
   outcome reconciliation, callbacks, child workflows, provider-specific
   storage/messaging, proper cancel propagation. Implement `Parallel` and
   `ParallelFor` with server-owned fork/join; **not** a sequential rewrite.
4. **Security and reliability:** real Firestore contention/lease takeover,
   sweeper, process sandbox, token isolation, authenticated public launcher,
   tenant/status/session integration, artifact and active-release provenance.
5. **Measure and pilot:** benchmark equal-workload Google versus Rust
   p50/p95/p99 plus storage/CPU/cost; demonstrate publisher rollback and
   stable artifact resume; only then consider a limited traffic opt-in.

Full acceptance gates and their order are in
[current-state-and-roadmap.md](current-state-and-roadmap.md).
See [compiler architecture](native-control-flow-refactor-plan.md),
[wire protocol](native-ipc-v2.md), and the
[full implementation plan](native-rust-implementation-plan.md).
