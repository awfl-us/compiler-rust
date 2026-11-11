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
FIXTURES = [
    "native-effect", "native-resume", "native-two-effects",
    "native-range-effect", "native-range-two-effects", "native-switch-effects",
    "native-nested-switch-effects", "native-nested-range-effects",
]


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

        # Two call sites per iteration: the second call receives the first
        # call's recorded result, with unique site+iteration effect IDs.
        multi_range = binaries["native-range-two-effects"]
        current = invoke(multi_range, {"protocol_version": 1, "input": None})
        for iteration in range(2):
            first = current
            assert first["status"] == "suspended", first
            assert first["continuation"]["next_step"] == 0
            assert first["continuation"]["frames"] == [
                {"node_id": "0.0", "iteration": iteration},
                {"node_id": first["effect"]["node_id"], "iteration": 0},
            ]
            assert first["effect"]["effect_id"].endswith("_" + str(iteration))
            first_value = "first-" + str(iteration)
            second = invoke(multi_range, {
                "protocol_version": 1, "input": None,
                "continuation": bind(first["continuation"],
                                     first["effect"]["result_slot"], first_value),
            })
            assert second["status"] == "suspended", second
            assert second["continuation"]["frames"] == [
                {"node_id": "0.0", "iteration": iteration},
                {"node_id": second["effect"]["node_id"], "iteration": 1},
            ]
            assert second["effect"]["effect_id"].endswith("_" + str(iteration))
            assert second["effect"]["effect_id"] != first["effect"]["effect_id"]
            assert second["effect"]["args"] == first_value
            assert second["continuation"]["slots"][first["effect"]["result_slot"]] == {
                "type": "string", "value": first_value,
            }
            if iteration == 0:
                wrong_site = copy.deepcopy(second["continuation"])
                wrong_site["frames"][1]["iteration"] = 99
                assert "invalid native range continuation site" in invoke(multi_range, {
                    "protocol_version": 1, "input": None,
                    "continuation": bind(wrong_site, second["effect"]["result_slot"], "bad"),
                }, fails=True)
                missing_prior = copy.deepcopy(second["continuation"])
                del missing_prior["slots"][first["effect"]["result_slot"]]
                assert "missing continuation slot" in invoke(multi_range, {
                    "protocol_version": 1, "input": None,
                    "continuation": bind(missing_prior, second["effect"]["result_slot"], "bad"),
                }, fails=True)
            current = invoke(multi_range, {
                "protocol_version": 1, "input": None,
                "continuation": bind(second["continuation"],
                                     second["effect"]["result_slot"],
                                     "second-" + str(iteration)),
            })
        assert current == {
            "protocol_version": 1, "status": "completed",
            "result": ["second-0", "second-1"],
        }, current

        # A Switch pins its branch and Call site. Resuming with deliberately
        # different input must NOT take a different branch or repeat a Call.
        switch = binaries["native-switch-effects"]
        first = invoke(switch, {"protocol_version": 1, "input": 5})
        assert first["status"] == "suspended", first
        assert first["effect"]["node_id"] == "0.0.case0.0"
        assert first["continuation"]["frames"] == [
            {"node_id": "0.0", "iteration": 0},
            {"node_id": "0.0.case0.0", "iteration": 0},
        ]
        assert first["continuation"]["next_step"] == 0
        second = invoke(switch, {
            "protocol_version": 1, "input": -5,
            "continuation": bind(first["continuation"],
                                 first["effect"]["result_slot"], "branch-a-first"),
        })
        assert second["status"] == "suspended", second
        assert second["effect"]["node_id"] == "0.0.case0.1"
        assert second["effect"]["args"] == "branch-a-first"
        assert second["continuation"]["frames"] == [
            {"node_id": "0.0", "iteration": 0},
            {"node_id": "0.0.case0.1", "iteration": 1},
        ]
        assert first["effect"]["effect_id"] != second["effect"]["effect_id"]
        assert second["continuation"]["slots"][first["effect"]["result_slot"]] == {
            "type": "string", "value": "branch-a-first",
        }
        invalid_site = copy.deepcopy(second["continuation"])
        invalid_site["frames"][0]["iteration"] = 1
        assert "invalid native Switch continuation site" in invoke(switch, {
            "protocol_version": 1, "input": -5,
            "continuation": bind(invalid_site, second["effect"]["result_slot"], "bad"),
        }, fails=True)
        invalid_prior = copy.deepcopy(second["continuation"])
        del invalid_prior["slots"][first["effect"]["result_slot"]]
        assert "missing continuation slot" in invoke(switch, {
            "protocol_version": 1, "input": -5,
            "continuation": bind(invalid_prior, second["effect"]["result_slot"], "bad"),
        }, fails=True)
        final = invoke(switch, {
            "protocol_version": 1, "input": -5,
            "continuation": bind(second["continuation"],
                                 second["effect"]["result_slot"], "branch-a-second"),
        })
        assert final == {
            "protocol_version": 1, "status": "completed", "result": "branch-a-second",
        }, final
        other = invoke(switch, {"protocol_version": 1, "input": -1})
        assert other["status"] == "suspended" and other["effect"]["node_id"] == "0.0.case1.0"
        assert other["continuation"]["frames"] == [
            {"node_id": "0.0", "iteration": 1},
            {"node_id": "0.0.case1.0", "iteration": 0},
        ]
        other_done = invoke(switch, {
            "protocol_version": 1, "input": 999,
            "continuation": bind(other["continuation"],
                                 other["effect"]["result_slot"], "branch-b"),
        })
        assert other_done["result"] == "branch-b"
        unmatched = invoke(switch, {"protocol_version": 1, "input": 0})
        assert unmatched["status"] == "completed" and unmatched["result"] == {}

        # Two nested Switches: resume must pin both branches and the Call
        # site even if process input changes to select the no-effect path.
        nested_switch = binaries["native-nested-switch-effects"]
        first = invoke(nested_switch, {"protocol_version": 1, "input": 5})
        assert first["status"] == "suspended", first
        assert first["effect"]["node_id"] == "0.0.case0.0.case0.0"
        assert first["continuation"]["frames"] == [
            {"node_id": "0.0", "iteration": 0},
            {"node_id": "0.0.case0.0", "iteration": 0},
            {"node_id": first["effect"]["node_id"], "iteration": 0},
        ]
        second = invoke(nested_switch, {
            "protocol_version": 1, "input": -100,
            "continuation": bind(first["continuation"],
                                 first["effect"]["result_slot"], "nested-first"),
        })
        assert second["status"] == "suspended", second
        assert second["effect"]["node_id"] == "0.0.case0.0.case0.1"
        assert second["effect"]["args"] == "nested-first"
        assert second["effect"]["effect_id"] != first["effect"]["effect_id"]
        assert second["continuation"]["frames"] == [
            {"node_id": "0.0", "iteration": 0},
            {"node_id": "0.0.case0.0", "iteration": 0},
            {"node_id": second["effect"]["node_id"], "iteration": 1},
        ]
        assert second["continuation"]["slots"][first["effect"]["result_slot"]] == {
            "type": "string", "value": "nested-first",
        }
        wrong = copy.deepcopy(second["continuation"])
        wrong["frames"][1]["iteration"] = 1
        assert "invalid nested Switch continuation site" in invoke(nested_switch, {
            "protocol_version": 1, "input": -100,
            "continuation": bind(wrong, second["effect"]["result_slot"], "invalid"),
        }, fails=True)
        missing = copy.deepcopy(second["continuation"])
        del missing["slots"][first["effect"]["result_slot"]]
        assert "missing continuation slot" in invoke(nested_switch, {
            "protocol_version": 1, "input": -100,
            "continuation": bind(missing, second["effect"]["result_slot"], "invalid"),
        }, fails=True)
        done = invoke(nested_switch, {
            "protocol_version": 1, "input": -100,
            "continuation": bind(second["continuation"],
                                 second["effect"]["result_slot"], "nested-second"),
        })
        assert done == {
            "protocol_version": 1, "status": "completed", "result": "nested-second",
        }, done
        alternate = invoke(nested_switch, {"protocol_version": 1, "input": 2})
        assert alternate["status"] == "suspended", alternate
        assert alternate["effect"]["node_id"] == "0.0.case0.0.case1.0"
        assert alternate["continuation"]["frames"][1]["iteration"] == 1
        alternate_done = invoke(nested_switch, {
            "protocol_version": 1, "input": -2,
            "continuation": bind(alternate["continuation"],
                                 alternate["effect"]["result_slot"], "alternate"),
        })
        assert alternate_done["result"] == "alternate"
        outer_noop = invoke(nested_switch, {"protocol_version": 1, "input": -5})
        assert outer_noop == {
            "protocol_version": 1, "status": "completed", "result": "no-effect",
        }

        # Two nested ranges with dependent Calls inside each inner iteration.
        # Completed prefixes for BOTH loop levels must survive every process
        # restart. Negative outer range bounds use zero-based ordinals.
        nested_range = binaries["native-nested-range-effects"]
        current = invoke(nested_range, {"protocol_version": 1, "input": None})
        expected = []
        for outer in range(2):
            row = []
            for inner in range(2):
                first = current
                assert first["status"] == "suspended", first
                assert first["effect"]["node_id"] == "0.0.each.0.each.0"
                assert first["continuation"]["next_step"] == 0
                assert first["continuation"]["frames"] == [
                    {"node_id": "0.0", "iteration": outer},
                    {"node_id": "0.0.each.0", "iteration": inner},
                    {"node_id": first["effect"]["node_id"], "iteration": 0},
                ]
                saved = first["continuation"]["slots"]
                assert len(saved["__awfl_loop_0_items"]["value"]) == outer
                assert len(saved["__awfl_loop_0_inner_items"]["value"]) == inner
                assert first["effect"]["effect_id"].endswith("_" + str(outer) + "_" + str(inner))
                first_result = "first-" + str(outer) + "-" + str(inner)
                second = invoke(nested_range, {
                    "protocol_version": 1, "input": None,
                    "continuation": bind(first["continuation"],
                                         first["effect"]["result_slot"], first_result),
                })
                assert second["status"] == "suspended", second
                assert second["effect"]["node_id"] == "0.0.each.0.each.1"
                assert second["effect"]["args"] == first_result
                assert second["effect"]["effect_id"] != first["effect"]["effect_id"]
                assert second["continuation"]["frames"][-1]["iteration"] == 1
                assert second["continuation"]["slots"][first["effect"]["result_slot"]] == {
                    "type": "string", "value": first_result,
                }
                if outer == 1 and inner == 1:
                    invalid_prefix = copy.deepcopy(second["continuation"])
                    invalid_prefix["slots"]["__awfl_loop_0_inner_items"]["value"] = []
                    assert "invalid nested range continuation prefix" in invoke(nested_range, {
                        "protocol_version": 1, "input": None,
                        "continuation": bind(invalid_prefix,
                                             second["effect"]["result_slot"], "bad"),
                    }, fails=True)
                    invalid_site = copy.deepcopy(second["continuation"])
                    invalid_site["frames"][2]["iteration"] = 99
                    assert "invalid nested range continuation site" in invoke(nested_range, {
                        "protocol_version": 1, "input": None,
                        "continuation": bind(invalid_site,
                                             second["effect"]["result_slot"], "bad"),
                    }, fails=True)
                    invalid_prior = copy.deepcopy(second["continuation"])
                    del invalid_prior["slots"][first["effect"]["result_slot"]]
                    assert "missing continuation slot" in invoke(nested_range, {
                        "protocol_version": 1, "input": None,
                        "continuation": bind(invalid_prior,
                                             second["effect"]["result_slot"], "bad"),
                    }, fails=True)
                second_result = "second-" + str(outer) + "-" + str(inner)
                row.append(second_result)
                current = invoke(nested_range, {
                    "protocol_version": 1, "input": None,
                    "continuation": bind(second["continuation"],
                                         second["effect"]["result_slot"], second_result),
                })
            expected.append(row)
        assert current == {
            "protocol_version": 1, "status": "completed", "result": expected,
        }, current
        print("native protocol: 2 single-effect resumes, 2-step effect sequence,"
              " three-iteration range, two effects per range iteration,"
              " pinned two-branch and nested Switch, nested-range resumes,"
              " malformed continuation"
              " and version rejection passed")


if __name__ == "__main__":
    check()
