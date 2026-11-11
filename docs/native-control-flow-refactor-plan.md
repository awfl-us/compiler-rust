# Native compiler refactor: structured DSL to resumable control-flow IR

Status: implementation plan, November 2026. Existing native ABI 1 (pure) and
ABI 2 (effectful v1) remain backward-compatible during migration.

## Problem and invariants

RustEmitter.scala has grown into separate special cases for top-level Calls,
ForRange Calls, nested ForRange, Switch Calls and nested Switch. These duplicate
resume validation, dynamic invocation naming, and saved-slot restoration.
Adding another control-flow combination multiplies complexity.

Maintain these invariants:
1. Generated Rust performs pure operations locally. Side effects are ONLY
   server-dispatched and journaled, never executed in the compiled binary.
2. Following a recorded effect, resume executes the next logical instruction.
   No re-running a completed external Call or previous loop iteration.
3. Keep the existing execution owner/epoch/lease fencing and atomic Firestore
   effect journal. Do not duplicate the producer/consumer or launcher paths.
4. Dynamic effect identity derives from static Call node ID and ordered
   enclosing loop iteration ordinals, independent of restarts and process ID.
5. Deterministic labels, bounded continuation data, explicit fail-closed
   handling of unsupported DSL constructs, and versioned artifacts.
6. Existing ABI 1/2 artifacts and persisted v1 continuations remain valid.
   Do NOT silently interpret them using the new control-flow meaning.

## Pipeline and module boundaries

  existing AWFL DSL
      -> Normalize: structured typed Ir.Program
      -> ControlFlowLowering: explicit basic blocks, labels and edges
      -> ContinuationAnalysis: resume sites, dynamic IDs, live-slot sets
      -> StateMachineEmitter: Rust match/loop on control-flow labels
      -> ArtifactWriter: deterministically emit source, Cargo, manifest
           | 
      shared native Rust runtime (typed values + protocol)
           |
      AWFL server: runner -> persistent journal -> dispatcher

The lowering emits a statically compiled Rust state machine, not a Google YAML
interpreter. The pure execution fast path can remain direct Rust code.

Scala source responsibilities under the existing us.awfl.compiler.rust package:
- Ir.scala and Normalize.scala: immutable structured DSL IR.
- ControlFlowIr.scala: CFG instruction data and target validator.
- ControlFlowLowering.scala: recursive construction of explicit block edges.
- ContinuationAnalysis.scala: dynamic effect-site paths and liveness sets.
- ExpressionEmitter.scala: expression to Rust code; slot binding mode.
- PureRustEmitter.scala: direct pure-code path and its return semantics.
- StateMachineEmitter.scala: generic CFG code and suspension/resumption.
- ArtifactWriter.scala: manifest hash, Cargo crate name, file writes.
- RustEmitter.scala: minimal backwards-compatible public facade.

The Rust runtime keeps operations.rs, lib.rs, protocol.rs separate. The AWFL
server retains executionStore.js, runner.js, effectDispatcher.js and their
single-responsibility boundaries.

## Control-flow IR

Each BasicBlock has a stable deterministic integer label and one instruction;
all successor targets are explicit and validated.

- Assign(slot, expression, next)
- Branch(condition, ifTrue, ifFalse); Switch is first-match test chain
- RangeInit(from, until, cursorSlot, resultSlot, next)
- RangeNext(cursorSlot, indexSlot, body, done)
- EachInit(iterable, cursorSlot, resultSlot, next) / EachNext
- FoldInit(initial, iterable, accumulatorSlot, cursorSlot, next) / FoldNext
- Append(resultExpr, partialOutputSlot, next) / FoldUpdate
- Invoke(nodeId, operation, arguments, resultSlot, next)
- Finish(expression) / EarlyReturn(expression)

Loop cursors, accumulated outputs, and fold state are compiler-owned tagged
slots. Nested branches and loops are ordinary graph edges, not distinct
emitNestedRange / emitNestedSwitch code paths. Early Return is terminal.

## Versioned v2 continuation / ABI 3

Use protocol_version=2 for newly generated effectful ABI 3 artifacts. The
server distinguishes v1 ABI 2 and v2 ABI 3 via the pinned artifact manifest.
The v2 JSON envelope retains tagged slots and effect intent but next_step
identifies a lowered CFG label rather than a top-level DSL step. V1 semantics
remain unchanged. Frames may be empty in v2: the precise PC plus compiler
slots capture the execution location.

An Invoke block at label N suspends with next_step = Invoke.next (not N).
The server atomically journals the result and binds the resultSlot in slots;
resume jumps immediately AFTER the effect. This eliminates replay of calls
and reconstructing nested branch/loop stacks on restart. Each Invoke ID is
deterministic from the static node ID plus enclosing dynamic loop cursors
(branch identities already live in structural node IDs).

Start with a bounded, conservative checkpoint containing every bound slot.
Then compute backward liveIn/liveOut dataflow sets for each CFG block,
including loop backedges. Persist only slots used after each effect; later
externalize large data by immutable content hash and a trusted registry.

Validate CFG target bounds and slot schema on both sides of the IPC. Never
confuse a bound null with an unbound slot. Cap instruction steps per run and
checkpoints. The runtime and server must reject invalid protocol versions.

## Implementation / release gates

A. Baseline: preserve unfinished nested-range WIP, run current tests, commit
   this plan independently of executable code.

B. Extract ExpressionEmitter, PureRustEmitter and ArtifactWriter from the
   monolithic emitter, preserving generated ABI 1/2 behavior. Run Scala
   smoke, native fixtures, Rust Clippy and Firestore emulator suites.

C. Introduce ControlFlowIr and recursive ControlFlowLowering with unit tests:
   deterministic labels, successors, nested returns, loops and invalid
   constructs. Migrate effectful source generation to StateMachineEmitter.
   Version the new native request/response as protocol v2 / ABI 3; maintain
   ABI 2 support for existing binaries until conversion.

D. Test actual generated workflow binaries through v2 server runner. Include
   sequential Calls, Switch, ForRange, nested branch/range, negative or empty
   loops, mixed nesting, For/Fold, and worker takeover between effects.
   Assert deterministic, unique IDs and journal-once behavior; no claim that
   arbitrary external HTTP can be performed exactly once.

E. Production gates: tested artifact registry and provenance, sandbox,
   authenticated effect adapters, reconciliation and idempotency, heartbeat
   and orphan sweeper, explicit backend option within the EXISTING launcher,
   and performance comparison with Google Workflows. No deployment/merge
   until explicitly approved.

## Acceptance criteria

- Current ABI 1/2 golden fixtures and native server tests do not regress.
- RustEmitter.scala is a facade rather than the repository's largest
  multi-purpose file.
- New effects use a single generic CFG-based suspension/resume generator.
- V2 continuation resumes at the next block without replay in real binaries.
- Each accepted control-flow shape has negative and crash-recovery tests.
- Commits and pushes are limited to the feature branches; no production
  rollout or independent AWFL agent sessions.
