#!/usr/bin/env python3
"""Keep `public/docs/changelog.md`, the public "What's new" page, in step with `main`.

The page is written by hand (by a person or an agent) from what this tool reports; the tool
never writes prose. What it owns is the bookkeeping that makes the page trustworthy:

* **Every landing on `main` belongs to exactly one entry.** Each entry carries an invisible
  marker, ``<!-- changelog: <first>..<last> -->``, naming the first and last first-parent commits
  of `main` it summarises. ``check`` requires the markers to tile `main`'s first-parent history
  with no gap and no overlap, from the root to the newest entry. A quiet stretch with nothing a
  reader would care about is folded into a neighbouring entry's range rather than skipped, so
  "not yet written up" and "deliberately left out" can never be confused.
* **What comes next.** ``brief`` prints the next window of landings after the newest entry: each
  landing's subject and the commits it merged, the documentation pages it added, renamed, deleted
  or rewrote (with line counts, under their *current* names), and the source areas with the most
  churn. That is the material an entry is condensed from.
* **Links.** ``check`` requires each entry to link at least one docs page and every relative
  ``.md`` link to name a file under `public/docs`. Fragments are left to
  `tools/check_doc_site_links.py`, which checks them against the built site.

Windows are counted in landings — first-parent commits of `main`, not counting the lander's
"Regenerate generated artifacts" commits — about ``--size`` (10) to a window, closing at a day
boundary when one is near. A window is a suggestion: an entry may cover several when nothing
much happened, and a busy window may become two entries.

Usage (stdlib only; run from anywhere in the checkout):

    python tools/docs_changelog.py status            # newest entry, landings not yet covered
    python tools/docs_changelog.py brief             # the next window, ready to write up
    python tools/docs_changelog.py brief --windows 3 # the next three
    python tools/docs_changelog.py windows           # every window, for a backfill
    python tools/docs_changelog.py brief --window 12 # one window by number from `windows`
    python tools/docs_changelog.py check             # the structural rules above; exit 1 on a fault

How to write an entry is in `dev_docs/agents/DOCS_CHANGELOG.md`.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS_ROOT = REPO_ROOT / "public" / "docs"
CHANGELOG = DOCS_ROOT / "changelog.md"
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

MARKER = re.compile(r"<!--\s*changelog:\s*([0-9a-f]{7,40})\.\.([0-9a-f]{7,40})\s*-->")
MONTH_HEADING = re.compile(r"^## (January|February|March|April|May|June|July|August|September|"
                           r"October|November|December) (\d{4})\s*$")
ENTRY_HEADING = re.compile(r"^### \S")
# A relative markdown link to a page, with its fragment: ](page.md) or ](dir/page.md#frag).
PAGE_LINK = re.compile(r"\]\((?![a-z][a-z0-9+.-]*:|/|#)([^)\s#]+\.md)(?:#([^)\s]*))?\)")
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
          "October", "November", "December"]

# Generated or bookkeeping commits the lander makes; they count toward an entry's range but not
# toward a window's size.
HOUSEKEEPING = re.compile(r"^(Regenerate generated artifacts|Regenerate agent transcript)")

# Directories under public/docs whose pages are generated, not written.
GENERATED_DOCS = ("public/docs/generated/",)

DOC_PAGE = re.compile(r"^public/docs/(?:[^/]+|reference/[^/]+)\.md$")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=REPO_ROOT, check=True, capture_output=True,
                          text=True).stdout


@dataclass
class Landing:
    index: int
    sha: str
    date: str  # committer date, YYYY-MM-DD
    subject: str
    parents: list[str]

    @property
    def housekeeping(self) -> bool:
        return bool(HOUSEKEEPING.match(self.subject))


def landings(ref: str = "main") -> list[Landing]:
    """First-parent history of ``ref``, oldest first."""
    out = git("log", "--first-parent", "--reverse", "--format=%H%x09%P%x09%cs%x09%s", ref)
    result = []
    for line in out.splitlines():
        sha, parents, date, subject = line.split("\t", 3)
        result.append(Landing(len(result), sha, date, subject, parents.split()))
    return result


def windows(history: list[Landing], start: int = 0, size: int = 10) -> list[tuple[int, int]]:
    """Group ``history[start:]`` into inclusive index ranges of about ``size`` real landings.

    A window closes at ``size`` real landings, or a little early (from 70% of ``size``) when the
    next landing is on a later day, so most windows end with a day.
    """
    result = []
    i = start
    while i < len(history):
        first, count = i, 0
        while i < len(history):
            if not history[i].housekeeping:
                count += 1
            nxt = history[i + 1] if i + 1 < len(history) else None
            if count >= size or (count >= size * 7 // 10
                                 and (nxt is None or nxt.date != history[i].date)):
                break
            i += 1
        last = min(i, len(history) - 1)
        result.append((first, last))
        i = last + 1
    return result


@dataclass
class Entry:
    line: int
    first: str
    last: str
    heading: str = ""
    month: tuple[str, str] | None = None
    links: list[tuple[str, str]] = field(default_factory=list)  # (page, fragment or "")


def parse_changelog(text: str) -> list[Entry]:
    """Entries in page order (newest first), each with its heading, month and page links."""
    entries: list[Entry] = []
    month = None
    for number, line in enumerate(text.splitlines(), 1):
        if m := MONTH_HEADING.match(line):
            month = (m.group(1), m.group(2))
            continue
        if m := MARKER.search(line):
            entries.append(Entry(number, m.group(1), m.group(2), month=month))
            continue
        if not entries:
            continue
        if ENTRY_HEADING.match(line) and not entries[-1].heading:
            entries[-1].heading = line[4:].strip()
        elif line.startswith("## ") or line.startswith("### "):
            continue
        entries[-1].links.extend(PAGE_LINK.findall(line))
    return entries


def resolve(history: list[Landing], sha: str) -> int | None:
    for landing in history:
        if landing.sha.startswith(sha):
            return landing.index
    return None


def anchors(page: Path) -> set[str]:
    """Heading slugs of a docs page, computed the way `tools/docs_voice/check_anchors.py` does."""
    sys.path.insert(0, str(REPO_ROOT / "tools" / "docs_voice"))
    try:
        from check_anchors import anchors_of
    finally:
        sys.path.pop(0)
    return anchors_of(page)


def check(text: str, history: list[Landing], docs_root: Path = DOCS_ROOT,
          partial_from: int | None = None) -> list[str]:
    """Every fault in the page's structure, as ``line N: message`` strings.

    ``partial_from`` checks a draft holding only some entries: the oldest must start at that
    landing index instead of the root, and month headings are not required.
    """
    faults = []
    slugs: dict[str, set[str]] = {}
    entries = parse_changelog(text)
    if not entries:
        return ["no entries: expected <!-- changelog: <first>..<last> --> markers"]
    spans = []
    for entry in entries:
        where = f"line {entry.line}"
        first, last = resolve(history, entry.first), resolve(history, entry.last)
        if first is None or last is None:
            missing = entry.first if first is None else entry.last
            faults.append(f"{where}: {missing} is not a first-parent commit of main")
            continue
        if first > last:
            faults.append(f"{where}: range runs backwards ({entry.first}..{entry.last})")
            continue
        spans.append((entry, first, last))
        if not entry.heading:
            faults.append(f"{where}: entry has no ### heading after its marker")
        if not entry.links:
            faults.append(f"{where}: entry links no docs page")
        for link, fragment in entry.links:
            target = docs_root / link
            if not target.is_file():
                faults.append(f"{where}: link to {link}, which is not a page under public/docs")
            elif fragment:
                if link not in slugs:
                    slugs[link] = anchors(target)
                if fragment not in slugs[link]:
                    faults.append(f"{where}: {link} has no heading #{fragment}")
        if partial_from is not None:
            continue
        end = history[last]
        year, month = end.date[:4], MONTHS[int(end.date[5:7]) - 1]
        if entry.month != (month, year):
            shown = " ".join(entry.month) if entry.month else "no month heading"
            faults.append(f"{where}: entry ends {end.date} but sits under {shown}")
    # Newest first, tiling history with no gap or overlap, down to the root.
    for (newer, n_first, _), (older, _, o_last) in zip(spans, spans[1:]):
        if o_last >= n_first:
            faults.append(f"line {older.line}: overlaps or is newer than the entry at line "
                          f"{newer.line}; entries run newest first without overlap")
        elif o_last + 1 != n_first:
            gap = history[o_last + 1], history[n_first - 1]
            faults.append(f"line {older.line}: landings {gap[0].sha[:10]}..{gap[1].sha[:10]} "
                          f"between this entry and the one at line {newer.line} are in no entry")
    oldest = 0 if partial_from is None else partial_from
    if spans and spans[-1][1] != oldest:
        faults.append(f"line {spans[-1][0].line}: the oldest entry must start at "
                      f"{history[oldest].sha[:10]}"
                      + (" (main's root commit)" if partial_from is None else ""))
    return faults


def tip(text: str, history: list[Landing]) -> int:
    """Index of the newest landing the page covers, or -1."""
    covered = [resolve(history, e.last) for e in parse_changelog(text)]
    covered = [c for c in covered if c is not None]
    return max(covered) if covered else -1


# --- render ---------------------------------------------------------------------------------
# The page is authored as plain markdown (marker, heading, italic date line, body). The docs build
# (`tools/build_zensical_docs.py`) passes it through `render`, which puts the date and a link to
# the range's last commit on the heading's line and adds a strip cropped from the first picture on
# the first linked page that has one, behind the heading. `<!-- changelog-image: assets/x.png -->` (or `none`) inside
# an entry overrides the choice.

COMMIT_URL = "https://github.com/OpenEndedGroup/Field4/commit/"
IMAGE_OVERRIDE = re.compile(r"<!--\s*changelog-image:\s*(\S+)\s*-->")
DATE_LINE = re.compile(r"^\*([^*]+)\*\s*$")
IMAGE_REF = re.compile(r"""(?:\]\(|screenshot=["']|src=["'])([^)"'\s]+\.(?:png|jpe?g|webp|gif))""")
HEADING_LINE = re.compile(r"^(#{1,6})\s+(.*)$")


def first_image(page: Path, fragment: str, docs_root: Path) -> str | None:
    """First picture on ``page`` (from the heading ``fragment`` onward, when given), as a path
    relative to the docs root, or None. Only images that exist on disk count."""
    try:
        lines = page.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    start = 0
    if fragment:
        sys.path.insert(0, str(REPO_ROOT / "tools" / "docs_voice"))
        try:
            from check_anchors import slugify, strip_inline
        finally:
            sys.path.pop(0)
        for number, line in enumerate(lines):
            m = HEADING_LINE.match(line)
            if m and slugify(strip_inline(m.group(2))) == fragment:
                start = number
                break
    for line in lines[start:]:
        for ref in IMAGE_REF.findall(line):
            if "://" in ref:
                continue
            candidate = (page.parent / ref).resolve()
            try:
                relative = candidate.relative_to(docs_root.resolve())
            except ValueError:
                continue
            if candidate.is_file():
                return relative.as_posix()
    return None


STRIP_ASPECT = 12.0  # column width over header-row height, as the CSS draws it


CROP_CANDIDATES = (10.0, 30.0, 50.0, 70.0, 90.0)  # object-position y of the strips proposed per picture


def busiest_band(path: Path) -> float:
    """Vertical ``object-position`` (0-100) for ``path``'s strip.

    A centre crop of a screenshot is often an empty canvas. So five strips are proposed, evenly
    from near the top to near the bottom (`CROP_CANDIDATES`), each cut at the strip's
    aspect ratio, and the one whose greyscale histogram has the highest entropy wins; a tie goes
    to the middle. Without Pillow, or for a picture it cannot read, the centre.
    """
    try:
        from PIL import Image
        with Image.open(path) as image:
            grey = image.convert("L")
    except Exception:
        return 50.0
    width, height = grey.size
    band = max(1, round(width / STRIP_ASPECT))
    if band >= height:
        return 50.0

    def entropy(y: float) -> float:
        top = round((height - band) * y / 100.0)
        return grey.crop((0, top, width, top + band)).entropy()

    return max(CROP_CANDIDATES, key=lambda y: (entropy(y), y == 50.0))


def published_commits(remote_ref: str = "origin/main") -> set[str] | None:
    """Commits GitHub has, as far as this checkout knows (``remote_ref``'s history), or None
    when there is no such ref. A commit the lander made since the last push is a 404 there."""
    try:
        return set(git("rev-list", remote_ref).split())
    except subprocess.CalledProcessError:
        return None


def render(text: str, history: list[Landing] | None = None, docs_root: Path = DOCS_ROOT,
           published: set[str] | None = None) -> str:
    """The page as the docs build should see it: same markdown, richer entry headers.

    A commit hash links to GitHub only when the commit is in ``published`` (by default the
    history of ``origin/main``); otherwise it is shown unlinked until the next push."""
    history = history if history is not None else landings()
    if published is None:
        published = published_commits() or set()
    lines = text.splitlines()
    out: list[str] = []
    i = 0
    while i < len(lines):
        marker = MARKER.search(lines[i])
        if not marker:
            out.append(lines[i])
            i += 1
            continue
        # The entry runs to the next marker or month heading.
        end = i + 1
        while end < len(lines) and not MARKER.search(lines[end]) and not lines[end].startswith("## "):
            end += 1
        body = lines[i + 1:end]
        heading_at = next((n for n, l in enumerate(body) if ENTRY_HEADING.match(l)), None)
        if heading_at is None:
            out.extend(lines[i:end])
            i = end
            continue
        date_at = next((n for n in range(heading_at + 1, len(body)) if body[n].strip()), None)
        date = None
        if date_at is not None and (m := DATE_LINE.match(body[date_at])):
            date = m.group(1).strip()
        last = resolve(history, marker.group(2))
        sha = history[last].sha if last is not None else marker.group(2)
        override = next((m.group(1) for l in body if (m := IMAGE_OVERRIDE.search(l))), None)
        image = None
        if override and override != "none":
            image = override
        elif override is None:
            for link, fragment in (l for line in body for l in PAGE_LINK.findall(line)):
                if image := first_image(docs_root / link, fragment, docs_root):
                    break
        meta = ['<div class="changelog-meta">']
        if date:
            meta.append(f'<span class="changelog-date">{date}</span>')
        if sha in published:
            meta.append(f'<a class="changelog-commit" href="{COMMIT_URL}{sha}" '
                        f'title="The last commit this entry covers">{sha[:9]}</a></div>')
        else:
            meta.append(f'<span class="changelog-commit" title="The last commit this entry '
                        f'covers; not on GitHub yet">{sha[:9]}</span></div>')
        out.append(lines[i])
        out.extend(body[:heading_at])
        # md_in_html keeps the heading a heading (anchor, TOC) inside the row; the picture,
        # when there is one, sits behind both.
        tease = []
        if image:
            y = busiest_band(docs_root / image)
            tease = [f'<div class="changelog-tease"><img src="{image}" alt="" loading="lazy" '
                     f'style="object-position: 50% {y}%"></div>', ""]
        out += ['<div class="changelog-head" markdown="1">', "", *tease, body[heading_at], "",
                "".join(meta), "", "</div>", ""]
        rest = [l for n, l in enumerate(body[heading_at + 1:], heading_at + 1)
                if n != date_at or date is None]
        while rest and not rest[0].strip():
            rest.pop(0)
        out.extend(rest)
        i = end
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


# --- brief ----------------------------------------------------------------------------------


def rename_map(ref: str = "main") -> dict[str, str]:
    """Old docs path -> newer path, for every rename on ``ref``'s first-parent history."""
    out = git("log", "--first-parent", "--reverse", "-M", "--diff-filter=R", "--name-status",
              "--format=", ref, "--", "public/docs")
    renames = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0].startswith("R"):
            renames[parts[1]] = parts[2]
    return renames


def current_name(path: str, renames: dict[str, str]) -> str | None:
    seen = set()
    while path in renames and path not in seen:
        seen.add(path)
        path = renames[path]
    return path if (REPO_ROOT / path).is_file() else None


def landing_commits(landing: Landing) -> list[str]:
    if len(landing.parents) < 2:
        return []
    out = git("log", "--no-merges", "--format=%s", f"{landing.parents[0]}..{landing.sha}")
    return out.splitlines()


def page_changes(base: str, end: str, renames: dict[str, str]):
    """(status, path, added, deleted) for docs pages between two commits, plus src churn."""
    status = {}
    for line in git("diff", "-M", "--name-status", base, end, "--", "public/docs").splitlines():
        parts = line.split("\t")
        code, path = parts[0][0], parts[-1]
        status[path] = (code, parts[1] if code == "R" else None)
    pages, src = [], Counter()
    for line in git("diff", "-M", "--numstat", base, end, "--", "public/docs", "src").splitlines():
        added, deleted, path = line.split("\t", 2)
        if added == "-":
            continue
        if "=>" in path:  # rename: "dir/{old => new}.md" or "old => new"
            path = re.sub(r"\{[^}]* => ([^}]*)\}", r"\1", path)
            path = path.split(" => ")[-1].replace("//", "/")
        churn = int(added) + int(deleted)
        if path.startswith("src/"):
            parts = path.split("/")
            key = "/".join(parts[:5]) if "kotlin" in parts[:3] else "/".join(parts[:3])
            src[key] += churn
            continue
        if not DOC_PAGE.match(path) or path.startswith(GENERATED_DOCS):
            continue
        code, old = status.get(path, ("M", None))
        pages.append((code, path, old, int(added), int(deleted), current_name(path, renames)))
    return pages, src


def line_count(path: str | None) -> int:
    if not path:
        return 0
    try:
        return sum(1 for _ in open(REPO_ROOT / path, encoding="utf-8"))
    except OSError:
        return 0


def brief(history: list[Landing], first: int, last: int, renames: dict[str, str],
          commits_per_landing: int = 25) -> str:
    start, end = history[first], history[last]
    base = start.parents[0] if start.parents else EMPTY_TREE
    real = [h for h in history[first:last + 1] if not h.housekeeping]
    lines = [f"<!-- changelog: {start.sha[:10]}..{end.sha[:10]} -->",
             f"Window: landings {first}..{last} of main ({len(real)} real), "
             f"{start.date} .. {end.date}", "", "Landings:"]
    for landing in real:
        lines.append(f"- {landing.date} {landing.sha[:10]} {landing.subject}")
        merged = landing_commits(landing)
        for subject in merged[:commits_per_landing]:
            lines.append(f"    · {subject}")
        if len(merged) > commits_per_landing:
            lines.append(f"    · ... and {len(merged) - commits_per_landing} more")
    pages, src = page_changes(base, end.sha, renames)
    lines += ["", "Docs pages (current name in brackets; churn = lines added + deleted):"]
    if not pages:
        lines.append("- none")
    for code, path, old, added, deleted, now in sorted(
            pages, key=lambda p: (p[0] != "A", -(p[3] + p[4]))):
        rel = lambda p: p.removeprefix("public/docs/") if p else p  # noqa: E731
        size = line_count(now)
        churn = added + deleted
        tag = {"A": "NEW", "D": "DELETED", "R": "RENAMED"}.get(code, "edited")
        if code == "M" and size and (churn >= 150 or churn >= 0.4 * size):
            tag = "REWRITTEN"
        where = f"[{rel(now)}]" if now else "[gone now]"
        extra = f" (from {rel(old)})" if old else ""
        lines.append(f"- {tag:9} {rel(path)}{extra} +{added} -{deleted} {where}"
                     + (f" now {size} lines" if size else ""))
        if code == "M" and churn < 20 and tag == "edited":
            lines.pop()  # small edits are noise in a brief
    if src:
        lines += ["", "Source areas by churn:"]
        lines += [f"- {area} {n}" for area, n in src.most_common(8)]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--ref", default="main", help="branch whose history the page follows")
    parser.add_argument("--size", type=int, default=10, help="real landings per window")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    sub.add_parser("windows")
    b = sub.add_parser("brief")
    b.add_argument("--windows", type=int, default=1, help="how many uncovered windows to print")
    b.add_argument("--window", help="window number(s) from `windows`, e.g. 12 or 12-15")
    c = sub.add_parser("check")
    c.add_argument("--file", type=Path, default=CHANGELOG, help="page or draft to check")
    c.add_argument("--from", dest="partial_from", metavar="SHA",
                   help="check a draft whose oldest entry starts at this landing, not the root; "
                        "month headings are not required")
    args = parser.parse_args(argv)

    history = landings(args.ref)
    text = CHANGELOG.read_text(encoding="utf-8") if CHANGELOG.exists() else ""
    covered = tip(text, history)

    if args.command == "check":
        text = args.file.read_text(encoding="utf-8")
        partial = None
        if args.partial_from:
            partial = resolve(history, args.partial_from)
            if partial is None:
                parser.error(f"{args.partial_from} is not a first-parent commit of {args.ref}")
        faults = check(text, history, partial_from=partial)
        covered = tip(text, history)
        for fault in faults:
            print(f"{args.file.name} {fault}")
        behind = len([h for h in history[covered + 1:] if not h.housekeeping])
        print(f"{len(parse_changelog(text))} entries, {len(faults)} faults; "
              f"{behind} landings on {args.ref} since the newest entry")
        return 1 if faults else 0

    if args.command == "status":
        if covered < 0:
            print("no entries yet")
        else:
            print(f"newest entry ends at {history[covered].sha[:10]} ({history[covered].date})")
        behind = [h for h in history[covered + 1:] if not h.housekeeping]
        print(f"{len(behind)} landings on {args.ref} not yet in an entry"
              + (f", {behind[0].date} .. {behind[-1].date}" if behind else ""))
        return 0

    all_windows = windows(history, 0, args.size)
    if args.command == "windows":
        for number, (first, last) in enumerate(all_windows):
            real = sum(1 for h in history[first:last + 1] if not h.housekeeping)
            done = "covered" if last <= covered else ""
            print(f"{number:4} {history[first].sha[:10]}..{history[last].sha[:10]} "
                  f"{history[first].date}..{history[last].date} {real:3} {done}")
        return 0

    renames = rename_map(args.ref)
    if args.window:
        lo, _, hi = args.window.partition("-")
        chosen = all_windows[int(lo):int(hi or lo) + 1]
        if not chosen:
            parser.error(f"no window {args.window}; there are {len(all_windows)}")
    else:
        chosen = windows(history, covered + 1, args.size)[:args.windows]
    if not chosen:
        print("nothing to brief: the page covers every landing")
    for first, last in chosen:
        print(brief(history, first, last, renames))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
