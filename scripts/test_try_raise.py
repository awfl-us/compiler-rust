#!/usr/bin/env python3
"""Real native compiled Try/Raise, nested rescue and durable-effect intent.
No external HTTP request is issued. The server failure handoff is separately gated.
"""
import copy
import json
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = Path('/tmp/awfl-try-target/debug')


def run(name, input_value=None, continuation=None):
    crate = ROOT / 'fixtures/native-try-raise' / name
    with (crate / 'Cargo.toml').open('rb') as f:
        package = tomllib.load(f)['package']['name']
    payload = {'protocol_version': 2, 'input': input_value}
    if continuation is not None:
        payload['continuation'] = continuation
    proc = subprocess.run([str(TARGET / package)], input=json.dumps(payload),
        text=True, capture_output=True, timeout=20)
    assert proc.returncode == 0, (name, proc.stdout, proc.stderr)
    return json.loads(proc.stdout)


def result(name, expected, data=None):
    observed = run(name, data)
    assert observed == {'protocol_version': 2, 'status': 'completed', 'result': expected}, observed
    assert run(name, data) == observed, name + ' result changed on replay'


result('catch-raise', 'raised')
result('nested-catch', 'nested')
result('success', 'good')
uncaught = run('uncaught')
assert uncaught['status'] == 'failed', uncaught
assert uncaught['error'] == {'message': 'uncaught', 'code': None}, uncaught
runtime_error = run('runtime-error', {'key': 1})
assert runtime_error['status'] == 'completed', runtime_error
assert 'map.get' in runtime_error['result'], runtime_error

suspended = run('effect-catch')
assert suspended['status'] == 'suspended', suspended
assert suspended['effect']['operation'] == 'http.post'
assert suspended['effect']['args']['url'] == 'https://example.invalid/try'
assert suspended['effect']['on_error']['next_step'] >= 0
assert suspended['effect']['on_error']['error_slot'].startswith('s')
assert suspended['continuation']['frames'] == []
assert run('effect-catch') == suspended

success = copy.deepcopy(suspended['continuation'])
success['slots'][suspended['effect']['result_slot']] = {'type': 'string', 'value': 'ok'}
assert run('effect-catch', continuation=success)['result'] == 'effect-ok'

# Emulate the *checkpoint* the effect dispatcher must atomically persist on
# confirmed remote failure. The server integration is NOT yet implemented.
error = copy.deepcopy(suspended['continuation'])
error['next_step'] = suspended['effect']['on_error']['next_step']
error['slots'][suspended['effect']['on_error']['error_slot']] = {
    'type': 'map', 'value': {
        'message': {'type': 'string', 'value': 'HTTP 503'},
        'code': {'type': 'integer', 'value': 503},
    }
}
failed = run('effect-catch', continuation=error)
assert failed['status'] == 'completed' and failed['result'] == 'HTTP 503', failed
assert run('effect-catch', continuation=error) == failed
print('NATIVE TRY/RAISE: caught and uncaught, nested rethrow, runtime error, '
      'HTTP intent + happy/error continuation paths, determinism: PASS')
