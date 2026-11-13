#!/usr/bin/env python3
"""Run a real generated Rust workflow; untrusted input cannot forge globals."""
import json
import subprocess
import tomllib
from pathlib import Path
from native_test_paths import native_binary

root = Path(__file__).resolve().parents[1]
crate = root / "fixtures/native-trusted-globals"
with (crate / "Cargo.toml").open("rb") as handle:
    binary_name = tomllib.load(handle)["package"]["name"]
binary = native_binary(crate, binary_name)

spoof = {
    "env": {
        "GOOGLE_CLOUD_PROJECT_ID": "spoofed-tenant",
        "GOOGLE_CLOUD_WORKFLOW_ID": "spoofed-workflow",
        "GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID": "spoofed-execution",
        "GOOGLE_CLOUD_LOCATION": "spoofed-region",
    }
}
trusted = {
    "GOOGLE_CLOUD_PROJECT_ID": "trusted-gcp-project",
    "GOOGLE_CLOUD_WORKFLOW_ID": "workflow-pinned-by-server",
    "GOOGLE_CLOUD_WORKFLOW_EXECUTION_ID": "native_trusted1234",
    "GOOGLE_CLOUD_LOCATION": "us-central1",
}


def execute(context=None):
    request = {"protocol_version": 2, "input": spoof}
    if context is not None:
        request["trusted_globals"] = context
    return subprocess.run(
        [str(binary)], input=json.dumps(request),
        text=True, capture_output=True, timeout=20,
    )


accepted = execute(trusted)
assert accepted.returncode == 0, (accepted.stdout, accepted.stderr)
observed = json.loads(accepted.stdout)
assert observed == {
    "protocol_version": 2,
    "status": "completed",
    "result": {
        "workflow": "workflow-pinned-by-server",
        "execution": "native_trusted1234",
        "project": "trusted-gcp-project",
        "region": "us-central1",
        "fromEnv": "native_trusted1234",
        "fallback": "default",
        "requestInput": spoof,
    },
}, observed

# No trusted envelope is NOT authorization to read input.env or std::env.
absent = execute()
assert absent.returncode != 0, absent.stdout
assert "trusted global" in absent.stderr, absent.stderr

# Strict schema cannot be tricked into injecting arbitrary process environment.
invalid = dict(trusted, PATH="/tmp/injected")
rejected = execute(invalid)
assert rejected.returncode != 0, rejected.stdout
assert "invalid trusted native execution globals" in rejected.stderr, rejected.stderr

incomplete = dict(trusted)
del incomplete["GOOGLE_CLOUD_WORKFLOW_ID"]
rejected = execute(incomplete)
assert rejected.returncode != 0, rejected.stdout

print("TRUSTED_GLOBALS: four server globals, env lookups, spoof resistance and strict schema PASS")
