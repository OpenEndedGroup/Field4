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

    def test_a_new_demo_selects_the_demo_registration_guards(self) -> None:
        # 2026-10-29, branches 2 and 3: each added a demo directory registered nowhere, passed precheck,
        # and bounced on test_demo_build_scripts.py CORPUS. Positive control: this diff is that one.
        change = Change({
            "dev_docs/agents/demos/controls_surface/metadata.edn": "A",
            "dev_docs/agents/demos/controls_surface/build.yaml": "A",
        })
        selected = dict((check.key, why) for check, why in precheck.select(change))
        self.assertIn("demo-corpus", selected)
        self.assertIn("python-tools", selected)
        self.assertEqual(selected["demo-corpus"], sorted(change.paths))
        demo = next(c for c in precheck.CHECKS if c.key == "demo-corpus")
        args = precheck.gradle_arguments([demo], Path("/tmp/ref"))
        self.assertIn("checkAgentDemos", args)
        self.assertIn("checkAgentTranscriptLayouts", args)
        tail = args[args.index("jvmTest"):]
        self.assertEqual(tail, ["jvmTest", "--rerun", "--tests", "afield.tools.docs.AgentDemoRegistrationTest"])

    def test_a_demo_outside_the_corpus_selects_no_demo_guard(self) -> None:
        # Negative control: a transcript is not a demo.
        self.assertNotIn("demo-corpus", keys(Change({"dev_docs/agents/transcripts/x.yaml": "A"})))
        self.assertIn("transcript-index", keys(Change({"dev_docs/agents/transcripts/x.yaml": "A"})))

    def test_a_new_transcript_selects_the_registration_check(self) -> None:
        # 2026-11-05, gpu_physarum: the branch added the yaml and nothing else (positive control).
        change = Change({"dev_docs/agents/transcripts/gpu_physarum.yaml": "A",
                         "dev_docs/agents/transcripts/gpu_physarum/physarum_slime_code_.kt": "A"})
        selected = dict((check.key, why) for check, why in precheck.select(change))
        self.assertEqual(["dev_docs/agents/transcripts/gpu_physarum.yaml"], selected["transcript-registration"])
        check = next(c for c in precheck.CHECKS if c.key == "transcript-registration")
        self.assertEqual(("tools/check_transcript_registration.py",), check.python)
        # Negative control: a docs_capture variant is not a starting point.
        self.assertNotIn("transcript-registration",
                         keys(Change({"dev_docs/agents/transcripts/docs_capture/x.yaml": "A"})))

    def test_a_docs_figure_selects_the_asset_budget(self) -> None:
        # 6291b6ff9: public/docs/assets/alife_examples/physarum-gpu.png at 2.6 MB.
        figure = "public/docs/assets/alife_examples/physarum-gpu.png"
        selected = dict((check.key, why) for check, why in precheck.select(Change({figure: "A"})))
        self.assertEqual([figure], selected["doc-asset-budget"])
        self.assertIn("doc-asset-budget", keys(Change({"public/docs/assets/x/clip.mp4": "M"})))
        self.assertNotIn("doc-asset-budget", keys(Change({"public/docs/alife_examples.md": "M"})))

    def test_a_docs_document_sidecar_selects_the_inline_copy_guard(self) -> None:
        sidecar = "public/docs/assets/canonical_graphics/0e01f4b0_code_.kt"
        self.assertIn("saved-document-inlines", keys(Change({sidecar: "M"})))
        self.assertIn("saved-document-inlines", keys(Change({"templates/first-steps/metadata.edn": "M"})))
        # Negative controls: a figure beside the documents, and a generated transcript document.
        self.assertNotIn("saved-document-inlines", keys(Change({"public/docs/assets/afield_four_graphics/04c.png": "M"})))
        self.assertNotIn("saved-document-inlines",
                         keys(Change({"public/docs/assets/agent_transcripts/x/metadata.edn": "M"})))

    def test_build_gradle_selects_the_python_suites_as_the_lander_does(self) -> None:
        self.assertIn("python-tools", keys(Change({"build.gradle.kts": "M"})))

    def test_every_lander_guard_is_prechecked_or_exempted(self) -> None:
        # The family of gap behind N1029-1: a guard the lander runs that no precheck path selects.
        from check_staged_generated_artifacts import GUARDS

        lander = {guard.task for guard in GUARDS}
        covered = {task for check in precheck.CHECKS for task in check.lander_guards}
        exempt = set(precheck.NOT_PRECHECKED)
        self.assertEqual(set(), lander - covered - exempt, "lander guards no precheck check stands in for")
        self.assertEqual(set(), (covered | exempt) - lander, "names that are not lander guards")
        self.assertEqual(set(), covered & exempt)

    def test_each_check_selects_at_least_what_its_lander_guard_selects(self) -> None:
        # For every lander guard a check stands in for wholly (not citationGates, which precheck runs a
        # few classes of), a path that selects the guard selects the check. Sample paths, one per guard.
        from check_staged_generated_artifacts import GUARDS

        samples = [
            "src/jvmMain/kotlin/afield/math/X.kt",
            "dev_docs/agents/demos/new_demo/metadata.edn",
            "dev_docs/agents/transcripts/new.yaml",
            "public/docs/agent_transcripts.md",
            "public/docs/some_page.md",
            "public/docs/assets/web_publications/catalog.json",
            "tools/land.py",
            "build.gradle.kts",
            "public/docs/reference/math.md",
            "templates/WORKSPACE_AGENTS.md",
        ]
        by_task = {guard.task: guard for guard in GUARDS}
        for check in precheck.CHECKS:
            for task in check.lander_guards:
                if task == "citationGates":
                    continue
                for path in samples:
                    if by_task[task].is_relevant(path):
                        self.assertTrue(check.selects(Change({path: "A"})),
                                        f"{path} selects the lander's {task} but not precheck's {check.key}")

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



class DuplicatePatchTest(unittest.TestCase):
    """N1029-2: a cherry-pick whose patch already landed on main under another sha."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)

        def git(*args: str) -> str:
            return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

        self.git = git
        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        (self.repo / "harness.md").write_text("one\n")
        git("add", ".")
        git("commit", "-qm", "base")
        git("checkout", "-qb", "topic")
        (self.repo / "mine.md").write_text("mine\n")
        git("add", ".")
        git("commit", "-qm", "my own work")
        git("checkout", "-q", "main")
        (self.repo / "harness.md").write_text("one\ncommand tool\n")
        git("commit", "-qam", "command tool")
        self.landed = git("rev-parse", "HEAD")
        git("checkout", "-q", "topic")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def test_a_cherry_pick_of_a_landed_commit_is_named_with_its_twin(self) -> None:
        # Positive control: the 2026-10-29 shape (8b68b8187 cherry-picked, 23018e9b6 on main).
        self.git("cherry-pick", self.landed)
        pick = self.git("rev-parse", "HEAD")
        self.assertNotEqual(pick, self.landed)
        duplicates = precheck.duplicate_patches(self.repo, "main")
        self.assertEqual([(d.commit, d.on_base) for d in duplicates], [(pick, self.landed)])
        text = precheck.describe_duplicates(duplicates)
        self.assertIn(pick[:9], text)
        self.assertIn(self.landed[:9], text)
        self.assertIn(f"git rebase --onto {pick[:9]}^ {pick[:9]}", text)

    def test_the_suggested_rebase_drops_exactly_the_duplicate(self) -> None:
        self.git("cherry-pick", self.landed)
        pick = self.git("rev-parse", "HEAD")
        self.git("rebase", "-q", "--onto", f"{pick}^", pick)
        self.assertEqual([], precheck.duplicate_patches(self.repo, "main"))
        self.assertEqual("my own work", self.git("log", "-1", "--format=%s"))

    def test_distinct_work_is_not_a_duplicate(self) -> None:
        # Negative control: the same file touched differently.
        (self.repo / "harness.md").write_text("one\nsomething else\n")
        self.git("commit", "-qam", "other")
        self.assertEqual([], precheck.duplicate_patches(self.repo, "main"))

    def test_after_merging_main_the_duplicate_is_no_longer_reported(self) -> None:
        # Once main is merged in, the twin is an ancestor; nothing is left to conflict.
        self.git("cherry-pick", self.landed)
        self.git("merge", "-q", "--no-edit", "main")
        self.assertEqual([], precheck.duplicate_patches(self.repo, "main"))

    def test_precheck_refuses_a_duplicate_before_merging(self) -> None:
        self.git("cherry-pick", self.landed)
        saved, cwd = precheck.REPO, Path.cwd()
        try:
            os.chdir(self.repo)
            with patch.object(precheck, "_say") as say:
                code = precheck.run(argparse.Namespace(base="main", no_merge=False, plan=True, keep_merge=False))
        finally:
            os.chdir(cwd)
            precheck.REPO = saved
        self.assertEqual(1, code)
        self.assertIn("already on main as " + self.landed[:9], say.call_args_list[0].args[0])


if __name__ == "__main__":
    unittest.main()
