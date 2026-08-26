"""The citation rewrite: what a reader sees, and what the guard still gets to read.

`rewrite_source_citations` runs on the way into the generated site, so the markdown on disk keeps
the `#anchor` that `CitationGuard` resolves while the published page shows only the short form.
Both halves of that sentence are asserted here, including the cross-language one: the extension
list this transform recognises is read out of the Kotlin guard rather than restated, because an
extension the guard checks and the transform misses is a page published with its apparatus showing.
"""

import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

import docs_hooks  # noqa: E402
from docs_hooks import (  # noqa: E402
    CITATION_EXTENSIONS,
    rewrite_source_citations,
)

GUARD = REPO_ROOT / "src/jvmTest/kotlin/afield/docs/CitationGuard.kt"


class SourceCitationRewriteTests(unittest.TestCase):
    def test_a_prose_citation_shows_the_short_form_and_keeps_the_anchor(self):
        out = rewrite_source_citations(
            "`__visionpose__` (`VisionPose.kt:82#const val VISION_POSE_CHANNEL`) is the one channel.\n"
        )
        self.assertIn(">VisionPose.kt:82</code>", out)
        self.assertIn('title="const val VISION_POSE_CHANNEL"', out)
        self.assertIn('data-anchor="const val VISION_POSE_CHANNEL"', out)
        # The apparatus is gone from what the reader sees.
        self.assertNotIn("82#const", out)

    def test_a_citation_with_no_line_number_keeps_its_path(self):
        out = rewrite_source_citations("see `FLine.kt#fun thicken`\n")
        self.assertIn(">FLine.kt</code>", out)
        self.assertIn('title="fun thicken"', out)

    def test_a_ledger_row_is_left_alone(self):
        # The table form spells the anchor in its own cell, so there is nothing to fold away.
        row = "| The channel | `VisionPose.kt:82` | `const val VISION_POSE_CHANNEL` |\n"
        self.assertEqual(rewrite_source_citations(row), row)

    def test_two_citations_on_one_line_are_both_rewritten(self):
        out = rewrite_source_citations("`A.kt:1#fun a` and `B.kt:2#fun b`\n")
        self.assertEqual(out.count('class="source-citation"'), 2)
        self.assertIn(">A.kt:1</code>", out)
        self.assertIn(">B.kt:2</code>", out)

    def test_a_fenced_block_keeps_the_citation_verbatim(self):
        # A fence showing the citation format has to keep showing it.
        source = "before\n\n```markdown\n`Foo.kt:1#fun keep`\n```\n\nafter `Bar.kt:2#fun go`\n"
        out = rewrite_source_citations(source)
        self.assertIn("`Foo.kt:1#fun keep`", out)
        self.assertIn(">Bar.kt:2</code>", out)

    def test_an_anchor_with_markup_characters_is_escaped(self):
        out = rewrite_source_citations("`Types.kt:9#fun of(m: Map<String, Set<String>>)`\n")
        self.assertIn("&lt;String, Set&lt;String&gt;&gt;", out)
        self.assertNotIn("<String,", out)

    def test_a_page_with_no_citations_is_returned_unchanged(self):
        source = "Just prose, one `inline` pill, and a `path/to/file.kt` with no anchor.\n"
        self.assertEqual(rewrite_source_citations(source), source)


class CitationLinkingTests(unittest.TestCase):
    """Linking is opt-in twice over: the caller must pass a depth, and the page must exist."""

    def setUp(self):
        self.anchors = Path(tempfile.mkdtemp()) / "anchors.json"
        self.anchors.write_text(
            json.dumps(
                {
                    "VisionPose.kt": {
                        "page": "reference/source/jvmMain/afield/graphics/VisionPose.md",
                        "lines": [1, 82, 88, 273, 418],
                    }
                }
            ),
            encoding="utf-8",
        )
        os.environ[docs_hooks.SOURCE_PAGE_ANCHORS_ENV] = str(self.anchors)
        docs_hooks._source_page_anchors = None

    def tearDown(self):
        os.environ.pop(docs_hooks.SOURCE_PAGE_ANCHORS_ENV, None)
        docs_hooks._source_page_anchors = None

    def test_a_citation_at_a_declaration_links_to_that_declaration(self):
        # depth 0: a page at the docs root, whose links are written with no `../` at all.
        out = rewrite_source_citations("see `VisionPose.kt:82#const val X`\n", depth=0)
        self.assertIn('href="reference/source/jvmMain/afield/graphics/VisionPose/#L82"', out)

    def test_a_citation_into_a_body_links_to_the_declaration_containing_it(self):
        # There is no anchor on line 300; the section that contains it is the declaration at 273.
        out = rewrite_source_citations("see `VisionPose.kt:300#whatever`\n", depth=0)
        self.assertIn("#L273", out)

    def test_the_relative_prefix_follows_the_citing_page_depth(self):
        out = rewrite_source_citations("see `VisionPose.kt:82#x`\n", depth=3)
        self.assertIn('href="../../../reference/', out)

    def test_a_file_with_no_page_stays_an_unlinked_chip(self):
        out = rewrite_source_citations("see `Server.kt:1#fun main`\n", depth=1)
        self.assertNotIn("<a ", out)
        self.assertIn('class="source-citation"', out)

    def test_no_source_pages_at_all_means_no_links_anywhere(self):
        os.environ.pop(docs_hooks.SOURCE_PAGE_ANCHORS_ENV, None)
        docs_hooks._source_page_anchors = None
        out = rewrite_source_citations("see `VisionPose.kt:82#const val X`\n", depth=1)
        self.assertNotIn("<a ", out)
        self.assertIn(">VisionPose.kt:82</code>", out)


class PageDepthTests(unittest.TestCase):
    def test_depth_counts_directories_not_url_segments(self):
        # Written file-relative because zensical rebases raw-HTML hrefs itself; counting URL segments
        # instead put every link one level above the site.
        docs = Path("/repo/public/docs")
        self.assertEqual(docs_hooks.page_directory_depth(docs / "vision.md", docs), 0)
        self.assertEqual(docs_hooks.page_directory_depth(docs / "reference/graphics.md", docs), 1)
        self.assertEqual(docs_hooks.page_directory_depth(docs / "reference/index.md", docs), 1)


class ExtensionListAgreementTests(unittest.TestCase):
    def test_the_transform_recognises_exactly_what_the_guard_checks(self):
        text = GUARD.read_text(encoding="utf-8")
        match = re.search(r'private const val EXT = "([^"]+)"', text)
        self.assertIsNotNone(match, f"CitationGuard.EXT not found in {GUARD}")
        self.assertEqual(
            set(match.group(1).split("|")),
            set(CITATION_EXTENSIONS.split("|")),
            "CitationGuard.EXT and docs_hooks.CITATION_EXTENSIONS have drifted; a citation the "
            "guard checks would be published with its `#anchor` showing",
        )


if __name__ == "__main__":
    unittest.main()
