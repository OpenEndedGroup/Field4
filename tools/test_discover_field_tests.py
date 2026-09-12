"""Regression tests for FIELD_TEST annotation parsing and repository discovery."""

import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from discover_field_tests import (  # noqa: E402
    DEFAULT_ROOTS,
    AnnotationParser,
    ParseError,
    Token,
    discover,
    find_annotation_blocks,
)


class AnnotationParserTests(unittest.TestCase):
    def test_quoted_list_scalar_keeps_its_colon(self) -> None:
        parser = AnnotationParser([Token(indent=0, text='- "PASS glTF: textures"', line_no=4)])
        self.assertEqual(parser._parse_block(0), ["PASS glTF: textures"])

    def test_list_mapping_still_splits_an_unquoted_colon(self) -> None:
        parser = AnnotationParser([Token(indent=0, text="- name: texture", line_no=4)])
        self.assertEqual(parser._parse_block(0), [{"name": "texture"}])

    def test_parse_error_names_the_source_file_and_line(self) -> None:
        path = Path("broken.kt")
        text = """/*
FIELD_TEST
id: broken
END_FIELD_TEST
*/
"""
        with self.assertRaisesRegex(ParseError, r"broken\.kt:3: expected key/value pair on line 3"):
            find_annotation_blocks(path, text)


class RepositoryCatalogTests(unittest.TestCase):
    def test_every_field_test_in_the_repository_parses(self) -> None:
        repo_root = Path(__file__).resolve().parent.parent
        # `strict=True` on purpose. Discovery now fails *open* per annotation, so a malformed
        # annotation in the tree no longer raises and this guard would otherwise have quietly
        # lost its teeth -- it would pass with a catalog full of skips.
        catalog = discover(repo_root, DEFAULT_ROOTS, strict=True)
        ids = [str(test["id"]) for test in catalog["tests"]]
        self.assertTrue(ids)
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(catalog["skipped"], [])
        # A floor near the measured number, not an equality: adding a test must not fail the
        # build, while losing a third of the catalog to silent skips must. The number is
        # checkout-dependent, so the floor is set below the smaller of the two measurements --
        # 234 in a worktree (170 from src/jvmMain/kotlin/afield + 64 from public/docs) against
        # 252 in the primary checkout, whose extra 18 come from the gitignored `src/personal`
        # root that exists for one person on one clone.
        self.assertGreaterEqual(len(ids), 230)


GOOD_ANNOTATION = """
/*
FIELD_TEST
id: {id}
runner: jvm-main
entrypoint: afield.examples.Placeholder
*/
fun placeholder() {{}}
"""

# `END_FIELD_TEST` is not annotation syntax -- the parser wants `key: value` and this line has no
# colon. It is the exact shape of malformed annotation that took the whole catalog down, and it is
# an easy mistake because it reads like a terminator.
BAD_ANNOTATION = """
/*
FIELD_TEST
id: {id}
runner: jvm-main
END_FIELD_TEST
*/
"""


class FailOpenDiscoveryTests(unittest.TestCase):
    """One unreadable annotation must not make every other test unselectable.

    Before this, `discover` accumulated every per-file `ParseError` and raised at the end, so a
    single malformed `FIELD_TEST` anywhere in the tree made `run_field_tests.py --id <anything>`
    dead for all 252 tests. The only fallback was `build/field-tests/catalog.json`, untracked
    build output a fresh worktree does not have. That is how the canonical smoke runner stayed
    broken for twelve hours.

    These write into a temp dir, never the work tree (`checkTestTreeWrites`), and use the real
    repo root only so the fabricate-scene registry check can find its harness.
    """

    def setUp(self) -> None:
        self.repo_root = Path(__file__).resolve().parent.parent
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def scan(self, **kwargs) -> dict:
        return discover(self.repo_root, [str(self.root)], warn_stream=None, **kwargs)

    def test_a_broken_annotation_does_not_hide_a_good_one_in_another_file(self) -> None:
        (self.root / "good.kt").write_text(GOOD_ANNOTATION.format(id="nightly.good"))
        (self.root / "bad.kt").write_text(BAD_ANNOTATION.format(id="nightly.bad"))

        catalog = self.scan()

        self.assertEqual([test["id"] for test in catalog["tests"]], ["nightly.good"])
        self.assertEqual(len(catalog["skipped"]), 1)
        self.assertEqual(catalog["skipped"][0]["sourceLine"], 5)
        self.assertIn("bad.kt", catalog["skipped"][0]["sourceFile"])
        self.assertIn("expected key/value pair", catalog["skipped"][0]["error"])

    def test_a_broken_annotation_does_not_hide_a_good_one_in_the_same_file(self) -> None:
        # `find_annotation_blocks` used to raise on the first bad block, so the good annotation
        # *below* it in the same file was lost even once discovery stopped aborting the tree.
        (self.root / "mixed.kt").write_text(
            BAD_ANNOTATION.format(id="nightly.bad") + GOOD_ANNOTATION.format(id="nightly.good")
        )

        catalog = self.scan()

        self.assertEqual([test["id"] for test in catalog["tests"]], ["nightly.good"])
        self.assertEqual(len(catalog["skipped"]), 1)

    def test_a_block_that_fails_validation_is_skipped_not_fatal(self) -> None:
        # The other family of per-block failure: the metadata parses as YAML-ish but the
        # annotation is not a usable test (no `runner`).
        (self.root / "good.kt").write_text(GOOD_ANNOTATION.format(id="nightly.good"))
        (self.root / "invalid.kt").write_text("/*\nFIELD_TEST\nid: nightly.runnerless\n*/\n")

        catalog = self.scan()

        self.assertEqual([test["id"] for test in catalog["tests"]], ["nightly.good"])
        self.assertEqual(len(catalog["skipped"]), 1)
        self.assertEqual(catalog["skipped"][0]["error"], "missing required field 'runner'")
        # Not double-prefixed: the location lives in the structured fields, once.
        self.assertNotIn(":", catalog["skipped"][0]["error"])

    def test_strict_mode_still_refuses_the_whole_catalog(self) -> None:
        (self.root / "good.kt").write_text(GOOD_ANNOTATION.format(id="nightly.good"))
        (self.root / "bad.kt").write_text(BAD_ANNOTATION.format(id="nightly.bad"))

        with self.assertRaisesRegex(ParseError, r"bad\.kt:5: expected key/value pair"):
            self.scan(strict=True)

    def test_the_skip_is_announced_loudly_on_stderr(self) -> None:
        (self.root / "bad.kt").write_text(BAD_ANNOTATION.format(id="nightly.bad"))
        stream = io.StringIO()

        discover(self.repo_root, [str(self.root)], warn_stream=stream)

        printed = stream.getvalue()
        self.assertIn("SKIPPED unreadable annotation", printed)
        self.assertIn("bad.kt:5", printed)
        self.assertIn("1 annotation(s) skipped", printed)

    def test_a_clean_tree_says_nothing_and_records_nothing(self) -> None:
        (self.root / "good.kt").write_text(GOOD_ANNOTATION.format(id="nightly.good"))
        stream = io.StringIO()

        catalog = discover(self.repo_root, [str(self.root)], warn_stream=stream)

        self.assertEqual(stream.getvalue(), "")
        self.assertEqual(catalog["skipped"], [])

    def test_an_out_of_tree_root_is_catalogued_rather_than_crashing(self) -> None:
        # `--root <absolute path outside the checkout>` is an advertised option and used to raise
        # ValueError from `path.relative_to(repo_root)` while building the record.
        (self.root / "good.kt").write_text(GOOD_ANNOTATION.format(id="nightly.good"))

        catalog = self.scan()

        self.assertEqual(catalog["tests"][0]["sourceFile"], str(self.root / "good.kt"))


if __name__ == "__main__":
    unittest.main()
