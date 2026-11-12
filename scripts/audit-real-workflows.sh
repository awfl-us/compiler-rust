#!/usr/bin/env bash
# Read-only constructed-AST / native source emission compatibility report.
# The caller compiles workflows in its own repository first; the scanner
# deliberately never edits, rewrites, publishes, or executes workflow sources.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOWS="${AWFL_WORKFLOWS_ROOT:-$ROOT/../workflows}"
CLASS_DIR="$WORKFLOWS/target/scala-3.4.2/classes"
OUT="${1:-$ROOT/target/constructed-ast-compatibility.json}"
OUT="$(python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$OUT")"
[[ -d "$CLASS_DIR" ]] || {
  echo "Compile unchanged workflows first: (cd $WORKFLOWS && sbt -batch compile)" >&2
  exit 2
}
mkdir -p "$(dirname "$OUT")"

# The workflow compiler frontend normally tests the latest published
# workflow-utils release.  When auditing an updated helper checkout, ensure
# its locally-published snapshot is also used by the reflection scanner, not
# just by the independently compiled workflow classes. Otherwise the scanner
# silently instantiates OLD helpers despite fresh workflow class files.
sbt_overrides=()
if [[ -n "${AWFL_WORKFLOW_UTILS_SNAPSHOT:-}" ]]; then
  [[ "$AWFL_WORKFLOW_UTILS_SNAPSHOT" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$ ]] || {
    echo "Invalid AWFL_WORKFLOW_UTILS_SNAPSHOT version" >&2; exit 2;
  }
  sbt_overrides+=("set dependencyOverrides += \"us.awfl\" %% \"workflow-utils\" % \"$AWFL_WORKFLOW_UTILS_SNAPSHOT\"")
  echo "Auditing with workflow-utils $AWFL_WORKFLOW_UTILS_SNAPSHOT override"
fi
(cd "$ROOT/frontend"
 sbt -batch "${sbt_overrides[@]}" \
   "set Test / unmanagedClasspath += Attributed.blank(file(\"$CLASS_DIR\"))" \
   "Test / runMain us.awfl.compiler.rust.CompatibilityMain $OUT --classes-dir $CLASS_DIR")
