"""tools/land_precheck.py: each 2026-10-20 bounce selects the check that would have caught it."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import land_precheck as precheck  # noqa: E402
from land_precheck import Change  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
