"""Structured check records (`afield.tools.testing.Checks`) are collected per test, not scraped."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.run_field_tests import (CHECK_LINE_PREFIX, collect_check_records, describe_check,
                                   format_check_table)


def line(**record):
    return CHECK_LINE_PREFIX + json.dumps(record)


COST = {"name": "least-squares cost", "measured": 1.2e-14, "expected": "< 1e-10", "tolerance": None,
        "passed": True, "note": None, "provenance": "convergence target"}
DRIFT = {"name": "hit y", "measured": 0.9, "expected": 0.62, "tolerance": 0.18,
         "passed": False, "note": "two\nlines", "provenance": None}


class CheckRecordTests(unittest.TestCase):
    def test_jvm_main_stdout_records_are_collected_in_order(self):
        stdout = "Running...\n" + line(**COST) + "\nnoise FIELD_CHECK {not a record}\n" + line(**DRIFT) + "\ndone\n"
        records, problems = collect_check_records([{"ok": True, "data": {"stdout": stdout}, "events": []}])
        self.assertEqual(problems, [])
        self.assertEqual(records, [COST, DRIFT])

    def test_box_println_is_read_verbatim_and_peer_output_not_double_counted(self):
        # As a box's `println` arrives (measured): the line itself in `html`, one event per line.
        rule = {**COST, "provenance": 'a <rule> & "quote"'}
        events = [{"name": "output", "payload": {"html": line(**rule) + "\n"}},
                  {"name": "output", "payload": {"html": line(**DRIFT) + "\n"}},
                  {"name": "peer-output", "payload": {"html": line(**DRIFT) + "\n"}},
                  {"name": "output", "payload": {"html": "done\n"}}]
        records, problems = collect_check_records([{"ok": True, "data": {}, "events": events}])
        self.assertEqual(problems, [])
        self.assertEqual(records, [rule, DRIFT])

    def test_malformed_record_is_a_problem_not_silently_dropped(self):
        stdout = CHECK_LINE_PREFIX + "{oops\n" + CHECK_LINE_PREFIX + json.dumps({"name": "x"}) + "\n"
        records, problems = collect_check_records([{"ok": True, "data": {"stdout": stdout}}])
        self.assertEqual(records, [])
        self.assertEqual(len(problems), 2)
        self.assertIn("malformed check record", problems[0])
        self.assertIn("boolean passed", problems[1])

    def test_missing_keys_are_normalised_to_null(self):
        records, _ = collect_check_records([{"data": {"stdout": line(name="bare", passed=True)}}])
        self.assertEqual(records, [{"name": "bare", "measured": None, "expected": None, "tolerance": None,
                                    "passed": True, "note": None, "provenance": None}])

    def test_table_marks_failures_and_shows_tolerance(self):
        table = format_check_table([COST, DRIFT])
        self.assertEqual(table[0], "  checks: 1/2 passed")
        self.assertIn("provenance", table[1])
        self.assertTrue(table[2].strip().startswith("ok"))
        self.assertTrue(table[3].strip().startswith("FAIL"))
        self.assertIn("0.62 ± 0.18", table[3])
        self.assertEqual(format_check_table([]), [])
        self.assertEqual(describe_check(DRIFT), "hit y (measured 0.9, expected 0.62 ± 0.18)")


if __name__ == "__main__":
    unittest.main()
