#!/usr/bin/env python3
"""Original DSL object-to-string templates must not read placeholder variables."""

import json
import subprocess
import tomllib
from pathlib import Path
from native_test_paths import native_binary

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "fixtures/native-schema-text"
with (CRATE / "Cargo.toml").open("rb") as stream:
    package = tomllib.load(stream)["package"]["name"]
binary = native_binary(CRATE, package)

for data in ({}, {"example": {"title": "should-not-substitute", "summary": "also-not"}}):
    response = json.loads(subprocess.check_output(
        [str(binary)],
        input=json.dumps({"protocol_version": 2, "input": data}).encode(),
        timeout=20,
    ))
    assert response["status"] == "completed", response
    template = json.loads(response["result"])
    assert template == {"title": "${example.title}", "summary": "${example.summary}"}, template

print("OBJECT_STRING_NATIVE_RUNTIME_PASS")
