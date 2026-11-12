# Native durable Parallel / ParallelFor — implementation design

Status (2026-11-12): native `Parallel` and `ParallelFor` now lower into independently addressable, compiled Rust branch entries using ABI 3/protocol v2 and a version-pinned parallel capability. The paired server draft implements durable child records, an effect-journaled parent fork, bounded execution, and join. Generated ELF + Jobs worker integration tests cover overlapping effects, ordered joins, replay, a crash between child completion and parent journal, and caught/uncaught child `Raise`. These currently run against a transaction-shaped in-memory Firestore substitute, **not the real Firestore emulator or deployed Jobs path**. Production rollout remains disabled while emulator, cancellation, nested fanout budgeting, and broader workflow compatibility are completed.

## Baseline and semantic contract

The Scala DSL already provides `Parallel(name, branches)` and `ParallelFor(name, in)(each)`; no DSL AST change is needed. YAML uses Google Workflows `parallel`/`parallel.for`: `Parallel` declares each branch's top-level result as a shared value, while `ParallelFor` shares a list and concatenates iteration results. Each branch can contain nested `Try`, `Switch`, loops and effects. Some production workflow branches are child-workflow invocations, and `ParallelFor` bodies contain several effects and nested constructs. **Sequentially translating these constructs would be an incorrect optimization.**

Native semantics required:

1. All sibling branches have a common immutable parent snapshot and independent mutable slot maps, stable structural lane IDs, and separate PCs/checkpoints. They may not read sibling results before a join. No Scala name uniqueness restriction. Merge only declared top-level branch results; reject ambiguous shared writes rather than racing them.
2. An individual branch executes ordinary native instructions until completion or a durable external effect suspension. The existing authenticated Jobs/Firestore journal is the only effect authority; no network calls or credentials inside generated Rust.
3. At a fork, compute a deterministic generation key `(rootExecutionId, nodeId, ancestorLoopIndices, invocationOrdinal)`; for `ParallelFor`, derive one lane per **input index**, not input element hash. Snapshot the input list once; do not re-evaluate `time.now`/`uuid.generate` after a fork retry.
4. Persist branch record creation, pinned artifact SHA/capability, immutable captured input and slots before scheduling any branch. Duplicate task dispatch and lease takeover find the same records. Each child has a unique fenced lease and uses the same result and journal transitions as current native executions.
5. Run a bounded number of ready branches concurrently (server-side cap, initial 8, configurable). Do not create one Cloud Task per CEL instruction. Nested fanout is subject to a *global* active-branch budget to prevent combinatorial overload.
6. Parent remains parked at a compiled `Join` PC until every branch is durably terminal. Join is idempotent, merges top-level declared `Parallel` results using compiler-generated slot mapping, or assembles a `ParallelFor` array by **input index** (deterministic result order; compare this deliberate improvement with the YAML backend's append completion order).
7. A branch error is persisted with its identity. Preserve the compiled `Try` exception route for confirmed failures; never convert unknown/uncertain remote outcomes into catchable errors. Cancellation and timeout must prevent new work and avoid implicitly duplicating an in-flight effect. Define the full sibling-cancellation policy explicitly against YAML before release.
8. Existing ABI-3/protocol-v2 binaries/active releases must not receive new envelope fields. Add a manifest-declared, release-pinned parallel capability, with opt-in request/response schema, before activating this feature. Retain normal `AWFL_NATIVE_EXECUTION_ENABLED=0` rollout gating.

## Compiler implementation

- Normalize `Parallel` into a structural fork/join instruction with a vector of statically compiled branch bodies, each with explicit result-slot ownership. Normalize each branch against the **same parent symbol snapshot**; merge only the branch's exported result symbol, preserving lexical shadowing.
- Normalize `ParallelFor` into an iterable expression, one scoped iterator slot, a statically compiled body, and result expression. `input` list is evaluated/persisted once, not per restart.
- Lower the fork and each body to independently addressable CFG entrypoints. Stable source-node/branch/index identities are independent of user labels. Child-PC and slot liveness follow the same `ContinuationAnalysis`; keep the parent continuation parked at the join.
- Generated Rust must expose a statically dispatched branch entrypoint accepting a trusted selector, captured parent slots and branch iterator value. Unknown selectors/slot shapes are rejected. The parent binary returns a bounded fork declaration (branch descriptors + continuation), not a hidden sequential loop.
- Extend the versioned compiler artifact manifest with its required native parallel capability so older binaries remain untouched.

## Server implementation

- Extend existing native execution storage with parent/lane relationships and immutable lane identity. Uniquely index/store lane progress, checkpoint, journal and terminal result. Reuse owner, artifact digest, effect authorization, lease fencing and idempotency logic. Parent `Fork` transitions must atomically persist a recoverable fork intent before any children can start.
- Add a recovery-safe bounded scheduler to the Jobs worker. Fan out children, dispatch normal native effects, and reconcile terminal child state on parent resume; avoid holding a Firestore transaction across network calls. Never treat an uncertain child effect as a retryable failure.
- Join is a fenced Firestore transition. Replays observe the already-completed join and do not double-append results or redispatch branches. Cancellation and execution GC cover child records.
- Trust the parent owner/project/record, not task request fields or caller input. Keep `WORKFLOW_EXECUTION_ID` consistent with the parent workflow's execution identity in child contexts.

## Acceptance tests (must pass before enablement)

1. `Parallel` with two pure branches and identical DSL step names: unique compiler slots; both results visible after join. Repeat run yields deterministic artifact and results.
2. `ParallelFor` empty, one and many elements; nested loop, `Switch`, `Try`, `Raise`; list results stable by original input index even with reverse completion order.
3. Two external effects blocked on a test barrier genuinely overlap before releasing it (proves concurrency instead of serialization), respecting max fanout.
4. Suspend/restart between fork record and scheduling, effect intent and dispatch, dispatch and journal write, branch completion and parent join; duplicate jobs, owner mismatch and lease takeover cannot rerun an already journaled effect.
5. One confirmed child failure within outer `Try`, uncertain remote outcome, cancellation and timeout: correct propagation, no silent retry of unknown remote outcomes.
6. Exact versioned JSON and SHA-pinned ELF integration on the restricted runner; old artifacts still read old IPC; no subprocess credentials.
7. Differential tests with YAML for both constructs, zero unknown operation IDs; rerun all 66 constructed-AST workflow audits and build/execute representative real workflows. Do not report `emitted` as proof of deployed runtime parity.

## Sequencing / checkpoints

A. Repair unbound raw helper references at the source (schema examples and Firebase cache's 404 recovery), rerun the baseline audit without changing compiler raw-CEL validation.

B. Add compiler-private parallel IR, scoped CFG branches and exact resume-slot/lane identity tests; keep Rust emission gated until protocol support exists.

C. Implement versioned fork/lane runtime envelope, GCP persistence and concurrency scheduler. Integrate compiled branch execution and authorized effect dispatch.

D. Prove crash/retry/overlap/semantics with actual native executables (done with a transaction-shaped test store); additionally run real Firestore emulator, cancellation, uncertain-outcome, full nested-workflow and deployed Jobs tests. Keep production path disabled until the full rollout gates pass.
