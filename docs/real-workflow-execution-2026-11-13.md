# Real workflow Rust execution tests — November 13, 2026

This is **execution of original AWFL Scala workflow definitions**, not another
AST/source-emission compatibility audit. The definitions were taken from
`awfl-us/workflows` at `c84270f` (main), compiled without modifying the
workflows into native Rust IPC v2 / ABI3 using compiler-rust main (`0375d50`
when checked out), and built with `cargo build --offline --release --target
x86_64-unknown-linux-musl`. All calls used the server's real
`createNativeLocalRunner` and `runNativeArtifact` code. External HTTP handlers
were **mocked in process**; no real Jobs requests or task mutations occurred.
This is a local functional test, **not** a Google Workflows differential or
GCP Cloud Run performance benchmark.

## Successful executions (7 of 7)

| Actual Scala workflow | Executed branch and result | Effects |
| --- | --- | ---: |
| `LoadConversationHistory` | POST to `/convo-history/loadConvoHistory` with object/user IDs, returns `{"status":"success"}` | 1 POST |
| `helpers-Tasks` | Empty response to current, queued, and done queries; emits three `None found for session` messages | 3 GET |
| `helpers-Tasks` | Existing in-progress task; emits correctly formatted summary and two empty-task messages | 3 GET |
| `tools-Tasks` | Unsupported tool; correctly returns error text without calling HTTP | 0 |
| `tools-Tasks` | `CREATE_TASK`; correct session-scoped POST body and returned task summary | 1 POST |
| `tools-Tasks` | `UPDATE_TASK` to `Done` with no queued tasks; performs PATCH, current GET, queued GET and emits no-promotion message | 1 PATCH + 2 GET |
| `tools-Tasks` | `UPDATE_TASK` to `Done` with a queued task; performs PATCH, current GET, queued GET, promotion PATCH and emits promoted-task summary | 2 PATCH + 2 GET |

**Total:** 15 controlled HTTP effects, no external services called. The
initial mocked UPDATE_TASK response omitted task fields including `created`,
causing a `string()` conversion error; after supplying the complete task data,
the workflow correctly proceeded to its additional GET branches. This was a
**test-fixture problem**, not evidence of a compiler defect.

## Partial result and next blocker: `helpers-Chain`

The unchanged `helpers-Chain` compiled to a real Rust binary. Its zero-tail
path suspended on `workflow.run` and resumed successfully when supplied with
a synthetic result; however, the emitted child target was:

`LocalLeaf${WORKFLOW_ENV}`

This is a literal Google deployment placeholder from `Chain.scala` that the
native local workflow resolver does not accept as a published child name.
Consequently **`helpers-Chain` does not pass actual native local-child
execution**. Resolve environment-suffix handling at the compiler/workflow
boundary using the existing deployment semantics rather than inventing a
second scheduler. The separately mocked single-tail attempt used an invalid
synthetic `ChainableInput` and cannot establish anything about the real
single-tail behavior. Also review `Chain.apply`'s detached
`ConnectorParams(true)` when choosing an appropriate native root/child call
contract. Do not count this workflow as passed until it runs with published
child artifacts.

## Reproduction and limits

The generated source resides in the AWFL cloud workspace under
`/workspace/realwf-execute/`. The compiled musl artifacts are in
`/workspace/realwf-execute-target/x86_64-unknown-linux-musl/release/`.
The local controlled-execution harness is at
`/workspace/server-native-local-subworkflows/workflows/native/realWorkflowProbe.mjs`,
with JSON lines at `/workspace/realwf-native-results3.jsonl`.

The test harness used mock user/project IDs and an explicitly fake base URL.
No benchmark claim is made from the local wall-clock measurements. This
provides evidence of branching, CEL evaluation, effect argument construction,
continuation resumption, and final result formatting, but not GCP network
permissions, API compatibility, Google-result parity, or failure recovery.

**Next tests:** fix the `helpers-Chain` environment placeholder in the native
execution path, then run it using real published children. Continue with
actual `Summaries-All`/`assistant-ExtractTopics-All` and the multi-branch
workflow definitions against mocked external effects; use real compiled
binaries before treating source emission as execution success.
