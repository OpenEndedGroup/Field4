"""The presentation path is set in Field's own Iosevka and nothing else.

Marc, 2026-11-09: "make sure that our font remains our custom Iosevka — there's no need for a
sans-serif anywhere." Hierarchy on slides, in the presenter and in figure views comes from size,
weight, opacity and colour. This fails if a generic or system family creeps into the CSS and JS
that draw them: the audience and presenter pages, the figure mode (its block of field.css and
figureMode.kt), the Electron presentation host, the slide builders and the starter themes.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
BANNED = re.compile(r"sans-serif|system-ui|-apple-system|Helvetica|Arial|Segoe UI|Roboto", re.IGNORECASE)

FILES = [
    "src/commonMain/resources/presentation-audience.js",
    "src/commonMain/resources/presentation-audience.html",
    "src/commonMain/resources/presentation-control.html",
    "src/jsMain/kotlin/afield/app/client/figureMode.kt",
    "electron/presentation.js",
    "electron/presentation-cues.js",
    "electron/presentation-transition.js",
    "src/jvmMain/kotlin/afield/ui/slides/Presentation.kt",
    "src/jvmMain/kotlin/afield/ui/slides/PresentationFigures.kt",
    "src/jvmMain/kotlin/afield/ui/slides/AudienceBuilder.kt",
]
THEMES = "templates/presentation/files"
FIGURE_BLOCK = ("/* Presentation figure (?figure=<uid>", "#field-figure-host .field-figure-box .outputArea *")


def figure_css() -> str:
    css = (REPO / "src/commonMain/resources/field.css").read_text(encoding="utf-8")
    start = css.index(FIGURE_BLOCK[0])
    # The figure block runs to the end of field.css: its controls rules follow the font rule.
    css[css.index("}", css.index(FIGURE_BLOCK[1], start))]
    return css[start:]


class PresentationFontsTest(unittest.TestCase):
    def sources(self):
        for name in FILES:
            yield name, (REPO / name).read_text(encoding="utf-8")
        for path in sorted((REPO / THEMES).rglob("*.css")):
            yield str(path.relative_to(REPO)), path.read_text(encoding="utf-8")
        yield "field.css (figure mode)", figure_css()

    def test_no_generic_or_system_family_in_the_presentation_path(self):
        found = [f"{name}:{text[:m.start()].count(chr(10)) + 1}: {m.group(0)}"
                 for name, text in self.sources() for m in BANNED.finditer(text)]
        self.assertEqual([], found, "Use Field's Iosevka ('Iosevka Web') only; vary size, weight, opacity or colour")

    def test_the_figure_mode_forces_iosevka_over_inline_box_styles(self):
        css = figure_css()
        self.assertRegex(css, r'font-family:\s*"Iosevka Web", Iosevka !important')

    def test_positive_control_the_pattern_catches_a_fallback(self):
        self.assertTrue(BANNED.search("font: 20px sans-serif"))
        self.assertTrue(BANNED.search("font-family: system-ui"))


if __name__ == "__main__":
    unittest.main()
