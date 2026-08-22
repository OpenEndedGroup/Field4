#!/usr/bin/env python3
"""Lint: every asset a *published* docs page references must be committed to git.

A docs page can point at an image/video that exists in a warm working tree but was
never ``git add``-ed — a ``diffstatic_runner`` capture that the docs build regenerated,
or a hand-placed hero/still. It renders fine locally and 404s on a clean checkout or in
CI. This script catches that class before it merges.

Scope
-----
By default only pages listed in ``public/zensical.yml`` ``nav:`` are checked — that is
what actually ships. Unpublished POC pages (e.g. ``diff-player.md``) and ``dev_docs``
are skipped. Pass ``--all`` to scan every markdown file under the docs root.

Resolution
----------
References are resolved the way Zensical serves the site, checked against the *source*
tree:

* markdown-processed refs (``screenshot=``, ``poster=``, ``![](...)``, ``<img src>``)
  are relative to the source page's directory;
* raw-HTML refs in a pretty-URL site can carry a leading ``../`` to climb out of the
  page's own URL directory (page served at ``/foo/`` → ``../assets`` = ``/assets``).

So an asset is satisfied if it is git-tracked at *either* resolution. Only assets that
are untracked at both are reported (whether they are absent or merely unstaged — both
break a clean checkout).

Exit status is non-zero when any published page references an untracked asset, so this
can gate a merge or run at the end of a docs build.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

ASSET_EXTS = (".png", ".svg", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".mp4", ".webm", ".wav")

# Attribute-style refs (runner-fence headers + raw HTML). Covers runner capture
# outputs (`screenshot=` PNGs, `video=` MP4s, `audio=` WAVs) and HTML `src=`/`poster=`.
# Note this does NOT match `source-image=` (a local /tmp input to the runner, not a
# shipped asset): none of these tokens appears as `…=` inside "source-image".
ATTR_REF = re.compile(r'(?:screenshot|video|audio|poster|src)\s*=\s*"([^"]+)"')
MD_IMG = re.compile(r'!\[[^\]]*\]\(\s*([^)\s]+)')
EXTERNAL = ("http://", "https://", "data:", "mailto:", "#")


def git(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout


def repo_root() -> Path:
    top = git(Path.cwd(), "rev-parse", "--show-toplevel").strip()
    return Path(top) if top else Path.cwd()


def nav_pages(config_yml: Path) -> set[str]:
    """Collect every `*.md` path listed under the config `nav:` block (relative to docs_dir)."""
    pages: set[str] = set()
    if not config_yml.exists():
        return pages
    in_nav = False
    for line in config_yml.read_text(encoding="utf-8").splitlines():
        if re.match(r"^nav:\s*$", line):
            in_nav = True
            continue
        if in_nav:
            # A new top-level key (non-indented, non-comment) ends the nav block.
            if line and not line[0].isspace() and not line.startswith("#"):
                break
            pages.update(re.findall(r"([\w./-]+\.md)", line))
    return pages


def extract_refs(text: str) -> list[str]:
    return ATTR_REF.findall(text) + MD_IMG.findall(text)


def resolve_candidates(page: Path, ref: str) -> list[Path]:
    """Filesystem candidates for `ref` on `page`, covering both resolution conventions."""
    clean = ref.split("#")[0].split("?")[0].strip()
    if clean.startswith("/"):
        # site-absolute — resolved against the docs root by the caller via `../` walk-up;
        # treated here as relative to the page dir after stripping the leading slash.
        clean = clean.lstrip("/")
    # A: markdown semantics — relative to the source page's directory.
    # B: pretty-URL semantics — page served one level deep (/<stem>/), so a leading
    #    `../` climbs back to the page dir; model that by nesting under the stem.
    return [
        (page.parent / clean).resolve(),
        (page.parent / page.stem / clean).resolve(),
    ]


DIFFSTATIC_FENCE = re.compile(r"```diffstatic_runner \{([^}]*)\}")
FENCE_ASSET = re.compile(r'(?:screenshot|video|audio)="([^"]+)"')


def orphaned_capture_fences(text: str) -> list[str]:
    """Assets a fence generates that the page then shows nowhere.

    `embed` defaults to FALSE in `public/docs_hooks.py`, and the `<img>`/`<video>` is only emitted
    when it is true. So a fence declaring `screenshot="..."` without `embed=true` runs its code,
    spends the capture budget, writes the PNG -- and the reader sees nothing. That is invisible from
    either end: the asset exists on disk so the tracked-asset check above is happy, and the page
    renders without error. It shipped on two whole pages (`rectsl_inverse_animation`, `manifold`)
    plus three stragglers before anyone opened them and noticed the code had no pictures.

    Referencing the asset by hand instead -- an ordinary `![](...)` line -- is a legitimate second
    convention and is not reported.
    """
    shown = set(re.findall(r"!\[[^\]]*\]\(([^)]+)\)", text)) | set(re.findall(r'src="([^"]+)"', text))
    shown_names = {PurePosixPath(r.strip()).name for r in shown}
    orphans: list[str] = []
    for match in DIFFSTATIC_FENCE.finditer(text):
        attrs = match.group(1)
        asset = FENCE_ASSET.search(attrs)
        if asset is None or "embed=" in attrs:
            continue
        path = asset.group(1).strip()
        if path in shown or PurePosixPath(path).name in shown_names:
            continue
        orphans.append(path)
    return orphans


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--docs-root", default="public/docs", help="Docs directory (default: public/docs).")
    parser.add_argument("--config", default="public/zensical.yml", help="Zensical config for the nav (default: public/zensical.yml).")
    parser.add_argument("--all", action="store_true", help="Scan every *.md under the docs root, not just nav pages.")
    parser.add_argument("--ignore", action="append", default=[], metavar="PAGE",
                        help="Docs-root-relative page path to skip (repeatable).")
    args = parser.parse_args()

    root = repo_root()
    docs_root = (root / args.docs_root).resolve()
    if not docs_root.is_dir():
        print(f"error: docs root {docs_root} not found", file=sys.stderr)
        return 2

    tracked = {line for line in git(root, "ls-files").splitlines()}
    ignore = set(args.ignore)

    if args.all:
        pages = sorted(docs_root.rglob("*.md"))
    else:
        nav = nav_pages(root / args.config)
        pages = sorted(docs_root / p for p in nav if (docs_root / p).exists())

    def rel(p: Path) -> str | None:
        try:
            return p.relative_to(root).as_posix()
        except ValueError:
            return None

    problems: list[tuple[str, str, str]] = []
    orphans: list[tuple[str, str]] = []
    checked_pages = 0
    checked_refs = 0
    for page in pages:
        page_rel = rel(page)
        if page_rel is None or page.relative_to(docs_root).as_posix() in ignore:
            continue
        checked_pages += 1
        page_text = page.read_text(encoding="utf-8", errors="ignore")
        for asset in orphaned_capture_fences(page_text):
            orphans.append((page_rel, asset))
        for ref in extract_refs(page.read_text(encoding="utf-8", errors="ignore")):
            r = ref.strip()
            if not r or r.startswith(EXTERNAL) or not r.lower().endswith(ASSET_EXTS):
                continue
            checked_refs += 1
            candidates = resolve_candidates(page, r)
            cand_rels = [c for c in (rel(c) for c in candidates) if c is not None]
            if any(cr in tracked for cr in cand_rels):
                continue
            on_disk = any(c.exists() for c in candidates)
            state = "unstaged (present, not git-added)" if on_disk else "absent (never generated)"
            problems.append((page_rel, r, state))

    print(f"checked {checked_pages} page(s), {checked_refs} asset ref(s) "
          f"[{'all md' if args.all else 'nav-published only'}]")
    if orphans:
        print(f"\nFAIL — {len(orphans)} capture fence(s) generate an asset the page never shows:\n")
        for page_rel, asset in orphans:
            print(f"  {asset}")
            print(f"      in:    {page_rel}")
            print("      cause: the fence declares it but has no `embed=true`, and no markdown "
                  "reference shows it")
        print("\nFix: add `embed=true` to the fence, or reference the asset with an ordinary "
              "![](...) line.")
        if not problems:
            return 1

    if not problems:
        print("OK — every referenced asset is committed, and every capture is shown.")
        return 0

    print(f"\nFAIL — {len(problems)} referenced asset(s) not tracked in git:\n")
    for page_rel, ref, state in problems:
        print(f"  {ref}")
        print(f"      in:    {page_rel}")
        print(f"      state: {state}")
    print("\nFix: `git add` the asset (if present) or regenerate it, then commit.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
