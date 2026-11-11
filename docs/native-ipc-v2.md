# Native CFG state-machine ABI 3 / IPC protocol 2

Status: **experimental, opt-in**. Protocol 1 and its existing Rust fixtures are
unchanged and remain the default for existing executions. This is not wired
into AWFL's shared production launcher.

## Compilation model

`Normalize` converts the Scala DSL into typed instructions with structural
node and result-slot IDs. `ControlFlowLowering` compiles those instructions to
one deterministic, explicit control-flow graph (CFG). It supports conditional
branches, nested `ForRange`, `For`, `Fold`, sequential Calls, and `Return`.
`Plan.validate` rejects invalid successors, block IDs, or missing termination.
Dead blocks after unconditional returns are removed and labels renumbered.

`ContinuationAnalysis` computes live input/output slots across graph edges,
including loop backedges, by a backward fixed-point calculation.
`StateMachineEmitter` emits actual Rust match arms for the CFG, not an
interpreter and not a serialization of the DSL.

Each externally effectful instruction has one *successor PC*. When it
suspends, the native binary returns its effect intent and a tagged
continuation containing the live values at that successor (excluding
the pending result). It does not re-run the effect or replay earlier
branch conditions when resumed. The server atomically records the
effect outcome, binds its result slot, and persists the checkpoint
before starting another native subprocess.

## Wire representation

The input is JSON with `protocol_version: 2`:

```json
{
  "protocol_version": 2,
  "input": ["one", "two"],
  "continuation": {
    "next_step": 5,
    "frames": [],
    "slots": {
      "s2": {"type": "string", "value": "recorded"},
      "__awfl_pc_3_ordinal": {"type": "integer", "value": 1}
    }
  }
}
```

The continuation is omitted on first entry. In v2, `next_step` indexes a
*compiled CFG block*, not a top-level DSL step. `frames` must be empty;
nested control-flow state lives in tagged compiler-owned slots. The exact
CFG label and compiler-owned slot names are artifact-specific and must
never be interpreted using a different compiled artifact.

Suspension returns:

```json
{
  "protocol_version": 2,
  "status": "suspended",
  "effect": {
    "effect_id": "call_0_0_each_0_1",
    "node_id": "0.0.each.0",
    "operation": "http.post",
    "result_slot": "s3",
    "args": {"url": "https://example.org"}
  },
  "continuation": {
    "next_step": 7,
    "frames": [],
    "slots": {}
  }
}
```

The IDs and PC above are illustrative, not fixed values. Logical effect IDs
derive from structural node IDs and zero-based *active loop ordinals*, giving
different iterations distinct idempotency keys. A completed response uses
`{"protocol_version":2,"status":"completed","result":...}`.

## Server contract

- Artifact manifests set `runtime_abi: 3` for this backend. The execution
  store pins `protocolVersion: 2` at creation; previous executions default
  to version 1. Preparing an effect with a mismatched version is rejected.
- `executeNativeStep` passes the pinned version to the digest-verified
  executable and requires the response version to match. Protocol 2 allows
  a frame-free continuation at PC zero, unlike protocol 1.
- The bounded Firestore document holds the checkpoint, lease and
  prepared effect; effect outcomes live in per-effect subcollection documents.
  The journal and resumed tagged result slot are committed in one fenced
  transaction. A replacement worker resumes at the successor PC.
- Dispatch remains independently allowlisted and requires authorization
  by its caller; it uses the stable execution/effect idempotency key.
  Uncertain remote effects must be reconciled, never blindly retried.
- Plain JSON inputs, arguments, outcomes and results are stored as bounded
  JSON strings to avoid Firestore's nested-array restriction. Tagged
  continuation slots remain structured values.

The protocol is exercised by `scripts/test_native_protocol_v2.py` against
freshly compiled Rust executables and by the Firestore emulator's
worker-takeover test using a generated nested-range executable.

## Required before production

Integrate trusted artifact registration and backend selection into the
existing shared launcher. Add comprehensive error/retry/`Try`/`Parallel`
parity, authenticated provider-specific effect handlers, artifact isolation,
lease heartbeats, broader crash injection, and differential conformance
tests against Google Workflows. No production migration is implied.
