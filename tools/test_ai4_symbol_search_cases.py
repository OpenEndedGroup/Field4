"""Tests for tools/ai4_symbol_search_cases.py: what a logged symbol search is labelled with."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools import ai4_symbol_search_cases as cases
from tools.ai_tool_usage import read_session


def turn(root: Path, name: str, timestamp: str, events: list[dict], conversation: str = "conv_real", model: str = "gpt-6.1-sol") -> None:
    start = {"event": "session_start", "timestamp": timestamp, "conversationId": conversation, "fileKey": "afield://doc/box/code",
             "maxToolRounds": 24, "model": model, "provider": "openai", "prompt": "You are an IDE-embedded assistant. " * 3}
    (root / f"{name}.jsonl").write_text("\n".join(json.dumps(line) for line in [start, *events]) + "\n")


def call(tool: str, cid: str, args: dict) -> dict:
    return {"event": "tool_call", "toolName": tool, "callId": cid, "arguments": json.dumps(args)}


def result(tool: str, cid: str, body: dict) -> dict:
    return {"event": "tool_result", "toolName": tool, "callId": cid, "result": json.dumps(body)}


class IdentifierTest(unittest.TestCase):
    def test_dotted_and_bare_names_are_told_apart(self) -> None:
        names = cases.identifiers("val axis = delta.normalized(1e-12f) * quat(p.rotation)\nprintln(\"quat in a string\")")
        self.assertIn(("quat", False), names)
        self.assertIn(("normalized", True), names)
        self.assertIn(("rotation", True), names)
        self.assertNotIn("axis", [n for n, _ in names])  # declared here
        self.assertEqual(1, [n for n, _ in names].count("quat"))  # the string's quat is not code

    def test_package_paths_named_arguments_and_lambda_parameters_are_not_references(self) -> None:
        names = [n for n, _ in cases.identifiers(
            "val f = afield.graphics.FLine()\nlines(f, fitToSize = true)\nlistOf(1).map { it2 -> it2 + 1 }\nfor (k in 0 until 3) {}")]
        self.assertIn("FLine", names)
        self.assertIn("lines", names)
        for absent in ("afield", "graphics", "fitToSize", "it2", "k"):
            self.assertNotIn(absent, names)

    def test_template_expressions_are_code(self) -> None:
        names = [n for n, _ in cases.identifiers('println("size ${points.size} at ${vec(1, 2)}")')]
        self.assertIn("vec", names)
        self.assertIn("points", names)


class CasesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def load(self) -> list[dict]:
        sessions = [s for s in (read_session(p) for p in sorted(self.root.glob("*.jsonl"))) if s and not cases.is_fixture_session(s)]
        return cases.cases_from_sessions(sessions)

    def test_a_turn_that_only_looked_is_labelled_by_the_next_turn(self) -> None:
        """2026-11-06: 'isn't there something like this on Vector2f already?', then 'ok, let's use quat then'."""
        turn(self.root, "a", "2026-11-06T20:07:41Z", [
            call("GetBoxPropertyRequest", "c0", {"box": "b", "property": "code"}),
            result("GetBoxPropertyRequest", "c0", {"value": "fun Vector2f.rotated(r: Double) = vec(x, y)\nval v = vec(1, 2).rotated(0.1)"}),
            call("WorkspaceSymbolSearch", "c1", {"query": "Vector2f.rotate", "limit": 10}),
            result("WorkspaceSymbolSearch", "c1", {"matches": [{"symbol": "rotate"}, {"symbol": "PointDriver.rotate"}]}),
        ])
        turn(self.root, "b", "2026-11-06T20:10:18Z", [
            call("SetBoxPropertyRequest", "c2", {"box": "b", "property": "code", "value": "val v = vec(1, 2) * quat(0.1)\nval w = v.rotated(1.0)"}),
            call("AttachmentRequest", "c3", {"file": "/Users/someone/field4/src/jvmMain/kotlin/afield/math/linalg/linalg.kt", "find": "fun quat"}),
        ])
        [case] = self.load()
        self.assertEqual("Vector2f.rotate", case["query"])
        self.assertEqual(["quat"], case["written"])  # vec and rotated were already in the box
        self.assertEqual([{"file": "src/jvmMain/kotlin/afield/math/linalg/linalg.kt", "find": "fun quat"}], case["opened"])
        self.assertEqual(["rotate", "PointDriver.rotate"], case["loggedTop"])
        self.assertEqual(2, case["callsInLaterTurns"])

    def test_another_conversation_never_labels_a_search(self) -> None:
        turn(self.root, "a", "2026-11-06T10:00:00Z", [call("WorkspaceSymbolSearch", "c1", {"query": "quat"})], conversation="one")
        turn(self.root, "b", "2026-11-06T10:01:00Z", [call("NewBoxRequest", "c2", {"name": "x", "parent": "", "code": "quat(1)"})], conversation="two")
        [case] = self.load()
        self.assertEqual([], case["written"])

    def test_a_dotted_write_is_recorded_with_its_dot(self) -> None:
        turn(self.root, "a", "2026-11-06T10:00:00Z", [
            call("WorkspaceSymbolSearch", "c1", {"query": "Solid.translate"}),
            call("PatchBoxTextRequest", "c2", {"box": "b", "property": "code", "find": "s", "replace": "s.translated(1, 0, 0)"}),
        ])
        [case] = self.load()
        self.assertEqual([".translated"], case["written"])

    def test_test_fixture_turns_are_skipped(self) -> None:
        turn(self.root, "a", "2026-11-06T10:00:00Z", [call("WorkspaceSymbolSearch", "c1", {"query": "quat"})], conversation="conv_test")
        turn(self.root, "b", "2026-11-06T10:00:00Z", [call("WorkspaceSymbolSearch", "c1", {"query": "quat"})], model="claude-test")
        self.assertEqual([], self.load())

    def test_the_committed_fixture_parses(self) -> None:
        lines = cases.DEFAULT_OUT.read_text(encoding="utf-8").splitlines()
        self.assertGreater(len(lines), 100)
        for line in lines:
            case = json.loads(line)
            self.assertTrue(case["query"])
            self.assertTrue(all(isinstance(w, str) for w in case["written"]))


if __name__ == "__main__":
    unittest.main()
