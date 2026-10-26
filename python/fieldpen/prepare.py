"""`python -m fieldpen.prepare`: convert IAM XML or JSON {strokes: [[[x,y],...],...]} files to one NPZ.

Supply separately curated train/valid folders (prefer writer-disjoint splits).
No dataset is downloaded and no IAM permission is assumed.
"""
import argparse
import json
from pathlib import Path
from .data import iam_line, polyline_deltas, save_dataset


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--format', choices=['iam', 'json'], default='json')
    p.add_argument('--scale', type=float, required=True, help='Input coordinate units per model unit')
    a = p.parse_args()
    seq = []
    for f in sorted(a.folder.rglob('*.xml' if a.format == 'iam' else '*.json')):
        try:
            s = (iam_line(f, a.scale) if a.format == 'iam' else
                 polyline_deltas(json.loads(f.read_text())['strokes'], a.scale))
            seq.append(s)
        except Exception as e:
            raise ValueError(f'{f}: {e}') from e
    save_dataset(seq, a.output)
    print(f'Saved {len(seq)} independent sequences to {a.output}')


if __name__ == '__main__':
    main()
