#!/usr/bin/env python3
"""ABI 3 / v2 real-Rust resume conformance: no network or external effects.

Every suspension is completed by binding the result to the checkpoint as the
AWFL server would. Fresh subprocesses execute each native transition.
"""
import copy
import json
import os
import subprocess
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = (
    "native-v2-switch-effects",
    "native-v2-nested-switch-effects",
    "native-v2-range-two-effects",
    "native-v2-nested-range-effects",
    "native-v2-each",
    "native-v2-fold",
    "native-v2-each-effects",
    "native-v2-fold-effects",
    "native-v2-range-switch-effects",
    "native-v2-pure-nested",
    "native-v2-deep-switch-effects",
    "native-v2-mixed-nested-range",
)


def run(binary, payload, *, fails=False):
    proc = subprocess.run(
        [str(binary)], input=json.dumps(payload), text=True,
        capture_output=True, timeout=20,
    )
    if fails:
        assert proc.returncode != 0, (proc.stdout, proc.stderr)
        return proc.stderr
    assert proc.returncode == 0, (proc.stdout, proc.stderr)
    result = json.loads(proc.stdout)
    assert result["protocol_version"] == 2, result
    return result


def request(binary, value, continuation=None):
    return run(binary, {"protocol_version": 2, "input": value,
                        **({"continuation": continuation} if continuation else {})})


def bind(response, outcome):
    checkpoint = copy.deepcopy(response["continuation"])
    assert checkpoint["frames"] == []
    checkpoint["slots"][response["effect"]["result_slot"]] = {
        "type": "string", "value": outcome,
    }
    return checkpoint


def check():
    with tempfile.TemporaryDirectory(prefix="awfl-native-v2-") as target:
        binaries = {}
        for fixture in FIXTURES:
            manifest = ROOT / "fixtures" / fixture / "Cargo.toml"
            assert manifest.is_file(), f"generate {fixture} with Scala ControlFlowSmoke"
            with manifest.open("rb") as file:
                package = tomllib.load(file)["package"]["name"]
            subprocess.run([
                "cargo", "build", "--quiet", "--manifest-path", str(manifest),
                "--target-dir", target,
            ], check=True, env=os.environ.copy())
            binaries[fixture] = Path(target) / "debug" / package

        switch = binaries["native-v2-switch-effects"]
        first = request(switch, 5)
        assert first["status"] == "suspended"
        assert first["continuation"]["next_step"] >= 0
        assert first["effect"]["node_id"] == "0.0.case0.0"
        second = request(switch, -5, bind(first, "first"))
        assert second["status"] == "suspended"
        assert second["effect"]["args"] == "first"
        assert second["effect"]["node_id"] == "0.0.case0.1"
        assert second["effect"]["effect_id"] != first["effect"]["effect_id"]
        assert request(switch, -5, bind(second, "second"))["result"] == "second"
        assert request(switch, -1)["effect"]["node_id"] == "0.0.case1.0"
        assert request(switch, 0)["result"] == {}

        corrupt = bind(first, "first")
        corrupt["frames"] = [{"node_id": "0.0", "iteration": 0}]
        assert "frames must be empty" in run(switch, {
            "protocol_version": 2, "input": 5, "continuation": corrupt,
        }, fails=True)
        corrupt = bind(first, "first")
        corrupt["next_step"] = 9999
        assert "outside compiled workflow" in run(switch, {
            "protocol_version": 2, "input": 5, "continuation": corrupt,
        }, fails=True)
        assert "unsupported native protocol version" in run(switch, {
            "protocol_version": 1, "input": 5,
        }, fails=True)

        nested = binaries["native-v2-nested-switch-effects"]
        first = request(nested, 5)
        assert first["effect"]["node_id"] == "0.0.case0.0.case0.0"
        second = request(nested, -5, bind(first, "nested-first"))
        assert second["effect"]["node_id"] == "0.0.case0.0.case0.1"
        assert second["effect"]["args"] == "nested-first"
        assert request(nested, -5, bind(second, "nested-second"))["result"] == "nested-second"
        assert request(nested, 2)["effect"]["node_id"] == "0.0.case0.0.case1.0"
        assert request(nested, -5)["result"] == "no-effect"

        ranged = binaries["native-v2-range-two-effects"]
        current = request(ranged, None)
        seen_ids = set()
        results = []
        for iteration in range(2):
            first = current
            assert first["status"] == "suspended"
            first_id = first["effect"]["effect_id"]
            assert first_id not in seen_ids
            seen_ids.add(first_id)
            first_result = f"first-{iteration}"
            second = request(ranged, None, bind(first, first_result))
            assert second["status"] == "suspended"
            assert second["effect"]["args"] == first_result
            second_id = second["effect"]["effect_id"]
            assert second_id not in seen_ids
            seen_ids.add(second_id)
            answer = f"second-{iteration}"
            results.append(answer)
            current = request(ranged, None, bind(second, answer))
        assert current["result"] == results
        assert len(seen_ids) == 4

        nested_range = binaries["native-v2-nested-range-effects"]
        current = request(nested_range, None)
        expected = []
        seen_ids.clear()
        for outer in range(2):
            row = []
            for inner in range(2):
                first = current
                assert first["status"] == "suspended"
                first_id = first["effect"]["effect_id"]
                assert first_id not in seen_ids
                seen_ids.add(first_id)
                first_result = f"first-{outer}-{inner}"
                second = request(nested_range, None, bind(first, first_result))
                assert second["status"] == "suspended"
                assert second["effect"]["args"] == first_result
                second_id = second["effect"]["effect_id"]
                assert second_id not in seen_ids
                seen_ids.add(second_id)
                answer = f"second-{outer}-{inner}"
                row.append(answer)
                current = request(nested_range, None, bind(second, answer))
            expected.append(row)
        assert current["result"] == expected, current
        assert len(seen_ids) == 8

        assert request(binaries["native-v2-each"], [1, 2])["result"] == [4, 5]
        assert request(binaries["native-v2-fold"], [1, 2, 3])["result"] == 16
        each_effects = binaries["native-v2-each-effects"]
        current = request(each_effects, ["a", "b", "c"])
        for i, item in enumerate(["a", "b", "c"]):
            assert current["status"] == "suspended", current
            assert current["effect"]["args"] == item
            assert current["effect"]["effect_id"].endswith("_" + str(i))
            current = request(each_effects, ["changed"], bind(current, "item-" + str(i)))
        assert current["result"] == ["item-0", "item-1", "item-2"]

        fold_effects = binaries["native-v2-fold-effects"]
        current = request(fold_effects, ["a", "b", "c"])
        for i, previous in enumerate(["start", "fold-0", "fold-1"]):
            assert current["status"] == "suspended", current
            assert current["effect"]["args"] == previous
            assert current["effect"]["effect_id"].endswith("_" + str(i))
            current = request(fold_effects, ["changed"], bind(current, "fold-" + str(i)))
        assert current["result"] == "fold-2"

        mixed = binaries["native-v2-range-switch-effects"]
        current = request(mixed, None)
        assert current["status"] == "suspended", current
        assert current["effect"]["node_id"] == "0.0.each.0.case0.0"
        assert current["effect"]["effect_id"].endswith("_1")
        assert request(mixed, None, bind(current, "branch-done"))["result"] == [
            "skipped", "branch-done",
        ]

        assert request(binaries["native-v2-pure-nested"], None)["result"] == [2, 3, 9, 9]

        # A third effectful Switch depth is compiled to CFG blocks (no replay
        # frame limit). Follow its two dependent effects across restarts.
        deep = binaries["native-v2-deep-switch-effects"]
        first = request(deep, 5)
        assert first["effect"]["node_id"] == "0.0.case0.0.case0.0.case0.0"
        second = request(deep, -5, bind(first, "deep-first"))
        assert second["effect"]["args"] == "deep-first"
        assert request(deep, -5, bind(second, "deep-second"))["result"] == "deep-second"
        assert request(deep, -5)["result"] == "no-effect"

        # Two nested range loops are followed by a sibling effect. Completed
        # inner-loop calls must not replay when the sibling effect resumes.
        mixed_nested = binaries["native-v2-mixed-nested-range"]
        current = request(mixed_nested, None)
        seen = []
        rows = []
        for outer in range(2):
            row = []
            for inner in range(2):
                first = current
                assert first["status"] == "suspended"
                seen.append(first["effect"]["effect_id"])
                current = request(mixed_nested, None, bind(first, f"first-{outer}-{inner}"))
                assert current["effect"]["args"] == f"first-{outer}-{inner}"
                seen.append(current["effect"]["effect_id"])
                current = request(mixed_nested, None, bind(current, f"second-{outer}-{inner}"))
                row.append(f"second-{outer}-{inner}")
            rows.append(row)
        assert current["effect"]["node_id"] == "0.0.each.1"
        seen.append(current["effect"]["effect_id"])
        assert request(mixed_nested, None, bind(current, "sibling-ok"))["result"] == [rows]
        assert len(set(seen)) == len(seen)
        print("ABI 3 native v2: Switch, nested Switch, range, nested range,"
              " effectful For/Fold, mixed range+Switch, replay-safe effect IDs"
              " and malformed checkpoints passed")


if __name__ == "__main__":
    check()
