#!/usr/bin/env python3
"""Executes the Scala-generated CEL standard-library Rust crate on real inputs.

Tests exact observable JSON and type semantics, not just presence of Rust code.
"""
import json
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "fixtures/native-cel-stdlib"
with (CRATE / "Cargo.toml").open("rb") as f:
    name = tomllib.load(f)["package"]["name"]
BINARY = CRATE / "target/debug" / name


def execute(data):
    p = subprocess.run([str(BINARY)], input=json.dumps({
        "protocol_version": 2, "input": data,
    }), text=True, capture_output=True, timeout=20)
    assert p.returncode == 0, (p.stdout, p.stderr)
    return json.loads(p.stdout)


def check():
    left = {"a": 1, "nest": {"left": True}}
    right = {"b": 2, "nest": {"right": True}}
    inp = {
        "nested": {"present": 7, "outer": {"answer": 42}},
        "left": left, "right": right,
        "items": [1], "append": [3, 4],
        "json": '{"status":"ok","count":2}',
        "urlText": "a/b/c?item=d+e f~é🙂",
    }
    result = execute(inp)
    assert result["protocol_version"] == 2, result
    assert result["status"] == "completed", result
    assert result["result"] == {
        "present": 7, "missing": None, "nested": 42,
        "fallback": "fallback", "keepFalse": False, "typeName": "list",
        "merged": {"a": 1, "b": 2, "nest": {"right": True}},
        "appended": [1, [3, 4]],
        "decoded": {"status": "ok", "count": 2},
        "encoded": '{"a":1,"nest":{"left":true}}',
        "bytesRoundtrip": left,
        "urlEncoded": "a%2Fb%2Fc%3Fitem%3Dd%2Be%20f%7E%C3%A9%F0%9F%99%82",
    }, result
    assert execute(inp) == result, "pure CEL result must be deterministic"

    invalid = dict(inp, json="{bad json}")
    p = subprocess.run([str(BINARY)], input=json.dumps({
        "protocol_version": 2, "input": invalid,
    }), text=True, capture_output=True, timeout=20)
    assert p.returncode != 0, "invalid JSON must not silently become null"
    print("CEL STDLIB: 9 compiled functions, safe map paths, shallow merge, "
          "single-element list append, JSON, type, replay, bad JSON: PASS")


if __name__ == "__main__":
    check()
