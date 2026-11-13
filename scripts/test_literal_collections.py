#!/usr/bin/env python3
"""End-to-end native ABI 3 executable test for unchanged DSL collection CEL."""
import json
import subprocess
import tomllib
from pathlib import Path
from native_test_paths import native_binary

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "fixtures/native-literal-collections"
with (CRATE / "Cargo.toml").open("rb") as fh:
    package = tomllib.load(fh)["package"]["name"]
binary = native_binary(CRATE, package)

def run():
    response = subprocess.run([str(binary)],
        input=json.dumps({"protocol_version": 2, "input": None}),
        capture_output=True, text=True, timeout=20, check=True)
    return json.loads(response.stdout)

result = run()
assert result == {
    "protocol_version": 2,
    "status": "completed",
    "result": {
        "emptyList": [],
        "emptyMap": {},
        "scalaList": [1, 2, 3],
        "nestedList": [1, "é🙂", None, [True, 2.5]],
        "nestedMap": {"z": {"array": [False, "ok"]}, "a": -12},
        "nestedGet": 7,
    },
}, result
assert run() == result
print("CEL COLLECTIONS: original Scala literal list/map, nested JSON, "
      "unicode, numbers, map.get, deterministic native result: PASS")
