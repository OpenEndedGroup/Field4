"""conversation_packagers: which edits feed a recorded-conversation page, and the lander's repackaging."""
import sys
import unittest
from unittest.mock import patch
from pathlib import Path
from subprocess import CompletedProcess

sys.path.insert(0, str(Path(__file__).resolve().parent))
import conversation_packagers as cp  # noqa: E402


class PackagersTest(unittest.TestCase):
    def test_every_packager_script_is_found_with_its_sources(self):
        found = {p.stem: p for p in cp.packagers()}
        self.assertEqual(
            {"function_conversation", "rectsl_conversation", "stackauto_conversation", "stacksl_conversation"},
            set(found),
        )
        for p in found.values():
            self.assertTrue(p.sources, p.script)
            for source in p.sources:
                self.assertTrue((cp.ROOT / source).is_file(), source)

    def test_the_two_edits_that_bounced_on_2026_10_17_are_seen(self):
        hits = cp.packagers_for_paths([
            "src/jvmMain/kotlin/afield/math/functions/FunctionStudyTools.kt",
            "dev_docs/agents/demos/stackauto_conversation/polytope_source_code_.kt",
        ])
        self.assertEqual(["function_conversation", "stackauto_conversation"], [p.stem for p in hits])

    def test_unrelated_paths_feed_nothing(self):
        self.assertEqual([], cp.packagers_for_paths([
            "src/jvmMain/kotlin/afield/document/graph/Boxes.kt",
            "dev_docs/agents/demos/some_other_demo/metadata.edn",
            "public/docs/function_conversation.md",
        ]))

    def test_a_clean_tree_needs_no_repackaging(self):
        self.assertEqual((0, []), cp.repackage_stale(cp.ROOT))

    def test_checks_and_regeneration_use_the_checkout_docs_environment(self):
        packager = cp.Packager("tools/package_example_conversation.py", "example_conversation", ())
        with patch.object(cp, "packagers", return_value=[packager]), patch.object(cp.subprocess, "run") as run:
            # A stale check is followed by successful regeneration.
            run.side_effect = [CompletedProcess([], 1), CompletedProcess([], 0)]
            code, _ = cp.repackage_stale(cp.ROOT)
        self.assertEqual(0, code)
        command = ["uv", "run", "--project", ".", "--group", "docs", "--frozen", "python", packager.script]
        self.assertEqual([*command, "--check"], run.call_args_list[0].args[0])
        self.assertEqual(command, run.call_args_list[1].args[0])
        self.assertTrue(all(call.kwargs["cwd"] == cp.ROOT for call in run.call_args_list))


if __name__ == "__main__":
    unittest.main()
