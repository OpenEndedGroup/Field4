"""Tests for the docs link graph's published-site addresses (`tools/docs_link_graph.py`).

The site map (`docs_map.md`) sends a reader from a node to a built page, so a wrong address is a
dead link on the public site. These read the real reference pages rather than fixtures.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_docs_link_graph
"""

import re
import unittest

from tools.docs_link_graph import DOCS_RELATIVE, REPO_ROOT, reference_anchors, site_url, used_symbols


class SiteUrlTest(unittest.TestCase):
    def test_directory_urls(self) -> None:
        self.assertEqual(site_url("index.md", "../../"), "../../")
        self.assertEqual(site_url("changelog.md", "../../"), "../../changelog/")
        self.assertEqual(site_url("reference/index.md", ""), "reference/")
        self.assertEqual(site_url("reference/graphics.md#flines-kt", "../"), "../reference/graphics/#flines-kt")

    def test_a_page_named_like_index_is_not_an_index(self) -> None:
        self.assertEqual(site_url("reindex.md", ""), "reindex/")


class ReferenceAnchorsTest(unittest.TestCase):
    def test_every_anchor_is_on_its_reference_page(self) -> None:
        anchors = reference_anchors(REPO_ROOT)
        self.assertGreater(len(anchors), 500, "the reference documents far more files than this")
        pages: dict[str, str] = {}
        for source, target in anchors.items():
            self.assertTrue(source.startswith("src/") and source.endswith(".kt"), source)
            page, _, anchor = target.partition("#")
            text = pages.setdefault(page, (REPO_ROOT / DOCS_RELATIVE / page).read_text(encoding="utf-8"))
            heading = re.search(r"\{\s*#" + re.escape(anchor) + r"\s*\}", text)
            self.assertIsNotNone(heading, target)
            # The anchor names the file's own section, not a neighbour's.
            section = text[heading.end():].split("\n## ", 1)[0]
            self.assertTrue(f"`{source}`" in section, f"{target} is not the section for {source}")


class UsedSymbolsTest(unittest.TestCase):
    def test_strings_and_comments_are_not_calls(self) -> None:
        code = 'val t = Resolve.timeline("Edit 2") // Creatures\nprintln("${Resolve.markers().size}")'
        names = used_symbols(code)
        self.assertIn("Resolve", names)
        self.assertIn("markers", names)
        self.assertNotIn("Edit", names)
        self.assertNotIn("Creatures", names)


if __name__ == "__main__":
    unittest.main()
