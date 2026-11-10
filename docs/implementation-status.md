# Native Rust implementation status — 2026-11-10

This records a **partial** implementation, not readiness to replace Google
Workflows for production.

## Phase 0 — incomplete
- Pinned source revision inventory in `phase0-source-inventory.json`; static
  literal source scan shows no unregistered function/call spellings.
- Still required: complete constructed-AST inventory, exhaustive Google
  differential fixtures, error semantics and latency baseline.

## Phase 1 — companion draft PRs
- [dsl PR #2](https://github.com/awfl-us/dsl/pull/2) introduces canonical
  operation, call, operator, global and retry policy identifiers.
- [compiler-yaml PR #1](https://github.com/awfl-us/compiler-yaml/pull/1)
  preserves Google/YAML output for typed expressions.
- Publication and complete application migration are not yet done.

## Phase 2 — Scala frontend and native pure control flow
- The native compiler is a Scala 3 module in `frontend/`.
- `Normalize` walks constructed AWFL DSL trees and builds versioned typed IR
  with structural node and value-slot IDs, not persisted Scala hashes.
- Supported control flow: ordered `Switch`, `ForRange` with exclusive
  upper bound, `For`, sequential `Fold` with mutable accumulator,
  nesting, and pure expression returns.
- `RustEmitter` generates source with real Rust conditionals/loops, not a
  runtime workflow interpreter, with a source-hash manifest and unique Cargo
  executable names for each workflow.
- The Rust support crate provides tagged value serialization, checked
  arithmetic, typed comparisons and value path operations.
- `NativeCompiler.compile` provides an API for passing workflows in.
  `NativeMain` reuses the **existing** `awfl-us/compiler.WorkflowLoader`
  to load Scala singleton workflow builders.
- Scala compile/smoke and actual native Rust fixture executions cover pure
  arithmetic, branches, loops and nesting. CI builds and checks fixtures.
- **Pending:** `Try`, `Parallel`, early-return lowering, exhaustive
  CEL compatibility, Google differential validation and direct
  `awfl-us/compiler --target rust` integration.
- Published `compiler` 0.3.1 is reused for the loader, and the typed DSL
  snapshot is temporarily forced until coordinated releases.

## Phase 3 — experimental server foundation (draft, not integrated)

- [server PR #72](https://github.com/awfl-us/server/pull/72) adds a
  Firestore transaction-backed `NativeExecutionStore`: pinned artifact SHA-256,
  owner/project identity, leases with epoch fencing and renewal, cancellation,
  checkpoints, prepared effect intents and outcome recording.
- An opt-in pure native runner verifies binary SHA-256, launches a bounded
  JSON-in/JSON-out subprocess and completes the fenced execution. It is **not**
  wired to the common `createWorkflowExecution` launcher, and the process is
  **not sandboxed**. Executable paths must be selected from a trusted registry.
- An effect dispatcher boundary supports explicit handlers for canonical HTTP
  operation IDs and requires reconciliation rather than blind replay of
  uncertain outcomes. Actual secure HTTP adapters are **not** implemented.
- Tests: 12 local Node suites passed, including negative runner paths and
  running an actual Scala-generated Rust Fold executable through the server
  runner with a **mock** persistence adapter. The real binary returned the
  expected outputs for `[1,2,3]`, `[]` and `[-4,5]`.
- **Not tested:** real Firestore/emulator transactions and contention, persisted
  crash recovery, real effect delivery, native Rust suspension/resumption,
  full launcher integration, production deployment or Google latency parity.
- The prototype keeps all effect results in one execution document. This
  cannot support unbounded histories and must move to a bounded/subcollection
  journal with tested transaction behavior.

## Next required validation and implementation gates

1. Test the store against Firestore emulator: simultaneous claims, transaction
   retries, lease takeover, cancellation races and crashes around intent/outcome
   persistence. Do not treat in-memory transaction mocks as concurrency tests.
2. Define and implement a versioned Rust continuation/IPC protocol with stable
   invocation keys across nested loops, and exercise actual suspend/resume.
3. Implement bounded effect journal, artifact registry and authenticated
   dispatcher with idempotency/reconciliation. Test crash injection at every
   effect boundary, including unknown remote outcomes.
4. Extend the **existing** shared web/Agents MCP launcher and status/callback
   services with opt-in backend routing. Keep Google as the default.
5. Complete DSL semantics (`Try`, `Parallel`, CEL, early returns), differential
   fixtures, representative workflows and p50/p95/p99 benchmarks before pilot.

For frontend commands see `frontend/README.md`; for the server library and
cross-repository test commands see `server/workflows/native/README.md` in
[server PR #72](https://github.com/awfl-us/server/pull/72).
