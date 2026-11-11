# Native Rust compiler architecture — single CFG backend

Status: implemented for the tested subset (November 11, 2026).

There is **one** native backend. `Normalize` traverses constructed Scala AWFL
DSL objects, translating existing `CelFunc`, `CelOp` and `Call` nodes into compiler-internal expressions/instructions with
stable structural node/slot IDs. `ControlFlowLowering` builds a deterministic
CFG for `Switch`, `ForRange`, `For`, sequential `Fold`, `Try`, `Raise`, `Call`, `Return` and
terminal results. `Plan.compact` prunes dead blocks after early returns;
`Plan.validate` rejects inconsistent labels, missing exits and unreachable
blocks. The executable contains statically generated Rust CFG match arms,
not a graph interpreter.

`ContinuationAnalysis` calculates live input/output sets by fixed-point
backward analysis over all successors, including loop backedges. On an
effectful `Invoke`, generated Rust returns its canonical operation, arguments,
unique structural/loop-ordinal invocation ID and a successor program counter
with only its live tagged slots. The server owns external authorization,
effect idempotency, checkpoint/result persistence and recovery; resumed Rust
runs from the saved CFG block, without replaying completed effects.

`StateMachineEmitter` emits all pure and effectful code. `ArtifactWriter`
produces a reproducible Cargo project and SHA-256-pinned manifest with
**runtime ABI 3**. `runtime/src/protocol.rs` implements the **only** wire
version, `protocol_version: 2`, and rejects any other version, invalid CFG
counter, non-empty replay frames or invalid tagged slots. These frames remain
an empty field solely to match the existing server IPC schema. There are no
alternate emitters or compatibility switches.

### Verification

- Scala frontend/loader, CFG, liveness and early-return smoke fixtures.
- Actual compiled Rust pure/loop execution and repeated effect suspension
  across fresh processes, including nested Switch and For/Fold.
- Negative checks: wrong protocol number, missing slots, invalid PC, nonempty
  frames, and duplicate/unstable effect IDs.
- `TryRaiseSmoke` emits seven real Rust executables: success, caught and
  uncaught raise, nested rethrow, runtime CEL error, HTTP failure catch, and
  the original DSL default-`Try` logger. The latter suspends on `log.write`
  and resumes to an uncaught terminal `Failed` after a journaled null result.
- The full local `scripts/test-local.sh --full` passed in the AWFL Scala
  consumer (November 11, 2026). A separate Node server test with this Rust
  binary and a Firestore-compatible transaction store passed **34 of 37**
  tests (3 optional other-binary tests skipped): including tenant fencing,
  terminal errors and a simulated crash **after** an effect journal commit.
- The GCP pure canary (Cloud Tasks → Jobs → Firestore) completed and was
  duplicate-delivery fenced. It is not proof of external effect parity.

### Remaining compiler work

- `Try`/`Raise` is implemented and tested for the current subset, including
  compiled error edges and confirmed HTTP failure. Complete Google error
  taxonomy, retry semantics and uncertain remote outcome reconciliation.
- Add compiler-private lowering and durable server scheduling for `Parallel`,
  `ParallelFor`, callbacks and child workflows. **Do not** add public DSL
  variants or silently execute declared parallel branches sequentially.
- Expand constructed-AST expression/operation coverage and deterministic
  error/retry behavior; run differential fixtures against the existing YAML
  compiler before switching any real workflows.
- Integrate the Rust backend into the regular Scala compiler entry point
  without introducing a cyclic module dependency.
- Run adversarial continuation, overflow, large-value and concurrency tests.

### Implementation status and gates

This document describes a **completed compiler CFG refactor**, not general
production workflow parity. See the [current verified state, exact repository
PRs, and next-step acceptance gates](current-state-and-roadmap.md).
Normal native production routing remains disabled.
