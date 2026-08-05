import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

from docs_hooks import is_workspace_guide, rewrite_guide_fences  # noqa: E402


class WorkspaceGuideDetectionTests(unittest.TestCase):
    def test_only_guides_inside_the_asset_tree_are_rewritten(self):
        root = Path("/repo/public/docs")
        self.assertTrue(
            is_workspace_guide(root / "assets/agent_transcripts/alife_worked_example/AGENTS.md")
        )
        self.assertTrue(is_workspace_guide(root / "assets/rectsl_gallery/README.md"))
        # A hand-written docs page keeps whatever fences its author wrote.
        self.assertFalse(is_workspace_guide(root / "alife_examples.md"))
        # The transcript's document.md is Field's document format, not a guide.
        self.assertFalse(
            is_workspace_guide(root / "assets/agent_transcripts/alife_worked_example/document.md")
        )
        # The repo-root guide is never built as a page.
        self.assertFalse(is_workspace_guide(Path("/repo/AGENTS.md")))


class RewriteGuideFencesTests(unittest.TestCase):
    def test_plain_language_fences_become_diffstatic(self):
        source = "intro\n\n```bash\nls\n```\n"
        self.assertEqual(
            rewrite_guide_fences(source),
            "intro\n\n```diffstatic {data-lang=bash}\nls\n```\n",
        )

    def test_language_aliases_and_bare_fences_normalize(self):
        for info, expected in (("sh", "bash"), ("shell", "bash"), ("txt", "text"), ("", "text")):
            with self.subTest(info=info):
                out = rewrite_guide_fences(f"```{info}\nbody\n```\n")
                self.assertEqual(out.splitlines()[0], f"```diffstatic {{data-lang={expected}}}")

    def test_a_fence_carrying_attributes_survives_with_its_closer(self):
        """The bug this guards: a fence left as-is must still toggle the open/closed state,
        or its closing ``` reads as the next opening fence and gets rewritten."""
        source = '```kotlin [field-property box="x" name="code"]\nval a = 1\n```\n\n```bash\nls\n```\n'
        out = rewrite_guide_fences(source)
        self.assertEqual(
            out,
            '```kotlin [field-property box="x" name="code"]\nval a = 1\n```\n\n'
            "```diffstatic {data-lang=bash}\nls\n```\n",
        )

    def test_fence_bodies_are_never_touched(self):
        """A four-backtick wrapper quotes fence syntax literally; the inner fence is content."""
        source = "````text\n```bash\nls\n```\n````\n"
        out = rewrite_guide_fences(source)
        self.assertEqual(
            out,
            "````diffstatic {data-lang=text}\n```bash\nls\n```\n````\n",
        )

    def test_indentation_and_line_count_are_preserved(self):
        source = "   ```yaml\n   a: 1\n   ```\n"
        out = rewrite_guide_fences(source)
        self.assertEqual(out, "   ```diffstatic {data-lang=yaml}\n   a: 1\n   ```\n")
        self.assertEqual(len(out.splitlines()), len(source.splitlines()))

    def test_rewriting_is_idempotent(self):
        source = "```bash\nls\n```\n\n```\nplain\n```\n"
        once = rewrite_guide_fences(source)
        self.assertEqual(once, rewrite_guide_fences(once))

    def test_a_file_with_no_fences_is_returned_unchanged(self):
        source = "# Title\n\nSome prose with `inline code`.\n"
        self.assertEqual(rewrite_guide_fences(source), source)


if __name__ == "__main__":
    unittest.main()
