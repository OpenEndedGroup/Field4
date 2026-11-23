"""The per-fence source coverage of the capture digest, and the index it is read out of.

`load_source_symbol_index` reads symbol → file out of the generated codebase reference. When the
reference's format moves and the parser does not, the index is silently empty and every capture
digest quietly stops covering the source its fence calls — no error, just an incremental build that
never recaptures. That happened: 42d3c5e954 (2026-08-26) appended "— [read the source](…)" to each
section's path line, `REFERENCE_SOURCE_PATH_RE` still wanted the path alone, and coverage was off
until 2026-11-20. `AgainstTheRealReference` is the guard that would have said so on the first day.
"""

import re
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "public"))

import docs_hooks  # noqa: E402

REFERENCE_DIR = REPO_ROOT / docs_hooks.REFERENCE_DIR_RELATIVE
# Any line that starts with a backticked src/….kt path — the shape a section's path line has,
# whatever follows it. Deliberately looser than the parser, so the two can disagree.
PATH_LINE_RE = re.compile(r"^`src/[^`]+\.kt`", re.M)


class ReferenceSourcePathPatternTests(unittest.TestCase):
    def test_the_bare_path_line(self):
        match = docs_hooks.REFERENCE_SOURCE_PATH_RE.search("x\n`src/jvmMain/kotlin/afield/a/B.kt`\ny")
        self.assertEqual(match.group(1), "src/jvmMain/kotlin/afield/a/B.kt")

    def test_the_path_line_with_its_read_the_source_link(self):
        line = "`src/jvmMain/kotlin/afield/a/B.kt` — [read the source](source/jvmMain/afield/a/B/)"
        match = docs_hooks.REFERENCE_SOURCE_PATH_RE.search(f"x\n{line}\ny")
        self.assertEqual(match.group(1), "src/jvmMain/kotlin/afield/a/B.kt")

    def test_a_path_inside_a_sentence_is_not_a_section_path(self):
        self.assertIsNone(docs_hooks.REFERENCE_SOURCE_PATH_RE.search("See `src/jvmMain/kotlin/afield/a/B.kt` for more."))


@unittest.skipUnless(any(REFERENCE_DIR.glob("*.md")), "codebase reference not generated")
class AgainstTheRealReference(unittest.TestCase):
    def test_the_parser_reads_every_section_path_line(self):
        unread = []
        for page in sorted(REFERENCE_DIR.glob("*.md")):
            text = page.read_text(encoding="utf-8")
            loose = len(PATH_LINE_RE.findall(text))
            parsed = len(docs_hooks.REFERENCE_SOURCE_PATH_RE.findall(text))
            if parsed != loose:
                unread.append(f"{page.name}: {parsed} of {loose} path lines parsed")
        self.assertEqual(unread, [], "REFERENCE_SOURCE_PATH_RE no longer matches the reference's path lines")

    def test_the_symbol_index_is_not_empty(self):
        index = docs_hooks.load_source_symbol_index(REPO_ROOT)
        files = {source for sources in index.values() for source in sources}
        # 18,549 symbols over 1,713 files on 2026-11-20. The floor is far below that on purpose:
        # this is the "silently empty" guard, not a census.
        self.assertGreater(len(index), 1000, "per-fence source coverage would be off")
        self.assertGreater(len(files), 200)


if __name__ == "__main__":
    unittest.main()
