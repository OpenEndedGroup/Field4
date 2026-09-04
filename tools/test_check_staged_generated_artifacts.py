import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.check_staged_generated_artifacts import (
    conflicting_unstaged_paths,
    guards_for_paths,
)

REPO = Path(__file__).resolve().parents[1]


def task_names(paths: list[str]) -> list[str]:
    return [guard.task for guard in guards_for_paths(paths)]


class GeneratedArtifactSelectionTests(unittest.TestCase):
    def test_stdlib_change_selects_reference_and_kdoc_guards(self) -> None:
        self.assertEqual(
            task_names(["src/jvmMain/kotlin/afield/stdlib/Controls.kt"]),
            ["checkCodebaseReference", "checkKDocAttachment", "citationGates"],
        )

    def test_shader_macro_change_also_selects_completion_corpus_guard(self) -> None:
        self.assertEqual(
            task_names(["src/jvmMain/kotlin/afield/stdlib/shaders.kt"]),
            [
                "checkCodebaseReference",
                "checkKDocAttachment",
                "checkCompletionCorpusFixtures",
                "citationGates",
            ],
        )

    def test_macro_producer_is_discovered_from_its_registration(self) -> None:
        self.assertEqual(
            task_names(["src/jvmMain/kotlin/afield/four/modules/simplePLYSupport.kt"]),
            ["checkKDocAttachment", "checkCompletionCorpusFixtures", "citationGates"],
        )

    def test_ordinary_kotlin_source_does_not_become_a_corpus_input(self) -> None:
        self.assertEqual(
            task_names(["src/jvmMain/kotlin/afield/graphics/FLine.kt"]),
            ["checkKDocAttachment", "citationGates"],
        )

    def test_source_cited_by_close_node_contract_selects_citation_gate(self) -> None:
        self.assertEqual(
            task_names(["src/jvmMain/kotlin/afield/graphics/someSkiko.kt"]),
            ["checkKDocAttachment", "citationGates"],
        )

    def test_cited_frontend_resource_is_discovered_from_the_pages(self) -> None:
        self.assertEqual(
            task_names(["src/commonMain/resources/fieldSuggest.js"]),
            ["citationGates"],
        )

    def test_guarded_citation_page_selects_citation_gate(self) -> None:
        self.assertEqual(
            task_names(["dev_docs/small_language_anatomy.md"]),
            ["citationGates"],
        )

    def test_docs_fence_implementation_selects_citation_gate(self) -> None:
        self.assertEqual(task_names(["public/docs_hooks.py"]), ["citationGates"])

    def test_citation_task_wiring_selects_citation_gate(self) -> None:
        self.assertEqual(task_names(["build.gradle.kts"]), ["citationGates"])

    def test_transcript_change_selects_bundle_and_index(self) -> None:
        self.assertEqual(
            task_names(["dev_docs/agents/transcripts/hello.yaml"]),
            ["checkAgentTranscripts", "checkAgentTranscriptIndex"],
        )

    def test_materialized_transcript_also_selects_completion_corpus(self) -> None:
        self.assertEqual(
            task_names(["public/docs/assets/agent_transcripts/hello/metadata.edn"]),
            [
                "checkAgentTranscripts",
                "checkAgentTranscriptIndex",
                "checkCompletionCorpusFixtures",
            ],
        )

    def test_template_change_selects_completion_corpus_only(self) -> None:
        self.assertEqual(
            task_names(["templates/hello/metadata.edn"]),
            ["checkCompletionCorpusFixtures"],
        )

    def test_unrelated_change_selects_nothing(self) -> None:
        self.assertEqual(task_names(["README.md"]), [])

    def test_relevant_unstaged_output_blocks_worktree_based_check(self) -> None:
        guards = guards_for_paths(["src/jvmMain/kotlin/afield/stdlib/Controls.kt"])
        self.assertEqual(
            conflicting_unstaged_paths(
                guards,
                ["public/docs/reference/controls.md", "README.md"],
            ),
            ["public/docs/reference/controls.md"],
        )


class MergeSelectionTests(unittest.TestCase):
    """The ``--since`` path, which exists for work that arrives by pull rather than by commit."""

    def test_the_2026_08_13_regression_selects_both_guards_that_went_red(self) -> None:
        # 090279a36 "Bridge Field audio renders into analysis" added a stdlib source file and an
        # agent transcript, regenerated neither artifact, and turned two gates red on main for
        # nearly a day. These are the paths it touched; both guards must be selected from them.
        self.assertEqual(
            task_names(
                [
                    "src/jvmMain/kotlin/afield/stdlib/noir/audioai/RenderedAudioAnalysis.kt",
                    "public/docs/assets/agent_transcripts/audio_render_analysis/metadata.edn",
                ]
            ),
            [
                "checkCodebaseReference",
                "checkKDocAttachment",
                "checkAgentTranscripts",
                "checkAgentTranscriptIndex",
                "checkCompletionCorpusFixtures",
                "citationGates",
            ],
        )


class HookInstallTests(unittest.TestCase):
    """Installing must not discard Git LFS's hooks, which already own ``post-merge`` here."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        subprocess.run(["git", "init", "--quiet", str(self.tmp)], check=True)
        (self.tmp / "tools/hooks").mkdir(parents=True)
        for name in ("pre-commit", "post-merge"):
            shutil.copyfile(REPO / "tools/hooks" / name, self.tmp / "tools/hooks" / name)
        shutil.copyfile(
            REPO / "tools/install_git_hooks.py", self.tmp / "tools/install_git_hooks.py"
        )
        (self.tmp / "tools/check_staged_generated_artifacts.py").write_text("# stub\n")

    def _install(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", "tools/install_git_hooks.py"],
            cwd=self.tmp,
            capture_output=True,
            text=True,
        )

    def _hooks(self) -> Path:
        return self.tmp / ".git/hooks"

    def test_installs_both_hooks(self) -> None:
        self.assertEqual(self._install().returncode, 0)
        for name in ("pre-commit", "post-merge"):
            hook = self._hooks() / name
            self.assertTrue(hook.exists(), name)
            self.assertTrue(os.access(hook, os.X_OK), f"{name} not executable")

    def test_foreign_hook_is_preserved_and_chained_not_discarded(self) -> None:
        lfs = self._hooks() / "post-merge"
        lfs.parent.mkdir(parents=True, exist_ok=True)
        lfs.write_text("#!/bin/sh\ngit lfs post-merge \"$@\"\n")
        os.chmod(lfs, 0o755)

        self.assertEqual(self._install().returncode, 0)

        preserved = self._hooks() / "post-merge.local"
        self.assertTrue(preserved.exists(), "LFS hook was discarded")
        self.assertIn("git lfs post-merge", preserved.read_text())
        self.assertIn("post-merge.local", (self._hooks() / "post-merge").read_text())

    def test_reinstalling_is_idempotent_and_does_not_chain_to_itself(self) -> None:
        lfs = self._hooks() / "post-merge"
        lfs.parent.mkdir(parents=True, exist_ok=True)
        lfs.write_text("#!/bin/sh\ngit lfs post-merge \"$@\"\n")
        os.chmod(lfs, 0o755)

        self.assertEqual(self._install().returncode, 0)
        self.assertEqual(self._install().returncode, 0)

        preserved = (self._hooks() / "post-merge.local").read_text()
        self.assertIn("git lfs post-merge", preserved)
        self.assertNotIn("check_staged_generated_artifacts", preserved)

    def test_refuses_when_preserving_would_clobber_a_different_saved_hook(self) -> None:
        hooks = self._hooks()
        hooks.mkdir(parents=True, exist_ok=True)
        (hooks / "post-merge").write_text("#!/bin/sh\necho one\n")
        (hooks / "post-merge.local").write_text("#!/bin/sh\necho something else\n")

        result = self._install()

        self.assertEqual(result.returncode, 1)
        self.assertIn("Resolve by hand", result.stderr)
        self.assertEqual((hooks / "post-merge.local").read_text(), "#!/bin/sh\necho something else\n")


class PostMergeHookFiresTests(unittest.TestCase):
    """A real ``git merge``, to prove the ORIG_HEAD wiring and the LFS chain actually work."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.env = {
            **os.environ,
            "GIT_AUTHOR_NAME": "t",
            "GIT_AUTHOR_EMAIL": "t@t",
            "GIT_COMMITTER_NAME": "t",
            "GIT_COMMITTER_EMAIL": "t@t",
        }
        self._git("init", "--quiet", "--initial-branch=main", ".")
        (self.tmp / "tools/hooks").mkdir(parents=True)
        shutil.copyfile(REPO / "tools/hooks/post-merge", self.tmp / "tools/hooks/post-merge")

        # A stub standing in for the real guard: records the arguments it was handed, so the test
        # asserts on the wiring rather than on Gradle.
        (self.tmp / "tools/check_staged_generated_artifacts.py").write_text(
            "import sys, pathlib\n"
            "pathlib.Path('guard-ran.txt').write_text(' '.join(sys.argv[1:]))\n"
        )
        (self.tmp / "seed.txt").write_text("seed\n")
        self._git("add", "-A")
        self._git("commit", "--quiet", "-m", "seed")

        hooks = self.tmp / ".git/hooks"
        hooks.mkdir(parents=True, exist_ok=True)
        (hooks / "post-merge.local").write_text(
            "#!/bin/sh\necho chained >> lfs-ran.txt\n"
        )
        os.chmod(hooks / "post-merge.local", 0o755)
        shutil.copyfile(REPO / "tools/hooks/post-merge", hooks / "post-merge")
        os.chmod(hooks / "post-merge", 0o755)

    def _git(self, *args: str) -> str:
        return subprocess.run(
            ["git", *args], cwd=self.tmp, check=True, capture_output=True, text=True, env=self.env
        ).stdout

    def _merge_a_side_branch(self) -> None:
        self._git("checkout", "--quiet", "-b", "side")
        (self.tmp / "brought-in.txt").write_text("from another clone\n")
        self._git("add", "-A")
        self._git("commit", "--quiet", "-m", "work authored elsewhere")
        self._git("checkout", "--quiet", "main")
        self._git("merge", "--quiet", "--no-ff", "-m", "merge side", "side")

    def test_merge_runs_the_guard_against_orig_head_and_still_runs_lfs(self) -> None:
        self._merge_a_side_branch()

        ran = self.tmp / "guard-ran.txt"
        self.assertTrue(ran.exists(), "post-merge hook did not run the guard")
        self.assertEqual(ran.read_text(), "--since ORIG_HEAD")
        self.assertTrue((self.tmp / "lfs-ran.txt").exists(), "chained LFS hook did not run")

    def test_skip_env_var_disables_the_guard_but_not_the_chained_hook(self) -> None:
        self.env["FIELD_SKIP_MERGE_GUARD"] = "1"

        self._merge_a_side_branch()

        self.assertFalse((self.tmp / "guard-ran.txt").exists(), "guard ran despite the opt-out")
        self.assertTrue((self.tmp / "lfs-ran.txt").exists(), "opt-out wrongly skipped LFS too")


if __name__ == "__main__":
    unittest.main()
