"""Tests for the docs link graph's published-site addresses (`tools/docs_link_graph.py`).

The site map (`docs_map.md`) sends a reader from a node to a built page, so a wrong address is a
dead link on the public site. These read the real reference pages rather than fixtures.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_docs_link_graph
"""

import re
import tempfile
import unittest
from pathlib import Path

from tools.docs_link_graph import DOCS_RELATIVE, REPO_ROOT, code_files_for, link_pages_to_map, reference_anchors, site_url, used_symbols


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


    def test_package_paths_are_not_uses(self) -> None:
        code = "import afield.graphics.vulkan.Renderer\nval r = afield.four.vulkan.Renderer()\nr.vulkan.size"
        names = used_symbols(code)
        self.assertIn("Renderer", names)
        self.assertEqual(sum(1 for n in names if n == "vulkan"), 1, "only the member access r.vulkan")
        self.assertNotIn("vulkan", used_symbols("import afield.graphics.vulkan.*\nval x = afield.four.vulkan.Renderer"))


    def test_file_names_in_prose_are_not_calls(self) -> None:
        index = {"yaml": ("src/jvmMain/kotlin/afield/examples/DocumentToTranscript.kt",)}
        self.assertEqual(code_files_for("metadata.yaml", index, mention=True), {})
        self.assertEqual(code_files_for("dev_docs/agents/transcripts/x.yaml", index, mention=True), {})


class LinkPagesToMapTest(unittest.TestCase):
    def test_each_page_links_to_its_own_node_once(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            docs = root / DOCS_RELATIVE
            (docs / "reference").mkdir(parents=True)
            for page in ("index.md", "docs_map.md", "reference/graphics.md"):
                (docs / page).write_text("# x\n")
            site = root / "site"
            for built in ("index.html", "docs_map/index.html", "reference/graphics/index.html", "orphan/index.html"):
                (site / built).parent.mkdir(parents=True, exist_ok=True)
                (site / built).write_text("<article><h1>Title</h1><h2>Next</h2></article>")
            self.assertEqual(link_pages_to_map(site, root), 2)
            self.assertEqual(link_pages_to_map(site, root), 0, "a second pass adds nothing")
            graphics = (site / "reference/graphics/index.html").read_text()
            self.assertIn('</h1><p class="docs-map-link"><a href="../../docs_map/?node=page%3Areference%2Fgraphics.md">', graphics)
            self.assertIn('href="docs_map/?node=page%3Aindex.md"', (site / "index.html").read_text())
            self.assertNotIn("docs-map-link", (site / "docs_map/index.html").read_text(), "the map does not link to itself")
            self.assertNotIn("docs-map-link", (site / "orphan/index.html").read_text(), "no source page, no node")


if __name__ == "__main__":
    unittest.main()
