"""A box still is taken at 3 device pixels per CSS pixel and records that scale in its PNG.

The site shows every capture at 1.5 times its size in Field, and works out that size from the
scale the PNG was taken at (`data-capture-scale` on the image, written from the PNG's pHYs chunk).
A still at another scale than its fence asks for is recaptured: a fresh checkout has no cache
entries and would otherwise adopt every committed 2x still as it is.
"""

import shlex
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
PAGE = REPO_ROOT / "public" / "docs" / "survivalGuide.md"


def _options(attrs: str) -> "docs_hooks.RunnerOptions":
    return docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner",
        open_line="```diffstatic_runner {data-lang=kotlin " + attrs + "}",
        page_path=PAGE,
        line_number=1,
        block_index=0,
    )


def _screenshot_box_args(options) -> list[str]:
    invocation = docs_hooks.build_backdoor_command(
        repo_root=REPO_ROOT, page_path=PAGE, block_index=0,
        screenshot_path=PAGE.parent / "assets" / "x.png", options=options,
    )
    for line in invocation.backdoor_commands:
        parts = shlex.split(line)
        if parts[0] == "screenshot-box":
            return parts
    raise AssertionError("no screenshot-box in " + repr(invocation.backdoor_commands))


def _tiny_png(path: Path) -> None:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return len(body).to_bytes(4, "big") + kind + body + zlib.crc32(kind + body).to_bytes(4, "big")
    header = (1).to_bytes(4, "big") * 2 + bytes([8, 0, 0, 0, 0])
    path.write_bytes(
        docs_hooks._PNG_SIGNATURE + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(b"\x00\x7f")) + chunk(b"IEND", b"")
    )


class BoxStillScaleTests(unittest.TestCase):

    def test_a_box_still_is_taken_at_3_unless_its_fence_says_otherwise(self):
        plain = _options('capture="box" screenshot="assets/x.png"')
        self.assertEqual(docs_hooks.box_still_scale(plain), 3.0)
        args = _screenshot_box_args(plain)
        self.assertEqual(args[args.index("--scale") + 1], "3.0")
        self.assertEqual(docs_hooks.options_digest_payload(plain)["scale"], 3.0)
        self.assertEqual(docs_hooks.box_still_scale(_options('capture="box" scale="4" screenshot="assets/x.png"')), 4.0)

    def test_a_window_capture_that_collapses_to_a_box_is_a_box_still(self):
        options = _options('capture="window" open-outputs="true" screenshot="assets/x.png"')
        self.assertEqual(docs_hooks.box_still_scale(options), 3.0)

    def test_other_captures_keep_the_capture_page_scale_and_their_digest(self):
        for attrs in ('capture="ui" screenshot="assets/x.png"', 'capture="box" video="assets/x.mp4"',
                      'capture="box" screenshot="assets/x.png" source-image="/tmp/render.png"'):
            options = _options(attrs)
            self.assertIsNone(docs_hooks.box_still_scale(options), attrs)
            self.assertNotIn("scale", docs_hooks.options_digest_payload(options), attrs)

    def test_a_png_records_its_scale_and_an_untagged_one_reads_as_the_capture_page(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "still.png"
            _tiny_png(path)
            self.assertEqual(docs_hooks.png_pixel_scale(path), docs_hooks.CAPTURE_PAGE_SCALE)
            docs_hooks.write_png_pixel_scale(path, 3.0)
            self.assertEqual(docs_hooks.png_pixel_scale(path), 3.0)
            docs_hooks.write_png_pixel_scale(path, 4.0)
            self.assertEqual(docs_hooks.png_pixel_scale(path), 4.0)
            self.assertEqual(path.read_bytes().count(b"pHYs"), 1)


if __name__ == "__main__":
    unittest.main()
