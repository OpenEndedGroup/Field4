"""Tests for the docs link graph (`tools/docs_link_graph.py`): its published-site addresses, and the
report's link dating and shared-code measure.

The site map (`docs_map.md`) sends a reader from a node to a built page, so a wrong address is a
dead link on the public site. The address tests read the real reference pages rather than fixtures.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_docs_link_graph
"""

import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

from tools.docs_link_graph import (CAPTURE_CODE_PREFIX, DOCS_RELATIVE, REPO_ROOT, Graph, code_files_for, code_neighbours, link_pages_to_map,
                                  layout, parse_link_log, reference_anchors, site_url, used_symbols)


class SiteUrlTest(unittest.TestCase):
    def test_directory_urls(self) -> None:
        self.assertEqual(site_url("index.md", "../../"), "../../")
        self.assertEqual(site_url("changelog.md", "../../"), "../../changelog/")
        self.assertEqual(site_url("reference/index.md", ""), "reference/")
        self.assertEqual(site_url("reference/graphics.md#flines-kt", "../"), "../reference/graphics/#flines-kt")

    def test_a_page_named_like_index_is_not_an_index(self) -> None:
        self.assertEqual(site_url("reindex.md", ""), "reindex/")


class LayoutTest(unittest.TestCase):
    @unittest.skipUnless(
        importlib.util.find_spec("networkx") and importlib.util.find_spec("numpy"),
        "requires the docs dependency group",
    )
    def test_pages_only_layout_has_no_code_shell(self) -> None:
        graph = Graph()
        graph.node("page:a.md", kind="page")
        graph.node("page:b.md", kind="page")
        graph.edge("page:a.md", "page:b.md", "links")
        positions, packages = layout(graph)
        self.assertEqual({"page:a.md", "page:b.md"}, set(positions))
        self.assertEqual({}, packages)
        self.assertNotEqual(positions["page:a.md"], positions["page:b.md"])


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
                (site / built).write_text('<article><h1 id="title">Title</h1><h2>Next</h2></article>')
            self.assertEqual(link_pages_to_map(site, root), 2)
            self.assertEqual(link_pages_to_map(site, root), 0, "a second pass adds nothing")
            graphics = (site / "reference/graphics/index.html").read_text()
            self.assertIn('<h1 id="title"><a class="docs-map-link" href="../../docs_map/?node=page%3Areference%2Fgraphics.md"', graphics)
            self.assertIn("</svg></a>Title</h1>", graphics, "the icon sits inside the title, before its text")
            self.assertIn('href="docs_map/?node=page%3Aindex.md"', (site / "index.html").read_text())
            self.assertNotIn("docs-map-link", (site / "docs_map/index.html").read_text(), "the map does not link to itself")
            self.assertNotIn("docs-map-link", (site / "orphan/index.html").read_text(), "no source page, no node")


def diff(when: int, path: str, *lines: str, renamed_from: str | None = None) -> str:
    """One commit of `git log -p --format=%x00%ct --unified=0` touching one page."""
    head = [f"\x00{when}", "", f"diff --git a/{renamed_from or path} b/{path}"]
    if renamed_from:
        head += ["similarity index 90%", f"rename from {renamed_from}", f"rename to {path}"]
    return "\n".join(head + [f"--- a/{renamed_from or path}", f"+++ b/{path}", "@@ -1 +1 @@", *lines]) + "\n"


class ParseLinkLogTest(unittest.TestCase):
    PAGES = {"a.md", "b.md", "c.md", "d.md"}

    def test_a_link_dates_from_the_commit_that_first_named_its_target(self) -> None:
        log = diff(100, "public/docs/a.md", "+See [B](b.md).") + diff(200, "public/docs/a.md", "+And [C](c.md#x).")
        self.assertEqual(parse_link_log(log, self.PAGES), {("a.md", "b.md"): 100.0, ("a.md", "c.md"): 200.0})

    def test_a_rewritten_paragraph_keeps_its_links_date(self) -> None:
        log = diff(100, "public/docs/a.md", "+See [B](b.md).") + diff(300, "public/docs/a.md", "-See [B](b.md).", "+Now see [B](b.md) here.")
        self.assertEqual(parse_link_log(log, self.PAGES), {("a.md", "b.md"): 100.0})

    def test_a_removed_link_is_gone_and_a_restored_one_dates_from_its_return(self) -> None:
        log = diff(100, "public/docs/a.md", "+[B](b.md) [C](c.md)") + diff(200, "public/docs/a.md", "-[B](b.md) [C](c.md)", "+[C](c.md)")
        log += diff(300, "public/docs/a.md", "+[B](b.md) again")
        self.assertEqual(parse_link_log(log, self.PAGES), {("a.md", "c.md"): 100.0, ("a.md", "b.md"): 300.0})

    def test_a_rename_carries_the_links_to_the_new_path(self) -> None:
        log = diff(100, "public/docs/old.md", "+[B](b.md)") + diff(200, "public/docs/d.md", renamed_from="public/docs/old.md")
        self.assertEqual(parse_link_log(log, self.PAGES), {("d.md", "b.md"): 100.0})

    def test_links_to_nothing_and_to_itself_are_not_links(self) -> None:
        log = diff(100, "public/docs/a.md", "+[me](a.md) [gone](zz.md) [web](https://x.org/b.md)")
        self.assertEqual(parse_link_log(log, self.PAGES), {})


class CodeNeighboursTest(unittest.TestCase):
    def graph(self) -> Graph:
        g = Graph()
        files = {"paint": "src/a/Paint.kt", "brush": "src/a/Brush.kt", "fline": "src/a/FLine.kt",
                 "shot": CAPTURE_CODE_PREFIX + "docs/screenshot.kt", "mesh": "src/a/Mesh.kt"}
        for name, path in files.items():
            g.node("code:" + path, kind="code", path=path)
        reaches = {"strokes": ["paint", "brush", "shot"], "brushes": ["paint", "brush", "shot"],
                   "shots1": ["shot", "mesh"], "shots2": ["shot", "fline"], "solo": ["paint"]}
        reaches.update({f"filler{i}": ["mesh" if i % 2 else "fline"] for i in range(8)})
        for page, names in reaches.items():
            g.node("page:" + page, kind="page")
            for name in names:
                g.edge("page:" + page, "code:" + files[name], "calls")
        return g

    def test_pages_sharing_specific_files_are_neighbours_and_capture_helpers_do_not_count(self) -> None:
        g = self.graph()
        found = code_neighbours(g, [k for k in g.nodes if k.startswith("page:")])
        self.assertEqual([(a, b) for _, a, b, _ in found], [("page:brushes", "page:strokes")])
        self.assertEqual(sorted(found[0][3]), ["code:src/a/Brush.kt", "code:src/a/Paint.kt"])

    def test_one_shared_file_is_not_enough(self) -> None:
        g = self.graph()
        pages = ["page:strokes", "page:solo", "page:brushes"]
        self.assertNotIn("page:solo", {p for _, a, b, _ in code_neighbours(g, pages) for p in (a, b)})


if __name__ == "__main__":
    unittest.main()
