"""A recall comparison must exercise the same nonempty, uniquely named fixtures."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import completion_diff
from verification_environment import VerificationEnvironmentError


SCRIPT = Path(__file__).with_name("completion_diff.py")


def fixture(fid="one", member="length", is_dot=True):
    return {"id": fid, "member": member, "isDot": is_dot, "source": "example.le‸", "candidates": [
        {"name": member, "text": member, "displayText": member, "kind": "MEMBER"}
    ]}


class CompletionDiffTest(unittest.TestCase):
    def check_gate(self, k1, aa, passed, diagnostic=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "k1.json").write_text(json.dumps(k1))
            (root / "aa.json").write_text(json.dumps(aa))
            result = subprocess.run([
                sys.executable, str(SCRIPT), "--workspace", directory, "--gate",
                "--k1", "k1.json", "--aa", "aa.json",
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, passed, result.stdout + result.stderr)
            self.assertIn(diagnostic, result.stdout + result.stderr)

    def test_matching_real_candidates_pass(self):
        self.check_gate([fixture()], [fixture()], True)

    def test_empty_dumps_cannot_pass(self):
        self.check_gate([], [], False, "no shared fixtures")

    def test_missing_fixtures_in_either_dump_cannot_pass(self):
        for k1, aa in [([fixture(), fixture("two")], [fixture()]),
                       ([fixture()], [fixture(), fixture("two")])]:
            with self.subTest(k1=len(k1), aa=len(aa)):
                self.check_gate(k1, aa, False, "only in")

    def test_different_expectations_or_positions_cannot_pass(self):
        for changed in [fixture(member="size"), fixture(is_dot=False)]:
            with self.subTest(changed=changed):
                self.check_gate([fixture()], [changed], False, "different fixture definitions")

    def test_old_or_different_source_cannot_be_compared(self):
        old = fixture()
        del old["source"]
        different = fixture()
        different["source"] = "other.le‸"
        for aa in [old, different]:
            with self.subTest(aa=aa):
                self.check_gate([fixture()], [aa], False, "different fixture definitions")
        self.check_gate([old], [old], False, "different fixture definitions")

    def test_duplicate_ids_cannot_silently_overwrite_results(self):
        for k1, aa in [([fixture(), fixture()], [fixture()]),
                       ([fixture()], [fixture(), fixture()])]:
            with self.subTest(k1=len(k1), aa=len(aa)):
                self.check_gate(k1, aa, False, "duplicate fixture id")

    def test_lost_candidate_still_fails(self):
        missing = fixture()
        missing["candidates"] = []
        self.check_gate([fixture()], [missing], False, "REGRESSION")


class RegenerationFailuresTest(unittest.TestCase):
    def test_power_refusal_stops_before_build_and_closes_monitor(self):
        monitor = Mock()
        monitor.start.return_value = monitor
        monitor.checkpoint.side_effect = VerificationEnvironmentError("power: battery 47%")
        with patch.object(completion_diff, "VerificationEnvironment", return_value=monitor), patch.object(
            completion_diff.subprocess, "run"
        ) as run:
            with self.assertRaisesRegex(VerificationEnvironmentError, "battery 47%"):
                completion_diff.regenerate(Path.cwd())
            run.assert_not_called()
            monitor.close.assert_called_once()

    def test_environment_refusal_never_compares_stale_passing_dumps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("k1", "aa"):
                (root / f"{name}.json").write_text(json.dumps([fixture()]))
            result = subprocess.run([
                sys.executable, str(SCRIPT), "--workspace", directory, "--run", "--gate",
                "--k1", "k1.json", "--aa", "aa.json", "--json-summary", "summary.json",
            ], env={**os.environ, "FIELD_VERIFY_DISK_FLOOR_GIB": "1000000000"},
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            self.assertIn("NOT RUN", result.stderr)
            self.assertIn("disk floor", result.stderr)
            self.assertNotIn("Traceback", result.stderr)
            self.assertNotIn("PASS", result.stdout)
            self.assertFalse((root / "build/completion-corpus/fieldcp.txt").exists())
            self.assertEqual(json.loads((root / "summary.json").read_text())["status"], "NOT RUN")

    def test_k1_environment_incomplete_remains_distinct_from_failure(self):
        for code in (1, 2):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as directory:
                calls = [subprocess.CompletedProcess([], 0),
                         subprocess.CompletedProcess([], 0, stdout="CP\tfield.jar\n"),
                         subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], code)]
                error = VerificationEnvironmentError if code == 2 else subprocess.CalledProcessError
                with patch.object(completion_diff.subprocess, "run", side_effect=calls):
                    with self.assertRaises(error):
                        completion_diff._regenerate(Path(directory), Mock(), 0)

    def test_build_failure_returns_red_without_traceback_or_stale_comparison(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--run"]), patch.object(
            completion_diff, "regenerate", side_effect=subprocess.CalledProcessError(1, ["./gradlew", "compileKotlinJvm"])
        ), patch.object(completion_diff, "load") as load, patch("sys.stderr") as stderr:
            self.assertEqual(completion_diff.main(), 1)
            load.assert_not_called()
            self.assertIn("VERDICT: FAIL", str(stderr.write.call_args_list))


class ScrapedCorpusTest(unittest.TestCase):
    def test_committed_corpus_matches_current_documentation(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "scraped.tsv"
            subprocess.run([
                sys.executable, str(SCRIPT.with_name("scrape_completion_corpus.py")),
                "--out", str(output),
            ], check=True, capture_output=True, text=True)
            expected = SCRIPT.parent / "completion-corpus" / "scraped.tsv"
            self.assertTrue(output.read_text().strip(), "the scrape must exercise real fixtures")
            self.assertEqual(expected.read_text(), output.read_text(),
                             "regenerate with: uv run python tools/scrape_completion_corpus.py")


if __name__ == "__main__":
    unittest.main()
