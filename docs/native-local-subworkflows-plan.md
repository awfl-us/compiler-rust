# Fast local subworkflow calls — implementation plan (November 2026)

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
