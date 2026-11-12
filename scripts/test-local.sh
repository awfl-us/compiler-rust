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

echo '== Use DSL v0.3.5 with canonical CEL standard-library names =='
echo '== Scala AST normalization, CFG liveness, and NativeMain =='
(cd "$ROOT/frontend" && sbt -batch \
  'Test / runMain us.awfl.compiler.rust.FrontendSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowLoweringSmoke' \
  'Test / runMain us.awfl.compiler.rust.ControlFlowSmoke' \
  'Test / runMain us.awfl.compiler.rust.NativeCliSmoke' \
  'Test / runMain us.awfl.compiler.rust.TimeUuidSmoke' \
  'Test / runMain us.awfl.compiler.rust.CelStdlibSmoke' \
  'Test / runMain us.awfl.compiler.rust.TextSubstringSmoke' \
  'Test / runMain us.awfl.compiler.rust.TimeFormatSmoke' \
  'Test / runMain us.awfl.compiler.rust.ConversionSmoke' \
  'Test / runMain us.awfl.compiler.rust.BytesSmoke' \
  'Test / runMain us.awfl.compiler.rust.LiteralCollectionsSmoke' \
  'Test / runMain us.awfl.compiler.rust.CelOperatorsSmoke' \
  'Test / runMain us.awfl.compiler.rust.TryRaiseSmoke' \
  'Test / runMain us.awfl.compiler.rust.CompatibilitySmoke')

echo '== Compile unmodified production workflow source =='
(cd "$ROOT/frontend" && sbt -batch \
  "set Test / unmanagedSources += file(\"$WORKFLOWS/src/main/scala/us/awfl/workflows/LoadConversationHistory.scala\")" \
  'Test / runMain us.awfl.compiler.rust.RealWorkflowSmoke')

echo '== Compile unchanged Product.scala (Try/Fold/For) from AWFL workflows =='
(cd "$ROOT/frontend" && sbt -batch \
  "set Test / unmanagedSources ++= Seq(file(\"$WORKFLOWS/src/main/scala/us/awfl/workflows/evals/Product.scala\"), file(\"src/integration/scala/us/awfl/compiler/rust/RealProductSmoke.scala\"))" \
  'Test / runMain us.awfl.compiler.rust.RealProductSmoke')

echo '== Runtime unit tests and static analysis =='
(cd "$ROOT" && cargo fmt --all -- --check && cargo test --workspace --locked \
  && cargo clippy --workspace --all-targets --locked -- -D warnings)

echo '== Compile and run pure and real workflow Rust binaries =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-smoke/Cargo.toml \
  && python3 scripts/test_native_smoke.py \
  && cargo build --manifest-path fixtures/real-load-history/LoadConversationHistory/Cargo.toml \
       --target-dir "$ROOT/target/local-real-workflow" \
  && python3 scripts/test_real_workflow.py "$ROOT/target/local-real-workflow")

echo '== Compile FlatMap projection and verify effect continuation =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-flatmap/Cargo.toml \
  --target-dir "$ROOT/target/local-flatmap" \
  && python3 scripts/test_flatmap.py "$ROOT/target/local-flatmap")

echo '== Execute real Product combinator across single, Cartesian and empty inputs =='
(cd "$ROOT" && for name in single cartesian empty; do
  cargo build --manifest-path "fixtures/real-product/$name/Cargo.toml" \
    --target-dir "$ROOT/target/local-product-workflow"
  cargo clippy --manifest-path "fixtures/real-product/$name/Cargo.toml" \
    --all-targets --locked --target-dir "$ROOT/target/local-product-workflow" -- -D warnings
done && python3 scripts/test_real_product.py "$ROOT/target/local-product-workflow")

echo '== Compile and execute native CEL standard-library expressions =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-cel-stdlib/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-cel-stdlib/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_cel_stdlib.py)

echo '== Compile native text.substring and exercise Unicode, bounds and type errors =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-text-substring/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-text-substring/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_text_substring.py)

echo '== Compile native time.format with UTC/zone offsets, DST and strict types =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-time-format/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-time-format/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_time_format.py)

echo '== Compile and execute native time.now and uuid.generate =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-time-uuid/Cargo.toml   && cargo clippy --manifest-path fixtures/native-time-uuid/Cargo.toml --all-targets --locked -- -D warnings   && python3 scripts/test_time_uuid.py)

echo '== Compile and execute conversions with native Rust and Unicode/error checks =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-conversion/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-conversion/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_conversion.py)

echo '== Compile and execute UTF-8/ASCII and Base64 encoding paths =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-bytes/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-bytes/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_bytes.py)

echo '== Compile original DSL literal lists/maps as native Rust =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-literal-collections/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-literal-collections/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_literal_collections.py)

echo '== Compile existing CEL membership and floor-division operators =='
(cd "$ROOT" && cargo build --manifest-path fixtures/native-cel-operators/Cargo.toml \
  && cargo clippy --manifest-path fixtures/native-cel-operators/Cargo.toml --all-targets --locked -- -D warnings \
  && python3 scripts/test_cel_operators.py)

echo '== Compile nested Try/Raise, recoverable effects and explicit errors =='
(cd "$ROOT" && for name in catch-raise success nested-catch uncaught runtime-error effect-catch default-log; do
  cargo build --manifest-path "fixtures/native-try-raise/$name/Cargo.toml" --target-dir /tmp/awfl-try-target
  cargo clippy --manifest-path "fixtures/native-try-raise/$name/Cargo.toml" --all-targets --locked --target-dir /tmp/awfl-try-target -- -D warnings
done && python3 scripts/test_try_raise.py)

if [[ "$MODE" == '--full' ]]; then
  echo '== Real Rust process control-flow and effect/replay conformance =='
  (cd "$ROOT" && python3 scripts/test_control_flow.py \
    && python3 scripts/test_native_protocol.py)
fi

echo "AWFL_NATIVE_LOCAL_TESTS_PASS ($MODE)"
