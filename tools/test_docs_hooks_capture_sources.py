"""A page's `<!-- capture-sources: ... -->` folds those files into every fence's capture digest.

The per-fence digest sees only the source files whose symbols a fence names; a helper in another
file, or a bridge in another package, changes the figure without changing that digest. The page
declares it, and an edit to it must then change the digest.
"""

import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

from docs_hooks import compute_source_refs_digest, page_capture_sources  # noqa: E402


class CaptureSourcesTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "src/pkg").mkdir(parents=True)
        (self.root / "src/pkg/a.kt").write_text("fun a() = 1\n")
        (self.root / "src/pkg/b.kt").write_text("fun b() = 2\n")
        (self.root / "src/other.kt").write_text("fun other() = 3\n")
        self.page = self.root / "page.md"

    def digest(self):
        return compute_source_refs_digest(
            repo_root=self.root, code="println(1)", symbol_index={},
            extra_files=page_capture_sources(self.root, self.page),
        )

    def test_a_page_without_the_directive_declares_nothing(self):
        self.page.write_text("# Title\n")
        self.assertEqual((), page_capture_sources(self.root, self.page))
        self.assertEqual("", self.digest())

    def test_files_and_directories_are_both_read(self):
        self.page.write_text("# Title\n<!-- capture-sources: src/other.kt src/pkg/ -->\n")
        self.assertEqual(("src/other.kt", "src/pkg/a.kt", "src/pkg/b.kt"), page_capture_sources(self.root, self.page))

    def test_editing_a_declared_file_changes_the_digest(self):
        self.page.write_text("<!-- capture-sources:\n  src/pkg/\n-->\n")
        before = self.digest()
        (self.root / "src/pkg/b.kt").write_text("fun b() = 20\n")
        self.assertNotEqual(before, self.digest())
        unchanged = self.digest()
        (self.root / "src/other.kt").write_text("fun other() = 30\n")
        self.assertEqual(unchanged, self.digest(), "a file the page did not declare does not count")


if __name__ == "__main__":
    unittest.main()
