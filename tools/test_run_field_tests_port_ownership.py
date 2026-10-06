"""The smoke runner never stops, or adopts, a Field that another checkout owns.

On 2026-10-04 two runs in sibling worktrees both chose port 9477 ("the first free port from
9477", chosen before either JVM had bound it). The second run's prewarm took the first run's newly
booted Field for its own, because anything answering `/health` on the port counted. When the second
run finished, `stop_persistent_field` -> `recycle_field` SIGINTed whatever listened on 9477. That
was the first run's server, mid-test: its backdoor websocket dropped with 1006, and the test's box
compile failed with `ApplicationManager.getApplication() is null` as the JVM shut down around it.

Run with:  python -m unittest tools.test_run_field_tests_port_ownership
"""

import json
import signal
import sys
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import Mock, call, mock_open, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_field_tests as smoke  # noqa: E402  (path set up just above)

PORT = 9573
OURS = smoke.Attribution(True, "aot-cache-path", "/this/checkout/build/aot/field.aot")
THEIRS = smoke.Attribution(False, "unattributed", "looks like /Users/someone/other-worktree")


@contextmanager
def listening(pid_attributions, identity=None):
    """Patch the process census: these pids listen on PORT, classified as given, and the server
    there reports ``identity`` from ``/api/instance-info`` (None: it does not answer)."""
    pids = [pid for pid, _ in pid_attributions]
    ours = [(pid, why) for pid, why in pid_attributions if why is not None and why.ours]
    foreign = [(pid, why) for pid, why in pid_attributions if why is not None and not why.ours]
    with patch.object(smoke, "_pids_listening_on", return_value=pids), \
            patch.object(smoke, "_classify_field_jvms", return_value=(ours, foreign)), \
            patch.object(smoke, "_describe_pid", side_effect=lambda pid: f"pid {pid}"), \
            patch.object(smoke, "field_instance_info", return_value=identity):
        yield


class ForeignListenerTests(unittest.TestCase):
    def test_another_checkouts_field_is_reported_with_its_evidence(self):
        with listening([(4242, THEIRS)]):
            self.assertEqual(smoke.foreign_listeners(PORT),
                             ["pid 4242 [unattributed: looks like /Users/someone/other-worktree]"])

    def test_a_listener_without_a_field_command_line_is_foreign_too(self):
        with listening([(77, None)]):
            self.assertEqual(smoke.foreign_listeners(PORT), ["pid 77 [no Field JVM command line]"])

    def test_our_own_field_is_not_foreign(self):
        with listening([(4242, OURS)]):
            self.assertEqual(smoke.foreign_listeners(PORT), [])

    def test_a_failed_census_does_not_disown_a_server_that_says_it_is_ours(self):
        with listening([(4242, None)], identity={"repoRoot": str(smoke.REPO_ROOT)}):
            self.assertEqual(smoke.foreign_listeners(PORT), [])

    def test_a_server_naming_another_checkout_stays_foreign(self):
        with listening([(4242, THEIRS)], identity={"repoRoot": "/Users/someone/other-worktree"}):
            self.assertEqual(len(smoke.foreign_listeners(PORT)), 1)


class RecycleTests(unittest.TestCase):
    def test_recycle_refuses_to_signal_another_checkouts_field(self):
        with listening([(4242, THEIRS)]), patch.object(smoke.os, "kill") as kill:
            with self.assertRaisesRegex(SystemExit, "not this checkout's"):
                smoke.recycle_field(PORT, ctx=None)
        kill.assert_not_called()

    def test_recycle_still_stops_our_own_field(self):
        with listening([(4242, OURS)]), patch.object(smoke.os, "kill") as kill, \
                patch.object(smoke, "health_check", return_value=False):
            smoke.recycle_field(PORT, ctx=None)
        kill.assert_called_once_with(4242, signal.SIGINT)

    def test_sigkill_escalation_spares_a_listener_that_bound_the_port_meanwhile(self):
        # Ours ignores SIGINT and exits; a stranger takes the port before the escalation looks.
        census = iter([[4242], [4242], [9999]])
        with patch.object(smoke, "_pids_listening_on", side_effect=lambda port: next(census)), \
                patch.object(smoke, "_classify_field_jvms", return_value=([(4242, OURS)], [])), \
                patch.object(smoke, "health_check", side_effect=[True, False]), \
                patch.object(smoke.os, "kill") as kill, \
                patch.object(smoke.time, "sleep"):
            smoke.recycle_field(PORT, ctx=None, grace=0.0)
        self.assertEqual(kill.call_args_list, [call(4242, signal.SIGINT)])


class StopTests(unittest.TestCase):
    def test_stopping_this_run_leaves_another_runs_field_on_the_port_alone(self):
        process = Mock()
        process.poll.return_value = 0  # our Gradle tree is already gone
        with patch.object(smoke, "health_check", return_value=True), \
                patch.object(smoke, "foreign_listeners", return_value=["pid 4242 [unattributed]"]), \
                patch.object(smoke, "recycle_field") as recycle:
            smoke.stop_persistent_field(process, port=PORT)
        recycle.assert_not_called()

    def test_stopping_this_run_still_recycles_its_own_field(self):
        process = Mock()
        process.poll.return_value = 0
        with patch.object(smoke, "health_check", return_value=True), \
                patch.object(smoke, "foreign_listeners", return_value=[]), \
                patch.object(smoke, "recycle_field") as recycle:
            smoke.stop_persistent_field(process, port=PORT)
        recycle.assert_called_once()


class PrewarmTests(unittest.TestCase):
    def prewarm(self, foreign):
        process = Mock()
        process.poll.return_value = None
        watch = Mock()
        watch.expired.return_value = False
        with patch.object(smoke, "health_check", side_effect=[False, True]), \
                patch.object(smoke.subprocess, "Popen", return_value=process), \
                patch.object(smoke, "ManagedStartupWatch", return_value=watch), \
                patch.object(smoke, "foreign_listeners", return_value=foreign), \
                patch.object(smoke, "terminate_process_tree") as terminate, \
                patch.object(Path, "open", mock_open()):
            try:
                return smoke.ensure_persistent_field(PORT), process, terminate
            except smoke.PortTakenError as exc:
                return exc, process, terminate

    def test_a_foreign_field_answering_on_the_port_is_not_taken_for_ours(self):
        outcome, process, terminate = self.prewarm(["pid 4242 [unattributed]"])
        self.assertIsInstance(outcome, smoke.PortTakenError)
        self.assertIn("pid 4242", str(outcome))
        terminate.assert_called_once_with(process)

    def test_our_own_field_becoming_healthy_is_adopted(self):
        outcome, process, terminate = self.prewarm([])
        self.assertIs(outcome, process)
        terminate.assert_not_called()


class PortRaceTests(unittest.TestCase):
    def run_with(self, argv, ensure_effects, ports):
        seen_ports = []

        def action(test, **kw):
            seen_ports.append(kw["port"])
            return {"id": test["id"], "ok": True, "failures": [], "durationMs": 1}

        with tempfile.TemporaryDirectory() as directory:
            with patch.object(sys, "argv", ["run_field_tests", "--no-reap", "--catalog-order", *argv]):
                args = smoke.parse_args()
            args.results = str(Path(directory) / "results.json")
            with patch.object(smoke, "load_or_refresh_catalog", return_value={"tests": [{"id": "t"}]}), \
                    patch.object(smoke, "load_expected_smoke_failures", return_value={}), \
                    patch.object(smoke, "run_one_test", side_effect=action), \
                    patch.object(smoke, "warn_if_node_too_old"), \
                    patch.object(smoke, "select_automation_port", side_effect=ports), \
                    patch.object(smoke, "ensure_persistent_field", side_effect=ensure_effects), \
                    patch.object(smoke, "stop_persistent_field"):
                code = smoke.run(args, None)
                report = json.loads(Path(args.results).read_text())
        return code, report, seen_ports

    def test_a_run_that_chose_its_port_moves_when_it_loses_the_race(self):
        taken = smoke.PortTakenError(PORT, ["pid 4242 [unattributed]"])
        code, report, seen_ports = self.run_with([], [taken, Mock()], [PORT, PORT + 1])
        self.assertEqual((code, report["passed"]), (0, 1))
        self.assertEqual(seen_ports, [PORT + 1])

    def test_a_run_given_its_port_stops_rather_than_move(self):
        taken = smoke.PortTakenError(PORT, ["pid 4242 [unattributed]"])
        with self.assertRaisesRegex(SystemExit, "Choose a fresh --port"):
            self.run_with(["--port", str(PORT)], [taken], [PORT])


if __name__ == "__main__":
    unittest.main()
