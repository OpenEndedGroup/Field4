#!/usr/bin/env python3
"""Keep each web publication whole across a merge: its catalog entry and its folder from one side.

    uv run python tools/publication_merge.py        # during a merge (MERGE_HEAD set), before committing

``public/docs/assets/web_publications/**`` is ``merge=field-generated``: on a file both sides
changed, the merge keeps ours, and on a file only one side changed, git takes that side. A
publication is several files, though, and ``catalog.json`` records the hash of every one of them,
so a file-by-file merge can pair one side's catalog with the other side's captured files. On
2026-11-11 a branch that had regenerated the whole bundle merged main (keeping its own copies of
every capture), and the landing merge then kept main's ``catalog.json``: the catalog matched none
of the 201 publications, ``docs_publications.py sync`` recaptured all of them (80 minutes), and the
landing bounced three times on whichever slow page timed out first.

So after the merge, per publication (keyed by its source in ``catalog.json``): take their entry
and their folder if only their side changed the entry since the merge base, or if both did and only
theirs records the sources the merged tree now has; otherwise keep ours, entry and folder alike. Either way entry and files agree, and the sync recaptures only what is
actually stale against the merged sources. Content-addressed ``runtime/`` files are left as the
merge made them (a union; nothing references a runtime by anything but its hash).
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path, PurePosixPath

ROOT_REL = "public/docs/assets/web_publications"
CATALOG_REL = f"{ROOT_REL}/catalog.json"
SECTIONS = ("documents", "emptyDocuments")
DEFERRED = "deferred"


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=repo, check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True)


def _catalog(repo: Path, rev: str) -> dict:
    shown = _git(repo, "show", f"{rev}:{CATALOG_REL}", check=False)
    return json.loads(shown.stdout) if shown.returncode == 0 else {"version": 1, "documents": {}}


def _folder(entry: dict | None) -> str | None:
    """``public/docs/assets/web_publications/<name>`` for a captured entry; None for a code-only one."""
    if not entry or not entry.get("file"):
        return None
    parts = PurePosixPath(entry["file"]).parts  # assets/web_publications/<name>/document/metadata.edn
    if len(parts) < 4 or parts[:2] != ("assets", "web_publications"):
        return None
    return f"{ROOT_REL}/{parts[2]}"


def _files(repo: Path, rev: str, folder: str) -> set[str]:
    out = _git(repo, "ls-tree", "-r", "--name-only", rev, "--", folder, check=False).stdout
    return {line for line in out.splitlines() if line}


def _restore(repo: Path, rev: str, folder: str) -> None:
    """Make [folder] in the index and working tree exactly what it is at [rev]."""
    present = {line for line in _git(repo, "ls-files", "--", folder).stdout.splitlines() if line}
    wanted = _files(repo, rev, folder)
    stray = sorted(present - wanted)
    if stray:
        _git(repo, "rm", "-q", "-f", "--", *stray)
    if wanted:
        _git(repo, "checkout", rev, "--", folder)


def _fresh(repo: Path, entry: dict | None) -> bool:
    """Whether every plain-file source [entry] records has that digest in the merged working tree.
    (``page#capture-box=uid`` sources are skipped: only docs_publications.py can digest those.)"""
    if not entry:
        return False
    for name, expected in entry.get("sources", {}).items():
        if "#" in name:
            continue
        path = repo / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            return False
    return True


def _fresh_capture(repo: Path, entry: dict | None) -> bool:
    """A completed working-tree refresh: matching sources and an intact output bundle."""
    if not entry or not entry.get("sources") or not _fresh(repo, entry):
        return False
    files = entry.get("files") or {}
    if entry.get("file") and not files:
        return False
    return all((repo / "public/docs" / name).is_file()
               and hashlib.sha256((repo / "public/docs" / name).read_bytes()).hexdigest() == expected
               for name, expected in files.items())


def unify(repo: Path, ours: str = "HEAD", theirs: str = "MERGE_HEAD") -> list[str]:
    """Keep complete publication bundles, including fresh captures made during an earlier attempt.
    Returns names taken from their side; does nothing if their publications did not change."""
    base = _git(repo, "merge-base", ours, theirs).stdout.strip()
    changed = _git(repo, "diff", "--name-only", base, theirs, "--", ROOT_REL).stdout.strip()
    if not changed:
        return []  # git already took ours for every publication file
    cats = {side: _catalog(repo, rev) for side, rev in (("base", base), ("ours", ours), ("theirs", theirs))}
    working_path = repo / CATALOG_REL
    try:
        working = json.loads(working_path.read_text()) if working_path.is_file() else {}
    except json.JSONDecodeError:
        working = {}  # The branch catalogs still repair an incomplete text merge.
    refreshed = {}
    for section in SECTIONS:
        for source, entry in working.get(section, {}).items():
            branches = tuple(cats[side].get(section, {}).get(source) for side in ("ours", "theirs"))
            if entry not in branches and _fresh_capture(repo, entry):
                refreshed[source] = (section, entry)
    result = json.loads(json.dumps(cats["ours"]))
    taken: list[str] = []
    for section in SECTIONS:
        sources = set().union(*(c.get(section, {}) for c in [*cats.values(), working]))
        for source in sorted(sources):
            b, o, t = (cats[s].get(section, {}).get(source) for s in ("base", "ours", "theirs"))
            # --continue may follow a partial publication refresh. Preserve each complete new
            # capture rather than replacing it with a branch's older bundle and recapturing it.
            if source in refreshed:
                captured_section, local = refreshed[source]
                if captured_section == section:
                    result.setdefault(section, {})[source] = local
                else:
                    result.get(section, {}).pop(source, None)
                deferral = working.get(DEFERRED, {}).get(source)
                if deferral is None:
                    result.get(DEFERRED, {}).pop(source, None)
                else:
                    result.setdefault(DEFERRED, {})[source] = deferral
                continue
            # Only theirs changed it, or both did and only theirs was captured from what the
            # merged tree now holds (a branch that merges main after main recaptured).
            take_theirs = t != b and (o == b or (t is not None and _fresh(repo, t) and not _fresh(repo, o)))
            entry = t if take_theirs else o
            if entry is None:
                result.get(section, {}).pop(source, None)
            else:
                result.setdefault(section, {})[source] = entry
            if take_theirs:
                taken.append(PurePosixPath(source).parent.name)
            # A deferral (docs_publications.py DEFERRED) belongs to the entry it describes.
            deferral = cats["theirs" if take_theirs else "ours"].get(DEFERRED, {}).get(source)
            if deferral is None:
                result.get(DEFERRED, {}).pop(source, None)
            else:
                result.setdefault(DEFERRED, {})[source] = deferral
            for folder in {f for f in (_folder(b), _folder(o), _folder(t)) if f}:
                _restore(repo, theirs if take_theirs else ours, folder)
    if DEFERRED in result and not result[DEFERRED]:
        del result[DEFERRED]
    path = repo / CATALOG_REL
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    _git(repo, "add", "--", CATALOG_REL)
    return taken


def main() -> int:
    repo = Path(_git(Path.cwd(), "rev-parse", "--show-toplevel").stdout.strip())
    if _git(repo, "rev-parse", "-q", "--verify", "MERGE_HEAD", check=False).returncode != 0:
        print("publication_merge: no merge in progress", file=sys.stderr)
        return 2
    taken = unify(repo)
    if taken:
        print(f"publication_merge: took {len(taken)} publication(s) whole from the merged branch: {', '.join(taken)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
