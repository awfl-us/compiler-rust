#!/usr/bin/env python3
"""Execute DSL-compiled Parallel and ParallelFor ELF binaries across fork/join IPC."""
import json
import subprocess
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def binary(crate_name):
    crate = root / "fixtures" / crate_name
    with (crate / "Cargo.toml").open("rb") as f:
        package = tomllib.load(f)["package"]["name"]
    return crate / "target" / "debug" / package


def invoke(crate, input_value, continuation=None):
    request = {"protocol_version": 2, "input": input_value}
    if continuation is not None:
        request["continuation"] = continuation
    proc = subprocess.run(
        [str(binary(crate))], input=json.dumps(request),
        text=True, capture_output=True, timeout=20
    )
    assert proc.returncode == 0, (proc.stdout, proc.stderr)
    response = json.loads(proc.stdout)
    assert response["protocol_version"] == 2, response
    return response


def tagged(v):
    if isinstance(v, str):
        return {"type": "string", "value": v}
    if isinstance(v, list):
        return {"type": "list", "value": [tagged(x) for x in v]}
    raise TypeError(v)


def lane_continuation(lane):
    assert lane["entry"].startswith("pc_"), lane
    return {
        "next_step": int(lane["entry"][3:]),
        "slots": lane["slots"],
        "frames": [],
    }


def join_parent(crate, initial, results, input_value):
    cont = initial["continuation"]
    assert initial["effect"]["operation"] == "native.parallel"
    slot = initial["effect"]["result_slot"]
    cont["slots"][slot] = tagged(results)
    return invoke(crate, input_value, cont)


# Each sibling executes the same native artifact at a different compiled PC.
# Its external Call uses its own independently resumable effect/slot.
crate = "native-parallel-branches"
initial = invoke(crate, None)
assert initial["status"] == "suspended", initial
assert initial["effect"]["effect_id"].startswith("fork_")
lanes = initial["effect"]["args"]["lanes"]
assert len(lanes) == 2, lanes
assert lanes[0]["entry"] != lanes[1]["entry"], lanes
results = []
for i, lane in enumerate(lanes):
    child = invoke(crate, None, lane_continuation(lane))
    assert child["status"] == "suspended", child
    assert child["effect"]["operation"] == "http.post", child
    assert child["effect"]["args"] == ("first" if i == 0 else "second")
    next_cont = child["continuation"]
    next_cont["slots"][child["effect"]["result_slot"]] = tagged(f"value-{i}")
    complete = invoke(crate, None, next_cont)
    assert complete["status"] == "completed", complete
    results.append(complete["result"])
assert results == ["value-0", "value-1"], results
parent = join_parent(crate, initial, results, None)
assert parent == {"protocol_version": 2, "status": "completed",
                  "result": {"a": "value-0", "b": "value-1"}}, parent

# ParallelFor has one compiled entry PC, N independent item slot snapshots,
# and deterministic input ordering; zero items produces an empty fork.
crate = "native-parallel-for"
for items in ([], ["first"], ["c", "a", "b"]):
    initial = invoke(crate, items)
    assert initial["status"] == "suspended", initial
    lanes = initial["effect"]["args"]["lanes"]
    assert len(lanes) == len(items), lanes
    if lanes:
        assert len(set(lane["entry"] for lane in lanes)) == 1
    results = []
    for lane in lanes:
        child = invoke(crate, items, lane_continuation(lane))
        assert child["status"] == "completed", child
        results.append(child["result"])
    assert results == items, results
    parent = join_parent(crate, initial, results, items)
    assert parent == {"protocol_version": 2, "status": "completed",
                      "result": items}, parent

print("PARALLEL: compiled sibling effects, independent resumes, parent join, "
      "0/1/N ParallelFor lanes and stable ordering: PASS")
