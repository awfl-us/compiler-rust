# Native CFG state-machine ABI 3 / IPC protocol 2

Status: **one compiler-native protocol**, runtime ABI 3 / IPC version 2;
experimental server execution with production routing disabled. The compiler
generates no ABI 1/2. The server still recognizes some v1 storage/test
contracts for compatibility, which must **not** be confused with an available
compiler-v1 output mode. For verified features and outstanding gates see
[current-state-and-roadmap.md](current-state-and-roadmap.md).

## Compilation model

`Normalize` converts the Scala DSL into typed instructions with structural
node and result-slot IDs. `ControlFlowLowering` compiles those instructions to
one deterministic, explicit control-flow graph (CFG). It supports conditional
branches, nested `ForRange`, `For`, `Fold`, sequential Calls, and `Return`.
`Plan.validate` rejects invalid successors, block IDs, or missing termination.
The same CFG also supports nested `Try` and `Raise` with **statically known
exception routes**. A handler redirects to a compiled block PC and error slot;
an uncaught raise produces a versioned `failed` outcome. The program counter
must be mutable for catch-only workflows even if no normal block advances it.
Dead blocks after unconditional returns are removed and labels renumbered.

`ContinuationAnalysis` computes live input/output slots across graph edges,
including loop backedges, by a backward fixed-point calculation.
`StateMachineEmitter` emits actual Rust match arms for the CFG, not an
interpreter and not a serialization of the DSL.

Each externally effectful instruction has one **normal successor PC** and,
when enclosed in a compiled `Try`, an **optional exception successor PC**
plus error slot. When it
suspends, the native binary returns its effect intent and a tagged
continuation containing the live values at that successor (excluding
the pending result). It does not re-run the effect or replay earlier
branch conditions when resumed. The server atomically records the
effect outcome, binds its result slot, and persists the checkpoint
before starting another native subprocess. For a confirmed HTTP failure,
this must instead atomically bind the tagged error and change the checkpoint
to the compiled exception block; uncertain remote outcomes must **not** take
either path until reconciled.

## Trusted execution globals

The protocol-v2 request optionally carries a `trusted_globals` object whose
only valid keys are `GOOGLE_CLOUD_WORKFLOW_ID`,
`GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID`, `GOOGLE_CLOUD_PROJECT_ID`,
and `GOOGLE_CLOUD_LOCATION`. When supplied, all four nonempty string fields
are required and bounded. Native Scala `WORKFLOW_ID`,
`WORKFLOW_EXECUTION_ID`, `PROJECT_ID`, and `LOCATION` compile to reads
of these fields, as does `environment.get` / `sys.get_env` for the same
four built-ins. A missing built-in fails closed unless `sys.get_env` was
given a string fallback. Other environment names require separate,
explicitly designed deployment configuration support; native executables
must never read arbitrary subprocess environment variables.

**Trust boundary:** The Rust executable validates structure and types, but
does not authenticate the request. Only the IAM-protected Jobs service may
construct `trusted_globals`, using its persisted owner-checked execution
and server-owned Google Cloud deployment identity. The Google Cloud project
ID is **not** the AWFL tenant project ID and is never accepted from
`input.env`. The generated ELF sees no GCP credentials, user tokens, or
caller-provided raw environment. Subprocess tests with forged `input.env`
and absent/invalid trusted context prove the default is to reject, not to
silently fall back. Checkpoints continue to store ordinary result slots;
globals are supplied by the server on each invocation.

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

An effect lexically guarded by `Try` may additionally contain an
`on_error` edge. For example, a *confirmed HTTP* error handler is transmitted
as part of the compiled effect intent (not chosen by the caller):

```json
{
  "effect": {
    "effect_id": "call_0_0_try_0",
    "node_id": "0.0.try.0",
    "operation": "http.post",
    "result_slot": "s4",
    "args": {"url": "https://example.invalid/fixture"},
    "on_error": {"next_step": 12, "error_slot": "s7"}
  },
  "continuation": {"next_step": 8, "frames": [], "slots": {}}
}
```

The example omits the outer response fields for brevity. Both the normal
and exception successor live-slot sets are retained in the suspended
checkpoint. The server persists `on_error` with the intent fingerprint,
rejects malformed or protocol-mismatched edges, and, on a **definitive**
HTTP 4xx/5xx response, atomically writes the error journal and checkpoint
`next_step: 12`, tagging `s7` as a map containing `message` and `code`.
Transport timeout, cancellation, unknown outcome and infrastructure errors
cannot be converted to this catchable failure. The server's HTTP failure
handler is merged in [server PR #78](https://github.com/awfl-us/server/pull/78).

An existing DSL default `Try` handler yields `log.write` with an `args` object
of `{ "text": "[logError] ..." }` and then re-raises the *original* error
after resumption. Merged server PR #79 records the successful log
result as JSON `null`, tagged in the Rust result slot as `{ "type": "null" }`.
A crash *after* the journal transaction must resume without redispatching;
a crash *after emitting a log but before committing* may emit a duplicate,
so exactly-once log delivery is **not** guaranteed.

If a `Raise` is not caught by an enclosing handler, the binary returns:

```json
{
  "protocol_version": 2,
  "status": "failed",
  "error": {"message": "deliberately raised", "code": null}
}
```

The server persistently sets terminal `Failed` (owner scoped), without
re-running or re-raising the same effect. `Failed` and `Completed` are
terminal; an invalid continuation remains a **process/runtime error**, not
an ordinary `Try` failure.

The IDs and PC above are illustrative, not fixed values. Logical effect IDs
derive from structural node IDs and zero-based *active loop ordinals*, giving
different iterations distinct idempotency keys. A completed response uses
`{"protocol_version":2,"status":"completed","result":...}`.

## Server contract

- Artifact manifests set `runtime_abi: 3` for this backend. The execution
  store pins `protocolVersion: 2` at creation for this backend. Preparing
  an effect with a mismatched version is rejected.
- `executeNativeStep` passes the pinned version to the digest-verified
  executable and requires the response version to match. The continuation
  can resume at any valid compiled CFG program counter, including zero.
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

The protocol is exercised by `scripts/test_native_protocol.py`,
`scripts/test_try_raise.py`, and real Cargo-generated binaries; the server's
`nativeTryIntegration.test.js` uses actual Rust executables plus an
in-memory Firestore-transaction-compatible store, including a simulated
post-journal crash. These tests do not constitute an exhaustive production
Firestore, network, concurrency or Google differential validation.

## Required before production

Compiler PR #7 and bounded `log.write` server PR #79 are merged with
successful CI; this enables only the tested implementation, **not** native
production routing. Verify authenticated public API and agent-session
integration (including status/cancel); complete error and retry *differential
parity*, unknown external outcome reconciliation, `Parallel`/`ParallelFor`,
callbacks, children, provider connectors, production lease heartbeats,
process isolation, additional crash windows, and a Firestore emulator/load
suite. The isolated pure-path shared launcher is deployed but **disabled**
for normal traffic. Details and gates:
[current-state-and-roadmap.md](current-state-and-roadmap.md).
