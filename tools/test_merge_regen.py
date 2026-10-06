"""tools/merge_regen.py in a throwaway repo: generated conflicts merge clean, source conflicts stop."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "merge_regen.py"
ATTRIBUTES = (Path(__file__).resolve().parents[1] / ".gitattributes").read_text()


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=repo, check=True, stdout=subprocess.PIPE, text=True).stdout


class MergeRegenTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "user.email", "t@example.com")
        git(self.repo, "config", "user.name", "t")
        git(self.repo, "config", "core.hooksPath", "/dev/null")
        # The repo's real attributes, minus LFS filters a bare temp repo cannot run.
        lines = [line for line in ATTRIBUTES.splitlines() if "filter=lfs" not in line]
        (self.repo / ".gitattributes").write_text("\n".join(lines) + "\n")
        self.generated = self.repo / "dev_docs/kdoc_worklist.md"
        self.source = self.repo / "notes.txt"
        self.generated.parent.mkdir(parents=True)
        self._commit_both("base")
        git(self.repo, "checkout", "-q", "-b", "other")
        self._commit_both("theirs")
        git(self.repo, "checkout", "-q", "main")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _commit_both(self, text: str, source: bool = True) -> None:
        self.generated.write_text(text + "\n")
        if source:
            self.source.write_text(text + "\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", text)

    def _run(self, *args: str) -> subprocess.CompletedProcess[str]:
        env = {**os.environ, "GIT_EDITOR": "true"}
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args], cwd=self.repo, env=env, capture_output=True, text=True
        )

    def test_refuses_without_registered_driver(self) -> None:
        self._commit_both("ours")
        result = self._run("other")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("install_git_hooks.py", result.stderr)

    def test_generated_conflict_merges_clean_and_source_conflict_stops(self) -> None:
        git(self.repo, "config", "merge.field-generated.driver", "true")
        self._commit_both("ours")
        result = self._run("other")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("notes.txt", result.stderr)
        self.assertNotIn("kdoc_worklist", result.stderr)
        self.assertEqual(self.generated.read_text(), "ours\n")


class RegeneratorWritesAreAuthorizedTest(unittest.TestCase):
    """Every merge_regen regenerator may write its own outputs under the test tree-write guard.

    The guard finalises every Gradle Test task and sees every write since its baseline, so a
    regenerator whose outputs no escape covers fails any build it shares with a test. On 2026-10-04
    that was the completion-corpus harvest: 182 identical-byte rewrites bounced two landings.
    """

    def test_every_regenerator_output_root_is_covered_by_an_escape(self) -> None:
        sys.path.insert(0, str(SCRIPT.parent))
        import check_test_tree_writes as trees
        import merge_regen
        from check_staged_generated_artifacts import GUARDS

        environ = {}
        for _args, extra_env in merge_regen.REGENERATORS.values():
            environ.update(extra_env)
        repo = SCRIPT.resolve().parents[1]
        allowed = tuple(p for prefixes in trees._escape_prefixes(environ, repo).values() for p in prefixes)
        for guard in GUARDS:
            if guard.task not in merge_regen.REGENERATORS:
                continue
            roots = guard.output_roots or merge_regen.REGENERATED_ROOTS.get(guard.task, ())
            for root in roots:
                self.assertTrue(trees._covered(root, allowed),
                                f"{guard.task} writes {root}, and no tree-write escape authorizes it")


if __name__ == "__main__":
    unittest.main()
