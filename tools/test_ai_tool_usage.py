"""Tests for tools/ai_tool_usage.py: session parsing, the test-session filter, and the per-tool counts."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools import ai_tool_usage as usage


def write_session(root: Path, name: str, start: dict, events: list[dict]) -> None:
    lines = [{"event": "session_start", "timestamp": "2026-09-30T10:00:00Z", **start}, *events]
    (root / f"{name}.jsonl").write_text("\n".join(json.dumps(line) for line in lines) + "\n")


REAL = {"conversationId": "conv_abc", "fileKey": "afield://doc/box/code", "maxToolRounds": 24,
        "prompt": "make the box print the first ten primes please"}


def call(tool: str, parent: str = "") -> dict:
    record = {"event": "tool_call", "toolName": tool}
    if parent:
        record["parentCallId"] = parent
    return record


def result(tool: str, body: dict, ms: int = 5) -> dict:
    return {"event": "tool_result", "toolName": tool, "elapsedMs": ms, "result": json.dumps(body)}


class AiToolUsageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_test_sessions_are_dropped_unless_asked_for(self) -> None:
        write_session(self.root, "a", REAL, [call("RunBoxRequest")])
        write_session(self.root, "b", {**REAL, "conversationId": "conv_test"}, [call("RunBoxRequest")])
        write_session(self.root, "c", {**REAL, "prompt": "hi"}, [call("RunBoxRequest")])
        self.assertEqual(len(usage.load(self.root)), 1)
        self.assertEqual(len(usage.load(self.root, include_tests=True)), 3)

    def test_counts_shares_bursts_failures_and_nesting(self) -> None:
        write_session(self.root, "a", REAL, [
            call("RunBoxRequest"), result("RunBoxRequest", {"success": True}, 10),
            call("RunBoxRequest"), result("RunBoxRequest", {"success": False}, 20),
            call("RunBoxRequest", parent="call_1"), result("RunBoxRequest", {"success": True}, 30),
            {"event": "session_end", "status": "completed"},
        ])
        write_session(self.root, "b", REAL, [
            call("ListBoxesRequest"), result("ListBoxesRequest", {"boxes": []}),
            {"event": "tool_exception", "toolName": "ListBoxesRequest"},
        ])
        write_session(self.root, "c", REAL, [{"event": "session_end", "status": "completed"}])
        summary = usage.summarise(usage.load(self.root))
        run = summary["tools"]["RunBoxRequest"]
        self.assertEqual(summary["sessions"], 3)
        self.assertEqual(summary["sessionsWithCalls"], 2)
        self.assertEqual(run["calls"], 3)
        self.assertEqual(run["nested"], 1)
        self.assertEqual(run["sessionShare"], 0.5)
        self.assertEqual(run["burstShare"], 1.0)
        self.assertAlmostEqual(run["failureRate"], 1 / 3, places=3)
        self.assertEqual(run["medianElapsedMs"], 20)
        self.assertEqual(summary["tools"]["ListBoxesRequest"]["failureRate"], 1.0)

    def test_a_file_without_session_start_is_skipped(self) -> None:
        (self.root / "junk.jsonl").write_text('{"event": "tool_call", "toolName": "X"}\nnot json\n')
        self.assertEqual(usage.load(self.root, include_tests=True), [])

    def test_suggestion_thresholds(self) -> None:
        self.assertEqual(usage.suggest(0.2), "DIRECT")
        self.assertEqual(usage.suggest(0.02), "DIRECT?")
        self.assertEqual(usage.suggest(0.001), "DEFERRED")

    def test_cli_renders_a_table(self) -> None:
        write_session(self.root, "a", REAL, [call("RunBoxRequest"), result("RunBoxRequest", {"success": True})])
        self.assertEqual(usage.main(["--root", str(self.root), "--top", "5"]), 0)


if __name__ == "__main__":
    unittest.main()
