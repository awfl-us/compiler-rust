# AWFL native Rust compiler

> **Status, November 11, 2026:** Scala-to-Rust ABI 3 and unchanged
> `Product.scala`/`LoadConversationHistory.scala` native fixtures work.
> Durable `Try`/`Raise` HTTP-error handling is merged; the catch-only logger
> fix has merged as [compiler PR #7](https://github.com/awfl-us/compiler-rust/pull/7),
> and server `log.write` handling has merged as
> [server PR #79](https://github.com/awfl-us/server/pull/79).
> **Normal production native execution is disabled**. See the
> [evidence and ordered roadmap](docs/current-state-and-roadmap.md).

The Scala AWFL DSL remains the source of truth. This repository will implement a
**Scala frontend and native Rust backend**, not a Google Workflows YAML interpreter.
Durable steps, continuation/value storage and effect reconciliation belong to the
existing AWFL server; generated native binaries handle pure work locally.

Start with the [current state and roadmap](docs/current-state-and-roadmap.md),
then the [implementation plan](docs/native-rust-implementation-plan.md),
[CFG architecture](docs/native-control-flow-refactor-plan.md), and
[IPC contract](docs/native-ipc-v2.md) before changing compiler or runtime paths.

## Current state

An initial **Scala 3 compiler module** in `frontend/` now normalizes constructed
DSL workflows into a typed IR and generates native Rust crates, with deterministic
source manifests. A pure-workflow fixture is compiled and executed end-to-end.
`runtime/` implements tagged-value persistence and the tested native helper
functions. See `docs/implementation-status.md` for the precise capability gates.

The pure subset now includes compiled `Switch`, `ForRange`, `For` and `Fold`, including
nested control flow. Additional CEL functions (`default`, `get_type`, `map.get`,
`map.merge`, `list.concat`, JSON encode/decode) now emit native calls rather
than requiring a CEL interpreter. The Scala CLI in `frontend/` reuses the existing compiler
workflow loader. All compiled artifacts use **ABI 3 / protocol 2**; no
compatibility compiler/runtime variants are shipped. Full exception/retry parity, real network effects, and general production
routing remain unavailable. The existing server now supports an **opt-in**
native Jobs/Firestore backend; a pure ABI 3 `NativePilot` GCP canary passed
on November 11, 2026. See `docs/implementation-status.md` for evidence,
deployment boundaries and remaining blockers.

Local source inventory (requires sibling repository clones):

```bash
python3 scripts/source_inventory.py --output docs/phase0-source-inventory.json
```

### Constructed-AST compatibility audit

Unlike a source grep, this scanner loads compiled, unchanged workflow
singletons and walks their *constructed* DSL steps and CEL/Call expressions,
including those produced by `workflow-utils` helpers. It attempts native
CFG/Rust **source emission** and records exact first-failure node paths.
It does not run Cargo, external calls, or production execution.

```bash
(cd ../workflows && sbt -batch compile)  # compile unchanged application sources
scripts/audit-real-workflows.sh          # report under ignored target/
python3 scripts/render_compatibility_report.py \
  target/constructed-ast-compatibility.json \
  target/constructed-ast-compatibility.md
```

See the [verified inventory snapshot](docs/constructed-ast-compatibility.md).
Application Scala changes are restricted to **name-only** substitution of CEL
functions and connector operations with `CelFunctions` / `CallOperations`
constants. Keep all workflow structure and helper APIs unchanged; implement
missing semantics in this compiler and the server. The native compiler now
lowers the original DSL `FlatMap` projection wrapper without a new DSL node.

## Local tests in the AWFL compiler-rust environment

Use the AWFL `scala` image profile: Java 21, sbt 1.11.0, Rust 1.99.1,
Cargo/Clippy, and a working C linker. This profile is equipped with Rust
through [server PR #77](https://github.com/awfl-us/server/pull/77); existing
consumers created before its deployment require a one-time local Rust/linker
setup. **Run locally before triggering CI**, using the same project-scoped
consumer and workspace:

```bash
cd /workspace/compiler-rust
scripts/test-local.sh --smoke  # Scala DSL + Rust runtime + real workflow
scripts/test-local.sh --full   # Also run all compiled CFG/effect-resume fixtures
```

The script resolves the published additive DSL **v0.3.4**, loads and
compiles an **unchanged** `LoadConversationHistory.scala` from the sibling
Workflows checkout, builds actual Rust executable crates, tests HTTP
intent + checkpoint resume **without contacting the endpoint**, and checks
pure workflow outputs, generated native CEL standard-library semantics,
original DSL literal collections compiled to Rust, and native `in` and `//` operator semantics, and statically compiled `Try`/`Raise`
exception paths including nested catches and recoverable HTTP effects.
The full option also tests nested branches, loops,
folds, effect IDs, checkpoint corruption, and replay using fresh Rust
processes. No production workflow routing is enabled by these tests.

The existing default `Try` handler is also covered: `TryRaiseSmoke`
generates a real `default-log` Rust crate that suspends on `log.write` and
re-raises the original error after a replayed success, exercising the special
CFG case where only an exception edge changes the program counter.

Production-source conformance also compiles the original
`workflows/evals/Product.scala` (no changes to the source) to three native
executables. Its original DSL `Try`, `Fold`, `For`, and `join` constructors
produce 2, 4, and 0 Cartesian-product results across three test scenarios.
The DSL's internally emitted `sys.log` and `len` operations are resolved
by the Rust compiler's narrow built-in-name mapping, without introducing
legacy name fallback for arbitrary applications. The Product error logging
**dispatch** is not yet implemented on the native server, so this only
validates pure Product execution.

The actual local run on November 11 passed **10 runtime unit tests**, all
**four Scala frontend suites**, pure workflow inputs, the real HTTP-effect
workflow's suspend/resume sequence, and both compiled control-flow and
continuation conformance suites. The first invocation populated Scala/Cargo
caches; subsequent runs reuse them.

The shared operation-name constants are maintained in `awfl-us/dsl` v0.3.4,
not duplicated in the DSL AST or this compiler. The merged
`awfl-us/compiler-yaml` backend maps these names back to Google operations
and preserves original literals during gradual workflow migration.

