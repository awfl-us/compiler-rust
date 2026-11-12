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
(cd "$ROOT/frontend"
 sbt -batch \
   "set Test / unmanagedClasspath += Attributed.blank(file(\"$CLASS_DIR\"))" \
   "Test / runMain us.awfl.compiler.rust.CompatibilityMain $OUT --classes-dir $CLASS_DIR")
