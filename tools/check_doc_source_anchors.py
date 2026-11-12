#!/usr/bin/env python3
"""Lint: every `page.md#anchor` link written in source must name a heading that exists.

`tools/check_doc_site_links.py` already checks fragments, but on the *built* site, so it only runs
after a docs build and only sees links on published pages. Links into the docs written in KDoc
(`src/**/*.kt`) and in `dev_docs/**.md` are not on any built page, and `agent_transcripts.md#<uid>`
links from those places went stale with nothing to notice (judge backlog N1112-2). This guard reads
the markdown instead: it computes each docs page's anchors the way Python-Markdown's `toc`
extension does (slugified heading text, `_1`/`_2` for repeats, explicit `{#id}`, raw-HTML
`id=`/`name=`) and checks every `](… .md#frag)` link whose path resolves to a page under
`public/docs/`. A link whose path does not resolve is skipped; a missing page is another guard's job.

    uv run python tools/check_doc_source_anchors.py            # gate, exit 1 on a broken anchor
    uv run python tools/check_doc_source_anchors.py --summary  # population counts
"""
from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "public" / "docs"
SOURCE_GLOBS = (("public/docs", "*.md"), ("dev_docs", "*.md"), ("src", "*.kt"))

FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
ATTR_ID = re.compile(r"\{[^}]*#([A-Za-z0-9_:.-]+)[^}]*\}\s*$")
HTML_ID = re.compile(r"""\s(?:id|name)\s*=\s*["']([^"']+)["']""")
LINK = re.compile(r"\]\(\s*<?([^)\s>#]*\.md)#([^)\s>]+)>?\s*(?:\"[^\"]*\")?\)")
INLINE_CODE = re.compile(r"`[^`\n]*`")
MD_INLINE = re.compile(r"(\[([^\]]*)\]\([^)]*\))|<[^>]+>|[*`]")

# Recorded floor (2026-11-12: 1,9xx links into docs pages). A scan that suddenly finds a handful is
# a broken scan, not a tidy tree.
MIN_LINKS = 500


def slugify(text: str) -> str:
    """Python-Markdown `toc.slugify` (the default Zensical uses), applied to rendered heading text."""
    text = MD_INLINE.sub(lambda m: m.group(2) if m.group(1) else "", text)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")
    text = re.sub(r"[^\w\s-]", "", text).strip().lower()
    return re.sub(r"[-\s]+", "-", text)


def prose_lines(text: str):
    """Yield `(lineno, line)` outside fenced code blocks."""
    fenced = None
    for n, line in enumerate(text.splitlines(), 1):
        m = FENCE.match(line)
        if m:
            if fenced is None:
                fenced = m.group(1)
            elif m.group(1) == fenced:
                fenced = None
            continue
        if fenced is None:
            yield n, line


def anchors(page: Path) -> set[str]:
    found: set[str] = set()
    seen: dict[str, int] = {}
    for _, line in prose_lines(page.read_text(encoding="utf-8", errors="replace")):
        found.update(HTML_ID.findall(line))
        m = HEADING.match(line)
        if not m:
            continue
        title = m.group(2)
        attr = ATTR_ID.search(title)
        if attr:
            found.add(attr.group(1))
            continue
        slug = slugify(title)
        if slug in seen:
            seen[slug] += 1
            slug = f"{slug}_{seen[slug]}"
        else:
            seen[slug] = 0
        found.add(slug)
    return found


def resolve(source: Path, target: str, root: Path = ROOT) -> Path | None:
    docs = root / "public" / "docs"
    candidates = [source.parent / target, docs / target, root / target]
    for c in candidates:
        try:
            c = c.resolve()
        except OSError:
            continue
        if c.is_file() and docs.resolve() in c.parents:
            return c
    return None


def scan(root: Path = ROOT):
    cache: dict[Path, set[str]] = {}
    broken, checked = [], 0
    for base, pattern in SOURCE_GLOBS:
        for source in sorted((root / base).rglob(pattern)):
            if any(p in {"node_modules", "build", "field4"} for p in source.relative_to(root).parts):
                continue
            text = source.read_text(encoding="utf-8", errors="replace")
            lines = prose_lines(text) if source.suffix == ".md" else enumerate(text.splitlines(), 1)
            for n, line in lines:
                for m in LINK.finditer(INLINE_CODE.sub("", line)):
                    page = resolve(source, m.group(1), root)
                    if page is None:
                        continue
                    checked += 1
                    if page not in cache:
                        cache[page] = anchors(page)
                    frag = m.group(2)
                    if frag not in cache[page]:
                        broken.append((source.relative_to(root), n, m.group(1), frag))
    return broken, checked


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--summary", action="store_true")
    ap.add_argument("--root", type=Path, default=ROOT, help="repo root to scan (tests)")
    ap.add_argument("--min-links", type=int, default=MIN_LINKS)
    args = ap.parse_args(argv)
    broken, checked = scan(args.root)
    if args.summary or not broken:
        print(f"check_doc_source_anchors: {checked} anchored links into public/docs, {len(broken)} broken")
    if checked < args.min_links:
        print(f"FAIL: only {checked} anchored links found (floor {args.min_links}); the scan is broken", file=sys.stderr)
        return 1
    for src, n, target, frag in broken:
        print(f"{src}:{n}: {target}#{frag} - no such heading or id on that page")
    if broken:
        print(f"\n{len(broken)} broken anchor(s). Cause: a heading was renamed, or the link was written "
              "against a slug that never existed. Fix the link (or add an explicit {#id}).", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
