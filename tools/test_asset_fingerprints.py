"""Tests for `fingerprint_assets` in tools/build_zensical_docs.py: every page's links to the site's
own CSS and JS carry `?v=<content hash>`, so a CDN cannot serve a stale copy of a changed file."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build_zensical_docs import fingerprint_assets  # noqa: E402


class FingerprintAssets(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.site = Path(self._tmp.name)
        (self.site / "stylesheets").mkdir()
        (self.site / "assets" / "javascripts").mkdir(parents=True)
        (self.site / "stylesheets" / "extra.css").write_text("a { color: red }")
        (self.site / "assets" / "dot-grid.js").write_text("1;")
        (self.site / "assets" / "javascripts" / "bundle.0df4f2d0.min.js").write_text("2;")
        (self.site / "page").mkdir()
        self.page = self.site / "page" / "index.html"

    def tearDown(self):
        self._tmp.cleanup()

    def run_on(self, html: str) -> str:
        self.page.write_text(html)
        fingerprint_assets(self.site)
        return self.page.read_text()

    def test_local_css_and_js_get_a_content_hash(self):
        out = self.run_on('<link rel="stylesheet" href="../stylesheets/extra.css">'
                          '<script src="../assets/dot-grid.js"></script>')
        self.assertRegex(out, r'href="\.\./stylesheets/extra\.css\?v=[0-9a-f]{10}"')
        self.assertRegex(out, r'src="\.\./assets/dot-grid\.js\?v=[0-9a-f]{10}"')

    def test_the_hash_follows_the_content(self):
        first = self.run_on('<link rel="stylesheet" href="../stylesheets/extra.css">')
        (self.site / "stylesheets" / "extra.css").write_text("a { color: blue }")
        second = self.run_on('<link rel="stylesheet" href="../stylesheets/extra.css">')
        self.assertNotEqual(first, second)

    def test_left_alone(self):
        html = ('<script src="../assets/javascripts/bundle.0df4f2d0.min.js"></script>'
                '<link rel="stylesheet" href="../stylesheets/extra.css?v=already">'
                '<script src="https://cdn.example.com/x.js"></script>'
                '<link rel="stylesheet" href="../missing.css">'
                '<a href="../stylesheets/extra.css">not a stylesheet tag</a>')
        self.assertEqual(self.run_on(html), html)

    def test_a_ref_outside_the_site_is_not_read(self):
        outside = Path(self._tmp.name).parent / "outside-fingerprint-test.css"
        html = f'<link rel="stylesheet" href="../../{outside.name}">'
        self.assertEqual(self.run_on(html), html)


if __name__ == "__main__":
    unittest.main()
