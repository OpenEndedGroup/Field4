"""What the docs runner *says* when a capture fails.

Both suites here exist because of a single real build (2026-08-07, docs exit 1 on
`visualizers-inline-pianoroll.mp4`) in which every number and every log path the runner
offered a reader was wrong or misleading, while the underlying diagnosis was sitting in a
file it declined to open.
"""

import json
import sys
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402


class _Recorder:
    """The three counters `handle_capture_error` maintains, without a live runner."""

    def __init__(self):
        self.failures = []
        self.fallback_failures = []
        self.broken_failures = []

    handle_capture_error = docs_hooks.RunnerState.handle_capture_error


class CaptureFailureAccountingTests(unittest.TestCase):
    """`failures=N` in the summary must count a fail-fast abort too.

    The regression: the four capture call sites read

        except RuntimeError as exc:
            if not state.continue_on_error:
                raise                       # <-- returns before recording
            state.handle_capture_error(...)

    so with continue-on-error OFF -- which is exactly how CI runs -- a failing capture
    was never appended to `self.failures`. `log_summary()` still ran during teardown and
    printed `failures=0` on a build that exited 1.
    """

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_a_fatal_failure_is_still_counted(self):
        state = _Recorder()
        missing = self.root / "never-captured.png"
        state.handle_capture_error(
            page_path=Path("visualizers.md"),
            output_path=missing,
            error=RuntimeError("stop timed out"),
            continuing=False,
        )
        self.assertEqual(len(state.failures), 1)
        self.assertEqual(len(state.broken_failures), 1)
        self.assertIn("stop timed out", state.failures[0])

    def test_a_fatal_failure_with_a_committed_asset_is_counted_as_a_failure(self):
        # A committed asset on disk is what makes this case tempting to under-report:
        # the file exists, so nothing looks broken. The build still aborts.
        state = _Recorder()
        existing = self.root / "committed.png"
        existing.write_bytes(b"\x89PNG committed")
        state.handle_capture_error(
            page_path=Path("visualizers.md"),
            output_path=existing,
            error=RuntimeError("stop timed out"),
            continuing=False,
        )
        self.assertEqual(len(state.failures), 1)
        self.assertEqual(len(state.fallback_failures), 1)
        self.assertEqual(state.broken_failures, [])

    def test_continuing_and_fatal_both_reach_the_same_counter(self):
        state = _Recorder()
        for continuing in (True, False):
            state.handle_capture_error(
                page_path=Path("p.md"),
                output_path=self.root / f"a-{continuing}.png",
                error=RuntimeError("boom"),
                continuing=continuing,
            )
        self.assertEqual(len(state.failures), 2)


class _LogPicker:
    """Just enough RunnerState to exercise the client-log selection."""

    def __init__(self, repo_root):
        self.repo_root = repo_root
        self._last_capture_child_pid = None
        self._shared_field_started_at = None

    # `_error_lines_in` is a staticmethod; attribute access unwraps it to a plain
    # function, so re-wrap or it would rebind as an instance method here.
    _error_lines_in = staticmethod(docs_hooks.RunnerState._error_lines_in)
    _backdoor_client_log_for_pid = docs_hooks.RunnerState._backdoor_client_log_for_pid
    _latest_backdoor_client_error = docs_hooks.RunnerState._latest_backdoor_client_error
    _remember_capture_child_pid = docs_hooks.RunnerState._remember_capture_child_pid


class BackdoorClientLogSelectionTests(unittest.TestCase):
    """The post-mortem must quote the FAILING capture's log, not a neighbour's.

    `field_backdoor.py`'s SessionLogger names each session
    `<stamp>-pid<PID>-<token>.jsonl`, so the child PID identifies the invocation exactly.
    Before this, selection was "newest of the last four logs containing an error-shaped
    line" -- and on 2026-08-07 the failing capture's own log contained no such line (its
    `stop` returned a clean `ok:false`), so the scan silently fell through to an older,
    unrelated invocation and led the report with someone else's Playwright timeout.
    """

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.logs = self.root / ".field-backdoor" / "logs"
        self.logs.mkdir(parents=True)
        self.picker = _LogPicker(self.root)

    def _write(self, name, lines, *, age=0.0):
        path = self.logs / name
        path.write_text("".join(json.dumps(o) + "\n" for o in lines), encoding="utf-8")
        if age:
            stamp = time.time() - age
            import os

            os.utime(path, (stamp, stamp))
        return path

    def test_the_pid_matched_log_wins_over_a_newer_noisy_one(self):
        self._write(
            "20260807-094413-pid81948-ce2f9ac3.jsonl",
            [{"event": "ws_event", "error": "Timeout 30000ms exceeded"}],
            age=120,
        )
        mine = self._write(
            "20260807-094504-pid82129-b3181d3a.jsonl",
            [{"event": "print_result", "result": {"ok": False, "command": "stop @last"}}],
        )
        self.picker._remember_capture_child_pid(82129)
        path, tail = self.picker._latest_backdoor_client_error()
        self.assertEqual(path, str(mine))
        # The honest answer for a quiet log is "this log is clean", not another's traceback.
        self.assertIn("no error/exception events", tail)
        self.assertNotIn("Timeout 30000ms", tail)

    def test_the_pid_matched_log_reports_its_own_errors_when_it_has_them(self):
        self._write("20260807-094413-pid81948-ce2f9ac3.jsonl", [{"error": "someone else"}], age=120)
        mine = self._write(
            "20260807-094504-pid82129-b3181d3a.jsonl",
            [{"event": "receiver_loop_error", "traceback": "ConnectionClosedError 1006"}],
        )
        self.picker._remember_capture_child_pid(82129)
        path, tail = self.picker._latest_backdoor_client_error()
        self.assertEqual(path, str(mine))
        self.assertIn("ConnectionClosedError 1006", tail)
        self.assertNotIn("someone else", tail)

    def test_without_a_pid_the_fallback_is_labelled_as_unmatched(self):
        # Still useful, but a reader must be able to tell it is a guess.
        self._write("20260807-094413-pid81948-ce2f9ac3.jsonl", [{"error": "a dropped socket"}])
        self.picker._remember_capture_child_pid(999999)  # no log for this pid
        path, tail = self.picker._latest_backdoor_client_error()
        self.assertIn("NOT PID-MATCHED", path)
        self.assertIn("a dropped socket", tail)

    def test_no_logs_at_all_is_not_an_error(self):
        self.picker._remember_capture_child_pid(82129)
        self.assertIsNone(self.picker._latest_backdoor_client_error())


if __name__ == "__main__":
    unittest.main()
