#!/usr/bin/env python3
"""Lint: every `offline.html?file=…` link in the authored docs must survive the site's path prefix.

Why this needs its own guard
----------------------------
`tools/check_doc_site_links.py` resolves references out of the *built* site and would catch the
link half of this on its own: a server-absolute `/offline.html` resolves outside
`site_url`'s `/Field4/` prefix and gates as an escape. What it structurally cannot see is the
**query half**. `resolve()` there strips `?…` before resolving, because a query string is not
part of a path — so `?file=/field4/assets/x/metadata.edn` is invisible to it, and that half is
a second, independent 404.

It is a second 404 because the offline viewer fetches the `?file=` value itself
(`src/commonMain/resources/offline-viewer.js:ednFileCandidates`). Given a server-absolute value
it tries three candidates: the value as written, the value with its first path segment stripped,
and the value relative to the viewer's own URL. Under the published `/Field4/` prefix all three
miss for `/field4/assets/x`: `/field4/assets/x` (wrong case, wrong mount),
`/assets/x` (no prefix), and `/Field4/field4/assets/x` (doubled). Written *viewer-relative* —
`assets/x` — the first candidate resolves against the viewer's own URL and lands on
`/Field4/assets/x`, and the same value works unchanged when the site is served at the root.

So the rule this enforces is one rule with two halves, and both are "no leading slash":

    [Open ↗](offline.html?file=assets/agent_transcripts/NAME/metadata.edn)

`offline.html` written docs-root-relative is re-based by the build for the page's URL depth —
measured on a throwaway zensical project with `site_url: …/Field4/` and `use_directory_urls`:
both a markdown link and a raw-HTML `src` written `offline.html?file=assets/a/b.edn` on a
top-level page render as `../offline.html?file=assets/a/b.edn`, with no build warning, even
though `offline.html` is not in the docs source tree (the build syncs it into `site_dir` after
rendering, `build_zensical_docs.py:705`). A leading `../` gets a second one and leaves the site;
a leading `/` ignores the prefix entirely.

The case mismatch is the part that hides
----------------------------------------
`public/zensical.yml` sets `site_dir: field4` and `site_url: http://openendedgroup.com/Field4/`.
Those differ only in case, so every one of these links worked in local preview (where the site
dir *is* the server root, and macOS is case-insensitive anyway) and 404s on a case-sensitive
host. Neither the local preview nor a case-insensitive filesystem can reproduce it; a lint on
the source can.

Exit status is non-zero on any malformed link, so this can gate. Run:
    python3 tools/check_doc_offline_links.py [--summary]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlsplit

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_REL = "public/docs"
CONFIG_REL = "public/zensical.yml"

# Every way the viewer is referenced today: a markdown link target and a raw-HTML `src`. The
# pattern is deliberately loose on the left so a *wrong* form is matched and reported rather than
# silently skipped — a guard whose regex only matches the correct shape selects nothing.
OFFLINE_REF = re.compile(r"""(?P<lead>[^\s"'()]*?)offline\.html\?file=(?P<file>[^)\s"'>]*)""")

# The pages that carry these links, recorded 2026-09-17 by this guard over the population below.
# Set equality both ways, so a new carrier page fails as loudly as a removed one: adding a
# transcript to a page already on this list is routine and does not touch it, but a *new* page
# growing an offline link is exactly the moment to check the link's shape.
CARRIER_PAGES = {
    "public/docs/afield_four_graphics.md",
    "public/docs/agent_transcripts.md",
    "public/docs/aperture_playground.md",
    "public/docs/direct_lighting.md",
    "public/docs/faust.md",
    "public/docs/fieldml_host_bridge.md",
    "public/docs/fline_examples.md",
    # Added 2026-09-22: `b5b9ad9e0` landed `flow_grain.md` with a correct viewer-relative link
    # and left this ledger behind, so the guard was red on `main`. The shape was checked, which
    # is the whole point of failing here: `offline.html?file=assets/…/metadata.edn`, no leading
    # slash on either half.
    "public/docs/flow_grain.md",
    "public/docs/gpu_state.md",
    "public/docs/nano_examples.md",
    "public/docs/openxr_eye_intersection.md",
    "public/docs/profiling_timeline.md",
    "public/docs/rectsl_examples.md",
    "public/docs/simulation_clock.md",
    "public/docs/solid_notation.md",
    "public/docs/stacksl_examples.md",
    "public/docs/three_output.md",
    "public/docs/vision_geometry.md",
    "public/docs/wfc_examples.md",
}

# Measured the same day: 137 occurrences over 513 tracked `public/docs/**.md`. Reported rather
# than gated (agent_transcripts.md grows a link per new transcript), but the floor is asserted —
# a scan that suddenly finds a handful is a broken scan, not a tidied tree.
RECORDED_OCCURRENCES = 137


class _TolerantLoader(yaml.SafeLoader):
    """SafeLoader that ignores `public/zensical.yml`'s Python tags instead of importing them."""


_TolerantLoader.add_multi_constructor("", lambda loader, suffix, node: None)


def read_config(config_yml: Path) -> tuple[str, str]:
    """`(site_url, site_dir)` from the zensical config."""
    config = yaml.load(config_yml.read_text(encoding="utf-8"), Loader=_TolerantLoader) or {}
    return str(config.get("site_url", "") or "/"), str(config.get("site_dir", "") or "site")


def tracked_docs_markdown(repo_root: Path) -> list[str]:
    """Every tracked markdown file under `public/docs`, enumerated by git.

    `git ls-files public/docs | grep '\\.md$'` rather than a pathspec glob: `'public/docs/**/*.md'`
    does not match a single-level path in a git pathspec, so it silently excludes every top-level
    page — which is where all 17 carriers live.
    """
    listing = subprocess.run(
        ["git", "-C", str(repo_root), "ls-files", DOCS_REL],
        capture_output=True, text=True, check=True,
    ).stdout
    return sorted(line for line in listing.split("\n") if line.endswith(".md"))


def prefix_from_site_url(site_url: str) -> str:
    """The URL path a built page is served under, normalised to `/…/`."""
    path = urlsplit(site_url.strip()).path or "/"
    if not path.startswith("/"):
        path = "/" + path
    if not path.endswith("/"):
        path += "/"
    return path


def problems_for(*, page: str, lead: str, file_value: str, site_dir: str,
                 docs_dir: Path) -> list[str]:
    """Everything wrong with one `offline.html?file=…` reference, as sentences."""
    found: list[str] = []
    if lead.startswith("/"):
        found.append(
            f"the link is server-absolute (`{lead}offline.html`). Under the published prefix that "
            f"resolves outside the site. Write it docs-root-relative: `offline.html`"
        )
    elif lead.startswith(".."):
        found.append(
            f"the link climbs (`{lead}offline.html`). The build adds the `../` for the page's URL "
            f"depth, so a written one gets a second and leaves the site. Write `offline.html`"
        )
    elif lead not in ("", "("):
        found.append(f"unexpected text before `offline.html`: {lead!r}")

    if file_value.startswith("/"):
        found.append(
            f"`?file={file_value}` is server-absolute. The viewer resolves it against its own URL, "
            f"so a leading slash ignores the site prefix. Drop it"
        )
    stem = file_value.lstrip("/").split("/", 1)[0]
    if stem.lower() == site_dir.lower():
        found.append(
            f"`?file=` names the local `site_dir` (`{stem}/`) — that directory IS the site root "
            f"when published, so the segment must not appear in the value"
        )
    if not found:
        target = docs_dir / file_value
        if not target.is_file():
            found.append(
                f"`?file={file_value}` names nothing committed under {DOCS_REL}/ "
                f"(looked for {target.relative_to(REPO_ROOT)})"
            )
    return found


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--repo-root", default=str(REPO_ROOT), help="Repository root to scan.")
    parser.add_argument("--summary", action="store_true", help="Print the per-page census too.")
    parser.add_argument(
        "--no-ledger", action="store_true",
        help="Skip the CARRIER_PAGES set-equality check (for running against a fixture tree).",
    )
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    docs_dir = repo_root / DOCS_REL
    site_url, site_dir = read_config(repo_root / CONFIG_REL)
    prefix = prefix_from_site_url(site_url)

    pages = tracked_docs_markdown(repo_root)
    if not pages:
        # A guard with an empty population passes for the wrong reason.
        print(f"error: no tracked *.md under {DOCS_REL} in {repo_root}", file=sys.stderr)
        return 2

    per_page: dict[str, int] = defaultdict(int)
    failures: list[tuple[str, int, str, list[str]]] = []
    occurrences = 0
    for rel in pages:
        text = (repo_root / rel).read_text(encoding="utf-8")
        for match in OFFLINE_REF.finditer(text):
            occurrences += 1
            per_page[rel] += 1
            found = problems_for(
                page=rel, lead=match.group("lead"), file_value=match.group("file"),
                site_dir=site_dir, docs_dir=docs_dir,
            )
            if found:
                line = text[: match.start()].count("\n") + 1
                failures.append((rel, line, match.group(0), found))

    print(
        f"checked {len(pages)} tracked *.md under {DOCS_REL}/ against prefix {prefix!r} "
        f"(site_url {site_url.strip()}, site_dir {site_dir!r}): "
        f"{occurrences} `offline.html?file=` reference(s) on {len(per_page)} page(s)"
    )
    if args.summary:
        for rel in sorted(per_page, key=lambda k: (-per_page[k], k)):
            print(f"    {rel}: {per_page[rel]}")

    # Premise first: if the scan stops finding references at all, the regex or the scan root broke.
    # That must fail rather than read as "the tree is clean" — the exact way a guard becomes a
    # tautology. Recorded floor, not equality, because agent_transcripts.md gains one per
    # transcript; the page *set* below is what is pinned in both directions.
    if occurrences < RECORDED_OCCURRENCES:
        print(
            f"\nFAIL — found {occurrences} reference(s), fewer than the {RECORDED_OCCURRENCES} "
            f"recorded on 2026-09-17.\nEither links were deleted (say so and lower "
            f"RECORDED_OCCURRENCES) or this scan stopped matching them."
        )
        return 1

    ledger_problems: list[str] = []
    if not args.no_ledger:
        seen = set(per_page)
        for missing in sorted(CARRIER_PAGES - seen):
            ledger_problems.append(
                f"{missing}: listed in CARRIER_PAGES but carries no `offline.html?file=` link "
                f"any more. Delete the entry."
            )
        for extra in sorted(seen - CARRIER_PAGES):
            ledger_problems.append(
                f"{extra}: carries {per_page[extra]} `offline.html?file=` link(s) and is not in "
                f"CARRIER_PAGES. Check the shape, then add it."
            )

    if failures:
        print(f"\nFAIL — {len(failures)} malformed reference(s):\n")
        for rel, line, written, found in failures:
            print(f"  {rel}:{line}")
            print(f"      written as: {written}")
            for problem in found:
                print(f"      - {problem}")
        print(
            "\nThe correct shape is viewer-relative on both halves:\n"
            "  [Open ↗](offline.html?file=assets/<dir>/metadata.edn)\n"
            "It resolves under any prefix and at the server root, because the build re-bases the\n"
            "link for the page's URL depth and the viewer resolves `?file=` against its own URL."
        )
    if ledger_problems:
        print(f"\nFAIL — {len(ledger_problems)} CARRIER_PAGES ledger problem(s):\n")
        for problem in ledger_problems:
            print(f"  {problem}")

    if failures or ledger_problems:
        return 1
    print(
        f"OK — all {occurrences} reference(s) on {len(per_page)} page(s) are viewer-relative on "
        f"both halves and name a committed target; carrier set matches CARRIER_PAGES exactly."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
