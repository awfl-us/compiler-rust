#!/usr/bin/env python3
"""Run *unchanged* AWFL evals.Product Scala workflow as native Rust.

The Scala test generates these crates from the pinned real Product.scala source.
No workflow migration or Google Workflows service is needed.
"""
import hashlib
import json
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main(target_dir: Path) -> None:
    expected = {
        'single': [{'color': 'red'}, {'color': 'blue'}],
        'cartesian': [
            {'color': 'red', 'size': 'S'},
            {'color': 'red', 'size': 'M'},
            {'color': 'blue', 'size': 'S'},
            {'color': 'blue', 'size': 'M'},
        ],
        'empty': [],
    }
    for fixture, wanted in expected.items():
        crate = ROOT / 'fixtures' / 'real-product' / fixture
        with (crate / 'Cargo.toml').open('rb') as f:
            name = tomllib.load(f)['package']['name']
        manifest = json.loads((crate / 'manifest.json').read_text())
        source = (crate / 'src/main.rs').read_bytes()
        assert hashlib.sha256(source).hexdigest() == manifest['source_sha256']
        assert manifest['runtime_abi'] == 3
        assert any(e['operation'] == 'log.write' for e in manifest['effects'])
        binary = target_dir / 'debug' / name
        assert binary.is_file(), binary

        def execute(arg):
            p = subprocess.run([str(binary)], input=json.dumps({
                'protocol_version': 2, 'input': arg,
            }), text=True, capture_output=True, timeout=25, check=True)
            return json.loads(p.stdout)

        actual = execute(None)
        assert actual['status'] == 'completed', (fixture, actual)
        assert actual['protocol_version'] == 2
        records = actual['result']
        assert len(records) == len(wanted), (fixture, records)
        assert {json.dumps(x, sort_keys=True) for x in records} == {
            json.dumps(x, sort_keys=True) for x in wanted
        }, (fixture, records)
        assert execute({'unused': 42}) == actual, fixture
        print(f'REAL PRODUCT {fixture}: {len(records)} combinations; native result and determinism PASS')
    print('REAL PRODUCT: three unchanged production-source Scala workflows executed as Rust')


if __name__ == '__main__':
    assert len(sys.argv) == 2, 'usage: test_real_product.py /path/to/target'
    main(Path(sys.argv[1]))
