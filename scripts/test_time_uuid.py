#!/usr/bin/env python3
"""Execute real DSL-compiled sys.now and uuid.generate calls without a special journal."""
import json
import subprocess
import time
import tomllib
import uuid
from pathlib import Path
from native_test_paths import native_binary

root = Path(__file__).resolve().parents[1]
crate = root / "fixtures/native-time-uuid"
with (crate / "Cargo.toml").open("rb") as f:
    name = tomllib.load(f)["package"]["name"]
binary = native_binary(crate, name)

def execute():
    before = time.time()
    out = subprocess.check_output(
        [str(binary)],
        input=json.dumps({"protocol_version": 2, "input": {}}).encode(),
        timeout=20,
    )
    after = time.time()
    response = json.loads(out)
    assert response["status"] == "completed", response
    result = response["result"]
    for key in ("now1", "now2"):
        assert isinstance(result[key], float), result
        assert before - 2 <= result[key] <= after + 2, result
    for key in ("id1", "id2"):
        parsed = uuid.UUID(result[key])
        assert str(parsed) == result[key]
        assert parsed.version == 4 and parsed.variant == uuid.RFC_4122
    assert result["id1"] != result["id2"], result
    return result

first = execute()
second = execute()
assert first["id1"] != second["id1"]
print("TIME UUID: independently evaluated sys.now and RFC 4122 UUIDv4: PASS")
