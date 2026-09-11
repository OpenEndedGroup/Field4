"""Regression tests for FIELD_TEST annotation parsing and repository discovery."""

import sys
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
        catalog = discover(repo_root, DEFAULT_ROOTS)
        ids = [str(test["id"]) for test in catalog["tests"]]
        self.assertTrue(ids)
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
