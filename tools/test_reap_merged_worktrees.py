import unittest
from pathlib import Path

from tools.reap_merged_worktrees import branch_is_in_scope, parse_worktrees


class ReapMergedWorktreesTests(unittest.TestCase):
    def test_porcelain_parser_keeps_branch_identity(self) -> None:
        trees = parse_worktrees(
            "worktree /repo\nHEAD abc123\nbranch refs/heads/main\n\n"
            "worktree /trees/nightly\nHEAD def456\nbranch refs/heads/nightly/2026-08-01/example\n"
        )
        self.assertEqual(trees[1].path, Path("/trees/nightly"))
        self.assertEqual(trees[1].branch, "nightly/2026-08-01/example")

    def test_only_disposable_branch_families_are_in_scope(self) -> None:
        self.assertTrue(branch_is_in_scope("nightly/2026-08-01/example"))
        self.assertTrue(branch_is_in_scope("review/example-on-main"))
        self.assertTrue(branch_is_in_scope("integration/nightly-four"))
        self.assertFalse(branch_is_in_scope("main"))
        self.assertFalse(branch_is_in_scope("feature/user-work"))
        self.assertFalse(branch_is_in_scope("nightly/journal"))
        self.assertFalse(branch_is_in_scope("nightly/ci-journal"))
        self.assertFalse(branch_is_in_scope(None))

    def test_completed_mode_is_documented_as_branch_preserving(self) -> None:
        source = Path("tools/reap_merged_worktrees.py").read_text()
        self.assertIn("--completed", source)
        self.assertIn("branches preserved", source)
        self.assertIn("if not completed:", source)


if __name__ == "__main__":
    unittest.main()
