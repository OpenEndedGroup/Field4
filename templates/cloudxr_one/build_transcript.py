#!/usr/bin/env python3
"""Author the private CloudXR cube document through Field's operation transcripts."""
import argparse
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/cloudxr-one-transcripts'))
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    args.output.mkdir(parents=True, exist_ok=True)
    boxes = [('context', '1 · Stereo room / view', 0),
             ('cube', '2 · Red cube shader', 620),
             ('render', '3 · Frame loop', 1160)]
    create = []
    for name, label, x in boxes:
        properties = {'code': (here / f'{name}.kt').read_text()}
        if name == 'cube':
            for stage in ['vertex', 'fragment']:
                properties[stage] = (here / f'cube_{stage}.glsl').read_text()
        create.append({'create': {'box': f'cloudxr1_{name}', 'name': label,
                       'frame': {'x': x, 'y': 0, 'w': 470, 'h': 100},
                       'properties': properties}})
    create += [{'connect': {'parent': 'cloudxr1_context', 'child': f'cloudxr1_{name}'}}
               for name in ['cube', 'render']]
    launch = [{'launch': {'box': f'cloudxr1_{name}'}} for name, _, _ in boxes]
    launch += [{'await': {'wait': 'pending'}}]
    stop = [{'stop': {'box': f'cloudxr1_{name}'}} for name, _, _ in reversed(boxes)]
    for name, operations in [('create', create), ('launch', launch), ('stop', stop)]:
        path = args.output / f'{name}.json'
        path.write_text(json.dumps({'operations': operations}, ensure_ascii=False, indent=2) + '\n')
        print(path)


if __name__ == '__main__':
    main()
