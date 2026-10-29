"""Tests for tools/ai4_conversation_review.py: the four signals it reads from a conversation's turn logs."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools import ai4_conversation_review as review


def write_turn(root: Path, name: str, timestamp: str, request: str, events: list[dict]) -> None:
    start = {"event": "session_start", "timestamp": timestamp, "conversationId": "local:abc", "fileKey": "afield://doc/box/code",
             "maxToolRounds": 24, "model": "qwen", "provider": "local", "prompt": "You are an IDE-embedded assistant.\n\n" + request}
    (root / f"{name}.jsonl").write_text("\n".join(json.dumps(line) for line in [start, *events]) + "\n")


def call(tool: str, cid: str, args: dict | None = None) -> dict:
    return {"event": "tool_call", "toolName": tool, "callId": cid, "arguments": json.dumps(args or {})}


def result(tool: str, cid: str, body: dict) -> dict:
    return {"event": "tool_result", "toolName": tool, "callId": cid, "result": json.dumps(body)}


END = {"event": "session_end", "status": "completed", "textChars": 10}


class ConversationReviewTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def reviewed(self) -> dict:
        conversations = review.load_conversations(self.root, "", "", merge_file_key=False)
        self.assertEqual(1, len(conversations))
        return review.review(next(iter(conversations.values())), 65536)

    def test_tool_mistakes_are_reported_and_box_errors_are_not(self) -> None:
        write_turn(self.root, "t1", "2026-10-22T07:44:00Z", "put it in this box", [
            call("ListContentsRequest", "a"), result("ListContentsRequest", "a", {"error": True, "message": "No text content"}),
            call("RunBoxRequest", "b"), result("RunBoxRequest", "b", {"success": False, "stage": "compile", "errors": ["e"]}),
            END])
        turn = self.reviewed()["turns"][0]
        self.assertEqual(["ListContentsRequest"], [f["tool"] for f in turn["toolFailures"]])
        self.assertEqual("put it in this box", turn["request"])

    def test_a_run_of_lookups_after_a_change_is_counted(self) -> None:
        events = [call("NewBoxRequest", "n"), result("NewBoxRequest", "n", {"success": True})]
        events += [call("WorkspaceSymbolSearch", f"s{i}", {"query": "FLines"}) for i in range(5)]
        write_turn(self.root, "t1", "2026-10-22T07:47:00Z", "perhaps using FLines", events + [END])
        self.assertEqual(5, len(self.reviewed()["turns"][0]["lookupRun"]))

    def test_a_correction_marks_the_turn_before_and_failures_and_empty_answers_show(self) -> None:
        write_turn(self.root, "t1", "2026-10-22T08:01:00Z", "sure", [END])
        write_turn(self.root, "t2", "2026-10-22T08:07:00Z", "are you only plotting odd numbers? Look again",
                   [{"event": "session_end", "status": "completed", "textChars": 0}])
        write_turn(self.root, "t3", "2026-10-22T08:08:00Z", "?",
                   [{"event": "session_failed", "status": "failed", "message": "400: request (70579 tokens) exceeds the available context size"}])
        turns = self.reviewed()["turns"]
        self.assertIn("Look again", turns[0]["correctedBy"])
        self.assertTrue(turns[1]["emptyAnswer"])
        self.assertIn("exceeds", turns[2]["failure"])
        self.assertIn("corrected next turn", review.render([self.reviewed()]))

    def test_the_logged_request_wins_over_the_prompt_tail(self) -> None:
        write_turn(self.root, "t1", "2026-10-22T08:01:00Z", "tail", [END])
        path = self.root / "t1.jsonl"
        lines = path.read_text().splitlines()
        start = json.loads(lines[0])
        start["request"] = "draw an Ulam spiral"
        path.write_text("\n".join([json.dumps(start), *lines[1:]]) + "\n")
        self.assertEqual("draw an Ulam spiral", self.reviewed()["turns"][0]["request"])


if __name__ == "__main__":
    unittest.main()
