#!/usr/bin/env python3
"""Build the durable first-sweep index from saved, source-bound review records."""
from collections import Counter, defaultdict
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent


def main():
    manifest = json.loads((OUT / 'scope.json').read_text())
    groups = {g['group']: g for g in manifest['groups']}
    records = {}
    for path in sorted((OUT / 'reviews').glob('*.jsonl')):
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            record = json.loads(line)
            if record['group'] in groups:
                if record['group'] in records:
                    raise ValueError(f"Duplicate review: {record['group']}")
                records[record['group']] = record
    if records.keys() != groups.keys():
        raise ValueError(f'Missing reviews: {groups.keys() - records.keys()}')
    findings = []
    proposals = defaultdict(list)
    for name, record in sorted(records.items()):
        for number, finding in enumerate(record['findings'], 1):
            findings.append(dict(group=name, finding_id=f"{record['key']}-{number:02}", **finding))
        for proposal in record.get('helper_proposals', []):
            key = proposal['helper_key']
            if key == 'fixed-precision-number-properties':
                key = 'approved.numeric-format-properties'
            proposals[key].append(dict(group=name, proposal=proposal))
    (OUT / 'findings.json').write_text(json.dumps(findings, indent=2) + '\n')
    (OUT / 'helper-proposals.json').write_text(json.dumps(dict(sorted(proposals.items())), indent=2) + '\n')
    with (OUT / 'groups.tsv').open('w') as stream:
        writer = csv.writer(stream, delimiter='\t')
        writer.writerow(['group', 'disposition', 'bodies', 'findings', 'summary'])
        for name, record in sorted(records.items()):
            writer.writerow([name, record['disposition'], groups[name]['body_count'], len(record['findings']), record['summary']])
    summary = dict(groups=len(records), dispositions=dict(Counter(r['disposition'] for r in records.values())),
                   findings=len(findings), helper_families=len(proposals), source_commit=manifest['source_commit'])
    (OUT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
