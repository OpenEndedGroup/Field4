import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.prune_gradle_daemon_logs import eligible_logs, main


class GradleDaemonLogRetentionTests(unittest.TestCase):
    def test_only_old_logs_for_dead_pids_are_eligible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            version = Path(directory) / "9.5.1"
            version.mkdir()
            old_dead = version / "daemon-10.out.log"
            old_live = version / "daemon-11.out.log"
            new_dead = version / "daemon-12.out.log"
            unrelated = version / "registry.bin"
            for path in (old_dead, old_live, new_dead, unrelated):
                path.write_text("x")
            os.utime(old_dead, (0, 0))
            os.utime(old_live, (0, 0))

            found = eligible_logs(
                Path(directory), older_than_days=7, now=10 * 86400, alive=lambda pid: pid == 11
            )
            self.assertEqual(found, [old_dead])

    def test_missing_root_is_an_empty_population(self) -> None:
        self.assertEqual(
            eligible_logs(Path("/definitely/not/here"), older_than_days=7, now=10 * 86400), []
        )

    def test_symlinks_and_symlinked_version_directories_are_not_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            version = root / "9.5.1"
            version.mkdir()
            external = root / "external"
            external.mkdir()
            target = external / "daemon-10.out.log"
            target.write_text("valuable")
            os.utime(target, (0, 0))
            (version / "daemon-10.out.log").symlink_to(target)
            (root / "linked-version").symlink_to(external, target_is_directory=True)
            self.assertEqual(eligible_logs(root, older_than_days=7, now=10 * 86400,
                                           alive=lambda _: False), [target])

    def test_nonfinite_horizon_never_removes_logs(self) -> None:
        script = Path(__file__).with_name("prune_gradle_daemon_logs.py")
        with tempfile.TemporaryDirectory() as directory:
            version = Path(directory) / "9.5.1"
            version.mkdir()
            log = version / "daemon-99999999.out.log"
            log.write_text("keep")
            os.utime(log, (0, 0))
            for value in ("nan", "inf", "-inf"):
                with self.subTest(value=value):
                    result = subprocess.run([sys.executable, str(script), "--root", directory,
                                             f"--older-than-days={value}", "--apply"],
                                            capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertTrue(log.exists())

    def test_pid_is_rechecked_before_deleting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            version = Path(directory) / "9.5.1"
            version.mkdir()
            log = version / "daemon-99999999.out.log"
            log.write_text("keep")
            os.utime(log, (0, 0))
            with mock.patch.object(sys, "argv", ["prune", "--root", directory, "--apply"]), \
                 mock.patch("tools.prune_gradle_daemon_logs.pid_is_alive", return_value=True):
                self.assertEqual(main(), 0)
            self.assertTrue(log.exists())

    def test_real_apply_removes_only_old_dead_log_in_private_tree(self) -> None:
        script = Path(__file__).with_name("prune_gradle_daemon_logs.py")
        with tempfile.TemporaryDirectory() as directory:
            version = Path(directory) / "9.5.1"
            version.mkdir()
            old = version / "daemon-99999999.out.log"
            live = version / f"daemon-{os.getpid()}.out.log"
            recent = version / "daemon-99999998.out.log"
            for log in (old, live, recent):
                log.write_text("data")
            for log in (old, live):
                os.utime(log, (0, 0))
            result = subprocess.run([sys.executable, str(script), "--root", directory, "--apply"],
                                    capture_output=True, text=True, timeout=10, check=True)
            self.assertIn("removed 1", result.stdout)
            self.assertFalse(old.exists())
            self.assertTrue(live.exists())
            self.assertTrue(recent.exists())


if __name__ == "__main__":
    unittest.main()
