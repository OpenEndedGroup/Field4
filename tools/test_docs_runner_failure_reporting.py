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
        self.audio_run = 4
        self.ui_drives_run = 6
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
        self.assertIn("regenerated=15 (screenshots=2 videos=3 audio=4 ui_drives=6)", summary)
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


class _Diagnoser:
    """Just enough RunnerState to exercise the liveness branch of the post-mortem."""

    def __init__(self, root, process, *, stop_reason=None):
        self.repo_root = root
        self.shared_log_path = root / "field.log"
        self._shared_field_process = process
        self._shared_field_jvm_pid = 4242
        self._shared_field_stop_reason = stop_reason
        self._shared_field_started_at = None
        self._last_capture_child_pid = None

    def _collect_jvm_crash_dumps(self):
        return []

    def _latest_backdoor_client_error(self):
        return None

    def _save_failure_report(self, *, output_path, report):
        return None

    diagnose_capture_failure = docs_hooks.RunnerState.diagnose_capture_failure


class _FakeChild:
    """A Popen stand-in whose death can be scheduled relative to the grace window.

    `dies_within` is how long `wait()` takes to reap it; `None` means it never dies.
    """

    def __init__(self, *, returncode=None, dies_within=None):
        self.pid = 70196
        self.returncode = None
        self._final_returncode = returncode
        self._dies_within = dies_within
        self.waited_for = None

    def wait(self, timeout=None):
        self.waited_for = timeout
        if self._dies_within is None or (timeout is not None and self._dies_within > timeout):
            raise subprocess.TimeoutExpired(["field"], timeout)
        self.returncode = self._final_returncode
        return self.returncode

    def poll(self):
        return self.returncode


class ServerLivenessAttributionTests(unittest.TestCase):
    """An externally-killed JVM must not be reported as a healthy server.

    The regression this pins, found by the 2026-09-14 nightly CI run: the liveness branch
    was a single `process.poll() is None`, sampled immediately after the WebSocket dropped.
    A SIGKILL from outside the run (a sibling agent on this machine) arrives at the same
    instant as the drop, so the child was dying but not yet reaped and `poll()` returned
    None. The post-mortem printed "Field server process: still alive" -- and `log_summary`
    promotes that very line as "the single most telling line". A healthy server plus a dead
    socket reads as "the page hung", so suspicion landed on `chain_physics_render.md`, a
    two-day-old page that had never been certified and that re-captured clean minutes later.
    """

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "field.log").write_text("server log line\n", encoding="utf-8")

    def _diagnose(self, child, **kwargs):
        state = _Diagnoser(self.root, child, **kwargs)
        return state.diagnose_capture_failure(
            page_path=Path("chain_physics_render.md"),
            output_path=self.root / "drape.png",
        )

    def test_a_kill_landing_inside_the_grace_window_is_attributed_not_called_alive(self):
        text = self._diagnose(_FakeChild(returncode=-9, dies_within=0.0))
        self.assertNotIn("still alive", text)
        self.assertIn("EXITED with code -9", text)
        self.assertIn("SIGKILL", text)
        self.assertIn("NOT ours", text)

    def test_a_genuinely_healthy_server_is_still_reported_alive(self):
        text = self._diagnose(_FakeChild(dies_within=None))
        self.assertIn("still alive", text)
        # And the claim is now time-qualified rather than instantaneous.
        self.assertIn("5s after the failure", text)

    def test_the_liveness_probe_actually_waits(self):
        child = _FakeChild(dies_within=None)
        self._diagnose(child)
        self.assertEqual(child.waited_for, 5.0)

    def test_our_own_cleanup_kill_is_still_labelled_as_ours(self):
        text = self._diagnose(
            _FakeChild(returncode=-9, dies_within=0.0),
            stop_reason="stopping shared Field server",
        )
        self.assertIn("NOT a mid-capture crash", text)
        self.assertNotIn("NOT ours", text)


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


class ExpectErrorFenceWaiverTests(unittest.TestCase):
    """An `expect-error=true` fence must still reach its screenshot.

    The fence's whole subject is the diagnostic -- the figure is a picture of the error
    message -- so the box is *supposed* to fail. Since 2026-09-14 `run` reports
    `ok: false` for a box that errored, and `field_backdoor.py`'s batch loop returns at
    the first not-ok command, so an un-waived `run` would abort the invocation two
    commands before the `screenshot-box` the fence exists to take.

    Population: 1 fence tree-wide carries `expect-error` (public/docs/clojure.md).
    """

    def commands(self, open_line: str) -> list[str]:
        options = docs_hooks.parse_runner_options(
            runner_name="field",
            open_line=open_line,
            page_path=Path("public/docs/clojure.md"),
            line_number=1,
            block_index=0,
        )
        return docs_hooks.build_backdoor_command(
            repo_root=Path(__file__).resolve().parent.parent,
            page_path=Path("public/docs/clojure.md"),
            block_index=0,
            screenshot_path=Path("/tmp/expect-error-fence.png"),
            options=options,
            code="print(1)",
        ).backdoor_commands

    def test_the_run_is_waived_and_the_screenshot_still_happens(self) -> None:
        commands = self.commands('```field screenshot="x.png" expect-error=true')

        run = next(line for line in commands if line.startswith("run "))
        self.assertIn("--no-fail-on-error", run)
        screenshot = next(line for line in commands if line.startswith("screenshot-box "))
        self.assertNotIn("--no-fail-on-error", screenshot)
        self.assertLess(commands.index(run), commands.index(screenshot))

    def test_an_ordinary_fence_is_emitted_exactly_as_before(self) -> None:
        for line in self.commands('```field screenshot="x.png"'):
            self.assertNotIn("--no-fail-on-error", line, line)

    def test_native_capture_names_the_fences_target(self) -> None:
        commands = self.commands('```field screenshot="x.png" capture="native-window" graphics-context=true')
        capture = next(line for line in commands if line.startswith("native-screenshot "))
        self.assertIn("--uid docs_", capture)
        self.assertNotIn("--uid @last", capture)


class CaptureFallbackEmbeddingTests(unittest.TestCase):
    """A continuing recapture failure must retain each available figure in the page."""

    def render(self, root, kind, existing, *, continuing=True):
        state = _Recorder()
        state.docs_dir = root
        state.repo_root = root
        state.config = {"site_dir": str(root / "site"), "use_directory_urls": True}
        state.runner_mode = "always"
        state.runner_media = "all"
        state.continue_on_error = continuing
        for method in ("ensure_screenshot", "ensure_video", "ensure_audio", "ensure_ui_drive"):
            setattr(state, method, None)

        def fail(*args, **kwargs):
            raise RuntimeError("deliberate capture failure")

        state.capture_with_environment = fail
        for name in existing:
            (root / name).write_bytes(b"saved fixture")
        attrs = {
            "screenshot": 'screenshot="still.png"',
            "video": 'video="clip.mp4"',
            "audio": 'audio="sound.wav" audio-spec="fixture"',
            "ui": 'capture="ui" screenshot="still.png" video="clip.mp4"',
        }[kind]
        rendered = docs_hooks.process_markdown(
            markdown=f'```diffstatic_runner embed=true {attrs}\nprintln("fixture")\n```\n',
            page_path=root / "page.md", state=state, files=None,
        )
        return rendered, state

    def test_saved_media_remains_embedded_and_failure_remains_recorded(self):
        for kind, name in (("screenshot", "still.png"), ("video", "clip.mp4"), ("audio", "sound.wav")):
            with self.subTest(kind=kind), TemporaryDirectory() as tmp:
                rendered, state = self.render(Path(tmp), kind, [name])
                self.assertIn(name, rendered.split("```\n")[-1])
                self.assertEqual(len(state.failures), 1)
                self.assertEqual(len(state.fallback_failures), 1)

    def test_missing_media_is_not_embedded(self):
        for kind in ("screenshot", "video", "audio", "ui"):
            with self.subTest(kind=kind), TemporaryDirectory() as tmp:
                rendered, state = self.render(Path(tmp), kind, [])
                self.assertEqual(rendered.split("```\n")[-1], "")
                self.assertEqual(len(state.failures), 1)

    def test_ui_falls_back_independently_for_each_output(self):
        for names in (["still.png"], ["clip.mp4"], ["still.png", "clip.mp4"]):
            with self.subTest(names=names), TemporaryDirectory() as tmp:
                rendered, state = self.render(Path(tmp), "ui", names)
                embeds = rendered.split("```\n")[-1]
                for name in ("still.png", "clip.mp4"):
                    self.assertEqual(name in embeds, name in names)
                self.assertEqual(len(state.failures), 1)

    def test_fail_fast_still_raises_even_with_a_saved_image(self):
        with TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "deliberate capture failure"):
                self.render(Path(tmp), "screenshot", ["still.png"], continuing=False)
