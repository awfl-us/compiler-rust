# AWFL native Rust compiler

The Scala AWFL DSL remains the source of truth. This repository will implement a
**Scala frontend and native Rust backend**, not a Google Workflows YAML interpreter.
Durable steps, continuation/value storage and effect reconciliation belong to the
existing AWFL server; generated native binaries handle pure work locally.

Read [the implementation plan](docs/native-rust-implementation-plan.md) before
implementing any new compiler/runtime paths.

## Current state

An initial **Scala 3 compiler module** in `frontend/` now normalizes constructed
DSL workflows into a typed IR and generates native Rust crates, with deterministic
source manifests. A pure-workflow fixture is compiled and executed end-to-end.
`runtime/` implements tagged-value persistence and the tested native helper
functions. See `docs/implementation-status.md` for the precise capability gates.

The pure subset now includes compiled `Switch`, `ForRange`, `For` and `Fold`, including
nested control flow. The Scala CLI in `frontend/` reuses the existing compiler
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
pure workflow outputs. The full option also tests nested branches, loops,
folds, effect IDs, checkpoint corruption, and replay using fresh Rust
processes. No production workflow routing is enabled by these tests.

The actual local run on November 11 passed **10 runtime unit tests**, all
**four Scala frontend suites**, pure workflow inputs, the real HTTP-effect
workflow's suspend/resume sequence, and both compiled control-flow and
continuation conformance suites. The first invocation populated Scala/Cargo
caches; subsequent runs reuse them.

The shared operation-name constants are maintained in `awfl-us/dsl` v0.3.4,
not duplicated in the DSL AST or this compiler. The merged
`awfl-us/compiler-yaml` backend maps these names back to Google operations
and preserves original literals during gradual workflow migration.

