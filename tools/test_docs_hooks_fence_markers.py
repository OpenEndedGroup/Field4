"""Which `:::` lines the docs runner strips from a runnable fence, and which are the box's own code.

A bare `:::` closes a `:::fold` region. Anywhere else it is code: `markdown(...)` closes a table
row with it, and stripping it there made a docs page run different code from a live box.
"""

import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

from docs_hooks import extract_static_code  # noqa: E402


def code(text: str) -> str:
    return extract_static_code(lines=[line + "\n" for line in text.split("\n")])


class FenceMarkerTests(unittest.TestCase):
    def test_a_bare_marker_inside_the_code_is_kept(self):
        fence = ':::static\nmarkdown("""\n:::row\nleft\n:::\n""")'
        self.assertEqual('markdown("""\n:::row\nleft\n:::\n""")', code(fence))

    def test_a_bare_marker_still_closes_a_fold(self):
        fence = ":::static\nval a = 1\n:::fold setup\nval b = 2\n:::\nprintln(a + b)"
        self.assertEqual("val a = 1\nval b = 2\nprintln(a + b)", code(fence))

    def test_endfold_closes_a_fold_and_a_later_bare_marker_is_code(self):
        fence = ":::fold x\nval b = 2\n:::endfold\nprintln(\"\"\"\n:::\n\"\"\")"
        self.assertEqual('val b = 2\nprintln("""\n:::\n""")', code(fence))


if __name__ == "__main__":
    unittest.main()
