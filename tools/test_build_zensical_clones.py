"""The docs build's copies are copy-on-write clones of `public/docs/`, not second copies.

On 2026-11-20 a nightly worktree that had rebuilt one docs page measured 17 GB, with the disk at
6.9 GiB free: the tracked 4.8 GiB of `public/docs/`, a full copy of it in
`public/.zensical-generated/docs/`, and zensical's full copy of its assets in the site. APFS
reported every byte of all three as private to its file. These tests pin the cure: what this
script copies is a clone, the site's assets are swapped for clones of their sources, and a site
file that differs from its source is left as zensical wrote it.

"Shares its blocks" is read from APFS itself (`ATTR_CMNEXT_PRIVATESIZE`, the bytes deleting the
file would free), because `du` counts a clone as a full copy and cannot tell the two apart.
"""

from __future__ import annotations

import ctypes
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_zensical_docs import clone_file, copy_non_markdown_files, share_site_assets  # noqa: E402

# Big enough to span many APFS blocks, so a private size of 0 cannot be rounding.
ASSET = bytes(range(256)) * 4096


class _AttrList(ctypes.Structure):
    _fields_ = [("bitmapcount", ctypes.c_ushort), ("reserved", ctypes.c_uint16),
                ("commonattr", ctypes.c_uint32), ("volattr", ctypes.c_uint32),
                ("dirattr", ctypes.c_uint32), ("fileattr", ctypes.c_uint32),
                ("forkattr", ctypes.c_uint32)]


def private_bytes(path: Path) -> int:
    """APFS's count of [path]'s bytes that no clone shares (sys/attr.h, macOS 10.15+)."""
    libc = ctypes.CDLL(None, use_errno=True)
    attrs = _AttrList(5, 0, 0, 0, 0, 0, 0x00000008)  # ATTR_CMNEXT_PRIVATESIZE
    buffer = ctypes.create_string_buffer(32)
    # FSOPT_ATTR_CMN_EXTENDED | FSOPT_NOFOLLOW
    if libc.getattrlist(os.fsencode(path), ctypes.byref(attrs), buffer, len(buffer), 0x20 | 0x1) != 0:
        raise OSError(ctypes.get_errno(), os.strerror(ctypes.get_errno()), str(path))
    return struct.unpack_from("<Iq", buffer.raw)[1]


def on_apfs(path: Path) -> bool:
    if sys.platform != "darwin":
        return False
    probe = path / "probe"
    probe.write_bytes(ASSET)
    clone = path / "probe.clone"
    cloned = clone_file(probe, clone)
    probe.unlink()
    clone.unlink()
    return cloned


class CloneFileTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_a_clone_has_the_source_bytes_and_overwrites_like_copy2(self) -> None:
        source = self.root / "asset.png"
        source.write_bytes(ASSET)
        destination = self.root / "copy.png"
        destination.write_bytes(b"an older capture")
        clone_file(source, destination)
        self.assertEqual(destination.read_bytes(), ASSET)
        self.assertEqual(int(destination.stat().st_mtime), int(source.stat().st_mtime))

    def test_writing_a_clone_leaves_its_source_alone(self) -> None:
        source = self.root / "asset.png"
        source.write_bytes(ASSET)
        destination = self.root / "copy.png"
        clone_file(source, destination)
        destination.write_bytes(b"rewritten")
        self.assertEqual(source.read_bytes(), ASSET)

    def test_on_apfs_a_clone_costs_no_private_bytes(self) -> None:
        if not on_apfs(self.root):
            self.skipTest("cloning needs APFS")
        source = self.root / "asset.png"
        source.write_bytes(ASSET)
        destination = self.root / "copy.png"
        self.assertTrue(clone_file(source, destination))
        self.assertEqual(private_bytes(destination), 0)

    def test_the_generated_docs_tree_clones_every_asset(self) -> None:
        if not on_apfs(self.root):
            self.skipTest("cloning needs APFS")
        docs = self.root / "docs"
        (docs / "assets/page").mkdir(parents=True)
        (docs / "assets/page/figure.png").write_bytes(ASSET)
        (docs / "page.md").write_text("# Page")
        generated = self.root / "generated"
        copy_non_markdown_files(docs, generated)
        self.assertFalse((generated / "page.md").exists())
        self.assertEqual((generated / "assets/page/figure.png").read_bytes(), ASSET)
        self.assertEqual(private_bytes(generated / "assets/page/figure.png"), 0)


class ShareSiteAssetsTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.docs = self.root / "docs"
        self.site = self.root / "site"
        (self.docs / "assets/page").mkdir(parents=True)
        (self.site / "assets/page").mkdir(parents=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_an_identical_site_asset_becomes_a_clone_of_its_source(self) -> None:
        if not on_apfs(self.root):
            self.skipTest("cloning needs APFS")
        (self.docs / "assets/page/figure.png").write_bytes(ASSET)
        (self.site / "assets/page/figure.png").write_bytes(ASSET)  # what zensical writes: a copy
        self.assertEqual(private_bytes(self.site / "assets/page/figure.png"), len(ASSET))
        shared, size = share_site_assets(source_docs=self.docs, site_dir=self.site)
        self.assertEqual((shared, size), (1, len(ASSET)))
        self.assertEqual((self.site / "assets/page/figure.png").read_bytes(), ASSET)
        self.assertEqual(private_bytes(self.site / "assets/page/figure.png"), 0)
        self.assertEqual(sorted(p.name for p in (self.site / "assets/page").iterdir()), ["figure.png"])

    def test_a_site_file_that_differs_from_its_source_is_kept(self) -> None:
        (self.docs / "assets/page/style.css").write_text("a { color: red }")
        (self.site / "assets/page/style.css").write_text("a { color: blue }")  # same length, rewritten
        self.assertEqual(share_site_assets(source_docs=self.docs, site_dir=self.site), (0, 0))
        self.assertEqual((self.site / "assets/page/style.css").read_text(), "a { color: blue }")

    def test_pages_and_assets_the_site_does_not_have_are_ignored(self) -> None:
        (self.docs / "page.md").write_text("# Page")
        (self.site / "page.md").write_text("# Page")  # workspace markdown is copied verbatim, cloned already
        (self.docs / "assets/page/unpublished.png").write_bytes(ASSET)
        self.assertEqual(share_site_assets(source_docs=self.docs, site_dir=self.site), (0, 0))
        self.assertFalse((self.site / "assets/page/unpublished.png").exists())


if __name__ == "__main__":
    unittest.main()
