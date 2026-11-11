#!/usr/bin/env python3
"""Native executable tests for existing DSL `in` and `//` CEL operators."""
import json
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "fixtures/native-cel-operators"
with (CRATE / "Cargo.toml").open("rb") as fh:
    package = tomllib.load(fh)["package"]["name"]
binary = CRATE / "target/debug" / package


def invoke(value):
    p = subprocess.run([str(binary)], input=json.dumps({
        "protocol_version": 2, "input": value,
    }), capture_output=True, text=True, timeout=20)
    return p


for number, expected_runtime_floor, expected_member in ((2, -5, True), (-2, 4, False)):
    process = invoke(number)
    assert process.returncode == 0, (process.stdout, process.stderr)
    result = json.loads(process.stdout)
    assert result == {"protocol_version": 2, "status": "completed", "result": {
        "positiveFloor": 2,
        "negativeFloor": -3,
        "negativeDivisorFloor": -3,
        "bothNegative": 2,
        "runtimeFloor": expected_runtime_floor,
        "listMember": expected_member,
        "nestedListMember": True,
        "mapMember": True,
        "mapMissing": False,
    }}, result
    assert invoke(number).stdout == process.stdout, "non-deterministic output"

failure = invoke(0)
assert failure.returncode != 0, "division by zero must fail"
assert "division by zero" in failure.stderr, failure.stderr
print("CEL OPERATORS: map/list in, signed floor division, deterministic replay, divide-by-zero: PASS")
