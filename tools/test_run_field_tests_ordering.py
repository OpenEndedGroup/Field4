"""Regression tests for smoke catalog ordering and for `--id` selection diagnostics."""

import sys
import subprocess
import json
import tempfile
import unittest
import unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.run_field_tests import (  # noqa: E402
    RunnerError,
    apply_expected_smoke_failure,
    coverage_snapshot,
    describe_unmatched_ids,
    filtered_out_ids,
    load_coverage_state,
    load_expected_smoke_failures,
    order_tests_by_coverage,
    record_coverage_result,
    run_one_test,
    unmatched_ids,
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

    def test_transport_failure_is_not_reported_as_a_test_assertion(self) -> None:
        process = subprocess.CompletedProcess(["fake"], 1, stdout="", stderr="connection refused")
        with unittest.mock.patch(
            "tools.run_field_tests.build_backdoor_command", return_value=(["fake", "--command"], {})
        ), unittest.mock.patch(
            "tools.run_field_tests.run_with_watchdog", return_value=(process, 12, False)
        ):
            result = run_one_test({"id": "transport", "runner": "jvm-main"}, port=9999, workspace=None)
        self.assertFalse(result["ok"])
        self.assertEqual(result["failureKind"], "infrastructure")
        self.assertIn("connection refused", result["failures"][0])

    def test_parseable_failed_command_is_a_test_failure(self) -> None:
        payload = '{"ok":false,"command":"run","data":{"error":"assertion failed"}}\n'
        process = subprocess.CompletedProcess(["fake"], 1, stdout=payload, stderr="")
        with unittest.mock.patch(
            "tools.run_field_tests.build_backdoor_command", return_value=(["fake", "--command"], {})
        ), unittest.mock.patch(
            "tools.run_field_tests.run_with_watchdog", return_value=(process, 12, False)
        ):
            result = run_one_test({"id": "assertion", "runner": "jvm-main"}, port=9999, workspace=None)
        self.assertFalse(result["ok"])
        self.assertEqual(result["failureKind"], "test")


class ExpectedSmokeFailureTests(unittest.TestCase):
    ENTRY = {
        "id": "known.red",
        "reason": "The defect remains deliberately visible.",
        "documentation": "finding.md#known-red",
    }

    def test_registered_test_failure_is_accepted(self) -> None:
        result = {"id": "known.red", "ok": False, "failureKind": "test", "failures": ["boom"]}
        apply_expected_smoke_failure(result, {"known.red": self.ENTRY})
        self.assertTrue(result["acceptedFailure"])
        self.assertFalse(result["ok"])

    def test_registered_pass_is_a_contract_failure(self) -> None:
        result = {"id": "known.red", "ok": True, "failureKind": None, "failures": []}
        apply_expected_smoke_failure(result, {"known.red": self.ENTRY})
        self.assertFalse(result["ok"])
        self.assertEqual("contract", result["failureKind"])
        self.assertIn("registered smoke failure passed", result["failures"][0])

    def test_infrastructure_failure_is_never_accepted(self) -> None:
        result = {
            "id": "known.red",
            "ok": False,
            "failureKind": "infrastructure",
            "failures": ["no transport"],
        }
        apply_expected_smoke_failure(result, {"known.red": self.ENTRY})
        self.assertFalse(result["acceptedFailure"])

    def test_registry_rejects_missing_ids_and_wildcards(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "finding.md").write_text("# Known red\n", encoding="utf-8")
            registry = root / "expected.json"
            registry.write_text(
                json.dumps({"version": 1, "expectedFailures": [self.ENTRY]}), encoding="utf-8"
            )
            with self.assertRaisesRegex(RunnerError, "absent from the catalog"):
                load_expected_smoke_failures(registry, catalog_ids=set(), root=root)
            wildcard = {**self.ENTRY, "id": "known.*"}
            registry.write_text(
                json.dumps({"version": 1, "expectedFailures": [wildcard]}), encoding="utf-8"
            )
            with self.assertRaisesRegex(RunnerError, "must be exact"):
                load_expected_smoke_failures(registry, catalog_ids={"known.*"}, root=root)


if __name__ == "__main__":
    unittest.main()


class RequestedIdDiagnosticTests(unittest.TestCase):
    """`--id` combined with a `--tag` it does not carry used to be reported as nonexistent.

    Observed 2026-08-26, asking for six ids alongside `--tag context --tag regression`:

        6 requested --id value(s) matched no test in the catalog:
          - docs.fabricate.shadows  (did you mean: docs.fabricate.shadows, ...?)
        The catalog was refreshed before this check, so these ids are absent from the
        FIELD_TEST blocks in the scanned roots, not merely stale.

    Every one of those ids was in the catalog — the suggester found each of them there
    and offered it back as its own nearest match. `unmatched_ids` was measured against
    the post-filter *selection*, so `--tag` narrowing was indistinguishable from a typo,
    and the closing sentence asserted something false as fact. Both cases still refuse
    to run; they now say which one happened.
    """

    def test_an_id_absent_from_the_catalog_is_reported_as_absent(self) -> None:
        catalogued = tests("a.one", "a.two")
        self.assertEqual(unmatched_ids(catalogued, {"a.one", "b.nope"}), {"b.nope"})

    def test_an_id_the_catalog_has_is_never_reported_as_absent(self) -> None:
        """The regression, stated directly: a filter narrowing an id away is not absence."""
        catalogued = tests("a.one", "a.two")
        selected = tests("a.one")
        self.assertEqual(unmatched_ids(catalogued, {"a.one", "a.two"}), set())
        self.assertEqual(filtered_out_ids(catalogued, selected, {"a.one", "a.two"}), {"a.two"})

    def test_the_two_reasons_are_described_apart(self) -> None:
        catalogued = tests("a.one", "a.two")
        message = describe_unmatched_ids(
            {"b.nope"},
            catalogued,
            filtered={"a.two"},
            tags={"context"},
        )
        self.assertIn("1 requested --id value(s) matched no test in the catalog", message)
        self.assertIn("b.nope", message)
        self.assertIn("removed by --tag context", message)
        self.assertIn("a.two", message)
        self.assertIn("intersect rather than union", message)
        # The false-as-fact sentence must attach only to the ids it is true of.
        absent_paragraph, _, filtered_paragraph = message.partition(
            "are in the catalog but were removed"
        )
        self.assertIn("absent from the", absent_paragraph)
        self.assertNotIn("absent from the", filtered_paragraph)

    def test_nothing_is_said_when_every_id_matched(self) -> None:
        self.assertEqual(describe_unmatched_ids(set(), tests("a.one"), filtered=set()), "")
