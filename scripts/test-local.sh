#!/usr/bin/env bash
# Local Scala DSL -> Rust compiler verification in the AWFL Scala consumer.
# `--full` adds every generated control-flow and effect-resume fixture.
set -euo pipefail

MODE="${1:---smoke}"
if [[ "$MODE" != '--smoke' && "$MODE" != '--full' ]]; then
  echo 'Usage: scripts/test-local.sh [--smoke|--full]' >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOWS="${AWFL_WORKFLOWS_ROOT:-$ROOT/../workflows}"
export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"
for tool in java sbt cargo rustc cc python3; do
  command -v "$tool" >/dev/null || { echo "Missing $tool. Use AWFL Scala+Rust consumer." >&2; exit 1; }
done
[[ -f "$WORKFLOWS/src/main/scala/us/awfl/workflows/LoadConversationHistory.scala" ]] || {
  echo "Missing unmodified LoadConversationHistory source at $WORKFLOWS" >&2; exit 1;
}

echo '== Use published additive DSL v0.3.4 (no local DSL build required) =='
echo '== Scala AST normalization, CFG liveness, and NativeMain =='
(cd "$ROOT/frontend" && sbt -batch \
  'Test / runMain us.awfl.compiler.rust.FrontendSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowLoweringSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowSmoke' \
  'Test / runMain us.awfl.compiler.rust.NativeCliSmoke' \
  'Test / runMain us.awfl.compiler.rust.CelStdlibSmoke' \
  'Test / runMain us.awfl.compiler.rust.LiteralCollectionsSmoke' \
  'Test / runMain us.awfl.compiler.rust.CelOperatorsSmoke')

echo '== Compile unmodified production workflow source =='
(cd "$ROOT/frontend" && sbt -batch \
  "set Test / unmanagedSources += file(\"$WORKFLOWS/src/main/scala/us/awfl/workflows/LoadConversationHistory.scala\")" \
  'Test / runMain us.awfl.compiler.rust.RealWorkflowSmoke')

echo '== Runtime unit tests and static analysis =='
(cd "$ROOT" && cargo fmt --all -- --check && cargo test --workspace --locked \
  && cargo clippy --workspace --all-targets --locked -- -D warnings)

echo '== Compile and run pure and real workflow Rust binaries =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-smoke/Cargo.toml \
  && python3 scripts/test_native_smoke.py \
  && cargo build --manifest-path fixtures/real-load-history/LoadConversationHistory/Cargo.toml \
       --target-dir "$ROOT/target/local-real-workflow" \
  && python3 scripts/test_real_workflow.py "$ROOT/target/local-real-workflow")

echo '== Compile and execute native CEL standard-library expressions =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-cel-stdlib/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-cel-stdlib/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_cel_stdlib.py)

echo '== Compile original DSL literal lists/maps as native Rust =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-literal-collections/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-literal-collections/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_literal_collections.py)

echo '== Compile existing CEL membership and floor-division operators =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-cel-operators/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-cel-operators/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_cel_operators.py)

if [[ "$MODE" == '--full' ]]; then
  echo '== Real Rust process control-flow and effect/replay conformance =='
  (cd "$ROOT" && python3 scripts/test_control_flow.py \
    && python3 scripts/test_native_protocol.py)
fi

echo "AWFL_NATIVE_LOCAL_TESTS_PASS ($MODE)"
