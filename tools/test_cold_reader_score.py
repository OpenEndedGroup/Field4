"""Tests for tools/cold_reader_score.py: the fixed matching rule and the precision/recall it yields."""

from __future__ import annotations

import unittest

from tools import cold_reader_score as cr


def row(page="p.md", line=1, category="dense", exact="", note=""):
    return {"page": page, "line": line, "category": category, "exact": exact, "note": note}


class QuoteOverlap(unittest.TestCase):
    def test_containment_ignores_markdown_and_case(self):
        self.assertTrue(cr.quotes_overlap("with `propDown`", "values flow With propDown to children"))

    def test_four_word_run(self):
        self.assertTrue(cr.quotes_overlap("the transcript-driven renderer parent described at the foot",
                                          "renderer parent described at the foot of this page"))

    def test_three_shared_words_are_not_enough(self):
        self.assertFalse(cr.quotes_overlap("one two three four", "two three four nine"))
        self.assertFalse(cr.quotes_overlap("alpha beta gamma delta", "x beta gamma y"))

    def test_empty_never_matches(self):
        self.assertFalse(cr.quotes_overlap("", "anything"))


class Matching(unittest.TestCase):
    def test_location_needs_same_category(self):
        ledger = [row(line=10, category="dense", exact="aaa bbb")]
        self.assertEqual(cr.match_page([row(line=11, category="dense", exact="zzz")], ledger), [(0, 0, "line")])
        self.assertEqual(cr.match_page([row(line=11, category="page-meta", exact="zzz")], ledger), [])
        self.assertEqual(cr.match_page([row(line=12, category="dense", exact="zzz")], ledger), [])

    def test_one_to_one_and_quote_first(self):
        ledger = [row(line=5, exact="the other side is never defined"), row(line=6, category="dense", exact="qqq")]
        stalls = [row(line=6, exact="the other side is never"), row(line=5, exact="the other side is never defined")]
        pairs = cr.match_page(stalls, ledger)
        self.assertEqual(len(pairs), 2)
        self.assertEqual(sorted(p[2] for p in pairs), ["line", "quote"])

    def test_precision_recall_and_pages_are_separate(self):
        ledger = [row(exact="alpha beta gamma delta"), row(exact="x", line=40), row(page="q.md", exact="alpha beta gamma delta")]
        stalls = [row(exact="Alpha beta gamma delta epsilon"), row(exact="nothing", line=90), row(exact="more", line=70)]
        [p] = cr.score(stalls, ledger, ["p.md"])
        self.assertEqual((p.stalls, p.ledger, p.matched), (3, 2, 1))
        self.assertAlmostEqual(p.precision, 1 / 3)
        self.assertAlmostEqual(p.recall, 0.5)
        self.assertIn("**pooled** | 3 | 2 | 1 (1)", cr.table([p]))


if __name__ == "__main__":
    unittest.main()
