#!/usr/bin/env python3
"""Execute Scala-generated Rust branch and loop fixtures (not a graph interpreter)."""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
from contextlib import nullcontext
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = [
    ("native-switch", [(8, 100), (1, 200), (-3, 200)]),
    ("native-unmatched", [(11, 100), (3, {})]),
    ("native-range", [(None, [8, 9, 10, 11])]),
    ("native-each", [([1, 2, -3], [4, 5, 0]), ([], [])]),
    ("native-nested", [(None, [2, 3, 9, 9])]),
    ("native-fold", [([1, 2, 3], 16), ([], 10), ([-4, 5], 11)]),
    ("native-early-return", [(3, 777), (0, 42), (-2, 42)]),
    ("native-top-return", [(None, 55)]),
]


def run() -> None:
    with (nullcontext(os.environ["CARGO_TARGET_DIR"])
          if os.environ.get("CARGO_TARGET_DIR")
          else tempfile.TemporaryDirectory(prefix="awfl-rust-control-target-")) as dirname:
        for fixture, tests in CASES:
            manifest = ROOT / "fixtures" / fixture / "Cargo.toml"
            if not manifest.is_file():
                raise RuntimeError(
                    f"Missing Scala-generated fixture {fixture}; first run ControlFlowSmoke"
                )
            with manifest.open("rb") as cargo_file:
                package = tomllib.load(cargo_file)["package"]["name"]
            # Each generated crate has a distinct package/bin name.
            # Cargo may therefore reuse one shared build target safely.
            target_dir = dirname
            command = [
                "cargo", "build", "--quiet",
                "--manifest-path", str(manifest),
                "--target-dir", target_dir,
            ]
            subprocess.run(command, check=True, env=os.environ.copy())
            binary = Path(target_dir) / "debug" / package
            for input_value, expected in tests:
                completed = subprocess.run(
                    [str(binary)], input=json.dumps({"protocol_version": 2, "input": input_value}) + "\n",
                    text=True, capture_output=True, check=True
                )
                observed = json.loads(completed.stdout)
                assert observed == {"protocol_version": 2, "status": "completed", "result": expected}, (
                    f"{fixture}({input_value!r}) expected {expected!r}; got {observed!r}"
                )
            print(f"{fixture}: {len(tests)} native assertions passed")


if __name__ == "__main__":
    run()
