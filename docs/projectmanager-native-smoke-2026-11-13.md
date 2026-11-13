# Project Manager native Rust execution smoke — November 13, 2026 UTC

This checkpoint tests the **unchanged** `us.awfl.workflows.codebase.ProjectManager`
Scala definition, inherited `Agent` workflow, and generated `-tools` / `-prompts`
variants. The Scala frontend generated all three native ABI 3 / protocol v2
Rust crates without errors. Cargo built all three static-musl release executables.

| Executable | Verification | Result |
| --- | --- | --- |
| `codebase-ProjectManager-tools` | Real Rust executable, mock HTTP and `helpers-ToolDefs` result | **Passed**; 11 returned tool definitions |
| `codebase-ProjectManager-prompts` | Real Rust executable, mock GCS read, CLI and Tasks child results, numeric `fund=100`, `spent=0` | **Passed**; 10 prompts, correct numeric funding message |
| `codebase-ProjectManager` | Real Rust executable across 31 IPC suspensions/resumptions, using actual compiled Tools/Prompts outputs and deterministic mock external responses | **Passed**; returned synthetic assistant response, zero cost and usage |

The main test exercised first-call session/exec registration, best-effort status
and event writes, lock acquisition/release, a two-lane native fork/join,
compiled child `workflow.run` effects, context input, LLM request and response,
11 tool-definition logs, empty tool-dispatch result, completion updates,
summary/chain calls, and final context-collapser call. The fork lanes were
resumed through the generated Rust binary using their real `pc_` entrypoints;
**the test intentionally simulates the join and is not a verification of
concurrent durable GKE scheduling**. Tool-dispatch, Summaries, Chain,
ContextCollapser and other nested helper workflows return synthetic
responses, not their own real compiled results. No HTTP request reached
external services.

## Compatibility fix discovered

The `Funds` trait uses the existing CEL expression
`"Original funds allocated for the current task: $" + input.fund` and
`"Spent so far: $" + input.spent`, where `fund` and `spent` are numbers.
Google Workflows `+` permits scalar-to-string concatenation, while native
`ops::add` previously required matching operand types. This caused
`codebase-ProjectManager-prompts` to fail on real numeric inputs.

The Rust runtime now supports strings concatenated with integer, double and
boolean operands, and mixed integer/double arithmetic. Its unit test covers
both operand orders, non-convertible types, and integer overflow. The existing
Scala DSL and workflow source remain unchanged.

## Reproduce in the AWFL Scala+Rust GKE consumer

1. Load the existing `workflows` Scala classes in the compiler's Scala frontend
   test classpath, and run `NativeMain` for
   `us.awfl.workflows.codebase.ProjectManager`, directing output into a
   temporary generated-crate directory. The generated source contains
   `codebase-ProjectManager{,-tools,-prompts}` directories.
2. Build each generated Cargo crate with the current `runtime` path and
   `--target x86_64-unknown-linux-musl --release --target-dir <target>`.
3. Run the smoke script with paths set to those generated files:

```sh
export AWFL_PROJECTMANAGER_GENERATED_DIR=/workspace/projectmanager-native-20261113
export AWFL_PROJECTMANAGER_TARGET_DIR=/workspace/projectmanager-native-target/x86_64-unknown-linux-musl/release
export AWFL_SERVER_NATIVE_DIR=/workspace/server-native-workflow-env/workflows/native
node scripts/test_projectmanager_native.mjs
```

Expected success marker: `PROJECTMANAGER_NATIVE_SMOKE_PASS`, with
`binaryCount=3`, `toolCount=11`, `promptCount=10`, `transitions=31`.
The script checks native outputs; any failure exits nonzero. These paths are
example local GKE workspace paths, **not** production release locations.

## Remaining meaningful tests

- Compile and execute the other real ProjectManager dependency workflows
  rather than mocking them, especially ToolDispatcher, Summaries, Chain and
  ContextCollapser. Include a real tool-call path, not only an empty list.
- Run the complete dependency closure through the authenticated native Jobs
  service and its real fork coordinator with controlled external effects, then
  compare the behavior and latency against Google Workflows using the same
  launch path. Never infer production correctness from this local smoke.
- The process-local `createNativeLocalRunner` currently excludes
  `native.parallel`; the main fork in this smoke is driven through raw native
  IPC to avoid claiming support that the fast local runner does not yet have.

No Terraform, IAM, GCS release, runtime routing or application workflow source
was changed for this verification.
