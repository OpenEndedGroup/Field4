"""What the docs runner *says* when a capture fails.

Both suites here exist because of a single real build (2026-08-07, docs exit 1 on
`visualizers-inline-pianoroll.mp4`) in which every number and every log path the runner
offered a reader was wrong or misleading, while the underlying diagnosis was sitting in a
file it declined to open.
"""

import json
import subprocess
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


class _SummaryRecorder:
    """The diagnostic state read by `log_summary`, without starting Field."""

    def __init__(self):
        self.summary_logged = False
        self.started_at = time.monotonic()
        self.screenshots_run = 2
        self.videos_run = 3
        self.cache_hits = 5
        self.assets_reused = 7
        self.missing_assets = 11
        self.failures = ["fallback", "broken"]
        self.fallback_failures = ["fallback"]
        self.broken_failures = ["broken"]
        self.server_starts = 1
        self.server_restarts = 0
        self.session_captures = 0
        self._sidecar_started_by_runner = False
        self._sidecar_url = None
        self.shared_log_path = Path("/tmp/field-docs-runner.log")

    log_summary = docs_hooks.RunnerState.log_summary


class RunnerSummaryAccountingTests(unittest.TestCase):
    def test_failures_are_reported_separately_from_skipped_assets(self):
        state = _SummaryRecorder()
        with self.assertLogs(docs_hooks.LOG, level="INFO") as captured:
            state.log_summary()

        summary = next(line for line in captured.output if "runner summary" in line)
        self.assertIn("regenerated=5", summary)
        self.assertIn("skipped=23 (cache_hits=5 reused_existing=7 missing=11)", summary)
        self.assertIn("failures=2", summary)
        self.assertIn("warm_session_captures=0", summary)
        self.assertNotIn("skipped=25", summary)


class _WarmFallbackRecorder:
    def __init__(self):
        self._session_failures = 0
        self.session_enabled = True
        self.events = []

    def _stop_session(self, *, reason):
        self.events.append(("stop", reason))

    def _restart_shared_field_server(self, *, reason):
        self.events.append(("restart", reason))

    _session_fallback = docs_hooks.RunnerState._session_fallback


class WarmSessionFallbackTests(unittest.TestCase):
    def test_circuit_breaker_recycles_field_before_per_process_retry(self):
        state = _WarmFallbackRecorder()

        result = state._session_fallback("batch not ok")

        self.assertIsNone(result)
        self.assertFalse(state.session_enabled)
        self.assertEqual(
            state.events,
            [
                ("stop", "circuit breaker tripped"),
                ("restart", "warm-session miss: batch not ok"),
            ],
        )


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


class RunnerDigestScopeTests(unittest.TestCase):
    """Diagnostics must be testable without invalidating every successful capture."""

    @classmethod
    def setUpClass(cls):
        cls.source = (Path(__file__).resolve().parent.parent / "public/docs_hooks.py").read_bytes()

    def material(self, source: bytes) -> bytes:
        return docs_hooks.runner_digest_material("public/docs_hooks.py", source)

    def test_failure_report_wording_does_not_change_the_capture_digest(self):
        changed = self.source.replace(
            b"Both-sides-of-WebSocket diagnosis saved to",
            b"Full capture diagnosis saved to",
        )
        self.assertNotEqual(changed, self.source)
        self.assertEqual(self.material(changed), self.material(self.source))

    def test_an_operational_log_line_does_not_change_the_capture_digest(self):
        changed = self.source.replace(
            b"Field docs runner mode: %s",
            b"Field docs capture mode: %s",
        )
        self.assertNotEqual(changed, self.source)
        self.assertEqual(self.material(changed), self.material(self.source))

    def test_capture_affecting_code_still_changes_the_digest(self):
        changed = self.source.replace(
            b"DEFAULT_RUNNER_PORT = 9477",
            b"DEFAULT_RUNNER_PORT = 9478",
        )
        self.assertNotEqual(changed, self.source)
        self.assertNotEqual(self.material(changed), self.material(self.source))

    def test_other_runner_files_remain_byte_sensitive(self):
        self.assertNotEqual(
            docs_hooks.runner_digest_material("tools/field_backdoor.py", b"before"),
            docs_hooks.runner_digest_material("tools/field_backdoor.py", b"after"),
        )


if __name__ == "__main__":
    unittest.main()


class TimeoutDiagnosisOrderingTests(unittest.TestCase):
    """A capture *timeout* must diagnose before it restarts, like every other failure.

    The regression this pins, found by the 2026-08-21 nightly CI run: all four capture
    timeout handlers read

        except subprocess.TimeoutExpired as exc:
            self._restart_shared_field_server(...)      # <-- reopens the log in "w" mode
            raise RuntimeError(...)                     # <-- no diagnosis, exc.stderr dropped

    while every adjacent non-timeout branch called `diagnose_capture_failure` first and
    carried a comment explaining that the restart erases the server-side log. So a timeout,
    which is the most common way a capture fails, was the one mode that destroyed its own
    evidence — and `log_summary` still told the reader a post-mortem had been saved. That
    run's docs build aborted and could not be graded because of it.
    """

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.log = self.root / "field.log"
        self.log.write_text("server log line\n", encoding="utf-8")

    def _stub(self):
        calls = []

        class _Stub:
            shared_log_path = self.log
            _timeout_failure = docs_hooks.RunnerState._timeout_failure

            def diagnose_capture_failure(inner, *, page_path, output_path):
                calls.append("diagnose")
                return "BOTH-SIDES-DIAGNOSIS"

            def _restart_shared_field_server(inner, *, reason):
                calls.append("restart")

        return _Stub(), calls

    def _timeout(self):
        return subprocess.TimeoutExpired(
            ["field_backdoor.py"],
            15.2,
            output="",
            stderr="Docs capture watchdog: only 8/11 command results arrived before the outer limit.",
        )

    def test_diagnosis_happens_before_the_restart(self):
        stub, calls = self._stub()
        stub._timeout_failure(
            exc=self._timeout(),
            page_path=Path("afield_four_graphics.md"),
            output_path=self.root / "02-camera-matrices.png",
            command=["field_backdoor.py", "--command", "native-screenshot"],
            what="",
            restart_reason="timeout while capturing",
        )
        # Both must happen, and in this order — the restart truncates what the diagnosis reads.
        self.assertEqual(calls, ["diagnose", "restart"])

    def test_the_watchdog_verdict_survives_into_the_message(self):
        stub, _ = self._stub()
        error = stub._timeout_failure(
            exc=self._timeout(),
            page_path=Path("p.md"),
            output_path=self.root / "a.png",
            command=["field_backdoor.py"],
            what="",
            restart_reason="r",
        )
        text = str(error)
        # The one line that distinguishes "too slow" from "finished but would not exit".
        self.assertIn("only 8/11 command results arrived", text)
        self.assertIn("BOTH-SIDES-DIAGNOSIS", text)
        self.assertIn("Timed out after 15.2s", text)

    def test_the_subject_line_names_the_capture_kind(self):
        stub, _ = self._stub()
        for what, expected in (("", "runner timed out"), ("video", "runner video timed out")):
            error = stub._timeout_failure(
                exc=self._timeout(),
                page_path=Path("p.md"),
                output_path=self.root / "a.png",
                command=["field_backdoor.py"],
                what=what,
                restart_reason="r",
            )
            self.assertIn(expected, str(error))
