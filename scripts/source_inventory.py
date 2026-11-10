#!/usr/bin/env python3
"""Reproducible Phase 0 source inventory. This is a lint, NOT constructed-AST coverage.

Run from anywhere:
    python3 compiler-rust/scripts/source_inventory.py --output compiler-rust/docs/phase0-source-inventory.json
Constructed workflow AST enumeration and Google differential execution are separate
Phase 0 gates. Do not use this source scan to claim that they are complete.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

REPOSITORIES = ("dsl", "compiler", "compiler-yaml", "workflow-utils", "workflows", "server")
SCALA_REPOSITORIES = ("dsl", "compiler", "compiler-yaml", "workflow-utils", "workflows")
PATTERNS = {
    "cel_function_literal": re.compile(r'\bCelFunc\s*\(\s*"([^"]+)"', re.DOTALL),
    "raw_time_or_uuid": re.compile(r'\b(?:CelConst|Value)\s*\(\s*"(sys\.now\(\)|uuid\.generate\(\))"', re.DOTALL),
    "call_target_literal": re.compile(
        r'"(http\.(?:get|post|patch|delete)|sys\.log|events\.(?:create_callback_endpoint|await_callback)|'
        r'googleapis\.[^"]+)"'
    ),
}
def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()

def inventory(root: Path) -> dict:
    result = {"format_version": 1,
              "limitations": "Static literal scan only. AST traversal, tests, semantic parity, source-tree/dependency resolution and baseline latency remain necessary.",
              "repositories": {}, "occurrences": []}
    for name in REPOSITORIES:
        repo = root / name
        if not (repo / ".git").exists():
            raise SystemExit("Missing checkout: " + str(repo))
        result["repositories"][name] = {
            "commit": git(repo, "rev-parse", "HEAD"),
            "tree": git(repo, "rev-parse", "HEAD^{tree}"),
            "working_tree_clean": not bool(git(repo, "status", "--porcelain")),
            "build_sbt_sha256": sha256((repo / "build.sbt").read_bytes()) if (repo / "build.sbt").exists() else None
        }
    for name in SCALA_REPOSITORIES:
        repo = root / name
        files = sorted((repo / "src").rglob("*.scala"))
        result["repositories"][name]["scala_file_count"] = len(files)
        for path in files:
            source = path.read_text(encoding="utf-8")
            for kind, pattern in PATTERNS.items():
                for match in pattern.finditer(source):
                    lineno = source.count("\n", 0, match.start()) + 1
                    result["occurrences"].append({
                        "repository": name, "path": str(path.relative_to(repo)),
                        "line": lineno, "kind": kind, "literal": match.group(1)
                    })
    result["occurrences"].sort(key=lambda d: (d["repository"], d["path"], d["line"], d["kind"], d["literal"]))
    result["counts"] = {
        name: len([o for o in result["occurrences"] if o["kind"] == name])
        for name in PATTERNS
    }
    result["distinct_function_literals"] = sorted({
        item["literal"] for item in result["occurrences"] if item["kind"] == "cel_function_literal"
    })
    result["distinct_call_target_literals"] = sorted({
        item["literal"] for item in result["occurrences"] if item["kind"] == "call_target_literal"
    })
    registry = (root / "dsl" / "src/main/scala/us/awfl/dsl/Operations.scala").read_text(encoding="utf-8")
    registered_functions = set(re.findall(r'extends FunctionId\("[^"]+", "([^"]+)"\)', registry))
    registered_calls = set(re.findall(r'extends CallId\("[^"]+", "([^"]+)"\)', registry))
    result["unregistered_function_literals"] = sorted(set(result["distinct_function_literals"]) - registered_functions)
    result["unregistered_call_target_literals"] = sorted(set(result["distinct_call_target_literals"]) - registered_calls)
    return result

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    payload = json.dumps(inventory(args.root.resolve()), indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
        print("Wrote " + str(args.output))
    else:
        print(payload, end="")
