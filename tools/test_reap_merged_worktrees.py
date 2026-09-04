import unittest
from pathlib import Path

from tools.reap_merged_worktrees import (
    DiskRow,
    branch_is_in_scope,
    parse_lsof_cwds,
    parse_worktrees,
    pids_inside,
    print_disk_report,
)


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

    def test_live_cwd_parser_and_nested_path_detection(self) -> None:
        cwds = parse_lsof_cwds("p12\nn/trees/one\np34\nn/trees/two/build\n")
        self.assertEqual(pids_inside(Path("/trees/one"), cwds), {12})
        self.assertEqual(pids_inside(Path("/trees/two"), cwds), {34})
        self.assertEqual(pids_inside(Path("/trees/elsewhere"), cwds), set())

    def test_disk_report_counts_only_safe_rows_and_fails_a_deficit(self) -> None:
        rows = [
            DiskRow(Path("/large-but-dirty"), 9 * 1048576, "dirty", False),
            DiskRow(Path("/small-and-merged"), 2 * 1048576, "clean and merged", True),
        ]
        self.assertFalse(
            print_disk_report(
                rows,
                free_kib=5 * 1048576,
                total_kib=100 * 1048576,
                required_kib=8 * 1048576,
            )
        )

    def test_disk_report_passes_when_reservation_is_already_clear(self) -> None:
        self.assertTrue(
            print_disk_report(
                [],
                free_kib=12 * 1048576,
                total_kib=100 * 1048576,
                required_kib=8 * 1048576,
            )
        )


if __name__ == "__main__":
    unittest.main()
