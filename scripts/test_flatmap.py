#!/usr/bin/env python3
"""Run generated Rust FlatMap: one effect, deterministic suspension, projected resume."""
import copy
import json
import subprocess
import sys
import tomllib
from pathlib import Path

target = Path(sys.argv[1])
manifest = Path(__file__).resolve().parents[1] / "fixtures/native-flatmap/Cargo.toml"
with manifest.open("rb") as source:
    package = tomllib.load(source)["package"]["name"]
binary = target / "debug" / package
assert binary.exists(), f"missing generated FlatMap binary: {binary}"

payload = {"protocol_version": 2, "input": {}}

def invoke(continuation=None):
    p = copy.deepcopy(payload)
    if continuation is not None:
        p["continuation"] = continuation
    result = subprocess.run([str(binary)], text=True, input=json.dumps(p),
                            capture_output=True, timeout=20)
    assert result.returncode == 0, (result.stdout, result.stderr)
    return json.loads(result.stdout)

first = invoke()
assert first["status"] == "suspended", first
assert first["effect"]["operation"] == "http.post", first
assert first["effect"]["node_id"] == "0.0.0", first
assert first == invoke(), "deterministic effect suspension"
checkpoint = copy.deepcopy(first["continuation"])
checkpoint["slots"][first["effect"]["result_slot"]] = {"type": "string", "value": "hello"}
done = invoke(checkpoint)
assert done == {"protocol_version": 2, "status": "completed", "result": "hello!"}, done
assert done == invoke(checkpoint), "deterministic replay of projected value"
print("FLATMAP_NATIVE_PASS: one HTTP effect, persisted projection, replay")
