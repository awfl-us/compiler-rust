# Native execution: in-memory continuations, explicit caching, asynchronous progress

**Decision (November 12, 2026).** AWFL's `us.awfl.utils.Cache` already
implements opt-in Firebase result persistence around a user-selected step.
The engine must not impose an additional exactly-once journal around every
`http.*` or `log.write` effect. Workflow authors own idempotency and select
what to cache. On an environment crash, an uncached workflow starts over.

## Minimal execution contract

1. Authenticated Workflows API creates the existing execution record and
   dispatches one IAM-authenticated Cloud Task to Jobs. The record pins the
   signed artifact digest, identity, allowed effects, and **execution mode**.
2. Jobs claims **once** for exclusive ownership, loads the cached pinned
   executable, and repeatedly passes Rust continuation JSON in process memory.
   The existing Rust protocol stays unchanged. Intermediate values and HTTP
   outcomes are **not** committed by the executor.
3. Every external effect uses the trusted Jobs effect handler, scoped to the
   authenticated user and project. Explicit DSL `Cache` steps invoke their
   existing Firebase reads/writes as normal effects. Engine-controlled
   idempotency/reconciliation/effect journal are absent in this mode.
4. A tiny coalescing asynchronous progress writer publishes at most the latest
   continuation occasionally, **without awaiting it in the execution loop**.
   Progress is observational only and is never used as a recovery checkpoint.
   Writes must be fenced by the original worker epoch, serialized to avoid
   out-of-order snapshots, and drained or discarded before terminal commit.
5. Success/failure is persisted once and **awaited** before Jobs acknowledges
   the Task. Authenticated polling still reads the existing execution record.
   On worker crash or lease expiry, a new delivery starts from input rather
   than replaying partial progress. Uncached side effects may repeat.

## Implementation boundaries

- **Do not** add Redis, another Firestore collection, a background service,
  multi-phase recovery journal, custom Rust daemon, or new Terraform/IAM.
- Gate the new path behind `AWFL_NATIVE_MEMORY_EXECUTION_ENABLED=1` at the
  Workflows API. Pin `executionMode='memory'` on launch so a later config
  change cannot reinterpret an already enqueued execution.
- Initially use this path for single-lane protocol-v2 workflows. Parallel
  releases keep the old durable worker until fork/join has an equally small
  in-memory implementation; do not silently drop parallel behavior.
- Keep the old durable worker available for already-enqueued executions and
  rollback. Remove it only after real-cloud tests cover the new path.
- A single initial Firestore transaction fences duplicate Cloud Task
  deliveries. Cancellation is best effort while a worker is actively running;
  the final transaction must never overwrite a durable cancellation.
- No background work survives Cloud Run instance shutdown. The progress
  writer operates **only while the Jobs HTTP request is active**; the final
  terminal write is awaited. Partial snapshots do not guarantee recoverability.

## Acceptance and benchmarks

- Real compiled `helpers-ToolDefs` and a multi-effect workflow: correct
  results, ordered effect calls, **zero engine journal documents**, one
  initial claim and one terminal commit. Validate native compiled `Try` error
  edges, owner scoping, cancellation, duplicates, and failure/restart semantics.
- Writer tests: concurrent updates coalesce, at most one write is in flight,
  snapshots cannot overwrite later state, and terminal flush is awaited.
- Repeat isolated GCP canary (zero-traffic Jobs revision) with profiler and
  compare the recent 0.917s warm end-to-end / 517ms warm Jobs baselines.
  Record cold and warm separately; do not claim benchmark parity with Google
  Workflows unless both go through the same AWFL API entrypoint.
- Leave the stable native-routing flag disabled until this path passes its
  canaries. All infrastructure or IAM changes, if truly needed, belong in
  Terraform.
