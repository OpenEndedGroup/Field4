#!/usr/bin/env python3
"""Measure hand-written code across the project's whole git history, one point per day.

Counts `.kt`, `.js`, `.html`, `.swift` and `.py` in Field4 *and* in the repos it pulls in as
submodules, and emits a JSON series `tools/loc_chart.py` turns into a graph.

The counting is arithmetic; the corrections below are what make the number mean anything, and
each one was worth tens of thousands of lines when it was missing:

* **Vendored code is a third of the raw count.** Monaco, the three.js editor and its
  `examples/` trees, A-Frame, super-three, CodeMirror and its language modes, acorn/tern,
  tess2 and every `.min.js` together account for ~267k lines. See `VENDORED_DIRS` /
  `VENDORED_NAMES`.
* **`--first-parent` gives a badly wrong curve.** March-May 2026 happened entirely on a side
  branch, so the first-parent line is flat across it and then jumps 116k lines in one merge
  on 14 June 2026. We walk *every* commit reachable from HEAD in committer-date order
  instead, and take one point per day from the largest tree committed that day, which follows
  the leading edge while two branches run in parallel.
* **Field4 has two roots.** The visionOS app started as its own repo with its own initial
  commit, and in date order those commits interleave with Field4's as a +/-333k sawtooth.
  `_is_field4` drops that lineage. Its Swift now lives in `native/field-visionos` on the main
  line, so the code is still counted — only the stray commits are not.
* **Submodules are followed through the gitlink, not walked separately.** At each Field4
  commit we read the submodule sha that commit points at and count *that* tree. So the
  audio service appears on the day Field4 started pointing at it (4 Aug 2026) and moves when
  the pin moves, which is what "how big is the project" means. Walking the submodule's own
  history instead would draw code that Field4 had not yet adopted.

Also note Field4's first commit (11 Jun 2025) is empty and nothing follows it until
3 Nov 2025 — the series starts at the first commit that actually has code.

Reading every commit's tree is the slow step (~1s each unparallelised); `--jobs` readers bring
a full run to about 15s. There is deliberately no cache — see the note in `main`.

Usage:
    python3 tools/loc_history.py                        # writes build/loc/series.json
    python3 tools/loc_history.py --out /tmp/series.json --jobs 8
    python3 tools/loc_history.py --by-file              # today's per-file classification
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import threading
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent

# Stack order in the chart, and the order reported everywhere.
LANGS = ("kt", "js", "html", "swift", "py")
LANG_NAMES = {"kt": "Kotlin", "js": "JavaScript", "html": "HTML",
              "swift": "Swift", "py": "Python"}

COUNTED_EXT = re.compile(r"\.(" + "|".join(LANGS) + r")$", re.IGNORECASE)

# Submodule path in Field4 -> where its checkout lives. Counted through the gitlink; see the
# module docstring. A pin we cannot resolve locally is skipped with a warning, not a crash.
SUBMODULES = {"services/field-audio-ai": REPO_ROOT / "services/field-audio-ai"}

# Third-party code that happens to live in the tree. Substring match on the full path.
VENDORED_DIRS = (
    "/monaco/",
    "public/min/vs/",
    "dev_docs/min/vs/",
    "/extensions/remote_3editor/examples/",   # the three.js editor's example suite
    "/extensions/remote_3editor/js/",          # ...and the editor itself
    "/vendor/",                                # aframe, super-three, tone
    "public/docs/assets/mode/",                # CodeMirror language modes
)
# Vendored single files that sit among our own, matched on basename.
VENDORED_NAMES = frozenset({
    "acorn.js", "acorn_loose.js", "tern.js", "infer.js", "def.js", "walk.js",
    "signal.js", "js-object-pretty-print.js", "tess2.js", "codemirror.js",
})
# Machine-written or captured, not authored. Prefix match on the full path.
GENERATED_DIRS = (
    "src/generatedLinalg/",
    "public/docs/assets/agent_transcripts/",
    "public/docs/assets/canonical_graphics/",
    "public/docs/assets/rectsl_gallery/",
    "public/docs/assets/stacksl_gallery/",
    "templates/",                              # saved Field sheets, not source
    "thumbnails-proof/",
)

# Commits before this have no `src/jvmMain/kotlin` because the layout did not exist yet,
# rather than because they belong to the other root. See `_is_field4`.
LAYOUT_EPOCH = "2025-11-04"

ORIGIN_MARKER = "src/jvmMain/kotlin"


def _git(repo: Path, *args: str) -> str:
    out = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, check=True
    )
    return out.stdout.decode(errors="replace")


def classify(path: str) -> str:
    """`ours`, `vendored` or `generated` for one path, relative to the Field4 root.

    Submodule files arrive already prefixed with their submodule path, so one rule set
    covers every repo.
    """
    name = path.rsplit("/", 1)[-1]
    if name.endswith(".min.js") or name in VENDORED_NAMES:
        return "vendored"
    if any(frag in path for frag in VENDORED_DIRS):
        return "vendored"
    if any(path.startswith(prefix) for prefix in GENERATED_DIRS):
        return "generated"
    return "ours"


def language(path: str) -> str:
    return path.rsplit(".", 1)[-1].lower()


def _commits() -> list[tuple[str, str]]:
    """Every commit reachable from HEAD as (sha, committer-date), oldest first.

    Deliberately not `--first-parent` — see the module docstring.
    """
    raw = _git(REPO_ROOT, "rev-list", "--pretty=format:%H\t%cI", "HEAD").splitlines()
    commits = [line.split("\t") for line in raw if not line.startswith("commit ")]
    commits.sort(key=lambda c: c[1])
    return commits


def _read_tree(repo: Path, sha: str, prefix: str = "") -> tuple[list, dict]:
    """(counted blobs, submodule pins) for one commit's tree.

    Blobs come back as (path, blob-sha) with `prefix` applied; pins map a Field4-relative
    submodule path to the commit sha that tree records for it.
    """
    blobs, pins = [], {}
    for line in _git(repo, "ls-tree", "-r", sha).splitlines():
        meta, path = line.split("\t", 1)
        _mode, kind, obj = meta.split()
        full = prefix + path
        if kind == "blob":
            if COUNTED_EXT.search(path):
                blobs.append((full, obj))
        elif kind == "commit" and full in SUBMODULES:
            pins[full] = obj
    return blobs, pins


def _load_trees(commits, jobs: int) -> dict[str, tuple[list, dict]]:
    """Read every Field4 commit's tree. Deliberately uncached — see `main`."""
    shas = [sha for sha, _ in commits]
    print(f"reading {len(shas)} trees", file=sys.stderr)
    trees = {}
    with ThreadPoolExecutor(max_workers=jobs) as pool:
        results = pool.map(lambda s: (s, _read_tree(REPO_ROOT, s)), shas)
        for n, (sha, tree) in enumerate(results):
            trees[sha] = tree
            if n and n % 400 == 0:
                print(f"  {n}/{len(shas)}", file=sys.stderr)
    return trees


def _load_submodule_trees(trees) -> dict[tuple[str, str], list]:
    """Every distinct (submodule path, pinned sha) seen in history -> its counted blobs."""
    wanted = {(path, sha) for _, pins in trees.values() for path, sha in pins.items()}
    out = {}
    for path, sha in sorted(wanted):
        repo = SUBMODULES[path]
        if not (repo / ".git").exists():
            print(f"  skipping {path}: no checkout at {repo}", file=sys.stderr)
            continue
        probe = subprocess.run(["git", "-C", str(repo), "cat-file", "-e", f"{sha}^{{commit}}"],
                               capture_output=True)
        if probe.returncode != 0:
            print(f"  skipping {path}@{sha[:9]}: not present locally "
                  f"(run `git submodule update`)", file=sys.stderr)
            continue
        blobs, _ = _read_tree(repo, sha, prefix=f"{path}/")
        out[(path, sha)] = blobs
    if out:
        print(f"read {len(out)} submodule pin(s)", file=sys.stderr)
    return out


def _line_counts(repo: Path, blobs: set[str]) -> dict[str, int]:
    """Lines per blob, in one `cat-file --batch` pass against `repo`."""
    if not blobs:
        return {}
    proc = subprocess.Popen(
        ["git", "-C", str(repo), "cat-file", "--batch"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    )
    order = list(blobs)

    # Feed from a thread: the request list is far larger than a pipe buffer, so writing it
    # all up front before reading stdout deadlocks against git's own blocked writes.
    def feed() -> None:
        proc.stdin.write(("\n".join(order) + "\n").encode())
        proc.stdin.close()

    threading.Thread(target=feed, daemon=True).start()

    counts = {}
    for blob in order:
        size = int(proc.stdout.readline().decode().split()[2])
        data = proc.stdout.read(size)
        proc.stdout.read(1)  # trailing newline git adds after each blob
        n = data.count(b"\n")
        if data and not data.endswith(b"\n"):
            n += 1  # a final line with no newline still counts
        counts[blob] = n
    proc.wait()
    return counts


def _is_field4(sha: str, when: str, trees) -> bool:
    """False for commits on the visionOS app's original root. See the module docstring."""
    if when < LAYOUT_EPOCH:
        return True
    return any(path.startswith(ORIGIN_MARKER) for path, _ in trees[sha][0])


def build_series(jobs: int) -> list[dict]:
    """One record per day: the largest Field4 tree committed that day, forward-filled."""
    commits = _commits()
    trees = _load_trees(commits, jobs)
    commits = [(sha, when) for sha, when in commits if _is_field4(sha, when, trees)]
    sub_trees = _load_submodule_trees({s: trees[s] for s, _ in commits})

    main_lines = _line_counts(REPO_ROOT, {b for s, _ in commits for _, b in trees[s][0]})
    sub_lines = {}
    for (path, _sha), blobs in sub_trees.items():
        sub_lines.setdefault(path, {}).update(
            _line_counts(SUBMODULES[path], {b for _, b in blobs})
        )
    print(f"measuring {len(main_lines) + sum(len(v) for v in sub_lines.values())} "
          f"unique blobs", file=sys.stderr)

    def tally(sha: str) -> dict[str, int]:
        blobs, pins = trees[sha]
        totals: dict[str, int] = defaultdict(int)
        for path, blob in blobs:
            totals[f"{classify(path)}.{language(path)}"] += main_lines[blob]
        for sub_path, pin in pins.items():
            for path, blob in sub_trees.get((sub_path, pin), ()):
                totals[f"{classify(path)}.{language(path)}"] += sub_lines[sub_path][blob]
        return dict(totals)

    ours = lambda t: sum(v for k, v in t.items() if k.startswith("ours."))
    by_day: dict[str, dict] = {}
    for sha, when in commits:
        day = when[:10]
        totals = tally(sha)
        if day not in by_day or ours(totals) > ours(by_day[day]):
            by_day[day] = totals

    # Start where there is actually code — the 11 Jun 2025 initial commit is empty.
    days = sorted(d for d in by_day if ours(by_day[d]) > 0)
    if not days:
        return []

    series, carried = [], None
    cursor, last = date.fromisoformat(days[0]), date.fromisoformat(days[-1])
    while cursor <= last:
        key = cursor.isoformat()
        if key in by_day:
            carried = by_day[key]
        series.append({
            "date": key,
            "ours": {lang: carried.get(f"ours.{lang}", 0) for lang in LANGS},
            "vendored": sum(v for k, v in carried.items() if k.startswith("vendored.")),
            "generated": sum(v for k, v in carried.items() if k.startswith("generated.")),
        })
        cursor += timedelta(days=1)
    return series


def report_by_file() -> int:
    """Today's tracked files with their classification — for auditing the rules above."""
    buckets: dict[str, list[tuple[int, str]]] = defaultdict(list)

    def absorb(repo: Path, prefix: str) -> None:
        for rel in _git(repo, "ls-files").splitlines():
            if not COUNTED_EXT.search(rel):
                continue
            full = repo / rel
            if not full.exists():
                continue
            data = full.read_bytes()
            n = data.count(b"\n") + (1 if data and not data.endswith(b"\n") else 0)
            path = prefix + rel
            buckets[f"{classify(path)}.{language(path)}"].append((n, path))

    absorb(REPO_ROOT, "")
    for sub_path, repo in SUBMODULES.items():
        if (repo / ".git").exists():
            absorb(repo, f"{sub_path}/")

    for key in sorted(buckets):
        rows = sorted(buckets[key], reverse=True)
        print(f"\n=== {key}: {len(rows)} files, {sum(n for n, _ in rows):,} lines")
        for n, path in rows[:15]:
            print(f"  {n:>7,}  {path}")
        if len(rows) > 15:
            print(f"  ... {len(rows) - 15} more")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, default=REPO_ROOT / "build/loc/series.json")
    # No tree cache on purpose: pickling the path->blob lists for every commit costs ~250MB
    # on disk to save ~12s on a run that takes 15s from cold. Reading them is what --jobs is for.
    ap.add_argument("--jobs", type=int, default=12, help="concurrent `git ls-tree` readers")
    ap.add_argument("--by-file", action="store_true",
                    help="instead of the series, print how today's files classify")
    args = ap.parse_args()

    if args.by_file:
        return report_by_file()

    series = build_series(args.jobs)
    if not series:
        print("no commits with counted files", file=sys.stderr)
        return 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(series, separators=(",", ":")))

    first, last = series[0], series[-1]
    total = sum(last["ours"].values())
    print(f"\n{first['date']} -> {last['date']}  ({len(series)} days)")
    for lang in LANGS:
        print(f"  {LANG_NAMES[lang]:<11} {last['ours'][lang]:>9,}")
    print(f"  {'ours':<11} {total:>9,}")
    print(f"  {'vendored':<11} {last['vendored']:>9,}  (excluded)")
    print(f"  {'generated':<11} {last['generated']:>9,}  (excluded)")
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
