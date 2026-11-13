# Project Manager — fast native iteration and live integration plan

## Local-first development default

**For ordinary compiler/server fixes, do not deploy Cloud Run.** Run the
complete Jobs Express service (`npm run start:jobs` on localhost:5055)
and the actual compiled Rust executor inside the Scala GKE development
environment. Run Jobs in an interactive persistent foreground session;
check its health from another command. See
[the server local-first guide](https://github.com/awfl-us/server/blob/main/docs/native-local-development.md).

The existing server `npm run native:local` is a fast real-Rust,
recorded-HTTP *replay* option and does not perform new Jobs service
calls. The Jobs server itself runs the real HTTP route implementations,
but Firestore/GCS/LLM operations require working development
configuration. Avoid repeated GCS publications and Cloud Tasks during
the test/fix loop; use the isolated cloud canary only for final
integration/IAM checks, with Terraform-owned infra.


**As of November 13, 2026.** This is the active execution plan for the Project Manager native Rust backend. Historical smoke tests, compatibility inventories, and benchmark plans remain useful evidence, but their older "next" sections do not supersede this plan.

**Goal:** One developer command builds and publishes ProjectManager plus its registered dependency closure. One ordinary AWFL agent-session API call launches it. Neither action should require manually building child workflows or editing Firestore.

## Current verified state

| Component | Verified state |
| --- | --- |
| Compiler | Compiler-rust [PR #31](https://github.com/awfl-us/compiler-rust/pull/31) merged: one JVM batch emits 26 native workflows from 12 unchanged Scala modules, including ProjectManager, Tools, Prompts, Summaries, ToolDispatcher, Chain, ContextCollapser, Tasks, Agent, and helpers. Includes lowering the known legacy UUID CEL literal into the existing native UUID operation |
| Dependency closure | Static named subworkflow references resolved across all 26. There are six runtime-selected child call sites; known dynamic names are registered explicitly. This does not prove coverage of every future dynamic agent/tool name |
| Build | Workflows [PR #13](https://github.com/awfl-us/workflows/pull/13) merged: one shared Cargo workspace and target directory, stable generated source fingerprints, ABI/source validation and 26 static x86-64 musl ELF checks |
| Timings | First successful 26-workflow build 199.3 seconds; first rebuilt-Scala repeat 107.4 seconds; warm unchanged-source repeat 52.5 seconds with 0.11 seconds in Cargo. These are one-environment observations, not p95 benchmarks |
| Publisher | Three representative release manifests (root ProjectManager, Prompts, CliTools) passed a **fake-GCS** publication test, including callback/storage/parallel capabilities and skipping unchanged release aliases. All 26 have **not** yet been activated in real GCS |
| Native child dispatch | Server [PR #97](https://github.com/awfl-us/server/pull/97) merged: a durable native.parallel parent can invoke a pinned, nonparallel native workflow.run child within the same Jobs request; no new Cloud Task per synchronous child |
| Storage read | Native storage.object_get implemented and tested, restricted to the workflow-files bucket with a 128 KiB object cap |
| Native callbacks | Server [PR #98](https://github.com/awfl-us/server/pull/98) is **merged** (deployment verification pending). It reuses the existing user-scoped callback records and authenticated Jobs registration/delivery endpoints for callback.create and callback.await, rather than introducing a new callback service |
| Tests | Server native tests: **93 passed / 42 skipped / zero failed**. Real compiled tools-CliTools Rust binary completed callback create/register/producer/wait/resume with **mocked authenticated Jobs HTTP** and in-memory callback test adapters; not a real Cloud Run completion |
| Live native root | First real Jobs ProjectManager canary performed six effects, reached native.parallel, then failed because an **old tagged Jobs revision** rejected workflow.run in the operation allowlist. Its task was removed and execution cancelled. Not a completed native run |
| Google baseline | Separately, ProjectManager completed a real Google Workflows session, including LIST_DIRECTORY and READ_FILE. This is functional workflow evidence, not native backend parity |

## Developer command and safety boundary

From the workflows checkout, with a compiler-rust sibling checkout:

    python3 scripts/native-dev.py codebase-ProjectManager --recursive
    python3 scripts/native-dev.py codebase-ProjectManager --recursive --publish

The first command performs compilation/build/validation **without changing GCS**. The second uses existing GCP deployment IAM to write to the dedicated native-workflows artifacts bucket. Pass --compiler, --output and --bucket when workspace locations differ. The publisher validates all binaries before touching GCS, then writes content-addressed executables and sequentially activates the workflow aliases. Activation is **not one atomic transaction across 26 mappings**; a partial failure must be detected and reconciled. Unchanged binaries and mappings are skipped.

Native publication does not enable production routing. A regular create_session call with agent_id=project-manager remains the end-user launch interface; route it to native only after an isolated, authorized native test proves it works.

## Existing callbacks — correct integration, known limitation

Native callback.create stores an idempotent callback record under the **existing** users/{uid}/callbacks collection and returns an internal callback URL. The normal authenticated Jobs callback registration endpoint reuses that record; normal callback delivery records its result exactly once, rejecting conflicting subsequent deliveries. Native callback.await subscribes to the same record and resumes the compiled continuation.

Current callback.await **holds an active Jobs request** for up to 25 minutes (below the 30-minute Cloud Tasks deadline). This is not a general event-driven durable callback wake-up. Long waits, a Jobs crash during the wait, and retries of uncached child effects remain a follow-up design decision. Avoid promising one-hour callback parity.

## Next work, in order

1. **Server #98:** verify the merged changes are deployed and confirm the Jobs revision accepts all required native effects: HTTP, log.write, native.parallel, workflow.run, storage.object_get, callback.create, callback.await. Do not send a current release to the obsolete tagged worker.
2. **Publish the full closure:** run the single merged developer command with --recursive --publish, using existing IAM. Verify **all 26** active GCS mappings and immutable hashes match the built binaries; validate required protocol, trusted-global and parallel metadata. Keep regular production routing unchanged.
3. **Run isolated real native ProjectManager:** use the existing authenticated Workflows API / Cloud Tasks / Jobs execution path or isolated canary revision. Send a concrete agent task; verify real HTTP effects, root fork/join, Prompts workflow-files read, compiled Tools child, callback registration/delivery/resume, final session result.
4. **Fix only observed failures and repeat:** record the native execution ID, first failing operation, callback/child context and genuine service response. Reuse the warm batch publisher instead of repeating broad audits.
5. **Compare real behavior and latency:** same caller-facing launch path on Google and native, representative multi-effect request, correct result, stage timings, Cloud Tasks count, Rust process overhead and Firestore writes. Earlier direct GCP probes using different launch paths cannot establish speedup.

## Acceptance conditions

- One command compiles and publishes the complete registered closure; unchanged source does not trigger 26 redundant Rust rebuilds.
- One authorized normal session launch runs ProjectManager to completion through real native Jobs, child workflows, storage, parallelism, and callback delivery; the result is visible through normal session history.
- No unverified production traffic switch. No new callback collection or scheduler. All future GCP infrastructure and IAM updates must be expressed in Terraform.
- Document operationally significant remaining gaps, particularly dynamic workflow names and callbacks beyond the active Jobs request deadline.

Related: [native development loop](https://github.com/awfl-us/workflows/blob/main/docs/native-development-loop.md), [server native executor](https://github.com/awfl-us/server/blob/main/workflows/native/README.md), [prior Project Manager smoke](projectmanager-native-smoke-2026-11-13.md), [memory-first executor](native-memory-execution-plan.md), [local child execution benchmarks](native-local-subworkflows-plan.md).
