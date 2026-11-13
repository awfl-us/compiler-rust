# Native compiler build storage audit and cleanup (2026-11-13)

## What failed

The AWFL Scala GKE consumer running the extended native compiler test suite
was evicted when its **4 GiB container ephemeral-storage limit** was exceeded.
This was not a Rust executable size issue. The three compiled Project Manager
musl binaries were about **4.61 MB** combined.

The next workspace restore contained about **0.58 GiB**, including roughly
**490 MiB** across five separate, redundant Cargo target directories.
One unoptimized LoadConversationHistory Cargo build used **188 MiB**
(109 MiB dependency output, 45 MiB build scripts, 22 MiB incremental
artifacts). The evicted pod's transient contents could not be recovered, so
those are measured contributing sources, not a complete 4 GiB attribution.

## Changes

- The local test script exports one shared CARGO_TARGET_DIR
  (target/local-shared by default). Test fixtures, including real
  LoadConversationHistory, Product, FlatMap, and Try/Raise, reuse their
  compiled dependency tree rather than compiling the same dependencies into
  separate directories.
- Existing fixture test Python scripts resolve binary locations from
  CARGO_TARGET_DIR when set and preserve the old per-crate lookup when
  unset. The two full-control-flow tests also use the same target if supplied.
- The harness defaults to CARGO_INCREMENTAL=0,
  CARGO_PROFILE_DEV_DEBUG=0, and CARGO_PROFILE_TEST_DEBUG=0.
  Override the settings when debugging. **Production release artifacts are
  unaffected.**
- The GitHub Scala-to-native smoke job also uses a shared Cargo target and
  prints its size on completion, including when tests fail.
- The local harness emits per-stage du -sm measurements for shared Cargo
  output, Scala frontend output, the Cargo download cache, /tmp, and
  /workspace. Changes are reported directly in logs rather than inferred
  from node-wide df output.
- Companion CLI snapshot fix: https://github.com/awfl-us/cli/pull/25
  excludes debug and release output in *-build, *-static,
  and *-musl trees, including cross-target builds, from both
  upload and restore, while preserving generated manifests and source.
  Server's consumer image must pin the merged CLI revision for rollout.

## Usage

    AWFL_WORKFLOWS_ROOT=/workspace/workflows scripts/test-local.sh --smoke
    CARGO_TARGET_DIR=/workspace/my-shared-target scripts/test-local.sh --full

The default shared directory is inside the compiler's target/ tree and is
already excluded from cloud workspace persistence. Generated runtime fixtures
still compile to their normal native executables; these are **test-build
configuration changes only**, not changes to the generated Rust ABI.

## Verified regression results

- The GKE Scala consumer completed the entire optimized
  scripts/test-local.sh --smoke suite: AWFL_NATIVE_LOCAL_TESTS_PASS (--smoke).
- The shared Cargo output was **178 MiB** at smoke completion and **212 MiB**
  after separately running the full native control-flow and protocol tests.
- The /workspace tree was **840 MiB** at smoke completion, including restored
  source worktrees and retained artifacts.
- The real native Project Manager binaries remain approximately 4.61 MB
  combined; this change only affects test build outputs.

The rollout pin is tracked by https://github.com/awfl-us/server/pull/96,
which updates the server's consumer image build to CLI main after PR #25.

## What remains

Observe Cargo target growth over future runs and check the GKE pod's
container-level ephemeral-storage accounting if it approaches 4 GiB again.
A future cleanup can remove previously uploaded build objects in GCS, but
none are deleted by this patch. The server and CLI images keep the
existing Terraform-managed resource limits.
