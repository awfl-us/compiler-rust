# Native Rust implementation status — November 11, 2026

**Experimental.** The compiler now has one native backend, **runtime ABI 3 /
IPC protocol 2**. The normal Cloud Run API and Jobs revisions retain native
execution disabled until wider equivalence and safety tests pass.

## November 11: restored original DSL architecture (verified)

- Merged `awfl-us/dsl` PR #2 and released `v0.3.4`, replacing the accidental typed DSL additions with only
  `CelFunctions` / `CallOperations` backend-neutral String constants. The
  original `CelFunc`, `CelOp`, `Call` AST classes and the four global Value
  placeholders are **byte-for-byte unchanged from the pre-Rust DSL baseline**.
  DSL helpers only substitute the operation-name constants; no new AST or
  overloaded constructors are required. DSL test `OperationNamesSmoke` passed
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
- **Verified:** CI for compiler head `2ca8a93` passed both Rust and Scala
  jobs in [push run 43073698052](https://github.com/awfl-us/compiler-rust/actions/runs/43073698052)
  and [PR run 43073715306](https://github.com/awfl-us/compiler-rust/actions/runs/43073715306).
  This includes real Cargo-built executable tests, strict Clippy, deep
  Switch/ForRange continuation tests and malformed checkpoint rejection.

## Related repositories and production wiring

- Merged [dsl PR #2](https://github.com/awfl-us/dsl/pull/2) adds only
  `CelFunctions` and `CallOperations` string constants, preserving original
  AST classes and helper interfaces. The earlier typed API/registry was
  reverted. Merged [compiler-yaml PR #1](https://github.com/awfl-us/compiler-yaml/pull/1)
  renders constants into original Google names while retaining unconverted
  literal names for existing workflows. The frontend pins published DSL
  `v0.3.4`; downstream workflow calls can be migrated by replacing strings
  with constants, not constructors.
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

## Outstanding gates

1. Finish DSL/operation inventory, constructed-AST coverage and Google
   differential test fixtures. Preserve typed operation semantics and avoid
   silent fallback to YAML when encountering unsupported expressions.
2. Implement `Try`, `Raise`, `Parallel`/`ParallelFor`, callbacks, child
   workflow execution, broader CEL/global semantics, error and retry parity.
3. Test authenticated shared-launcher/session/history/status/cancel APIs,
   real effect allowlisting, trusted identity and OS-level sandboxing.
4. Test crash windows, effect outcome uncertainty/reconciliation, lease
   heartbeats, concurrent workers, orphan cleanup, and large data paths.
5. Verify GCP IAM automated artifact publication/rollback and compare real
   workloads, including ProjectManager's full dependency closure, before
   enabling production routing. Preserve pinned artifacts for active runs.

See [compiler architecture](native-control-flow-refactor-plan.md),
[wire protocol](native-ipc-v2.md), and the
[full implementation plan](native-rust-implementation-plan.md).
