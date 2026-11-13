# AWFL compiler-rust — agent instructions

**Use a local-first development loop.** For native ProjectManager fixes,
run the compiler and generated Rust executables in the existing AWFL
Scala GKE environment, alongside the real AWFL Jobs Express service
started from the server repository on localhost:5055. Do not repeatedly
deploy Cloud Run, enqueue Cloud Tasks or publish GCS artifacts for normal
compiler/runtime iteration.

See the [current ProjectManager plan](docs/projectmanager-native-integration-plan-2026-11-13.md)
and the [server local-first setup](https://github.com/awfl-us/server/blob/main/docs/native-local-development.md).

Build registered native dependencies from the workflows repository:

    python3 scripts/native-dev.py codebase-ProjectManager --recursive

Reuse built binaries when only the server code changes. Run targeted
compiler/runtime tests and native local execution before using an isolated
real-cloud canary. The server's `npm run native:local` replays recorded Jobs
HTTP results; do not confuse it with fresh Jobs HTTP or authenticated
production service calls. The full Jobs application can also be run
locally for real route code, subject to development credentials for its
downstream services.

Maintain the existing Scala DSL and normal workflow semantics. Keep
production native routing disabled until verified; infrastructure/IAM is
Terraform-managed.


**Current perf plan:** see docs/persistent-native-ipc-plan-2026-11-13.md.
Keep generated ABI-3 one-shot JSON backward compatible; opt-in persistent
JSON-lines streaming is release-manifest gated. No Scala DSL changes.
