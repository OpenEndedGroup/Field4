"""Regression guard for `check_doc_source_anchors.py` (judge backlog N1112-2).

Pinned: a broken `agent_transcripts.md#uid` link from a docs page, a dev_doc and a KDoc comment
each fails (positive controls); toc slug rules (underscores kept, punctuation dropped, `_1` for a
repeated heading, explicit `{#id}`, raw-HTML `id=`); links inside fences and inline code are
ignored; the population floor fails a scan that finds nothing; and the real tree is clean.

Run with:  uv run --group backdoor python -m unittest tools.test_check_doc_source_anchors
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_doc_source_anchors as c  # noqa: E402

PAGE = """# Agent transcripts

### rr_plumbing
### What's new: `display()` & friends
## Repeat
## Repeat
## Custom {#explicit-id}
<a id="html-anchor"></a>

```
### fenced_heading
```
"""


class SlugTest(unittest.TestCase):
    def test_slug_rules(self):
        self.assertEqual(c.slugify("rr_plumbing"), "rr_plumbing")
        self.assertEqual(c.slugify("What's new: `display()` & friends"), "whats-new-display-friends")
        self.assertEqual(c.slugify("See [the page](x.md) now"), "see-the-page-now")


class FixtureTree(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "public/docs").mkdir(parents=True)
        (self.root / "dev_docs").mkdir()
        (self.root / "src/k").mkdir(parents=True)
        (self.root / "public/docs/agent_transcripts.md").write_text(PAGE)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        (self.root / rel).write_text(text)

    def broken(self):
        return {(str(s), f) for s, _, _, f in c.scan(self.root)[0]}

    def test_good_anchors_pass(self):
        self.write("public/docs/a.md", "[x](agent_transcripts.md#rr_plumbing) [y](agent_transcripts.md#repeat_1)\n"
                   "[z](agent_transcripts.md#explicit-id) [w](agent_transcripts.md#html-anchor)\n"
                   "[v](agent_transcripts.md#whats-new-display-friends)\n")
        self.assertEqual(self.broken(), set())

    def test_broken_anchor_fails_from_every_source_kind(self):
        self.write("public/docs/a.md", "[x](agent_transcripts.md#no_such_uid)\n")
        self.write("dev_docs/n.md", "[x](../public/docs/agent_transcripts.md#gone)\n")
        self.write("src/k/K.kt", "/** See [it](agent_transcripts.md#kdoc_gone). */\n")
        self.assertEqual(self.broken(), {("public/docs/a.md", "no_such_uid"), ("dev_docs/n.md", "gone"),
                                         ("src/k/K.kt", "kdoc_gone")})

    def test_fenced_heading_is_not_an_anchor_and_code_links_are_ignored(self):
        self.write("public/docs/a.md", "[x](agent_transcripts.md#fenced_heading)\n`[y](agent_transcripts.md#nope)`\n"
                   "```\n[z](agent_transcripts.md#nope2)\n```\n")
        self.assertEqual(self.broken(), {("public/docs/a.md", "fenced_heading")})

    def test_unresolved_page_is_skipped(self):
        self.write("public/docs/a.md", "[x](missing.md#anything)\n")
        self.assertEqual(self.broken(), set())

    def test_floor_fails_an_empty_scan(self):
        self.assertEqual(c.main(["--root", str(self.root)]), 1)


class RealTree(unittest.TestCase):
    def test_tree_is_clean(self):
        broken, checked = c.scan()
        self.assertGreaterEqual(checked, c.MIN_LINKS)
        self.assertEqual(broken, [], "\n".join(f"{s}:{n}: {t}#{f}" for s, n, t, f in broken))


if __name__ == "__main__":
    unittest.main()
