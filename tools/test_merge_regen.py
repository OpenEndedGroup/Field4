"""tools/merge_regen.py in a throwaway repo: generated conflicts merge clean, source conflicts stop."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from tools.git_isolation import scrub_process_env  # noqa: E402

# This module builds scratch git repositories; an inherited GIT_DIR would redirect them.
scrub_process_env()

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
        git(self.repo, "config", "merge.field-counts.driver", COUNTS_DRIVER)
        self._commit_both("ours")
        result = self._run("other")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("notes.txt", result.stderr)
        self.assertNotIn("kdoc_worklist", result.stderr)
        self.assertEqual(self.generated.read_text(), "ours\n")


COUNTS_SCRIPT = SCRIPT.parent / "merge_derived_counts.py"
COUNTS_DRIVER = f"{sys.executable} {COUNTS_SCRIPT} %O %A %B %L %P"

LOGGING_BASE = """# Logging

There are **1019**<!--count:lprintlnChannelCallSites--> places that call `lprintln`, spelling
**238**<!--count:logChannelStrings--> distinct channel strings.

A paragraph nobody touches.

The last paragraph.
"""


class DerivedCountMergeTest(unittest.TestCase):
    """The `field-counts` driver: two branches that each moved a `<!--count:-->` numeral merge clean.

    The case it was built for, 2026-10-09: two branches each added `lprintln` calls and each bumped
    logging.md's count (1019 -> 1023, 1019 -> 1025); the text merge stopped on that line and the
    landing bounced before merge_regen could rewrite the number.
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name)
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "config", "user.email", "t@example.com")
        git(self.repo, "config", "user.name", "t")
        git(self.repo, "config", "core.hooksPath", "/dev/null")
        lines = [line for line in ATTRIBUTES.splitlines() if "filter=lfs" not in line]
        (self.repo / ".gitattributes").write_text("\n".join(lines) + "\n")
        self.page = self.repo / "public/docs/logging.md"
        self.page.parent.mkdir(parents=True)
        self._commit(LOGGING_BASE)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _commit(self, text: str) -> None:
        self.page.write_text(text)
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "edit")

    def _branches(self, ours: str, theirs: str) -> subprocess.CompletedProcess[str]:
        git(self.repo, "checkout", "-q", "-b", "other")
        self._commit(theirs)
        git(self.repo, "checkout", "-q", "main")
        self._commit(ours)
        return subprocess.run(["git", "merge", "-q", "--no-edit", "other"], cwd=self.repo,
                              capture_output=True, text=True)

    def test_both_sides_moving_a_count_merge_clean_and_keep_both_prose_edits(self) -> None:
        git(self.repo, "config", "merge.field-counts.driver", COUNTS_DRIVER)
        ours = LOGGING_BASE.replace("1019", "1023").replace("The last paragraph.", "The last paragraph, ours.")
        theirs = (LOGGING_BASE.replace("1019", "1025").replace("238", "239")
                  .replace("A paragraph nobody touches.", "A paragraph they touched."))
        result = self._branches(ours, theirs)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        merged = self.page.read_text()
        # Both moved it: ours. Only they moved it: theirs. merge_regen derives the real value after.
        self.assertIn("**1023**<!--count:lprintlnChannelCallSites-->", merged)
        self.assertIn("**239**<!--count:logChannelStrings-->", merged)
        self.assertIn("A paragraph they touched.", merged)
        self.assertIn("The last paragraph, ours.", merged)

    def test_a_prose_conflict_beside_a_count_still_stops(self) -> None:
        git(self.repo, "config", "merge.field-counts.driver", COUNTS_DRIVER)
        ours = LOGGING_BASE.replace("1019", "1023").replace("places that call", "call sites of")
        theirs = LOGGING_BASE.replace("1019", "1025").replace("places that call", "sites calling")
        result = self._branches(ours, theirs)
        self.assertNotEqual(result.returncode, 0)
        merged = self.page.read_text()
        self.assertIn("<<<<<<< ours", merged)
        self.assertIn("call sites of", merged)
        self.assertIn("sites calling", merged)

    def test_registered_command_falls_back_to_a_text_merge_without_the_script(self) -> None:
        sys.path.insert(0, str(SCRIPT.parent))
        from install_git_hooks import FIELD_COUNTS_DRIVER

        # The temp repo has no tools/merge_derived_counts.py: the state of a branch older than it.
        git(self.repo, "config", "merge.field-counts.driver", FIELD_COUNTS_DRIVER)
        result = self._branches(LOGGING_BASE.replace("1019", "1023"), LOGGING_BASE.replace("1019", "1025"))
        self.assertNotEqual(result.returncode, 0)
        merged = self.page.read_text()
        # A real conflict with both sides in it, never a silent "ours".
        self.assertIn("<<<<<<< ours", merged)
        self.assertIn("1023", merged)
        self.assertIn("1025", merged)


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
