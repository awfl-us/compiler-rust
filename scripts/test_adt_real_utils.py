#!/usr/bin/env python3
"""Native memory-mode parity for real workflow-utils Scala Match call sites."""
import json
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "target" / "adt-matching"
CASES = (
    ({"sessionId": "s1", "end": 4.0, "windowSeconds": 120.0}, "s1", "SegKala"),
    ({"sessionId": "s2", "sessionEnd": 16.0}, "s2", "SessionKala"),
    ({"weekEnd": 12.0}, "global", "WeekKala"),
    ({"termBegin": 1.0, "termEnd": 20.0}, "global", "TermKala"),
)


def binary(fixture: str) -> Path:
    manifest = ROOT / "fixtures" / fixture / "Cargo.toml"
    assert manifest.is_file(), fixture
    with manifest.open('rb') as f:
        package = tomllib.load(f)['package']['name']
    subprocess.run(['cargo','build','--quiet','--manifest-path',str(manifest),
                    '--target-dir',str(TARGET)],check=True)
    return TARGET / 'debug' / package


def run(path: Path, value):
    out = subprocess.run([str(path)], input=json.dumps({
        'protocol_version':2, 'input':value})+'\n',
        capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def check():
    lock = binary('native-adt-utils-lock')
    context = binary('native-adt-utils-context')
    tests = 0
    for value, identifier, kind in CASES:
        a = run(lock,value)
        assert a['status']=='completed' and a['result']=={
            'collection':'locks.benchmark.Session','id':identifier},(value,a)
        tests+=1
        b = run(context,value)
        assert b['status']=='completed' and b['result']['kind']==kind,(value,b)
        if kind in ('SegKala','SessionKala'):
            assert b['result']['sessionId']==identifier,(value,b)
        tests+=1
        print('REAL_UTILS_MATCH_PASS',kind,identifier)
    print('REAL_UTILS_MATCH_NATIVE_PASS',tests)


if __name__=='__main__':
    check()
