"""No docs-runner fence inlines an image at a percentage width.

A `capture="box"` figure photographs the box's output panel, and that panel is as wide as its
widest content. An `<img style='width:100%'>` has no width of its own: it takes 100% of whatever the
*other* content made the panel, so the same image comes out large beside a long line of text and
small beside short ones. `public/docs/studies.md` shipped its observe sheet (1002 px natural) at
292 CSS px that way on 2026-09-30, because the only text beside it was a list of tool names; the
capture itself was a normal 2x one (584 px), and the page showed it at half its pixel width like
every other figure, which read as "drawn at 1/2 size". Give an inlined image a width in px (or
none, for its natural size).

Only `diffstatic_runner` fence bodies are checked: a static fence is never captured.

Run directly with:  uv run --project . --group backdoor python -m unittest tools.test_runner_fence_image_widths
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DOCS = REPO_ROOT / "public/docs"
FENCE = re.compile(r"^(```+)diffstatic_runner[^\n]*\n(.*?)^\1\s*$", re.S | re.M)
PERCENT_IMG = re.compile(r"<img\b[^>]*width\s*[:=]\s*['\"]?\d+(?:\.\d+)?%", re.I)


def offenders(root: Path = DOCS) -> list[str]:
    found = []
    for page in sorted(root.glob("*.md")):
        text = page.read_text(encoding="utf-8")
        for fence in FENCE.finditer(text):
            body_start = text.count("\n", 0, fence.start(2)) + 1
            for i, line in enumerate(fence.group(2).splitlines()):
                if PERCENT_IMG.search(line):
                    found.append(f"{page.relative_to(REPO_ROOT)}:{body_start + i}: {line.strip()[:120]}")
    return found


class RunnerFenceImageWidthTest(unittest.TestCase):
    def test_no_runner_fence_inlines_an_image_at_a_percentage_width(self):
        self.assertEqual([], offenders(), "give each inlined <img> a width in px; see this file's docstring")

    def test_the_pattern_catches_the_shape_it_is_for(self):
        self.assertTrue(PERCENT_IMG.search("\"<img src='${x}' style='width:100%'/>\""))
        self.assertTrue(PERCENT_IMG.search('<img width="80%" src="a.png">'))
        self.assertFalse(PERCENT_IMG.search("\"<img src='${x}' style='width:720px'/>\""))
        self.assertFalse(PERCENT_IMG.search("<div style='width:100%'>"))


if __name__ == "__main__":
    unittest.main()
