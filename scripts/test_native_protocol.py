#!/usr/bin/env python3
"""Real compiled-native IPC suspend/resume conformance (no network effects).

These checks pass the recorded effect value back to the next native invocation.
The server, not this binary, is responsible for authentication, durable journal
transactions, idempotency and cancellation.
"""
import copy
import json
import os
import subprocess
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ["native-effect", "native-resume", "native-two-effects", "native-range-effect"]


def invoke(executable, payload, *, fails=False):
    result = subprocess.run(
        [str(executable)], input=json.dumps(payload) + "\n",
        text=True, capture_output=True, timeout=15,
    )
    if fails:
        assert result.returncode != 0, (result.stdout, result.stderr)
        return result.stderr
    assert result.returncode == 0, (result.stdout, result.stderr)
    return json.loads(result.stdout)


def bind(cont, slot, value):
    resumed = copy.deepcopy(cont)
    resumed["slots"][slot] = {"type": "string", "value": value}
    return resumed


def check():
    with tempfile.TemporaryDirectory(prefix="awfl-rust-ipc-") as target:
        binaries = {}
        for fixture in FIXTURES:
            manifest = ROOT / "fixtures" / fixture / "Cargo.toml"
            assert manifest.is_file(), f"run Scala ControlFlowSmoke first: {fixture}"
            with manifest.open("rb") as file:
                package = tomllib.load(file)["package"]["name"]
            subprocess.run(
                ["cargo", "build", "--quiet", "--locked",
                 "--manifest-path", str(manifest), "--target-dir", target],
                check=True, env=os.environ.copy(),
            )
            binaries[fixture] = Path(target) / "debug" / package

        single = binaries["native-effect"]
        start = invoke(single, {"protocol_version": 1, "input": 1})
        assert start["protocol_version"] == 1
        assert start["status"] == "suspended"
        assert start["effect"] == {
            "effect_id": "call_0", "node_id": "0.0", "operation": "http.post",
            "result_slot": "s0", "args": {"url": "https://example.org"},
        }
        assert start["continuation"] == {"next_step": 1, "slots": {}, "frames": []}
        complete = invoke(single, {
            "protocol_version": 1, "input": 1,
            "continuation": bind(start["continuation"], "s0", "from-server"),
        })
        assert complete == {
            "protocol_version": 1, "status": "completed", "result": "from-server",
        }

        previous = binaries["native-resume"]
        first = invoke(previous, {"protocol_version": 1, "input": 9})
        assert first["status"] == "suspended"
        assert first["continuation"]["slots"]["s0"] == {"type": "integer", "value": 8}
        assert first["effect"]["effect_id"] == "call_1"
        restored = bind(first["continuation"], "s1", "outcome")
        # Simulate a changed process input: previous branch is skipped and its
        # persisted slot is restored, not recomputed. The real server pins input.
        after = invoke(previous, {
            "protocol_version": 1, "input": -9, "continuation": restored,
        })
        assert after["status"] == "completed" and after["result"] == "outcome"

        invalid = copy.deepcopy(restored)
        del invalid["slots"]["s0"]
        assert "missing continuation slot" in invoke(previous, {
            "protocol_version": 1, "input": 9, "continuation": invalid,
        }, fails=True)
        assert "unsupported native protocol version" in invoke(single, {
            "protocol_version": 2, "input": 1,
        }, fails=True)
        assert "outside compiled workflow" in invoke(previous, {
            "protocol_version": 1, "input": 1,
            "continuation": {"next_step": 99, "slots": {}, "frames": []},
        }, fails=True)

        two = binaries["native-two-effects"]
        a = invoke(two, {"protocol_version": 1, "input": None})
        assert a["effect"]["effect_id"] == "call_0"
        b = invoke(two, {
            "protocol_version": 1, "input": None,
            "continuation": bind(a["continuation"], "s0", "first-ok"),
        })
        assert b["status"] == "suspended"
        assert b["effect"]["effect_id"] == "call_1"
        assert b["continuation"]["slots"]["s0"] == {
            "type": "string", "value": "first-ok",
        }
        c = invoke(two, {
            "protocol_version": 1, "input": None,
            "continuation": bind(b["continuation"], "s1", "second-ok"),
        })
        assert c == {
            "protocol_version": 1, "status": "completed", "result": "second-ok",
        }

        # An effect inside ForRange suspends once per iteration. The native
        # binary is a new process every time, and completed values must be
        # restored from the continuation rather than replayed or discarded.
        ranged = binaries["native-range-effect"]
        current = invoke(ranged, {"protocol_version": 1, "input": None})
        for iteration, result in enumerate(("first", "second", "third")):
            assert current["status"] == "suspended", current
            assert current["effect"]["effect_id"].startswith("call_0_s")
            assert current["effect"]["effect_id"].endswith("_" + str(iteration))
            assert current["continuation"]["next_step"] == 0
            assert current["continuation"]["frames"] == [
                {"node_id": "0.0", "iteration": iteration}
            ]
            slots = current["continuation"]["slots"]
            prefix = slots["__awfl_loop_0_items"]
            assert prefix["type"] == "list"
            assert [v["value"] for v in prefix["value"]] == \
                list(("first", "second", "third")[:iteration])
            if iteration == 1:
                invalid = copy.deepcopy(current["continuation"])
                invalid["slots"]["__awfl_loop_0_items"]["value"] = []
                assert "invalid native range continuation prefix" in invoke(ranged, {
                    "protocol_version": 1, "input": None,
                    "continuation": bind(invalid, current["effect"]["result_slot"], result),
                }, fails=True)
                wrong_frame = copy.deepcopy(current["continuation"])
                wrong_frame["frames"][0]["node_id"] = "0.wrong"
                assert "invalid native continuation frame target" in invoke(ranged, {
                    "protocol_version": 1, "input": None,
                    "continuation": bind(wrong_frame, current["effect"]["result_slot"], result),
                }, fails=True)
            current = invoke(ranged, {
                "protocol_version": 1, "input": None,
                "continuation": bind(current["continuation"],
                                     current["effect"]["result_slot"], result),
            })
        assert current == {
            "protocol_version": 1, "status": "completed",
            "result": ["first", "second", "third"],
        }, current
        print("native protocol: 2 single-effect resumes, 2-step effect sequence,"
              " three-iteration native range resume, malformed continuation"
              " and version rejection passed")


if __name__ == "__main__":
    check()
