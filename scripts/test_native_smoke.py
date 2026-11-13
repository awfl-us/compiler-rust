#!/usr/bin/env python3
"""End-to-end Scala-DSL generated executable smoke test."""
import json
import subprocess
import tomllib
from pathlib import Path
from native_test_paths import native_binary

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "fixtures/native-smoke"
with (CRATE / "Cargo.toml").open("rb") as manifest:
    package = tomllib.load(manifest)["package"]["name"]
BINARY = native_binary(CRATE, package)
for value, expected in ((5, 12), (0, 7), (-4, 3)):
    out = subprocess.check_output([str(BINARY)], input=json.dumps({
        "protocol_version": 2, "input": value,
    }).encode())
    parsed = json.loads(out)
    assert parsed == {"protocol_version": 2, "status": "completed",
                      "result": {"name": "native", "score": expected, "valid": True}}, parsed
print("Compiled native workflow passed three independent inputs")
