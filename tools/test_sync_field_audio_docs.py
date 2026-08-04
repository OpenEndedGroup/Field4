from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from sync_field_audio_docs import synchronize


class SyncFieldAudioDocsTests(unittest.TestCase):
    def test_copy_check_and_drift(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "service/reference.md"
            target = root / "field/generated/reference.md"
            source.parent.mkdir(parents=True)
            source.write_text("# Contract\n")

            self.assertTrue(synchronize(source, target))
            self.assertEqual(target.read_text(), "# Contract\n")
            self.assertFalse(synchronize(source, target, check=True))

            source.write_text("# New contract\n")
            with self.assertRaisesRegex(RuntimeError, "snapshot is stale"):
                synchronize(source, target, check=True)

    def test_missing_source_is_actionable(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "Initialize services/field-audio-ai"):
                synchronize(root / "missing.md", root / "target.md")


if __name__ == "__main__":
    unittest.main()
