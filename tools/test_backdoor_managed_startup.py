"""The managed-server startup wait in `tools/field_backdoor.py`, exercised without starting Field.

The wait used to be a flat 120 s, so the first `./gradlew runJvm` in a fresh worktree -- the whole
Kotlin JVM + JS compile and webpack -- always lost the race and every backdoor command failed with
"did not become healthy ... within 120 seconds" while Gradle was still compiling. These tests
drive `ManagedStartupWatch` and `FieldBackdoor.wait_for_managed_server` with a fake clock, a
temporary managed log, a fake process and a patched health check.
"""

import asyncio
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_PATH = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_PATH))
sys.path.insert(0, str(REPO_PATH / "tools"))

from tools.field_backdoor import (  # noqa: E402
    MANAGED_JVM_START_TIMEOUT_ENV,
    MANAGED_STARTUP_CEILING_ENV,
    BackdoorError,
    FieldBackdoor,
    ManagedStartupWatch,
    env_seconds,
)


class _Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class _Logger:
    session_id = "managed-startup-test"
    path = Path("/tmp/managed-startup-test.jsonl")

    def __init__(self) -> None:
        self.events: list[tuple[str, dict]] = []

    def log(self, event: str, **fields) -> None:
        self.events.append((event, fields))


class _Process:
    """Stands in for the `subprocess.Popen` of `./gradlew runJvm`."""

    def __init__(self) -> None:
        self.returncode: int | None = None
        self.pid = 4242

    def poll(self) -> int | None:
        return self.returncode


class _Fixture(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.log_path = Path(self.tmp.name) / "session.managed-server.log"
        self.log_path.write_text("")
        self.clock = _Clock()

    def append(self, text: str) -> None:
        with self.log_path.open("a", encoding="utf-8") as handle:
            handle.write(text)

    def watch(self, *, ceiling: float = 1800.0, jvm_timeout: float = 120.0) -> ManagedStartupWatch:
        return ManagedStartupWatch(self.log_path, ceiling=ceiling, jvm_timeout=jvm_timeout, clock=self.clock)


class ManagedStartupWatchTests(_Fixture):
    def test_a_long_gradle_build_is_not_held_to_the_jvm_budget(self) -> None:
        watch = self.watch()
        self.append("> Task :compileKotlinJvm\n")
        self.clock.now += 600  # five times the old flat budget
        watch.observe()
        self.assertEqual(watch.phase, "build")
        self.assertEqual(watch.last_task, ":compileKotlinJvm")
        self.assertFalse(watch.expired())

    def test_the_jvm_budget_starts_at_the_runjvm_task_line(self) -> None:
        watch = self.watch()
        self.append("> Task :compileKotlinJvm\n> Task :jsBrowserDevelopmentWebpack\n")
        self.clock.now += 400
        watch.observe()
        self.append("> Task :runJvm\n")
        watch.observe()
        self.assertEqual(watch.phase, "jvm")
        self.clock.now += 119
        self.assertFalse(watch.expired())
        self.clock.now += 1
        self.assertTrue(watch.expired())
        reason = watch.timeout_reason()
        self.assertIn("reached :runJvm 120s ago", reason)
        self.assertIn(MANAGED_JVM_START_TIMEOUT_ENV, reason)

    def test_the_ceiling_caps_both_phases(self) -> None:
        watch = self.watch(ceiling=300.0, jvm_timeout=120.0)
        self.clock.now += 299
        watch.observe()
        self.assertFalse(watch.expired())
        self.clock.now += 1
        self.assertTrue(watch.expired())
        self.assertIn("the Gradle build was still running when the 300s startup ceiling", watch.timeout_reason())
        self.assertIn("none yet", watch.timeout_reason())

        late = self.watch(ceiling=300.0, jvm_timeout=120.0)
        self.clock.now += 250
        self.append("> Task :runJvm\n")
        late.observe()
        self.clock.now += 50
        self.assertTrue(late.expired(), "the JVM budget must not extend past the ceiling")
        self.assertIn("the JVM was still starting", late.timeout_reason())

    def test_a_line_split_across_reads_is_still_recognised(self) -> None:
        watch = self.watch()
        self.append("> Task :ru")
        watch.observe()
        self.assertEqual(watch.phase, "build")
        self.append("nJvm\n")
        watch.observe()
        self.assertEqual(watch.phase, "jvm")
        self.assertEqual(watch.last_task, ":runJvm")

    def test_jvm_output_mentioning_runjvm_does_not_restart_the_budget(self) -> None:
        watch = self.watch()
        self.append("> Task :runJvm\n")
        watch.observe()
        started = watch.jvm_started_at
        self.clock.now += 60
        self.append("> Task :runJvm\nsome JVM output\n")
        watch.observe()
        self.assertEqual(watch.jvm_started_at, started)

    def test_a_verification_lock_wait_is_named_in_the_timeout(self) -> None:
        watch = self.watch(ceiling=60.0)
        self.append("WAIT gradle: verification lock held by {\"pid\": 77}; waited 0s (limit 1800s).\n")
        watch.observe()
        self.clock.now += 60
        self.assertTrue(watch.expired())
        self.assertIn("verification lock", watch.timeout_reason())
        self.assertIn('"pid": 77', watch.timeout_reason())

        self.append("gradle: verification lock released; continuing.\n")
        watch.observe()
        self.assertIsNone(watch.lock_wait)

    def test_a_missing_log_is_not_an_error(self) -> None:
        watch = ManagedStartupWatch(
            Path(self.tmp.name) / "absent.log", ceiling=10.0, jvm_timeout=5.0, clock=self.clock
        )
        watch.observe()
        self.assertEqual(watch.phase, "build")


class EnvSecondsTests(unittest.TestCase):
    def test_unset_and_blank_fall_back_to_the_default(self) -> None:
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("AFIELD_TEST_SECONDS", None)
            self.assertEqual(env_seconds("AFIELD_TEST_SECONDS", 7.0), 7.0)
        with mock.patch.dict(os.environ, {"AFIELD_TEST_SECONDS": "  "}):
            self.assertEqual(env_seconds("AFIELD_TEST_SECONDS", 7.0), 7.0)

    def test_values_are_parsed_and_nonsense_is_refused(self) -> None:
        with mock.patch.dict(os.environ, {"AFIELD_TEST_SECONDS": "2400"}):
            self.assertEqual(env_seconds("AFIELD_TEST_SECONDS", 7.0), 2400.0)
        for bad in ("soon", "0", "-5"):
            with mock.patch.dict(os.environ, {"AFIELD_TEST_SECONDS": bad}):
                with self.assertRaises(BackdoorError):
                    env_seconds("AFIELD_TEST_SECONDS", 7.0)


class WaitForManagedServerTests(_Fixture):
    """The loop in `FieldBackdoor.wait_for_managed_server`, with every outside effect injected."""

    def setUp(self) -> None:
        super().setUp()
        self.logger = _Logger()
        self.client = FieldBackdoor(port=1, workspace=None, default_timeout=0.01, logger=self.logger)
        self.process = _Process()
        self.client.managed_process = self.process  # type: ignore[assignment]
        self.client.managed_log_path = self.log_path
        # A script of (time offset, log text) the fake Gradle writes as the clock passes it.
        self.script: list[tuple[float, str]] = []
        self.healthy_after: float | None = None
        self.dies_at: float | None = None
        self.start = self.clock.now

    def fake_health(self, *_args, **_kwargs) -> bool:
        """One health probe; each costs five fake seconds and lets the fake Gradle progress."""
        self.clock.now += 5.0
        elapsed = self.clock.now - self.start
        while self.script and self.script[0][0] <= elapsed:
            self.append(self.script.pop(0)[1])
        if self.dies_at is not None and elapsed >= self.dies_at:
            self.process.returncode = 1
        return self.healthy_after is not None and elapsed >= self.healthy_after

    def run_wait(self, watch: ManagedStartupWatch) -> None:
        with (
            mock.patch("tools.field_backdoor.health_check", side_effect=self.fake_health),
            mock.patch("tools.field_backdoor.field_instance_info", return_value={"workspace": "/tmp/ws"}),
        ):
            asyncio.run(self.client.wait_for_managed_server(watch, poll_interval=0))

    def test_a_cold_build_longer_than_120s_then_a_healthy_jvm_succeeds(self) -> None:
        self.script = [
            (5, "> Task :compileKotlinJvm\n"),
            (300, "> Task :compileKotlinJs\n"),
            (480, "> Task :jsBrowserDevelopmentWebpack\n"),
            (540, "> Task :runJvm\n"),
        ]
        self.healthy_after = 580
        self.run_wait(self.watch())
        events = [name for name, _ in self.logger.events]
        self.assertIn("managed_server_jvm_starting", events)
        self.assertEqual(events[-1], "managed_server_ready")
        self.assertEqual(self.client.server_workspace, "/tmp/ws")

    def test_a_process_that_dies_mid_build_fails_at_once_and_names_the_task(self) -> None:
        self.script = [(5, "> Task :compileKotlinJvm\n")]
        self.dies_at = 200
        with self.assertRaises(BackdoorError) as raised:
            self.run_wait(self.watch())
        message = str(raised.exception)
        self.assertIn("exited early with code 1", message)
        self.assertIn("during the Gradle build (last task :compileKotlinJvm)", message)
        self.assertLess(self.clock.now - self.start, 210, "an exited process must not wait out a budget")

    def test_a_jvm_that_never_answers_fails_after_its_own_budget(self) -> None:
        self.script = [(5, "> Task :compileKotlinJvm\n"), (400, "> Task :runJvm\n")]
        with self.assertRaises(BackdoorError) as raised:
            self.run_wait(self.watch(jvm_timeout=120.0))
        message = str(raised.exception)
        self.assertIn("did not become healthy", message)
        self.assertIn("start budget", message)
        self.assertIn(str(self.log_path), message)
        # :runJvm appeared at 400 s; the budget is 120 s from there, plus one 5 s probe of slack.
        self.assertGreaterEqual(self.clock.now - self.start, 520)
        self.assertLess(self.clock.now - self.start, 530)

    def test_a_build_that_never_finishes_fails_at_the_ceiling(self) -> None:
        self.script = [(5, "> Task :compileKotlinJvm\n")]
        with self.assertRaises(BackdoorError) as raised:
            self.run_wait(self.watch(ceiling=900.0))
        self.assertIn("Gradle build was still running when the 900s startup ceiling", str(raised.exception))
        self.assertIn(":compileKotlinJvm", str(raised.exception))


class EnsureServerBudgetTests(unittest.TestCase):
    """`ensure_server` hands the configured budgets to the wait, from the CLI or the environment."""

    def launch(self, client: FieldBackdoor) -> ManagedStartupWatch:
        captured: list[ManagedStartupWatch] = []

        async def fake_wait(watch: ManagedStartupWatch, **_kwargs) -> None:
            captured.append(watch)

        with tempfile.TemporaryDirectory() as root:
            client.logger.root = Path(root)  # type: ignore[attr-defined]
            with (
                mock.patch("tools.field_backdoor.health_check", return_value=False),
                mock.patch("tools.field_backdoor.subprocess.Popen", return_value=_Process()),
                mock.patch("tools.field_backdoor.resolve_jdwp_port", return_value="off"),
                mock.patch.object(client, "wait_for_managed_server", side_effect=fake_wait),
            ):
                asyncio.run(client.ensure_server())
            client.managed_log_file.close()  # type: ignore[union-attr]
        self.assertEqual(len(captured), 1)
        return captured[0]

    def test_the_environment_sets_both_budgets(self) -> None:
        client = FieldBackdoor(port=1, workspace=None, default_timeout=0.01, logger=_Logger())
        env = {MANAGED_STARTUP_CEILING_ENV: "2400", MANAGED_JVM_START_TIMEOUT_ENV: "45"}
        with mock.patch.dict(os.environ, env):
            watch = self.launch(client)
        self.assertEqual((watch.ceiling, watch.jvm_timeout), (2400.0, 45.0))

    def test_an_explicit_startup_timeout_beats_the_environment(self) -> None:
        client = FieldBackdoor(
            port=1, workspace=None, default_timeout=0.01, logger=_Logger(), startup_timeout=600.0
        )
        with mock.patch.dict(os.environ, {MANAGED_STARTUP_CEILING_ENV: "2400"}):
            watch = self.launch(client)
        self.assertEqual(watch.ceiling, 600.0)

    def test_the_defaults_wait_far_longer_than_120s_for_a_build(self) -> None:
        client = FieldBackdoor(port=1, workspace=None, default_timeout=0.01, logger=_Logger())
        with mock.patch.dict(os.environ, {}):
            os.environ.pop(MANAGED_STARTUP_CEILING_ENV, None)
            os.environ.pop(MANAGED_JVM_START_TIMEOUT_ENV, None)
            watch = self.launch(client)
        self.assertGreaterEqual(watch.ceiling, 1200.0)
        self.assertEqual(watch.jvm_timeout, 120.0)


if __name__ == "__main__":
    unittest.main()
