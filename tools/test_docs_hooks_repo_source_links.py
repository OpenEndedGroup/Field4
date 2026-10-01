"""Repo source paths in docs prose link to GitHub; dev notes and stale paths do not.

Marc's decision on handoff 9b508085: a code span that is exactly a tracked repo path under src/,
tools/, server/ or public/ links to it at github.com/OpenEndedGroup/Field4/blob/main. dev_docs/
stays unlinked, a path naming nothing tracked stays plain and is reported, and an author's own link
is left alone.
"""

import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

from docs_hooks import (  # noqa: E402
    REPO_SOURCE_GITHUB,
    RepoTree,
    link_repo_source_paths_html,
    mark_inline_code_html,
    repo_source_href,
)

TREE = RepoTree.from_paths([
    "src/jvmMain/kotlin/afield/Boxes.kt",
    "tools/field_backdoor.py",
    "server/README.md",
    "public/docs_hooks.py",
    "dev_docs/TETGEN.md",
])
BLOB = f"{REPO_SOURCE_GITHUB}/blob/main"


def page(body: str) -> str:
    return f'<html><body><article class="md-content__inner md-typeset">{body}</article></body></html>'


class RepoSourceHrefTests(unittest.TestCase):
    def test_a_tracked_file_under_a_linked_root_links_to_main(self):
        self.assertEqual(
            repo_source_href("src/jvmMain/kotlin/afield/Boxes.kt", TREE),
            (f"{BLOB}/src/jvmMain/kotlin/afield/Boxes.kt", None),
        )
        for path in ("tools/field_backdoor.py", "server/README.md", "public/docs_hooks.py"):
            with self.subTest(path=path):
                self.assertEqual(repo_source_href(path, TREE), (f"{BLOB}/{path}", None))

    def test_a_line_or_range_becomes_a_github_anchor(self):
        self.assertEqual(repo_source_href("tools/field_backdoor.py:12", TREE)[0], f"{BLOB}/tools/field_backdoor.py#L12")
        self.assertEqual(
            repo_source_href("tools/field_backdoor.py:12-20", TREE)[0], f"{BLOB}/tools/field_backdoor.py#L12-L20"
        )

    def test_a_directory_links_to_its_tree(self):
        self.assertEqual(
            repo_source_href("src/jvmMain/kotlin/afield/", TREE),
            (f"{REPO_SOURCE_GITHUB}/tree/main/src/jvmMain/kotlin/afield", None),
        )

    def test_dev_docs_stay_unlinked_and_are_not_stale(self):
        self.assertEqual(repo_source_href("dev_docs/TETGEN.md", TREE), (None, None))
        self.assertEqual(repo_source_href("dev_docs/NO_SUCH.md", TREE), (None, None))

    def test_a_missing_path_under_a_linked_root_is_stale(self):
        self.assertEqual(repo_source_href("src/jvmMain/kotlin/afield/Gone.kt", TREE), (None, "src/jvmMain/kotlin/afield/Gone.kt"))

    def test_what_is_not_a_repo_path_is_neither(self):
        for text in ("Boxes.kt", "afield/Boxes.kt", "tools/test_*.py", "src/{a,b}", "foo.bar(1)", "src",
                     "/src/jvmMain/kotlin/afield/Boxes.kt", "src/jvmMain/kotlin/afield/Boxes.kt extra"):
            with self.subTest(text=text):
                self.assertEqual(repo_source_href(text, TREE), (None, None))


class LinkRepoSourcePathsHtmlTests(unittest.TestCase):
    def test_a_prose_path_is_wrapped_in_a_link(self):
        out = link_repo_source_paths_html(page("<p>See <code>tools/field_backdoor.py:12</code>.</p>"), TREE)
        self.assertIn(
            f'<a class="repo-source-link" href="{BLOB}/tools/field_backdoor.py#L12" '
            'data-repo-path="tools/field_backdoor.py" data-repo-line="12"><code>tools/field_backdoor.py:12</code></a>',
            out,
        )

    def test_an_authors_link_fences_and_classed_code_are_left_alone(self):
        body = (
            '<p><a href="https://example.com/x"><code>public/docs_hooks.py</code></a></p>'
            "<pre><code>server/README.md</code></pre>"
            '<p><code class="source-citation">public/docs_hooks.py</code></p>'
        )
        self.assertEqual(link_repo_source_paths_html(page(body), TREE), page(body))

    def test_stale_paths_are_reported_once_each_and_stay_plain(self):
        stale: list[str] = []
        body = "<p><code>src/Gone.kt</code> <code>src/Gone.kt</code> <code>dev_docs/Gone.md</code></p>"
        out = link_repo_source_paths_html(page(body), TREE, stale)
        self.assertEqual(out, page(body))
        self.assertEqual(stale, ["src/Gone.kt", "src/Gone.kt"])  # deduplicated by the site pass

    def test_a_link_inside_a_reference_table_is_still_made(self):
        body = '<div data-code-lang="kotlin"><table><tr><td><code>server/README.md</code></td></tr></table></div>'
        self.assertIn(f'href="{BLOB}/server/README.md"', link_repo_source_paths_html(page(body), TREE))

    def test_linking_then_marking_leaves_the_path_plain_and_is_idempotent(self):
        once = mark_inline_code_html(link_repo_source_paths_html(page("<p><code>public/docs_hooks.py</code></p>"), TREE))
        self.assertIn("><code>public/docs_hooks.py</code></a>", once)
        self.assertEqual(mark_inline_code_html(link_repo_source_paths_html(once, TREE)), once)


class RepoTreeFromGitTests(unittest.TestCase):
    def test_this_checkout_tracks_its_own_hooks_and_not_its_built_site(self):
        tree = RepoTree.from_git(REPO_ROOT)
        self.assertIn("public/docs_hooks.py", tree.files)
        self.assertIn("src/jvmMain/kotlin/afield", tree.directories)
        self.assertNotIn("public/field4/index.html", tree.files)


if __name__ == "__main__":
    unittest.main()
