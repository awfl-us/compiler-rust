#!/usr/bin/env python3
"""Process-level test of actual Scala-emitted native text.substring, not YAML."""
import json
import subprocess
import tomllib
from pathlib import Path
from native_test_paths import native_binary

root = Path(__file__).resolve().parents[1] / "fixtures/native-text-substring"
with (root / "Cargo.toml").open("rb") as f:
    name = tomllib.load(f)["package"]["name"]
binary = native_binary(root, name)


def invoke(source, start, end):
    proc = subprocess.run(
        [str(binary)], input=json.dumps({
            "protocol_version": 2,
            "input": {"source": source, "start": start, "end": end},
        }), text=True, capture_output=True, timeout=20,
    )
    return proc, json.loads(proc.stdout) if proc.stdout else {}


for source, start, end, expected in [
    ("hello", 2, 4, "ll"),
    ("hello", -10, 10, "hello"),
    ("hello", -3, -1, "ll"),
    ("a🙂éZ", 1, 3, "🙂é"),
    ("hello", 4, 2, ""),
    ("", -1, 10, ""),
    ("hello", -(2**63), 2**63 - 1, "hello"),
]:
    proc, result = invoke(source, start, end)
    assert proc.returncode == 0, (proc.stdout, proc.stderr)
    assert result == {"protocol_version": 2, "status": "completed", "result": expected}, result
    assert invoke(source, start, end)[0].stdout == proc.stdout

for data in [(123, 1, 2), ("hello", 1.5, 3), ("hello", 0, "3")]:
    proc, result = invoke(*data)
    assert proc.returncode != 0 or result.get("status") == "failed", (data, result)

print("NATIVE_TEXT_SUBSTRING_PROCESS_PASS")
