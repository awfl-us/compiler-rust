#!/usr/bin/env python3
"""Real generated Rust: original wip-old JSON candidate matching semantics.

No HTTP calls are sent; effect continuations use an injected local result.
"""
from __future__ import annotations

import copy
import json
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "target" / "adt-matching"
NORMAL = [
    ({"sessionId": "s1", "end": 4.0, "windowSeconds": 120.0}, "s1", "s1@segment"),
    ({"sessionId": "s2", "sessionEnd": 16.0}, "s2", "s2@session"),
    ({"weekEnd": 12.0}, "global", "week:12"),
    ({"begin": 1.0, "end": 20.0}, "global", "term:20"),
]
MALFORMED = [
    {},
    {"sessionId": "unknown"},
    {"weekEnd": 12.0, "extra": True},
    {"end": 4.0, "weekEnd": 5.0},
]


def native_binary(fixture: str) -> Path:
    manifest = ROOT / "fixtures" / fixture / "Cargo.toml"
    assert manifest.exists(), f"Generate {fixture} using AdtMatchNativeSmoke first"
    with manifest.open("rb") as file:
        package = tomllib.load(file)["package"]["name"]
    subprocess.run(["cargo", "build", "--quiet", "--manifest-path", str(manifest),
                    "--target-dir", str(TARGET)], check=True)
    return TARGET / "debug" / package


def request(binary: Path, value: object, continuation: dict | None = None) -> dict:
    payload = {"protocol_version": 2, "input": value}
    if continuation is not None:
        payload["continuation"] = continuation
    completed = subprocess.run([str(binary)], input=json.dumps(payload) + "\n",
                               capture_output=True, text=True, check=True)
    response = json.loads(completed.stdout)
    assert response["protocol_version"] == 2, response
    return response


def check() -> None:
    count = 0
    for fixture, result_idx in (("native-adt-match-session", 1),
                                ("native-adt-match-typed", 2)):
        binary = native_binary(fixture)
        for value, *expected in NORMAL:
            observed = request(binary, value)
            assert observed == {"protocol_version": 2, "status": "completed",
                                "result": expected[result_idx - 1]}, (fixture, value, observed)
            count += 1
        for value in MALFORMED:
            observed = request(binary, value)
            assert observed["status"] == "failed", (fixture, value, observed)
            assert "Non-exhaustive ADT match" in observed["error"]["message"]
            count += 1
        print(f"{fixture}: {len(NORMAL)} normal + {len(MALFORMED)} failures")

    priority = native_binary("native-adt-match-priority")
    for value, expected in (({"weekEnd": 12.0}, "specific-first"),
                            (NORMAL[0][0], "fallback"),
                            ({}, "fallback")):
        observed = request(priority, value)
        assert observed["status"] == "completed" and observed["result"] == expected, observed
        count += 1
    print("native-adt-match-priority: 3 source-order/wildcard assertions")

    effects = native_binary("native-adt-match-effects")
    for value in (NORMAL[1][0], NORMAL[2][0], NORMAL[3][0]):
        observed = request(effects, value)
        assert observed["status"] == "completed" and observed["result"] == "without-effect", observed
        count += 1
    started = request(effects, NORMAL[0][0])
    assert started["status"] == "suspended", started
    assert started["effect"]["operation"] == "http.post", started
    assert started["effect"]["args"]["url"] == "https://example.org/adt-matching"
    count += 1
    checkpoint = copy.deepcopy(started["continuation"])
    assert checkpoint["frames"] == []
    checkpoint["slots"][started["effect"]["result_slot"]] = {
        "type": "string", "value": "mock-effect-result"
    }
    finished = request(effects, NORMAL[0][0], checkpoint)
    assert finished["status"] == "completed" and finished["result"] == "mock-effect-result", finished
    count += 1
    print("native-adt-match-effects: 3 bypasses, 1 suspension, 1 continuation")

    literal = native_binary("native-adt-match-literal")
    literal_cases = (
        ({"role": "admin", "id": "a"}, "a@admin"),
        ({"role": "admin", "id": "another"}, "another@admin"),
        ({"role": "user", "id": "u"}, "user"),
        ({"role": "guest", "id": "x"}, "other"),
        ({"reason": "admin", "id": "deleted"}, "deleted"),
        ({"reason": "user", "id": "x"}, "other"),
        ({}, "other"),
    )
    for value, expected in literal_cases:
        observed = request(literal, value)
        assert observed["status"] == "completed" and observed["result"] == expected, (value, observed)
        count += 1
    print(f"native-adt-match-literal: {len(literal_cases)} value-sensitive patterns")
    print(f"NATIVE_ADT_JSON_MATCH_PASS {count} assertions")


if __name__ == "__main__":
    check()
