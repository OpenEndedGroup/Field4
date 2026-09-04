#!/usr/bin/env python3
"""Remove clean nightly worktrees without risking their commits.

By default candidates must already be merged to main, and their merged branch is deleted. An exact
``--completed PATH`` selects a clean registered nightly checkout even when its branch is unmerged;
that mode removes only the checkout and always preserves the branch. It is the safe completion-time
reclaim: the branch carries the work, while a multi-GiB build directory need not remain mounted.

Dry-run is always the default. There is intentionally no dirty tier: generated assets and worklogs
still deserve a human decision.
"""

from __future__ import annotations

import argparse
import shutil
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


@dataclass(frozen=True)
class DiskRow:
    path: Path
    size_kib: int
    verdict: str
    reclaimable: bool


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


def parse_lsof_cwds(output: str) -> dict[Path, set[int]]:
    """Parse ``lsof -Fpn`` records into cwd -> pids without depending on its table format."""
    result: dict[Path, set[int]] = {}
    pid: int | None = None
    for line in output.splitlines():
        if line.startswith("p"):
            try:
                pid = int(line[1:])
            except ValueError:
                pid = None
        elif line.startswith("n") and pid is not None:
            path = Path(line[1:]).resolve()
            result.setdefault(path, set()).add(pid)
    return result


def live_cwds() -> dict[Path, set[int]]:
    lsof = shutil.which("lsof")
    if not lsof:
        return {}
    completed = subprocess.run(
        [lsof, "-n", "-a", "-d", "cwd", "-Fpn"],
        capture_output=True,
        text=True,
        check=False,
    )
    return parse_lsof_cwds(completed.stdout) if completed.returncode in (0, 1) else {}


def pids_inside(path: Path, cwds: dict[Path, set[int]]) -> set[int]:
    root = path.resolve()
    return {
        pid
        for cwd, pids in cwds.items()
        if cwd == root or root in cwd.parents
        for pid in pids
    }


def disk_rows(
    roots: list[Path], trees: list[Worktree], repo: Path, main_head: str,
    cwds: dict[Path, set[int]],
) -> list[DiskRow]:
    registered = {tree.path.resolve(): tree for tree in trees}
    rows: list[DiskRow] = []
    for root in roots:
        if not root.is_dir():
            continue
        for child in root.iterdir():
            if not child.is_dir():
                continue
            path = child.resolve()
            size = tree_size_kib(path)
            tree = registered.get(path)
            active = pids_inside(path, cwds)
            if tree is None:
                verdict, reclaimable = "unregistered; report only", False
            elif active:
                verdict, reclaimable = f"live cwd pid(s) {','.join(map(str, sorted(active)))}", False
            elif not branch_is_in_scope(tree.branch):
                verdict, reclaimable = "out of automatic scope", False
            elif run_git(path, "status", "--porcelain").stdout.strip():
                verdict, reclaimable = "dirty", False
            elif run_git(repo, "merge-base", "--is-ancestor", tree.head, main_head, check=False).returncode == 0:
                verdict, reclaimable = "clean and merged", True
            else:
                verdict, reclaimable = "clean, branch preserved by --completed", False
            rows.append(DiskRow(path, size, verdict, reclaimable))
    return sorted(rows, key=lambda row: (-row.size_kib, str(row.path)))


def print_disk_report(rows: list[DiskRow], *, free_kib: int, total_kib: int, required_kib: int) -> bool:
    deficit = max(0, required_kib - free_kib)
    print(
        f"disk: Avail {free_kib / 1048576:.2f} GiB / {total_kib / 1048576:.2f} GiB; "
        f"required {required_kib / 1048576:.2f} GiB; deficit {deficit / 1048576:.2f} GiB"
    )
    subtotal = 0
    print("ranked worktree footprint (safe reclaim subtotal counts only clean merged rows):")
    for row in rows:
        if row.reclaimable:
            subtotal += row.size_kib
        marker = f"safe subtotal {subtotal / 1048576:.2f} GiB" if row.reclaimable else "not counted"
        print(f"  {row.size_kib / 1048576:6.2f} GiB  {row.verdict}; {marker}  {row.path}")
    closes = free_kib + subtotal >= required_kib
    print(
        f"safe reclaimable total {subtotal / 1048576:.2f} GiB — "
        + ("enough to close the requirement" if closes else "no safe subset closes the requirement")
    )
    return deficit == 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Remove the candidates printed by the dry run.")
    parser.add_argument(
        "--completed", action="append", type=Path, default=[], metavar="PATH",
        help="Select this exact clean nightly checkout and keep its branch (repeatable).",
    )
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--base", type=Path, help="Allowed worktree directory (default: <repo>-worktrees).")
    parser.add_argument(
        "--disk-floor-gib", type=float, metavar="GIB",
        help="Print a fresh ranked disk report and fail if Avail cannot preserve this floor.",
    )
    parser.add_argument(
        "--reserve-gib", type=float, default=0.0, metavar="GIB",
        help="Space reserved for another worktree population, added to --disk-floor-gib.",
    )
    parser.add_argument(
        "--planned-gib", type=float, default=0.0, metavar="GIB",
        help="Expected cost of worktrees about to be created, added to the reservation check.",
    )
    parser.add_argument(
        "--report-root", action="append", type=Path, default=[], metavar="PATH",
        help="Additional worktree directory to include in the ranked report (repeatable).",
    )
    args = parser.parse_args()

    repo = Path(run_git(args.repo, "rev-parse", "--show-toplevel").stdout.strip()).resolve()
    base = (args.base or repo.with_name(f"{repo.name}-worktrees")).resolve()
    main_head = run_git(repo, "rev-parse", "main").stdout.strip()
    trees = parse_worktrees(run_git(repo, "worktree", "list", "--porcelain").stdout)
    cwd_owners = live_cwds()
    completed = {path.resolve() for path in args.completed}
    registered = {tree.path.resolve() for tree in trees}
    missing = completed - registered
    if missing:
        parser.error("--completed path is not a registered worktree: " + ", ".join(map(str, sorted(missing))))

    candidates: list[tuple[Worktree, int]] = []
    for tree in trees:
        path = tree.path.resolve()
        if path.parent != base or not branch_is_in_scope(tree.branch):
            continue
        if completed and path not in completed:
            continue
        if run_git(path, "status", "--porcelain").stdout.strip():
            if path in completed:
                parser.error(f"--completed worktree is dirty: {path}")
            continue
        active = pids_inside(path, cwd_owners)
        if active:
            print(f"keeping {path}: live process cwd pid(s) {','.join(map(str, sorted(active)))}")
            continue
        if path in completed:
            candidates.append((tree, tree_size_kib(path)))
            continue
        ancestor = run_git(repo, "merge-base", "--is-ancestor", tree.head, main_head, check=False)
        if ancestor.returncode == 0:
            candidates.append((tree, tree_size_kib(path)))

    total_kib = sum(size for _, size in candidates)
    kind = "completed checkout(s), branches preserved" if completed else "clean merged worktree(s)"
    print(f"main {main_head[:10]} — {len(candidates)} {kind}, {total_kib / 1048576:.2f} GiB")
    for tree, size in candidates:
        print(f"  {tree.path}  {size / 1048576:.2f} GiB  {tree.branch}")

    budget_ok = True
    if args.disk_floor_gib is not None:
        if args.disk_floor_gib < 0 or args.reserve_gib < 0 or args.planned_gib < 0:
            parser.error("disk floor, reservation and planned cost must be non-negative")
        usage = shutil.disk_usage(repo)
        report_roots = [base, repo / ".claude/worktrees", *args.report_root]
        # Keep the report stable when a caller repeats a built-in root explicitly.
        report_roots = list(dict.fromkeys(path.resolve() for path in report_roots))
        rows = disk_rows(report_roots, trees, repo, main_head, cwd_owners)
        required_kib = int((args.disk_floor_gib + args.reserve_gib + args.planned_gib) * 1048576)
        budget_ok = print_disk_report(
            rows,
            free_kib=usage.free // 1024,
            total_kib=usage.total // 1024,
            required_kib=required_kib,
        )
    if not args.apply:
        print("DRY RUN — pass --apply to remove exactly these worktrees.")
        return 0 if budget_ok else 2

    for tree, _ in candidates:
        print(f"removing {tree.path}")
        remove_args = ["worktree", "remove"]
        # Git requires --force for a clean worktree with an initialized submodule. Dirt was already
        # rejected above, so this does not broaden the selected population.
        if completed:
            remove_args.append("--force")
        removed = run_git(repo, *remove_args, str(tree.path), check=False)
        if removed.returncode != 0:
            print(f"  git declined: {removed.stderr.strip() or removed.stdout.strip()}")
            continue
        if not completed:
            assert tree.branch is not None
            deleted = run_git(repo, "branch", "-d", tree.branch, check=False)
            if deleted.returncode != 0:
                print(f"  branch kept: {deleted.stderr.strip() or deleted.stdout.strip()}")
    run_git(repo, "worktree", "prune")
    return 0 if budget_ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
