"""A new worktree's tracked files are clones of the primary checkout's, never wrong ones.

Every worktree of this repository used to hold its own 4.8 GiB of `public/docs/`, because
`git worktree add` writes each tracked file afresh. `tools/worktree_seed.py` clones what the primary
already holds. These tests pin both halves of that: a clean file the two checkouts share costs no
private bytes (read from APFS's `ATTR_CMNEXT_PRIVATESIZE`; `du` counts clones as copies), and a file
the primary has edited, holds at another commit, or stores in other bytes than git would write is
never cloned, so `git status` in the new worktree is as clean as after a plain checkout.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

from git_isolation import scrub_process_env  # noqa: E402

scrub_process_env()

import cow_clone  # noqa: E402
import worktree_seed  # noqa: E402

ASSET = bytes(range(256)) * 4096  # many APFS blocks, so a private size of 0 cannot be rounding


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(cwd), "-c", "user.name=t", "-c", "user.email=t@t",
                           "-c", "commit.gpgsign=false", *args],
                          check=True, capture_output=True, text=True).stdout


def can_clone(root: Path) -> bool:
    probe = root / "probe"
    probe.write_bytes(ASSET)
    cloned = cow_clone.try_clone(probe, root / "probe.clone")
    probe.unlink()
    if cloned:
        (root / "probe.clone").unlink()
    return cloned and cow_clone.private_bytes(probe.parent) is not None


class _Repo(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(os.path.realpath(self._tmp.name))
        self.primary = self.root / "primary"
        self.primary.mkdir()
        git(self.primary, "init", "-q", "-b", "main")
        (self.primary / "assets").mkdir()
        (self.primary / "assets/figure.png").write_bytes(ASSET)
        (self.primary / "edited.txt").write_text("committed\n")
        (self.primary / "tool.sh").write_text("#!/bin/sh\necho hi\n")
        (self.primary / "tool.sh").chmod(0o755)
        git(self.primary, "add", ".")
        git(self.primary, "commit", "-qm", "base")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def assertClean(self, worktree: Path) -> None:
        self.assertEqual(git(worktree, "status", "--porcelain"), "")

    def needs_clones(self) -> None:
        if not can_clone(self.root):
            self.skipTest("cloning needs APFS")


class AddTests(_Repo):
    def test_a_clean_shared_file_is_a_clone_and_status_stays_clean(self) -> None:
        self.needs_clones()
        worktree = self.root / "wt"
        result = worktree_seed.add_worktree(self.primary, worktree, "main", new_branch="topic")
        self.assertEqual((result.tracked, result.cloned, result.note), (3, 3, ""))
        self.assertEqual(cow_clone.private_bytes(worktree / "assets/figure.png"), 0)
        self.assertEqual((worktree / "assets/figure.png").read_bytes(), ASSET)
        self.assertTrue(os.access(worktree / "tool.sh", os.X_OK))
        self.assertEqual(git(worktree, "branch", "--show-current").strip(), "topic")
        self.assertClean(worktree)

    def test_a_file_edited_in_the_primary_is_checked_out_from_the_commit(self) -> None:
        (self.primary / "edited.txt").write_text("work in progress\n")
        worktree = self.root / "wt"
        result = worktree_seed.add_worktree(self.primary, worktree, "main", detach=True)
        self.assertEqual((worktree / "edited.txt").read_text(), "committed\n")
        self.assertLessEqual(result.cloned, 2)
        self.assertClean(worktree)

    def test_a_file_at_another_commit_is_checked_out_from_the_commit(self) -> None:
        (self.primary / "edited.txt").write_text("newer\n")
        git(self.primary, "commit", "-qam", "newer")
        worktree = self.root / "wt"
        worktree_seed.add_worktree(self.primary, worktree, "main~1", detach=True)
        self.assertEqual((worktree / "edited.txt").read_text(), "committed\n")
        self.assertClean(worktree)

    def test_bytes_git_normalises_are_not_cloned(self) -> None:
        # Clean to git (text=auto folds CRLF), but a checkout writes LF: the clone would differ.
        (self.primary / ".gitattributes").write_text("* text=auto eol=lf\n")
        (self.primary / "notes.txt").write_bytes(b"one\r\ntwo\r\n")
        git(self.primary, "add", ".")
        git(self.primary, "commit", "-qm", "crlf")
        self.assertEqual(git(self.primary, "status", "--porcelain"), "")
        worktree = self.root / "wt"
        worktree_seed.add_worktree(self.primary, worktree, "main", detach=True)
        self.assertEqual((worktree / "notes.txt").read_bytes(), b"one\ntwo\n")
        self.assertClean(worktree)

    def test_a_file_the_primary_changes_mid_seed_is_rewritten_by_git(self) -> None:
        self.needs_clones()
        real = cow_clone.try_clone

        def racing(source, destination) -> bool:
            cloned = real(source, destination)
            if Path(source).name == "edited.txt":
                Path(destination).write_text("edited after the check\n")
            return cloned

        worktree = self.root / "wt"
        with mock.patch.object(worktree_seed, "try_clone", racing):
            result = worktree_seed.add_worktree(self.primary, worktree, "main", detach=True)
        self.assertEqual((result.cloned, result.rewritten), (2, 1))
        self.assertEqual((worktree / "edited.txt").read_text(), "committed\n")
        self.assertClean(worktree)

    def test_where_nothing_clones_it_is_an_ordinary_checkout(self) -> None:
        worktree = self.root / "wt"
        with mock.patch.object(worktree_seed, "try_clone", return_value=False):
            result = worktree_seed.add_worktree(self.primary, worktree, "main", detach=True)
        self.assertEqual(result.cloned, 0)
        self.assertIn("cannot clone", result.note)
        self.assertEqual((worktree / "assets/figure.png").read_bytes(), ASSET)
        self.assertClean(worktree)

    def test_a_seeding_failure_still_leaves_a_complete_checkout(self) -> None:
        worktree = self.root / "wt"
        with mock.patch.object(worktree_seed, "candidates", side_effect=RuntimeError("boom")):
            result = worktree_seed.add_worktree(self.primary, worktree, "main", detach=True)
        self.assertIn("boom", result.note)
        self.assertEqual((worktree / "edited.txt").read_text(), "committed\n")
        self.assertClean(worktree)

    def test_the_cli_mirrors_git_worktree_add(self) -> None:
        worktree = self.root / "wt"
        code = worktree_seed.main(["add", "-C", str(self.primary), "-b", "nightly/x", str(worktree), "main"])
        self.assertEqual(code, 0)
        self.assertEqual(git(worktree, "branch", "--show-current").strip(), "nightly/x")
        self.assertClean(worktree)
        self.assertNotEqual(worktree_seed.main(["add", "-C", str(self.primary), str(worktree), "main"]), 0)


class SeedTests(_Repo):
    def test_an_existing_worktree_is_re_shared_and_its_edits_kept(self) -> None:
        self.needs_clones()
        worktree = self.root / "wt"
        git(self.primary, "worktree", "add", "-q", "--detach", str(worktree), "main")
        self.assertEqual(cow_clone.private_bytes(worktree / "assets/figure.png"), len(ASSET))
        (worktree / "edited.txt").write_text("the worktree's own edit\n")
        result = worktree_seed.seed_worktree(worktree)
        self.assertEqual(result.cloned, 2)
        self.assertEqual(cow_clone.private_bytes(worktree / "assets/figure.png"), 0)
        self.assertEqual((worktree / "edited.txt").read_text(), "the worktree's own edit\n")
        self.assertEqual(git(worktree, "status", "--porcelain"), " M edited.txt\n")

    def test_the_primary_is_not_seeded_from_itself(self) -> None:
        self.assertIn("no other checkout", worktree_seed.seed_worktree(self.primary).note)


class ClaudeHookTests(_Repo):
    """`.claude/settings.json` runs `claude-worktree-create` as Claude Code's `WorktreeCreate` hook."""

    def run_hook(self, payload: dict, cwd: Path) -> subprocess.CompletedProcess:
        environment = {k: v for k, v in os.environ.items() if k != "CLAUDE_PROJECT_DIR"}
        return subprocess.run([sys.executable, str(Path(worktree_seed.__file__)), "claude-worktree-create"],
                              input=json.dumps(payload), capture_output=True, text=True, cwd=cwd,
                              env=environment, check=False)

    def test_the_hook_prints_only_the_new_worktree_path_and_branches_from_main(self) -> None:
        done = self.run_hook({"hook_event_name": "WorktreeCreate", "name": "bold-oak-a3f2",
                              "cwd": str(self.primary)}, self.primary)
        self.assertEqual(done.returncode, 0, done.stderr)
        worktree = self.primary / ".claude/worktrees/bold-oak-a3f2"
        self.assertEqual(done.stdout, f"{worktree}\n")
        self.assertEqual(git(worktree, "branch", "--show-current").strip(), "claude/bold-oak-a3f2")
        self.assertEqual(git(worktree, "rev-parse", "HEAD"), git(self.primary, "rev-parse", "main"))
        self.assertClean(worktree)

    def test_a_hook_run_from_inside_a_worktree_still_nests_under_the_primary(self) -> None:
        other = self.root / "other"
        git(self.primary, "worktree", "add", "-q", "--detach", str(other), "main")
        done = self.run_hook({"name": "second", "cwd": str(other)}, other)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(done.stdout.strip(), str(self.primary / ".claude/worktrees/second"))

    def test_an_existing_branch_is_checked_out_as_it_stands(self) -> None:
        git(self.primary, "branch", "claude/resumed", "main")
        (self.primary / "later.txt").write_text("after the branch\n")
        git(self.primary, "add", "later.txt")
        git(self.primary, "commit", "-qm", "later")
        done = self.run_hook({"name": "resumed", "cwd": str(self.primary)}, self.primary)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertFalse((self.primary / ".claude/worktrees/resumed/later.txt").exists())

    def test_a_name_that_is_not_a_slug_fails_the_hook_and_prints_no_path(self) -> None:
        for name in ("", "../escape", "a/b", "-flag"):
            done = self.run_hook({"name": name, "cwd": str(self.primary)}, self.primary)
            self.assertNotEqual(done.returncode, 0, name)
            self.assertEqual(done.stdout, "", name)

    def test_the_project_settings_run_this_hook(self) -> None:
        settings = json.loads((Path(__file__).resolve().parent.parent / ".claude/settings.json").read_text())
        commands = [hook["command"] for entry in settings["hooks"]["WorktreeCreate"] for hook in entry["hooks"]]
        self.assertEqual(commands, ['python3 "$CLAUDE_PROJECT_DIR/tools/worktree_seed.py" claude-worktree-create'])


if __name__ == "__main__":
    unittest.main()
