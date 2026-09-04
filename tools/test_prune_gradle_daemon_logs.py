import os
import tempfile
import unittest
from pathlib import Path

from tools.prune_gradle_daemon_logs import eligible_logs


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


if __name__ == "__main__":
    unittest.main()
