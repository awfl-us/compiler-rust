# Native durable Parallel / ParallelFor — implementation design

**Scope decision, November 12 (supersedes any exploratory requirements below):**
Fork/join is sufficiently implemented for the current **one Jobs worker per
workflow** model. Keep the shared in-process eight-permit budget and durable
child execution, journal and lease/fencing semantics. A temporary Firestore
cross-process permit layer was reverted from the paired server branch.
Distributed branch scheduling, global fleet quotas and orphan sweeps are
**not** prerequisites to the next milestone. This design records the
implementation contract and historical test requirements, **not** a request
to build additional parallel infrastructure. Next deliverable: the
[real-workflow pilot and Google benchmark plan](real-workflow-pilot-and-benchmark-plan.md).

**Latest verified branch evidence:** real generated three-level nested Rust
forks and transactionally checked ancestor leases pass; after simplification,
the server's local native suite passed 68/97 tests (29 intentionally skipped
without optional fixtures), and the actual Firestore emulator passed **7/7**.
An unchanged `LoadConversationHistory` executable also completed in the
native worker with mocked HTTP, a committed effect and idempotent replay.
Neither a live parallel cloud execution nor a Google `parallel` differential
has been verified. Production native routing remains disabled.

Status (2026-11-12): native `Parallel` and `ParallelFor` now lower into independently addressable, compiled Rust branch entries using ABI 3/protocol v2 and a version-pinned parallel capability. The paired server draft implements durable child records, an effect-journaled parent fork, bounded execution, and join. Generated ELF + Jobs worker integration tests cover overlapping effects, ordered joins, replay, a crash between child completion and parent journal, and caught/uncaught child `Raise`. **Four additional tests now pass against the actual local Firestore emulator**: concurrent compiled-ELF children, duplicate-parent-lease rejection and idempotent replay through a fresh store; recovery after child commit and parent interruption through a fresh store; zero/one/many indexed `ParallelFor` lanes; and cancellation fencing of a parent join and subsequent redelivery. This is a *single local emulator process*, **not** deployed Jobs, independent worker processes, or a full sibling-cancellation solution. Production routing remains disabled while wider cancellation, nested fanout budgeting, and workflow compatibility are completed.

## Baseline and semantic contract

The Scala DSL already provides `Parallel(name, branches)` and `ParallelFor(name, in)(each)`; no DSL AST change is needed. YAML uses Google Workflows `parallel`/`parallel.for`: `Parallel` declares each branch's top-level result as a shared value, while `ParallelFor` shares a list and concatenates iteration results. Each branch can contain nested `Try`, `Switch`, loops and effects. Some production workflow branches are child-workflow invocations, and `ParallelFor` bodies contain several effects and nested constructs. **Sequentially translating these constructs would be an incorrect optimization.**

Native semantics required:

1. All sibling branches have a common immutable parent snapshot and independent mutable slot maps, stable structural lane IDs, and separate PCs/checkpoints. They may not read sibling results before a join. No Scala name uniqueness restriction. Merge only declared top-level branch results; reject ambiguous shared writes rather than racing them.
2. An individual branch executes ordinary native instructions until completion or a durable external effect suspension. The existing authenticated Jobs/Firestore journal is the only effect authority; no network calls or credentials inside generated Rust.
3. At a fork, compute a deterministic generation key `(rootExecutionId, nodeId, ancestorLoopIndices, invocationOrdinal)`; for `ParallelFor`, derive one lane per **input index**, not input element hash. Snapshot the input list once; do not re-evaluate `time.now`/`uuid.generate` after a fork retry.
4. Persist branch record creation, pinned artifact SHA/capability, immutable captured input and slots before scheduling any branch. Duplicate task dispatch and lease takeover find the same records. Each child has a unique fenced lease and uses the same result and journal transitions as current native executions.
5. Run a bounded number of ready branches concurrently in the **same worker**
   (shared process-local cap, initially 8). Nested forks share that local
   budget; waiting parent coordinators do not consume permits. Do not create
   one Cloud Task per CEL instruction. A cross-process quota is needed only
   if the execution model actually distributes branches over Jobs workers.
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
- Join is a fenced Firestore transition. Replays observe the already-completed
  join and do not double-append results or redispatch branches. Parent and
  ancestor fencing prevent stale descendant commits. Automatic recursive
  child-record GC is **not implemented** and is deferred rather than being
  represented as a completed capability or a blocker to the real-workflow
  pilot.
- Trust the parent owner/project/record, not task request fields or caller input. Keep `WORKFLOW_EXECUTION_ID` consistent with the parent workflow's execution identity in child contexts.

## Acceptance tests (historical parallel-specific production inventory)

These tests describe eventual generalized parallel semantics; they are not
all required to begin the **isolated, nonparallel real-workflow pilot**.
Completed local Rust/Firestore tests remain regression gates; unimplemented
Google differential or cleanup policy is tracked when a parallel-bearing
real workflow is actually selected. For immediate acceptance, follow
[the current pilot plan](real-workflow-pilot-and-benchmark-plan.md).

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

D. Prove crash/retry/overlap/semantics with actual native executables (done with both a transaction-shaped test store and an actual local Firestore emulator for the four named scenarios); additionally test cross-process contender/takeover, cancellation propagation to descendants, uncertain outcome, nested-workflow semantics and deployed Jobs. Keep production path disabled until the full rollout gates pass.

### Emulator reproduction and scope (November 12)

With the server branch checked out, Node dependencies installed, the matching
compiler #18 branch and its generated `native-parallel-branches` executable
built, run the CI `Real Firestore emulator native Parallel fork, retry and
cancellation` step. It launches `firebase-tools@13.35.1 emulators:exec` against
`workflows/native/firebase.parallel-emulator.json`, using the demo-only
`demo-awfl-native` project. `AWFL_NATIVE_PARALLEL_BRANCHES_BINARY`
and `AWFL_NATIVE_PARALLEL_FOR_BINARY` point to the generated ELFs; `AWFL_NATIVE_FIRESTORE_PARALLEL_REQUIRED=1` ensures no
silently skipped tests. The test uses the real Firestore client, transactions,
lease epochs, native worker, artifact SHA-256 and compiled executable; its HTTP
handlers are deterministic mocks (no production network effects).

**Latest simplified branch:** **7 pass, 0 fail, 0 skip** including actual
three-level generated Rust forks, a killed independent Node worker and
epoch-fenced recovery, and a nonterminal root invalidating old descendants.
The original four-test description above is historical. The cancelled parent
stays terminal even when previously dispatched handlers settle. This does
**not** undo external calls already in flight or guarantee recursive child
record cleanup. Revisit those cases when real-workflow testing shows a need;
**do not** implement distributed admission merely for this pilot. Compare
actual parallel behavior with Google if and when a parallel-bearing real
workflow becomes the selected differential test.
