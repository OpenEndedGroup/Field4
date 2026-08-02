#!/usr/bin/env python3
"""Remove only clean, registered nightly worktrees whose HEAD is on main.

Dry-run is the default. This intentionally has no force/dirty tier: generated assets and worklogs
still deserve a human decision, while an ancestor-of-main plus a clean status cannot contain unique
commits or uncommitted edits.
"""

from __future__ import annotations

import argparse
import subprocess
from dataclasses import dataclass
from pathlib import Path


ALLOWED_BRANCH_PREFIXES = ("nightly/", "review/", "integration/nightly")
PROTECTED_BRANCHES = {"nightly/journal", "nightly/ci-journal"}


@dataclass(frozen=True)
class Worktree:
    path: Path
    head: str
    branch: str | None


def run_git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        text=True,
        check=check,
    )


def parse_worktrees(porcelain: str) -> list[Worktree]:
    parsed: list[Worktree] = []
    for block in porcelain.strip().split("\n\n"):
        fields: dict[str, str] = {}
        for line in block.splitlines():
            key, _, value = line.partition(" ")
            fields[key] = value
        if fields.get("worktree") and fields.get("HEAD"):
            ref = fields.get("branch")
            branch = ref.removeprefix("refs/heads/") if ref else None
            parsed.append(Worktree(Path(fields["worktree"]), fields["HEAD"], branch))
    return parsed


def branch_is_in_scope(branch: str | None) -> bool:
    return bool(
        branch
        and branch not in PROTECTED_BRANCHES
        and branch.startswith(ALLOWED_BRANCH_PREFIXES)
    )


def tree_size_kib(path: Path) -> int:
    result = subprocess.run(["du", "-sk", str(path)], capture_output=True, text=True, check=False)
    try:
        return int(result.stdout.split()[0])
    except (IndexError, ValueError):
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Remove the candidates printed by the dry run.")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--base", type=Path, help="Allowed worktree directory (default: <repo>-worktrees).")
    args = parser.parse_args()

    repo = Path(run_git(args.repo, "rev-parse", "--show-toplevel").stdout.strip()).resolve()
    base = (args.base or repo.with_name(f"{repo.name}-worktrees")).resolve()
    main_head = run_git(repo, "rev-parse", "main").stdout.strip()
    trees = parse_worktrees(run_git(repo, "worktree", "list", "--porcelain").stdout)

    candidates: list[tuple[Worktree, int]] = []
    for tree in trees:
        path = tree.path.resolve()
        if path.parent != base or not branch_is_in_scope(tree.branch):
            continue
        if run_git(path, "status", "--porcelain").stdout.strip():
            continue
        ancestor = run_git(repo, "merge-base", "--is-ancestor", tree.head, main_head, check=False)
        if ancestor.returncode == 0:
            candidates.append((tree, tree_size_kib(path)))

    total_kib = sum(size for _, size in candidates)
    print(f"main {main_head[:10]} — {len(candidates)} clean merged worktree(s), {total_kib / 1048576:.2f} GiB")
    for tree, size in candidates:
        print(f"  {tree.path}  {size / 1048576:.2f} GiB  {tree.branch}")
    if not args.apply:
        print("DRY RUN — pass --apply to remove exactly these worktrees.")
        return 0

    for tree, _ in candidates:
        print(f"removing {tree.path}")
        removed = run_git(repo, "worktree", "remove", str(tree.path), check=False)
        if removed.returncode != 0:
            print(f"  git declined: {removed.stderr.strip() or removed.stdout.strip()}")
            continue
        assert tree.branch is not None
        deleted = run_git(repo, "branch", "-d", tree.branch, check=False)
        if deleted.returncode != 0:
            print(f"  branch kept: {deleted.stderr.strip() or deleted.stdout.strip()}")
    run_git(repo, "worktree", "prune")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
