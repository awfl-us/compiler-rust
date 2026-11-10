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

There is not yet general workflow control-flow lowering, a server checkpointing
backend, external-effect execution, or production deployment.

Local source inventory (requires sibling repository clones):

```bash
python3 scripts/source_inventory.py --output docs/phase0-source-inventory.json
```

Runtime unit tests (requires Rust 1.99.1):

```bash
cargo test --workspace
```

Scala → Rust integration smoke test (requires the typed DSL PR published locally):

```bash
cd frontend
sbt -batch 'Test / runMain us.awfl.compiler.rust.FrontendSmoke'
cd ..
cargo build --manifest-path fixtures/native-smoke/Cargo.toml
python3 scripts/test_native_smoke.py
```

The shared operation identity catalog is maintained in `awfl-us/dsl`, not copied
into this repository. The YAML backend in `awfl-us/compiler-yaml` must retain
compatibility while that migration proceeds.

