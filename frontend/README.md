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
python3 scripts/test_native_protocol.py
```

## Current capability

Compiled and tested: pure return-only workflows, explicit `Return` from nested
branches and top-level steps, `Switch`, `ForRange`,
`For`, sequential `Fold`, and nested switch/loop combinations; literal objects/lists, input
selection, selected arithmetic, comparison and boolean operators. Branches
use ordered first-match semantics. A range's upper bound is exclusive.

Top-level typed `Call` steps now compile to server-managed suspension and
resume points using versioned JSON IPC. They return canonical operation
identifiers, arguments, result slot and tagged continuation slots. The Rust
artifact makes **no HTTP request itself**. Two sequential top-level calls
have been built and executed across native subprocess restarts with recorded
results. See [native IPC specification](../docs/native-ipc-v1.md) and
[implementation status](../docs/implementation-status.md).

Pure artifacts retain ABI 1 (plain JSON in/out). Effectful artifacts use
ABI 2 (versioned request/response protocol). The server integration is a
standalone experimental library: it is **not** connected to production routing.

One or more direct typed Calls in a top-level ForRange support experimental
per-site suspension and resumption with a tagged completed-output prefix.
When multiple Calls occur in one iteration, a second invocation frame
identifies the Call site and previous results from that iteration are saved.
Each new Rust subprocess skips finished effects; the server journals their
outcomes and can recover after worker takeover.

Known limitations: Calls inside nested loops, branches, For, and Fold
remain explicitly rejected. Try, Parallel
and complete Google/CEL semantics are not implemented.
Unsupported expressions fail compilation with node context rather than
falling back silently to YAML.
