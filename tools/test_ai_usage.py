"""Accounting regressions: aggregate separate JVM files without inventing missing usage."""
import json
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timezone
from ai_usage import read_events, summarize


class UsageTest(unittest.TestCase):
    def test_processes_retries_unknowns_and_duplicate_responses(self):
        events = []
        for pid in (10, 20):
            base = dict(instanceId=str(pid), pid=pid, requestId='request', attempt=1,
                        timestamp='2026-10-03T10:00:00Z', endpoint='https://api.openai.com/v1',
                        model='gpt-6-astra', provider='openai', fileKey=f'afield://doc-{pid}/box/code')
            events.extend([dict(base, event='attempt_started'), dict(base, event='attempt_finished', success=False, rateLimited=True),
                           dict(base, event='retry_wait'), dict(base, event='attempt_started', attempt=2)])
            usage = dict(base, attempt=2, event='response_usage', responseId=f'resp_{pid}', usageKnown=True,
                         inputTokens=100, cachedInputTokens=80, outputTokens=20, reasoningTokens=10)
            events.extend([usage, usage, dict(base, attempt=2, event='attempt_finished', success=True)])
        groups = summarize(events)
        self.assertEqual(2, len(groups))
        for g in groups:
            self.assertEqual((2, 1, 1, 1), (g['attempts'], g['failures'], g['rateLimits'], g['retries']))
            self.assertEqual((100, 80, 20, 10), (g['inputTokens'], g['cachedInputTokens'], g['outputTokens'], g['reasoningTokens']))
            self.assertEqual(1, g['unknownUsageAttempts'])
            self.assertEqual(0, g['unfinishedAttempts'])

    def test_unfinished_requests_are_not_claimed_as_zero_cost_or_running(self):
        r = summarize([dict(event='attempt_started', timestamp='2026-10-03T10:00:00Z', requestId='r', attempt=1)])
        self.assertEqual(1, r[0]['unknownUsageAttempts'])
        self.assertEqual(1, r[0]['unfinishedAttempts'])

    def test_local_fixtures_and_partial_lines_are_excluded(self):
        with tempfile.TemporaryDirectory() as d:
            base = dict(timestamp='2026-10-03T10:00:00Z', event='attempt_started')
            Path(d, 'one.jsonl').write_text(json.dumps(dict(base, endpoint='http://127.0.0.1:8000/v1'))+'\n')
            Path(d, 'two.jsonl').write_text(json.dumps(dict(base, endpoint='https://api.openai.com/v1'))+'\n{"timestamp":')
            since = datetime(2026, 10, 3, tzinfo=timezone.utc)
            self.assertEqual(1, len(list(read_events(Path(d), since))))
            self.assertEqual(2, len(list(read_events(Path(d), since, True))))


if __name__ == '__main__':
    unittest.main()
