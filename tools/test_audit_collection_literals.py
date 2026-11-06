import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_collection_literals import literal_lines, select, summarize_results
from inventory_box_code import digest


def row(path, code, *, role="source", language="kotlin"):
    return dict(path=path, id=path, code=code, role=role, language=language, sha256=digest(code))


class CollectionInventoryTests(unittest.TestCase):
    def test_real_expression_contexts_include_multiline_literals(self):
        code = "val a = [1, 2]\nf([3])\nfor (x in [4]) {}\nreturn [5]\n[6].map { it }\nval b =\n    [7]\n"
        self.assertEqual(literal_lines(code), [1, 2, 3, 4, 5, 7])

    def test_shader_strings_comments_and_indexing_are_not_candidates(self):
        code = 'val shader = "x + [1,2]"\nval shader2 = """[3].map{}"""\n// val a = [1]\n/* f([2]) */\nxs[0] = ys[1]\n'
        self.assertEqual(literal_lines(code), [])

    def test_shared_body_is_covered_by_owner_but_a_different_body_is_not(self):
        code = "val a = [1, 2]"
        rows = [row("dev_docs/agents/transcripts/demo.yaml", code),
                row("public/docs/demo.md", code, role="docs-fence"),
                row("public/docs/other.md", "val a = [1, 3]", role="docs-fence")]
        candidates, owners, unowned = select(rows)
        self.assertEqual(len(candidates), 3)
        self.assertEqual(owners, ["dev_docs/agents/transcripts/demo.yaml"])
        self.assertEqual([r["path"] for r in unowned], ["public/docs/other.md"])

    def test_guest_generated_and_historical_copies_are_not_new_sources(self):
        rows = [row("x", "val a = [1]", role=role) for role in ("history", "generated", "inline-fallback", "document-mirror")]
        rows.append(row("guest", "val a = [1]", language="kotlinjs"))
        self.assertEqual(select(rows), ([], [], []))

    def test_nested_capture_transcript_is_not_silently_counted_as_a_guard_owner(self):
        candidate = row("dev_docs/agents/transcripts/docs_capture/demo.yaml", "val a = [1]")
        candidates, owners, unowned = select([candidate])
        self.assertEqual(owners, [])
        self.assertEqual(unowned, candidates)

    def test_unowned_exact_duplicates_compile_once(self):
        rows = [row("one", "val a = [1]"), row("two", "val a = [1]")]
        self.assertEqual(len(select(rows)[2]), 1)

    def test_docs_use_the_runners_effective_source_including_fold_contents(self):
        owner = row("dev_docs/agents/transcripts/demo.yaml", "val a = [1]")
        doc = row("public/docs/demo.md", ":::static\n:::fold setup\nval a = [1]\n:::endfold", role="docs-fence")
        candidates, _, unowned = select([owner, doc])
        self.assertEqual(unowned, [])
        self.assertEqual(candidates[0]["sha256"], candidates[1]["sha256"])
        self.assertNotIn(":::fold", candidates[1]["code"])

    def test_deleted_before_code_is_not_a_current_example(self):
        doc = row("public/docs/demo.md", ":::before\nval a = [1]\n:::after\nval a = 3", role="docs-fence")
        doc["fence"] = "diff {data-lang=kotlin}"
        self.assertEqual(select([doc]), ([], [], []))

    def test_a_compiler_report_cannot_pass_empty_missing_duplicate_or_old_sources(self):
        inputs = [{"sha256": "current"}]
        good = {"sha256": "current", "compiled": True, "errors": []}
        for results in ([], [good, good], [{**good, "sha256": "old"}]):
            with self.subTest(results=results), self.assertRaises(ValueError):
                summarize_results(inputs, results)
        with self.assertRaises(ValueError):
            summarize_results([], [])

    def test_a_fragment_with_missing_context_is_not_counted_as_a_pass(self):
        results = [{"sha256": "one", "compiled": True, "errors": []},
                   {"sha256": "two", "compiled": False, "errors": ["Unresolved reference 'renderer'"]}]
        summary = summarize_results([{"sha256": "one"}, {"sha256": "two"}], results)
        self.assertEqual(summary["compiledStandalone"], 1)
        self.assertEqual(summary["review"], [results[1]])
        with self.assertRaises(ValueError):
            summarize_results([{"sha256": "one"}], [{**results[0], "errors": ["compiler rejected the source"]}])


if __name__ == "__main__":
    unittest.main()
