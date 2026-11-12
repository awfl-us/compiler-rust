#!/usr/bin/env python3
"""Run DSL-compiled duplicate-name Calls, simulating server-persisted results."""
import json
import subprocess
import tomllib
from pathlib import Path

crate = Path(__file__).resolve().parents[1] / "fixtures/native-scoped-names"
with (crate / "Cargo.toml").open("rb") as file:
    name = tomllib.load(file)["package"]["name"]
binary = crate / "target/debug" / name


def execute(continuation=None):
    request = {"protocol_version": 2, "input": None}
    if continuation is not None:
        request["continuation"] = continuation
    proc = subprocess.run([str(binary)], input=json.dumps(request),
                          capture_output=True, text=True, timeout=20)
    assert proc.returncode == 0, (proc.stdout, proc.stderr)
    return json.loads(proc.stdout)


first = execute()
assert first["status"] == "suspended", first
assert first["effect"]["node_id"] == "0.0", first
assert first["effect"]["args"]["url"] == "https://example.org/a"
first_slot = first["effect"]["result_slot"]

first_checkpoint = first["continuation"]
first_checkpoint["slots"][first_slot] = {"type": "string", "value": "A"}
second = execute(first_checkpoint)
assert second["status"] == "suspended", second
assert second["effect"]["node_id"] == "0.1", second
assert second["effect"]["args"]["url"] == "https://example.org/b", second
second_slot = second["effect"]["result_slot"]
assert first_slot != second_slot
assert first_slot in second["continuation"]["slots"], second
assert second["continuation"]["slots"][first_slot] == {"type": "string", "value": "A"}

second_checkpoint = second["continuation"]
second_checkpoint["slots"][second_slot] = {"type": "string", "value": "B"}
completed = execute(second_checkpoint)
assert completed == {
    "protocol_version": 2, "status": "completed",
    "result": {"first": "A", "second": "B"},
}, completed
print("SCOPED NAMES: repeated Call labels, distinct durable slots, two resumes: PASS")
