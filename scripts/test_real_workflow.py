#!/usr/bin/env python3
"""Exercise a Cargo-compiled, unchanged production workflow's native effect
protocol without issuing any network requests.
"""
import copy
import json
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main(target_dir: Path):
    crates = list((ROOT / 'fixtures' / 'real-load-history').glob('*/Cargo.toml'))
    assert len(crates) == 1, f'expected one real workflow crate, found {crates}'
    with crates[0].open('rb') as f:
        package = tomllib.load(f)['package']['name']
    binary = target_dir / 'debug' / package
    assert binary.is_file(), binary

    # Input.env is server-owned and injected after untrusted caller inputs;
    # synthetic values here prevent any use of real service credentials.
    supplied = {
        'arg1': 'conversation-test-object',
        'arg2': 'caller-test-user',
        'env': {
            'BASE_URL': 'https://example.invalid',
            'userId': 'trusted-user',
            'projectId': 'trusted-project',
        },
    }

    def invoke(continuation=None):
        payload = {'protocol_version': 2, 'input': supplied}
        if continuation is not None:
            payload['continuation'] = continuation
        p = subprocess.run([str(binary)], input=json.dumps(payload), text=True,
                           capture_output=True, timeout=20)
        assert p.returncode == 0, (p.stdout, p.stderr)
        return json.loads(p.stdout)

    first = invoke()
    assert first['status'] == 'suspended', first
    assert first['protocol_version'] == 2
    effect = first['effect']
    assert effect['operation'] == 'http.post', effect
    assert effect['node_id'] == '0.0'
    args = effect['args']
    assert args['url'] == 'https://example.invalid/convo-history/loadConvoHistory', args
    assert args['body'] == {
        'objectName': 'conversation-test-object',
        'userId': 'caller-test-user',
    }, args
    assert args['auth']['type'] == 'OIDC', args
    assert args['auth']['audience'] == 'https://example.invalid', args
    assert args['headers'] == {
        'x-user-id': 'trusted-user', 'x-project-id': 'trusted-project',
    }, args
    again = invoke()
    assert again == first, 'same input must yield deterministic effect intent'
    assert first['continuation']['frames'] == []

    # Emulates the server acknowledging the HTTP effect before resuming at
    # the saved compiled program counter. No endpoint is contacted.
    checkpoint = copy.deepcopy(first['continuation'])
    if effect['result_slot'] in checkpoint['slots']:
        checkpoint['slots'][effect['result_slot']] = {
            'type': 'map', 'value': {'body': {'type': 'null'}},
        }
    completed = invoke(checkpoint)
    assert completed == {
        'protocol_version': 2,
        'status': 'completed',
        'result': {'status': 'success'},
    }, completed
    print('REAL WORKFLOW: compiled original LoadConversationHistory; '
          'HTTP intent/args, deterministic replay, and persisted resume passed')


if __name__ == '__main__':
    assert len(sys.argv) == 2, 'usage: test_real_workflow.py /path/to/cargo/target'
    main(Path(sys.argv[1]))
