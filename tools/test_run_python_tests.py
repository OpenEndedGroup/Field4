"""A red in `checkPythonTools` must reach the lander by name, with its traceback.

On 2026-10-19 the lander's fixer declined a bounce because all it was handed was "uv exited 1":
the failing test was in the landing log, 54,000 lines above the tail it reads, and the lander's
failure collector reads only JUnit XML, which these suites did not write.
"""

from __future__ import annotations

import io
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from tools import land, run_python_tests
from tools.git_isolation import scrub_process_env  # noqa: E402

# This module builds scratch git repositories; an inherited GIT_DIR would redirect them.
scrub_process_env()


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



class AHungModuleNamesItself(unittest.TestCase):
    """The 2026-10-21 hang: a module stuck for 1 h 53 min, and a log that said nothing about where."""

    def test_a_module_waiting_on_a_hung_child_is_dumped_killed_and_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            wt = Path(tmp)
            results = wt / "build" / "test-results" / "pythonTools"
            results.mkdir(parents=True)
            started = time.time() - 1
            out = io.StringIO()
            abandoned = []
            with mock.patch.object(run_python_tests, "RESULTS_DIR", results):
                child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(600)"])
                with run_python_tests.ModuleWatchdog("tools.test_hangs", 1.0, lambda: abandoned.append(1),
                                                     grace=20, out=out) as watchdog:
                    def waiting_on_the_engine_jvm():
                        return child.wait(timeout=60)

                    code = waiting_on_the_engine_jvm()
            self.assertTrue(watchdog.timed_out)
            self.assertNotEqual(code, 0, "the child should have been killed, not left to finish")
            self.assertEqual(abandoned, [], "it unwound, so the run goes on")
            self.assertIn("waiting_on_the_engine_jvm", watchdog.dump, "the stuck frame is in the dump")
            self.assertIn(f"signalled 1 child process(es) [{child.pid}]", out.getvalue())
            log = wt / "round.log"
            summary = land._collect_failures(wt, started, log)
        self.assertIn("tools.test_hangs", summary)
        self.assertIn("ran past its 1 s budget", summary)

    def test_a_module_that_never_unwinds_is_abandoned_with_a_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            stuck, exits, abandoned = threading.Event(), [], []

            def exit_(code):
                exits.append(code)
                stuck.set()  # what os._exit would have ended

            with mock.patch.object(run_python_tests, "RESULTS_DIR", Path(tmp)):
                with run_python_tests.ModuleWatchdog("tools.test_stuck", 0.5, lambda: abandoned.append(1),
                                                     grace=0.5, out=io.StringIO(), exit=exit_):
                    stuck.wait(30)
            self.assertEqual(exits, [1])
            self.assertEqual(abandoned, [1])
            self.assertTrue((Path(tmp) / "TEST-tools.test_stuck.xml").is_file())

    def test_a_module_inside_its_budget_is_left_alone(self):
        with run_python_tests.ModuleWatchdog("tools.test_quick", 30, lambda: None, out=io.StringIO()) as watchdog:
            pass
        self.assertFalse(watchdog.timed_out)



class OnlyTheChangedCorpusRuns(unittest.TestCase):
    NAMES = ["tools.test_check_staged_generated_artifacts", "tools.test_docs_voice_detectors", "tools.test_land"]

    def _scope(self, paths: list[str] | None) -> tuple[list[str], str]:
        with tempfile.TemporaryDirectory() as tmp:
            env = {}
            if paths is not None:
                listing = Path(tmp, "changed.txt")
                listing.write_text("\n".join(paths) + "\n")
                env[run_python_tests.CHANGED_PATHS_ENV] = str(listing)
            return run_python_tests.scoped_module_names(list(self.NAMES), env)

    def test_without_a_change_list_every_suite_runs(self):
        self.assertEqual(self._scope(None)[0], self.NAMES)

    def test_regenerated_reference_pages_skip_unrelated_suites(self):
        names, why = self._scope(["public/docs/reference/math.md"])
        self.assertIn("tools.test_docs_voice_detectors", names)
        self.assertNotIn("tools.test_land", names)
        self.assertIn("--all runs every suite", why)

    def test_a_tooling_change_runs_every_suite(self):
        names, why = self._scope(["public/docs/reference/math.md", "tools/land.py"])
        self.assertEqual(names, self.NAMES)
        self.assertIn("Python tooling", why)


class SuitesRunInParallel(unittest.TestCase):
    """Each suite in its own process: verdicts, JUnit and output must survive the trip back."""

    SUITES = {
        "scratch_green": "import unittest\nclass T(unittest.TestCase):\n    def test_ok(self):\n        print('green says hi')\n",
        "scratch_red": "import unittest\nclass T(unittest.TestCase):\n    def test_red(self):\n        self.assertEqual(1, 2, 'one is not two')\n",
        "scratch_dies": "import os\nos._exit(3)\n",
    }

    def test_reds_and_a_suite_that_dies_are_named_and_greens_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name, body in self.SUITES.items():
                Path(tmp, f"{name}.py").write_text(body)
            results = Path(tmp, "results")
            results.mkdir()
            out = io.StringIO()
            env = {**os.environ, "PYTHONPATH": tmp + os.pathsep + os.environ.get("PYTHONPATH", "")}
            with mock.patch.dict(os.environ, env), mock.patch.object(run_python_tests, "RESULTS_DIR", results), \
                    mock.patch("sys.stdout", out):
                outcomes, durations = run_python_tests.run_parallel(sorted(self.SUITES), jobs=3)
            written = sorted(path.name for path in results.iterdir())
        self.assertEqual(set(durations), set(self.SUITES))
        self.assertEqual(outcomes["scratch_green"], (("scratch_green", 1, 0, 0, 0), [], False))
        row, lines, failed = outcomes["scratch_red"]
        self.assertTrue(failed)
        self.assertEqual(row, ("scratch_red", 1, 1, 0, 0))
        self.assertIn("one is not two", lines[0])
        row, lines, failed = outcomes["scratch_dies"]
        self.assertTrue(failed)
        self.assertIn("exited 3 without reporting a result", lines[0])
        # The parent's results directory, not the repository's: the lander reads that one.
        self.assertEqual(written, ["TEST-scratch_dies.xml", "TEST-scratch_green.xml", "TEST-scratch_red.xml"])
        log = out.getvalue()
        self.assertIn("green says hi", log, "a suite's own output reaches the log")
        self.assertNotIn(run_python_tests.RESULT_MARKER, log, "the hand-off line is not log noise")


class SuitesCannotReachTheOuterRepository(unittest.TestCase):
    def test_a_merge_hooks_git_dir_is_cleared(self):
        env = {"GIT_DIR": "/repo/.git", "GIT_INDEX_FILE": "/repo/.git/index", "GIT_REFLOG_ACTION": "merge",
               "PATH": "/usr/bin"}
        dropped = run_python_tests.clear_repository_env(env)
        self.assertEqual(sorted(dropped), ["GIT_DIR", "GIT_INDEX_FILE"])
        self.assertEqual(env, {"GIT_REFLOG_ACTION": "merge", "PATH": "/usr/bin"})

    def test_with_it_cleared_git_init_cannot_make_the_outer_repository_bare(self):
        # The 2026-10-21 shape: GIT_DIR names a *linked worktree's* git dir, as git sets it for a hook
        # in one, and `git init <path>` then writes `core.bare = true` into the shared config.
        def outer_repository(tmp: str) -> tuple[Path, dict[str, str]]:
            outer = Path(tmp, "outer")
            git = lambda *a: subprocess.run(["git", *a], check=True, capture_output=True)  # noqa: E731
            git("init", "-q", str(outer))
            git("-C", str(outer), "-c", "user.name=t", "-c", "user.email=t@e", "commit", "-q", "--allow-empty", "-m", "x")
            git("-C", str(outer), "config", "extensions.worktreeConfig", "true")
            git("-C", str(outer), "worktree", "add", "-q", str(Path(tmp, "wt")))
            env = {"GIT_DIR": str(outer / ".git" / "worktrees" / "wt"), "PATH": os.environ["PATH"], "HOME": tmp}
            return outer / ".git" / "config", env

        def bare_after_init(clear: bool) -> str:
            with tempfile.TemporaryDirectory() as tmp:
                config, env = outer_repository(tmp)
                if clear:
                    run_python_tests.clear_repository_env(env)
                subprocess.run(["git", "init", "-q", str(Path(tmp, "scratch", "repo"))], env=env, check=True)
                return subprocess.run(["git", "config", "--file", str(config), "core.bare"],
                                      capture_output=True, text=True).stdout.strip()

        self.assertEqual(bare_after_init(clear=False), "true", "the hazard no longer reproduces; re-read this test")
        self.assertEqual(bare_after_init(clear=True), "false")

if __name__ == "__main__":
    unittest.main()
