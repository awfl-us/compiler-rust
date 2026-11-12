# Next milestone: real AWFL workflow execution and Google comparison

**Latest:** Server #91 (small per-process verified artifact cache) and #92
(reuse durable in-memory state within an active Jobs execution) passed GCP
canaries. Five native `helpers-ToolDefs` executions completed with a
four-warm-run median of 0.917 s; the worker now needs only two document
reads, one claim, no renewals, and the same durable effect writes. See
[the follow-up measurements](native-vs-google-tooldefs-2026-11-12.md).
The outstanding task is still a same-AWFL-API comparison and a more
representative multi-effect workflow; avoid expanding orchestration.


**Current measured checkpoint (November 12, 2026):** the end-to-end
authenticated native `/workflows/execute` → Cloud Tasks → Jobs →
Firestore test **succeeded** with unchanged `helpers-ToolDefs` and one
journaled HTTP 200 effect. Server PRs #82, #86 and #87 are **merged**.
The first matching-input Google-vs-native probe produced **5/5 successful
executions on each engine**. Warm last-three medians were 0.422 s Google
and 1.445 s native, but **different launch APIs prevent a fair speedup
claim**. Details, revisions, raw timing and the still-needed same-API
experiment: [November 12 differential](native-vs-google-tooldefs-2026-11-12.md).
The priority is to measure the ~1s warm Jobs worker cost (artifact lookup,
Firestore/effect persistence, signed HTTP) and retest fairly, rather than
building extra fork/join orchestration. Stable native production routing
remains disabled. The dated phase/checklist material below is historical.

**Post-merge checkpoint — November 12, 2026:**
Compiler [#18](https://github.com/awfl-us/compiler-rust/pull/18)
is **merged into `main`** as `a285f66`, including nested fork/join,
JSON-text schema templates, `time.format`, and single-variant compilation.
Merged [workflow-utils #8](https://github.com/awfl-us/workflow-utils/pull/8)
restored the original `Llm.chatJson` template helper. Server
[#82](https://github.com/awfl-us/server/pull/82) was still **open at this
earlier compiler-only checkpoint**; it has since merged (see latest above).
The aligned source-emission audit is **65/66**, with the remaining
`initEnvResult_*` lexical-scope blocker intentionally deferred.
Nothing in these merges establishes the awaited authenticated **effectful**
Workflows → Cloud Tasks → Jobs → Firestore execution or a matched
Google/native benchmark. Those are still the next concrete tasks.
All IAM/infra adjustments belong in Terraform.

**Decision / owner:** AWFL native Rust compiler + server, November 12, 2026.
**Status:** plan only. No live effectful native canary, Google differential
result, or production traffic migration has been completed under this plan.

## November 12 live baseline and workflow selection

`LoadConversationHistory.scala` and its original Jobs ingestion handler date
to 2025. They remain useful **unchanged-source compiler and effect-journal
fixtures**, but are **not representative of current AWFL agent workloads**.
The first live Google invocation of `LoadConversationHistoryDev` failed with
HTTP **400**: Jobs middleware moves the legacy request-body `userId` into
`req.userId`, then removes the body field; the legacy handler incorrectly
read it again. The failed execution took **11.93 seconds**, which must not
be reported as workflow orchestration performance. The paired server PR #82
includes a handler fix to use the
middleware-resolved identity and tests a synthetic empty JSON-array input
without Firestore writes. Do not conflate that code fix with an observed
successful native or Google execution after deployment.

For the **current Jobs transport/OIDC smoke**, the newer deployed
`AwflApiProbeDev` Google Workflow (updated November 10) calls `GET /health`
at the Jobs origin with OIDC. Its November 12 live execution succeeded with
HTTP **200**, body `OK`, and a **739 ms** start-to-end duration (execution
`d801c57f-8540-4242-ba1a-8de9fa380cef`). **One run, not a benchmark**.
This probe is deployed YAML and has **no matching unchanged Scala source**
in the workflows repository, so it cannot by itself establish Scala-Rust
compiler parity. For a meaningful real-world differential, choose a current
Scala-defined workflow such as `Summaries`/an active agent after a separate
health/OIDC smoke and pin the exact definition and safe deterministic inputs.

Deployed service conventions: `jobs/native`, `jobs/producer`, `jobs/callbacks`
and LLM usage handlers consume middleware-populated `req.userId` /
`req.projectId`; older Firebase and workflow-execution routers retain
body/query fallbacks. Current native effect dispatch overwrites forwarded
user/project identity with its trusted execution scope. Preserve that
behavior when comparing against Google Workflows. The two forms of user ID
must **not** silently drift between the backends.

Terraform remains authoritative for **all infrastructure and IAM**:
`server/infra/native-execution` owns queue/task-invoker/Jobs IAM; the
`workflows/infra` root owns the native GCS artifact bucket and its IAM.
The GKE consumer's Node ADC identity cannot read native release objects,
create Cloud Tasks, or write native Firestore executions; the separately
injected gcloud credential was able to publish a canary-only release.
The deployed runtime already has the required IAM. **Do not grant the
development identity broad roles solely for a canary.** Use runtime and
GitHub deployment identities, review a Terraform plan before any IAM/infra
change, and leave global native routing off.

## Objective and scope

Prove that the **existing** Scala -> native Rust -> authenticated Workflows
service -> Cloud Tasks -> IAM-protected Jobs -> Firestore implementation can
execute a real, unchanged workflow correctly, and measure whether its
orchestration overhead improves on Google Workflows. Prefer repairing a
specific demonstrated incompatibility over building new orchestration
infrastructure. Keep the public Scala DSL and existing YAML backend intact.

`Parallel` / `ParallelFor` already compile to real Rust fork/join and run in
one Jobs worker, with one **process-local eight-permit** budget shared by
nested branches, durable child records, effect journals, epoch-fenced leases
and checkpoint replay. The temporary *distributed Firestore permit layer*
was reverted in server PR #82. Do not reinstate it, implement a global fleet
quota, or build an orphan-record sweeper **for this milestone**. Revisit only
for a demonstrated multi-worker branch-dispatch use case or observed resource
or lifecycle problem. Per-execution leases and parent/ancestor fencing are
still essential; they are **not** the removed distributed permit subsystem.

## Verified starting point (not extrapolated to production)

| Evidence | Verified | Not proven |
| --- | --- | --- |
| Unchanged `LoadConversationHistory.scala` | Scala compiler emits a native Rust ELF; IPC effect preparation and resume pass; server `runNativeWorkflow` journals one mocked `http.post` effect, completes and replays without redispatch | Real remote HTTP, an authenticated deployed Jobs execution, Google backend equivalence |
| Native server Node suite | 68 passed / 29 skipped / 0 failed with optional real ELF fixtures available | Skipped optional tests, live deployment and full external effect semantics |
| Real Firestore emulator | 7/7 pass with generated nested and parallel Rust, crash/recovery and replay cases | Production Firestore load, cloud network, in-flight remote exactly-once delivery |
| Local real Rust IPC timing | 40 invocations per stage; prepare-effect median **6.15 ms**, resume median **3.53 ms** | Cloud Tasks, Firestore, artifact lookup, actual HTTP, cold Jobs startup, or a Google speedup |
| Earlier isolated pure GCP canary | One **6,836 ms** enqueue-to-result observation including startup | An effectful workflow or statistically useful latency comparison |

Prior branch checks were green after simplification, and compiler-rust
PR #18 is now merged while server PR #82 remains open. Confirm the **latest**
commit/run before using this as a deployment preflight. The 65/66 constructed-AST *source-emission*
coverage is not 65 runnable workflows; one known `initEnvResult_*` out-of-
scope reference remains deliberately deferred.

## Phase 1 — Reproducible local real-workflow execution (completed)

1. Pin workflow, DSL, helper, Rust compiler/runtime and Jobs server revisions;
   use the **unchanged** source `LoadConversationHistory.scala` (not a
   handwritten replica of its generated JSON).
2. Compile to an ABI 3 / IPC v2 Rust binary and inspect its manifest,
   `allowedOperations`, SHA-256 and effect signature.
3. Run the compiled ELF through the existing `NativeExecutionStore` and
   `runNativeWorkflow` using a deterministic mocked HTTP handler. Verify
   trusted tenant/project headers, endpoint, OIDC audience, recorded effect,
   final result, and replay without another remote call.
4. Keep these checks in paired server CI using pinned `workflows` and
   `compiler-rust` sources; don't silently skip the real-workflow test when
   the executable is unavailable.

**Gate:** generated binary and server-worker integration both pass on CI.
**Result:** implemented on draft server PR #82 and passed locally/CI.

## Phase 2 — Controlled authenticated Jobs canary (next)

1. Select a **current representative Scala workflow** whose Jobs endpoint
   uses authenticated `req.userId` and `req.projectId` and whose effects can
   be exercised with deterministic synthetic inputs. Do not use the old
   conversation importer as the sole live correctness/performance workload.
   If the candidate requires unsupported operations, record the concrete
   compiler gap and choose the smallest suitable current workflow; do not
   invent a new parallel execution subsystem to make it run.
2. Reconfirm native production routing stays **off**
   (`AWFL_NATIVE_EXECUTION_ENABLED=0`), regular Google routing is unaffected,
   and existing revisions and current IAM resources are understood. Capture
   the exact deployed Jobs/Workflows revisions, artifact bucket and Terraform
   state owner; do not make ad hoc infrastructure changes.
3. Prepare one **isolated test** workflow deployment and safe test endpoint
   (or an approved, deterministic mock adapter), with no customer data or
   non-idempotent production side effects. Publish the static Rust ELF through
   the existing authorized artifact path, pin digest, ABI and allowed effect
   operations. Keep secrets entirely in server-owned auth; never supply cloud
   credentials to the Rust process.
4. Start it via the authenticated **Workflows service API**, rather than
   directly fabricating a Firestore record or invoking Jobs. Follow the
   existing OIDC-signed Cloud Tasks dispatch to the trusted Jobs service.
   Scope any native opt-in to the test deployment only, not global routing.
5. Trace request -> task -> job -> artifact resolution -> checkpoint/effect
   journal -> terminal status and history. Check tenant isolation, correct
   headers, result payload, and error codes. Duplicate delivery must not
   resend an effect that is *already journaled*. A pre-journal ambiguous HTTP
   outcome must remain uncertain/reconcilable, never auto-replayed.
6. Demonstrate a **rollback for new starts** to Google Workflows; keep
   existing native runs pinned to their original artifact for resume. Verify
   status/cancellation and that normal production feature flags are unchanged.

**Gate:** one successful authenticated isolated effectful run, traceable
evidence of the steps above, no credential leakage, and a tested routing
rollback. Abort if the test cannot be safely isolated or authorized; a local
mock-effect run is not a substitute for a live Jobs canary.

## Phase 3 — Correctness and differential comparison

Deploy/compile the **same unchanged Scala definition** to both backends.
Use the same inputs and a disposable deterministic test HTTP target or
recorded effect fixtures; do not double-send customer/LLM/tool side effects.
Compare:

- returned result, null/missing fields, serialized JSON and trusted globals;
- HTTP method, path, headers, authentication audience and response shape;
- error/`Try` behavior for confirmed 4xx/5xx versus ambiguous transport
  failure, and current retry/idempotency policy;
- checkpoint/resume after a simulated worker interruption, duplicate task,
  cancellation and re-delivery;
- status/history visible through the existing Workflows API.

Record each observation as **match / documented difference / blocked** with
source line, artifact/commit digest, test input and both traces. For future
parallel-bearing workloads compare actual Google `parallel` behavior and
ordering, rather than assuming indexed Rust joins are automatically equal.
Do not relax compiler lexical-scope validation to achieve a green report.

**Gate:** the selected workflow has no unexplained semantic drift for its
supported operations. Expand to one more representative workflow *only after*
this first case is sound.

## Phase 4 — Performance comparison and decision

Collect at least **30 warm runs per backend** with identical deterministic
effect responses and record cold starts separately. Report p50/p95/p99 where
sample size permits, not a single speedup ratio. Capture distinct timings:

1. authenticated request -> Cloud Tasks enqueue / dispatch;
2. Jobs cold/warm startup and pinned artifact materialization;
3. Rust startup + IPC/computation;
4. Firestore prepare/commit/checkpoint/journal;
5. authorized mock/real test HTTP latency;
6. end-to-end response/result visibility, number of commits and cost.

Keep a matched Google Workflows trace for the same workflow, input, target
endpoint and timing conditions. Preserve all raw measurements and command/
deployment manifests alongside a dated result report. The local 6.15/3.53
ms Rust numbers measure subprocess/IPC **only** and are not comparable to
Google's remote orchestration latency. Diagnose the largest **observed**
bottleneck before optimizing anything.

**Gate:** correctness first, then a quantified latency/cost comparison with
warm/cold separation and a clear recommendation on whether native routing
is worthwhile for this workflow.

## Explicitly deferred / stop conditions

- No distributed per-root permits, cross-root fleet scheduler, forced
  recursive child tombstoning, or new parallel protocols without evidence.
- No broad user traffic, default feature-flag change, public execution
  migration or removal of the Google backend.
- No automatic retry after an HTTP request whose remote outcome is unknown.
- Callback inbox, child workflows, GCS/PubSub connectors and the lone
  `initEnvResult_*` scope issue are **separate** compatibility work; address
  only when the chosen real workflow actually requires them.
- If the candidate cannot safely exercise a real endpoint, test a pure or
  mock-only deployment first, record that limitation, and do not claim the
  effectful canary gate passed.

## Where to run and track

- **Compiler:** `scripts/test_real_workflow.py`, `frontend/.../RealWorkflowSmoke`,
  pinned `LoadConversationHistory.scala`, `docs/current-state-and-roadmap.md`.
- **Server:** `workflows/native/realWorkflowIntegration.test.js`,
  `workflows/native/parallelFirestore.emulator.test.js`,
  `.github/workflows/test-native-try.yml`, and `workflows/native/README.md`.
- **Production mode:** standard Google routing remains the fallback; do not
  remove it. Record a dated canary report only after actual deployment and
  observation, with exact workflow digest and measurements.
