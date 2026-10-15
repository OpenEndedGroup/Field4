#!/usr/bin/env python3
"""Unit tests for the working-tree write guard.

Each case builds a throwaway Git repository, snapshots it, writes into it the way a badly-behaved
test would, and asserts the guard's verdict. The point of these is the *negative* cases: a guard
that cannot be shown to fail is not evidence of anything, and the touch-without-diff case in
particular is invisible to `git status` and would otherwise be untestable by inspection.
"""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools import check_test_tree_writes as guard


def _git(root: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=str(root), check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


class TreeWriteGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name).resolve()
        _git(self.root, "init")
        _git(self.root, "config", "user.email", "guard@example.com")
        _git(self.root, "config", "user.name", "Guard")
        (self.root / "assets").mkdir()
        (self.root / "assets" / "figure.png").write_bytes(b"figure-bytes")
        (self.root / "README.md").write_text("readme\n")
        (self.root / ".gitignore").write_text("build/\n")
        _git(self.root, "add", "-A")
        _git(self.root, "commit", "-m", "seed")
        self.addCleanup(self._tmp.cleanup)

    def snapshot(self) -> dict:
        return guard.snapshot(self.root)

    def findings(self, before: dict) -> list:
        return guard.compare(before, self.snapshot())

    def kinds(self, before: dict) -> dict:
        return {f.path: f.kind for f in self.findings(before)}

    def test_an_untouched_tree_yields_nothing(self) -> None:
        before = self.snapshot()
        self.assertEqual([], self.findings(before))

    def test_a_new_untracked_file_is_reported_by_its_own_name(self) -> None:
        before = self.snapshot()
        (self.root / "assets" / "proof").mkdir()
        (self.root / "assets" / "proof" / "sparkline.svg").write_text("<svg/>")
        # -uall, not the default: the default collapses this to `assets/proof/`, which does not
        # name the file a test wrote.
        self.assertEqual({"assets/proof/sparkline.svg": "untracked"}, self.kinds(before))

    def test_a_write_into_an_ignored_directory_is_not_a_finding(self) -> None:
        before = self.snapshot()
        (self.root / "build").mkdir()
        (self.root / "build" / "figure.png").write_bytes(b"scratch")
        self.assertEqual([], self.findings(before))

    def test_a_changed_committed_file_is_reported(self) -> None:
        before = self.snapshot()
        (self.root / "assets" / "figure.png").write_bytes(b"different-bytes")
        self.assertEqual({"assets/figure.png": "dirtied"}, self.kinds(before))

    def test_a_rewrite_with_identical_bytes_is_reported(self) -> None:
        """The half `git status` cannot see, and the reason this guard reads mtimes.

        Three of the five `public/docs/assets/vector_algebra` PNGs were rewritten on every
        `jvmTest` run before 2026-09-04 and matched their committed bytes every time, so no
        porcelain status would ever have named them.
        """
        before = self.snapshot()
        target = self.root / "assets" / "figure.png"
        original = target.read_bytes()
        os.utime(target, ns=(0, 0))  # start from an mtime the rewrite cannot coincide with
        before["tracked"]["assets/figure.png"] = "0:{}".format(len(original))
        target.write_bytes(original)
        self.assertEqual("", subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(self.root),
            check=True, stdout=subprocess.PIPE, text=True).stdout)
        self.assertEqual({"assets/figure.png": "rewritten"}, self.kinds(before))

    def test_a_rewrite_of_an_already_dirty_file_does_not_claim_identical_bytes(self) -> None:
        """`M` before and `M` after says nothing about the content, so the message must not.

        Measured on 2026-09-04 during the first full-suite run of this guard: an edit made to
        `build.gradle.kts` *while* the suite ran was reported as "rewritten with identical bytes",
        which was false — the file was already dirty, and its bytes had plainly changed.
        """
        target = self.root / "assets" / "figure.png"
        target.write_bytes(b"local edit")
        before = self.snapshot()
        target.write_bytes(b"a different local edit")
        findings = self.findings(before)
        self.assertEqual({"assets/figure.png": "rewritten-dirty"},
                         {f.path: f.kind for f in findings})
        self.assertNotIn("identical bytes", findings[0].detail)

    def test_a_deleted_committed_file_is_reported_once(self) -> None:
        before = self.snapshot()
        (self.root / "assets" / "figure.png").unlink()
        # Porcelain sees this too, so the status half claims it and the mtime half must not
        # double-report it.
        self.assertEqual({"assets/figure.png": "dirtied"}, self.kinds(before))

    def test_dirt_that_predates_the_test_run_is_not_attributed_to_it(self) -> None:
        (self.root / "README.md").write_text("a local edit in progress\n")
        (self.root / "scratch.txt").write_text("notes\n")
        before = self.snapshot()
        self.assertEqual([], self.findings(before))

    def test_a_test_reverting_a_local_edit_is_still_a_write(self) -> None:
        (self.root / "assets" / "figure.png").write_bytes(b"local edit")
        before = self.snapshot()
        (self.root / "assets" / "figure.png").write_bytes(b"figure-bytes")
        self.assertEqual({"assets/figure.png": "reverted"}, self.kinds(before))

    def test_a_submodule_going_dirty_is_not_this_trees_problem(self) -> None:
        _git(self.root, "update-index", "--add", "--cacheinfo",
             "160000,0000000000000000000000000000000000000001,vendor/lib")
        before = self.snapshot()
        self.assertEqual(["vendor/lib"], before["gitlinks"])
        self.assertNotIn("vendor/lib", before["tracked"])
        self.assertEqual([], [f for f in self.findings(before) if f.path == "vendor/lib"])


class EscapeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name).resolve()
        self.addCleanup(self._tmp.cleanup)

    def prefixes(self, **environ: str) -> dict:
        return guard._escape_prefixes(environ, self.root)

    def properties(self, **props: str) -> dict:
        return guard._escape_prefixes({}, self.root, props)

    def test_no_variables_means_no_allowance(self) -> None:
        self.assertEqual({}, self.prefixes())

    def test_a_flag_escape_allows_only_its_registered_paths(self) -> None:
        self.assertEqual({"AFIELD_DOCS_ASSETS": ("public/docs/assets",)},
                         self.prefixes(AFIELD_DOCS_ASSETS="1"))
        # The documented value, not merely "set" — the Kotlin compares against "1".
        self.assertEqual({}, self.prefixes(AFIELD_DOCS_ASSETS="true"))

    def test_a_set_escape_accepts_any_value(self) -> None:
        allowed = self.prefixes(AFIELD_CALLER_AUDIT_WRITE="yes")
        self.assertEqual(
            ("src/jvmTest/resources/afield/graphics/close_node_caller_audit.txt",),
            allowed["AFIELD_CALLER_AUDIT_WRITE"])

    def test_a_directory_escape_authorizes_the_directory_it_names(self) -> None:
        self.assertEqual({"AFIELD_PROOF_DIR": ("proofs/here",)},
                         self.prefixes(AFIELD_PROOF_DIR="proofs/here"))

    def test_a_directory_escape_outside_the_repo_authorizes_nothing_in_it(self) -> None:
        """A `/tmp` destination cannot dirty the tree, so it must not widen the allowance.

        Turning it into a `../..`-relative prefix would authorize an unrelated part of the tree.
        """
        self.assertEqual({"AFIELD_PROOF_DIR": ()}, self.prefixes(AFIELD_PROOF_DIR="/tmp/proofs"))

    def test_a_list_escape_authorizes_each_page_it_names(self) -> None:
        self.assertEqual(
            {"FIELD_CITATIONS_ANCHOR": ("public/docs/a.md", "public/docs/b.md")},
            self.prefixes(FIELD_CITATIONS_ANCHOR="public/docs/a.md,public/docs/b.md"))

    def test_a_system_property_escape_is_recognized_and_labelled_as_one(self) -> None:
        """`-Dafield.*` reaches the test JVM but not this process, so Gradle forwards it."""
        allowed = self.properties(**{"afield.rectsl.headingfigure": "write"})
        self.assertEqual(
            {"-Dafield.rectsl.headingfigure":
                ("public/docs/assets/rectsl/rectsl-heading-lowering.png",)},
            allowed)

    def test_a_system_property_is_not_read_from_the_environment(self) -> None:
        """Otherwise an unrelated exported variable could authorize a write."""
        self.assertEqual({}, self.prefixes(**{"afield.rectsl.headingfigure": "write"}))

    def test_the_general_escape_still_has_to_name_its_paths(self) -> None:
        self.assertEqual(
            {"AFIELD_ALLOW_TREE_WRITES": ("public/docs/assets/one.png", "dev_docs/two.md")},
            self.prefixes(AFIELD_ALLOW_TREE_WRITES="public/docs/assets/one.png,dev_docs/two.md"))

    def test_every_registered_escape_has_a_note_and_a_known_kind(self) -> None:
        for escape in guard.ESCAPES:
            self.assertIn(escape.kind, {"flag", "set", "dir", "list"}, escape.name)
            self.assertIn(escape.source, {"env", "prop"}, escape.name)
            self.assertTrue(escape.note, "{} has no note to print".format(escape.name))
            if escape.kind in {"flag", "set"}:
                self.assertTrue(escape.paths, "{} authorizes nothing".format(escape.name))

    def test_coverage_is_by_path_component_not_by_string_prefix(self) -> None:
        self.assertTrue(guard._covered("public/docs/assets/x.png", ("public/docs/assets",)))
        self.assertTrue(guard._covered("public/docs/assets", ("public/docs/assets",)))
        self.assertFalse(guard._covered("public/docs/assets-other/x.png", ("public/docs/assets",)))


class CliTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name).resolve()
        _git(self.root, "init")
        _git(self.root, "config", "user.email", "guard@example.com")
        _git(self.root, "config", "user.name", "Guard")
        (self.root / "kept.txt").write_text("kept\n")
        _git(self.root, "add", "-A")
        _git(self.root, "commit", "-m", "seed")
        self.baseline = self.root / "build" / "baseline.json"
        self.addCleanup(self._tmp.cleanup)

    def run_guard(self, *args: str) -> int:
        return guard.main(["--root", str(self.root), *args])

    def test_snapshot_then_check_passes_on_a_quiet_tree(self) -> None:
        self.assertEqual(0, self.run_guard("--snapshot", str(self.baseline)))
        self.assertEqual(0, self.run_guard("--check", str(self.baseline)))

    def test_check_fails_on_a_write_and_the_escape_excuses_it(self) -> None:
        self.assertEqual(0, self.run_guard("--snapshot", str(self.baseline)))
        (self.root / "public").mkdir()
        (self.root / "public" / "docs").mkdir()
        (self.root / "public" / "docs" / "assets").mkdir()
        (self.root / "public" / "docs" / "assets" / "f.png").write_bytes(b"x")
        self.assertEqual(1, self.run_guard("--check", str(self.baseline)))
        os.environ["AFIELD_DOCS_ASSETS"] = "1"
        self.addCleanup(os.environ.pop, "AFIELD_DOCS_ASSETS", None)
        self.assertEqual(0, self.run_guard("--check", str(self.baseline)))

    def test_a_missing_baseline_fails_rather_than_passing_vacuously(self) -> None:
        """The wiring failure has to be loud.

        A check that reports success because it had nothing to compare against is the exact shape
        of guard this repository has been bitten by before — an UP-TO-DATE gate whose green is a
        cached verdict about a file it never read.
        """
        self.assertEqual(1, self.run_guard("--check", str(self.baseline)))

    def test_the_guard_never_reports_its_own_baseline_file(self) -> None:
        """This repository ignores `build/`, so the baseline is invisible here in practice.

        The test repo deliberately has no .gitignore, which is the only way to see that the
        exclusion is real rather than inherited from the caller's ignore rules.
        """
        self.assertEqual(0, self.run_guard("--snapshot", str(self.baseline)))
        self.assertTrue(self.baseline.is_file())
        self.assertEqual(0, self.run_guard("--check", str(self.baseline)))

    def test_a_build_that_ran_no_test_task_says_so_instead_of_passing(self) -> None:
        """A green here would be a verdict about a run that never happened.

        `jvmTest` going UP-TO-DATE is the ordinary way this arises, and it is genuinely not a
        failure — but it is not evidence either, and the output has to be readable as such.
        """
        ran = self.root / "build" / "ran"
        self.assertEqual(0, self.run_guard("--snapshot", str(self.baseline), "--ran-dir", str(ran)))
        (self.root / "leftover.txt").write_text("a test wrote this\n")
        self.assertEqual(0, self.run_guard("--check", str(self.baseline), "--ran-dir", str(ran)))
        # With a marker present the same write is a failure, named by the task that made it.
        ran.mkdir(parents=True, exist_ok=True)
        (ran / "jvmTest").write_text("")
        self.assertEqual(1, self.run_guard("--check", str(self.baseline), "--ran-dir", str(ran)))

    def test_a_stale_marker_cannot_survive_into_the_next_build(self) -> None:
        ran = self.root / "build" / "ran"
        ran.mkdir(parents=True)
        (ran / "jvmTest").write_text("")
        self.assertEqual(0, self.run_guard("--snapshot", str(self.baseline), "--ran-dir", str(ran)))
        self.assertEqual([], guard.ran_labels(str(ran), self.root))

    def test_a_baseline_from_an_older_format_fails_rather_than_guessing(self) -> None:
        self.baseline.parent.mkdir(parents=True, exist_ok=True)
        self.baseline.write_text(json.dumps({"version": 0}))
        self.assertEqual(1, self.run_guard("--check", str(self.baseline)))


class WiringTest(unittest.TestCase):
    """Pins the build-graph edges that make this guard run at all.

    Here rather than in a Kotlin test on purpose. `jvmTest` does not declare `build.gradle.kts`
    as an input, so a build-file-only edit leaves it UP-TO-DATE and any wiring assertion inside it
    would "pass" without running — the failure mode this repository has been bitten by more than
    once. `checkPythonTools` is an `Exec` with no declared inputs, so it always executes.
    """

    def setUp(self) -> None:
        self.build = (Path(guard.__file__).resolve().parent.parent / "build.gradle.kts").read_text()

    def test_every_test_task_records_a_baseline_first(self) -> None:
        self.assertIn("dependsOn(recordTestTreeBaseline)", self.build)

    def test_every_test_task_is_finalized_by_the_check(self) -> None:
        # `finalizedBy` and not `dependsOn` from `check`: a failing test must not be able to skip
        # the guard, and the command people type is `./gradlew jvmTest`.
        self.assertIn("finalizedBy(checkTestTreeWrites)", self.build)

    def test_the_hookup_covers_all_test_tasks_and_not_one_by_name(self) -> None:
        self.assertRegex(
            self.build,
            r"tasks\.withType<org\.gradle\.api\.tasks\.testing\.Test>\(\)\.configureEach \{"
            r"[\s\S]{0,900}?finalizedBy\(checkTestTreeWrites\)",
        )

    def test_both_tasks_pass_the_ran_marker_directory(self) -> None:
        """Without it the check cannot tell "the tests wrote nothing" from "no test ran"."""
        self.assertEqual(2, self.build.count('"--ran-dir", testTreeWriteRanDir'))


if __name__ == "__main__":
    unittest.main()
