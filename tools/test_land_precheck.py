"""tools/land_precheck.py: each 2026-10-20 bounce selects the check that would have caught it."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import land_precheck as precheck  # noqa: E402
from land_precheck import Change  # noqa: E402
from tools.git_isolation import scrub_process_env  # noqa: E402

# This module builds scratch git repositories; an inherited GIT_DIR would redirect them.
scrub_process_env()


def keys(change: Change) -> set[str]:
    return {check.key for check, _ in precheck.select(change)}


class SelectionTest(unittest.TestCase):
    """The diffs below are cut down from the bounced commits named in each test."""

    def test_webrtc_route_and_ws_commands_select_the_server_ledger(self) -> None:
        # claude/webrtc-sources-sinks ecc10abbbb: three ledger reds, KDoc, a box that did not compile,
        # and a bare send( in a module script.
        change = Change(
            {
                "src/jvmMain/kotlin/afield/media/webrtc/WebRTC.kt": "A",
                "src/commonMain/resources/webrtc-signal.js": "A",
                "dev_docs/agents/transcripts/webrtc_shader_jam.yaml": "A",
                "public/docs/webrtc.md": "A",
            },
            {"src/jvmMain/kotlin/afield/media/webrtc/WebRTC.kt": '    handlers["webrtc-offer"] = { m ->\n    get("/webrtc/{name}") {'},
        )
        self.assertTrue(
            {"compile", "kdoc-attachment", "server-surface", "documented-api", "box-code", "js-module-imports"}
            <= keys(change)
        )

    def test_kotlin_that_adds_no_route_does_not_select_the_server_ledger(self) -> None:
        change = Change({"src/jvmMain/kotlin/afield/math/Thing.kt": "M"},
                        {"src/jvmMain/kotlin/afield/math/Thing.kt": 'val x = json.put("abs", 1)\nfun get(i: Int) = 3'})
        self.assertNotIn("server-surface", keys(change))
        self.assertIn("kdoc-attachment", keys(change))

    def test_fern_gallery_entry_selects_the_documented_api_ledger(self) -> None:
        # claude/madp-fern b2db9f4802: the transcript index named GLSL functions.
        change = Change({"dev_docs/agents/transcripts/fern_iterated_systems.yaml": "A",
                         "public/docs/agent_transcripts.md": "M"})
        self.assertIn("documented-api", keys(change))
        self.assertIn("box-code", keys(change))

    def test_a_new_evaluator_selects_the_documented_api_ledger_backwards(self) -> None:
        # claude/madp-splat e66caba056: a new jvmTest made the `modelView` row stale.
        self.assertIn("documented-api", keys(Change({"src/jvmTest/kotlin/afield/four/SplatTest.kt": "A"})))
        smoke = "src/jvmMain/kotlin/afield/examples/SplatSmoke.kt"
        self.assertIn("documented-api", keys(Change({smoke: "A"}, {smoke: "/* FIELD_TEST\n id: splat */"})))

    def test_a_new_source_file_under_a_reference_root_selects_the_nav_check(self) -> None:
        # claude/madp-score 09cd698f67 opened animation.md; claude/madp-geometry opened geometry.md.
        added = Change({"src/jvmMain/kotlin/afield/stdlib/Score.kt": "A"})
        self.assertIn("reference-nav", keys(added))
        edited = Change({"src/jvmMain/kotlin/afield/stdlib/Score.kt": "M"})
        self.assertNotIn("reference-nav", keys(edited))

    def test_tools_python_selects_the_python_suites(self) -> None:
        self.assertIn("python-tools", keys(Change({"tools/land.py": "M"})))

    def test_a_deleted_file_selects_no_compile(self) -> None:
        self.assertEqual(set(), keys(Change({"src/jvmMain/kotlin/afield/math/Gone.kt": "D"})))

    def test_gradle_arguments_run_only_the_selected_citation_classes(self) -> None:
        checks = [c for c in precheck.CHECKS if c.key in {"compile", "server-surface", "reference-nav"}]
        args = precheck.gradle_arguments(checks, Path("/tmp/ref"))
        self.assertEqual(args[:3], ["--continue", "compileKotlinJvm", "generateCodebaseReference"])
        self.assertIn("-Pout=/tmp/ref", args)
        tail = args[args.index("citationGates"):]
        self.assertEqual(tail, ["citationGates", "--rerun", "--tests", "afield.app.server.ServerSurfaceLedgerTest"])

    def test_no_drift_check_is_ever_run(self) -> None:
        # The lander regenerates these before it guards, so they cannot bounce a branch.
        tasks = {task for check in precheck.CHECKS for task in check.gradle}
        self.assertFalse({"checkCodebaseReference", "checkAgentTranscripts"} & tasks)

    def test_missing_nav_entries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("geometry.md", "animation.md", "math.md", "kdoc_worklist.md"):
                (Path(tmp) / name).write_text("x")
            nav = "  - Reference:\n    - reference/math.md\n    - reference/geometry.md\n"
            self.assertEqual(precheck.missing_nav_entries(Path(tmp), nav), ["animation.md"])


class ConflictTest(unittest.TestCase):
    """The first check is git's, against a throwaway repository."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        self.saved = precheck.REPO
        precheck.REPO = self.repo

        def git(*args: str) -> None:
            subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)

        self.git = git
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        (self.repo / "index.md").write_text("one\n")
        git("add", ".")
        git("commit", "-qm", "base")

    def tearDown(self) -> None:
        precheck.REPO = self.saved
        self.tmp.cleanup()

    def _diverge(self, mine: str, theirs: str) -> None:
        self.git("checkout", "-qb", "topic")
        (self.repo / "index.md").write_text(mine)
        self.git("commit", "-qam", "mine")
        self.git("checkout", "-q", "main")
        (self.repo / "index.md").write_text(theirs)
        self.git("commit", "-qam", "theirs")
        self.git("checkout", "-q", "topic")

    def test_a_sibling_that_landed_first_is_a_conflict(self) -> None:
        # claude/madp-stacktrinket a6b23a0efc: agent_transcripts.md, after the siblings' entries landed.
        self._diverge("one\nstacktrinket\n", "one\nsplat\n")
        self.assertEqual(precheck.conflicts_with("main"), ["index.md"])

    def test_a_clean_merge_is_none(self) -> None:
        self._diverge("one\ntwo\n", "zero\none\n")
        self.assertIsNone(precheck.conflicts_with("main"))

    def test_the_trial_merge_is_undone(self) -> None:
        self.git("checkout", "-qb", "topic")
        (self.repo / "mine.md").write_text("m\n")
        self.git("add", "mine.md")
        self.git("commit", "-qm", "mine")
        self.git("checkout", "-q", "main")
        (self.repo / "theirs.md").write_text("t\n")
        self.git("add", "theirs.md")
        self.git("commit", "-qm", "theirs")
        self.git("checkout", "-q", "topic")
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, capture_output=True, text=True).stdout
        self.assertTrue(precheck._merge_main("main"))
        self.assertTrue((self.repo / "theirs.md").exists())
        change = precheck.read_change("main")
        self.assertEqual(change.status, {"mine.md": "A"})
        self.git("merge", "--abort")
        self.assertFalse((self.repo / "theirs.md").exists())
        after = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, capture_output=True, text=True).stdout
        self.assertEqual(head, after)

    def _inventory_merge(self, *, generator_fails: bool = False) -> None:
        (self.repo / "tools").mkdir()
        (self.repo / "dev_docs").mkdir()
        (self.repo / "tools/guard_corpora.py").write_text(
            "from pathlib import Path\n"
            "Path('dev_docs/GUARD_CORPORA.md').write_text(str(len(list(Path('tools').glob('test_*.py')))) + '\\n')\n"
            + ("raise SystemExit(1)\n" if generator_fails else "")
        )
        (self.repo / "dev_docs/GUARD_CORPORA.md").write_text("0\n")
        self.git("add", ".")
        self.git("commit", "-qm", "inventory scaffold")
        for branch, filename in [("topic", "test_topic.py"), ("main", "test_main.py")]:
            self.git("checkout", "-qb", branch) if branch == "topic" else self.git("checkout", "-q", branch)
            (self.repo / "tools" / filename).write_text("# fixture\n")
            (self.repo / "dev_docs/GUARD_CORPORA.md").write_text("1\n")
            self.git("add", ".")
            self.git("commit", "-qm", branch)
        self.git("checkout", "-q", "topic")

    def _run_inventory_precheck(self, *, keep: bool = False, result: int = 0, raises: bool = False) -> int:
        def check_inventory(script: str) -> int:
            self.assertEqual(script, "tools/run_python_tests.py")
            self.assertEqual((self.repo / "dev_docs/GUARD_CORPORA.md").read_text(), "2\n")
            if raises:
                raise RuntimeError("interrupted check")
            return result

        cwd = Path.cwd()
        try:
            os.chdir(self.repo)
            with patch.object(precheck, "_run_python", side_effect=check_inventory):
                return precheck.run(argparse.Namespace(base="main", no_merge=False, plan=False, keep_merge=keep))
        finally:
            os.chdir(cwd)

    def _assert_inventory_restored(self) -> None:
        self.assertEqual((self.repo / "dev_docs/GUARD_CORPORA.md").read_text(), "1\n")
        self.assertFalse((self.repo / "tools/test_main.py").exists())
        self.assertEqual(precheck._git("status", "--porcelain").stdout, "")

    def test_trial_merge_refreshes_inventory_and_restores_it(self) -> None:
        self._inventory_merge()
        self.assertEqual(self._run_inventory_precheck(), 0)
        self._assert_inventory_restored()

    def test_kept_merge_commits_the_refreshed_inventory(self) -> None:
        self._inventory_merge()
        self.assertEqual(self._run_inventory_precheck(keep=True), 0)
        self.assertEqual((self.repo / "dev_docs/GUARD_CORPORA.md").read_text(), "2\n")
        self.assertTrue((self.repo / "tools/test_main.py").exists())
        self.assertEqual(precheck._git("status", "--porcelain").stdout, "")

    def test_a_failed_check_restores_the_inventory(self) -> None:
        self._inventory_merge()
        self.assertEqual(self._run_inventory_precheck(result=1), 1)
        self._assert_inventory_restored()

    def test_an_interrupted_check_restores_the_inventory(self) -> None:
        self._inventory_merge()
        with self.assertRaisesRegex(RuntimeError, "interrupted check"):
            self._run_inventory_precheck(raises=True)
        self._assert_inventory_restored()

    def test_a_failed_generator_cannot_leave_a_partial_inventory(self) -> None:
        self._inventory_merge(generator_fails=True)
        self.assertEqual(self._run_inventory_precheck(), 1)
        self._assert_inventory_restored()


if __name__ == "__main__":
    unittest.main()
