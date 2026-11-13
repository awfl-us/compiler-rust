# Persistent native Rust IPC performance plan — November 13, 2026

## Motivation / measured baseline

Native ProjectManager still restarts, SHA-256 verifies, stages, and removes a
Rust ELF for **every continuation**. The same benchmark uses 89 invocations
for a 30-effect ProjectManager request. Isolated measurements:

- 2,509 ms cumulative ELF lifecycle time (1,463 ms stage/verify/cleanup,
  1,046 ms Rust subprocess I/O).
- One 15,074 ms local test measured 12,702 ms during Jobs HTTP requests and
  2,372 ms outside Jobs; 1,555 ms of that was exclusively in Rust lifecycle.
- Jobs HTTP client 12,859 ms vs Jobs server processing 12,617 ms (~242 ms
  cumulative transport delta), so reducing HTTP transport alone is not
  the primary Rust-specific opportunity.
- The development harness uses **in-memory execution journal**; production
  Firestore durability costs are distinct and not represented here.

These are measurements, not a guaranteed speedup. Keep external Job operation
latency separate from native orchestration and use overlap-aware wall timers.

## Design

1. **Wire compatibility:** Generated ABI-3 / protocol-v2 binaries retain
   one-shot stdin JSON->stdout JSON behavior. In addition they accept opt-in
   `AWFL_NATIVE_STREAM_IPC=1`, reading one bounded JSON object per newline
   and replying with one newline-terminated JSON response, without exiting.
   Existing binary/manifest consumers continue to use the one-shot path.
   Manifest `stream_ipc_version: 1` advertises the capability.
2. **Isolation:** Rust code remains pure and never gets GCP credentials. The
   server authenticates executions and checks a published SHA-256 release.
   A persistent Rust process may only handle requests for its parent
   execution/tenant and is shut down on completion, errors or cancellation.
   Child workflow subprocesses are independently bounded and cleaned up.
3. **Server:** Preserve the current one-shot function as fallback. For opted-in
   releases, create a stateful, bounded request/response IPC session with
   one outstanding request, output/input byte limits, stderr redaction,
   read/write timeouts, process-exit handling and guaranteed cleanup. Cache
   staged executable verification for the lifetime of the execution context.
   Never let a child process persist across authenticated users.
4. **Persistence:** Keep the durable store, effect dispatch and checkpoints
   authoritative. The Rust process has no durable state; every request includes
   full validated input and continuation. On crash, the server can restart a
   subprocess and recover from persisted continuation. No new Cloud Tasks.
   Idle or long-awaited children must be safely cancellable.
5. **Fork/join:** Each concurrently running lane owns its own session so
   branches can advance in parallel. Synchronous child workflows similarly
   use sessions scoped to their caller, not a global process shared by users.
6. **Tests:** Scala smoke asserts manifest flag, one-shot compatibility and
   multi-message streaming against a compiled binary. Server tests assert
   two requests use one process, failures and timeouts clean up, legacy
   fallback, concurrent lane isolation and no leaked children. Run the
   compiled ProjectManager against real local Jobs Express, maintaining
   existing tests. Benchmark separately: Jobs processing, HTTP transport,
   native subprocess, execution-store, callbacks and untraced intervals.

## Delivery sequence

- [x] Record this plan/documentation in compiler-rust and server.
- [x] Compiler stream IPC generation, manifest flag, Rust smoke tests.
- [x] Server per-execution persistent process and verified staging;
      propagate to durable, memory-first and synchronous-child paths.
- [x] End-to-end test on compiled real workflow.
- [x] Repeat performance benchmark: one-shot vs streamed compiled Rust,
      with matching 100-request input. The prior Jobs/non-Jobs wall split
      is retained as baseline; a controlled full-workflow differential
      still depends on deterministic non-LLM service fixtures.
- [ ] Merge changes via pull requests; do not enable production native routing
      or change IAM/infrastructure outside Terraform.

## Guardrails

No changes to user-facing Scala DSL or canonical operations. Do not change
workflow idempotency policy. Never cache executable bytes based on a user
provided path/hash without verifying the trusted release. Avoid background
work tied to request-scoped Cloud Run instances. Maintain the native
execution-budget limit and deterministic journaling behavior.

## Verification notes — November 13

- Scala `NativeCliSmoke` passed; full 26-workflow dependency closure emitted
  ABI-3 / v2 Rust with `stream_ipc_version:1` and was statically compiled
  as musl ELF (232 seconds including cold Cargo compilation).
- Compiled `cli-fixture-increment` passed one-shot compatibility, five
  requests on one streaming process, protocol error, timeout, SHA mismatch
  and concurrent separate-session tests.
- Local compiled `codebase-ProjectManager` with genuine Jobs Express
  calls and authenticated development GCP storage completed its parent
  execution: **30 effects, 19,387 ms, 0 failed Jobs HTTP calls**. The
  detached continuation remains separate; this does not prove its
  later completion or cross-restart GKE recovery.
- A matched isolated benchmark with 100 repeated requests to one compiled
  binary measured **one-shot 1,928.4 ms total** (19.28 ms mean) vs
  **stream 19.7 ms total** (0.20 ms mean); one process served 101 stream
  requests including warm-up. Reuse removes about 1,908.7 ms per 100
  continuations *in this benchmark*.
- Earlier external-service split: 15,074 ms total, 12,702 ms Jobs overlap,
  2,372 ms outside Jobs. Streaming reduces repeated subprocess overhead;
  it cannot eliminate LLM, Jobs, callbacks, and Firestore latencies.
