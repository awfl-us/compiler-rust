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

## Server execution — not implemented
- No backend dispatch, leases/fencing, durable checkpointing, callback or
  effect journal, crash/replay conformance, rollout, or production deploy.
- External effects are recognized by the IR but deliberately rejected by the
  code generator. Arbitrary network calls cannot be claimed exactly-once.

For commands see `frontend/README.md`.
