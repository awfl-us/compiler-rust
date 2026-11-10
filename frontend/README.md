# Scala frontend (planned)

Re-use `awfl-us/compiler` workflow loading and schema/definition emission.
Normalize constructed AWFL DSL trees into versioned typed IR with stable node
and slot IDs, then validate and emit resumable Rust functions. Do not parse
generated Google YAML or interpret an entire DSL graph in production.

Phase 0 still requires **constructed-AST** operation inventory in addition to
the supplementary static source scan in `scripts/source_inventory.py`.

