"""tools/explainer_pages.py: each explainer page shows the code its deck's figure box runs.

Negative control: the committed tree has no difference. Positive controls: one changed character
in a fence body, and a fence for a figure the deck does not have, are both named.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_explainer_pages
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import explainer_pages as ep  # noqa: E402


class ExplainerPagesTest(unittest.TestCase):
    def test_committed_pages_match_their_decks(self):
        self.assertEqual([], ep.problems())

    def test_fold_markers_are_not_code(self):
        code = "import a.b\n\nfun dot(x: Int) {\n    print(x)\n}\n\ndot(1)\n"
        folded = ep.fold(code)
        self.assertIn(":::fold imports\n", folded)
        self.assertIn(":::fold dot: ", folded)
        self.assertEqual(code, ep.strip_folds(folded))

    def _scratch_docs(self, edit):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        tmp = Path(scratch.name)
        for deck in ep.DECKS:
            page = ep.DOCS / f"explainer_{deck}.md"
            text = page.read_text(encoding="utf-8")
            (tmp / page.name).write_text(edit(deck, text), encoding="utf-8")
        return mock.patch.object(ep, "DOCS", tmp)

    def test_a_changed_fence_is_named(self):
        def edit(deck, text):
            return text.replace("val pitches = [196.0", "val pitches = [197.0", 1) if deck == "sound" else text
        with self._scratch_docs(edit):
            found = ep.problems()
        self.assertTrue(any("(keyboard): differs from box 'keyboard'" in p for p in found), found)

    def test_an_unknown_figure_is_named(self):
        def edit(deck, text):
            return text.replace('name="explainer-cameras-snell"', 'name="explainer-cameras-no_such_box"', 1)
        with self._scratch_docs(edit):
            found = ep.problems()
        self.assertTrue(any("no box named 'no_such_box'" in p for p in found), found)
        self.assertTrue(any("no fence for figure box(es) snell" in p for p in found), found)


if __name__ == "__main__":
    unittest.main()
