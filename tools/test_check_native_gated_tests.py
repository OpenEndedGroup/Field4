"""Tests for tools/check_native_gated_tests.py, including a run over the real tree."""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import check_native_gated_tests as checker  # noqa: E402


def flagged(source: str) -> list[str]:
    return checker.offenders_in(Path("T.kt"), source)


class Patterns(unittest.TestCase):
    def test_one_line_forms_are_flagged(self) -> None:
        for line in (
            "if (!available()) return",
            "if (!ManifoldLibrary.isAvailable) return",
            'if (!PdLibrary.isAvailable) return println("SKIPPED: ${PdLibrary.describe()}")',
            "if (!TetgenLibrary.isAvailable || !ManifoldLibrary.isAvailable) return",
            "if (!available()) return@forEach",
        ):
            self.assertEqual(len(flagged(f"    @Test fun t() {{\n        {line}\n    }}")), 1, line)

    def test_block_forms_are_flagged(self) -> None:
        self.assertEqual(len(flagged('if (!X.isAvailable) { println("no; really"); return }')), 1)
        self.assertEqual(len(flagged('if (!X.isAvailable) {\n    println("skip")\n    return\n}')), 1)

    def test_assumptions_helpers_and_comments_pass(self) -> None:
        for source in (
            'org.junit.Assume.assumeTrue("missing", ManifoldLibrary.isAvailable)',
            "assumeAvailable()",
            'if (!SteamAudioNatives.isAvailable) {\n    println("x")\n    return false\n}',
            "// if (!available()) return",
            'if (!X.isAvailable) println("SKIPPED")',
        ):
            self.assertEqual(flagged(source), [], source)


class AgainstTheRealTree(unittest.TestCase):
    def test_the_repository_is_clean(self) -> None:
        result = subprocess.run(
            [sys.executable, str(TOOLS / "check_native_gated_tests.py")],
            capture_output=True, text=True, cwd=TOOLS.parent,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_the_real_tree_has_native_gated_tests_to_check(self) -> None:
        """A checker that finds nothing to look at passes vacuously."""
        text = (TOOLS.parent / "src/jvmTest/kotlin/afield/geometry/solid/manifold/FusingTest.kt").read_text()
        self.assertIn("ManifoldLibrary.isAvailable", text)


if __name__ == "__main__":
    unittest.main()
