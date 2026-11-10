# Scala 3 native Rust compiler frontend

The Scala compiler consumes **constructed AWFL DSL workflows** and generates
native Rust source directly; it does not execute Google Workflows YAML or
interpret the workflow graph at runtime.

- `NativeCompiler.compile(workflows, baseName, outputDir, runtimeDir)` is the
  reusable backend API.
- `NativeMain` is the current opt-in CLI. It reuses
  `us.awfl.compiler.WorkflowLoader` from the published `awfl-us/compiler`
  module, including the same Scala-object discovery conventions.
- The existing `awfl-us/compiler` remains responsible for input/output schema
  generation. A coordinated `--target rust` switch in its CLI is still pending.

## Dependencies

The frontend currently depends on `us.awfl:compiler_3:0.3.1` and the typed
`us.awfl:dsl_3:0.1.0-SNAPSHOT` from
[dsl PR #2](https://github.com/awfl-us/dsl/pull/2).
Publish that exact DSL checkout locally before development. This snapshot and
the forced dependency override are **not** for production deployment.

## Use the Scala CLI

Build your Scala workflow object into the frontend classpath, then run:

```bash
cd frontend
sbt -batch 'runMain us.awfl.compiler.rust.NativeMain your.package.WorkflowBuilder ../rust_gens ../runtime'
```

This generates a Cargo project for each DSL workflow, each with a deterministic
distinct executable name. You can then build the generated crate with Cargo.
This does not deploy it or register server-side execution.

## Integration tests

```bash
# In sibling checkout of awfl-us/dsl at the typed operation registry PR:
sbt -batch publishLocal

# In compiler-rust/frontend:
sbt -batch \
  'Test / runMain us.awfl.compiler.rust.FrontendSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowSmoke' \
  'Test / runMain us.awfl.compiler.rust.NativeCliSmoke'

# In compiler-rust:
cargo test --workspace --locked
cargo build --manifest-path fixtures/native-smoke/Cargo.toml --locked
python3 scripts/test_native_smoke.py
python3 scripts/test_control_flow.py
```

## Current capability

Compiled and tested: pure return-only workflows, `Switch`, `ForRange`,
`For`, sequential `Fold`, and nested switch/loop combinations; literal objects/lists, input
selection, selected arithmetic, comparison and boolean operators. Branches
use ordered first-match semantics. A range's upper bound is exclusive.

Known limitations: typed `Call` steps are recognized but rejected by the
emitter pending server-managed effects, checkpoint/replay and recovery.
`Try`, `Parallel`, generic early returns and full Google/CEL
semantics are not implemented. Unsupported expressions fail compilation with
node context rather than falling back silently.
