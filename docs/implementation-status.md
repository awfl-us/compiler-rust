# Implementation status — 2026-11-10

This is a progress record, not a claim that the roadmap is complete.

## Phase 0 — in progress

- Checked out the plan's exact source revisions of dsl, compiler-yaml, compiler,
  workflow-utils, workflows and server. Their commit IDs, tree IDs and static
  source matches are in `phase0-source-inventory.json`.
- Added a repeatable source inventory tool. This is **supplementary**: it cannot
  discover dynamically constructed DSL trees or prove compatibility.
- Constructed-AST walking, real Google differential fixtures, full dependency
  closure and benchmark baselines are **not complete**.

## Phase 1 — in progress in sibling repositories

- Added distinct typed IDs for functions, calls, globals, operators and retry
  policies to `awfl-us/dsl`, with legacy aliases and explicit unsupported-Rust
  diagnostics. All Rust capabilities are initially marked unimplemented.
- Introduced structured CEL function/global/operator nodes and typed calls;
  extended `awfl-us/compiler-yaml` to emit their legacy Google spellings.
- Remaining work: migrate ALL application/workflow utility calls and raw CEL;
  conformance tests and published dependency upgrades are still required.

## Runtime foundation — experimental

- `runtime/` contains tagged values and tests for byte/text, null/missing,
  integers/floats, nested maps and non-finite floats. This is NOT a backend.

## Guardrails

- Do not route production executions to Rust until compiled fixture coverage,
  crash/replay behavior and server-managed persistence have passed their gates.
- Do not claim arbitrary HTTP exactly-once effects; expose unknown outcome
  unless the receiver supports idempotency/reconciliation.
