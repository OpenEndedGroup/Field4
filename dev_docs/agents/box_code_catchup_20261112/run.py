"""Reuse the prior catch-up's source-aware inventory, formatter and reference checks."""
from pathlib import Path
import argparse
import importlib.util
import json
import os
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


S = load('session', PRIOR / 'session.py')
S.HERE = HERE
S.OUT = ROOT / 'build/box-code-revision/catchup-20261112'
S.BASE = '21e960ad3'

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inventory', 'format', 'reference-candidates', 'reference-apply', 'reference-verify'])
    action = parser.parse_args().action
    if action == 'inventory':
        S.inventory()
    elif action == 'format':
        os.environ['CATCHUP_FORMAT_PATHS'] = ','.join(json.loads((HERE / 'scope.json').read_text())['paths'])
        formatter = load('catchup_formatter', PRIOR / 'format.py')
        formatter.prepare()
        formatter.run()
        formatter.apply()
    else:
        links = load('catchup_links', PRIOR / 'reference_links.py')
        getattr(links, action.removeprefix('reference-').replace('-', '_'))()
