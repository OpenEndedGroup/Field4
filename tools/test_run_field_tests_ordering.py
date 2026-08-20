"""Regression tests for least-recently-completed smoke catalog ordering."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.run_field_tests import (  # noqa: E402
    RunnerError,
    coverage_snapshot,
    load_coverage_state,
    order_tests_by_coverage,
    record_coverage_result,
    write_coverage_state,
)


def tests(*ids: str) -> list[dict[str, str]]:
    return [{"id": test_id} for test_id in ids]


class CoverageOrderingTests(unittest.TestCase):
    def test_first_run_preserves_catalog_order(self) -> None:
        ordered = order_tests_by_coverage(tests("a", "b", "c"), {"tests": {}})
        self.assertEqual([test["id"] for test in ordered], ["a", "b", "c"])

    def test_next_partial_run_starts_after_completed_prefix(self) -> None:
        state = {"version": 1, "tests": {}}
        record_coverage_result(state, test_id="a", ok=True, completed_at=10.0)
        record_coverage_result(state, test_id="b", ok=True, completed_at=20.0)
        ordered = order_tests_by_coverage(tests("a", "b", "c", "d"), state)
        self.assertEqual([test["id"] for test in ordered], ["c", "d", "a", "b"])

    def test_completed_failure_does_not_starve_the_unseen_tail(self) -> None:
        state = {"version": 1, "tests": {}}
        record_coverage_result(state, test_id="a", ok=False, completed_at=10.0)
        ordered = order_tests_by_coverage(tests("a", "b", "c"), state)
        self.assertEqual([test["id"] for test in ordered], ["b", "c", "a"])
        self.assertFalse(state["tests"]["a"]["ok"])

    def test_oldest_completed_test_wins_after_every_test_has_a_verdict(self) -> None:
        state = {
            "version": 1,
            "tests": {
                "a": {"completedAt": 30.0, "ok": True},
                "b": {"completedAt": 10.0, "ok": True},
                "c": {"completedAt": 20.0, "ok": True},
            },
        }
        ordered = order_tests_by_coverage(tests("a", "b", "c"), state)
        self.assertEqual([test["id"] for test in ordered], ["b", "c", "a"])

    def test_snapshot_reports_unseen_count_and_oldest_age(self) -> None:
        state = {"version": 1, "tests": {"a": {"completedAt": 25.0, "ok": True}}}
        self.assertEqual(
            coverage_snapshot(tests("a", "b"), state, now=100.0),
            {"unseen": 1, "oldestCompletedAgeSec": 75.0},
        )

    def test_atomic_write_prunes_removed_catalog_entries(self) -> None:
        state = {
            "version": 1,
            "tests": {
                "current": {"completedAt": 10.0, "ok": True},
                "removed": {"completedAt": 5.0, "ok": False},
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "coverage.json"
            write_coverage_state(path, state, current_test_ids={"current"})
            loaded = load_coverage_state(path)
        self.assertEqual(set(loaded["tests"]), {"current"})

    def test_corrupt_state_is_an_explicit_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "coverage.json"
            path.write_text("{broken", encoding="utf-8")
            with self.assertRaisesRegex(RunnerError, "Cannot read smoke coverage state"):
                load_coverage_state(path)


if __name__ == "__main__":
    unittest.main()
