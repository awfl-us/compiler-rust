# Native Rust implementation status — 2026-11-11

This branch is a **partial, experimental implementation**, not approval to
replace Google Workflows in production. The Scala frontend emits real Rust
statements; the server owns all effects, state transitions and persistence.

## November 11: deployed pure-workflow canary (verified)

- [`awfl-us/server` PR #72](https://github.com/awfl-us/server/pull/72)
  merged as `a797377` and deployed to Cloud Run (API `api-00137-hjd`,
  Jobs `jobs-00119-7gr`). The existing `createWorkflowExecution` launcher
  now has an **opt-in** GCS release lookup, Firestore execution store,
  Cloud Tasks enqueue, IAM/OIDC-protected Jobs worker, and per-execution
  status/cancel adapters. The API and normal Jobs revisions remain at
  `AWFL_NATIVE_EXECUTION_ENABLED=0`.
- [`awfl-us/workflows` PR #3](https://github.com/awfl-us/workflows/pull/3)
  merged the Rust 1.99.1 Alpine (`x86_64-unknown-linux-musl`) publisher.
  A first live Cloud Task reached the tagged Jobs worker but failed
  `spawn ... ENOENT`: the old artifact requested glibc's
  `/lib64/ld-linux-x86-64.so.2`, absent from `node:22-alpine`.
  That failed Cloud Task was deleted. A replacement static ELF built and
  executed in Alpine CI was checked for **no PT_INTERP**, uploaded to the
  dedicated native bucket, read back, and activated for `NativePilot`:
  SHA-256 `109b82a73175a46ada8b4c5f5059b724f40017a8321b2c76edec9e62396f984e`.
- **Live GCP canary succeeded:** an isolated `NativePilot` record was
  created in Firestore, a single Cloud Task was sent with OIDC to the
  **zero-traffic** `native-rust-pilot` Jobs revision (`jobs-00121-jaz`),
  and the real Rust binary completed with persisted result `[2,3,9,9]`.
  End-to-end single-sample latency was **6,836 ms** from enqueue to
  observation, not a p50/p95 production benchmark. Duplicate task delivery
  did not change the completed execution epoch or replay work. No HTTP or
  other external effects were attempted (`allowedOperations: []`).
- Reproducible test driver: `scripts/live_native_pilot.py` using the
  compiler-rust cloud environment's injected GCP token, `google-cloud-firestore`,
  `google-cloud-tasks` and `gcloud`. It performs a direct trusted canary
  setup; it is **not** a successful end-user-authenticated
  `/workflows/execute` smoke test. The tagged revision handles only explicit
  canary requests; normal Jobs traffic remains at 100% on the disabled
  revision.
- **Publisher automation caveat:** the attempted manual GitHub Actions
  dispatch was denied with HTTP 403 for the GitHub App installation.
  The validated CI artifact was instead published using the existing
  injected GCP IAM identity. The manual `workflow_dispatch` permission or
  owner-run publication path needs verification; do not claim that the
  GitHub App can dispatch the workflow yet.

This proves the pure execution plumbing and duplicate completion fencing,
**not** general native workflow production readiness or user-facing parity.

## Phase 0 — incomplete

- Pinned source inventory in `phase0-source-inventory.json`; its static
  literal scan found no unregistered call/function spellings.
- Still required: constructed-AST coverage, Google differential fixtures,
  error/retry semantics and representative latency baselines.

## Phase 1 — typed operation registry (companion draft PRs)

- [dsl PR #2](https://github.com/awfl-us/dsl/pull/2) adds canonical call,
  function, operator, global and retry identifiers.
- [compiler-yaml PR #1](https://github.com/awfl-us/compiler-yaml/pull/1)
  preserves existing Google/YAML compilation for typed expressions.
- The typed DSL snapshot is temporarily published locally for the Rust
  frontend; coordinated publication and application migration are pending.

## Phase 2 — Scala frontend and generated Rust

- `frontend/` contains the Scala 3 module, `Normalize` for typed IR,
  structural node IDs, deterministic slot names and direct `RustEmitter`
  source generation (no graph interpreter and no YAML as input).
- Pure native execution supports `Switch`, `ForRange`, `For`, sequential
  `Fold`, nested pure control flow, typed arithmetic/comparison/boolean
  operations, literal lists/maps, input and selected value paths.
- **New:** Explicit DSL `Return` exits from nested branches and top-level
  workflows through an `EarlyReturn` IR instruction.
- **New:** Top-level typed `Call` instructions compile into *server-managed*
  effect suspensions; the Rust process does not perform the HTTP request.
  The first protocol version records `effect_id`, structural `node_id`,
  canonical operation, result slot, arguments and tagged continuation slots.
  The binary can resume with a recorded result and skip earlier top-level
  computation. Separate top-level effects form a resumable sequence.
- **Compatibility:** Pure binaries retain the legacy plain-JSON ABI
  (`runtime_abi=1`). Effectful binaries require versioned JSON IPC
  (`runtime_abi=2`, protocol version 1). Both are opt-in. Manifest lists
  structural node IDs, effect operations and generated-source SHA-256.
- **Limited nested effect slice:** A top-level ForRange with one or more
  direct Calls in its body suspends and resumes at each call site. Checkpoints
  preserve the completed iteration prefix and previously recorded Call
  results in the unfinished iteration. A range frame records the zero-based
  iteration; a second frame identifies the direct Call site when more than
  one exists. Dynamic effect IDs include the top-level step, result slot
  and iteration ordinal. Each completed effect is skipped after recovery.
- **Top-level Switch:** One or more direct Calls within a selected branch
  use two frames to pin the branch and Call site. Previously completed Calls
  are restored without re-evaluating branch conditions after restart.
  Pure and unmatched branches still return without effects.
- **Nested Switch slice:** One inner Switch in an effectful outer branch is
  supported. Three validated invocation frames pin both chosen branches
  and the exact direct Call site. Results from preceding Calls are restored
  after worker takeover without re-evaluating conditions.
- **Nested ForRange slice:** A top-level range containing one inner range
  with direct Calls can suspend and resume at each inner Call. Three frames
  pin outer iteration, inner iteration and Call site; two independent tagged
  output prefixes restore both loops without replaying completed effects.
- **New opt-in generalized backend:** `ControlFlowLowering` builds one typed,
  deterministic CFG for nested branches, `ForRange`, `For`, `Fold` and `Return`.
  `ContinuationAnalysis` computes live slots over loop backedges. The new
  `StateMachineEmitter` (manifest `runtime_abi: 3`, IPC `protocol_version: 2`)
  suspends with the *successor CFG block* as its continuation, not a
  replayable lexical branch/loop frame. It handles sequential dependent
  effects inside `For` and `Fold` and mixed range/branch nesting. The old
  ABI 1/2 emitters remain the default; callers must explicitly opt into
  `RustEmitter.emitV2` or `writeV2`.
- **Legacy ABI 2 limitations:** Calls beyond two Switch/ForRange levels,
  mixed nested branches/loops, effectful For/Fold and mixed pure/effect
  steps in a branch still fail closed. These combinations are covered by
  the new experimental CFG backend instead.
- **Still unsupported in either backend:** `Try`/`Parallel`, complete CEL,
  Google retry/error parity and YAML fallback.
- `NativeCompiler.compile` consumes constructed DSL workflows.
  `NativeMain` reuses `awfl-us/compiler.WorkflowLoader` to load existing
  Scala objects. Integration with `compiler --target rust` is pending.
- Verified locally in the AWFL Scala cloud workspace:
  - All three Scala frontend/loader smoke applications passed.
  - Eleven Rust runtime unit tests passed, including strict v1/v2
    continuation-version separation.
  - Pure Rust smoke executable passed three inputs.
  - Eight pure control-flow/early-return fixtures passed sixteen assertions.
  - Actual generated native effect binaries passed suspend/resume,
    two sequential effects, continuation restoration, missing-slot rejection,
    malformed PC rejection and protocol-version rejection. A generated
    three-iteration range binary passed repeated suspend/resume with
    preserved results and malformed prefix/frame rejection. A second
    generated binary passed two dependent Calls per iteration, verifying
    four unique invocation IDs, saved intra-iteration results, and
    malformed site/missing-slot rejection. The new two-branch Switch fixture
    passed branch pinning, dependent Calls, alternate and unmatched branches
    and malformed continuation site/missing-slot rejection. A two-level
    nested Switch fixture passed pinned branches, dependent Calls, alternate
    and no-effect paths, malformed frames and missing-slot rejection.
    A two-level nested range fixture passed eight dependent Call
    suspensions, both output prefix recoveries and malformed checkpoint
    rejection.
  - Rustfmt and strict Clippy checks apply to the Rust crate and generated
    fixtures in CI.
  - The opt-in ABI 3 protocol suite compiles real Rust fixtures and checks
    effectful `For`/`Fold`, mixed `ForRange`/`Switch`, nested effectful
    ranges/branches, distinct idempotency IDs, corrupt continuations and
    resumption without replaying earlier choices. The dedicated CFG and
    liveness Scala smoke test checks deterministic labels and loop backedges.

## Phase 3 — experimental server integration, still opt-in

- [server PR #72](https://github.com/awfl-us/server/pull/72) hosts the native
  execution prototype on branch `feat/native-execution-store`.
- `NativeExecutionStore` uses Firestore transactions for state creation,
  lease claims, epoch fencing, renewal, cancellation, prepared effects,
  checkpoint updates and completion. Reentrant claims by the current fenced
  worker permit multi-effect continuation without lease expiry.
- **New:** Each recorded effect outcome is stored in an
  `executions/{id}/effects/{effectId}`-style subcollection document in the
  **same transaction** that updates its result slot and clears the prepared
  intent. The root document has a scalar `effectCount`, not an unbounded
  map. Input, checkpoint, effect-argument, outcome and result size caps
  fail closed before Firestore document limits are reached. This is not an
  infinite-payload store.
- **New:** `executeNativeStep` verifies the pinned executable digest,
  launches the versioned IPC subprocess, persists suspension, and completes
  the execution after recorded effects. `NativeEffectDispatcher` only calls
  explicitly injected, allowlisted operation handlers and uses a stable
  per-execution/per-effect idempotency key. An aged unresolved intent requires
  operation-specific reconciliation after takeover; blind retries are blocked.
- **New opt-in v2 route:** The store pins `protocolVersion` at execution
  creation (default 1). The server checks the corresponding native request
  and response version, accepts a frame-free v2 continuation at CFG PC zero,
  and rejects effect intents using another protocol. The Firestore emulator
  verified a v2 compiled nested-range workflow with eight journaled effects
  and worker takeover.
- **Verified using a real local Firestore emulator**, not just fake
  transactions: 12 concurrent workers racing to claim, takeover fencing,
  cancellation versus recording, process/store reconstruction around
  pending intents, uncertain outcome reconciliation, a generated Rust
  suspension with recorded server outcome, and a two-effect Rust sequence.
  All fourteen emulator integration tests passed, including compiled Rust
  ranges and Switches resuming after worker takeovers. The nested range test
  survives two worker replacements and journals eight effects exactly once.
  A separate regression proves arbitrary nested JSON inputs, arguments,
  outcomes and final results persist and hydrate correctly. The entire
  Node native suite passed 28/28 with all real-binary integrations
  enabled, including the new ABI 3 CFG recovery and protocol-pinning
  checks; two additional
  local tests verify subcollection scaling and persistence-size rejection.
- The **pure-path** native worker, shared launcher and IAM-backed GCS
  release selection are deployed but disabled for general traffic. Neither a
  restricted OS-level execution sandbox nor a live general-purpose external
  effect adapter exists. Historical emulator fixture counts above refer to
  local tests, **not** to live Cloud Run effect execution.

## Remaining gates before a broader pilot

1. Exercise the **existing authenticated** public `/workflows/execute`
   launcher end to end, including session registration, status/history,
   cancellation, agent/MCP surfaces and Google fallback behavior; today's
   successful canary injected the Firestore execution and Cloud Task directly.
2. Add crash-window and retry coverage with recoverable error states,
   lease heartbeats, uncertain-effect reconciliation and orphan sweeping.
   Prevent noisy 503 retries while another worker holds a valid lease.
3. Validate OS-level confinement for downloaded executables, production
   artifact approval/provenance, real allowlisted HTTP effect handlers,
   per-operation idempotency and trust boundaries under adversarial inputs.
4. Complete `Try`, `Parallel`, callbacks/children, CEL, Google
   error/retry semantics, and representative workflow dependency closures.
5. Verify automated **IAM-authorized** publishing and rollback without a
   GitHub App workflow-dispatch permission gap; run differential tests and
   measured p50/p95/p99 performance under representative load.

The ABI 3 generalized CFG compiler already covers the tested effectful For,
Fold, Switch and nested-range subset; this does not imply full DSL parity.

See `frontend/README.md`, `docs/native-ipc-v1.md`, and
`server/workflows/native/README.md` in [server PR #72](https://github.com/awfl-us/server/pull/72).
