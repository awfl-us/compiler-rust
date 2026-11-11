# Scala 3 native Rust compiler frontend

The Scala compiler consumes **constructed AWFL DSL workflows** and generates
native Rust source directly; it does not execute Google Workflows YAML or
interpret the workflow graph at runtime.

- `NativeCompiler.compile(workflows, baseName, outputDir, runtimeDir)` is the
  reusable backend API; it now defaults to **ABI 3 / protocol 2**, accepted by
  the IAM-authorized native artifact publisher. Use
  `NativeCompiler.Backend.Legacy` explicitly for ABI 1/2 compatibility tests.
- `NativeMain` is the current opt-in CLI. It reuses
  `us.awfl.compiler.WorkflowLoader` from the published `awfl-us/compiler`
  module, including the same Scala-object discovery conventions.
- The existing `awfl-us/compiler` remains responsible for input/output schema
  generation. A coordinated `--target rust` switch in its CLI is still pending.

## Opt-in generalized control-flow backend

The low-level `RustEmitter.emit/write` retains the legacy pure ABI 1 and
effectful ABI 2 (IPC version 1). The `RustEmitter.emitV2/writeV2` entry
points select runtime **ABI 3** and **IPC protocol 2**. The standalone
`NativeMain` CLI and `NativeCompiler` library now use ABI 3 by default;
legacy emission requires `--abi=legacy` or `Backend.Legacy`.
`ControlFlowLowering` compiles nested branches, `ForRange`, `For`, `Fold`,
`Return` and Calls into deterministic CFG blocks. `ContinuationAnalysis`
calculates live slots across loop backedges. Each effect saves the successor
CFG block and only the live tagged slots, so a new native subprocess resumes
without replaying earlier branches or completed calls.

The new backend supports effectful `For` and `Fold` and mixed nested
`ForRange`/`Switch` trees, as exercised by generated Rust conformance
fixtures. The server has an opt-in `protocolVersion: 2` execution setting
that pins the chosen wire version. Native execution in the server is opt-in and disabled for normal traffic;
the ABI 3 CLI default does not enable server-side workflow routing. See
[the v2 IPC specification](../docs/native-ipc-v2.md).

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
# Optional, for differential testing of ABI 1/2 only:
sbt -batch 'runMain us.awfl.compiler.rust.NativeMain your.package.WorkflowBuilder ../legacy_gens ../runtime --abi=legacy'
```

This generates an **ABI 3** Cargo project for each DSL workflow, each with a
deterministic distinct executable name. Build with Cargo for local tests or
publish a verified static musl binary via the existing GCP/IAM-authorized
Workflows publication path. Generation alone does not deploy it or enable
server-side native execution. CI builds and executes the generated CLI crate
using real Rust and validates the versioned JSON protocol.

## Integration tests

```bash
# In sibling checkout of awfl-us/dsl at the typed operation registry PR:
sbt -batch publishLocal

# In compiler-rust/frontend:
sbt -batch \
  'Test / runMain us.awfl.compiler.rust.FrontendSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowLoweringSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowSmoke' \
  'Test / runMain us.awfl.compiler.rust.NativeCliSmoke'

# In compiler-rust:
cargo test --workspace --locked
cargo build --manifest-path fixtures/native-smoke/Cargo.toml --locked
python3 scripts/test_native_smoke.py
python3 scripts/test_control_flow.py
python3 scripts/test_native_protocol_v2.py
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

Only the explicitly selected legacy backend retains ABI 1 (plain JSON) for
pure work and ABI 2 / protocol 1 for effectful work. By default, pure and
effectful workflows both use ABI 3 / protocol 2, which the server's
opt-in backend supports. Normal Cloud Run API/Jobs revisions still have
native execution disabled.

One or more direct typed Calls in a top-level ForRange support experimental
per-site suspension and resumption with a tagged completed-output prefix.
When multiple Calls occur in one iteration, a second invocation frame
identifies the Call site and previous results from that iteration are saved.
Each new Rust subprocess skips finished effects; the server journals their
outcomes and can recover after worker takeover.

A top-level Switch now suspends at direct Calls in its selected branch.
Its two-frame continuation pins the branch and Call site, and previous
Call results are restored without re-evaluating branch conditions.
Pure and unmatched branches still complete without dispatching effects.
A two-level nested Switch also supports direct Calls in its inner branches.
Three frames pin the outer choice, inner choice and active Call site.
A two-level ForRange can likewise suspend at direct Calls inside its inner
range, with separate saved prefixes for completed outer and inner iterations.

Legacy ABI 2 intentionally rejects effects beyond two Switch/ForRange
levels, mixed nested control flow, and effectful For/Fold. The default ABI 3
backend supports the tested CFG versions of these constructs, but `Try`,
`Parallel`, callbacks/children, complete Google/CEL semantics, external
effect parity and unrestricted nested graphs are not yet production-ready.
Unsupported expressions fail compilation with node context rather than
falling back silently to YAML.
