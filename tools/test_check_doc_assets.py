"""Regression guard for the placeholder-capture detector in `check_doc_assets.py`.

Background: `RayTracingDocsHarness` used to write a "Ray Tracing / Not Available" card
whenever the device had no `VK_KHR_ray_tracing_pipeline`, so that docs builds would
succeed anywhere. On 2026-07-09 four of those cards were committed as the worked-example
figures on `ray_tracing_examples.md` and shipped for seven weeks under a page claiming
every image on it was rendered by Field. The detector exists so that cannot recur.

The threshold numbers below are **real measurements** taken on 2026-08-30 by running the
detector over all 744 PNGs under `public/docs`: 123 were card-sized and therefore decoded,
and exactly 4 -- the known fakes -- were flagged. Both sides of the margin are pinned here,
because a detector that quietly drifts past either edge is worse than none: too loose and
it condemns real captures, too tight and it waves the next card through.

The hand-written PNG unfilter is the riskiest code in the detector, so it is round-tripped
against all five filter types rather than trusted.

Run with:  uv run --group docs python -m unittest tools.test_check_doc_assets
"""

import struct
import subprocess
import sys
import unittest
import zlib
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.check_doc_assets import (
    KNOWN_PLACEHOLDERS,
    PLACEHOLDER_GRID_PITCH,
    PLACEHOLDER_MAX_PIXELS,
    PLACEHOLDER_MIN_COVERAGE,
    PLACEHOLDER_MIN_GRID_LINE,
    PLACEHOLDER_PALETTE,
    decode_png_rgb,
    looks_like_placeholder_card,
    orphaned_capture_fences,
    placeholder_report,
    png_header,
    stale_placeholder_exceptions,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKER = REPO_ROOT / "tools" / "check_doc_assets.py"

# Measured across the four committed cards (rt-uv-gradient, rt-triangle-scene,
# rt-shader-tiles, rt-mirror-balls); the minimum of each score.
REAL_CARD_COVERAGE = 0.9944
REAL_CARD_GRID = 0.8770

# The closest *real* capture in the tree to the card's palette: a dark 512x512 figure,
# `assets/value_visualizers/reflective_tree.png`. It has no grid at all.
NEAREST_REAL_COVERAGE = 0.8492
NEAREST_REAL_GRID = 0.0

BACKGROUND, GRID, TEXT = PLACEHOLDER_PALETTE


def encode_png(rows: list[list[tuple[int, int, int]]], *, filter_type: int = 0, alpha: bool = True) -> bytes:
    """An 8-bit truecolour PNG, every scanline written with `filter_type`.

    Encoding (rather than hand-rolling bytes) is the point: the decoder has to *reverse*
    a real filter, so the test has to apply one.
    """
    height, width = len(rows), len(rows[0])
    bpp = 4 if alpha else 3
    stride = width * bpp

    raw = bytearray()
    for row in rows:
        for r, g, b in row:
            raw += bytes((r, g, b, 255)) if alpha else bytes((r, g, b))

    def paeth(a: int, b: int, c: int) -> int:
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        return a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)

    body = bytearray()
    prev = bytearray(stride)
    for y in range(height):
        line = raw[y * stride:(y + 1) * stride]
        out = bytearray(stride)
        for i in range(stride):
            left = line[i - bpp] if i >= bpp else 0
            up = prev[i]
            upleft = prev[i - bpp] if i >= bpp else 0
            if filter_type == 0:
                out[i] = line[i]
            elif filter_type == 1:
                out[i] = (line[i] - left) & 0xFF
            elif filter_type == 2:
                out[i] = (line[i] - up) & 0xFF
            elif filter_type == 3:
                out[i] = (line[i] - ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                out[i] = (line[i] - paeth(left, up, upleft)) & 0xFF
            else:
                raise ValueError(filter_type)
        body += bytes((filter_type,)) + out
        prev = line

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\x0a"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(body)))
            + chunk(b"IEND", b""))


def placeholder_card(size: int = 512, *, text: bool = True) -> list[list[tuple[int, int, int]]]:
    """Reproduce `writeNotSupportedPlaceholder`'s geometry: flat ground, 32px grid, text.

    Scores 1.00 / 0.95 where the committed cards scored 0.9944 / 0.8770. The gap is the
    third line: the real cards spell out a long label ("UV Gradient (RT not available)"),
    whose glyphs cut across more of each grid rule than this fixture's short lines do, and
    whose antialiasing frays the palette. Both are well inside the thresholds, which is all
    this fixture is asked to show -- the real numbers are pinned in `Thresholds` instead.
    """
    rows = [[BACKGROUND] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            if x % PLACEHOLDER_GRID_PITCH == 0 or y % PLACEHOLDER_GRID_PITCH == 0:
                rows[y][x] = GRID
    if text:
        # Three centred lines of 14pt monospace. Drawn as glyph-like rings on an 8px
        # advance rather than solid bars: real strokes only nick the grid rules they
        # cross, which is why the committed cards score 0.877 rather than 0.0.
        for line in range(3):
            top = size // 2 - 30 + line * 20
            left = size // 2 - 56
            for cell in range(14):
                x0 = left + cell * 8
                for y in range(top, top + 9):
                    for x in range(x0, x0 + 5):
                        edge = y in (top, top + 8) or x in (x0, x0 + 4)
                        if edge:
                            rows[y][x] = TEXT
    return rows


def gradient(size: int = 512) -> list[list[tuple[int, int, int]]]:
    """Stand-in for the render the card was covering for: a UV gradient."""
    scale = 255 / max(size - 1, 1)
    return [[(int(x * scale), int(y * scale), 128) for x in range(size)] for y in range(size)]


class PngDecoding(unittest.TestCase):
    def test_round_trips_every_filter_type(self):
        rows = [[(x * 7 % 256, y * 13 % 256, (x + y) % 256) for x in range(16)] for y in range(12)]
        expected = [bytes(px) for row in rows for px in row]
        for filter_type in range(5):
            with self.subTest(filter_type=filter_type):
                decoded = decode_png_rgb(encode_png(rows, filter_type=filter_type))
                self.assertIsNotNone(decoded)
                width, height, pixels = decoded
                self.assertEqual((width, height), (16, 12))
                self.assertEqual(pixels, expected)

    def test_decodes_truecolour_without_alpha(self):
        rows = [[(10, 20, 30), (40, 50, 60)]]
        decoded = decode_png_rgb(encode_png(rows, filter_type=4, alpha=False))
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded[2], [bytes((10, 20, 30)), bytes((40, 50, 60))])

    def test_header_reads_geometry(self):
        self.assertEqual(png_header(encode_png([[(0, 0, 0)] * 5] * 3))[:2], (5, 3))

    def test_rejects_non_png_and_truncated_input(self):
        for junk in (b"", b"not a png at all", b"\x89PNG\r\n\x1a\x0a" + b"\x00" * 10):
            with self.subTest(junk=junk[:12]):
                self.assertIsNone(decode_png_rgb(junk))
                self.assertIsNone(png_header(junk))

    def test_declines_images_larger_than_the_decode_bound(self):
        """Decoding is pure Python, so oversized assets must not be decoded at all."""
        side = 1024
        self.assertGreater(side * side, PLACEHOLDER_MAX_PIXELS)
        header = struct.pack(">IIBBBBB", side, side, 8, 6, 0, 0, 0)

        def chunk(kind, payload):
            return (struct.pack(">I", len(payload)) + kind + payload
                    + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

        oversized = b"\x89PNG\r\n\x1a\x0a" + chunk(b"IHDR", header) + chunk(b"IEND", b"")
        self.assertEqual(png_header(oversized)[:2], (side, side))
        self.assertIsNone(decode_png_rgb(oversized))


class PlaceholderDetection(unittest.TestCase):
    def _write(self, rows, name="figure.png", **kwargs):
        path = Path(self.tmp.name) / name
        path.write_bytes(encode_png(rows, **kwargs))
        return path

    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_flags_the_placeholder_card(self):
        card = self._write(placeholder_card())
        coverage, grid = placeholder_report(card)
        self.assertGreaterEqual(coverage, PLACEHOLDER_MIN_COVERAGE)
        self.assertGreaterEqual(grid, PLACEHOLDER_MIN_GRID_LINE)
        self.assertTrue(looks_like_placeholder_card(card))

    def test_does_not_flag_a_real_render(self):
        self.assertFalse(looks_like_placeholder_card(self._write(gradient())))

    def test_does_not_flag_a_dark_image_without_the_grid(self):
        """Palette drift alone is not enough -- the 32px rule is the structural half."""
        flat = [[BACKGROUND] * 512 for _ in range(512)]
        path = self._write(flat)
        coverage, grid = placeholder_report(path)
        self.assertGreaterEqual(coverage, PLACEHOLDER_MIN_COVERAGE)
        self.assertLess(grid, PLACEHOLDER_MIN_GRID_LINE)
        self.assertFalse(looks_like_placeholder_card(path))

    def test_does_not_flag_a_grid_that_is_not_the_card(self):
        """A 32px grid in unrelated colours -- a graph-paper figure -- is left alone."""
        rows = [[(240, 240, 240)] * 512 for _ in range(512)]
        for y in range(512):
            for x in range(512):
                if x % PLACEHOLDER_GRID_PITCH == 0 or y % PLACEHOLDER_GRID_PITCH == 0:
                    rows[y][x] = (200, 200, 200)
        self.assertFalse(looks_like_placeholder_card(self._write(rows)))

    def test_unreadable_asset_is_not_a_placeholder(self):
        missing = Path(self.tmp.name) / "nope.png"
        self.assertIsNone(placeholder_report(missing))
        self.assertFalse(looks_like_placeholder_card(missing))


class Thresholds(unittest.TestCase):
    """Both edges of the margin, against measurements from the real tree."""

    def test_thresholds_admit_the_four_committed_cards(self):
        self.assertLess(PLACEHOLDER_MIN_COVERAGE, REAL_CARD_COVERAGE)
        self.assertLess(PLACEHOLDER_MIN_GRID_LINE, REAL_CARD_GRID)

    def test_thresholds_exclude_the_nearest_real_capture(self):
        self.assertGreater(PLACEHOLDER_MIN_COVERAGE, NEAREST_REAL_COVERAGE)
        self.assertGreater(PLACEHOLDER_MIN_GRID_LINE, NEAREST_REAL_GRID)


class KnownPlaceholders(unittest.TestCase):
    """The grandfathered list is a debt. These tests are what stop it becoming furniture."""

    def test_every_exception_still_names_a_placeholder(self):
        stale = stale_placeholder_exceptions(REPO_ROOT)
        self.assertEqual(
            stale, [],
            "these figures are real captures now (or are gone) — delete their entries from "
            "KNOWN_PLACEHOLDERS in tools/check_doc_assets.py",
        )

    def test_an_unlisted_placeholder_fails_the_check(self):
        """The whole point: only the four recorded cards are tolerated, nothing else."""
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            assets = repo / "docs" / "assets"
            assets.mkdir(parents=True)
            (assets / "fake.png").write_bytes(encode_png(placeholder_card()))
            (assets / "real.png").write_bytes(encode_png(gradient()))
            (repo / "docs" / "page.md").write_text(
                "# Page\n\n![fake](assets/fake.png)\n\n![real](assets/real.png)\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "-A"], cwd=repo, check=True)

            done = subprocess.run(
                [sys.executable, str(CHECKER), "--docs-root", "docs", "--all"],
                cwd=repo, capture_output=True, text=True,
            )

        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("are placeholder cards", done.stdout)
        self.assertIn("assets/fake.png", done.stdout)
        self.assertNotIn("assets/real.png", done.stdout)


class OrphanedCaptureFences(unittest.TestCase):
    """The attribute block runs to the last `}` on the line, not the first.

    An alt string describing Kotlin code contains braces. `petri_nets.md:143`
    describes `.every(4) { it.fast(2) }`, and cutting the block at that inner brace
    dropped `embed=true` off the end -- so a figure the page shows perfectly well was
    reported as an orphaned capture fence, and the check failed on `main`.
    """

    def fence(self, attrs):
        return f"```diffstatic_runner {{{attrs}}}\n// body\n```\n"

    def test_embedding_fence_is_not_an_orphan(self):
        text = self.fence('screenshot="a/b.png" embed=true')
        self.assertEqual(orphaned_capture_fences(text), [])

    def test_a_brace_in_an_alt_string_does_not_hide_embed(self):
        text = self.fence(
            'screenshot="a/b.png" '
            'alt="a ring where .every(4) { it.fast(2) } doubles the onsets" embed=true')
        self.assertEqual(orphaned_capture_fences(text), [])

    def test_a_fence_with_no_embed_and_no_reference_is_an_orphan(self):
        text = self.fence('screenshot="a/b.png"')
        self.assertEqual(orphaned_capture_fences(text), ["a/b.png"])

    def test_a_hand_written_reference_excuses_a_missing_embed(self):
        text = self.fence('screenshot="a/b.png"') + "\n![a description](a/b.png)\n"
        self.assertEqual(orphaned_capture_fences(text), [])


if __name__ == "__main__":
    unittest.main()
