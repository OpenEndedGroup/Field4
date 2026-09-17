#!/usr/bin/env python3
"""Lint: every local reference on every *built* page must resolve under the site's path prefix.

Why this exists, and why reading the markdown could not find it
---------------------------------------------------------------
``public/zensical.yml`` publishes to ``site_url: http://openendedgroup.com/Field4/`` — a path
prefix, not a bare host. Under a prefix, a relative reference that climbs one level too far is
a 404. Served at ``/`` the same reference *works*, because ``..`` clamps at the server root:
``/page/../../assets/x.png`` and ``/page/../assets/x.png`` both land on ``/assets/x.png``. So
the whole failure mode is invisible to anyone testing at the root, and invisible to anyone
reading the source, because the source is not what the browser reads.

The source is not what the browser reads for a second reason. Zensical re-bases relative
``href``/``src`` inside *raw HTML* exactly as it re-bases markdown links — see
``public/docs_hooks.py:page_directory_depth``, which measured this. The house convention is
therefore to write a raw-HTML ref **file-relative to the docs root** (``assets/foo.png``) and
let the build add the ``../`` for the pretty URL. A page that instead writes
``../assets/foo.png`` gets it added *again* and escapes the site. Two pages in this tree used
opposite conventions and looked equally plausible in the markdown; only the rendered HTML
tells them apart.

So this guard extracts references from the **rendered HTML in the built site**, resolves each
one as a browser would from the page's own published URL, and requires that it land on a file
that exists inside the prefix.

Two checks, and the difference matters
--------------------------------------
* **Escape** — the resolved URL falls outside the configured prefix. This is
  deployment-independent: it is a 404 under *any* non-empty prefix, and at the root it is a
  silently clamped near-miss that will break the day the site moves. Reported even when the
  target happens to exist at the clamped location.
* **Missing** — the resolved URL is inside the prefix but names nothing in the built site.

Relation to ``tools/check_doc_assets.py``
-----------------------------------------
That script answers a different question — "is the asset committed to git?" — and to do so it
deliberately accepts a ref that resolves at *either* convention (see its ``resolve_candidates``).
That makes it structurally unable to see this family: ``assets/x.png`` and ``../assets/x.png``
both satisfy it, though at most one can be right in the rendered HTML. The two guards are
complements, not duplicates: it checks the source tree, this checks the built site.

What this guard cannot see
--------------------------
* References built at runtime by JavaScript, and references inside ``.css``/``.js`` assets
  (``url(...)`` in a stylesheet). Only HTML attributes are parsed.
* Whether an asset's *content* is right — a committed placeholder card resolves at 200.
  ``check_doc_assets.py`` owns that question.
* Anything on a page that is not built. 345 markdown files are copied into the site verbatim
  by ``build_zensical_docs.py:copy_workspace_markdown`` and are not pages; they have no
  rendered HTML, so nothing in them is checked.
* External links. Nothing off-site is fetched — there is no network guarantee in a docs build.
* A ref that is *absolute with the prefix baked in* (``/Field4/assets/...``) is checked
  against this prefix and passes, but such a ref only works under this exact prefix. They are
  emitted by the theme from ``site_url``, so they move when it does; they are counted and
  reported under ``--summary`` so the count cannot drift unnoticed.
* Anything inside a ``<pre>`` or ``<code>`` element. That is displayed source, not markup —
  see below.

Code is not markup
------------------
A docs page that *shows* HTML shows it escaped: the ``diffstatic`` fence treatment renders
``<iframe src="/extensions/remote_ar/index.html?debug=1">`` as
``<pre …><code>&lt;iframe src="/extensions/…"``. The ``<`` is escaped, so no element is
created and no browser ever requests it — but the attribute text survives verbatim, quotes and
all, so a regex over the raw HTML matches ``src="…"`` inside the code block and reports a
reference that does not exist.

This was mis-diagnosed once, in the commit that first made it a category (``d6d164822``): it
said a source that builds HTML in a string literal "is emitted live rather than escaped, so an
unexpanded ``$src`` reaches the browser as a real anchor", and proposed escaping in
``generate_source_pages`` as the fix. The count was right and the cause was not. Read back out
of the built site on 2026-09-17, ``reference/source/jvmMain/afield/outputs/index.html`` contains
``<pre class="code-diff-fence-static" …><code>… &lt;iframe … src="/keyframe_browser.html?root=$token"``
— already escaped, already inert. Nothing was reaching the browser; the extractor was reading
displayed code. So the fix is here, in the extractor, and ``generate_source_pages``
(which is ``afield.docs.SourcePageGenerator``, emitting ``diffstatic`` fences) needs no change.

Measured on the full build the same night, over 613 pages: of 146724 ``href``/``src``/``poster``/
``srcset`` attribute matches in the raw HTML, 51 sit inside a ``<pre>`` or ``<code>``. 39 of
those were already skipped as external or in-page, **0 resolved to a real file**, and 12 were
reported as broken references — the 11 the base commit counted, plus one on ``ar_reference.md``.
Masking code regions therefore costs this guard no coverage at all, which is asserted rather
than assumed: :func:`main` resolves the masked refs too and fails if any of them *would* have
landed on a real file inside the prefix, because that would mean the mask had swallowed a real
reference.

Exit status is non-zero when any reference escapes the prefix or is missing, so this can gate
a docs build (it is wired into ``tools/build_zensical_docs.py`` under ``--strict``) or a test.
"""
from __future__ import annotations

import argparse
import posixpath
import re
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote, urlsplit

import yaml

# `href`, `src` and `poster` are the three ref-carrying attributes this theme actually emits:
# counted over the full built site, 48129 href / 3070 src / 39 poster and no `srcset` or
# `data-src`. `srcset` is matched anyway so that adding one responsive image later cannot
# quietly open a hole; its comma/descriptor syntax is split below.
REF_ATTR = re.compile(r'\b(href|src|poster|srcset)\s*=\s*"([^"]*)"', re.IGNORECASE)

# Displayed source: everything between a `<pre>`/`<code>` and its close. Non-greedy and
# non-nested on purpose — these elements do not nest in this theme's output (the fence
# treatment emits `<pre …><code>…</code></pre>` and inline code emits `<code>…</code>`), and a
# backreference keeps the two tag names paired so a `<pre>` is not closed by a `</code>`.
CODE_REGION = re.compile(r"<(pre|code)\b[^>]*>.*?</\1>", re.IGNORECASE | re.DOTALL)

# Schemes and forms that do not name a file in this site. `//` is protocol-relative, so it is
# off-site too. A bare `#fragment` is the same page.
SKIP_PREFIXES = ("http://", "https://", "data:", "mailto:", "tel:", "javascript:", "#", "//")


def code_spans(html: str) -> list[tuple[int, int]]:
    """The `(start, end)` character ranges of every `<pre>`/`<code>` element in `html`."""
    return [match.span() for match in CODE_REGION.finditer(html)]


def inside_code(spans: list[tuple[int, int]], index: int) -> bool:
    """Whether `index` falls inside one of `spans`."""
    return any(start <= index < end for start, end in spans)


def prefix_from_site_url(site_url: str) -> str:
    """The URL path a built page is served under, normalised to `/…/` (or `/` for no prefix).

    Read from `site_url` rather than hard-coded so that moving the site — or dropping the
    prefix entirely — re-points the guard instead of falsifying it.
    """
    path = urlsplit(site_url.strip()).path or "/"
    if not path.startswith("/"):
        path = "/" + path
    if not path.endswith("/"):
        path += "/"
    return path


class _TolerantLoader(yaml.SafeLoader):
    """SafeLoader that parses `public/zensical.yml`'s Python tags without executing them.

    The config carries `!!python/name:pymdownx.superfences.fence_code_format` (:304), so a plain
    `safe_load` raises and `tools/build_zensical_docs.py:450` uses the full `yaml.Loader` instead.
    A guard has no business importing whatever a config names, and it only wants two scalars, so
    unknown tags resolve to None here rather than to an imported object.
    """


_TolerantLoader.add_multi_constructor("", lambda loader, suffix, node: None)


def read_config(config_yml: Path) -> tuple[str, str]:
    """`(site_url, site_dir)` from the zensical config."""
    config = yaml.load(config_yml.read_text(encoding="utf-8"), Loader=_TolerantLoader) or {}
    return str(config.get("site_url", "") or "/"), str(config.get("site_dir", "") or "site")


# All three entries recorded here on 2026-09-16 were fixed on 2026-09-17 and are gone. The
# mechanism stays, because it is the half of a grandfather list that usually gets left out:
# `stale_known_unresolved` fails when a listed entry stops reproducing, so an exception cannot
# outlive its cause. What the list held, and where each went:
#
#   `/offline.html`                      — 137 refs on 17 authored pages, server-absolute and in
#                                          the wrong case. Fixed at source; `offline.html?file=`
#                                          is now viewer-relative and guarded by
#                                          `tools/check_doc_offline_links.py`.
#   `/extensions/remote_ar/index.html`   — never a live reference. It is the `src` of an escaped
#                                          `&lt;iframe` shown inside a `diffstatic` fence on
#                                          `ar_reference.md`, and is now masked with all other
#                                          displayed code. The page also says, in prose, that
#                                          `/extensions/…` is served by a running Field.
#   `/Field4/reference/3,8`              — `patterns.md`'s `pat("[bd cp](3,8) hh!2")` sample,
#                                          published as a paragraph because its KDoc indent was
#                                          one space short of a code block. Fixed in
#                                          `TidalPatterns.kt` and regenerated.
#
# An empty dict is the correct state, not a missing feature: anything not listed here fails now,
# and nothing is listed.
KNOWN_UNRESOLVED: dict[str, str] = {}

# The source mirror (`reference/source/**`) is the one generated docs tree that is not committed:
# `build_zensical_docs.py:generate_source_pages` renders it per build and needs a compiled Kotlin
# tree, so `--no-source-pages` (or a machine without one) legitimately produces a site with none of
# it. The committed `reference/*.md` pages link into it regardless, so its absence shows up as
# several hundred missing targets that say nothing about the page that referenced them. Treated as a
# build-configuration fact when the whole subtree is absent, and as ordinary failures when it exists
# — so a mirror that is present but incomplete still gates.
SOURCE_MIRROR_PREFIX = "reference/source/"


def stale_known_unresolved(unresolved: set[str]) -> list[str]:
    """Entries in `KNOWN_UNRESOLVED` that no longer reproduce, and so should be deleted."""
    return sorted(entry for entry in KNOWN_UNRESOLVED if entry not in unresolved)


def split_srcset(value: str) -> list[str]:
    """The URLs in a `srcset`, dropping the width/density descriptors."""
    return [part.strip().split()[0] for part in value.split(",") if part.strip()]


def page_url(site_dir: Path, page: Path, prefix: str) -> str:
    """The published URL *directory* a page's relative refs resolve against.

    With `use_directory_urls` (set by `build_zensical_docs.py`) a page lives at
    `<stem>/index.html` and is served as `/<prefix>/<stem>/`, so its refs resolve against that
    directory — which is one level deeper than the markdown source sat. That one level is the
    entire bug this guard exists for.

    A file served under its own name (`foo.html`, which the copied-asset tree contains) is *not*
    a directory: its relative refs resolve against the directory holding it. Getting this wrong
    invents targets like `…/scala.html/clike.js`, which is how it was caught.
    """
    relative = page.relative_to(site_dir).as_posix()
    head, _, _name = relative.rpartition("/")
    return prefix + (head + "/" if head else "")


def resolve(page_url_dir: str, ref: str) -> str:
    """The absolute server path a browser would request for `ref` seen on `page_url_dir`.

    `posixpath.normpath` on an absolute path is exactly the browser's rule *including* the
    clamp: an over-long run of `..` stops at `/` rather than going negative. That is wanted —
    the clamped result is what the browser really asks for, and the prefix test below is what
    notices it left the site.
    """
    clean = unquote(ref.split("#", 1)[0].split("?", 1)[0]).strip()
    if not clean:
        return ""
    if clean.startswith("/"):
        target = clean
    else:
        target = posixpath.join(page_url_dir, clean)
    normalised = posixpath.normpath(target)
    # normpath eats a meaningful trailing slash, which is how a directory URL is spelled.
    if target.endswith("/") and not normalised.endswith("/"):
        normalised += "/"
    return normalised


def site_path(site_dir: Path, prefix: str, url_path: str) -> Path:
    """Where in the built site `url_path` would be served from."""
    relative = url_path[len(prefix):]
    candidate = site_dir / relative if relative else site_dir
    if url_path.endswith("/") or candidate.is_dir():
        return candidate / "index.html"
    return candidate


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--config", default="public/zensical.yml",
        help="Zensical config to read site_url/site_dir from (default: public/zensical.yml).",
    )
    parser.add_argument(
        "--site-dir", default=None,
        help="Built site directory. Default: `site_dir` from the config, relative to its parent.",
    )
    parser.add_argument(
        "--prefix", default=None,
        help="Override the URL path prefix (default: the path of `site_url`). "
             "Pass / to check the site as if served at the server root.",
    )
    parser.add_argument(
        "--summary", action="store_true",
        help="Print the per-kind reference census as well as the verdict.",
    )
    parser.add_argument(
        "--include-copied-assets", action="store_true",
        help="Also check *.html inside the copied-asset tree (site `assets/`). Off by default: "
             "that tree is vendored third-party HTML, not pages built from this repo's markdown.",
    )
    parser.add_argument(
        "--max-report", type=int, default=40,
        help="Cap on individually reported failures (default: 40).",
    )
    args = parser.parse_args()

    config_yml = Path(args.config).resolve()
    if not config_yml.is_file():
        print(f"error: config {config_yml} not found", file=sys.stderr)
        return 2
    site_url, configured_site_dir = read_config(config_yml)
    prefix = args.prefix if args.prefix is not None else prefix_from_site_url(site_url)
    if not prefix.startswith("/"):
        prefix = "/" + prefix
    if not prefix.endswith("/"):
        prefix += "/"

    site_dir = Path(args.site_dir).resolve() if args.site_dir else (config_yml.parent / configured_site_dir).resolve()
    if not site_dir.is_dir():
        print(
            f"error: built site {site_dir} not found — build it first with\n"
            f"  uv run --group docs python tools/build_zensical_docs.py",
            file=sys.stderr,
        )
        return 2

    all_html = sorted(p for p in site_dir.rglob("*.html") if p.is_file())
    # `public/docs/assets/**` is the copied-asset tree (`build_zensical_docs.py:copy_non_markdown_files`),
    # not markdown that zensical rendered. It carries a vendored CodeMirror distribution whose 129
    # mode-demo `*.html` files reference CodeMirror's own source layout (`../../lib/codemirror.js`)
    # that this repo does not ship. Measured: 132 of the 287 `*.html` in the built site sit there and
    # account for 417 of 475 unresolved targets. They are third-party files, in no nav, authored by no
    # one here, and every one of them was already broken at `$BASE` — gating on them would only mean
    # never gating. Counted and named on every run so the exclusion cannot grow in silence.
    copied = [p for p in all_html if p.relative_to(site_dir).as_posix().startswith("assets/")]
    pages = all_html if args.include_copied_assets else [p for p in all_html if p not in set(copied)]
    if not pages:
        # A guard with an empty population is a guard that passes for the wrong reason.
        print(f"error: no *.html under {site_dir} — nothing to check", file=sys.stderr)
        return 2

    # url_path -> the pages that reference it, and the raw ref text as written.
    wanted: dict[str, list[tuple[str, str]]] = defaultdict(list)
    total_refs = 0
    skipped_external = 0
    absolute_refs = 0
    # Displayed code, masked out below. `masked_would_resolve` is the assertion that the mask is
    # not hiding real references: a `src=` inside a `<pre>` that lands on a real file in the site
    # is not displayed code, it is a reference in a place this guard did not expect, and the mask
    # must not be what makes it pass.
    masked_refs = 0
    masked_skipped = 0
    masked_would_resolve: list[tuple[str, str, str]] = []
    for page in pages:
        page_dir = page_url(site_dir, page, prefix)
        html = page.read_text(encoding="utf-8", errors="ignore")
        spans = code_spans(html)
        for match in REF_ATTR.finditer(html):
            value = match.group(2)
            in_code = inside_code(spans, match.start())
            for ref in (split_srcset(value) if "," in value and " " in value else [value]):
                stripped = ref.strip()
                if not stripped:
                    continue
                if stripped.lower().startswith(SKIP_PREFIXES):
                    if in_code:
                        masked_skipped += 1
                    else:
                        skipped_external += 1
                    continue
                url_path = resolve(page_dir, stripped)
                if in_code:
                    masked_refs += 1
                    landed = url_path == prefix.rstrip("/") or url_path.startswith(prefix)
                    if landed and site_path(site_dir, prefix, url_path or prefix).is_file():
                        masked_would_resolve.append(
                            (url_path, page.relative_to(site_dir).as_posix(), stripped)
                        )
                    continue
                total_refs += 1
                if stripped.startswith("/"):
                    absolute_refs += 1
                if url_path:
                    wanted[url_path].append((page.relative_to(site_dir).as_posix(), stripped))

    # Absent as a whole subtree, or merely incomplete? Only the first is a build-configuration fact.
    source_mirror_built = (site_dir / SOURCE_MIRROR_PREFIX).is_dir()

    escapes: list[tuple[str, str, str]] = []
    missing: list[tuple[str, str, str]] = []
    known: list[tuple[str, str, str]] = []
    source_mirror_absent: list[str] = []
    unresolved_paths: set[str] = set()
    for url_path in sorted(wanted):
        page_rel, ref = wanted[url_path][0]
        # There used to be a `mirror_code` category here, for refs whose every referrer was a
        # `reference/source/**` page. It existed because the extractor read displayed code as
        # markup; masking `<pre>`/`<code>` above removes the cause, so the category is gone. It
        # was also a weaker rule than the mask: it let a bogus ref through whenever every
        # referrer happened to be a mirror page, whether or not the ref was inside a fence.
        # `prefix.rstrip("/")` is the site root itself. The theme links back to it as `./..`
        # from every page (463 of them), which normalises to `/Field4` with no trailing slash —
        # inside the site, not an escape. Only treating it as such stops the guard reporting
        # its own most common reference as a failure.
        inside = url_path == prefix.rstrip("/") or url_path.startswith(prefix)
        if inside and url_path == prefix.rstrip("/"):
            url_path = prefix
        if inside and site_path(site_dir, prefix, url_path).is_file():
            continue
        unresolved_paths.add(url_path)
        if url_path in KNOWN_UNRESOLVED:
            known.append((url_path, page_rel, ref))
        elif not inside:
            escapes.append((url_path, page_rel, ref))
        elif not source_mirror_built and url_path[len(prefix):].startswith(SOURCE_MIRROR_PREFIX):
            source_mirror_absent.append(url_path)
        else:
            missing.append((url_path, page_rel, ref))

    print(
        f"checked {len(pages)} built page(s) under prefix {prefix!r} "
        f"({site_url.strip()}): {total_refs} local reference(s) extracted from rendered HTML, "
        f"{len(wanted)} distinct target(s); {skipped_external} external/in-page ref(s) skipped"
    )
    # The population is meaningless without the build flag that produced it, because the source
    # mirror is by far the largest page family and is the only one generated per build. Measured
    # on 2026-09-17 in one tree, the same site two ways: a default build checks 613 of 745 *.html
    # (455 of them the mirror), and `--no-source-pages` checks 158 of 290. Quoting either number
    # on its own says nothing. The flag is not observable from here, so the mirror's presence and
    # page count are printed instead — that is the fact the flag decides.
    mirror_pages = sum(
        1 for p in pages if p.relative_to(site_dir).as_posix().startswith(SOURCE_MIRROR_PREFIX)
    )
    print(
        f"  population: {len(pages)} of {len(all_html)} *.html in {site_dir.name}/"
        + (
            ""
            if args.include_copied_assets
            else f"; {len(copied)} skipped as copied/vendored under assets/ "
                 f"(--include-copied-assets to check them too)"
        )
    )
    print(
        f"  build shape: source mirror "
        + (
            f"PRESENT — {mirror_pages} of the {len(pages)} checked page(s) are "
            f"{SOURCE_MIRROR_PREFIX}**; a --no-source-pages build omits them and is a different, "
            f"much smaller population"
            if source_mirror_built
            else f"ABSENT (--no-source-pages, or no compiled Kotlin tree) — {SOURCE_MIRROR_PREFIX}** "
                 f"is the largest page family and is not checked by this run"
        )
    )
    print(
        f"  displayed code masked: {masked_refs + masked_skipped} attribute match(es) inside "
        f"<pre>/<code> ({masked_skipped} external or in-page, {masked_refs} local), of which "
        f"{len(masked_would_resolve)} would have resolved to a real file"
    )
    if args.summary:
        print(f"  of the {total_refs} local refs, {absolute_refs} are site-absolute "
              f"(prefix baked in by the theme from site_url) and "
              f"{total_refs - absolute_refs} are relative")
        print(f"  {len(wanted) - len(unresolved_paths)} of {len(wanted)} distinct "
              f"target(s) resolve to a file inside the prefix")

    def report(title: str, rows: list[tuple[str, str, str]], explain: str) -> None:
        print(f"\nFAIL — {len(rows)} distinct reference(s) {title}:\n")
        for url_path, page_rel, ref in rows[: args.max_report]:
            referrers = wanted[url_path]
            print(f"  {url_path}")
            print(f"      written as: {ref}")
            print(f"      on page:    {page_rel}"
                  + (f" (and {len(referrers) - 1} other page(s))" if len(referrers) > 1 else ""))
        if len(rows) > args.max_report:
            print(f"  … and {len(rows) - args.max_report} more")
        print(f"\n{explain}")

    if escapes:
        report(
            f"escape the site prefix {prefix!r}",
            escapes,
            "Cause: a relative ref written from the *published URL's* point of view rather than\n"
            "file-relative to the docs root. Zensical re-bases raw-HTML href/src for the page's\n"
            "URL depth (public/docs_hooks.py:page_directory_depth), so a leading `../` gets a\n"
            "second one and leaves the site. Fix: write it docs-root-relative (`assets/x.png`).\n"
            "These 404 under the prefix; at the server root they are hidden by the `..` clamp.",
        )
    if missing:
        report(
            "name nothing in the built site",
            missing,
            "Cause: a typo, a renamed page or anchor target, or an asset the build never wrote.\n"
            "Fix: correct the ref, or generate/commit the target.",
        )

    if source_mirror_absent:
        print(
            f"\nNOTE — {len(source_mirror_absent)} target(s) under {prefix}{SOURCE_MIRROR_PREFIX} "
            f"are absent because this build did not generate the source mirror.\n"
            f"That tree is rendered per build and not committed "
            f"(build_zensical_docs.py:generate_source_pages), so `--no-source-pages` or a machine "
            f"with no\ncompiled Kotlin tree legitimately omits it. Not gating. Build without "
            f"--no-source-pages to check these."
        )

    if masked_would_resolve:
        print(f"\nFAIL — {len(masked_would_resolve)} reference(s) inside <pre>/<code> resolve to a "
              f"real file in the site:\n")
        for url_path, page_rel, ref in masked_would_resolve[: args.max_report]:
            print(f"  {url_path}  (as {ref} on {page_rel})")
        if len(masked_would_resolve) > args.max_report:
            print(f"  … and {len(masked_would_resolve) - args.max_report} more")
        print(
            "\nThe code mask is meant to hide displayed source, and displayed source does not\n"
            "name real files in this site — measured 0 of 51 on the full build of 2026-09-17.\n"
            "A hit here means either the theme now emits real markup inside a code element, or\n"
            "CODE_REGION is matching more than it should. Either way the mask is now hiding\n"
            "references this guard exists to check, so it fails rather than shrinking in silence."
        )

    if known:
        print(f"\nKNOWN GAP — {len(known)} reference(s) were already broken under the prefix "
              f"and are grandfathered:\n")
        for url_path, page_rel, ref in known:
            referrers = wanted[url_path]
            print(f"  {url_path}  (as {ref} on {page_rel}"
                  + (f" +{len(referrers) - 1} more page(s)" if len(referrers) > 1 else "") + ")")
            print(f"      {KNOWN_UNRESOLVED[url_path]}")
        print("\nThese are listed in KNOWN_UNRESOLVED and do not fail. Each owes a fix.")

    # The ledger describes the real published site, so it can only be graded against the real
    # published site. Over a synthetic fixture, or at a different prefix (where the same debt has
    # different URLs), every entry would look "no longer reproducing" and the staleness check would
    # fire on a site that simply is not the one the ledger is about. Caught by this guard's own
    # test suite, which is the correct place to have caught it.
    grading_the_real_site = args.site_dir is None and args.prefix is None
    stale = stale_known_unresolved(unresolved_paths) if grading_the_real_site else []
    if stale:
        print(f"\nFAIL — {len(stale)} KNOWN_UNRESOLVED entr(y/ies) no longer reproduce:\n")
        for entry in stale:
            print(f"  {entry}")
        print("\nThe reference now resolves (or is gone). Delete the entry — an exception that "
              "outlives\nits cause is how a grandfathered list becomes a policy.")

    if escapes or missing or stale or masked_would_resolve:
        return 1
    resolved = len(wanted) - len(unresolved_paths)
    print(f"OK — {resolved} of {len(wanted)} distinct local reference(s) across {len(pages)} built "
          f"page(s) resolve inside {prefix!r}, source mirror "
          + ("present" if source_mirror_built else "absent")
          + (f"; {len(known)} known gap(s) and "
             f"{len(source_mirror_absent)} un-generated source-mirror target(s) above, not gating."
             if known or source_mirror_absent else "."))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
