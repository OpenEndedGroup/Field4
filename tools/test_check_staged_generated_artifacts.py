import unittest

from tools.check_staged_generated_artifacts import (
    conflicting_unstaged_paths,
    guards_for_paths,
)


def task_names(paths: list[str]) -> list[str]:
    return [guard.task for guard in guards_for_paths(paths)]


class GeneratedArtifactSelectionTests(unittest.TestCase):
    def test_stdlib_change_selects_reference_and_kdoc_guards(self) -> None:
        self.assertEqual(
            task_names(["src/jvmMain/kotlin/afield/stdlib/Controls.kt"]),
            ["checkCodebaseReference", "checkKDocAttachment"],
        )

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


if __name__ == "__main__":
    unittest.main()
