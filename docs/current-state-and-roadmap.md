# Native Rust execution: verified state, compatibility matrix, and roadmap


## Active priority — ProjectManager native execution, November 13, 2026

**Current authoritative plan:** [fast ProjectManager iteration and real Jobs execution](projectmanager-native-integration-plan-2026-11-13.md). Earlier dated sections below record how the architecture evolved; their old "next milestone" statements are not active instructions.

**Merged:** [compiler-rust #31](https://github.com/awfl-us/compiler-rust/pull/31) and [workflows #13](https://github.com/awfl-us/workflows/pull/13) build all **26 ProjectManager native workflows from 12 unchanged Scala modules** in one batch, with a single Cargo cache and static-musl ELF verification. First full run: **199.3 seconds**. Warm repeated run: **52.5 seconds**, of which Cargo consumed **0.11 seconds**. A three-workflow representative release publication test used **fake GCS**, including parallel/storage/callback capabilities and unchanged-upload skipping; **the full 26-workflow release set has not been published to actual GCS**.

**Server:** [#97](https://github.com/awfl-us/server/pull/97) is merged (durable native.parallel parents and local workflow.run children). The existing restricted storage.object_get implementation is tested. [#98](https://github.com/awfl-us/server/pull/98) is **merged**; live Jobs deployment verification is pending (callback.create and callback.await using the existing user-scoped callback records and Jobs routes). Node native regression: **93 passed, 42 skipped, none failed**, including a real generated tools-CliTools Rust binary resumed from a simulated callback delivery with mocked Jobs HTTP. Callback waits currently retain an active Jobs request up to 25 minutes; durable event-driven wake-up is not implemented.

**Live evidence:** First native ProjectManager canary performed **six real HTTP effects**, then failed at native.parallel on an **old tagged Jobs revision** that rejected workflow.run in the pinned operation set; the task was removed and execution cancelled. A separate Google Workflows ProjectManager request successfully used real LIST_DIRECTORY and READ_FILE, but that does **not** establish native parity.

**Next, in this order:** merge/deploy server #98 and confirm correct revision; run one-command publication and verify all 26 actual GCS releases; execute an isolated real native ProjectManager across fork, child workflow, storage, callback and completion; fix actual failures; compare equivalent requests on the **same** public launch path. No extra orchestrator or new IAM. Production native routing stays disabled; all future infra changes must remain in Terraform.

---


## Project Manager full native smoke — November 13, 2026

The unchanged Scala ProjectManager definition emitted and built **all three**
static-musl Rust executables. Their compiled Tools and Prompts helpers passed
with mocked effect/child responses (**11 tools, 10 prompts**); the compiled
main ran **31 native suspension/resumption transitions to `Completed`** using
these actual helper outputs and synthetic HTTP/downstream helpers. Native
mixed string/number `+` needed a runtime compatibility fix. This is a
**controlled local mock** including a simulated two-lane fork join, not a
live IAM Jobs request or complete nested workflow graph. See
[Project Manager smoke report](projectmanager-native-smoke-2026-11-13.md).


## Real workflow execution tests — November 13, 2026

Executed three unchanged Scala-generated Rust workflows through the native
local continuation runner with controlled HTTP handlers: **7/7 branch tests
passed**, 15 mocked effects, covering `LoadConversationHistory`,
`helpers-Tasks`, and `tools-Tasks` (including DONE→queued-task promotion).
`helpers-Chain` compiled and its initial `workflow.run` effect executed, but
full child dispatch is blocked by its literal `${WORKFLOW_ENV}` placeholder;
that is **not** counted as a passing workflow. See
[the detailed execution report](real-workflow-execution-2026-11-13.md).
These tests did not call live Jobs APIs or change production routing.


## Latest — local nested Rust subworkflows implemented, cloud-tested

Server [#94](https://github.com/awfl-us/server/pull/94) and Scala compiler
fixture [#26](https://github.com/awfl-us/compiler-rust/pull/26) are merged.
Synchronous nested `workflow.run` calls execute under the **same root Jobs
request**, inheriting authenticated context; child binaries are pinned,
SHA-256-verified, and reused from the local cache, enforcing each child's
published operation allowlist. No per-child Cloud Tasks, execution records,
or effect journals are created. Tests: 93 native passes, 36 optional skips.

GCP zero-traffic canary passed 5/5 full real Scala-derived
`LocalParent→LocalChild→LocalLeaf` Rust workflow trees (40 calls per root).
Warm median: **3.491s** client-observed, **2.891s** in Jobs. The **81 Rust
continuation subprocess invocations per tree** consumed median **2.417s**
inclusive, the dominant remaining bottleneck. Details and caveats:
[local-subworkflow implementation plan](native-local-subworkflows-plan.md).

**Priority:** simplify/benchmark continuation-process overhead, not more
Firestore optimization or moving Jobs to GKE before evidence. Existing
Scala workflows still using the Google `workflows.executions.run` spelling
need planned migration to backend-neutral `CallOperations.WorkflowRun` before
native deployment. Stable native routing stays disabled.


## Historical design milestone — local synchronous subworkflows (implemented by server #94 and #97)

The native executor's critical scalability requirement is cheap, nested
subworkflow invocation rather than one Cloud Task/Firestore execution per
child. The DSL already defines `CallOperations.WorkflowRun`; existing
workflow definitions use Google's `workflows.executions.run` connector, but
native Jobs has not supported `workflow.run` as a local handler. Implement
published SHA-verified child Rust artifacts in the **same active Jobs
request**, keep the authenticated root context, and reserve distributed
scheduling for top-level or explicitly detached workflows. See the
[local-subworkflow implementation plan](native-local-subworkflows-plan.md).

First benchmark warm Cloud Run with local children. Assess moving the
executor to GKE *after* removing child dispatch overhead and measuring
root/child latency, not in place of that simplification.


## Memory-first executor merged and cloud-canary verified (November 12)

Server [#93](https://github.com/awfl-us/server/pull/93) added an opt-in
single-lane native executor that keeps Rust continuations/effect outcomes
in memory, coalesces optional asynchronous progress, and awaits one final
Firestore commit; DSL `Cache` controls recovery and idempotency. The
previous durable worker still handles existing/parallel workflows.

On isolated Jobs revision `jobs-00152-nek`, **5/5** real published Rust
`helpers-ToolDefs` runs returned `{"defs":[]}`, each with one HTTP effect
and **zero engine effect-journal documents**. The warm client median
fell to **0.785 s** (earlier 0.917 s), warm Jobs median to **405 ms**
(earlier 517 ms). Profiler: **one Firestore claim and one final commit**,
no progress writes in short workflows. This is a *direct Firestore/Tasks
canary*, not a same-API or load benchmark. Full measurements and remaining
tests: [memory-first implementation plan](native-memory-execution-plan.md).

**Next:** representative longer multi-effect Cloud Run test and same
authenticated Workflows API benchmark. Keep production native mode off.


## New architecture decision — memory-first executor, explicit recovery (November 12)

The workflow author, not the native engine, owns idempotency and explicit
result caching (`us.awfl.utils.Cache`). The next executor runs Rust
continuations and external effects in memory, asynchronously coalesces
observational progress, and awaits only the final Firestore result write.
Crash recovery restarts the workflow; uncached effects can repeat. This
replaces the redundant automatic per-effect journal for opted-in single-lane
releases while retaining the existing durable worker for older and parallel
executions. See [the detailed plan](native-memory-execution-plan.md).


## November 12 — native worker hot-path simplification (latest)

Server [#91](https://github.com/awfl-us/server/pull/91) and
[#92](https://github.com/awfl-us/server/pull/92) are merged and the latter
deployed. The worker now reuses its own **already committed, fenced**
Firestore state between transitions, without introducing a second in-memory
database or Rust process protocol. The hot path eliminated two reads, one
claim and one renewal on `helpers-ToolDefs`; all effect-intent/outcome
durability and pre-HTTP cancellation checks remain.

An isolated GCP canary passed **5/5** real compiled Rust executions with
one journaled HTTP 200 effect each. Its last four runs had a client median
of **0.917 s**, with **517 ms** median in Jobs. The earlier native warm
client median was **1.445 s**; the reduction is shared between a pinned
artifact cache and state reuse, not independently attributed. Regular
production remains Google-backed and native-disabled. For full measured
phase timings and limitations, see
[the comparison report](native-vs-google-tooldefs-2026-11-12.md).

**Next:** representative multi-effect workflow and same-API comparison.
Do not add another state store or distributed scheduler without evidence.


## November 12 — authenticated native execution and Google differential (latest)

**Live native API execution passed.** Merged server
[#82](https://github.com/awfl-us/server/pull/82) provides the durable Jobs
worker, [#86](https://github.com/awfl-us/server/pull/86) restored `/jobs`
prefix compatibility, and [#87](https://github.com/awfl-us/server/pull/87)
made AWFL API keys available through shared HTTP/MCP identity handling.
An isolated zero-traffic native API revision ran the unchanged
`helpers-ToolDefs` Rust artifact via `/workflows/execute` → Cloud Tasks →
Jobs → Firestore; the API returned `SUCCEEDED` with `{"defs":[]}` in
**14.35 s** on its one cold sample. Firestore confirms **one journaled
HTTP 200 effect**. The temporary key was revoked (403 on reuse), the
zero-traffic tag removed, and stable API native routing restored **off**.

The first same-source/same-input cloud differential also passed for
**5/5 Google and 5/5 native runs**, all returning `{"defs":[]}`. Warm
last-three client median: **Google 0.422 s**, **native 1.445 s**;
the *launch methods differ* (Google Executions REST vs native direct
Firestore/Cloud Tasks), so this is directional and **not an API-vs-API
benchmark**. Cloud Run Jobs' own warm request latencies are roughly
**0.93–1.15 s**; profile that worker path before introducing another
scheduler or tuning Rust core loops. See the
[full measurements, execution IDs and caveats](native-vs-google-tooldefs-2026-11-12.md).

**Next:** instrument the warm worker's actual bottleneck; then repeat via
the **same AWFL API** on stable Google and isolated native revisions,
and expand beyond this single HTTP effect. All infrastructure/IAM changes
must stay in Terraform. Historical checkpoints below are intentionally
retained as of their original dates.

## Post-merge checkpoint — November 12, 2026

**Compiler [PR #18](https://github.com/awfl-us/compiler-rust/pull/18) is merged
into `main`** as [`a285f66`](https://github.com/awfl-us/compiler-rust/commit/a285f66df11efd1b2c9811fbd494dcd45541aa7e).
The Scala frontend/native Rust runtime now include compiled nested
`Parallel`/`ParallelFor`, `time.format`, original JSON object-to-string
template semantics, and per-workflow `NativeMain --only=<FullWorkflowName>`
selection. This is a **compiler merge**, not a full native deployment:
paired server [PR #82](https://github.com/awfl-us/server/pull/82) was **open
at this earlier compiler-only checkpoint** (it has since merged; see above),
and regular API/Jobs native routing remains disabled. Merged
[workflow-utils #8](https://github.com/awfl-us/workflow-utils/pull/8) restores
the original LLM schema expression while keeping the Firebase 404 scope fix.

**Aligned current-source result:** unchanged `workflows/main` at `c84270f`
against the reverted helper snapshot, using the *same helper on the scanner
classpath*, yielded **65 Rust source emissions / 66 constructed definitions,
1 scoped-reference blocker, 0 loading errors**. The only blocker is
`evals-experiments-SweBenchLite`'s `initEnvResult_71e5323f` reference outside
its lexical scope. `Summaries` JSON-template variants compile without
looking up a runtime `example` variable. This measures **source emission**,
not Cargo builds for all 65, live effects, or Google behavioral equivalence.

**Test evidence:** the object-string Scala regression and actual generated
Rust ELF preserve literal `${example.title}` / `${example.summary}` even with
`example` in the input; ordinary executable objects still reject unbound
references. Focused Scala CLI/collections tests, Cargo build, and the aligned
workflow audit passed locally. Compiler Rust and compatibility CI checks
succeeded before the merge. The longer Scala-to-native CI was still running
at merge time and later **passed** on the merged PR head (GitHub run
`43303204990`). This does not establish live Jobs behavior.

**Next:** run one unchanged effectful workflow through the authenticated
Workflows → Cloud Tasks → Jobs → Firestore path in isolation; compare
results and end-to-end latency with Google Workflows, then fix only observed
gaps. Keep infrastructure/IAM changes in Terraform. Do not add another
parallel scheduler or weaken scope validation to inflate the audit.

**Current execution plan (November 12):** [Real-workflow pilot and benchmark](real-workflow-pilot-and-benchmark-plan.md).
That plan and the scope decision immediately below **supersede historical
parallel delivery and release-gate checklists later in this document**.
The next objective is one safely isolated authenticated Jobs run of an
unchanged workflow, then semantic and latency comparison with Google.
No additional parallel scheduler or cleanup subsystem is on the critical
path unless actual testing demonstrates a need.

## November 12 — simplify parallel scope; prioritize unchanged workflows

**Decision:** native `Parallel` and `ParallelFor` are implemented sufficiently
for the current **single-Jobs-worker per workflow** execution architecture.
The per-root process-local eight-permit budget, durable branch records,
checkpoint/effect journals, parent lease/epoch fencing and crash recovery
remain. The experimental distributed Firestore permit layer in paired server
PR #82 was **reverted** (not deployed): there is no independent Jobs worker
per branch to coordinate. Global fleet quotas and orphan cleanup are deferred
unless real usage establishes a need; they are not blockers to testing the
existing architecture.

**Next milestone: existing AWFL workloads.** The unchanged
`LoadConversationHistory.scala` source from the workflows repository compiles
to a native Rust ELF. Its real IPC effect request, continuation and replay
pass the compiler's Python smoke test. The server's Jobs worker also executed
that same compiled workflow with a **mocked** HTTP response: it recorded the
effect once, produced `{ "status": "success" }`, and replayed the completed
execution without sending the HTTP request again. This is now a **mandatory
test** in the paired server CI, which passed on commit `9885751` (run
`43280844780`); the compiler CI also passed on commit `920ac23` (run
`43280871676`). The simplified code passed **68/97** native Node tests
(29 skipped) with the compiled binaries supplied and **7/7** separate real
Firestore emulator tests. No live service call, production rollout or
Google Workflows latency comparison has been performed in this checkpoint.

**Measured local-only IPC baseline:** 40 Rust subprocess invocations per
stage, `LoadConversationHistory`: median **6.15 ms** to prepare the HTTP
intent; median **3.53 ms** to resume after a supplied response. These are
*not* cloud orchestration timings or a Google Workflows speedup claim.

**Near-term order:** (1) completed: retain the unchanged real-workflow test
in CI, (2) next: exercise a representative effectful workflow through the
*authenticated* Jobs path in an isolated test deployment, with routing
rollback, (3) compare correctness and end-to-end latency with its Google
Workflows version, and (4) address only concrete compiler or runtime gaps
uncovered. The one known out-of-scope `initEnv` lexical-scope case remains
deferred; do not generalize compiler scoping just for it. Follow the
[step-by-step pilot plan](real-workflow-pilot-and-benchmark-plan.md).

**Evidence date:** 2026-11-12 (original baseline below: 2026-11-11). **Status:** experimental, opt-in backend; **not production-ready**. This document distinguishes *merged*, *verified on a feature branch*, and *unimplemented*. It does not claim that passing tests equals Google Workflows parity.

## November 12 — genuine three-level nested Parallel and recursive fork fencing

The now-merged compiler fixture `ParallelIrSmoke` constructs **unchanged Scala DSL**
`Parallel` nodes at three nesting levels with deliberately repeated `Call`
labels. That exposed a real Rust normalizer gap: the original DSL's
`Parallel` is a `Step[NoValueT, NoValue]` *statement*. The compiler had
incorrectly tried to resolve its phantom `resultValue` as if it were a
registered Call result when `Parallel` itself was an outer parallel branch.
`Normalize` now lowers that nested branch's result to `null`, without
inventing a global name, exporting an undeclared binding or changing the DSL.

**Local verification:** `ParallelIrSmoke` emits the nested crate, Cargo
1.99.1 builds it, Clippy `-D warnings` passes, and the Python native-IPC
regression traverses all three fork levels and four effects using the **real
generated ELF**. The paired server's worker executes the same ELF using
isolated child execution records: all four effects overlap as permitted,
the root completes `nested-complete`, and a repeat delivery sends no extra
effects. These checks use synthetic effect handlers, not live Google services.

The paired [server PR #82](https://github.com/awfl-us/server/pull/82) also
adds bounded, transactionally checked full ancestor-fork lineage. It passes
**6/6** real Firestore emulator tests locally (including actual worker process
death/recovery and a three-level stale-root-fork fence) and **20/20** focused
unit tests. The stale-root case currently constructs nested Firestore records;
it is distinct from the newly compiled nested ELF worker test. No production
native routing was enabled. Cross-process concurrency admission, orphan
cleanup, live effects, and unchanged-workflow Google/YAML differential parity
remain release gates.

## Latest verified compatibility checkpoint — November 12 (supersedes 55/66 below)

**Original LLM JSON-template semantics restored (November 12, subsequent check).**
The `workflow-utils` [revert PR #8](https://github.com/awfl-us/workflow-utils/pull/8)
is merged: `Llm.chatJson` again uses
`str(CelValue(obj(implicitly[Spec[T]].init(Resolver("example")))))`.
The Rust normalizer now preserves this exact object-to-JSON-*string* use case,
instead of trying to bind `example` as a runtime variable. Ordinary executable
objects still resolve their values strictly and reject undefined names.
Focused Scala normalization tests and the actual generated Rust ELF prove that
`${example.title}` and `${example.summary}` stay literal for both empty input
and input containing an unrelated `example` object. CI now runs these tests.
Recompiling the unchanged `workflows/main` source at `c84270f` against the
reverted, locally published `workflow-utils` helper and running the same
constructed-AST audit yields **66 scanned, 65 emitted, 1 blocked, 0 load errors**.
The sole blocker remains the existing `evals-experiments-SweBenchLite` reference
to `initEnvResult_71e5323f` outside its lexical scope. The older 21/66 result
from a stale workflow *feature* checkout is not the current main-branch baseline.
As before, Rust source emission does not prove cloud execution or effect parity.

**Parallel durability follow-through:** Paired server draft
[#82](https://github.com/awfl-us/server/pull/82) (`d776d76`) extended the
four-test real Firestore emulator suite: a separate Node process with its own
Firestore client receives a lease conflict while a parent is forking, and
observes the terminal result after parent join. All four tests passed locally
with actual generated Rust binaries. This verifies **cross-process active
parent lease exclusion**, *not* process-crash lease takeover, cross-process
concurrency permits, recursive cancellation or production Jobs durability.

**65 of 66 constructed AWFL workflow definitions now emit Rust source (98.5%).**
The read-only audit uses unchanged `workflows/main` at `c84270f`, rebuilt
against helper sources whose tree matches `workflow-utils/main` at `08bda2d`.
The compiler frontend reflection scanner **also** uses the same newly
published local snapshot (`AWFL_WORKFLOW_UTILS_SNAPSHOT=0.1.0-SNAPSHOT`),
rather than silently loading its old `0.3.5` test dependency. Thus the
historical 55/66 result was measuring older helper code and is **not** the
remaining gap in current sources. A first aligned audit produced **56/66**
with nine `time.format` blockers and one out-of-scope `initEnv` reference.

The new native `time.format(seconds[,timezone])` implements an integer/float
epoch to ISO 8601 with exactly six fractional digits, UTC `Z` or a tzdb/IANA
offset (including DST, e.g. `America/New_York`); non-finite timestamps,
invalid zone names and wrong types fail. Both the Scala-generated real Rust
ELF and Rust unit tests pass the UTC, timezone, DST, negative fractional
epoch, invalid-type and replay fixtures. **After this addition the identical
audit reports 65 emitted, 1 blocked, 0 class-load errors and 0 unknown
operation names.** The only first blocker is
`evals-experiments-SweBenchLite` referring to `initEnvResult_71e5323f`
outside its lexical scope; do **not** promote undeclared variables to globals
or modify the application's `initEnv` structure without deciding its proper
control-flow scope.

CI now pins the merged helper revision, publishes its test-only local
snapshot, compiles the unchanged workflow sources with that dependency, and
forces the scanner to use the same library. Reproduce using
`scripts/audit-real-workflows.sh` with `AWFL_WORKFLOW_UTILS_SNAPSHOT` and
`AWFL_WORKFLOWS_ROOT`. These numbers concern **Rust source emission, not
65 independently built/tested workflow executables, Google differential
semantics, effect safety or native production readiness**.

## November 12 parallel implementation checkpoint — supersedes earlier parallel status

### Follow-on source-compatibility audit and native text.substring

Rerunning `AWFL_WORKFLOWS_ROOT=/workspace/workflows-raw-audit scripts/audit-real-workflows.sh`
against the **unchanged, cached compiled workflow classes** after adding the
existing `text.substring` operation to native Rust gives **66 constructed
definitions, 55 Rust sources emitted, 11 first-blocking definitions, zero
load errors, zero unknown operation IDs**. This improves the immediately
preceding branch audit from **54 emitted / 12 blocked**; the newly emitted
definition is `evals-SweBenchLiteEval`. The 11 remaining first blockers are
invalid/raw CEL references (`example` in nine constructed graphs and two
cache-result symbols). The fixed `text.substring` implements the exact required
three-argument Workflows signature, Unicode character offsets, negative and
clamped indexes; standalone generated ELF and Rust unit/replay/invalid-type
tests pass. Neither source-emission count nor those fixtures proves execution
of every emitted workflow or Google differential parity. The workflow classes
were precompiled from a historical `workflows` checkout; rerun this audit
against an explicitly pinned fresh `workflows/main` checkout before using
these counts as a release gate.

**Dependency-provenance finding:** The same **55/66** count was independently
confirmed by the GitHub Actions `real-workflow-compatibility` job checking out
`workflows/main` on this compiler branch. Both audits still resolve an old
`workflow-utils` artifact. The nine unbound `example` CEL paths come from
`Llm.chatJson` constructing a `Spec.init(Resolver("example"))` for a *JSON-text
schema template* wrapped in `str(CelValue(obj(...)))`; this was **not** a
reference intended for runtime evaluation. The YAML encoder stringified the
object and kept `${example.title}` as text inside quoted JSON, whereas the
old Rust normalizer walked into each field and erroneously tried to resolve
`example` as a bound symbol. The other two failures involved a real
`Firebase.read` 404 handler reference to a failed request's out-of-scope
result. [workflow-utils PR #6](https://github.com/awfl-us/workflow-utils/pull/6)
temporarily changed the LLM helper to generate `SchemaExample[T]` directly;
that change was reverted in merged
[workflow-utils PR #8](https://github.com/awfl-us/workflow-utils/pull/8),
in favor of preserving **existing DSL semantics in the Rust compiler**,
while retaining the unrelated Firebase 404 fix.
All actual executable object/step references remain strictly scope-checked.
The 55/66 historical measurement still uses old helper dependencies; rerun
an aligned source audit before comparing current coverage.

The original November 11 compatibility figures below are **historical baseline snapshots**; in particular, rows stating that `Parallel`/`ParallelFor` are not implemented refer to the then-merged baseline, **not** current compiler `main`. Do not change those old audit counts without rerunning the identical constructed-AST audit against the newer compiler. Compiler [#18](https://github.com/awfl-us/compiler-rust/pull/18) is merged as `a285f66`; paired server [#82](https://github.com/awfl-us/server/pull/82) remains an unmerged PR.

- **Merged compiler; server draft:** original Scala `Parallel` and `ParallelFor` lower to separate Rust entry PCs and branch-local slot snapshots, with a version-pinned `native.parallel` fork. The still-unmerged Jobs worker PR #82 creates deterministic, digest-pinned child records, dispatches bounded concurrent child effects through the existing journal, records one parent join, and reconstructs indexed loop results. Parent `Try` handles **confirmed** failed children, not uncertain remote outcomes. A parent fork lease is refreshed during execution; a shared *in-process* permit queue limits nested work without reserving permits while awaiting children.
- **Verified:** Scala `ParallelIrSmoke`; four generated Rust crates built and Clippy-clean; a real compiled binary + worker + transaction-shaped in-memory Firestore store test covers overlapping HTTP effects, zero/one/many indexed lanes, crash after committed children before parent journal, replay, caught/uncaught `Raise`, and uncertain sibling outcomes. Server native discovery suite **85 tests: 63 passed, 0 failed, 22 skipped** (prior to the added emulator cases). **New:** the compiled Rust ELF suites passed **4/4 tests against the actual local Firestore emulator**—concurrent branches + fresh-store replay with duplicate-parent-lease rejection, takeover after injected post-child/pre-parent crash, zero/one/many indexed `ParallelFor` iterations, and parent cancellation fencing. Compiler GitHub Actions and paired server GitHub Actions passed on the prior revisions, including the first three emulator cases; updated four-case CI is being verified on the draft.
- **Subsequent update:** independent Node worker death and parent lease
  takeover, nested three-level generated Rust forks and full ancestor
  fencing now pass the real Firestore emulator (**7/7** after simplification).
  Recursive child-record garbage collection and a cross-process admission
  manager remain unimplemented **by design**; neither blocks the isolated
  nonparallel pilot. Unverified: effectful deployed Jobs routing, remote
  ambiguous-outcome semantics, and parity/latency against Google. Production
  `AWFL_NATIVE_EXECUTION_ENABLED` remains disabled. Emulator tests are **not**
  a live GCP traffic rollout.

**Historical gate order (superseded November 12):** the earlier four-emulator
and cross-process expansion plan below predates successful nested Rust
emulator tests and the decision to stop expanding parallel infrastructure.
The authoritative next gates are now [unchanged-workflow authenticated Jobs
canary -> Google differential -> measured performance](real-workflow-pilot-and-benchmark-plan.md).

## November 12, 2026 — authenticated native globals and URL encoding (feature branch)

The compiler's canonical DSL globals `WORKFLOW_ID`,
`WORKFLOW_EXECUTION_ID`, `PROJECT_ID`, and `LOCATION` now lower to
lookup of a separately supplied, strictly validated native IPC-v2
`trusted_globals` map. `environment.get` / `sys.get_env` allow the
same four Google Workflows built-in names, plus an explicit fallback string;
unknown names never read process environment or caller `input.env`.
The companion server feature branch sources these fields from the
authenticated execution and its server-owned Google Cloud configuration,
not from the tenant project ID. Production native routing remains disabled.

The native runtime now implements `text.url_encode` using UTF-8
percent-encoding with Unicode, spaces and reserved characters covered
by generated-binary tests. The current read-only **66-workflow constructed
AST** audit generates Rust source for **41 definitions** (formerly 4);
**25 still block**: 15 Parallel/ParallelFor and 10 raw CEL/reference
cases. The raw cases include nine `example` references generated by
the existing LLM JSON-schema helper and one cache result reference.
These cannot safely be treated as arbitrary literals or guessed variables.
No class-loading failures or unknown operations were observed.
The results measure **source generation only**, not working binaries
or deployed end-to-end executions. The audit is available as a CI
artifact in the compiler-rust feature-branch runs.

## November 12, 2026 — scoped step/result symbols (newer audit)

The source DSL intentionally allows repeated step labels. The YAML backend
globally uniquifies *step labels* using numeric suffixes; its result
references are still textual. Rust now gives every result, Try-error and
iterator occurrence a distinct compiler-private slot, and uniquely suffixes
human-readable step labels. Textual value references are resolved at
normalization time within the active lexical scope, with branch/loop
shadowing and restoration. Structural node IDs identify external Calls and
continuations independently of labels. No public DSL or special persistence
changes are required.

After removing the previous 18 first-blocking symbol collisions, the
66-definition constructed-AST audit has 0 collision failures, 0 unknown
operations and 0 class-loading errors; 4 emit Rust source and 62 first
block on 37 trusted execution globals, 15 Parallel/ParallelFor nodes and
10 unsupported raw CEL/reference cases. These are first blockers only; no
claim of 66 full native executables is made. The older audit below and
the existing per-workflow table remain historical pre-fix snapshots.

Compiler tests verify repeated Call labels, structurally equal step results,
independent branch bindings, repeated Try-error symbols and nested iterator
shadowing. The generated Rust process test checks two same-named Calls
preserve distinct slots through two resumptions.

## November 12, 2026 — canonical operation migration update

The sections below document the original November 11 execution foundation
and are retained for historical context. **The newer source-compatibility
measurement is in [canonical-operation-compatibility.md](canonical-operation-compatibility.md).**
After switching AWFL workflows and helpers to top-level standard CEL
helpers and generic Call operation IDs, the constructed-AST audit now finds
**4 of 66 workflows emit Rust source, 62 remain blocked, and 0 have class
loading errors or unknown CEL/Call identifiers** (prior baseline: 2/66).
First blockers: 29 trusted execution globals, 18 step-result symbol collisions,
14 Parallel/ParallelFor, and 1 raw CEL reference.
Clock/UUID functions are compiled directly after PR #14 and require
no special journal; existing materialized result slots are checkpointed.
This is source emission only—not completed native binaries or live workflows.

DSL v0.3.5, YAML compiler v0.3.2, and migrated workflow-utils v0.3.5
preserve the old DSL AST while matching canonical standard function names.
The YAML backend resolves generic connector IDs to Google names.
The migrated application workflow source is merged in workflows PR #8.
Future changes to Scala workflow code must remain name-level substitutions;
remaining compiler/runtime work belongs in the respective backends and server.

## Executive view

- **Working native pipeline:** unchanged Scala AWFL DSL objects -> compiler-private normalized expressions and IR -> one statically generated Rust CFG -> content-addressed Cargo/ELF artifact with `runtime_abi: 3` -> `protocol_version: 2` JSON IPC -> server-managed durable external effects. No YAML/CEL interpreter is involved in native execution.
- **Original public DSL remains intact:** `CelFunc(name, params)`, `CelOp(left, op, right)`, `Call(name, call, args)`, `Try`, `Raise`, `For`, `Fold`, `Switch`, `Parallel` and `ParallelFor` retain their shapes. Only string namespaces `CelFunctions` and `CallOperations` were added. Never add public typed AST variants as part of this compiler. The name-only application source migration is in workflows PR #8.
- **Verified real workflows:** unchanged `LoadConversationHistory.scala` compiled to native Rust and exercised HTTP intent/checkpoint/replay with no actual HTTP; unchanged `evals/Product.scala` compiled to three real Rust binaries returning 2 (single), 4 (Cartesian), and 0 (empty) results. The `Product` test covers its pure success path; its failure-path logging is tested with a separate real DSL-built `Try` fixture.
- **Durable effects:** effect intent is persisted before dispatch; outcome/error and resumed checkpoint are journaled together in Firestore under lease-epoch fencing. Successful outcomes aren't redispatched after a committed journal entry. Confirmed HTTP 4xx/5xx errors can enter a compiled `Try` catch; ambiguous transport outcomes cannot safely be treated as a confirmed failure.
- **Single live GCP pilot:** an isolated, **pure** statically linked `NativePilot` ran Cloud Tasks -> tagged Jobs -> Firestore, returned `[2,3,9,9]`, and survived duplicate delivery. Observed time was **6,836 ms for one cold-path sample**, not a useful percentile or a representative speedup measurement.
- **Production:** regular API/Jobs native routing remains disabled (`AWFL_NATIVE_EXECUTION_ENABLED=0`); Google Workflows remains the default. No user workflow migration or live effect traffic was authorized.

## Repository and change-state ledger

| Repository | Verified merged foundation | Latest work as of this snapshot | Deployment implications |
| --- | --- | --- | --- |
| `awfl-us/dsl` | PR [#2](https://github.com/awfl-us/dsl/pull/2), constants only, public AST unchanged | Application source name substitution deliberately deferred | No schema or DSL-API migration required for Rust fixtures |
| `awfl-us/compiler-yaml` | PR [#1](https://github.com/awfl-us/compiler-yaml/pull/1), canonical names map back to Google while old literals still work | Continue YAML differential fixtures | No YAML routing changes |
| `awfl-us/compiler-rust` | PRs [#1–#7](https://github.com/awfl-us/compiler-rust/pulls?q=is%3Apr+is%3Amerged), plus **[#18](https://github.com/awfl-us/compiler-rust/pull/18)** (`a285f66`), ABI3, CEL, `Try`/`Raise`, nested `Parallel`/`ParallelFor`, `time.format`, JSON-text templates and `--only` variant selection | Compiler code is on `main`; source-emission audit **65/66**, focused native tests pass | No server PR #82 merge or native production routing switch is implied |
| `awfl-us/server` | PR [#72](https://github.com/awfl-us/server/pull/72), native launcher/persistence; PR [#78](https://github.com/awfl-us/server/pull/78), confirmed-HTTP-error recovery; PR [#79](https://github.com/awfl-us/server/pull/79), bounded `log.write`, capability gates and real Rust/Node/Firestore-compatible recovery tests | **PR [#82](https://github.com/awfl-us/server/pull/82) remains open:** paired durable fork/join/Jobs worker and test improvements | Code merge is not live-traffic approval; keep native routing off |
| `awfl-us/workflow-utils` | **[#8](https://github.com/awfl-us/workflow-utils/pull/8)** restores original `Llm.chatJson` object-to-string template; keeps Firebase 404 recovery fix | CI green; Rust compiler implements original template semantics | No public helper schema replacement required |
| `awfl-us/workflows` | PR [#3](https://github.com/awfl-us/workflows/pull/3), private GCS static-musl native publisher and `NativePilot` | No application workflows migrated; published pilot is no-effect only | Keep Google workflow deployments active; GCS state and native artifact bucket are separate from cloud-env storage |

## Executable feature matrix

| Feature | Current disposition | Evidence / limitation |
| --- | --- | --- |
| Scala DSL loader and native ELF generation | **Verified** | Scala frontend, `NativeMain`, Cargo, manifest SHA-256 and strict Clippy |
| One CFG backend / IPC 2 / ABI 3 | **Verified** | Generated match arms and tagged-slot continuations; compiler emits no ABI 1/2 |
| `Switch`, `Return`, `ForRange`, `For`, `Fold`, `FlatMap`, nested effects | **Verified tested subset** | Process-level nested CFG, repeated suspension/resume, structural effect IDs, deterministic replay |
| List/map JSON literals, `in`, signed `//` | **Verified tested subset** | Actual emitted Rust executable and invalid-type/zero/overflow regression |
| `len`, `default`, type, map get/merge, list concat, JSON decode/encode | **Verified tested subset** | Rust value operations; remaining CEL overload and edge-case differences are not fully parity-tested |
| `Try`/`Raise` and nested rethrows | **Merged + tested** | CFG exception edges, explicit terminal `Failed`, pure CEL errors and confirmed HTTP failure recovery |
| Existing DSL default `Try` logger | **Merged and locally verified** | Compiler PR #7 fixes catch-only PC; server PR #79 journals bounded `log.write`; post-journal crash restart tested |
| Ordinary trusted HTTP GET/POST/PATCH/DELETE | **Local adapter and bounded integration tests** | Jobs IAM/OIDC, identity override, allowlist, HTTP-error catch; no production live-effect approval, uncertain outcome reconciliation missing |
| `Parallel`, `ParallelFor` | **Compiler merged; server PR #82 open; not production-validated** | Compiler #18 generates nested fork/join and indexed loops; paired server worker branch passes compiled-ELF and Firestore emulator tests; no deployed Jobs parallel canary or complete Google differential parity |
| `str(CelValue(obj(...)))` literal JSON text | **Merged + tested** | Original DSL/YAML semantics retained; `${example.title}` stays a string placeholder, while executable unbound object references still fail |
| `NativeMain --only=<FullWorkflowName>` | **Merged + tested** | Compiles an individual named variant; default retains all-variant behavior, unknown selector fails |
| Callback create/await, child workflow `workflow.run` | **Not implemented** | Need durable callback inbox, child-start idempotency, timeout and cancellation propagation |
| Storage object get, Pub/Sub publish | **Not implemented** | Existing Google connector argument/return contracts need explicit server-side adapters |
| Conversion/text/bytes/time/UUID/environment and arbitrary CEL | **Partial or not implemented** | Native clock/UUID are direct expressions; remaining overloads and authenticated env bindings need implementation; unknown names fail compile |
| Google equivalence and whole agent graph | **Not established** | No complete ProjectManager dependency closure, differential error/retry/parallel ordering or traced live comparison |
| Production isolation and operations | **Not established** | OS/network sandbox, lease maintenance, orphan sweeper, monitoring, benchmarks, rollback and failure drills |

## Latest verified test evidence and interpretation

1. `compiler-rust/scripts/test-local.sh --full` in the **AWFL scala GKE consumer** passed on the catch-only implementation now merged as PR #7, including Scala frontend, Rustfmt/unit tests/Clippy, unchanged `LoadConversationHistory` and `Product`, generated ABI3 crates, compiled CEL/loop tests, seven `Try` fixtures (including `default-log`) and effect/replay process tests. `AWFL_NATIVE_LOCAL_TESTS_PASS (--full)` recorded.
2. Node native server test selection including the **real Rust `default-log` executable**: **37 tests, 34 passed, 0 failed, 3 skipped**. Skipped tests depend on *other* optional binary env variables; not a 37/37 claim. The tests use an in-memory Firestore API with transaction semantics, not an actual remote Firestore load test.
3. Default `Try` integration: Scala `Raise` -> native catch -> `log.write` intent -> authenticated, bounded local log handler -> fenced `recordEffect` to a null result slot -> native resume -> original error persisted as terminal `Failed`. Tenant mismatch and a simulated crash **after journal commit** did not redispatch the log.
4. **Important limitation:** an output log is not globally exactly once. A crash **after the logger emits but before the journal commit** can duplicate the log; stable Cloud Logging `insertId` supports downstream deduplication. A synthetic post-journal crash does **not** prove all network or Firestore failure windows.
5. Rust and Node tests **do not** validate live production HTTPS transport, live Firestore contention, public authenticated app/MCP control flow, or latency parity against Google.

## Ordered next steps and acceptance gates

### P0 — Compiler/server `Try` logging integration (merged; rollout still gated)

- **Completed:** compiler PR #7 merged after Rust/Scala CI; fixed the catch-only CFG PC and generated/clippy-tested the real default-DSL-`Try` fixture. Server PR #79 merged after CI pinned to the merged compiler SHA, with bounded `log.write`, capability gates and Native Rust → Node journaling/terminal-error/replay tests.
- **Still required before live traffic:** verify the regular Jobs/API revisions keep `AWFL_NATIVE_EXECUTION_ENABLED=0`, monitor the post-merge deployment, exercise the public-authenticated launch route in a controlled test, and prohibit deployment of unsupported effect capabilities. A code merge must **not** flip the routing flag.

**Gate:** compiler and server PR tests green (achieved); production switch remains independently blocked pending P1–P4.

### P1 — Build a constructed-AST compatibility and Google comparison matrix

**Progress (November 11, after P0):** read-only scanner and reproducible
[compatibility report](constructed-ast-compatibility.md) now cover 66
instantiated definitions from unchanged application bytecode. Two emit Rust
source (`LoadConversationHistory`, `helpers-ToolDefs`); 64 fail closed at a
recorded first unsupported operation. The compiler now lowers existing
`FlatMap` wrappers without DSL/application edits. This is **source-emission**
coverage, not 2/66 executable conformance or Google differential parity.

**Following pure-operation increment:** the native emitter and runtime now
handle `ToInt`, `ToString`, and Unicode `TextLowercase` by generic DSL
operation ID, with generated Rust process and runtime unit tests. This does
not alter the last *unchanged-application-source* 2/66 compatibility inventory;
legacy name-only substitutions are deliberately separate from the compiler
implementation, and connector/clock/fork-join readiness gates remain open.

**Byte/text increment:** the native compiler also supports the existing
generic Base64 encoder and UTF-8/US-ASCII text encode/decode functions.
Remaining IANA charsets fail closed. This closes common pure dependencies
of queue/storage helpers but does not make storage, Pub/Sub or child-workflow
connector effects runnable.

- Enumerate *actual instantiated* workflows and dependencies from `workflows`, `workflow-utils`, DSL and compiler: `CelFunc`, `CelOp`, raw `CelConst`, callbacks and all `Call` names; include names hidden inside helpers. Preserve original DSL; migrate constants in application sources **later** as a simple name-only operation.
- Select increasingly realistic unchanged workflows (queue helpers, dispatcher, task/callback workflows, then full `ProjectManager` dependency graph). Label each as compiles, executes purely, executes with mock effects, differential-conformant, or blocked, with the exact missing operation and source location.
- Build golden YAML-vs-native fixtures for result, exceptions, retries, null/missing/bytes, JSON encoding, integer edges, Unicode, clock/UUID, globals, and HTTP 4xx/5xx vs uncertain failure. Use deterministic mocked services and avoid duplicate live agent/LLM/tool side effects.

**Gate:** prioritized workflows have reproducible green/broken rows and fail-closed compiler diagnostics; backend drift has an explicit, reviewable exception list rather than being hidden by fallback.

### P2 — Complete durable effects and reliability (broader follow-up; not all pilot prerequisites)

- Implement per-operation policy for HTTP retry, idempotency, unknown outcome reconciliation, and recovery after worker deaths both before and after an external effect. Distinguish provider operation failure from infrastructure failure. Add live Firestore emulator / test-project contention, lease expiry/takeover, cancellation, and orphan sweeps.
- Implement approved `workflow.run` sync/async child behavior and `callback.create`/`callback.await` with atomic inbox and event de-duplication; integrate existing session history and cancellation semantics. Then add storage and Pub/Sub provider adapters with exact envelopes.
- Separate native executor into a restricted OS container/job with no mounted credentials, dedicated least-privilege identity, network policy, CPU/memory/process limits, artifact verification, and audited release handling.

**Gate:** crash-injection matrix and tenant isolation tests pass for every supported effect, including concurrent takeovers and actual remote uncertainty; no untrusted native process inherits server credentials.

### P3 — Durable `Parallel` and `ParallelFor` (compiler merged, server draft; historical specification)

- Keep original DSL nodes. Normalize them to compiler-private fork/join operations with stable branch/iteration identifiers and isolated frame slots.
- Store branch scheduling, cancellation, result/error states and join completion in server-owned durable records; use bounded concurrency rather than sequentializing or spawning unconstrained threads. Define legacy Google list ordering and shared-error semantics with differential fixtures.
- Verify duplicate delivery, repeated child branch launch, a crash before/after join, partial completion, sibling cancellation and large fan-out, without repeating committed effects.

**Updated scope:** real compiled nested parallel, branch records, journaled
effects, transactionally fenced ancestors, and replay are now verified in
the local worker and Firestore emulator. There is no plan to schedule each
branch as a separate Jobs worker, so a distributed permit manager is **not**
required for this milestone. Differential parallel semantics can be tested
when selecting a real parallel-bearing workflow; do not treat a cross-process
capacity system or orphan sweeper as the next implementation deliverable.

### P4 — Performance, canary and controlled rollout (now the immediate milestone)

- Benchmark warm/cold native startup separately from engine cost. Compare Google and Rust with equivalent durability, simulated effects and at least one agent workflow; report p50/p95/p99, operations, Firestore writes/bytes, CPU/RSS, cost and effect latency. The one 6,836 ms cold pilot is *not* a speedup claim.
- Validate GCS static-musl artifact publisher, pinned active manifests, authenticated `/workflows/execute`, session history, result/status/cancel, rollback of new starts and old-run continuation compatibility.
- Only then enable a feature-flagged, small IAM-approved native canary with explicit rollback, monitoring and retained Google fallback. Do not auto-migrate in-flight Google executions.

**Gate:** published runbook, measured baseline, demonstrated rollback, recoverable pinned artifacts and explicit sign-off on compatibility/security and operator ownership.

## Operational invariants

1. Users authenticate to Workflows; Jobs trusts only verified IAM/OIDC calls and server-carried tenant identity. The end user does not deploy binaries or invoke Jobs directly.
2. The server owns durable effect and result persistence. Rust performs pure CFG work, returning intents/continuations; no direct Google credentials or arbitrary network execution in the ELF.
3. Artifact digest, ABI, protocol and allowed operations are pinned per execution. No code change can reinterpret an active run's checkpoint with an incompatible artifact.
4. A prepared but **unknown** remote outcome is not silently retried or caught as a definitive error. Firestore epoch fencing is not remote exactly-once delivery.
5. The previous Google compiler and runtime stay available during a measured, reversible migration.

## Source documents

- [Implementation status](implementation-status.md)
- [Full implementation plan](native-rust-implementation-plan.md)
- [Compiler CFG design](native-control-flow-refactor-plan.md)
- [IPC ABI 3 / protocol 2](native-ipc-v2.md)
- [Server native execution guide](https://github.com/awfl-us/server/blob/main/workflows/native/README.md)
- [Workflows repository](https://github.com/awfl-us/workflows)


**Native clock and UUID increment (November 12, 2026):** Scala CEL
functions time.now() and uuid.generate() emit direct Rust calls using system
Unix seconds and random UUIDv4. Values are evaluated at each expression use;
repeated occurrences within a step may differ. Persisted step-result slots
continue to use the existing CFG continuation and server checkpoint, without
any new journal. TimeUuidSmoke and the native generated-executable test
verify the actual process output.


### Build footprint and workspace persistence (2026-11-13)

The Scala-to-native test harness shares one Cargo target directory, disables
unneeded dev/test debug info and incremental compilation, and reports disk
consumption by stage. See
[build-storage-optimization-2026-11-13.md](build-storage-optimization-2026-11-13.md).
Companion CLI PR #25 excludes previously persisted custom Cargo artifact
trees from subsequent workspace restores. This does not affect production
native artifact builds or workflow semantics.
