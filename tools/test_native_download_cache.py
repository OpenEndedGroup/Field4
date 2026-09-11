import hashlib
import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))

from native_download_cache import native_download_cache, seed_from_legacy_cache  # noqa: E402


class NativeDownloadCacheTests(unittest.TestCase):
    def test_override_is_shared_and_component_scoped(self):
        with TemporaryDirectory() as temporary:
            os.environ["AFIELD_NATIVE_DOWNLOAD_CACHE"] = temporary
            self.addCleanup(os.environ.pop, "AFIELD_NATIVE_DOWNLOAD_CACHE", None)
            self.assertEqual(
                native_download_cache("tetgen"),
                Path(temporary) / "native-downloads" / "tetgen",
            )

    def test_valid_legacy_archive_seeds_cache_atomically(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy = root / "worktree" / "archive.tar.gz"
            destination = root / "shared" / "archive.tar.gz"
            legacy.parent.mkdir()
            legacy.write_bytes(b"pinned archive")
            digest = hashlib.sha256(legacy.read_bytes()).hexdigest()
            self.assertTrue(seed_from_legacy_cache(legacy, destination, digest))
            self.assertEqual(destination.read_bytes(), b"pinned archive")
            self.assertFalse(list(destination.parent.glob("*.tmp")))

    def test_invalid_legacy_archive_is_not_reused(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            legacy = root / "archive.tar.gz"
            destination = root / "shared" / "archive.tar.gz"
            legacy.write_bytes(b"corrupt")
            self.assertFalse(seed_from_legacy_cache(legacy, destination, "0" * 64))
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
