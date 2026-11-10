#!/usr/bin/env python3
"""End-to-end Scala-DSL generated executable smoke test."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "fixtures/native-smoke/target/debug/awfl-generated-workflow"
for value, expected in ((5, 12), (0, 7), (-4, 3)):
    out = subprocess.check_output([str(BINARY)], input=f"{value}\n".encode())
    parsed = json.loads(out)
    assert parsed == {"name": "native", "score": expected, "valid": True}, parsed
print("Compiled native workflow passed three independent inputs")
