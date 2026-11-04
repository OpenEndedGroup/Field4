"""Tests for tools/docs_changelog.py: how windows are cut from `main`'s landings, the structural
rules `check` holds `public/docs/changelog.md` to, and the real page against the real history.

The unit tests build a synthetic history and a throwaway docs tree, so they read no git state.
`RealPage` is the enforcement: it runs `check` over the committed page against this checkout's
`main`, so an entry with a dead link, a gap in its ranges or a misfiled month fails
`checkPythonTools`.
"""

from __future__ import annotations

import subprocess
import textwrap
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools import docs_changelog as dc
from tools.git_isolation import scrub_process_env  # noqa: E402

scrub_process_env()


def full(i: int) -> str:
    """A 40-char sha whose 10-char prefix is unique to ``i``."""
    return f"{i:04x}".ljust(40, "0")


def history(*days: str, housekeeping: tuple[int, ...] = ()) -> list[dc.Landing]:
    """One landing per day string; landing i has the sha `full(i)`."""
    result = []
    for i, day in enumerate(days):
        subject = ("Regenerate generated artifacts after landing 1 branch(es)"
                   if i in housekeeping else f"Land topic-{i}")
        result.append(dc.Landing(i, full(i), day, subject, [full(i - 1)] if i else []))
    return result


def sha(i: int) -> str:
    return full(i)[:10]


def entry(first: int, last: int, heading: str = "A thing", body: str = "See [it](page.md).") -> str:
    return f"<!-- changelog: {sha(first)}..{sha(last)} -->\n### {heading}\n\n*date*\n\n{body}\n"


class Windows(unittest.TestCase):
    def test_counts_real_landings_and_skips_housekeeping(self):
        h = history(*["2026-10-01"] * 25, housekeeping=(3, 7))
        spans = dc.windows(h, size=10)
        self.assertEqual(spans[0], (0, 11))  # two regenerate commits ride along uncounted
        self.assertEqual(spans[-1][1], 24)
        covered = [i for a, b in spans for i in range(a, b + 1)]
        self.assertEqual(covered, list(range(25)))

    def test_closes_early_at_a_day_boundary(self):
        h = history(*["2026-10-01"] * 8 + ["2026-10-02"] * 8)
        self.assertEqual(dc.windows(h, size=10)[0], (0, 7))

    def test_starts_where_asked(self):
        h = history(*["2026-10-01"] * 30)
        self.assertEqual(dc.windows(h, start=12, size=10)[0], (12, 21))


class Check(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.docs = Path(self._tmp.name)
        (self.docs / "page.md").write_text("# Page\n\n## A section\n\n```\n## not a heading\n```\n")
        self.h = history(*["2026-10-30"] * 5 + ["2026-11-02"] * 5)

    def tearDown(self):
        self._tmp.cleanup()

    def faults(self, text: str, **kw) -> list[str]:
        return dc.check(textwrap.dedent(text), self.h, docs_root=self.docs, **kw)

    def test_a_page_that_tiles_history_is_clean(self):
        page = "# What's new\n\n## November 2026\n\n" + entry(5, 9) + "\n## October 2026\n\n" + \
               entry(0, 4, body="Read [the section](page.md#a-section).")
        self.assertEqual(self.faults(page), [])

    def test_gap_between_entries(self):
        page = "## November 2026\n\n" + entry(6, 9) + "## October 2026\n\n" + entry(0, 4)
        self.assertTrue(any("are in no entry" in f for f in self.faults(page)))

    def test_overlap_and_order(self):
        page = "## November 2026\n\n" + entry(4, 9) + "## October 2026\n\n" + entry(0, 4)
        self.assertTrue(any("overlaps" in f for f in self.faults(page)))

    def test_must_reach_the_root(self):
        page = "## November 2026\n\n" + entry(5, 9) + "## October 2026\n\n" + entry(1, 4)
        self.assertTrue(any("root commit" in f for f in self.faults(page)))

    def test_partial_draft_starts_where_told_and_needs_no_months(self):
        self.assertEqual(self.faults(entry(5, 9), partial_from=5), [])
        self.assertTrue(self.faults(entry(6, 9), partial_from=5))

    def test_month_follows_the_last_landing(self):
        page = "## October 2026\n\n" + entry(0, 9)
        self.assertTrue(any("sits under October 2026" in f for f in self.faults(page)))

    def test_links_and_anchors(self):
        bad = [entry(0, 9, body="No link at all."),
               entry(0, 9, body="[gone](missing.md)"),
               entry(0, 9, body="[x](page.md#not-a-heading)")]
        expected = ["links no docs page", "not a page under public/docs", "has no heading"]
        for text, message in zip(bad, expected):
            with self.subTest(message):
                faults = self.faults(text, partial_from=0)
                self.assertTrue(any(message in f for f in faults), faults)

    def test_fenced_heading_is_not_an_anchor(self):
        faults = self.faults(entry(0, 9, body="[x](page.md#not-a-heading)"), partial_from=0)
        self.assertTrue(faults)

    def test_external_and_fragment_only_links_are_not_pages(self):
        body = "[ext](https://example.com/x.md), [here](#top) and [it](page.md)."
        self.assertEqual(self.faults(entry(0, 9, body=body), partial_from=0), [])

    def test_unknown_sha(self):
        page = "<!-- changelog: deadbeef00..deadbeef01 -->\n### X\n"
        self.assertTrue(any("not a first-parent commit" in f for f in self.faults(page)))

    def test_tip(self):
        page = entry(5, 9) + entry(0, 4)
        self.assertEqual(dc.tip(page, self.h), 9)
        self.assertEqual(dc.tip("", self.h), -1)


class Render(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.docs = Path(self._tmp.name)
        (self.docs / "assets").mkdir()
        (self.docs / "assets" / "a.png").write_bytes(b"png")
        (self.docs / "assets" / "b.png").write_bytes(b"png")
        (self.docs / "page.md").write_text(
            "# Page\n\n![first](assets/a.png)\n\n## Later\n\n![second](assets/b.png)\n"
            "![missing](assets/none.png)\n")
        (self.docs / "plain.md").write_text("# Plain\n\nNo pictures.\n")
        self.h = history(*["2026-11-02"] * 10)
        self.published = {full(9)}

    def tearDown(self):
        self._tmp.cleanup()

    def render(self, body: str) -> str:
        page = "## November 2026\n\n" + entry(0, 9, heading="Headline", body=body)
        return dc.render(page.replace("*date*", "*2 November 2026*"), self.h, docs_root=self.docs,
                         published=self.published)

    def test_header_row_carries_date_and_commit(self):
        out = self.render("See [it](plain.md).")
        self.assertIn('<div class="changelog-head" markdown="1">', out)
        self.assertIn('<span class="changelog-date">2 November 2026</span>', out)
        self.assertIn(dc.COMMIT_URL + full(9), out)
        self.assertNotIn("*2 November 2026*", out)  # moved into the row, not repeated
        self.assertIn("### Headline", out)
        self.assertNotIn("changelog-tease", out)  # no picture on the linked page: no strip

    def test_unpushed_commit_is_not_linked(self):
        self.published = set()
        out = self.render("See [it](plain.md).")
        self.assertNotIn(dc.COMMIT_URL, out)
        self.assertIn('<span class="changelog-commit"', out)
        self.assertIn(full(9)[:9], out)

    def test_picture_comes_from_the_linked_section(self):
        self.assertIn('src="assets/a.png"', self.render("[it](page.md)"))
        self.assertIn('src="assets/b.png"', self.render("[it](page.md#later)"))
        self.assertIn('src="assets/a.png"', self.render("[none](plain.md), then [it](page.md)"))

    def test_override(self):
        self.assertIn('src="assets/b.png"',
                      self.render("[it](page.md)\n<!-- changelog-image: assets/b.png -->"))
        self.assertNotIn("changelog-tease",
                         self.render("[it](page.md)\n<!-- changelog-image: none -->"))


def has_main() -> bool:
    return subprocess.run(["git", "rev-parse", "--verify", "--quiet", "main^{commit}"],
                          cwd=dc.REPO_ROOT, capture_output=True).returncode == 0


@unittest.skipUnless(dc.CHANGELOG.exists() and has_main(), "needs the page and a local main")
class RealPage(unittest.TestCase):
    def test_the_published_page_passes_check(self):
        faults = dc.check(dc.CHANGELOG.read_text(encoding="utf-8"), dc.landings("main"))
        self.assertEqual(faults, [], "\n".join(faults))


if __name__ == "__main__":
    unittest.main()
