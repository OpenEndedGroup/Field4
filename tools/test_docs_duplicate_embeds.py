"""A runner fence with `embed=true` already puts its screenshot on the page; a second image line for
the same file shows the figure twice. paint_canvas.md, watercolor.md and paint_gestures.md all did
(2026-10-08), so the docs build now refuses such a page.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

DOCS = Path(__file__).resolve().parent.parent / "public" / "docs"


class DuplicateEmbedTests(unittest.TestCase):
    def test_an_image_line_repeating_an_embedded_screenshot_is_found(self):
        page = (
            '```diffstatic_runner {data-lang=kotlin screenshot="assets/p/a.png" embed=true}\n'
            "code\n```\n\n![a](assets/p/a.png)\n"
        )
        self.assertEqual(docs_hooks.duplicate_embedded_images(page), [(5, "assets/p/a.png")])

    def test_an_image_without_embed_or_of_another_file_is_left_alone(self):
        page = (
            '```diffstatic_runner {data-lang=kotlin screenshot="assets/p/a.png"}\n'
            "code\n```\n\n![a](assets/p/a.png)\n\n![b](assets/p/b.png)\n"
        )
        self.assertEqual(docs_hooks.duplicate_embedded_images(page), [])

    def test_no_published_page_repeats_an_embedded_screenshot(self):
        found = {str(p.relative_to(DOCS)): d for p in DOCS.rglob("*.md") if (d := docs_hooks.duplicate_embedded_images(p.read_text()))}
        self.assertEqual(found, {})


if __name__ == "__main__":
    unittest.main()
