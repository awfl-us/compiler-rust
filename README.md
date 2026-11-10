# AWFL native Rust compiler

The Scala AWFL DSL remains the source of truth. This repository will implement a
**Scala frontend and native Rust backend**, not a Google Workflows YAML interpreter.
Durable steps, continuation/value storage and effect reconciliation belong to the
existing AWFL server; generated native binaries handle pure work locally.

Read [the implementation plan](docs/native-rust-implementation-plan.md) before
implementing any new compiler/runtime paths.

## Current state

Early foundation only: pinned source inventory and a tagged-value Rust support
crate. There is no native workflow emitter, executable workflow runner, server
checkpointing backend or production deployment in this repository yet.

Local source inventory (requires sibling repository clones):

```bash
python3 scripts/source_inventory.py --output docs/phase0-source-inventory.json
```

Runtime unit tests (requires Rust 1.99.1):

```bash
cargo test --workspace
```

The shared operation identity catalog is maintained in `awfl-us/dsl`, not copied
into this repository. The YAML backend in `awfl-us/compiler-yaml` must retain
compatibility while that migration proceeds.

