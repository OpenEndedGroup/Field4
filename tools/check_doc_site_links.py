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

# Schemes and forms that do not name a file in this site. `//` is protocol-relative, so it is
# off-site too. A bare `#fragment` is the same page.
SKIP_PREFIXES = ("http://", "https://", "data:", "mailto:", "tel:", "javascript:", "#", "//")


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
    for page in pages:
        page_dir = page_url(site_dir, page, prefix)
        html = page.read_text(encoding="utf-8", errors="ignore")
        for _attr, value in REF_ATTR.findall(html):
            for ref in (split_srcset(value) if "," in value and " " in value else [value]):
                stripped = ref.strip()
                if not stripped:
                    continue
                if stripped.lower().startswith(SKIP_PREFIXES):
                    skipped_external += 1
                    continue
                total_refs += 1
                if stripped.startswith("/"):
                    absolute_refs += 1
                url_path = resolve(page_dir, stripped)
                if url_path:
                    wanted[url_path].append((page.relative_to(site_dir).as_posix(), stripped))

    escapes: list[tuple[str, str, str]] = []
    missing: list[tuple[str, str, str]] = []
    for url_path in sorted(wanted):
        page_rel, ref = wanted[url_path][0]
        # `prefix.rstrip("/")` is the site root itself. The theme links back to it as `./..`
        # from every page (463 of them), which normalises to `/Field4` with no trailing slash —
        # inside the site, not an escape. Only treating it as such stops the guard reporting
        # its own most common reference as a failure.
        if url_path != prefix.rstrip("/") and not url_path.startswith(prefix):
            escapes.append((url_path, page_rel, ref))
            continue
        if url_path == prefix.rstrip("/"):
            url_path = prefix
        if not site_path(site_dir, prefix, url_path).is_file():
            missing.append((url_path, page_rel, ref))

    print(
        f"checked {len(pages)} built page(s) under prefix {prefix!r} "
        f"({site_url.strip()}): {total_refs} local reference(s) extracted from rendered HTML, "
        f"{len(wanted)} distinct target(s); {skipped_external} external/in-page ref(s) skipped"
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
    if args.summary:
        print(f"  of the {total_refs} local refs, {absolute_refs} are site-absolute "
              f"(prefix baked in by the theme from site_url) and "
              f"{total_refs - absolute_refs} are relative")
        print(f"  {len(wanted) - len(escapes) - len(missing)} of {len(wanted)} distinct "
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

    if escapes or missing:
        return 1
    print(f"OK — all {len(wanted)} distinct local reference(s) across {len(pages)} built "
          f"page(s) resolve inside {prefix!r}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
