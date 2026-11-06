"""`max-width=` on a docs fence downscales the finished still, so a capture fits the asset budget.

The native-window physarum figure (6291b6ff9) had to be recaptured 2.6 MB -> 0.9 MB by changing
the render target by hand; `max-width=` is the runner's own downscale path for such captures.
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
PAGE = REPO_ROOT / "public" / "docs" / "alife_examples.md"


def _options(attrs: str) -> "docs_hooks.RunnerOptions":
    return docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner",
        open_line="```diffstatic_runner {data-lang=kotlin " + attrs + "}",
        page_path=PAGE,
        line_number=1,
        block_index=0,
    )


class MaxWidthTests(unittest.TestCase):

    def test_parse_and_digest(self):
        native = _options('capture="native-window" graphics-context="true" max-width="1600" screenshot="assets/x.png"')
        self.assertEqual(1600, native.max_width)
        self.assertEqual(1600, docs_hooks.options_digest_payload(native)["maxWidth"])
        plain = _options('capture="native-window" graphics-context="true" screenshot="assets/x.png"')
        self.assertIsNone(plain.max_width)
        # Absent when unset, so every existing fence keeps its digest.
        self.assertNotIn("maxWidth", docs_hooks.options_digest_payload(plain))

    def test_rejected_where_it_cannot_mean_anything(self):
        for attrs in ('capture="box" max-width="800" screenshot="assets/x.png"',
                      'capture="native-window" graphics-context="true" max-width="800" video="assets/x.mp4"',
                      'capture="native-window" graphics-context="true" max-width="12" screenshot="assets/x.png"',
                      'capture="native-window" graphics-context="true" max-width="wide" screenshot="assets/x.png"'):
            with self.subTest(attrs=attrs), self.assertRaises(RuntimeError):
                _options(attrs)

    def test_downscales_a_real_png_and_leaves_a_narrow_one_alone(self):
        from PIL import Image

        options = _options('capture="native-window" graphics-context="true" max-width="400" screenshot="assets/x.png"')
        with tempfile.TemporaryDirectory() as tmp:
            wide = Path(tmp) / "wide.png"
            Image.radial_gradient("L").resize((1200, 600)).convert("RGB").save(wide)
            self.assertTrue(docs_hooks.downscale_screenshot(screenshot_path=wide, options=options))
            with Image.open(wide) as image:
                self.assertEqual((400, 200), image.size)
            narrow = Path(tmp) / "narrow.png"
            Image.new("RGB", (300, 100), (10, 20, 30)).save(narrow)
            before = narrow.read_bytes()
            self.assertFalse(docs_hooks.downscale_screenshot(screenshot_path=narrow, options=options))
            self.assertEqual(before, narrow.read_bytes())


if __name__ == "__main__":
    unittest.main()
