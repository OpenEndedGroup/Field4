"""Regression guard for the prefix-aware link resolver in `check_doc_site_links.py`.

Background: `public/zensical.yml` publishes under `site_url: http://openendedgroup.com/Field4/`,
a path prefix. Under a prefix a relative reference that climbs one level too far is a 404; served
at `/` the same reference works, because `..` clamps at the server root. On 2026-09-16,
`rectsl_examples.md` shipped 50 such references -- every figure and video on the page -- and
nothing in the tree could see them, because `check_doc_assets.py` deliberately accepts a ref that
resolves at *either* convention and so cannot tell a correct one from a broken one.

The numbers below are real measurements taken on 2026-09-16 against the built site at
cb4377a6c + this branch: 155 built pages (of 287 `*.html`; the other 132 are vendored CodeMirror
files copied verbatim), 39943 local references extracted from rendered HTML, 1462 distinct
targets. Before the build-side fix, 50 distinct targets escaped the prefix; after it, 0 do.

Two properties get pinned hardest, because both were bugs in this guard's own first draft:

* a page served under its own name (`foo.html`) resolves relative refs against its *directory*,
  not against itself -- getting that wrong invented targets like `.../scala.html/clike.js`;
* the site root reached as `./..` (which the theme emits on 463 pages) normalises to `/Field4`
  with no trailing slash and is inside the site, not an escape.

Run with:  uv run --group docs python -m unittest tools.test_check_doc_site_links
"""

import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.check_doc_site_links import (
    KNOWN_UNRESOLVED,
    SOURCE_MIRROR_PREFIX,
    page_url,
    prefix_from_site_url,
    read_config,
    resolve,
    site_path,
    split_srcset,
    stale_known_unresolved,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKER = REPO_ROOT / "tools" / "check_doc_site_links.py"
CONFIG = REPO_ROOT / "public" / "zensical.yml"


class PrefixDerivation(unittest.TestCase):
    """The prefix comes from `site_url`, never from a constant."""

    def test_reads_the_published_prefix_from_the_real_config(self) -> None:
        site_url, site_dir = read_config(CONFIG)
        # Pinned because the whole guard is meaningless if the site stops being prefixed and
        # nobody notices that the guard silently started checking nothing interesting.
        self.assertEqual(prefix_from_site_url(site_url), "/Field4/")
        self.assertEqual(site_dir, "field4")

    def test_config_with_python_tags_is_parsed_without_importing_them(self) -> None:
        # public/zensical.yml carries `!!python/name:pymdownx...`, which plain safe_load refuses.
        self.assertIn("openendedgroup.com", read_config(CONFIG)[0])

    def test_normalises_every_spelling_of_a_prefix(self) -> None:
        for site_url, expected in [
            ("http://example.com/Field4/", "/Field4/"),
            ("http://example.com/Field4", "/Field4/"),
            ("http://example.com/", "/"),
            ("http://example.com", "/"),
            ("http://example.com/a/b/", "/a/b/"),
        ]:
            with self.subTest(site_url=site_url):
                self.assertEqual(prefix_from_site_url(site_url), expected)


class Resolution(unittest.TestCase):
    """Resolving a ref the way a browser does, including the `..` clamp."""

    def test_directory_url_page_resolves_against_its_own_directory(self) -> None:
        page_dir = "/Field4/rectsl_examples/"
        # The bug this guard was built for: one `../` is right, two leave the site.
        self.assertEqual(resolve(page_dir, "../assets/x.png"), "/Field4/assets/x.png")
        self.assertEqual(resolve(page_dir, "../../assets/x.png"), "/assets/x.png")
        # File-relative, i.e. what the source should say before zensical rebases it.
        self.assertEqual(resolve(page_dir, "assets/x.png"), "/Field4/rectsl_examples/assets/x.png")

    def test_over_long_climb_clamps_at_the_server_root(self) -> None:
        # Not an error: it is what the browser really requests, and the prefix test is what
        # notices it left the site.
        self.assertEqual(resolve("/Field4/a/", "../../../../x.png"), "/x.png")

    def test_query_and_fragment_are_stripped_and_percent_escapes_decoded(self) -> None:
        self.assertEqual(resolve("/Field4/", "a.png?v=2#top"), "/Field4/a.png")
        self.assertEqual(resolve("/Field4/", "a%20b.png"), "/Field4/a b.png")

    def test_trailing_slash_survives_normalisation(self) -> None:
        # A directory URL is how one page links to another; losing the slash would send
        # site_path looking for a file named after the directory.
        self.assertEqual(resolve("/Field4/a/", "../b/"), "/Field4/b/")

    def test_site_root_reached_as_dot_dot(self) -> None:
        # The theme's own most common reference, on 463 pages. Must not read as an escape.
        self.assertEqual(resolve("/Field4/page/", "./.."), "/Field4")


class PageUrl(unittest.TestCase):
    def test_index_html_is_served_as_its_directory(self) -> None:
        with TemporaryDirectory() as tmp:
            site = Path(tmp)
            self.assertEqual(page_url(site, site / "rectsl_examples" / "index.html", "/Field4/"),
                             "/Field4/rectsl_examples/")
            self.assertEqual(page_url(site, site / "index.html", "/Field4/"), "/Field4/")

    def test_a_named_html_file_resolves_against_its_directory_not_itself(self) -> None:
        # Regression: treating `scala.html` as a directory invented `.../scala.html/clike.js`.
        with TemporaryDirectory() as tmp:
            site = Path(tmp)
            self.assertEqual(
                page_url(site, site / "assets" / "mode" / "clike" / "scala.html", "/Field4/"),
                "/Field4/assets/mode/clike/",
            )


class SitePath(unittest.TestCase):
    def test_directory_url_maps_to_index_html(self) -> None:
        with TemporaryDirectory() as tmp:
            site = Path(tmp)
            self.assertEqual(site_path(site, "/Field4/", "/Field4/a/"), site / "a" / "index.html")
            self.assertEqual(site_path(site, "/Field4/", "/Field4/a.png"), site / "a.png")

    def test_prefix_root_maps_to_the_site_index(self) -> None:
        with TemporaryDirectory() as tmp:
            site = Path(tmp)
            self.assertEqual(site_path(site, "/Field4/", "/Field4/"), site / "index.html")


class Srcset(unittest.TestCase):
    def test_descriptors_are_dropped(self) -> None:
        self.assertEqual(split_srcset("a.png 1x, b.png 2x"), ["a.png", "b.png"])


class KnownUnresolvedLedger(unittest.TestCase):
    """The grandfathered list must not become a policy."""

    def test_every_entry_explains_itself(self) -> None:
        # An exception with no reason is an exception nobody can retire.
        for entry, reason in KNOWN_UNRESOLVED.items():
            with self.subTest(entry=entry):
                self.assertGreater(len(reason), 120, f"{entry} needs a real explanation")

    def test_an_entry_that_stops_reproducing_is_reported_stale(self) -> None:
        self.assertEqual(stale_known_unresolved(set(KNOWN_UNRESOLVED)), [])
        # Drop one from the observed set and it must be named for deletion.
        observed = set(KNOWN_UNRESOLVED)
        dropped = sorted(observed)[0]
        observed.remove(dropped)
        self.assertEqual(stale_known_unresolved(observed), [dropped])


class EndToEnd(unittest.TestCase):
    """The checker over a synthetic site, which is where its teeth are demonstrated."""

    def build_site(self, body: str, *, assets: tuple[str, ...] = ("assets/x.png",)) -> tuple[Path, Path]:
        tmp = Path(self.enterContext(TemporaryDirectory()))
        site = tmp / "field4"
        (site / "page").mkdir(parents=True)
        (site / "index.html").write_text("<html></html>", encoding="utf-8")
        (site / "page" / "index.html").write_text(body, encoding="utf-8")
        for asset in assets:
            target = site / asset
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"x")
        config = tmp / "zensical.yml"
        config.write_text("site_url: http://example.com/Field4/\nsite_dir: field4\n", encoding="utf-8")
        return config, site

    def run_checker(self, config: Path, site: Path, *extra: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), "--config", str(config), "--site-dir", str(site), *extra],
            capture_output=True, text=True, check=False, cwd=REPO_ROOT,
        )

    def test_a_correctly_framed_ref_passes(self) -> None:
        config, site = self.build_site('<img src="../assets/x.png">')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK", result.stdout)

    def test_an_over_climbing_ref_fails_and_names_itself(self) -> None:
        config, site = self.build_site('<img src="../../assets/x.png">')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("escape the site prefix", result.stdout)
        # The report has to carry the URL, how it was written, and the referring page --
        # a bare count is not actionable at 2am.
        self.assertIn("/assets/x.png", result.stdout)
        self.assertIn("../../assets/x.png", result.stdout)
        self.assertIn("page/index.html", result.stdout)

    def test_a_missing_target_inside_the_prefix_fails_separately(self) -> None:
        config, site = self.build_site('<img src="../assets/gone.png">')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("name nothing in the built site", result.stdout)

    def test_external_and_in_page_refs_are_not_local_references(self) -> None:
        config, site = self.build_site(
            '<a href="https://example.com/a">x</a><img src="data:image/png;base64,AA">'
            '<a href="#section">y</a><a href="//cdn.example.com/a.js">z</a>'
        )
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("4 external/in-page ref(s) skipped", result.stdout)

    def test_poster_is_checked_as_rendered_not_as_a_convention(self) -> None:
        # The guard reads rendered HTML, so it needs no knowledge of which attributes zensical
        # rebases -- a `poster` that resolves is fine, one that does not is reported.
        config, site = self.build_site('<video src="../assets/x.png" poster="../assets/x.png">')
        self.assertEqual(self.run_checker(config, site).returncode, 0)
        config, site = self.build_site('<video src="../assets/x.png" poster="assets/x.png">')
        self.assertEqual(self.run_checker(config, site).returncode, 1)

    def test_the_un_prefixed_deployment_can_be_checked_too(self) -> None:
        # Same site, served at the root: the over-climb now clamps onto a real file, which is
        # exactly why testing at `/` hid this class of bug for as long as it did.
        config, site = self.build_site('<img src="../../assets/x.png">')
        self.assertEqual(self.run_checker(config, site).returncode, 1)
        rooted = self.run_checker(config, site, "--prefix", "/")
        self.assertEqual(rooted.returncode, 0, rooted.stdout)
        self.assertIn("'/'", rooted.stdout)

    def test_an_empty_site_is_an_error_not_a_pass(self) -> None:
        # A guard whose population is zero must not report success.
        tmp = Path(self.enterContext(TemporaryDirectory()))
        site = tmp / "field4"
        site.mkdir(parents=True)
        config = tmp / "zensical.yml"
        config.write_text("site_url: http://example.com/Field4/\nsite_dir: field4\n", encoding="utf-8")
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("nothing to check", result.stderr)

    def test_population_is_stated_on_every_run(self) -> None:
        config, site = self.build_site('<img src="../assets/x.png">')
        result = self.run_checker(config, site)
        self.assertIn("population:", result.stdout)
        self.assertIn("built page(s) under prefix", result.stdout)

    def test_an_absent_source_mirror_is_a_note_and_a_present_one_gates(self) -> None:
        ref = f'<a href="../{SOURCE_MIRROR_PREFIX}Foo/">x</a>'
        config, site = self.build_site(ref)
        absent = self.run_checker(config, site)
        self.assertEqual(absent.returncode, 0, absent.stdout)
        self.assertIn("did not generate the source mirror", absent.stdout)
        # Present but incomplete is a real failure, not a configuration fact.
        (site / SOURCE_MIRROR_PREFIX).mkdir(parents=True)
        present = self.run_checker(config, site)
        self.assertEqual(present.returncode, 1, present.stdout)
        self.assertIn("name nothing in the built site", present.stdout)


class AgainstTheRealBuiltSite(unittest.TestCase):
    """Skipped unless the site has been built, so the suite stays fast and offline."""

    def test_the_real_site_has_no_unknown_unresolved_reference(self) -> None:
        site = REPO_ROOT / "public" / "field4"
        if not (site / "index.html").is_file():
            self.skipTest(
                "no built site at public/field4 — run "
                "`uv run --group docs python tools/build_zensical_docs.py` first"
            )
        result = subprocess.run(
            [sys.executable, str(CHECKER), "--summary"],
            capture_output=True, text=True, check=False, cwd=REPO_ROOT,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # Assert a floor near the measured population, so a build that silently checks almost
        # nothing cannot report success. Measured 2026-09-16: 155 pages, 39943 refs, 1462 targets.
        self.assertIn("built page(s) under prefix", result.stdout)
        pages = int(result.stdout.split("checked ", 1)[1].split(" ", 1)[0])
        self.assertGreater(pages, 140, f"only {pages} pages checked; expected ~155")


if __name__ == "__main__":
    unittest.main()
