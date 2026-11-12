#!/usr/bin/env python3
"""Summarize constructed-AST audit JSON; keep the verbose per-node dump local."""
import collections
import json
import sys
from pathlib import Path

src, dst = map(Path, sys.argv[1:3])
data = json.loads(src.read_text())
workflows = data["workflows"]

def category(row):
    if row["status"] == "emitted":
        return "Rust source emitted"
    reason = row.get("reason") or ""
    if "unsupported raw CEL" in reason:
        return "Unsupported raw CEL/reference"
    if "FlatMap" in reason:
        return "FlatMap wrapper"
    if "unknown native Call" in reason:
        return "Connector name not migrated"
    if "unknown native CEL function" in reason:
        return "CEL function name not migrated"
    if "requires native lowering" in reason:
        return "Control-flow lowering missing"
    if "lacks tested native semantics" in reason:
        return "Function semantics missing"
    return "Other compiler error"

labels = collections.Counter(category(w) for w in workflows)
unknown = collections.Counter((o["kind"], o["name"]) for o in data["unknown_operations"])
lines = [
    "# Constructed-AST compatibility snapshot",
    "",
    "Generated from **unchanged** compiled AWFL workflow classes, using",
    "`scripts/audit-real-workflows.sh` and `scripts/render_compatibility_report.py`.",
    "The scanner walks expanded helper-built DSL graphs and attempts CFG/Rust",
    "source emission. It **does not** compile Cargo binaries, exercise effects,",
    "or prove Google Workflows behavioral parity.",
    "",
    f"- Workflow definitions: **{data['workflow_count']}**",
    f"- Rust source emitted: **{data['emitted']}**",
    f"- Source emission blocked: **{data['blocked']}**",
    f"- Class-loading errors: **{len(data['load_errors'])}**",
    "",
    "## First blocker by workflow",
    "",
    "| Category | Definitions |",
    "| --- | ---: |",
]
for label, count in labels.most_common():
    lines.append(f"| {label} | {count} |")
lines += [
    "",
    "These are *first* failures: removing a blocker can uncover other unsupported",
    "operations in the same graph. Do not treat counts as the total set of failures.",
    "",
    "The original DSL's raw `sys.now()` reference is particularly common.",
    "Native clock values must not be recomputed after continuation replay.",
    "",
    "## Legacy operation names in instantiated graphs",
    "",
    "| Kind | Name | Occurrences |",
    "| --- | --- | ---: |",
]
for (kind, name), count in unknown.most_common():
    lines.append(f"| {kind} | `{name.replace('|', '&#124;')}` | {count} |")
lines += [
    "",
    "These counts include operations instantiated by helpers; replacing a name in",
    "one helper source can fix many occurrences. Only change application Scala",
    "source to replace CEL function and connector names with the existing",
    "`CelFunctions` / `CallOperations` generic constants. Do not restructure",
    "workflow logic, introduce typed public AST nodes, or change helper APIs.",
    "",
    "## Priority and safety boundaries",
    "",
    "1. Complete compiler-private syntactic lowering (e.g. `FlatMap`).",
    "2. Change only the source-level operation-name values where needed;",
    "   preserve the same constructed workflow shape in both backends.",
    "3. Implement each missing function and server adapter with separately",
    "   tested semantics, including nondeterministic time/UUID outcomes.",
    "4. Add durable fork/join and child/callback execution before treating",
    "   ProjectManager as runnable on native.",
    "5. Verify actual Cargo builds, mock-effect resumptions, and Google-vs-Rust",
    "   differential behavior; source emission is only the first gate.",
    "",
    "## Per-definition result",
    "",
    "| Source module | Definition | Status | First issue |",
    "| --- | --- | --- | --- |",
]
for row in workflows:
    label = category(row)
    source = row["source"].replace("|", "&#124;")
    name = row["workflow"].replace("|", "&#124;")
    detail = (row.get("reason") or "").replace("|", "&#124;").replace("\n", " ")
    if detail:
        # Retain the exact compiler-node diagnostic without making the table huge.
        detail = detail[:175] + ("…" if len(detail) > 175 else "")
    lines.append(f"| `{source}` | `{name}` | {label} | {detail or '—'} |")
lines += [
    "",
    "The full node-by-node JSON is intentionally kept in the ignored build",
    "output directory (`target/constructed-ast-compatibility.json`) rather than",
    "committed; it can be regenerated from the current application bytecode.",
    "",
]
dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text("\n".join(lines))
print(f"REPORT_SUMMARY={dst} defs={len(workflows)}")
