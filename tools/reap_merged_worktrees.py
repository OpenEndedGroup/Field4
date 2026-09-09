#!/usr/bin/env python3
"""Remove clean nightly worktrees without risking their commits.

By default candidates must already be merged to main, and their merged branch is deleted. An exact
``--completed PATH`` selects a clean registered nightly checkout even when its branch is unmerged;
that mode removes only the checkout and always preserves the branch. It is the safe completion-time
reclaim: the branch carries the work, while a multi-GiB build directory need not remain mounted.

Dry-run is always the default. There is intentionally no dirty tier: generated assets and worklogs
still deserve a human decision.

WHAT MAY BE REMOVED — the retention policy
------------------------------------------
The long version, with the measurements, is ``dev_docs/worktree_retention_policy.md``.

A worktree is only ever a candidate if it matches a *retention family*: a named rule that pairs a
worktree **root directory** with a set of **branch prefixes**. Both halves must match. Run
``--explain-policy`` to print the table without scanning anything.

  nightly        (on by default)  <repo>-worktrees        nightly/  review/  integration/nightly
  docs-blast     (--family)       both roots              docs/language-
  agent-session  (--family)       <repo>/.claude/worktrees  claude/

Two axes, not one, because the scope gap here was measured to be the *root*, not the prefix: on
2026-09-09 the 33 worktrees under ``.claude/worktrees`` held 61.4 GiB -- 54% of the whole footprint,
and the population that actually grows -- and no branch name could have made one of them a candidate.
``--family`` is purely additive: naming one can only ever *add* a population, never remove a
protection, and [PROTECTED_BRANCHES] outranks every family.

Nothing is removed merely for matching a family. A candidate must additionally be clean, merged into
main, free of live process cwds, and **idle** (see below).

THE IDLE GUARD
--------------
Measured on 2026-09-09: a dry run offered ``2026-09-09-1-kratos-parameter-studies`` -- a sibling
agent that committed one minute later -- because at the instant of the scan it was clean and its
snapshotted HEAD was still an ancestor of main. The ``lsof`` cwd check is the only thing that stood
in the way, and it is a race rather than an invariant: in that same run it saved two of the three
worktrees in the cohort and missed the third, because agent shells reset cwd to the primary repo
between commands. So [branch_idle_hours] adds a guard that does not depend on process timing -- the
newest sign of life on the branch, from its reflog tip or its commit date, whichever is more recent.
Tonight that separated the two populations by three orders of magnitude: 0.01--0.17 h idle for the
in-flight cohort against 45.75 h and 372 h for finished ones.

Two things here exist because of a measured leak. ``git worktree remove`` unregisters a worktree
*before* it finishes deleting the directory, so a single leftover file makes git delete most of the
content, drop the entry from ``git worktree list``, and exit non-zero with ``Directory not empty`` --
which this tool used to print as an ordinary decline. The checkout survived on disk and, being
unregistered, was invisible to every later run. 640 MiB was lost that way on 2026-08-27. So:
[sweep_finder_metadata] removes the usual culprit before the attempt, and a declined removal is now
diagnosed rather than shrugged at -- if the directory outlived its registration it is reported as an
ORPHAN. [find_orphans] additionally sweeps for leaks that already happened, which is the only way
they become visible again.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path


PROTECTED_BRANCHES = {"nightly/journal", "nightly/ci-journal"}

# Long enough that a nightly agent which pauses to run a two-hour Gradle build is still protected,
# short enough that this morning's finished cohort is reclaimable by tonight. The populations this
# has to separate were three orders of magnitude apart when measured, so the exact value is not
# delicate.
DEFAULT_MIN_IDLE_HOURS = 12.0


@dataclass(frozen=True)
class Worktree:
    path: Path
    head: str
    branch: str | None


@dataclass(frozen=True)
class RetentionFamily:
    """One named, reviewable answer to "what is this tool allowed to delete?".

    Both axes must match: a worktree is claimed only if it sits directly inside one of [roots] *and*
    its branch starts with one of [branch_prefixes]. Keeping the root explicit is the point -- the
    previous single-axis version could not reach the largest population on the machine no matter how
    the branch was named.
    """

    name: str
    roots: tuple[Path, ...]
    branch_prefixes: tuple[str, ...]
    default_on: bool
    why: str


@dataclass(frozen=True)
class Verdict:
    """Why one worktree is, or is not, removable -- computed once and used by every caller.

    The report and the candidate loop used to decide this separately, and disagreed: the report
    counted anything with an in-scope branch name, while ``--apply`` additionally required the
    worktree to live under the nightly root. The report could therefore print a "safe reclaimable
    total ... enough to close the requirement" that ``--apply`` had no way to deliver. One function,
    two callers, so that cannot come back.
    """

    text: str
    reason: str
    removable: bool
    family: RetentionFamily | None


@dataclass(frozen=True)
class DiskRow:
    path: Path
    size_kib: int
    verdict: str
    reclaimable: bool
    reason: str = "other"
    # Set when the row is removable but its family is not enabled: this is what naming that family
    # on the command line would additionally offer, and the only honest way to quote that number.
    offered_by: str | None = None


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


def retention_families(base: Path, agent_root: Path) -> tuple[RetentionFamily, ...]:
    """The whole policy, in one readable table. [base] is ``<repo>-worktrees``, [agent_root] is
    ``<repo>/.claude/worktrees``."""
    return (
        RetentionFamily(
            name="nightly",
            roots=(base,),
            branch_prefixes=("nightly/", "review/", "integration/nightly"),
            default_on=True,
            why="This loop's own branches. Created nightly, merged or abandoned within days, and "
                "reproducible from the branch alone -- the original disposable population.",
        ),
        RetentionFamily(
            name="docs-blast",
            roots=(base, agent_root),
            branch_prefixes=("docs/language-",),
            default_on=False,
            why="Prose-blast checkouts from the docs language harness, which runs 45 units 8-wide "
                "and reaps none of them. 32 such branches existed on 2026-09-09 with no checkout "
                "left, so the family recurs in bursts and then gets cleaned up by hand.",
        ),
        RetentionFamily(
            name="agent-session",
            roots=(agent_root,),
            branch_prefixes=("claude/",),
            default_on=False,
            why="Interactive agent-session checkouts. The largest population on the machine (33 "
                "trees, 61.4 GiB on 2026-09-09) and the one that grows fastest, but also the one "
                "most likely to be somebody's open window -- opt in deliberately.",
        ),
    )


def family_for(
    path: Path, branch: str | None, families: tuple[RetentionFamily, ...]
) -> RetentionFamily | None:
    """The first family claiming [path] on [branch], or None for "this tool will not touch it".

    [PROTECTED_BRANCHES] is checked here rather than per family so that no family, however it is
    written, can ever reach one of them.
    """
    if not branch or branch in PROTECTED_BRANCHES:
        return None
    parent = path.resolve().parent
    for family in families:
        if parent in family.roots and branch.startswith(family.branch_prefixes):
            return family
    return None


def branch_idle_hours(repo: Path, branch: str, now_epoch: float) -> float | None:
    """Hours since the newest sign of life on [branch], or None if nothing dates it.

    The reflog tip is the better signal, because it moves when a branch is *created*: a worktree
    whose agent has not committed anything yet still reads as alive, which is exactly the case that
    was nearly deleted. But reflogs expire, so an ancient abandoned branch may have none, and the
    commit date backs it up. Whichever is more recent wins -- for a tool that deletes things, any
    sign of life should count.
    """
    stamps: list[int] = []
    reflog = run_git(repo, "reflog", "show", "-1", "--date=unix", "--format=%gd", branch, check=False)
    # "<branch>@{<epoch>}" -- git forbids "@{" inside a ref name, so this partition is unambiguous.
    tail = reflog.stdout.strip().rpartition("@{")[2].rstrip("}")
    if tail.isdigit():
        stamps.append(int(tail))
    committed = run_git(
        repo, "for-each-ref", "--format=%(committerdate:unix)", f"refs/heads/{branch}", check=False
    )
    if committed.stdout.strip().isdigit():
        stamps.append(int(committed.stdout.strip()))
    if not stamps:
        return None
    return max(0.0, (now_epoch - max(stamps)) / 3600.0)


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


def classify_worktree(
    tree: Worktree,
    *,
    repo: Path,
    main_head: str,
    families: tuple[RetentionFamily, ...],
    cwds: dict[Path, set[int]],
    min_idle_hours: float,
    now_epoch: float,
    completed: bool = False,
) -> Verdict:
    """The one place that decides whether a registered worktree may be removed.

    [completed] is the ``--completed PATH`` mode: an exact path named by a human or an orchestrator
    that has just finished with it. That waives the merge requirement (the branch is preserved
    instead) and the idle guard, which would otherwise reject every such call by construction --
    a checkout you have just finished with is by definition not idle. It waives nothing else.

    Checks run cheapest-and-most-decisive first, so an out-of-policy worktree never has ``git
    status`` run inside it.
    """
    path = tree.path.resolve()
    family = family_for(path, tree.branch, families)
    if family is None:
        return Verdict("out of retention policy", "no-family", False, None)
    active = pids_inside(path, cwds)
    if active:
        pids = ",".join(map(str, sorted(active)))
        return Verdict(f"live cwd pid(s) {pids}", "live", False, family)
    if run_git(path, "status", "--porcelain").stdout.strip():
        return Verdict("dirty", "dirty", False, family)
    if not completed:
        assert tree.branch is not None  # family_for rejects a detached head
        idle = branch_idle_hours(repo, tree.branch, now_epoch)
        if idle is None:
            return Verdict("no reflog or commit date to age it by", "undatable", False, family)
        if idle < min_idle_hours:
            return Verdict(
                f"in flight — branch touched {idle:.2f} h ago, under the {min_idle_hours:g} h floor",
                "in-flight",
                False,
                family,
            )
        ancestor = run_git(repo, "merge-base", "--is-ancestor", tree.head, main_head, check=False)
        if ancestor.returncode != 0:
            return Verdict("clean, branch preserved by --completed", "unmerged", False, family)
        return Verdict("clean and merged", "removable", True, family)
    return Verdict("clean, completed checkout; branch preserved", "removable", True, family)


def sweep_finder_metadata(path: Path) -> int:
    """Delete ``.DS_Store`` files under [path], returning how many went.

    Narrow on purpose. ``git worktree remove`` refuses a directory that is not empty after it has
    already unregistered it, and on macOS the file that most often makes it non-empty is Finder
    metadata nobody wants. Anything else that is untracked is potentially somebody's work, so it is
    reported by the caller rather than deleted here -- widening this sweep would turn a reclaimer
    into a shredder.
    """
    removed = 0
    for junk in path.rglob(".DS_Store"):
        try:
            junk.unlink()
            removed += 1
        except OSError:
            pass
    return removed


def worktree_leftovers(path: Path) -> list[Path]:
    """Whatever is still inside [path], shallowly, so a decline can name its cause."""
    try:
        return sorted(path.iterdir())
    except OSError:
        return []


def find_orphans(roots: list[Path], registered: set[Path]) -> list[Path]:
    """Worktree-shaped directories that no longer appear in ``git worktree list``.

    The ``.git`` *file* is the marker: a real worktree has one pointing at the repository, so
    requiring it keeps unrelated directories the human happens to keep alongside their checkouts out
    of the result. These are the residue of the unregister-then-fail sequence described above, and
    nothing else in the tree looks for them.
    """
    orphans: list[Path] = []
    for root in roots:
        if not root.is_dir():
            continue
        for entry in sorted(root.iterdir()):
            if not entry.is_dir() or entry.resolve() in registered:
                continue
            if (entry / ".git").is_file():
                orphans.append(entry)
    return orphans


def disk_rows(
    roots: list[Path], trees: list[Worktree], repo: Path, main_head: str,
    cwds: dict[Path, set[int]], families: tuple[RetentionFamily, ...], enabled: frozenset[str],
    min_idle_hours: float, now_epoch: float,
) -> list[DiskRow]:
    """Rank every worktree-shaped directory under [roots] by size, with the reason for its fate.

    [families] is the *whole* policy, not just the enabled part, so that a row claimed by a family
    the caller did not enable can be reported as an offer rather than silently lumped in with the
    genuinely untouchable. Removability is still decided by [classify_worktree], so the subtotal a
    caller quotes is the subtotal ``--apply`` would deliver.
    """
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
            if tree is None:
                rows.append(DiskRow(path, size, "unregistered; report only", False, "unregistered"))
                continue
            verdict = classify_worktree(
                tree, repo=repo, main_head=main_head, families=families, cwds=cwds,
                min_idle_hours=min_idle_hours, now_epoch=now_epoch,
            )
            assert verdict.family is not None or not verdict.removable
            if verdict.removable and verdict.family is not None and verdict.family.name not in enabled:
                rows.append(DiskRow(
                    path, size, f"{verdict.text}, but family {verdict.family.name} is not enabled",
                    False, "withheld", verdict.family.name,
                ))
                continue
            rows.append(DiskRow(path, size, verdict.text, verdict.removable, verdict.reason))
    return sorted(rows, key=lambda row: (-row.size_kib, str(row.path)))


REFUSAL_LABELS = {
    "no-family": "outside every retention family",
    "live": "a live process has its cwd inside",
    "dirty": "dirty",
    "in-flight": "in flight",
    "undatable": "undatable",
    "unmerged": "clean but unmerged",
    "unregistered": "unregistered",
}


def print_disk_report(
    rows: list[DiskRow], *, free_kib: int, total_kib: int, required_kib: int,
) -> bool:
    """Print the ranked footprint and return whether the requirement is already met.

    The numbers here are meant to be quoted by an orchestrator deciding whether tonight can run, so
    each one says precisely what it is: what reaping would free *as configured*, what naming another
    family would additionally offer, and what is refused and why. The old single "safe reclaimable
    total" conflated the first two and could therefore promise space ``--apply`` could not deliver.
    """
    deficit = max(0, required_kib - free_kib)
    print(
        f"disk: Avail {free_kib / 1048576:.2f} GiB / {total_kib / 1048576:.2f} GiB; "
        f"required {required_kib / 1048576:.2f} GiB; deficit {deficit / 1048576:.2f} GiB"
    )
    subtotal = 0
    print("ranked worktree footprint (subtotal counts only rows --apply would remove as configured):")
    for row in rows:
        if row.reclaimable:
            subtotal += row.size_kib
        marker = f"subtotal {subtotal / 1048576:.2f} GiB" if row.reclaimable else "not counted"
        print(f"  {row.size_kib / 1048576:6.2f} GiB  {row.verdict}; {marker}  {row.path}")

    reclaimable = [row for row in rows if row.reclaimable]
    print(
        f"reclaimable now: {subtotal / 1048576:.2f} GiB across {len(reclaimable)} worktree(s) — "
        + ("closes the requirement" if free_kib + subtotal >= required_kib
           else "does not close the requirement")
    )

    withheld: dict[str, tuple[int, int]] = {}
    for row in rows:
        if row.offered_by is None:
            continue
        count, size = withheld.get(row.offered_by, (0, 0))
        withheld[row.offered_by] = (count + 1, size + row.size_kib)
    if withheld:
        print("withheld by families that are not enabled:")
        for name, (count, size) in sorted(withheld.items(), key=lambda item: -item[1][1]):
            closes = free_kib + subtotal + size >= required_kib
            note = "would close the requirement" if closes else "still short"
            print(f"  --family {name:<14} {count:3d} worktree(s)  {size / 1048576:6.2f} GiB  — {note}")

    refused: dict[str, tuple[int, int]] = {}
    for row in rows:
        if row.reclaimable or row.offered_by is not None:
            continue
        count, size = refused.get(row.reason, (0, 0))
        refused[row.reason] = (count + 1, size + row.size_kib)
    if refused:
        parts = [
            f"{count} {REFUSAL_LABELS.get(reason, reason)} ({size / 1048576:.2f} GiB)"
            for reason, (count, size) in sorted(refused.items(), key=lambda item: -item[1][1])
        ]
        print("refused: " + ", ".join(parts))
    return deficit == 0


def print_policy(families: tuple[RetentionFamily, ...], enabled: frozenset[str]) -> None:
    """The whole policy, printed without scanning anything, so it can be read before it is trusted."""
    print("retention policy — a worktree is a candidate only if some ENABLED family claims it,")
    print("and a family claims it only when BOTH its root and its branch prefix match.")
    for family in families:
        state = "enabled" if family.name in enabled else f"disabled (--family {family.name})"
        print(f"\n  {family.name}  [{state}]")
        print(f"    roots:    {', '.join(str(root) for root in family.roots)}")
        print(f"    branches: {'  '.join(family.branch_prefixes)}")
        print(f"    why:      {family.why}")
    print(f"\n  never, under any family: {', '.join(sorted(PROTECTED_BRANCHES))}")
    print("  also required of every candidate: clean, merged into main, no live process cwd inside,")
    print("  and idle (see --min-idle-hours). --family only ever adds a population.")


def main() -> int:
    # Raw, because the module docstring is the policy documentation and argparse's reflow turns its
    # table into a paragraph.
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
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
    parser.add_argument(
        "--family", action="append", default=[], metavar="NAME",
        help="Additionally allow this retention family (repeatable). Purely additive: it can only "
             "widen the population, never lift a protection. See --explain-policy.",
    )
    parser.add_argument(
        "--min-idle-hours", type=float, default=DEFAULT_MIN_IDLE_HOURS, metavar="HOURS",
        help=f"Refuse a worktree whose branch shows any sign of life inside this window; it is "
             f"probably still in flight (default {DEFAULT_MIN_IDLE_HOURS:g}).",
    )
    parser.add_argument(
        "--explain-policy", action="store_true",
        help="Print the retention policy and exit without scanning or removing anything.",
    )
    args = parser.parse_args()

    repo = Path(run_git(args.repo, "rev-parse", "--show-toplevel").stdout.strip()).resolve()
    base = (args.base or repo.with_name(f"{repo.name}-worktrees")).resolve()
    agent_root = (repo / ".claude/worktrees").resolve()
    families = retention_families(base, agent_root)
    unknown = sorted(set(args.family) - {family.name for family in families})
    if unknown:
        parser.error(
            f"unknown --family {', '.join(unknown)}; known families are "
            + ", ".join(family.name for family in families)
        )
    enabled = frozenset(
        [family.name for family in families if family.default_on] + list(args.family)
    )
    if args.explain_policy:
        print_policy(families, enabled)
        return 0
    if args.min_idle_hours < 0:
        parser.error("--min-idle-hours must be non-negative")

    main_head = run_git(repo, "rev-parse", "main").stdout.strip()
    trees = parse_worktrees(run_git(repo, "worktree", "list", "--porcelain").stdout)
    cwd_owners = live_cwds()
    now_epoch = time.time()
    completed = {path.resolve() for path in args.completed}
    registered = {tree.path.resolve() for tree in trees}
    missing = completed - registered
    if missing:
        parser.error("--completed path is not a registered worktree: " + ", ".join(map(str, sorted(missing))))

    print(
        "policy: "
        + "  ".join(
            f"{family.name}={'on' if family.name in enabled else 'off'}" for family in families
        )
        + f"  min-idle={args.min_idle_hours:g}h"
    )
    candidates: list[tuple[Worktree, int]] = []
    for tree in trees:
        path = tree.path.resolve()
        if completed and path not in completed:
            continue
        # One classifier for the candidate list and the ranked report, so the report cannot promise
        # space that --apply will not free.
        verdict = classify_worktree(
            tree, repo=repo, main_head=main_head, families=families, cwds=cwd_owners,
            min_idle_hours=args.min_idle_hours, now_epoch=now_epoch, completed=path in completed,
        )
        if verdict.family is None or verdict.family.name not in enabled:
            if path in completed:
                # Distinguish "you could enable a family for this" from "nothing will ever reach
                # it": suggesting --family for a protected branch would be an invitation to try.
                if tree.branch in PROTECTED_BRANCHES:
                    parser.error(
                        f"--completed worktree is on a protected branch: {path} ({tree.branch}). "
                        f"No family reaches it and no flag will."
                    )
                offer = family_for(path, tree.branch, families)
                hint = (
                    f"Enable it with --family {offer.name}." if offer
                    else "No retention family covers it; see --explain-policy."
                )
                parser.error(
                    f"--completed worktree is outside every enabled retention family: {path} "
                    f"(branch {tree.branch}). {hint}"
                )
            continue
        if not verdict.removable:
            if path in completed:
                parser.error(f"--completed worktree is not removable: {path} — {verdict.text}")
            # Worth saying out loud: these two mean something is alive, not merely uninteresting.
            if verdict.reason in ("live", "in-flight"):
                print(f"keeping {path}: {verdict.text}")
            continue
        candidates.append((tree, tree_size_kib(path)))

    total_kib = sum(size for _, size in candidates)
    kind = "completed checkout(s), branches preserved" if completed else "clean merged worktree(s)"
    print(f"main {main_head[:10]} — {len(candidates)} {kind}, {total_kib / 1048576:.2f} GiB")
    for tree, size in candidates:
        print(f"  {tree.path}  {size / 1048576:.2f} GiB  {tree.branch}")

    # Leaks that already happened. Reported every run, in both modes, because an orphan is by
    # definition something no future candidate scan can reach.
    orphan_roots = list(dict.fromkeys(
        path.resolve() for path in [base, repo / ".claude/worktrees", *args.report_root]
    ))
    orphans = find_orphans(orphan_roots, registered)
    if orphans:
        orphan_kib = sum(tree_size_kib(path) for path in orphans)
        print(
            f"ORPHANED — {len(orphans)} worktree-shaped director(ies) not in `git worktree list`, "
            f"{orphan_kib / 1048576:.2f} GiB. Left by an unregister-then-fail removal; nothing "
            f"reclaims these automatically."
        )
        for path in orphans:
            print(f"  {path}  {tree_size_kib(path) / 1048576:.2f} GiB  — inspect, then `rm -rf` it")

    budget_ok = True
    if args.disk_floor_gib is not None:
        if args.disk_floor_gib < 0 or args.reserve_gib < 0 or args.planned_gib < 0:
            parser.error("disk floor, reservation and planned cost must be non-negative")
        usage = shutil.disk_usage(repo)
        report_roots = [base, repo / ".claude/worktrees", *args.report_root]
        # Keep the report stable when a caller repeats a built-in root explicitly.
        report_roots = list(dict.fromkeys(path.resolve() for path in report_roots))
        rows = disk_rows(
            report_roots, trees, repo, main_head, cwd_owners, families, enabled,
            args.min_idle_hours, now_epoch,
        )
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
        swept = sweep_finder_metadata(tree.path)
        if swept:
            print(f"  swept {swept} .DS_Store file(s) first, so git is not left half-done")
        remove_args = ["worktree", "remove"]
        # Git requires --force for a clean worktree with an initialized submodule. Dirt was already
        # rejected above, so this does not broaden the selected population.
        if completed:
            remove_args.append("--force")
        removed = run_git(repo, *remove_args, str(tree.path), check=False)
        if removed.returncode != 0:
            reason = removed.stderr.strip() or removed.stdout.strip()
            # A decline is not automatically harmless: git unregisters before it finishes deleting,
            # so the interesting question is whether the directory outlived its registration.
            still_registered = any(
                Path(t.path).resolve() == tree.path.resolve()
                for t in parse_worktrees(run_git(repo, "worktree", "list", "--porcelain").stdout)
            )
            if tree.path.exists() and not still_registered:
                leftovers = worktree_leftovers(tree.path)
                print(f"  *** ORPHANED: {tree.path}")
                print(f"      git declined ({reason}) AFTER unregistering it, so this checkout is")
                print(f"      now on disk and invisible to `git worktree list`. It holds")
                print(f"      {tree_size_kib(tree.path) / 1048576:.2f} GiB and no later run of this tool will see it.")
                if leftovers:
                    shown = ", ".join(item.name for item in leftovers[:8])
                    more = "" if len(leftovers) <= 8 else f", +{len(leftovers) - 8} more"
                    print(f"      what blocked it: {shown}{more}")
                print(f"      reclaim by hand once you have looked: rm -rf {tree.path}")
            else:
                print(f"  git declined: {reason}")
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
