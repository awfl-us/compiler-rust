# `helpers-ToolDefs`: Google Workflows vs native Rust — first cloud differential

## Follow-up: simpler in-memory worker state and pinned artifact cache

**Measured:** November 12, 2026, isolated Jobs revision `jobs-00147-siv`
(`native-profile` zero-traffic tag during the test). Server
[#91](https://github.com/awfl-us/server/pull/91) caches small, SHA-256-
verified artifact bytes within a warm Jobs instance; server
[#92](https://github.com/awfl-us/server/pull/92) passes already committed,
fenced execution state between transitions within the active Jobs request.
No new in-memory database, state synchronization process, Rust IPC format,
or Terraform resource was introduced. Firestore still commits effect intent
**before** HTTP and atomically journals the outcome with its continuation.
The dispatcher retains a fresh Firestore lease/intent check before the
external effect; recovery, cancellation and parent-fork fencing remain.

| Trial | Client: Firestore create → Tasks → Jobs → completion | Jobs request |
| --- | ---: | ---: |
| Cold | 2.298 s | 1,811 ms |
| Warm 1 | 0.952 s | 517 ms |
| Warm 2 | 0.966 s | 526 ms |
| Warm 3 | 0.882 s | 517 ms |
| Warm 4 | 0.863 s | 507 ms |

**Five of five** runs succeeded, with `{"defs":[]}`, a single HTTP 200
effect, and a durable Firestore journal. The **four warm client runs have
median 0.917 s**; corresponding Jobs request median **517 ms**. This is
about **37% lower** client-observed time than the older 1.445 s warm native
median, but it reflects **both** the artifact cache and worker-state reuse
and is *not* a controlled isolated A/B test. It is still slower than the
previous warmed Google Workflows sample (~0.422 s), with different launch
APIs. Results from five sequential runs do not establish p95, cost, or
concurrency scaling.

| Per-execution profiling metric | Earlier median | New median |
| --- | ---: | ---: |
| Firestore document reads | 4 calls / 116.8 ms | 2 calls / 57.3 ms |
| Firestore claims | 2 calls / 159.7 ms | 1 call / 74.6 ms |
| Firestore renewal | 1 call / 73.2 ms | 0 |
| GCS artifact materialization | 241.3 ms | 4.9 ms warm |
| Firestore prepare effect | 103.8 ms | 91.6 ms |
| Firestore journal/outcome | 106.7 ms | 111.4 ms |
| Firestore completion | 78.4 ms | 76.3 ms |

Measured through Cloud Run structured logs `awfl_native_job_timing`,
new request IDs: `native_4c8ca34941bb4ac4a35f3c25e75775f5`,
`native_5caef1f82a294bf794a5cabb42556c5f`,
`native_9a61b829783c4a8e97a12ed400b77d53`,
`native_95ad7be7b89c40ef8d10a764282426c8`,
`native_a9e7cf18c63f43bdbf6e93fa77936cc2`.

**Next:** avoid additional state stores and running Rust processes until
a longer, multi-effect workflow demonstrates a need. Remaining latency is
largely the **necessary** Firestore effect-intent, journal and completion
transactions, plus Tasks/Jobs dispatch. A same-AWFL-API Google/native
comparison and a representative multi-effect workflow are still needed.

---

**Measured:** November 12, 2026, `us-central1`, project `awfl-us`.

## Inputs, revisions, and correctness

- **Scala source:** unchanged `us.awfl.workflows.helpers.ToolDefs` in
  `awfl-us/workflows`; Google deployment `helpers-ToolDefs`, revision
  `000022-297` (updated 2026-11-12 04:25 UTC). The deployed Google YAML
  was inspected against the generated YAML in the workflows repository.
- **Native release:** the same Scala-defined `helpers-ToolDefs` compiled to
  Rust ABI 3 / IPC 2, SHA-256
  `177ddd6b8fa017888178c9764ce089667e80ad5efc4b24aa31002bf27deb31c5`,
  allowed effects `http.get` only; isolated Cloud Run Jobs revision
  `jobs-00135-dip` (`native-e2e` tag), without routing ordinary traffic.
- **Input:** `toolNames = ["__awfl_native_http_api_e2e_probe__"]`,
  `env.BASE_URL = https://jobs-4wmfqir74q-uc.a.run.app`, authenticated
  synthetic user `awfl-native-e2e-probe`, project
  `qeIBreRiGblqMplTixii`. Both read the same Jobs tools-list handler:
  Google generated YAML uses `/tools/list`; the existing Rust helper uses
  its `/jobs/tools/list` compatibility prefix. Both prefixes are mounted
  to the same authenticated router.
- **Outcome:** **5/5 Google and 5/5 native succeeded**; all returned
  `{"defs":[]}`. Every native execution persisted **one** confirmed
  `http.get` effect with HTTP **200** in its Firestore journal.

## Measured latencies (seconds)

| Run | Google REST create + poll | Native Firestore create + Cloud Task + poll | Google `endTime-startTime` | Native Firestore `updatedAt-createdAt` |
| --- | ---: | ---: | ---: | ---: |
| 1 | 11.963 | 12.225 | 11.765 | 12.050 |
| 2 | 8.389 | 1.518 | 8.028 | 1.391 |
| 3 | 0.478 | 1.445 | 0.263 | 1.214 |
| 4 | 0.422 | 1.531 | 0.236 | 1.221 |
| 5 | 0.398 | 1.269 | 0.239 | 1.115 |

**Warm comparison (last three trials):** Google client-observed median
**0.422 s**; native client-observed median **1.445 s** (native about
**3.4x slower** on this tiny single-effect workflow). Google service-side
median **0.239 s**, native Firestore lifecycle median **1.214 s**; these
fields measure **different systems** and must not be conflated. Results
indicate a major cold/warm discontinuity, particularly Google's first two
executions. Five non-randomized sequential samples are *not* capacity,
concurrency, cost, p95, or production performance evidence.

**Worker evidence:** Cloud Run request logs for `jobs-00135-dip` show the
four warm native `/native/execute` requests taking approximately **1.147,
1.023, 0.985, 0.925 s**. Most warm native time therefore occurs *inside*
the worker HTTP request, not in the queue's apparent enqueue-to-dispatch
interval. Do not assert a more specific bottleneck without instrumentation.

**Reproducibility IDs (ordered by run):**

| Run | Google Workflows execution ID | Native Firestore execution ID |
| --- | --- | --- |
| 1 | `53f6d586-a709-4df1-b258-8eb015c48b61` | `native_69b03d2a53604633b98cfe92030e9d11` |
| 2 | `803adcb0-cc66-47c4-a899-5b820dce1148` | `native_6d5818cc229f44d0948ab72c1b217e72` |
| 3 | `36ba361b-17a9-4f68-b6e4-a75962f86f32` | `native_7fb80f55470f492eb40310a58da98176` |
| 4 | `6b327d11-a347-4c30-86bb-0ccff442ca18` | `native_5633d48a90d64ee7a4f11029d1be1012` |
| 5 | `7226093e-2390-427d-8f7a-71486dbd63f3` | `native_2d8636b6a779479fb33ae852dbee55ed` |

## Critical methodology limitation

Google timings start at an authenticated Google **Executions REST API**
`createExecution` request and end when polling reports `SUCCEEDED`.
Native timings start with a direct IAM-authenticated **Firestore execution
creation**, continue through Cloud Tasks / tagged Jobs, and end with a
Firestore poll reporting `completed`. **These are not the same public API
entrypoint and are not a controlled API-vs-API benchmark.** The workflow,
inputs, effect and final results were aligned; the infrastructure launch
paths differ. Wall times include each harness's REST/polling overhead.

Separately, the **real AWFL `/workflows/execute` API** successfully
authenticated a temporary AWFL key and launched the same native release as
`native_aa719b451bd24a04884a5b93cfc0cc63`; the API returned `SUCCEEDED`,
`{"defs":[]}` in **14.35 s** in one cold run, and Firestore recorded a
single confirmed HTTP 200 effect. That temporary key was revoked and a
subsequent request returned HTTP 403. Native routing was restored to off
in the stable API after the test. This *separate* observation cannot be
used as a warm API median.

## Interpretation and smallest next steps

1. **Correctness gate passed** for the unchanged `helpers-ToolDefs` single-
   effect case, including the full authenticated native API path in a
   separate canary. Do **not** claim full Google Workflows compatibility,
   especially for multi-effect, `Try`, retries, callbacks and parallel work.
2. **No native warm-latency win has been established.** Avoid optimizing
   Rust's few-millisecond local interpreter when a roughly one-second
   Cloud Run worker request dominates. First measure time spent in pinned
   GCS artifact download/materialization, Firestore lease/checkpoint/effect
   transactions, outbound signed HTTP, and second Rust continuation.
3. Run a **same-API harness** after setting up a supported fresh user
   credential: compare `/workflows/execute` with `AWFL_NATIVE_EXECUTION_ENABLED`
   set per isolated API revision versus the stable Google-backed API, using
   the same authenticated user/project, same job URL, alternating cold/warm
   runs, and capturing response plus execution/worker traces. The previous
   temporary API credential was deliberately revoked. Keep normal native
   routing off and GCP IAM/Terraform unchanged unless a real dependency
   requires it.
4. After isolating the observed ~1s worker cost, validate one **representative
   multi-effect/LLM agent workflow**; a tiny tools-list read is not a
   representative throughput or cost workload.
