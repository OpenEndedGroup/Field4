"""Reuse the prior catch-up's source-aware inventory, formatter and reference checks."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
import re
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PRIOR = HERE.parent / 'box_code_catchup_20261111'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


S = load('session', HERE / 'session.py')
S.HERE = HERE
S.OUT = ROOT / 'build/box-code-revision/catchup-20261126'
S.BASE = 'be4dd4c67f'

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inventory', 'format', 'reference-candidates', 'reference-verify'])
    action = parser.parse_args().action
    if action == 'inventory':
        S.inventory()
    elif action == 'format':
        os.environ['CATCHUP_FORMAT_PATHS'] = ','.join(json.loads((HERE / 'scope.json').read_text())['paths'])
        formatter = load('catchup_formatter', HERE / 'format.py')
        formatter.prepare()
        formatter.run()
        formatter.apply()
    elif action == 'reference-verify':
        receipt = json.loads((HERE / 'reference-verification.json').read_text())
        for row in receipt['links']:
            target, anchor = row['target'].split('#')
            if target.startswith('reference/source/'):
                source = target.removeprefix('reference/source/').strip('/').split('/')
                path = ROOT / 'src' / source[0] / 'kotlin' / ('/'.join(source[1:]) + '.kt')
                line = int(anchor.removeprefix('L'))
                assert 'fun asFn(' in path.read_text().splitlines()[line - 1], row
            else:
                text = (ROOT / 'public/docs' / target).read_text()
                assert f'id="{anchor}"' in text or re.search(r'\{\s*#' + re.escape(anchor) + r'\s*\}', text), row
            assert row['target'] in (ROOT / row['path']).read_text(), row
        print(len(receipt['links']), 'reference anchors verified')
    else:
        links = load('catchup_links', PRIOR / 'reference_links.py')
        getattr(links, action.removeprefix('reference-').replace('-', '_'))()
