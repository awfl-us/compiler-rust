# Native Rust implementation status — 2026-11-10

This records implemented code and verified tests, **not** completion of the
native workflow execution plan.

## Phase 0 (partial)
- Exact plan baseline revisions pinned in `docs/phase0-source-inventory.json`.
- Supplementary literal-source scan shows **zero unregistered** function or call
  strings in the observed sources.
- **Pending:** actual constructed-AST inventory for all application workflows,
  pinned Google semantic/error fixtures, end-to-end latency baseline.

## Phase 1 (partial; companion PRs)
- [dsl PR #2](https://github.com/awfl-us/dsl/pull/2) defines canonical typed
  function, call, global, operator and retry policy IDs with legacy aliases.
- [compiler-yaml PR #1](https://github.com/awfl-us/compiler-yaml/pull/1)
  retains Google Workflows generation for typed DSL nodes.
- Scala compile and smoke tests passed; production changes remain draft
  pending dependency release and compatibility coverage.
- **Pending:** migrate workflow-utils and application call sites, raw CEL and
  deployment string substitutions.

## Phase 2 (partial; in this repository)
- `frontend/` is a **Scala 3 module** with normalized typed expression/step IR.
- `Normalize` consumes constructed DSL objects directly, allocates structural
  node IDs and stable slot indices, classifies calls, and rejects unsupported
  raw expressions with workflow/node context.
- `RustEmitter` generates native Rust statements, a Cargo crate, and an
  artifact manifest pinned to the SHA-256 of generated source.
- `NativeCompiler.compile` is the backend API for the existing Scala compiler
  loader and schema generator.
- `runtime/` is a Rust helper crate containing tagged value persistence,
  explicit errors, JSON boundary conversions, arithmetic, and path operations.
- A Scala fixture compiles to real Rust and produces the correct JSON for
  three different inputs. Scala compile/smoke, Rust tests and Clippy passed.
- **Currently supported:** pure return-only workflows, typed literals,
  input references, maps/lists, selected integer/float and boolean expressions,
  nested input selections and the tested length function.
- **Not yet supported:** general switch/loops/folds, exception frames, parallel
  flow, artifact deployment, effectful calls, server-owned state, wakeups.
  Rust emission explicitly rejects unsafe/unsupported behavior.

## Phase 3+ — not implemented
- Server backend dispatch, leases/fencing, checkpoints, effect journal, crash
  replay, callbacks/children, backend routing and rollout remain future work.
- Never route real executions to this pure fixture runner. No arbitrary external
  effect is exactly-once without idempotency or reconciliation.

## Test commands
See `frontend/README.md` and `scripts/test_native_smoke.py`.
