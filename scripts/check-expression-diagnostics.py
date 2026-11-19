#!/usr/bin/env python3
"""Native ABI regression for compiler-generated CEL diagnostics.

Prerequisites (from frontend/):
  sbt -batch 'Test / runMain us.awfl.compiler.rust.TryRaiseSmoke'
Then (from repository root):
  cargo build --manifest-path fixtures/native-try-raise/runtime-divide/Cargo.toml
  cargo build --manifest-path fixtures/native-try-raise/catch-divide/Cargo.toml
  python3 scripts/check-expression-diagnostics.py
"""
import json
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
request = lambda value: json.dumps({"protocol_version": 2, "input": value})


def invoke(name, value):
    folder = root / "fixtures" / "native-try-raise" / name
    manifest = (folder / "Cargo.toml").read_text()
    package = re.search(r'^name = "([^"]+)"', manifest, re.MULTILINE).group(1)
    binary = folder / "target" / "debug" / package
    if not binary.is_file():
        raise AssertionError(f"Missing binary: {binary}; build fixture first")
    return subprocess.run([str(binary)], input=request(value), text=True,
                          capture_output=True, timeout=20)


failed = invoke("runtime-divide", 8.25)
assert failed.returncode != 0, failed.stdout
for token in ("0.return", "divide_int", "Float", "Integer",
              "integer floor division requires two integers"):
    assert token in failed.stderr, (token, failed.stderr)
assert "8.25" not in failed.stderr, "Do not log operand values"

caught = invoke("catch-divide", 8.25)
assert caught.returncode == 0, caught.stderr
result = json.loads(caught.stdout)
assert result["status"] == "completed", result
for token in ("node=0.", "operation=divide_int",
              "left_type=Float", "right_type=Integer"):
    assert token in result["result"], (token, result)
assert "8.25" not in result["result"]

for workflow in ("runtime-divide", "catch-divide"):
    valid = invoke(workflow, 8)
    assert valid.returncode == 0, valid.stderr
    outcome = json.loads(valid.stdout)
    assert outcome["status"] == "completed" and outcome["result"] == 4, outcome

print("NATIVE_EXPRESSION_DIAGNOSTICS_PASS: uncaught, caught, integer-success, no operand values")
