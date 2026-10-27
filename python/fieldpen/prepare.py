"""`python -m fieldpen.prepare`: convert IAM XML or JSON {strokes: [[[x,y],...],...]} files to one NPZ.

Supply separately curated train/valid folders (prefer writer-disjoint splits).
No dataset is downloaded and no IAM permission is assumed.
"""
import argparse
import json
from pathlib import Path
from .data import iam_line, polyline_deltas, save_dataset
from .sense import with_sense


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--format', choices=['iam', 'json'], default='json')
    p.add_argument('--scale', type=float, required=True, help='Input coordinate units per model unit')
    p.add_argument('--sense', type=int, default=0,
                   help='Append what a sensing pen sees, in this many sectors (0: a plain pen)')
    p.add_argument('--sense-radius', type=float, default=3.0, help='How far it sees, in model units')
    p.add_argument('--sense-recent', type=int, default=8, help='How many of its own last points it ignores')
    a = p.parse_args()
    seq = []
    for f in sorted(a.folder.rglob('*.xml' if a.format == 'iam' else '*.json')):
        try:
            s = (iam_line(f, a.scale) if a.format == 'iam' else
                 polyline_deltas(json.loads(f.read_text())['strokes'], a.scale))
            seq.append(with_sense(s, a.sense, a.sense_radius, a.sense_recent) if a.sense else s)
        except Exception as e:
            raise ValueError(f'{f}: {e}') from e
    save_dataset(seq, a.output, (a.sense, a.sense_radius, a.sense_recent) if a.sense else None)
    print(f'Saved {len(seq)} independent sequences to {a.output}')


if __name__ == '__main__':
    main()
