# Native Rust implementation status — 2026-11-10

This branch is a **partial, experimental implementation**, not approval to
replace Google Workflows in production. The Scala frontend emits real Rust
statements; the server owns all effects, state transitions and persistence.

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
- **Still unsupported:** Calls beyond two Switch or ForRange levels, mixed
  nested branch/loop bodies, effectful For/Fold, Try and Parallel. Mixed pure
  steps and
  Calls in one effectful Switch branch fail closed. Complete CEL, Google
  retry/error semantics and YAML fallback remain pending.
- `NativeCompiler.compile` consumes constructed DSL workflows.
  `NativeMain` reuses `awfl-us/compiler.WorkflowLoader` to load existing
  Scala objects. Integration with `compiler --target rust` is pending.
- Verified locally in the AWFL Scala cloud workspace:
  - All three Scala frontend/loader smoke applications passed.
  - Ten Rust runtime unit tests passed.
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
- **Verified using a real local Firestore emulator**, not just fake
  transactions: 12 concurrent workers racing to claim, takeover fencing,
  cancellation versus recording, process/store reconstruction around
  pending intents, uncertain outcome reconciliation, a generated Rust
  suspension with recorded server outcome, and a two-effect Rust sequence.
  All twelve emulator integration tests passed, including compiled Rust
  ranges and Switches resuming after worker takeovers. The nested range test
  survives two worker replacements and journals eight effects exactly once.
  A separate regression proves arbitrary nested JSON inputs, arguments,
  outcomes and final results persist and hydrate correctly. The entire
  Node native suite passed 26/26 with all real-binary integrations
  enabled; two additional
  local tests verify subcollection scaling and persistence-size rejection.
- This is **not** wired to production `createWorkflowExecution`, cloud
  consumers, APIs, or agent session routing. There is no deployed native
  worker, executable sandbox, trusted artifact registry, secure real HTTP
  adapter, production credential policy, or workload-wide parity claim.

## Remaining gates before a pilot

1. Extend the bounded two-level Switch/ForRange frames to arbitrary nested
   branches and loops, effectful For and Fold, with precise live-slot
   capture and branch-safe replay.
2. Test every crash window (prepare, dispatch, unknown remote outcome,
   result commit, post-commit resume), transaction contention at realistic
   scale, lease heartbeats and lifecycle sweeping.
3. Introduce trusted manifest/artifact registration, code-signing or
   equivalent verification, process isolation, real authenticated/allowlisted
   handlers and explicit remote idempotency/reconciliation contracts.
4. Wire opt-in native routing **into the existing shared AWFL launcher and
   status/callback flow**; keep the Google backend as default.
5. Complete `Try`, `Parallel`, CEL and retry semantics with comparative
   Google Workflows differential cases, then benchmark actual p50/p95/p99
   workloads under realistic load.

See `frontend/README.md`, `docs/native-ipc-v1.md`, and
`server/workflows/native/README.md` in [server PR #72](https://github.com/awfl-us/server/pull/72).
