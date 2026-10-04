#!/usr/bin/env python3
"""Inspect the shared AI4 request ledger across Field processes; no server or API calls."""
import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from urllib.parse import urlparse


def read_events(root, since, include_local=False):
    for path in sorted(root.glob('*.jsonl')):
        with path.open() as source:
            for line in source:
                try:
                    event = json.loads(line)
                    if datetime.fromisoformat(event['timestamp'].replace('Z', '+00:00')) < since:
                        continue
                except (ValueError, KeyError):
                    # A running process may not have finished its last line yet.
                    continue
                host = urlparse(event.get('endpoint', '')).hostname
                if not include_local and host in ('localhost', '127.0.0.1', '::1'):
                    continue
                yield event


def summarize(events, group='document'):
    groups = {}
    attempts = {}
    responses = set()
    for e in events:
        document = e.get('documentPath') or e.get('documentName') or e.get('fileKey') or '(unattributed)'
        key = (e.get('instanceId'), document if group == 'document' else None, e.get('provider'), e.get('model'))
        row = groups.setdefault(key, dict(pid=e.get('pid'), instanceId=e.get('instanceId'),
            processStartedAt=e.get('processStartedAt'), checkout=e.get('checkout'),
            document=document if group == 'document' else '*', provider=e.get('provider'), model=e.get('model'),
            attempts=0, failures=0, rateLimits=0, retries=0, responsesWithUsage=0,
            inputTokens=0, cachedInputTokens=0, outputTokens=0, reasoningTokens=0,
            unknownUsageAttempts=0, unfinishedAttempts=0, firstSeen=e['timestamp'], lastSeen=e['timestamp']))
        row['firstSeen'] = min(row['firstSeen'], e['timestamp'])
        row['lastSeen'] = max(row['lastSeen'], e['timestamp'])
        aid = (e.get('instanceId'), e.get('requestId'), e.get('attempt'))
        if e['event'] == 'retry_wait':
            row['retries'] += 1
            continue
        attempt = attempts.setdefault(aid, dict(row=row, started=False, finished=False, known=False))
        if e['event'] == 'attempt_started':
            if not attempt['started']:
                row['attempts'] += 1
            attempt['started'] = True
        elif e['event'] == 'attempt_finished':
            if not attempt['finished']:
                row['failures'] += int(not e.get('success', False))
                row['rateLimits'] += int(e.get('rateLimited', False))
            attempt['finished'] = True
        elif e['event'] == 'response_usage':
            attempt['known'] = bool(e.get('usageKnown'))
            rid = (e.get('provider'), e.get('endpoint'), e.get('responseId'))
            if e.get('usageKnown') and rid not in responses:
                responses.add(rid)
                row['responsesWithUsage'] += 1
                for field in ('inputTokens', 'cachedInputTokens', 'outputTokens', 'reasoningTokens'):
                    row[field] += e.get(field) or 0
    for a in attempts.values():
        a['row']['unknownUsageAttempts'] += int(not a['known'])
        a['row']['unfinishedAttempts'] += int(a['started'] and not a['finished'])
    return sorted(groups.values(), key=lambda r: (r['lastSeen'], r['document']), reverse=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.home()/'.afield/logs/ai_usage')
    parser.add_argument('--hours', type=float, default=24)
    parser.add_argument('--group', choices=('document', 'process'), default='document')
    parser.add_argument('--include-local', action='store_true', help='Include localhost protocol tests')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    since = datetime.now(timezone.utc) - timedelta(hours=args.hours)
    rows = summarize(read_events(args.root, since, args.include_local), args.group)
    if args.json:
        print(json.dumps(dict(since=since.isoformat(), root=str(args.root), groups=rows), indent=2))
        return
    print(f'Field AI usage — last {args.hours:g} hours — {args.root}')
    if not rows:
        print('No records. Older Field binaries do not write this ledger; this does not prove no API activity.')
    for r in rows:
        print(f"\nPID {r['pid']} · {r['provider']} / {r['model']} · {r['document']}")
        print(f"  {r['checkout']} · started {r['processStartedAt']} · last record {r['lastSeen']}")
        print(f"  {r['attempts']} attempts · {r['failures']} failures · {r['rateLimits']} rate limits · {r['retries']} retries")
        print(f"  Input {r['inputTokens']:,} (cached {r['cachedInputTokens']:,}) · output {r['outputTokens']:,} (reasoning {r['reasoningTokens']:,})")
        print(f"  {r['unknownUsageAttempts']} attempts with unknown usage · {r['unfinishedAttempts']} without a finish record")
    print('\nCached input is included in input; reasoning is included in output. Counts are provider-reported, not a bill or exact TPM reconstruction.')


if __name__ == '__main__':
    main()
