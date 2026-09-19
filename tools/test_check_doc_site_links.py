"""Regression guard for the prefix-aware link resolver in `check_doc_site_links.py`.

Background: `public/zensical.yml` publishes under `site_url: http://openendedgroup.com/Field4/`,
a path prefix. Under a prefix a relative reference that climbs one level too far is a 404; served
at `/` the same reference works, because `..` clamps at the server root. On 2026-09-16,
`rectsl_examples.md` shipped 50 such references -- every figure and video on the page -- and
nothing in the tree could see them, because `check_doc_assets.py` deliberately accepts a ref that
resolves at *either* convention and so cannot tell a correct one from a broken one.

The numbers below are real measurements, and each carries the build that produced it — which
matters more than it looks, because the source mirror (`reference/source/**`) is generated per
build and is the largest page family, so the same site measures two completely different ways.

* `--no-source-pages`, 2026-09-16 at cb4377a6c + that branch: **155 built pages of 287 `*.html`**
  (the other 132 are vendored CodeMirror files copied verbatim), 39943 local references, 1462
  distinct targets. Before the build-side fix 50 distinct targets escaped the prefix; after it 0.
* `--no-source-pages`, 2026-09-17 at this branch: **158 of 290**, 1483 distinct targets, of which
  1120 resolve — the shortfall is 360 mirror targets the build did not generate.
* default build (mirror present), same tree, same night: **613 of 745**, 455 of the 613 being the
  mirror, 121655 local references, **1575 of 1575** distinct targets resolving.

So "607 of 739" and "155 of 287" were never in conflict; they are the two flags. The guard now
prints a `build shape:` line so a quoted population can never again omit which one it was.

Two properties get pinned hardest, because both were bugs in this guard's own first draft:

* a page served under its own name (`foo.html`) resolves relative refs against its *directory*,
  not against itself -- getting that wrong invented targets like `.../scala.html/clike.js`;
* the site root reached as `./..` (which the theme emits on 463 pages) normalises to `/Field4`
  with no trailing slash and is inside the site, not an escape.

Run with:  uv run --group docs python -m unittest tools.test_check_doc_site_links
"""

import re
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from tools.check_doc_site_links import (
    KNOWN_MISSING_FRAGMENTS,
    KNOWN_UNRESOLVED,
    REF_ATTR,
    SKIP_PREFIXES,
    SOURCE_MIRROR_PREFIX,
    code_spans,
    element_ids,
    fragment_of,
    inside_code,
    page_url,
    prefix_from_site_url,
    read_config,
    resolve,
    site_path,
    split_srcset,
    stale_known_missing_fragments,
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
    """The grandfathered list must not become a policy — and it is empty, which is the goal state.

    All three entries recorded on 2026-09-16 were fixed at source on 2026-09-17, so the real dict
    is now `{}`. The mechanism is still tested, against a synthetic ledger rather than the real
    one: an empty dict makes `stale_known_unresolved` trivially return `[]`, and a test that read
    that as a pass would have stopped covering anything the moment the list emptied. That is
    exactly how this test broke when the list emptied, which is the right way for it to break.
    """

    def test_the_ledger_is_empty(self) -> None:
        # Stated rather than implied: the correct state of a grandfather list is no entries, and
        # a future entry should have to change this line deliberately.
        self.assertEqual(KNOWN_UNRESOLVED, {})

    def test_every_entry_explains_itself(self) -> None:
        # An exception with no reason is an exception nobody can retire.
        for entry, reason in KNOWN_UNRESOLVED.items():
            with self.subTest(entry=entry):
                self.assertGreater(len(reason), 120, f"{entry} needs a real explanation")

    def test_an_entry_that_stops_reproducing_is_reported_stale(self) -> None:
        synthetic = {"/a": "x" * 130, "/b": "y" * 130}
        with mock.patch.dict(
            "tools.check_doc_site_links.KNOWN_UNRESOLVED", synthetic, clear=True
        ):
            self.assertEqual(stale_known_unresolved(set(synthetic)), [])
            self.assertEqual(stale_known_unresolved({"/a"}), ["/b"])
            self.assertEqual(stale_known_unresolved(set()), ["/a", "/b"])


class DisplayedCodeIsNotMarkup(unittest.TestCase):
    """`<pre>`/`<code>` interiors are masked, and the mask is checked for over-reach.

    A page that shows HTML shows it escaped, so `&lt;iframe src="/x"` creates no element — but
    the attribute text survives with its quotes, and a regex over raw HTML matches it. That is
    what put `/extensions/remote_ar/index.html` (an `ar_reference.md` fence) and eleven
    `reference/source/**` refs on this guard's report. Measured on the full build of 2026-09-17:
    51 of 146724 attribute matches sit inside a code element, 39 already skipped as
    external/in-page, and **0** resolved to a real file — so the mask costs no coverage.
    """

    def test_code_spans_pair_their_own_tags(self) -> None:
        html = '<pre><code>a</code></pre><a href="real">x</a><code>b</code>'
        spans = code_spans(html)
        # `<pre>…</pre>` and the standalone `<code>…</code>`; the `<a>` between them is not in one.
        self.assertTrue(inside_code(spans, html.index("a</code>")))
        self.assertTrue(inside_code(spans, html.index("b</code>")))
        self.assertFalse(inside_code(spans, html.index('href="real"')))

    def test_an_inline_code_ref_is_not_mistaken_for_markup(self) -> None:
        html = '<p>write <code>src="../../assets/x.png"</code> like this</p>'
        match = REF_ATTR.search(html)
        self.assertIsNotNone(match)
        self.assertTrue(inside_code(code_spans(html), match.start()))

    def test_a_pre_is_not_closed_by_a_code_close_tag(self) -> None:
        # The backreference in CODE_REGION is what keeps the two tag names paired. Without it a
        # `<pre …><code>…</code></pre>` would end at the `</code>`, leaving the tail unmasked.
        html = '<pre class="f"><code>&lt;img src="../../escapes.png"&gt;</code></pre>'
        match = REF_ATTR.search(html)
        self.assertIsNotNone(match)
        self.assertTrue(inside_code(code_spans(html), match.start()))
        self.assertEqual(len(re.findall(r"<pre", html)), 1)


class FragmentTests(unittest.TestCase):
    """The anchor half of a ref, which until 2026-09-18 no guard in this tree could see.

    `"#"` sat in `SKIP_PREFIXES` and `resolve()` did `ref.split("#", 1)[0]`, so a renamed heading
    broke every `](#…)` into it silently. Measured on the `--no-source-pages` build of that day,
    158 built pages of 290 `*.html`: **20655** refs carry a fragment (8740 bare `#frag`, 11915
    `path#frag`), 10996 of them at a target page the build wrote, giving **4418 distinct
    (page, fragment) pairs** now checked where 0 were checked before. Exactly one names no `id`
    and it is the theme's `404.html#__skip`.
    """

    def test_the_hash_prefix_is_no_longer_skipped_outright(self) -> None:
        # Asserted on the constant, not inferred from behaviour: re-adding `"#"` here is the one
        # edit that would make the whole family invisible again, and it would look harmless.
        self.assertNotIn("#", SKIP_PREFIXES)

    def test_a_fragment_is_everything_after_the_first_hash(self) -> None:
        self.assertEqual(fragment_of("page/#a-heading"), "a-heading")
        self.assertEqual(fragment_of("#a-heading"), "a-heading")
        self.assertEqual(fragment_of("page/"), "")
        # Query-then-fragment is the only legal order, so a `?` inside the fragment is part of it.
        self.assertEqual(fragment_of("page/?q=1#frag"), "frag")
        self.assertEqual(fragment_of("page/#frag?not-a-query"), "frag?not-a-query")
        # Percent-encoded, because that is how a heading with punctuation is linked.
        self.assertEqual(fragment_of("#a%20b"), "a b")
        # `href="foo#"` names no anchor.
        self.assertEqual(fragment_of("foo.html#"), "")

    def test_element_ids_reads_the_rendered_anchor_names(self) -> None:
        # The slug exists only in the built HTML: it is derived from the heading's *text*, so it
        # changes when the wording changes, which is why the markdown cannot answer this.
        html = '<h2 id="the-dispatcher">D</h2><p id=\'single\'>x</p><a id="x-y">a</a>'
        ids = element_ids(html)
        self.assertIn("the-dispatcher", ids)
        self.assertIn("x-y", ids)
        # Single-quoted attributes are not matched, and that is stated rather than assumed: the
        # theme emits double quotes throughout (counted 0 single-quoted `id=` over 158 pages).
        self.assertNotIn("single", ids)

    def test_a_fragment_is_not_confused_with_a_path(self) -> None:
        # `resolve` still drops the fragment, deliberately — the path check and the fragment
        # check ask different questions about the same ref.
        self.assertEqual(resolve("/Field4/page/", "../other/#frag"), "/Field4/other/")


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
        # This used to assert "4 external/in-page ref(s) skipped", lumping the bare `#section`
        # in with the three off-site refs. The two are now counted apart, because they are not
        # the same statement: an off-site ref is never checked, an in-page one names no *file*
        # but does name an `id` that has to exist. `#section` is a real heading here, so this
        # still passes — with 3 skipped and 1 in-page.
        config, site = self.build_site(
            '<a href="https://example.com/a">x</a><img src="data:image/png;base64,AA">'
            '<a href="#section">y</a><a href="//cdn.example.com/a.js">z</a>'
            '<h2 id="section">S</h2>'
        )
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("3 external ref(s) skipped", result.stdout)
        self.assertIn("1 in-page #fragment ref(s)", result.stdout)

    def test_poster_is_checked_as_rendered_not_as_a_convention(self) -> None:
        # The guard reads rendered HTML, so it needs no knowledge of which attributes zensical
        # rebases -- a `poster` that resolves is fine, one that does not is reported.
        config, site = self.build_site('<video src="../assets/x.png" poster="../assets/x.png">')
        self.assertEqual(self.run_checker(config, site).returncode, 0)
        config, site = self.build_site('<video src="../assets/x.png" poster="assets/x.png">')
        self.assertEqual(self.run_checker(config, site).returncode, 1)

    def test_an_escaped_iframe_shown_in_a_fence_is_not_a_reference(self) -> None:
        # Exactly the ar_reference.md and reference/source/** shape: the `<` is escaped so no
        # element exists, but `src="…"` survives verbatim inside the code element.
        config, site = self.build_site(
            '<img src="../assets/x.png">'
            '<pre class="code-diff-fence-static"><code>display("remote-ar", """\n'
            '&lt;iframe src="/extensions/remote_ar/index.html?debug=1"&gt;&lt;/iframe&gt;\n'
            '""")</code></pre>'
        )
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("displayed code masked: 1 attribute match(es)", result.stdout)
        self.assertIn("0 would have resolved", result.stdout)

    def test_the_mask_fails_when_it_would_hide_a_real_reference(self) -> None:
        # A `src=` inside a code element that lands on a real file is not displayed source. If
        # the mask ever starts swallowing those, it must say so rather than shrink in silence.
        config, site = self.build_site('<pre><code><img src="../assets/x.png"></code></pre>')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("inside <pre>/<code> resolve to a real file", result.stdout)

    def test_a_real_broken_ref_outside_code_still_gates_on_the_same_page(self) -> None:
        config, site = self.build_site(
            '<pre><code>&lt;img src="/extensions/x"&gt;</code></pre>'
            '<img src="../../assets/x.png">'
        )
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("escape the site prefix", result.stdout)

    def test_the_build_shape_line_names_the_mirror_and_the_flag(self) -> None:
        # A population quoted without the source mirror's state is not a measurement: the same
        # site is 613 of 745 pages with it and 158 of 290 without.
        config, site = self.build_site('<img src="../assets/x.png">')
        absent = self.run_checker(config, site)
        self.assertIn("source mirror ABSENT (--no-source-pages", absent.stdout)
        (site / SOURCE_MIRROR_PREFIX).mkdir(parents=True)
        (site / SOURCE_MIRROR_PREFIX / "index.html").write_text("<html></html>", encoding="utf-8")
        present = self.run_checker(config, site)
        self.assertIn("source mirror PRESENT", present.stdout)
        self.assertIn(f"1 of the 3 checked page(s) are {SOURCE_MIRROR_PREFIX}", present.stdout)

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

    def test_a_bare_fragment_whose_heading_was_renamed_fails(self) -> None:
        # The half of the family that had no guard at all: `"#"` was in SKIP_PREFIXES, so this
        # ref was discarded before anything looked at it.
        config, site = self.build_site('<a href="#gone">y</a><h2 id="here">H</h2>')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("name no id on a page that does exist", result.stdout)
        self.assertIn("page/index.html#gone", result.stdout)

    def test_a_cross_page_fragment_whose_heading_was_renamed_fails(self) -> None:
        # The other half: the target file resolves, so `resolve()`'s `split("#", 1)[0]` made this
        # indistinguishable from a correct ref. Planted on the real site by renaming
        # `reference/imaging/index.html`'s `id="imaging-kt-img"`, which 43 refs on 5 pages point
        # at; the guard at 4546d72b5 exited 0 on it and this one exits 1.
        config, site = self.build_site('<a href="../#gone">y</a>')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("index.html#gone", result.stdout)

    def test_a_fragment_that_exists_passes_and_is_counted(self) -> None:
        config, site = self.build_site(
            '<a href="#here">y</a><a href="../#root">z</a><h2 id="here">H</h2>'
        )
        (site / "index.html").write_text('<h1 id="root">R</h1>', encoding="utf-8")
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout)
        # The count is the point of the branch: a widened guard whose new population is unstated
        # has reproduced the defect it fixes.
        self.assertIn("2 ref(s) carry a #fragment (1 bare, 1 path#fragment)", result.stdout)
        self.assertIn("2 distinct (page, fragment) pair(s), 0 naming no id", result.stdout)

    def test_a_fragment_on_a_target_that_does_not_resolve_is_reported_once(self) -> None:
        # A broken path is the path check's failure. Reporting the fragment too would make the
        # fragment census a function of the other check's failures.
        config, site = self.build_site('<a href="../gone/#anything">y</a>')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("name nothing in the built site", result.stdout)
        self.assertNotIn("name no id on a page that does exist", result.stdout)

    def test_a_fragment_inside_displayed_code_is_not_a_reference(self) -> None:
        config, site = self.build_site(
            '<pre><code>&lt;a href="#not-a-real-anchor"&gt;</code></pre>'
        )
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("name no id", result.stdout)

    def test_an_empty_fragment_names_nothing_and_is_not_checked(self) -> None:
        config, site = self.build_site('<a href="#">top</a>')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("0 ref(s) carry a #fragment", result.stdout)

    def test_the_fragment_ledger_is_graded_backwards_on_the_real_site_only(self) -> None:
        # Same rule as KNOWN_UNRESOLVED: an entry that no longer reproduces must fail, but only
        # when the site being checked is the one the ledger is about. Over a fixture (or at
        # another prefix) every entry would look stale.
        self.assertEqual(
            stale_known_missing_fragments(set(KNOWN_MISSING_FRAGMENTS)),
            [],
            "an entry graded against its own keys can never be stale",
        )
        self.assertEqual(
            stale_known_missing_fragments(set()), sorted(KNOWN_MISSING_FRAGMENTS)
        )
        config, site = self.build_site('<img src="../assets/x.png">')
        result = self.run_checker(config, site)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("no longer reproduce", result.stdout)

    def test_every_grandfathered_fragment_carries_an_owner_note(self) -> None:
        # An exception without a named owner is a widening with extra steps.
        self.assertTrue(KNOWN_MISSING_FRAGMENTS, "the ledger's one real entry went missing")
        for key, note in KNOWN_MISSING_FRAGMENTS.items():
            with self.subTest(key=key):
                self.assertIn("#", key)
                self.assertGreater(len(note), 80, f"{key} has no owner note")
                self.assertIn("owner", note.lower())

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
