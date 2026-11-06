#!/usr/bin/env python3
"""Classify whether a CI-branch commit's content has reached `main`.

A drop-in, finishing replacement for `landed_check.sh` (backlog #114). Same three-way
verdict, same output columns; what changes is that it can get to the end.

Why not `git cherry` alone: its `+` false-positive rate on this repo has measured 75%-96%,
because any context drift makes a landed change look absent. Why not whole-file
`git diff --quiet <sha>:<f> main:<f>`: identical proves LANDED, but differing proves
nothing -- the file moves on for unrelated reasons. So both sides of the patch are tested
separately, which is what distinguishes the three real outcomes:

  LANDED       added lines present on main
  OUTSTANDING  added lines absent AND removed lines still present   (real debt)
  MOOT         neither side present -- the code it edited is gone

Why the shell version could not finish (measured 09-18, again 09-19): for every changed
line it spawned `grep -qF` against a freshly written copy of the whole `main` blob, so a
generated-docs commit was O(lines x filesize) in *subprocesses*. On 09-19 it sat on one
27433-line commit for over twenty minutes. Here each blob is read once and matched in
process, which is the same predicate -- an exact line hit is checked against a set first,
and only a miss falls back to the substring search `grep -F` was doing.

Usage:  routines/nightly-ci/landed_check.py [--repo PATH]   (default: the checkout this file is in) [--bulk-threshold N] [--quiet]
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

SKIP_SUFFIXES = (".png", ".jpg", ".jpeg", ".mp4", ".bin", ".gz", ".zip", ".pdf", ".mov")
MIN_LINE = 12  # shorter lines carry no evidence; the shell version used the same floor


def git(repo: str, *args: str, binary: bool = False):
    result = subprocess.run(
        ["git", "-C", repo, *args],
        capture_output=True,
        check=False,
    )
    if binary:
        return result.stdout
    return result.stdout.decode("utf-8", "replace")


@dataclass
class Tally:
    added_found: int = 0
    added_total: int = 0
    removed_found: int = 0
    removed_total: int = 0
    missing_file: bool = False


def diff_sides(repo: str, sha: str, path: str) -> tuple[list[str], list[str]]:
    """The commit's added and removed lines for one file, long ones only."""
    text = git(repo, "show", "--format=", "-U0", sha, "--", path)
    added, removed = [], []
    for line in text.split("\n"):
        if line.startswith("+") and not line.startswith("++"):
            body = line[1:]
            if len(body) >= MIN_LINE:
                added.append(body)
        elif line.startswith("-") and not line.startswith("--"):
            body = line[1:]
            if len(body) >= MIN_LINE:
                removed.append(body)
    return added, removed


def present(needles: list[str], blob_lines: set[str], blob_text: str) -> int:
    """How many of [needles] appear in the blob -- exact line first, substring as fallback."""
    hits = 0
    for needle in needles:
        if needle in blob_lines or needle in blob_text:
            hits += 1
    return hits


def classify(repo: str, sha: str, bulk_threshold: int) -> tuple[str, Tally]:
    tally = Tally()
    files = [f for f in git(repo, "show", "--name-only", "--format=", sha).split("\n") if f]
    for path in files:
        if path.endswith(SKIP_SUFFIXES):
            continue
        blob = git(repo, "cat-file", "blob", f"main:{path}", binary=True)
        if not blob and subprocess.run(
            ["git", "-C", repo, "cat-file", "-e", f"main:{path}"], capture_output=True
        ).returncode != 0:
            tally.missing_file = True
            continue
        blob_text = blob.decode("utf-8", "replace")
        blob_lines = set(blob_text.split("\n"))

        added, removed = diff_sides(repo, sha, path)
        if bulk_threshold and len(added) + len(removed) > bulk_threshold:
            return "BULK-SKIPPED", tally
        tally.added_total += len(added)
        tally.removed_total += len(removed)
        tally.added_found += present(added, blob_lines, blob_text)
        tally.removed_found += present(removed, blob_lines, blob_text)

    t = tally
    if t.added_total == 0 and not t.missing_file:
        return "NO-TEXT", t
    if t.added_total > 0 and t.added_found == t.added_total:
        return "LANDED", t
    if t.added_found == 0 and t.removed_found == 0 and not t.missing_file:
        return "MOOT", t
    if t.added_found == 0 and t.removed_total > 0 and t.removed_found == t.removed_total:
        return "OUTSTANDING", t
    return "PARTIAL", t


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument(
        "--bulk-threshold",
        type=int,
        default=4000,
        help="Diffs larger than this are BULK-SKIPPED. Generated-artifact commits are already "
        "bulk-verified by a green docsGates; 0 disables.",
    )
    ap.add_argument("--quiet", action="store_true", help="Print only non-LANDED verdicts.")
    args = ap.parse_args()

    branches = [
        b
        for b in git(
            args.repo,
            "for-each-ref",
            "--format=%(refname:short)",
            "refs/heads/nightly/ci",
            "refs/heads/claude",
        ).split("\n")
        if b
    ]

    for branch in branches:
        cherry = git(args.repo, "cherry", "main", branch)
        for line in cherry.split("\n"):
            parts = line.split()
            if len(parts) != 2 or parts[0] != "+":
                continue
            sha = parts[1]
            verdict, t = classify(args.repo, sha, args.bulk_threshold)
            if args.quiet and verdict == "LANDED":
                continue
            subject = git(args.repo, "log", "-1", "--format=%h %s", sha).strip()
            print(
                f"{verdict}  added={t.added_found}/{t.added_total} "
                f"removed_still={t.removed_found}/{t.removed_total} "
                f"newfile={int(t.missing_file)}  {branch}  {subject}",
                flush=True,
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
