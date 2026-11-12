#!/usr/bin/env python3
"""Process-level native conversion/Unicode/error/replay checks. No remote effects."""
import json
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "fixtures/native-conversion"
with (CRATE / "Cargo.toml").open("rb") as f:
    binary_name = tomllib.load(f)["package"]["name"]
BINARY = CRATE / "target/debug" / binary_name


def invoke(input_data):
    return subprocess.run([str(BINARY)], input=json.dumps({
        "protocol_version": 2, "input": input_data
    }), text=True, capture_output=True, timeout=20)


sample = {
    "intText": "-102", "double": -2.9,
    "integer": 42, "boolean": False,
    "text": "ÉCOLE İSTANBUL",
}
result = invoke(sample)
assert result.returncode == 0, (result.stdout, result.stderr)
body = json.loads(result.stdout)
assert body == {"protocol_version": 2, "status": "completed", "result": {
    "intText": -102, "intDouble": -2, "stringInt": "42",
    "stringDouble": "-2.9", "stringBool": "false",
    "lower": "école i̇stanbul",
}}, body
assert invoke(sample).stdout == result.stdout, "pure operations must replay deterministically"

for key, bad in [
    ("intText", "9223372036854775808"),
    ("intText", "12.5"),
    ("intText", True),
    ("double", 9223372036854775808.0),
    ("integer", None),
    ("text", 123),
]:
    modified = dict(sample, **{key: bad})
    response = invoke(modified)
    assert response.returncode != 0 or json.loads(response.stdout).get("status") == "failed", (
        f"{key}={bad!r} must fail, not silently coerce", response.stdout, response.stderr
    )

print("NATIVE_CONVERSION_PROCESS_PASS: numeric conversion, Unicode, bounds, types, replay")
