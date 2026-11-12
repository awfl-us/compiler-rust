# AWFL Scala-to-Rust compiler frontend

The compiler consumes constructed Scala AWFL DSL objects, normalizes them to
structural IR, lowers nested control flow to an explicit CFG, and emits actual
Rust match arms. It does not interpret YAML or execute a graph at runtime.

**There is one artifact format:** runtime ABI **3**, JSON IPC protocol **2**.
`RustEmitter.emit/write`, `NativeCompiler.compile`, and the `NativeMain` CLI
all produce the same compiled state machine. No ABI 1/2 emitters, compatibility
flags or protocol-v1 runner remain in this repository.

## Compiler entry point

`NativeMain` uses `awfl-us/compiler.WorkflowLoader` to load the existing Scala
`core.Workflow` object. `NativeCompiler.compile(workflows, baseName, outputDir,
runtimeDir)` is the programmatic API. The main compiler's `--target rust`
integration remains open until coordinated publishing avoids a module cycle.

```bash
cd frontend
sbt -batch 'runMain us.awfl.compiler.rust.NativeMain your.package.WorkflowBuilder ../rust_gens ../runtime'
```

The generated crate has a stable package name, source digest and manifest with
`runtime_abi: 3`. Use Cargo to compile; publish Linux artifacts as **static
musl x86-64 ELF** through the GCP-IAM-authorized workflows publisher, never by
user-supplied executable paths. Generating Rust does not enable production
native routing. See [implementation status](../docs/implementation-status.md).

## Test commands

The temporary operation-constants DSL dependency lives at
`us.awfl:dsl_3:0.1.0-SNAPSHOT` until the coordinated DSL release.

```bash
# In sibling awfl-us/dsl checked out to the typed operation IDs:
sbt -batch publishLocal

# In compiler-rust/frontend:
sbt -batch \
  'Test / runMain us.awfl.compiler.rust.FrontendSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowLoweringSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowSmoke' \
  'Test / runMain us.awfl.compiler.rust.NativeCliSmoke'

# In compiler-rust (after Scala has generated fixtures):
cargo test --workspace --locked
python3 scripts/test_native_smoke.py
python3 scripts/test_control_flow.py
python3 scripts/test_native_protocol.py
```

GitHub Actions also runs the actual `NativeMain` CLI against a real
`core.Workflow` fixture, compiles its Rust crate, checks the SHA-256 and
executes the ABI 3 binary with protocol 2. A separate job runs Rust unit
checks and Clippy. Scala fixture tests exercise nested Switch/ForRange/For/Fold
with dependent effects, continuation liveness, early Return, malformed
checkpoints, and replay-safe loop invocation IDs.

## Execution semantics and known limits

Pure operations run in the compiled Rust process. External Calls produce an
intent and continuation for the **server** to authenticate, persist and
execute. A successor CFG label plus its live tagged slots is checkpointed;
new processes resume without repeating completed control flow or effects.
See the [protocol](../docs/native-ipc-v2.md) and
[CFG architecture](../docs/native-control-flow-refactor-plan.md).

The native compiler consumes the **original** `CelFunc`, `CelOp`, and `Call`
DSL classes. Standard CEL names remain unchanged: `time.now()` and
`map.get(...)` from `import us.awfl.dsl.*` emit ordinary `CelFunc` nodes using
`CelFunctions.Time.Now` and `CelFunctions.Map.Get` (exact standard names).
Only `CallOperations.HttpPost` and other connector names are backend-neutral. The
Rust frontend converts these strings into **compiler-private** operation IDs
and compiled expressions. Operators keep their original `CelOp` representation;
unknown function/call IDs fail with node-context diagnostics. YAML continues
to compile existing literal Google names while they are migrated.

The current subset covers pure and effectful Switch, ForRange, For, Fold,
sequential Calls, nested control flow and early Return. `Try`, `Raise`,
`Parallel`, parallel For, callbacks/child workflows, complete CEL semantics,
and error/retry parity still need native implementations and conformance tests.
Normal Cloud Run revisions retain native execution disabled; the only live
GCP canary so far is a pure no-effect workflow.
