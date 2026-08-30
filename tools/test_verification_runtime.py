import os
import tempfile
import unittest
from pathlib import Path

from tools.verification_runtime import field_instance_problem


class FieldInstanceIdentityTests(unittest.TestCase):
    def test_missing_identity_is_never_safe_to_reuse(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            problem = field_instance_problem(None, Path(directory))
        self.assertIn("does not expose checkout identity", problem or "")

    def test_foreign_checkout_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as ours, tempfile.TemporaryDirectory() as foreign:
            problem = field_instance_problem(
                {"repoRoot": foreign, "startedAtEpochMs": 10_000},
                Path(ours),
            )
        self.assertIn("belongs to", problem or "")

    def test_unchanged_checkout_is_safe_to_reuse(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src" / "Example.kt"
            source.parent.mkdir()
            source.write_text("class Example\n", encoding="utf-8")
            os.utime(source, ns=(1_000_000_000, 1_000_000_000))
            problem = field_instance_problem(
                {"repoRoot": str(root), "startedAtEpochMs": 2_000},
                root,
            )
        self.assertIsNone(problem)

    def test_source_edited_after_boot_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src" / "Example.kt"
            source.parent.mkdir()
            source.write_text("class Example\n", encoding="utf-8")
            os.utime(source, ns=(3_000_000_000, 3_000_000_000))
            problem = field_instance_problem(
                {"repoRoot": str(root), "startedAtEpochMs": 2_000},
                root,
            )
        self.assertIn("changed after the server started", problem or "")

    def test_explicit_live_attachment_can_ignore_source_freshness(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src" / "Example.kt"
            source.parent.mkdir()
            source.write_text("class Example\n", encoding="utf-8")
            os.utime(source, ns=(3_000_000_000, 3_000_000_000))
            problem = field_instance_problem(
                {"repoRoot": str(root), "startedAtEpochMs": 2_000},
                root,
                allow_stale_source=True,
            )
        self.assertIsNone(problem)

    def test_explicit_live_attachment_still_rejects_a_foreign_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as ours, tempfile.TemporaryDirectory() as foreign:
            problem = field_instance_problem(
                {"repoRoot": foreign, "startedAtEpochMs": 10_000},
                Path(ours),
                allow_stale_source=True,
            )
        self.assertIn("belongs to", problem or "")


if __name__ == "__main__":
    unittest.main()
