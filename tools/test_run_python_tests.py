"""A red in `checkPythonTools` must reach the lander by name, with its traceback.

On 2026-10-19 the lander's fixer declined a bounce because all it was handed was "uv exited 1":
the failing test was in the landing log, 54,000 lines above the tail it reads, and the lander's
failure collector reads only JUnit XML, which these suites did not write.
"""

from __future__ import annotations

import io
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from tools import land, run_python_tests


def _reds() -> type[unittest.TestCase]:
    """Built on demand: at module level the loader would run these reds as this suite's own."""
    class _Reds(unittest.TestCase):
        def test_fails(self):
            self.assertEqual(1, 2, "one is not two")

        def test_errors(self):
            raise RuntimeError("exploded")

        def test_subtest(self):
            with self.subTest(board="check_fences.py"):
                raise TimeoutError("timed out after 300 seconds")

    return _Reds


class RedsReachTheLander(unittest.TestCase):
    def test_failures_round_trip_through_junit_into_the_fixer_summary(self):
        result = unittest.TextTestRunner(stream=io.StringIO()).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(_reds()))
        with tempfile.TemporaryDirectory() as tmp:
            wt = Path(tmp)
            results = wt / "build" / "test-results" / "pythonTools"
            results.mkdir(parents=True)
            started = time.time() - 1
            with mock.patch.object(run_python_tests, "RESULTS_DIR", results):
                run_python_tests.write_junit("tools.test_reds", result.testsRun,
                                             result.failures, result.errors, 0)
            log = wt / "round.log"
            summary = land._collect_failures(wt, started, log)
            self.assertIsNotNone(summary)
            self.assertIn("(+2 more)", summary)
            full = log.with_suffix(".failures.txt").read_text()
        self.assertIn("_Reds.test_fails", full)
        self.assertIn("AssertionError: 1 != 2 : one is not two", full)
        self.assertIn("RuntimeError: exploded", full)
        self.assertIn("test_subtest (board='check_fences.py')", full)
        self.assertIn("Traceback (most recent call last)", full, "the traceback, not just the last line")


if __name__ == "__main__":
    unittest.main()
