# Fast local subworkflow calls — implementation plan (November 2026)


## Current integration status — November 13 (later checkpoint)

[Server #97](https://github.com/awfl-us/server/pull/97) has merged
the durable native parallel-parent and pinned synchronous nonparallel
workflow.run child support. The earlier #94 local runner remains the
memory-first path. [Server #98](https://github.com/awfl-us/server/pull/98)
is open with native callbacks using the existing AWFL callback service;
the current await retains a Jobs request for up to 25 minutes rather
than implementing a durable event wake-up.

Compiler #31 and Workflows #13 have merged, providing one-command build
and publication for the 26 ProjectManager native workflows. **Full real
native ProjectManager completion is pending**: the first canary reached
six real effects before a stale Jobs worker rejected an operation during
its fork. Refer to the [current ProjectManager action plan](projectmanager-native-integration-plan-2026-11-13.md). The rest of this
document is preserved as dated design and benchmark evidence, not the
latest deployment checklist.


## Implemented and verified — November 13, 2026 UTC

Server [#94](https://github.com/awfl-us/server/pull/94) implements the
shared `workflow.run` memory executor, and compiler-rust
[#26](https://github.com/awfl-us/compiler-rust/pull/26) supplies real Scala
parent, child, leaf, and child-HTTP compilation fixtures. These have been
merged. **No new Terraform, IAM, GKE workload, or Cloud Tasks queue**.

Live Cloud Run Jobs test: isolated `jobs-00157-qib` revision, 0% normal
traffic, published SHA-256-pinned musl Rust executables for `LocalParent` →
20 `LocalChild` calls → 20 `LocalLeaf` calls. All **five/five** executions
returned `[3, 4, ..., 22]`, and each had **40 native `workflow.run` effects,
only one top-level Cloud Task and Firestore execution, zero child records,
and zero engine effect journals**. The first test ELF used glibc and could
not launch on Cloud Run (`spawn ... ENOENT`); recompiling the same Scala
fixtures for `x86_64-unknown-linux-musl` resolved it. The failed initial
probe was terminal; no effect ran or replayed.

| Cloud Run test | Client wall time (create Firestore record + enqueue Task → terminal) |
| --- | ---: |
| First successful static artifact run | 3.222s |
| Warm run 1 | 3.535s |
| Warm run 2 | 3.254s |
| Warm run 3 | 3.446s |
| Warm run 4 | 3.992s |
| **Warm median** | **3.491s** |

The warm **Jobs request** median was **2.891s**, from tagged Cloud Run
structured profiler logs. This particular workflow has **81 separate Rust
continuation subprocess invocations**, consuming median **2.417s** across
`runner.transition` spans, with median **0.959s** specifically in
`rust.spawn_io`. These spans include nested parent/child dispatch and should
not be naively summed. Warm GCS materialization totaled **~19ms** for
three distinct cached artifacts; the two required Firestore transactions
(`claim` and `complete`) together took **~223ms**. No additional child
network requests or Firestore writes occurred.

The same compiled tree ran in **~1.44s** inside the Scala GKE consumer's
local environment, highlighting environment/process overhead differences.
Neither figure compares the *same workflow* on Google Workflows or a
distributed-per-child native implementation; there is no defensible
relative-speedup multiplier. This is five sequential executions, not p95,
concurrency, or cost benchmarking.

**Next highest-leverage work:** reduce Rust process invocation / JSON
continuation overhead within the existing Jobs service for nested pure
workflows. A persistent Rust process or direct in-process compiled module
would help if it stays simple; benchmark an isolated prototype first. GKE
colocation does not remove that overhead and is not justified yet. Keep
normal production native routing disabled until migration and broader tests.

---

## Decision

AWFL frequently decomposes logic into many subworkflows (`Chain`,
`ToolDispatcher`, tool helpers, etc.). Subworkflows are ordinary synchronous
function calls **within one top-level execution**, not new independently
scheduled distributed executions. A Cloud Task and Firestore execution record
per child would defeat the point of native compilation.

**First implement local `CallOperations.WorkflowRun` in the existing warm
native Jobs service.** The API still authenticates and schedules the *root*
workflow as before. The native worker must resolve a child through the same
IAM-published GCS release registry, run its SHA-256-verified Rust binary in
the same Jobs request, return the result to the parent continuation, and
retain parent user/project identity and effect handlers. Do not add GKE
services, Redis, a scheduler, Firestore child documents, or new Terraform/IAM.

## Scope / semantics

1. Accept the existing `RunWorkflowArgs` contract: `workflow_id`, `argument`,
   optional `location`, `project_id`, and `connector_params`. Never trust
   caller-supplied tenant or GCP identity to override the authenticated root.
   Explicit `skip_polling=true` means detached execution: **reject it for
   local synchronous calls** rather than silently changing semantics.
2. Resolve the named, **published** child release (protocol v2 / ABI3,
   `parallelVersion` absent) using the server's GCP IAM. If missing, fail
   explicitly. Never run an arbitrary path or silently fall back to Google
   Workflows. Validate the child's own `allowedOperations` at every effect.
3. Invoke the existing Rust continuation protocol locally. A nested child
   may invoke another child. Use a small shared call stack and total-effect
   budget; avoid infinite recursion. All child errors propagate to the
   parent `Try` edge if one is compiled, otherwise fail the root execution.
4. Keep the pinned root artifact, owner, final Firestore write, and optional
   async progress behavior unchanged. **No Firestore or Cloud Tasks per
   child.** `Cache` remains an explicitly chosen workflow operation.
5. The first implementation is **synchronous, nonparallel**. Keep parallel
   or detached workflows on their existing distributed path; do not pretend
   they can run locally without testing fork/join semantics.
6. Reuse the per-instance verified-binary cache. Initially keep per-child
   OS processes and the existing Rust protocol: this is simpler and easier
   to test, and profiling shows each transition is inexpensive. If local
   process launches dominate large nested workloads, then consider a
   persistent worker or native library invocation as a separate benchmarked
   change.

## Performance validation and deployment decision

Benchmark a published parent calling a small compiled child repeatedly,
both with pure child computation and with child HTTP effects. Record
child-call median/p95 and root end-to-end timings, plus Firestore/Task counts;
verify one top-level Cloud Task, one root execution record, and zero child
records/journals. Compare with the previous ~0.785s warm native root
baseline, noting different workflows and the small sample size.

Only after measuring the in-process path should we decide whether to run
the executor in the existing `awfl-gke` cluster alongside its consumers.
Cloud Run and GKE reside in `us-central1`; moving services into one cluster
does not itself remove distributed per-child scheduling. A persistent GKE
pool could remove cold starts but would introduce operational overhead.
Keep normal Google-backed routing and stable Jobs traffic unchanged until
canary tests pass. Any future infrastructure/IAM changes must be Terraform.
