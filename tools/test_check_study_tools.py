#!/usr/bin/env python3
"""Hold `check_study_tools.py` to its two rules, and to running over the real tree.

The checker guards the extraction done in architecture review 3: a study's standard verbs are wired
once by `registerStudyTools`, so a `*StudyTools.kt` may declare their request classes (names and
schemas are the model's contract) but each must implement its `StudyRequest.*` interface, and none may
be registered by hand. `tools/run_python_tests.py` discovers this suite by glob, which is what gates
the checker in `checkPythonTools`.

Run with:  uv run --project . python -m unittest tools.test_check_study_tools
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools import check_study_tools as checker

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKER = REPO_ROOT / "tools" / "check_study_tools.py"

# Measured 2026-09-29: function, StackSL, RectSL, stack.auto. A floor, so a deleted file is noticed.
RECORDED_TOOL_FILES = 4


def _repo(tmp: Path, body: str) -> Path:
    path = tmp / "src" / "jvmMain" / "kotlin" / "afield" / "pond" / "PondStudyTools.kt"
    path.parent.mkdir(parents=True)
    path.write_text("package afield.pond\n\n" + body, encoding="utf-8")
    return tmp


class AgainstTheRealTree(unittest.TestCase):
    def test_the_repository_passes(self) -> None:
        result = subprocess.run([sys.executable, str(CHECKER), "--root", str(REPO_ROOT)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_it_sees_every_study_tools_file(self) -> None:
        self.assertGreaterEqual(len(checker.study_tool_files(REPO_ROOT)), RECORDED_TOOL_FILES)


class TheRules(unittest.TestCase):
    def test_a_standard_request_that_skips_the_interface_fails(self) -> None:
        with TemporaryDirectory() as tmp:
            root = _repo(Path(tmp), 'data class ObservePondStudy(val revision: String = "current", val seconds: List<Double> = emptyList())\n')
            problems = checker.check_file(next(checker.study_tool_files(root).__iter__()), root)
            self.assertEqual(1, len(problems), problems)
            self.assertIn("ObservePondStudy", problems[0])
            self.assertIn("StudyRequest.Observe", problems[0])

    def test_implementing_the_interface_passes_even_on_a_continuation_line(self) -> None:
        with TemporaryDirectory() as tmp:
            root = _repo(Path(tmp), 'data class SweepPondNumber(override val knob: Int, override val values: List<Double>) :\n'
                                    '    StudyRequest.Sweep, StudyRequest.At\n')
            self.assertEqual([], checker.check_file(checker.study_tool_files(root)[0], root))

    def test_registering_a_standard_verb_by_hand_fails(self) -> None:
        with TemporaryDirectory() as tmp:
            root = _repo(Path(tmp), 'data class InspectPondStudy(override val revision: String = "current") : StudyRequest.Inspect\n\n'
                                    'fun install() {\n    r.register(InspectPondStudy::class.java, gate, LIBRARY) { ctx, q -> 1 }\n}\n')
            problems = checker.check_file(checker.study_tool_files(root)[0], root)
            self.assertEqual(1, len(problems), problems)
            self.assertIn("registered by hand", problems[0])

    def test_extras_and_listed_differences_pass(self) -> None:
        with TemporaryDirectory() as tmp:
            root = _repo(Path(tmp), 'data class TracePondStudy(val revision: String = "current")\n'
                                    'data class ObserveStackAutoSlot(val slot: String = "")\n')
            self.assertEqual([], checker.check_file(checker.study_tool_files(root)[0], root))

    def test_no_files_is_a_failure_not_a_pass(self) -> None:
        with TemporaryDirectory() as tmp:
            (Path(tmp) / "src").mkdir()
            self.assertEqual(1, checker.main(["--root", tmp]))


if __name__ == "__main__":
    unittest.main()
