#!/usr/bin/env python3
"""Process-level native Workflows time.format time zone and error checks."""
import json
import subprocess
import tomllib
from pathlib import Path
from native_test_paths import native_binary

root = Path(__file__).resolve().parents[1] / "fixtures/native-time-format"
with (root / "Cargo.toml").open("rb") as f:
    name = tomllib.load(f)["package"]["name"]
binary = native_binary(root, name)


def invoke(timestamp, zone):
    process = subprocess.run(
        [str(binary)], input=json.dumps({
            "protocol_version": 2,
            "input": {"timestamp": timestamp, "zone": zone},
        }), capture_output=True, text=True, timeout=20,
    )
    return process, json.loads(process.stdout) if process.stdout else {}


for timestamp, zone, expected_utc, expected_zone in [
    (1720788633, "Europe/Paris", "2024-07-12T12:50:33.000000Z", "2024-07-12T14:50:33.000000+02:00"),
    (1720788633, "America/New_York", "2024-07-12T12:50:33.000000Z", "2024-07-12T08:50:33.000000-04:00"),
    (0, "America/New_York", "1970-01-01T00:00:00.000000Z", "1969-12-31T19:00:00.000000-05:00"),
    (-0.25, "", "1969-12-31T23:59:59.750000Z", "1969-12-31T23:59:59.750000Z"),
]:
    process, body = invoke(timestamp, zone)
    assert process.returncode == 0, (process.stdout, process.stderr)
    assert body == {"protocol_version": 2, "status": "completed", "result": {
        "utc": expected_utc, "zoned": expected_zone,
    }}, body
    assert invoke(timestamp, zone)[0].stdout == process.stdout

for timestamp, zone in [("10", "UTC"), (False, "UTC"), (0, "No/Such_Zone"), (0, 12)]:
    process, body = invoke(timestamp, zone)
    assert process.returncode != 0 or body.get("status") == "failed", (timestamp, zone, body)

print("NATIVE_TIME_FORMAT_PROCESS_PASS")
