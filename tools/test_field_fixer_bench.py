"""tools/field_fixer_bench.py: the parts that need no Field, no queue and no scratch worktree."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import field_fixer_bench as bench  # noqa: E402


def row(**kw) -> dict:
    base = {"id": "abc", "branch": "b", "checks": [], "controls": True, "ai4_verdict": "fix", "touched": ["x"],
            "lander_problem": None, "identical": False, "check_pass": True, "similarity": 0.5, "wall_s": 1}
    return {**base, **kw}


class OutcomeTest(unittest.TestCase):
    def test_ordering_of_outcomes(self) -> None:
        self.assertEqual(bench.outcome(row(identical=True)), "identical")
        self.assertEqual(bench.outcome(row()), "check passes")
        self.assertEqual(bench.outcome(row(check_pass=False)), "check red")
        self.assertEqual(bench.outcome(row(controls=False, check_pass=None)), "ungraded")
        self.assertEqual(bench.outcome(row(lander_problem="it deletes a file", identical=True)), "rejected")
        self.assertEqual(bench.outcome(row(ai4_verdict="decline", touched=[])), "declined")
        self.assertEqual(bench.outcome(row(ai4_verdict=None, touched=[])), "no verdict")

    def test_table_has_a_row_per_result(self) -> None:
        text = bench.table([row(id="one"), row(id="two", checks=[["junit", "afield.x.FooTest"]])])
        self.assertEqual(len(text.splitlines()), 4)
        self.assertIn("FooTest", text)


class CheckSpecTest(unittest.TestCase):
    def test_only_the_test_that_now_passes_is_a_check(self) -> None:
        case = {"reason": "StereoTest.kt put @Test and the fun on one line; splitting it makes checkKDocAttachment pass.",
                "log": None}
        self.assertEqual(bench.check_spec(case, bench.REPO), [["main", "afield.tools.docs.KDocAttachmentGuard"]])

    def test_named_passing_test_resolves_in_this_checkout(self) -> None:
        case = {"reason": "adding the row makes ServerSurfaceLedgerTest pass.", "log": None}
        self.assertEqual(bench.check_spec(case, bench.REPO), [["junit", "afield.app.server.ServerSurfaceLedgerTest"]])


if __name__ == "__main__":
    unittest.main()
