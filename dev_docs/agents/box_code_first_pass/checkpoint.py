#!/usr/bin/env python3
"""Validate saved review coverage and prepare bounded, resumable assignments.

Read-only with respect to teaching sources. Run from the repository root.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

ROOT = Path.cwd()
REVIEW = ROOT / 'dev_docs/agents/box_code_first_pass'
RUN = ROOT / 'build/box-code-revision/first-pass'


def checkpoint(batch_size=10, max_chars=45000):
    manifest = json.loads((REVIEW / 'scope.json').read_text())
    groups = {g['group']: g for g in manifest['groups']}
    seen, invalid, duplicate = {}, [], []
    for path in sorted((REVIEW / 'reviews').glob('*.jsonl')):
        for line_no, text in enumerate(path.read_text().splitlines(), 1):
            if not text.strip(): continue
            try: record = json.loads(text)
            except ValueError:
                invalid.append({'file':str(path.relative_to(ROOT)), 'line':line_no}); continue
            group = record['group']
            if group in seen: duplicate.append(group)
            seen[group] = record
    complete, reconcile, stale, problems = [], [], [], []
    lenses = {'smells_idioms','imports','whitespace','comments','glsl','live_coding','helpers'}
    for name, record in seen.items():
        if name not in groups: continue
        group = groups[name]
        changed = [p for p,h in group['source_hashes'].items()
                   if hashlib.sha256((ROOT/p).read_bytes()).hexdigest() != h]
        if changed:
            stale.append({'group':name,'paths':changed}); continue
        supplemental = record.get('supplemental_sources', [])
        extra_paths = set()
        if isinstance(supplemental, dict): extra_paths = set(supplemental)
        elif isinstance(supplemental, list):
            extra_paths = {s.get('path','') if isinstance(s,dict) else s for s in supplemental}
        missing = [ident for ident in group['occurrence_ids']
                   if ident not in record.get('occurrence_ids',[]) and ident.split('#',1)[0] not in extra_paths]
        if missing:
            reconcile.append({'group':name,'missing_occurrences':missing}); continue
        claimed_hashes = dict(record.get('source_hashes', {}))
        if isinstance(supplemental, dict): claimed_hashes.update(supplemental)
        elif isinstance(supplemental, list):
            claimed_hashes.update({s['path']: s['sha256'] for s in supplemental
                                   if isinstance(s, dict) and 'path' in s and 'sha256' in s})
        mismatched = [p for p,h in group['source_hashes'].items()
                      if claimed_hashes.get(p) != h]
        if mismatched:
            problems.append({'group':name,'review_hash_mismatch':mismatched}); continue
        if set(record.get('lenses',{})) != lenses or not record.get('source_hashes_verified'):
            problems.append(name); continue
        complete.append(name)
    remaining = [g for name,g in groups.items() if name not in complete]
    state = dict(schema=1, source_commit=manifest['source_commit'], scope_groups=len(groups),
        completed_groups=len(complete), remaining_groups=len(remaining),
        review_records_saved=len(seen), duplicate_groups=duplicate, invalid_jsonl=invalid,
        needs_reconciliation=reconcile, stale_sources=stale, schema_problems=problems,
        dispositions=dict(Counter(seen[n]['disposition'] for n in complete)),
        remaining=[{'key':g['key'],'group':g['group'],'code_chars':g['code_chars']} for g in remaining])
    (REVIEW/'checkpoint.json').write_text(json.dumps(state,indent=2)+'\n')
    batches, current, chars = [], [], 0
    for group in sorted(remaining,key=lambda g:g['code_chars']):
        if current and (len(current)>=batch_size or chars+group['code_chars']>max_chars):
            batches.append(current);current=[];chars=0
        current.append(dict(group, packet=str(RUN / 'packets' / (group['key'] + '.md'))));chars+=group['code_chars']
    if current:batches.append(current)
    directory=RUN/'bounded';directory.mkdir(exist_ok=True)
    for n,batch in enumerate(batches,1):
        (directory/f'batch-{n:03}.json').write_text(json.dumps(batch,indent=2)+'\n')
    (directory/'index.json').write_text(json.dumps({
        'max_groups':batch_size,'max_chars':max_chars,'oversize_groups_run_alone':True,
        'batches':[{'file':f'batch-{n:03}.json','groups':len(b),'code_chars':sum(g['code_chars'] for g in b)}
                   for n,b in enumerate(batches,1)]},indent=2)+'\n')
    print(json.dumps({k:v for k,v in state.items() if k not in ('remaining','needs_reconciliation')},indent=2))
    print(f'Prepared {len(batches)} bounded assignments. Keep assignments fixed while workers run.')
    return state


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch-size',type=int,default=10)
    parser.add_argument('--max-chars',type=int,default=45000)
    args=parser.parse_args()
    checkpoint(args.batch_size,args.max_chars)
