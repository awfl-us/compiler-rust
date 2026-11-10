# AWFL native IPC v1 (experimental)

This is the initial **top-level effect** protocol between a trusted generated
Rust artifact and the existing AWFL server. It is not a public SDK or a
production execution API.

## Pure versus effectful artifacts

- `runtime_abi: 1`: legacy pure artifacts consume one plain JSON value on
  stdin and write one plain JSON value on stdout.
- `runtime_abi: 2`: effectful artifacts consume and produce one versioned JSON
  envelope. The frontend emits concrete Rust statements; it does not evaluate
  a workflow graph. Only the trusted server dispatches external calls.
- The server must pin the executable SHA-256, keep the original input stable,
  authenticate the caller and validate the artifact manifest before launch.

## Request

```json
{
  "protocol_version": 1,
  "input": {"order_id": 123},
  "continuation": {
    "next_step": 2,
    "slots": {
      "s0": {"type": "integer", "value": 8},
      "s1": {"type": "string", "value": "recorded-response"}
    },
    "frames": []
  }
}
```

`continuation` is omitted on first execution. `input` remains plain JSON.
The continuation stores tagged `AwflValue` data: `integer`, `float`,
`string`, `bool`, `null`, `list`, `map`, or `bytes`; the `slots` map
omits unbound slots instead of equating absence with a bound null. For now,
`frames` **must be empty**; nested effect suspensions are not supported.

## Response

A successful run returns:

```json
{"protocol_version":1,"status":"completed","result":"done"}
```

An effect boundary returns:

```json
{
  "protocol_version": 1,
  "status": "suspended",
  "effect": {
    "effect_id": "call_1",
    "node_id": "0.1",
    "operation": "http.post",
    "result_slot": "s1",
    "args": {"url": "https://example.org"}
  },
  "continuation": {
    "next_step": 2,
    "slots": {"s0": {"type": "integer", "value": 8}},
    "frames": []
  }
}
```

The Rust process exits after writing a suspended response. The server
persistently prepares the effect intent under the execution's fenced lease
**before** allowing any external dispatch. An operation-specific handler may
execute the effect; an ambiguous outcome must be reconciled and never blindly
replayed. The result is journaled in a separate Firestore effect document, and
the `result_slot` gets a tagged value in the checkpoint in the same transaction.
The next native invocation uses the checkpoint as `continuation`, restores
top-level slots and skips already completed steps.

`call_<n>` is only stable for direct top-level nodes. **Never extend it to
nested loops/branches without adding dynamic invocation frames and tests.**

## Safety constraints and limitations

- Protocol version, continuation program counter, tagged values, missing
  slots and empty frame stack are validated by the native runtime.
- The server verifies the trusted executable SHA and execution-pinned digest,
  bounds input/output data, and uses transactional lease epochs and checkpoints.
- All API authentication/authorization and executable selection must happen
  outside the test-only runner; those boundaries are not production wired.
- A bounded checkpoint and subcollection journal prevent unbounded root
  document growth, but large live slot sets still need liveness optimization,
  payload indirection and an explicit production retention policy.
- Neither Firestore nor a local protocol provides exactly-once remote HTTP.
  Side-effect handlers require stable idempotency keys and reconciliation.
- The server runner is currently an opt-in library, not a hardened executable
  sandbox, artifact registry, or production backend.

## Test locally

From `compiler-rust/` (after publishing the typed AWFL DSL locally):

```bash
cd frontend
sbt -batch 'Test / runMain us.awfl.compiler.rust.FrontendSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowSmoke'
cd ..
cargo test --workspace --locked
python3 scripts/test_control_flow.py
python3 scripts/test_native_protocol.py
```

For real Firestore emulator, server runner, and real compiled effect binaries,
see `server/workflows/native/README.md`.
