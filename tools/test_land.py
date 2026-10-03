"""tools/land.py against a throwaway clone, with merge_regen.py stubbed so no Gradle runs."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

LAND = Path(__file__).resolve().parent / "land.py"

# Stands in for tools/merge_regen.py: a plain --no-ff merge with the lander's message, or a failure
# when the merged tree contains a file named BREAKS, the way a red guard would refuse it.
STUB = '''
import subprocess, sys, pathlib
sha, msg = sys.argv[1], sys.argv[sys.argv.index("-m") + 1]
subprocess.run(["git", "merge", "--no-ff", "--no-commit", sha], check=True)
if pathlib.Path("BREAKS").exists():
    print("merge_regen: a guard failed; the merge is left uncommitted for you to fix", file=sys.stderr)
    sys.exit(1)
sys.exit(subprocess.run(["git", "commit", "-q", "-m", msg]).returncode)
'''


class LandQueueTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.primary = root / "repo"
        self.lander = root / "lander"
        self.env = {
            **os.environ,
            "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
            "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t",
            "FIELD_LAND_BY": "test-agent",
        }
        self.primary.mkdir()
        self.git(self.primary, "init", "-q", "-b", "main")
        self.git(self.primary, "config", "core.hooksPath", "/dev/null")
        self.git(self.primary, "config", "merge.field-generated.driver", "true")
        (self.primary / "tools").mkdir()
        (self.primary / "tools/merge_regen.py").write_text(STUB)
        (self.primary / "seed.txt").write_text("seed\n")
        self.git(self.primary, "add", "-A")
        self.git(self.primary, "commit", "-q", "-m", "seed")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def git(self, cwd: Path, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True, env=self.env).stdout.strip()

    def land(self, cwd: Path, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(LAND), *args], cwd=cwd, capture_output=True, text=True, env=self.env)

    def branch_with(self, name: str, filename: str) -> Path:
        wt = Path(self._tmp.name) / name
        self.git(self.primary, "worktree", "add", "-q", "-b", name, str(wt), "main")
        (wt / filename).write_text(f"{name}\n")
        self.git(wt, "add", "-A")
        self.git(wt, "commit", "-q", "-m", f"work on {name}")
        return wt

    def run_lander(self) -> subprocess.CompletedProcess[str]:
        return self.land(self.primary, "run", "--once", "--worktree", str(self.lander))

    def test_two_branches_land_in_order_with_attributed_commits(self) -> None:
        for name in ("alpha", "beta"):
            self.assertEqual(self.land(self.branch_with(name, f"{name}.txt"), "enqueue").returncode, 0)
        result = self.run_lander()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        subjects = self.git(self.primary, "log", "--format=%s", "main").splitlines()
        self.assertEqual(subjects[:2], ["Land beta (test-agent)", "Land alpha (test-agent)"])
        self.assertTrue((self.primary / "beta.txt").exists(), "primary's checkout was not fast-forwarded")

    def test_a_failing_landing_bounces_and_leaves_main_alone(self) -> None:
        before = self.git(self.primary, "rev-parse", "main")
        self.land(self.branch_with("broken", "BREAKS"), "enqueue")
        self.run_lander()
        self.assertEqual(self.git(self.primary, "rev-parse", "main"), before)
        status = self.land(self.primary, "status")
        self.assertIn("bounced", status.stdout)
        self.assertIn("a guard failed", status.stdout)

    def test_dirty_files_in_the_primary_survive_the_fast_forward(self) -> None:
        (self.primary / "seed.txt").write_text("someone else's uncommitted edit\n")
        self.land(self.branch_with("gamma", "gamma.txt"), "enqueue")
        self.run_lander()
        self.assertIn("Land gamma", self.git(self.primary, "log", "-1", "--format=%s", "main"))
        self.assertEqual((self.primary / "seed.txt").read_text(), "someone else's uncommitted edit\n")

    def test_enqueue_refuses_uncommitted_work(self) -> None:
        wt = self.branch_with("delta", "delta.txt")
        (wt / "delta.txt").write_text("not committed\n")
        result = self.land(wt, "enqueue")
        self.assertEqual(result.returncode, 2)
        self.assertIn("uncommitted", result.stderr)

    def test_a_branch_already_on_main_is_not_queued(self) -> None:
        wt = self.branch_with("eps", "eps.txt")
        self.git(self.primary, "merge", "-q", "--ff-only", "eps")
        result = self.land(wt, "enqueue")
        self.assertEqual(result.returncode, 0)
        self.assertIn("already on main", result.stdout)

    def test_a_second_lander_refuses_and_status_sees_the_first(self) -> None:
        self.assertIn("no lander running", self.land(self.primary, "status").stdout)
        first = subprocess.Popen(
            [sys.executable, str(LAND), "run", "--worktree", str(self.lander)],
            cwd=self.primary, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        try:
            # "lander up in ..." is printed once the lock is held (after git's own worktree output).
            line = ""
            while "lander up" not in line:
                line = first.stdout.readline()
                self.assertNotEqual(line, "", "the first lander exited before it was up")
            second = self.land(self.primary, "run", "--once", "--worktree", str(self.lander) + "-2")
            self.assertEqual(second.returncode, 2)
            self.assertIn("already running", second.stderr)
            self.assertNotIn("no lander running", self.land(self.primary, "status").stdout)
        finally:
            first.kill()
            first.wait()
        # SIGKILL leaves no cleanup behind, and status must not be fooled by that.
        self.assertIn("no lander running", self.land(self.primary, "status").stdout)

    def test_the_hook_refuses_a_direct_merge_into_main_but_not_the_lander(self) -> None:
        hooks = Path(self._tmp.name) / "hooks"
        hooks.mkdir()
        hook = hooks / "reference-transaction"
        hook.write_text((LAND.parent / "hooks/reference-transaction").read_text())
        hook.chmod(0o755)
        self.git(self.primary, "config", "core.hooksPath", str(hooks))
        wt = self.branch_with("zeta", "zeta.txt")
        before = self.git(self.primary, "rev-parse", "main")
        direct = subprocess.run(["git", "merge", "--ff-only", "zeta"], cwd=self.primary, capture_output=True, text=True, env=self.env)
        self.assertNotEqual(direct.returncode, 0)
        self.assertIn("land.py enqueue", direct.stderr)
        self.assertEqual(self.git(self.primary, "rev-parse", "main"), before)
        self.git(self.primary, "pack-refs", "--all")  # git's own housekeeping must still work
        self.land(wt, "enqueue")
        self.run_lander()
        self.assertIn("Land zeta", self.git(self.primary, "log", "-1", "--format=%s", "main"))


if __name__ == "__main__":
    unittest.main()
