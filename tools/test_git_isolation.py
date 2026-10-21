"""A suite that builds scratch repositories cannot touch the repository an inherited GIT_DIR names.

The 2026-10-21 incident: `tools.test_refactor_pass` run with `GIT_DIR` set made the real repository bare.
Here the same suite runs with `GIT_DIR` pointing at a throwaway repository in that shape (a linked
worktree's git dir, `extensions.worktreeConfig` on), and the throwaway must come out byte-identical.
"""

from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools import git_isolation

REPO_ROOT = Path(__file__).resolve().parents[1]


def _git(*args: str) -> None:
    subprocess.run(["git", *args], check=True, capture_output=True, env=git_isolation.scrubbed_env())


def _fingerprint(git_dir: Path) -> dict[str, str]:
    """Every file under the common git dir, by content: config, HEAD, refs, index, worktree entries."""
    return {
        str(path.relative_to(git_dir)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(git_dir.rglob("*"))
        if path.is_file() and "objects" not in path.parts and "logs" not in path.parts
    }


class AnInheritedGitDirRedirectsNothing(unittest.TestCase):
    def test_test_refactor_pass_leaves_the_repository_git_dir_names_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            outer = Path(tmp, "outer")
            _git("init", "-q", str(outer))
            _git("-C", str(outer), "-c", "user.name=t", "-c", "user.email=t@e", "commit", "-q", "--allow-empty", "-m", "x")
            _git("-C", str(outer), "config", "extensions.worktreeConfig", "true")
            _git("-C", str(outer), "worktree", "add", "-q", str(Path(tmp, "wt")))
            before = _fingerprint(outer / ".git")

            env = {**git_isolation.scrubbed_env(), "GIT_DIR": str(outer / ".git" / "worktrees" / "wt"),
                   "GIT_INDEX_FILE": str(outer / ".git" / "worktrees" / "wt" / "index")}
            run = subprocess.run([sys.executable, "-m", "unittest", "tools.test_refactor_pass"], cwd=REPO_ROOT,
                                 env=env, capture_output=True, text=True, timeout=600)

            self.assertEqual(_fingerprint(outer / ".git"), before,
                             "a suite run under an inherited GIT_DIR changed the repository it names")
            self.assertEqual(run.returncode, 0, run.stdout[-2000:] + run.stderr[-4000:])

    def test_scrubbed_env_drops_exactly_gits_repository_variables(self):
        env = {"GIT_DIR": "/x/.git", "GIT_WORK_TREE": "/x", "GIT_COMMON_DIR": "/x/.git", "GIT_AUTHOR_NAME": "a",
               "PATH": os.environ.get("PATH", "")}
        self.assertEqual(sorted(git_isolation.scrubbed_env(env)), ["GIT_AUTHOR_NAME", "PATH"])
        self.assertIn("GIT_OBJECT_DIRECTORY", git_isolation.repository_variables())


if __name__ == "__main__":
    unittest.main()
