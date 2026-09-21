import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))
import docs_hooks
import run_field_tests as smoke
from verification_environment import (VerificationEnvironment, VerificationEnvironmentError,
                                     DiskBudget, GIB, environment_problem, parse_display, parse_power, sleep_evidence)
from verification_runtime import run_with_watchdog


GOOD = {"supported": True, "onAC": True, "batteryPercent": 42, "displayAvailable": True}


class EnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.
        self.state = dict(GOOD)
        self.free = 64 * GIB
        self.monitor = VerificationEnvironment(probe=lambda: dict(self.state), clock=lambda: self.now,
                                               evidence=lambda a, b: ["Sleep/Wake fixture"],
                                               disk_probe=lambda path: SimpleNamespace(free=self.free),
                                               disk_floor_gib=8, disk_reserve_gib=0, disk_planned_gib=0)
        self.monitor.sample(force=True)

    def change(self, **fields):
        self.state.update(fields)
        self.now += 1
        self.monitor.sample(force=True)

    def test_power_parser_does_not_trust_time_remaining(self):
        self.assertEqual(parse_power("Now drawing from 'Battery Power'\n42%; discharging; 9:00 remaining"),
                         {"onAC": False, "batteryPercent": 42})
        self.assertEqual(parse_power("Now drawing from 'AC Power'"), {"onAC": True, "batteryPercent": None})
        self.assertIsNone(parse_power("probe failed")["onAC"])

    def test_display_requires_online_primary_not_vendor_or_lid(self):
        def display(**kwargs):
            return parse_display(json.dumps({"SPDisplaysDataType": [{"spdisplays_ndrvs": [kwargs]}]}))
        self.assertTrue(display(spdisplays_online="spdisplays_yes", spdisplays_main="spdisplays_yes"))
        self.assertFalse(display(spdisplays_online="spdisplays_no", spdisplays_main="spdisplays_yes"))
        self.assertFalse(display(_spdisplays_displayID="1"))
        self.assertIsNone(parse_display("timeout"))

    def test_power_floor_and_unknown_probe_fail_closed(self):
        for percent, allowed in ((79, False), (80, True)):
            snapshot = {**GOOD, "onAC": False, "batteryPercent": percent}
            self.assertEqual(environment_problem(snapshot, require_display=False) is None, allowed)
        self.assertIsNotNone(environment_problem({**GOOD, "onAC": None}, require_display=False))
        self.assertIsNone(environment_problem({"supported": False}, require_display=True))

    def test_no_display_blocks_native_but_not_headless(self):
        self.change(displayAvailable=False)
        self.monitor.checkpoint(require_display=False)
        with self.assertRaisesRegex(VerificationEnvironmentError, "no display"):
            self.monitor.checkpoint(require_display=True)

    def test_loss_and_recovery_during_action_remain_interrupted(self):
        token = self.monitor.checkpoint(require_display=True)
        self.change(displayAvailable=False)
        self.change(displayAvailable=True)
        with self.assertRaisesRegex(VerificationEnvironmentError, "no display"):
            self.monitor.check(token, require_display=True)
        self.monitor.check(token, require_display=False)

    def test_power_loss_invalidates_even_headless_work_after_recovery(self):
        token = self.monitor.checkpoint(require_display=False)
        self.change(onAC=False)
        self.change(onAC=True)
        with self.assertRaisesRegex(VerificationEnvironmentError, "battery 42"):
            self.monitor.check(token, require_display=False)

    def test_gap_is_recorded_not_silently_priced_as_execution_time(self):
        token = self.monitor.checkpoint(require_display=False)
        self.now += 63 * 60
        self.monitor.sample(force=True)
        with self.assertRaisesRegex(VerificationEnvironmentError, "unobserved"):
            self.monitor.check(token, require_display=False)
        event = self.monitor.report()["events"][-1]
        self.assertEqual(event["wallSeconds"], 3780)
        self.assertEqual(event["sleepWakeEvidence"], ["Sleep/Wake fixture"])

    def test_regular_sampling_does_not_invent_gaps(self):
        token = self.monitor.checkpoint(require_display=False)
        for _ in range(100):
            self.now += 1
            self.monitor.sample()
        self.monitor.check(token, require_display=False)

    def test_clock_rollback_is_not_a_pass(self):
        token = self.monitor.checkpoint(require_display=False)
        self.now -= 1
        self.monitor.sample()
        with self.assertRaises(VerificationEnvironmentError):
            self.monitor.check(token, require_display=False)

    def test_sleep_log_is_filtered_to_the_gap(self):
        text = "2026-09-20 01:00:00 -0500 Sleep old\n2026-09-20 02:00:00 -0500 Sleep current\n"
        from datetime import datetime
        start = datetime.strptime("2026-09-20 01:59:00 -0500", "%Y-%m-%d %H:%M:%S %z").timestamp()
        with patch("verification_environment.sys.platform", "darwin"), patch("verification_environment._read", return_value=text):
            self.assertEqual(sleep_evidence(start, start + 120), ["2026-09-20 02:00:00 -0500 Sleep current"])

    def test_watchdog_stops_only_the_child_it_started(self):
        calls = []
        def check():
            calls.append(1)
            if len(calls) > 1:
                raise VerificationEnvironmentError("display removed")
        process = Mock()
        process.poll.return_value = None
        with patch("verification_runtime.subprocess.Popen", return_value=process), \
             patch("verification_runtime.terminate_process_tree") as stop:
            with self.assertRaisesRegex(VerificationEnvironmentError, "display removed"):
                run_with_watchdog(["fixture"], cwd=Path.cwd(), timeout=5, check_environment=check)
            stop.assert_called_once_with(process)

    def test_display_declaration_is_not_inferred_from_browser_screenshots(self):
        self.assertFalse(smoke.requires_native_display({"expect": {"screenshot": {"kind": "window"}}}))
        self.assertTrue(smoke.requires_native_display({"graphicsContext": True}))
        self.assertTrue(smoke.requires_native_display({"requiresDisplay": True}))

    def docs_state(self):
        state = docs_hooks.RunnerState.__new__(docs_hooks.RunnerState)
        state.environment = self.monitor
        state.environment_outcomes = []
        state.cache, state.cache_metadata = {}, {}
        state.save, state._stop_session, state._stop_shared_field_server = Mock(), Mock(), Mock()
        state._ensure_runner_owned_field_server = Mock()
        state.failures = []
        return state

    def options(self, **kwargs):
        return SimpleNamespace(graphics_context=False, capture="box", screenshot=None, video=None, audio=None, **kwargs)

    def test_docs_no_display_skips_native_then_runs_headless(self):
        state = self.docs_state()
        self.change(displayAvailable=False)
        action = Mock()
        options = self.options()
        options.capture = "native-window"
        with self.assertRaises(VerificationEnvironmentError):
            state.capture_with_environment(action, options=options, page_path=Path("native.md"), block_index=1)
        action.assert_not_called()
        state._ensure_runner_owned_field_server.assert_not_called()
        self.assertEqual(state.environment_outcomes[0]["status"], "not-run")
        options.capture = "window"  # headless browser window, not the native GLFW window
        state.capture_with_environment(action, options=options, page_path=Path("browser.md"), block_index=1)
        action.assert_called_once()
        state._ensure_runner_owned_field_server.assert_called_once()
        self.assertEqual(state.failures, [])

    def test_docs_constructor_does_not_start_field_before_environment_preflight(self):
        class Config(dict):
            config_file_path = str(Path(__file__).resolve().parent.parent / "public/zensical.yml")
        config = Config(docs_dir=str(Path.cwd() / "public/docs"))
        with patch.dict("os.environ", {docs_hooks.RUNNER_MODE_ENV: "always", docs_hooks.RUNNER_PORT_ENV: "9573"}), \
             patch.object(docs_hooks.RunnerState, "_ensure_runner_owned_field_server") as start, \
             patch.object(docs_hooks, "load_source_symbol_index", return_value={}), \
             patch.object(docs_hooks.atexit, "register"):
            state = docs_hooks.RunnerState(config)
        start.assert_not_called()
        self.assertIsNone(state.environment)

    def test_docs_interrupted_capture_cannot_leave_a_certified_cache_entry(self):
        state = self.docs_state()
        options = self.options()
        options.screenshot = "image.png"
        key = str(docs_hooks.resolve_output_path(Path.cwd() / "page.md", "image.png"))
        def capture(**kwargs):
            state.cache[key] = "new digest"
            self.change(onAC=False)
        with self.assertRaises(VerificationEnvironmentError):
            state.capture_with_environment(capture, options=options, page_path=Path.cwd() / "page.md", block_index=1)
        self.assertNotIn(key, state.cache)
        self.assertEqual(state.environment_outcomes[0]["status"], "interrupted")
        state._stop_shared_field_server.assert_called_once()

    def test_docs_incomplete_is_nonzero_even_without_product_failures(self):
        state = SimpleNamespace(failures=[], environment_outcomes=[{"status": "not-run"}],
                                save=Mock(), close=Mock(), repo_root=Path.cwd())
        with patch.object(docs_hooks, "STATE", state):
            with self.assertRaisesRegex(VerificationEnvironmentError, "incomplete"):
                docs_hooks.on_post_build(None)
        state.close.assert_called_once()

    def run_smoke_fixture(self, tests, action):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(sys, "argv", ["run_field_tests", "--no-prewarm", "--catalog-order"]):
                args = smoke.parse_args()
            args.results = str(Path(directory) / "results.json")
            with patch.object(smoke, "load_or_refresh_catalog", return_value={"tests": tests}), \
                 patch.object(smoke, "load_expected_smoke_failures", return_value={}), \
                 patch.object(smoke, "run_one_test", side_effect=action) as run_test, \
                 patch.object(smoke, "warn_if_node_too_old"), \
                 patch.object(smoke, "select_automation_port", return_value=9573):
                code = smoke.run(args, self.monitor)
            return code, json.loads(Path(args.results).read_text()), run_test.call_count

    def test_smoke_no_display_runs_headless_and_reports_incomplete(self):
        self.change(displayAvailable=False)
        tests = [{"id": "native", "requiresDisplay": True}, {"id": "headless"}]
        code, report, count = self.run_smoke_fixture(tests, lambda test, **kw:
            {"id": test["id"], "ok": True, "failures": [], "durationMs": 1})
        self.assertEqual((code, count, report["passed"], report["failed"]), (2, 1, 1, 0))
        self.assertFalse(report["complete"])
        self.assertEqual(report["results"][0]["environmentStatus"], "NOT RUN")

    def test_smoke_interruption_cannot_be_reported_as_an_assertion_failure(self):
        def action(test, **kw):
            self.change(onAC=False)
            self.change(onAC=True)
            return {"id": test["id"], "ok": False, "failures": ["timeout"], "durationMs": 1}
        code, report, _ = self.run_smoke_fixture([{"id": "interrupted"}], action)
        self.assertEqual(code, 2)
        self.assertEqual(report["failureKinds"]["test"], 0)
        self.assertEqual(report["results"][0]["environmentStatus"], "INTERRUPTED")

    def test_preflight_refusal_never_stops_an_unowned_listener(self):
        with patch.object(smoke, "health_check") as health, patch.object(smoke, "recycle_field") as recycle:
            smoke.stop_persistent_field(None, port=9573)
        health.assert_not_called()
        recycle.assert_not_called()

    def test_interrupted_prewarm_cleans_up_its_owned_server(self):
        process = Mock()
        with patch.object(smoke, "health_check", return_value=False), \
             patch.object(smoke.subprocess, "Popen", return_value=process), \
             patch.object(smoke, "stop_persistent_field") as stop, \
             patch.object(Path, "open", unittest.mock.mock_open()):
            with self.assertRaisesRegex(VerificationEnvironmentError, "power lost"):
                smoke.ensure_persistent_field(9573, check_environment=Mock(
                    side_effect=VerificationEnvironmentError("power lost")))
        stop.assert_called_once_with(process, port=9573)

    def test_display_annotation_must_be_a_boolean(self):
        from discover_field_tests import validate_annotation, ParseError
        with self.assertRaisesRegex(ParseError, "requiresDisplay"):
            validate_annotation(Path("x.kt"), 2, {"id": "x", "runner": "backdoor-box", "requiresDisplay": "yes"})

    def test_catalogued_direct_window_creators_declare_display_requirement(self):
        from discover_field_tests import discover, DEFAULT_ROOTS
        import re
        root = Path(__file__).resolve().parent.parent
        catalog = discover(root, DEFAULT_ROOTS, strict=True)
        tracked = set(subprocess.check_output(["git", "ls-files"], cwd=root, text=True).splitlines())
        direct = [test for test in catalog["tests"] if test["sourceFile"] in tracked and test["runner"] == "jvm-main" and
                  re.search(r"\bWindow\(", (root / test["sourceFile"]).read_text())]
        self.assertGreaterEqual(len(direct), 10)
        self.assertEqual([t["id"] for t in direct if t.get("requiresDisplay") is not True], [])

    def test_disk_floor_applies_even_when_mac_probes_are_unsupported(self):
        self.free = 7 * GIB
        self.change(supported=False)
        with self.assertRaisesRegex(VerificationEnvironmentError, "disk floor"):
            self.monitor.checkpoint(require_display=False)

    def test_disk_loss_cannot_be_erased_by_recovery(self):
        token = self.monitor.checkpoint(require_display=False)
        self.free = 7 * GIB
        self.change()
        self.free = 20 * GIB
        self.change()
        with self.assertRaisesRegex(VerificationEnvironmentError, "disk floor"):
            self.monitor.check(token, require_display=False)
        self.assertEqual(self.monitor.report()["diskBudget"]["minimumFreeBytes"], 7 * GIB)
        self.assertEqual(self.monitor.report()["diskBudget"]["maxObservedVolumeLossBytes"], 57 * GIB)

    def test_disk_probe_failure_is_not_treated_as_unsupported(self):
        self.monitor.disk.probe = Mock(side_effect=OSError("volume unavailable"))
        self.change(supported=False)
        with self.assertRaisesRegex(VerificationEnvironmentError, "disk state unknown"):
            self.monitor.checkpoint(require_display=False)

    def test_low_disk_starts_no_smoke_and_is_not_a_test_failure(self):
        self.free = 7 * GIB
        self.change()
        code, report, count = self.run_smoke_fixture([{"id": "disk-blocked"}], Mock())
        self.assertEqual((code, count, report["failed"]), (2, 0, 0))
        self.assertEqual(report["results"][0]["environmentStatus"], "NOT RUN")

    def test_low_disk_starts_no_docs_server(self):
        state, action = self.docs_state(), Mock()
        self.free = 7 * GIB
        self.change()
        with self.assertRaisesRegex(VerificationEnvironmentError, "disk floor"):
            state.capture_with_environment(action, options=self.options(), page_path=Path("disk.md"), block_index=1)
        action.assert_not_called()
        state._ensure_runner_owned_field_server.assert_not_called()
        self.assertEqual(state.environment_outcomes[0]["status"], "not-run")

    def test_docs_disk_loss_invalidates_capture_even_after_recovery(self):
        state = self.docs_state()
        def capture(**kwargs):
            self.free = 7 * GIB
            self.change()
            self.free = 20 * GIB
            self.change()
        with self.assertRaisesRegex(VerificationEnvironmentError, "disk floor"):
            state.capture_with_environment(capture, options=self.options(), page_path=Path("disk.md"), block_index=1)
        self.assertEqual(state.environment_outcomes[0]["status"], "interrupted")
        state._stop_shared_field_server.assert_called_once()


class DiskBudgetTests(unittest.TestCase):
    def budget(self, free=20, **kwargs):
        return DiskBudget(Path.cwd(), probe=lambda path: SimpleNamespace(free=int(free * GIB)),
                          floor_gib=8, reserve_gib=2, planned_gib=kwargs.get("planned", 6))

    def test_planned_growth_is_admission_only_reserve_is_continuous(self):
        budget = self.budget(free=16)
        budget.admit(budget.sample())  # equality is sufficient
        budget.probe = lambda path: SimpleNamespace(free=10 * GIB)
        snapshot = budget.sample()
        budget.admit(snapshot)  # do not charge the six GiB again at every action
        self.assertIsNone(environment_problem({"supported": False, "disk": snapshot}, require_display=False))
        budget.probe = lambda path: SimpleNamespace(free=10 * GIB - 1)
        self.assertIn("disk floor", environment_problem({"supported": False, "disk": budget.sample()}, require_display=False))

    def test_floor_alone_does_not_admit_an_unfunded_run(self):
        budget = self.budget(free=15)
        with self.assertRaisesRegex(VerificationEnvironmentError, "planned whole-run growth 6"):
            budget.admit(budget.sample())
        self.assertFalse(budget.admitted)

    def test_configuration_rejects_negative_nonfinite_and_bad_numbers(self):
        for value in (-1, "nan", "inf", "broken"):
            with self.subTest(value=value), self.assertRaisesRegex(VerificationEnvironmentError, "finite non-negative"):
                DiskBudget(Path.cwd(), floor_gib=value)

    def test_environment_configuration_is_reported(self):
        with patch.dict("os.environ", {"FIELD_VERIFY_DISK_FLOOR_GIB": "5",
                                       "FIELD_VERIFY_DISK_RESERVE_GIB": "8",
                                       "FIELD_VERIFY_DISK_PLANNED_GIB": "22"}):
            budget = DiskBudget(Path.cwd())
        self.assertEqual((budget.floor, budget.reserve, budget.planned), (5 * GIB, 8 * GIB, 22 * GIB))

    def test_report_does_not_call_a_volume_drop_checkout_consumption(self):
        budget = self.budget()
        budget.sample()
        budget.probe = lambda path: SimpleNamespace(free=30 * GIB)
        budget.sample()
        self.assertEqual(budget.report()["maxObservedVolumeLossBytes"], 0)
        self.assertIn("other writers", budget.report()["attribution"])

    def test_disk_path_is_the_explicit_checkout_not_the_callers_cwd(self):
        with tempfile.TemporaryDirectory() as directory:
            probe = Mock(return_value=SimpleNamespace(free=20 * GIB))
            budget = DiskBudget(Path(directory), probe=probe, floor_gib=8, reserve_gib=0, planned_gib=0)
            budget.sample()
            probe.assert_called_once_with(Path(directory).resolve())


if __name__ == "__main__":
    unittest.main()
