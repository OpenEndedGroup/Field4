"""Pin the rule that keeps workspace markdown out of the site's pages -- and out of search.

Every `public/docs/assets/<name>/` folder is a downloadable Field workspace, and the three markdown
files in one (`README.md`, `AGENTS.md`, `document.md`) are workspace furniture rather than pages:
96 transcripts meant 96 byte-identical copies of the same two guides plus 96 walls of EDN, 312
orphan pages that nothing on the site linked to. They were reachable only by turning up in a
search for something else -- 1265 of 6673 index entries and 2.3 MB of a 10.2 MB `search.json`.

So `build_zensical_docs` skips them in the page pipeline and copies them verbatim into the built
site instead. These tests cover the classifier, the copy, and the prune that makes an incremental
build drop a page an earlier build rendered.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_zensical_docs import (  # noqa: E402
    copy_workspace_markdown,
    is_workspace_markdown,
    prune_stale_page,
)


class WorkspaceMarkdownDetectionTests(unittest.TestCase):
    def test_markdown_inside_the_asset_tree_is_workspace_markdown(self):
        for relative in (
            "assets/agent_transcripts/alife_worked_example/README.md",
            "assets/agent_transcripts/alife_worked_example/AGENTS.md",
            "assets/agent_transcripts/alife_worked_example/document.md",
            "assets/rectsl_gallery/README.md",
        ):
            with self.subTest(relative=relative):
                self.assertTrue(is_workspace_markdown(Path(relative)))

    def test_hand_written_prose_in_an_asset_folder_is_still_a_page(self):
        """The bug this guards: excluding *all* markdown under assets/ broke two live links --
        `afield_four_graphics.md` -> LESSONS.md and `path_tracing.md` -> ATTRIBUTION.md -- and
        turned them into the `page does not exist` warnings a `--strict` build fails on."""
        self.assertFalse(is_workspace_markdown(Path("assets/canonical_graphics/LESSONS.md")))
        self.assertFalse(is_workspace_markdown(Path("assets/fabricate_demo/ATTRIBUTION.md")))

    def test_hand_written_pages_and_non_markdown_assets_are_not(self):
        for relative in (
            "alife_examples.md",
            "reference/search.md",
            # The repo-root guides are not under the docs root at all, but the name test alone
            # would match them.
            "README.md",
            "AGENTS.md",
            # The gallery page *about* the transcripts is a page; it lives outside assets/.
            "agent_transcripts.md",
            # The file the gallery actually links, opened in the offline viewer.
            "assets/agent_transcripts/alife_worked_example/metadata.edn",
            "assets/agent_transcripts/alife_worked_example/evolve_code_.kt",
        ):
            with self.subTest(relative=relative):
                self.assertFalse(is_workspace_markdown(Path(relative)))


class CopyWorkspaceMarkdownTests(unittest.TestCase):
    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.source_docs = root / "docs"
        self.site_dir = root / "field4"
        workspace = self.source_docs / "assets/agent_transcripts/demo"
        workspace.mkdir(parents=True)
        (workspace / "README.md").write_text("# A Field workspace\n", encoding="utf-8")
        (workspace / "document.md").write_text("# Field Document: demo\n", encoding="utf-8")
        (workspace / "metadata.edn").write_text("{}\n", encoding="utf-8")
        (self.source_docs / "index.md").write_text("# Home\n", encoding="utf-8")
        self.site_dir.mkdir()

    def tearDown(self):
        self._tmp.cleanup()

    def test_workspace_markdown_lands_raw_and_nothing_else_is_touched(self):
        copied = copy_workspace_markdown(source_docs=self.source_docs, site_dir=self.site_dir)
        self.assertEqual(copied, 2)
        workspace = self.site_dir / "assets/agent_transcripts/demo"
        self.assertEqual((workspace / "README.md").read_text(), "# A Field workspace\n")
        self.assertEqual((workspace / "document.md").read_text(), "# Field Document: demo\n")
        # A page is never copied raw into the site by this step, and `metadata.edn` is the
        # Zensical build's business, not this function's.
        self.assertFalse((self.site_dir / "index.md").exists())
        self.assertFalse((workspace / "metadata.edn").exists())

    def test_a_page_an_earlier_build_rendered_is_pruned(self):
        """The bug this guards: `zensical build` without `--clean` keeps serving last build's
        pages, and the site dir is git-ignored, so the orphans would outlive the fix."""
        workspace = self.site_dir / "assets/agent_transcripts/demo"
        (workspace / "document").mkdir(parents=True)
        (workspace / "document/index.html").write_text("<html>stale</html>", encoding="utf-8")
        (workspace / "index.html").write_text("<html>stale README</html>", encoding="utf-8")

        copy_workspace_markdown(source_docs=self.source_docs, site_dir=self.site_dir)

        self.assertFalse((workspace / "document").exists())
        self.assertFalse((workspace / "index.html").exists())
        self.assertTrue((workspace / "document.md").is_file())

    def test_a_static_index_html_in_the_source_tree_is_left_alone(self):
        """`assets/mode/**` ships hand-written `index.html` files; a README beside one must not
        make the prune delete it."""
        source = self.source_docs / "assets/mode"
        source.mkdir(parents=True)
        (source / "index.html").write_text("<html>static</html>", encoding="utf-8")
        (source / "README.md").write_text("# modes\n", encoding="utf-8")
        built = self.site_dir / "assets/mode"
        built.mkdir(parents=True)
        (built / "index.html").write_text("<html>static</html>", encoding="utf-8")

        prune_stale_page(
            source_docs=self.source_docs,
            site_dir=self.site_dir,
            relative=Path("assets/mode/README.md"),
        )

        self.assertTrue((built / "index.html").is_file())


if __name__ == "__main__":
    unittest.main()
