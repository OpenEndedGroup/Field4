"""tools/check_transcript_registration.py: a new transcript registered nowhere is named in every register.

Positive control: the gpu_physarum shape of 2026-11-05 (444938ec9, 1269001df) — a yaml with no
index heading, no smoke classification and no bundle — in a scratch tree. Negative control: the
committed tree is clean.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_check_transcript_registration
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_transcript_registration as ctr  # noqa: E402

SMOKE = '''object DemoTranscriptExecSmokeTest {
    internal val EXECUTE = setOf(
        // a comment with a "quoted_word" and (parens)
        "known_cpu",
    )
    internal val COMPILE_ONLY = setOf(
        "known_gpu", /* "commented_out" */
    )
    internal val KOTLINJS = setOf("known_js")
}
'''

MATERIALIZER = '''object AgentTranscriptMaterializer {
    internal val EXCLUDE = setOf(
        "known_js",
    )
}
'''


def scratch(root: Path, transcripts: list[str], headings: list[str], bundles: list[str]) -> None:
    (root / ctr.TRANSCRIPTS).mkdir(parents=True)
    for name in transcripts:
        (root / ctr.TRANSCRIPTS / f"{name}.yaml").write_text("operations: []\n")
    (root / ctr.INDEX).parent.mkdir(parents=True)
    (root / ctr.INDEX).write_text("".join(f"### {h}\n\ntext\n\n" for h in headings))
    for path, text in ((ctr.SMOKE, SMOKE), (ctr.MATERIALIZER, MATERIALIZER)):
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text(text)
    for name in bundles:
        (root / ctr.BUNDLES / name).mkdir(parents=True)
        (root / ctr.BUNDLES / name / "metadata.edn").write_text("{}")


class RegistrationTest(unittest.TestCase):
    def test_kotlin_set_reader_skips_comments(self) -> None:
        self.assertEqual({"known_cpu"}, ctr.kotlin_string_set(SMOKE, "EXECUTE"))
        self.assertEqual({"known_gpu"}, ctr.kotlin_string_set(SMOKE, "COMPILE_ONLY"))
        self.assertEqual({"known_js"}, ctr.kotlin_string_set(SMOKE, "KOTLINJS"))
        self.assertIsNone(ctr.kotlin_string_set(SMOKE, "MISSING"))

    def test_registered_scratch_corpus_is_clean(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            known = ["known_cpu", "known_gpu", "known_js"]
            scratch(root, known, known + ["saved_document_only"], ["known_cpu", "known_gpu"])
            report = ctr.check(root, corpora=False)
            self.assertEqual([], report.failures)
            self.assertEqual([], report.reminders)

    def test_an_unregistered_transcript_is_named_in_every_register(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            known = ["known_cpu", "known_gpu", "known_js"]
            scratch(root, known + ["gpu_physarum"], known, ["known_cpu", "known_gpu"])
            report = ctr.check(root, corpora=False)
            self.assertFalse(report.ok)
            text = "\n".join(report.failures)
            self.assertIn("### gpu_physarum", text)
            self.assertIn('"gpu_physarum",', text)
            self.assertIn("COMPILE_ONLY", text)
            self.assertEqual(2, len(report.failures), report.failures)
            self.assertEqual(1, len(report.reminders))
            self.assertIn("regenerateAgentTranscriptStartingPoints", report.reminders[0])
            strict = ctr.check(root, corpora=False, strict_bundles=True)
            self.assertEqual(3, len(strict.failures))

    def test_a_classification_naming_no_transcript_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scratch(root, ["known_cpu", "known_js"], ["known_cpu", "known_js"], ["known_cpu"])
            report = ctr.check(root, corpora=False)
            self.assertEqual(1, len(report.failures), report.failures)
            self.assertIn("known_gpu: COMPILE_ONLY", report.failures[0])

    def test_the_committed_tree_is_clean(self) -> None:
        # Negative control. Corpus counts are left out: the lander regenerates them, and a branch
        # behind main legitimately carries a stale block.
        report = ctr.check(corpora=False)
        self.assertEqual([], report.failures)
        self.assertGreater(len(report.transcripts), 200)


if __name__ == "__main__":
    unittest.main()
