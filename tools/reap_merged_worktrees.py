#!/usr/bin/env python3
"""Remove clean merged worktrees, or strip build output from idle ones, without risking any work.

By default candidates must already be merged to main; a nightly or docs branch is then deleted, an
agent-session or other branch is kept. ``--clean-builds`` is the second mode, described below. An exact
``--completed PATH`` selects a clean registered nightly checkout even when its branch is unmerged;
that mode removes only the checkout and always preserves the branch. It is the safe completion-time
reclaim: the branch carries the work, while a multi-GiB build directory need not remain mounted.

Dry-run is always the default. There is intentionally no dirty tier: uncommitted edits and ignored
work outside build output still deserve a human decision.

WHAT MAY BE REMOVED — the retention policy
------------------------------------------
The long version, with the measurements, is ``dev_docs/worktree_retention_policy.md``.

A worktree is only ever a candidate if it matches a *retention family*: a named rule that pairs a
worktree **root directory** with a set of **branch prefixes**. Both halves must match. Run
``--explain-policy`` to print the table without scanning anything.

  nightly        <repo>-worktrees          nightly/  review/  integration/nightly
  docs-blast     both roots                docs/language-  docs/voice-  docs-pass/  docs/kdoc-
  agent-session  <repo>/.claude/worktrees  claude/
  any-merged     both roots + ~/.codex/worktrees/*/  any branch, or a detached HEAD

All four are on by default since 2026-10-03. Two axes, not one, because the scope gap here was
measured to be the *root*, not the prefix: on 2026-09-09 the 33 worktrees under ``.claude/worktrees``
held 61.4 GiB -- 54% of the whole footprint, and the population that actually grows -- and no branch
name could have made one of them a candidate. Then on 2026-10-02 a hand cull found 16 merged, clean
trees worth 45 GiB of free space on ``codex/``, ``viewers/``, ``arch/``, ``fix/`` and detached HEADs
that no prefix covered, which is what ``any-merged`` is for. ``--only-family`` narrows the run to the
named families; [PROTECTED_BRANCHES] outranks every family.

Nothing is removed merely for matching a family. A candidate must additionally be clean, merged into
main, free of live process cwds, **idle** (see below), free of **ignored work**, and its submodules
must be at commits that exist somewhere other than this checkout.

IGNORED WORK
------------
"Clean" used to mean ``git status --porcelain`` printed nothing, and that is blind to ignored files.
``src/personal/`` is gitignored and holds real hand-written source: on 2026-10-02 one merged, clean
tree carried 1,205 files of it that main lacked, and the reaper would have deleted them. So every
ignored path is now checked against [REGENERABLE] -- build output, caches, logs -- and anything else
refuses the tree. Two known-precious files, [ARCHIVED_FILES], are copied to ``<repo>-archive/``
first instead of refusing, because every nightly tree has one and both are small.

SUBMODULES
----------
``git worktree remove`` refuses any tree with an initialized submodule ("working trees containing
submodules cannot be moved or removed"), and since ``services/field-audio-ai`` arrived that is every
fresh tree. The submodule's git dir lives under ``.git/worktrees/<name>/modules``, so removal deletes
it; [submodule_problem] therefore requires each initialized submodule to sit at its recorded commit
and for that commit to exist in the primary checkout's submodule or on a fetched remote branch. Then
``--apply`` runs ``submodule deinit -f --all`` and removes with ``--force``.

CLEANING BUILDS (``--clean-builds``)
------------------------------------
Most of a worktree's footprint is regenerable build output, and it is safe to delete from a tree
nobody is using whether or not the branch is merged. ``--clean-builds`` runs
``git clean -xdff -- <CLEAN_PATHS>`` (literal paths, never a bare clean) in every registered tree
with no live cwd and no file under its source or build directories modified inside the idle window.
It never touches tracked files, untracked source, or ``src/personal``, and it checks that
``git status --porcelain`` is unchanged afterwards.

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

The sweep before the attempt was not enough. On 2026-09-28, with a Finder window open on the
worktree root, six of seven removals orphaned: Finder writes ``.DS_Store`` into directories *while*
git empties them, and git stops at the first failure, leaving half the tree. So when git abandons a
removal it has already unregistered, [finish_interrupted_removal] finishes it -- re-sweep, remove,
retry while Finder keeps writing -- for that one just-verified candidate only, after re-checking that
it is a worktree (gitfile, not a repository) with no live cwd inside. ORPHAN is now what is left when
even that is refused or fails.
"""

from __future__ import annotations

import argparse
import fnmatch
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path


PROTECTED_BRANCHES = {
    "nightly/journal",
    "nightly/ci-journal",
    # The landing queue's own worktree (~/field4-worktrees/lander, tools/land.py LANDER_BRANCH): clean
    # and merged between landings, so any-merged would otherwise reap it.
    "land/lander",
}

# Ignored paths a tree may hold and still be removed: build output, caches and logs, each of which
# the build or the next run regenerates. Matched with fnmatch against the path git prints for an
# ignored entry (trailing "/" stripped). Note what is *not* here: ``src/personal``, agent demo
# transcripts, anything under ``src/``. Adding a pattern here is a decision that the files it matches
# are worthless; when in doubt, leave it out and let the tree be refused.
REGENERABLE = (
    "build", ".gradle", ".venv", ".kotlin", "kotlin-js-store", ".field-backdoor", ".field",
    ".pytest_cache", ".nightly-tmp", "electron/node_modules",
    "public/field4", "public/.cache", "public/.zensical-generated", "public/docs-runner-failures",
    "public/docs-runner-failures/*", "public/.field-docs-runner-*", "public/zensical.generated.yml",
    "public/zensical.generated.build.yml",
    "native/*/build", "native/*/.build", "tools/*/build", "tools/*/.gradle", "tools/*/.kotlin",
    "*__pycache__", "*.pyc", "*.DS_Store", "config/field4-local.p12",
    "hs_err_pid*.log", "java_pid*.hprof", ".nightly-tmp-*.log",
    # `.afield-local/` is per-checkout state: `field.sh` rewrites `last-workspace.txt` on the next
    # launch, and agents leave build logs there. It is NOT regenerable as a whole -- Marc's
    # intermediate renders are copied there to keep them -- so only these two shapes are listed, and
    # [ignored_work] judges the folder file by file.
    ".afield-local/last-workspace.txt", ".afield-local/*.log",
)

# Ignored files that are precious but expected: copied to ``<repo>-archive/<subdir>/<tree>-<name>``
# before removal rather than refusing it. NIGHTLY_WORKLOG.md is deliberately untracked (it conflicts
# on every nightly merge), so a tree's analysis exists only on that disk.
ARCHIVED_FILES = {
    "NIGHTLY_WORKLOG.md": "worklogs",
    ".claude/settings.local.json": "settings",
    ".claude/launch.json": "settings",
}

# A file of this name in a worktree's root means "a person needs this checkout; never remove it and
# never clean its build output". Merged, clean and idle is not the same as unused: on 2026-10-12 the
# reaper removed `split-panes-p0`, which Marc had asked to keep, the day its `http.server` stopped.
# The marker is gitignored, so it does not make the tree dirty.
KEEP_MARKER = ".reaper-keep"

# Untracked (not ignored) paths that do not make a tree dirty. Every nightly tree carries a
# ``.nightly-tmp/`` scratch directory, which used to make the whole nightly population read as dirty.
SCRATCH_UNTRACKED = (".nightly-tmp/",)

# What ``--clean-builds`` deletes, as literal pathspecs. Narrower than [REGENERABLE] on purpose:
# these trees may be unmerged and mid-task, so only output the build itself writes is touched.
CLEAN_PATHS = (
    "build", ".gradle", ".venv", ".kotlin", "kotlin-js-store", ".field-backdoor", ".pytest_cache",
    "public/field4", "public/.cache", "public/.zensical-generated", "electron/node_modules",
    "tools/code-use-semantic-sidecar/build", "tools/code-use-semantic-sidecar/.gradle",
    "tools/code-use-semantic-sidecar/.kotlin",
)
CLEAN_GLOBS = ("native/*/build", "native/*/.build", "java_pid*.hprof")

# Where a working session writes; any file here newer than the idle window means "in use".
ACTIVITY_DIRS = ("src", "tools", "dev_docs", "public/docs", "build", ".gradle")

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
    # Roots whose worktrees sit one level further down, ``<root>/<id>/<checkout>`` -- the Codex app's
    # layout.
    nested_roots: tuple[Path, ...] = ()
    # Claim every branch name, and a detached HEAD, rather than matching [branch_prefixes].
    any_branch: bool = False
    # Delete the merged branch after removing its checkout. Off for agent sessions: the desktop app
    # checks a resumed session's branch out again, so the ref is what lets the session come back.
    delete_branch: bool = True


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


# Refusals said out loud as "keeping <path>: <why>". Only live and in-flight used to be, so a clean,
# merged worktree refused for ignored work, dirt, a submodule or a missing date dropped out of the
# dry run with no reason given (2026-10-22: two agent checkouts holding plan notes and captures in
# .afield-local/). "unmerged" stays quiet: it is the normal state of somebody's ongoing branch, and
# the disk report already counts those. "prunable" has its own line.
ANNOUNCED_REFUSALS = frozenset({"live", "in-flight", "dirty", "ignored-work", "submodule", "undatable", "kept"})


def refusal_line(path: Path, verdict: Verdict) -> str | None:
    """The "keeping ..." line for a refused worktree, or None for one the run should pass over quietly."""
    if verdict.removable or verdict.reason not in ANNOUNCED_REFUSALS:
        return None
    return f"keeping {path}: {verdict.text}"


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


def retention_families(
    base: Path, agent_root: Path, codex_root: Path | None = None
) -> tuple[RetentionFamily, ...]:
    """The whole policy, in one readable table. [base] is ``<repo>-worktrees``, [agent_root] is
    ``<repo>/.claude/worktrees``, [codex_root] is ``~/.codex/worktrees``.

    Order matters: [family_for] returns the first match, so the catch-all comes last and the named
    families keep their own branch-deletion rules.
    """
    nested = (codex_root,) if codex_root is not None else ()
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
            branch_prefixes=("docs/language-", "docs/voice-", "docs-pass/", "docs/kdoc-"),
            default_on=True,
            why="Checkouts from the docs harnesses -- the language blast (45 units 8-wide), the "
                "voice pass, the docs pass and the kdoc routine -- none of which reap their own. 20 "
                "merged docs/voice-* trees sat unreached on 2026-09-29 and were culled by hand.",
        ),
        RetentionFamily(
            name="agent-session",
            roots=(agent_root,),
            branch_prefixes=("claude/",),
            default_on=True,
            why="Interactive agent-session checkouts: the largest population and the fastest "
                "growing. Often still open in the app, so the live-cwd and idle guards are what "
                "protect a session in use. The branch is kept, so the session can be resumed.",
            delete_branch=False,
        ),
        RetentionFamily(
            name="any-merged",
            roots=(base, agent_root),
            branch_prefixes=(),
            default_on=True,
            why="Everything else under the worktree roots, including detached HEADs and Codex "
                "checkouts: codex/, viewers/, arch/, fix/ and one-off branches. On 2026-10-02 these "
                "held 16 merged, clean trees that no prefix reached. The branch is kept.",
            nested_roots=nested,
            any_branch=True,
            delete_branch=False,
        ),
    )


def family_for(
    path: Path, branch: str | None, families: tuple[RetentionFamily, ...]
) -> RetentionFamily | None:
    """The first family claiming [path] on [branch], or None for "this tool will not touch it".

    [PROTECTED_BRANCHES] is checked here rather than per family so that no family, however it is
    written, can ever reach one of them.
    """
    if branch in PROTECTED_BRANCHES:
        return None
    parent = path.resolve().parent
    for family in families:
        in_root = parent in family.roots or parent.parent in family.nested_roots
        if not in_root:
            continue
        if family.any_branch:
            return family
        if branch and branch.startswith(family.branch_prefixes):
            return family
    return None


def head_idle_hours(path: Path, now_epoch: float) -> float | None:
    """[branch_idle_hours] for a detached HEAD: the worktree's own HEAD reflog, or its commit date."""
    stamps: list[int] = []
    reflog = run_git(path, "reflog", "show", "-1", "--date=unix", "--format=%gd", "HEAD", check=False)
    tail = reflog.stdout.strip().rpartition("@{")[2].rstrip("}")
    if tail.isdigit():
        stamps.append(int(tail))
    committed = run_git(path, "log", "-1", "--format=%ct", "HEAD", check=False)
    if committed.stdout.strip().isdigit():
        stamps.append(int(committed.stdout.strip()))
    if not stamps:
        return None
    return max(0.0, (now_epoch - max(stamps)) / 3600.0)


def meaningful_status(path: Path) -> list[str]:
    """``git status --porcelain`` lines, less untracked [SCRATCH_UNTRACKED] directories."""
    lines = run_git(path, "status", "--porcelain").stdout.splitlines()
    return [
        line for line in lines
        if not (line.startswith("?? ") and line[3:] in SCRATCH_UNTRACKED)
    ]


def ignored_paths(path: Path) -> list[str]:
    """Every ignored entry git reports in [path], as git prints it (directories end in "/")."""
    out = run_git(path, "status", "--porcelain", "--ignored=matching", check=False).stdout
    return [line[3:] for line in out.splitlines() if line.startswith("!! ")]


def is_regenerable(entry: str) -> bool:
    """Whether [entry], or a directory containing it, matches [REGENERABLE].

    The containing directories matter because a cache that carries its own ``.gitignore`` (as
    ``public/.cache`` and ``.pytest_cache`` do) is reported file by file rather than as one entry.
    """
    parts = entry.rstrip("/").split("/")
    prefixes = ["/".join(parts[: i + 1]) for i in range(len(parts))]
    return any(
        fnmatch.fnmatchcase(prefix, pattern) for prefix in prefixes for pattern in REGENERABLE
    )


def ignored_work(path: Path) -> list[str]:
    """Ignored entries that are neither regenerable nor archived -- somebody's work, maybe.

    This is the guard that keeps ``src/personal`` alive: it is gitignored, so ``git status`` says
    nothing about it, and on 2026-10-02 a merged, clean tree held 1,205 files of it that main lacked.
    """
    work: list[str] = []
    for entry in ignored_paths(path):
        if is_regenerable(entry) or entry in ARCHIVED_FILES:
            continue
        # git reports a wholly ignored directory as one entry ("`.afield-local/`"). Judge what is
        # inside it file by file, so a folder holding only regenerable or archived files does not
        # refuse the tree, while one file of real work inside it still does.
        if entry.endswith("/") and (path / entry).is_dir():
            for inner in sorted((path / entry).rglob("*")):
                if inner.is_file():
                    relative = inner.relative_to(path).as_posix()
                    if not is_regenerable(relative) and relative not in ARCHIVED_FILES:
                        work.append(relative)
            continue
        work.append(entry)
    return work


def submodule_problem(path: Path, repo: Path) -> str | None:
    """Why removing [path] could lose a submodule commit, or None when it cannot.

    ``git submodule status`` prefixes ``-`` for uninitialized, ``+`` for checked out off the
    recorded commit and ``U`` for conflicts. An initialized submodule's git dir lives under the
    worktree's admin dir and is deleted with it, so its commit must also exist in the primary
    checkout's submodule or on a fetched remote branch.
    """
    if not (path / ".gitmodules").exists():
        return None
    status = run_git(path, "submodule", "status", "--recursive", check=False)
    if status.returncode != 0:
        return f"submodule status failed: {status.stderr.strip()}"
    for line in status.stdout.splitlines():
        if not line:
            continue
        flag, rest = line[0], line[1:].split()
        if flag == "-" or len(rest) < 2:
            continue
        sha, subpath = rest[0], rest[1]
        if flag in "+U":
            return f"submodule {subpath} is off its recorded commit"
        primary = repo / subpath
        if (primary / ".git").exists() and run_git(
            primary, "cat-file", "-e", f"{sha}^{{commit}}", check=False
        ).returncode == 0:
            continue
        remote = run_git(path / subpath, "branch", "-r", "--contains", sha, check=False)
        if remote.returncode == 0 and remote.stdout.strip():
            continue
        return f"submodule {subpath} commit {sha[:10]} exists only in this checkout"
    return None


def archive_precious(path: Path, archive_root: Path) -> list[Path]:
    """Copy any [ARCHIVED_FILES] out of [path] before it is removed; return the copies."""
    copies: list[Path] = []
    for relative, subdir in ARCHIVED_FILES.items():
        source = path / relative
        if not source.is_file():
            continue
        target_dir = archive_root / subdir
        target_dir.mkdir(parents=True, exist_ok=True)
        target = target_dir / f"{path.name}-{Path(relative).name}"
        counter = 1
        while target.exists():
            counter += 1
            target = target_dir / f"{path.name}-{counter}-{Path(relative).name}"
        shutil.copy2(source, target)
        copies.append(target)
    return copies


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
    # Before anything runs git inside it: a registered worktree whose directory was deleted by hand
    # made `git status` exit 128, and the uncaught error stopped the whole run (2026-10-08, four
    # such entries). There is nothing on disk to reclaim; `git worktree prune` drops the entry.
    if not path.exists():
        return Verdict("registered, but its directory is gone (git worktree prune drops it)",
                       "prunable", False, family)
    if (path / KEEP_MARKER).exists():
        return Verdict(f"kept by its {KEEP_MARKER} file", "kept", False, family)
    if family is None:
        return Verdict("out of retention policy", "no-family", False, None)
    active = pids_inside(path, cwds)
    if active:
        pids = ",".join(map(str, sorted(active)))
        return Verdict(f"live cwd pid(s) {pids}", "live", False, family)
    if meaningful_status(path):
        return Verdict("dirty", "dirty", False, family)
    work = ignored_work(path)
    if work:
        shown = ", ".join(work[:4]) + (f", +{len(work) - 4} more" if len(work) > 4 else "")
        return Verdict(f"holds ignored work ({shown})", "ignored-work", False, family)
    problem = submodule_problem(path, repo)
    if problem:
        return Verdict(problem, "submodule", False, family)
    if not completed:
        if tree.branch is None:
            idle = head_idle_hours(path, now_epoch)
        else:
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


FINISH_ATTEMPTS = 6
FINISH_PAUSE_SECONDS = 0.5


def finish_interrupted_removal(
    path: Path,
    *,
    roots: list[Path],
    repo: Path,
    cwds: dict[Path, set[int]],
    attempts: int = FINISH_ATTEMPTS,
    pause: float = FINISH_PAUSE_SECONDS,
    remove=shutil.rmtree,
    sleep=time.sleep,
) -> tuple[bool, str]:
    """Finish the deletion ``git worktree remove`` began on [path] and then abandoned.

    Measured 2026-09-28: with a Finder window open on the worktree root, six of seven removals
    failed. Finder writes ``.DS_Store`` into directories as git empties them, git stops at the first
    ``Directory not empty`` -- after it has already unregistered the checkout -- and leaves half the
    tree behind (2.11 of 4.39 GiB in one case). A sweep *before* the attempt cannot win a race that
    happens *during* it, so this finishes the job git started: re-sweep, remove, and try again while
    Finder keeps writing, up to [attempts] times.

    This is not a force mode. It is only ever called for the one candidate this run has just
    verified clean, merged, idle and free of live cwds, whose removal git itself began and whose
    registration git itself dropped. Everything under it is what git was already deleting: tracked
    files, ignored build output and Finder metadata. And it re-checks the preconditions that could
    have changed: the path must sit directly under one of [roots] and not be [repo], its ``.git``
    must be a worktree's gitfile or already gone -- never a directory, which would be a repository
    of its own -- and no live process may have its cwd inside it. [find_orphans]' leaks from earlier
    runs are never passed here; they stay report-only, for a human to look at.

    @return whether [path] is gone, and a reason when it is not, or when it was not attempted
    """
    resolved = path.resolve()
    if resolved == repo.resolve() or resolved.parent not in {root.resolve() for root in roots}:
        return False, "not directly under a worktree root, so not finished automatically"
    git_marker = path / ".git"
    if git_marker.is_dir():
        return False, "its .git is a directory, a repository of its own, so not finished automatically"
    busy = pids_inside(path, cwds)
    if busy:
        return False, f"live cwd pid(s) {','.join(str(p) for p in sorted(busy))} inside it now"
    for attempt in range(1, attempts + 1):
        sweep_finder_metadata(path)
        remove(path, ignore_errors=True)
        if not path.exists():
            return True, f"finished in {attempt} attempt(s)"
        sleep(pause)
    left = ", ".join(item.name for item in worktree_leftovers(path)[:8])
    return False, f"still not empty after {attempts} attempts ({left})"


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
    "ignored-work": "holds ignored work",
    "submodule": "a submodule commit could be lost",
    "prunable": "registered, but its directory is gone",
    "kept": f"kept by a {KEEP_MARKER} file",
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


def recently_active(path: Path, hours: float, now_epoch: float) -> str | None:
    """The first file under [ACTIVITY_DIRS] modified within [hours], or None if the tree is quiet.

    Branch age is no use here: an agent session that never commits reads as ancient by reflog while
    its build is running. File times are what a working session actually moves. ``.DS_Store`` is not
    one of them: Finder rewrites it when someone merely looks at a folder, which held nine idle trees
    back from a clean on 2026-10-31.
    """
    dirs = [str(path / d) for d in ACTIVITY_DIRS if (path / d).is_dir()]
    if not dirs:
        return None
    # A local "YYYY-MM-DD HH:MM:SS", because /usr/bin/find on macOS rejects "@<epoch>" (bfs and GNU
    # find accept it, which is how a shell test can pass while this call fails).
    cutoff = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now_epoch - hours * 3600))
    found = subprocess.run(
        ["find", *dirs, "-type", "f", "!", "-name", ".DS_Store", "-newermt", cutoff, "-print", "-quit"],
        capture_output=True, text=True, check=False,
    )
    hit = found.stdout.strip()
    if hit:
        return hit
    # Fail closed: a find that could not answer must not read as "quiet", or every tree is cleaned.
    # Unreadable subdirectories also exit non-zero, so only a parse or usage error counts here.
    if found.returncode != 0 and "No such file" not in found.stderr and "Permission denied" not in found.stderr:
        return f"(find failed: {found.stderr.strip()[:120]})"
    return None


def clean_targets(path: Path) -> list[str]:
    """The [CLEAN_PATHS] and [CLEAN_GLOBS] that exist in [path], relative, as literal pathspecs."""
    present = [p for p in CLEAN_PATHS if (path / p).exists()]
    for pattern in CLEAN_GLOBS:
        present.extend(str(hit.relative_to(path)) for hit in sorted(path.glob(pattern)))
    return present


@dataclass(frozen=True)
class CleanResult:
    kib: int
    passes: int
    status_changed: bool
    leftovers: tuple[str, ...]


def clean_builds(path: Path, *, apply: bool) -> CleanResult:
    """Delete regenerable build output from [path], or measure it when [apply] is false.

    ``git clean -xdff -- <literal paths>`` never touches a tracked file, and naming the paths keeps
    it away from untracked source and ``src/personal``. ``-xdff`` routinely needs a second pass
    (``failed to remove build/js: Directory not empty``) and occasionally a third, so passes repeat
    until git prints nothing. The proof that nothing else moved is ``git status --porcelain`` before
    and after, which is the number a human would check.
    """
    targets = clean_targets(path)
    if not targets:
        return CleanResult(0, 0, False, ())
    kib = sum(tree_size_kib(path / t) for t in targets)
    if not apply:
        return CleanResult(kib, 0, False, ())
    before = run_git(path, "status", "--porcelain").stdout
    passes = 0
    for passes in range(1, 4):
        cleaned = run_git(path, "clean", "-xdff", "-q", "--", *targets, check=False)
        targets = clean_targets(path)
        if not (cleaned.stdout.strip() or cleaned.stderr.strip()) or not targets:
            break
    after = run_git(path, "status", "--porcelain").stdout
    return CleanResult(kib, passes, before != after, tuple(targets))


def print_policy(families: tuple[RetentionFamily, ...], enabled: frozenset[str]) -> None:
    """The whole policy, printed without scanning anything, so it can be read before it is trusted."""
    print("retention policy — a worktree is a candidate only if some ENABLED family claims it,")
    print("and a family claims it only when BOTH its root and its branch prefix match.")
    for family in families:
        state = "enabled" if family.name in enabled else f"disabled (--family {family.name})"
        print(f"\n  {family.name}  [{state}]")
        roots = [str(root) for root in family.roots] + [f"{root}/*" for root in family.nested_roots]
        print(f"    roots:    {', '.join(roots)}")
        branches = "any branch, or a detached HEAD" if family.any_branch else "  ".join(family.branch_prefixes)
        print(f"    branches: {branches}")
        print(f"    branch:   {'deleted once merged' if family.delete_branch else 'kept'}")
        print(f"    why:      {family.why}")
    print(f"\n  never, under any family: {', '.join(sorted(PROTECTED_BRANCHES))}")
    print("  also required of every candidate: clean, merged into main, no live process cwd inside,")
    print("  idle (see --min-idle-hours), no ignored work outside build/cache output, and no")
    print("  submodule commit that exists only in the checkout. --family adds a population and")
    print("  --only-family narrows to the named ones.")
    print(f"  a {KEEP_MARKER} file in a worktree's root keeps it from removal and from --clean-builds.")


def run_clean_builds(
    trees: list[Worktree], *, repo: Path, families: tuple[RetentionFamily, ...],
    enabled: frozenset[str], cwds: dict[Path, set[int]], min_idle_hours: float,
    now_epoch: float, apply: bool,
) -> int:
    """``--clean-builds``: strip build output from every quiet registered worktree.

    Unlike removal this needs no merge, because it deletes nothing a branch or a human wrote. The
    guards are the ones that say "somebody is using this right now": a live cwd, or any file under
    [ACTIVITY_DIRS] newer than the idle window.
    """
    total = 0
    changed: list[Path] = []
    for tree in trees:
        path = tree.path.resolve()
        if path == repo.resolve() or not path.exists():
            continue
        family = family_for(path, tree.branch, families)
        if family is None or family.name not in enabled:
            continue
        if (path / KEEP_MARKER).exists():
            print(f"keeping {path}: {KEEP_MARKER}")
            continue
        busy = pids_inside(path, cwds)
        if busy:
            print(f"keeping {path}: live cwd pid(s) {','.join(map(str, sorted(busy)))}")
            continue
        # Both signals, because each misses a case: a landing tree's branch moves with few file
        # writes, and a session that never commits writes files on a branch that reads as ancient.
        idle = (
            head_idle_hours(path, now_epoch) if tree.branch is None
            else branch_idle_hours(repo, tree.branch, now_epoch)
        )
        if idle is None or idle < min_idle_hours:
            shown = "undatable" if idle is None else f"branch touched {idle:.2f} h ago"
            print(f"keeping {path}: {shown}")
            continue
        recent = recently_active(path, min_idle_hours, now_epoch)
        if recent:
            print(f"keeping {path}: modified within {min_idle_hours:g} h ({recent})")
            continue
        result = clean_builds(path, apply=apply)
        if result.kib < 10 * 1024:
            continue
        total += result.kib
        note = ""
        if apply:
            note = f"  {result.passes} pass(es)"
            if result.leftovers:
                note += f"; still present: {', '.join(result.leftovers)}"
            if result.status_changed:
                changed.append(path)
                note += "  *** git status CHANGED ***"
        print(f"  {result.kib / 1048576:6.2f} GiB  {path}{note}")
    verb = "cleaned" if apply else "cleanable"
    print(f"{verb}: {total / 1048576:.2f} GiB of build output (du; shared APFS blocks make the real gain smaller)")
    if changed:
        print(f"*** git status changed in {len(changed)} tree(s) -- inspect them: "
              + ", ".join(map(str, changed)))
        return 1
    if not apply:
        print("DRY RUN — pass --apply to delete exactly this build output.")
    return 0


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
        help="Expected whole-run volume growth (worktrees, builds, downloads and concurrent writers), added to the reservation check; not just checkout size.",
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
        "--only-family", action="append", default=[], metavar="NAME",
        help="Consider only these retention families (repeatable), instead of every default one.",
    )
    parser.add_argument(
        "--clean-builds", action="store_true",
        help="Instead of removing merged worktrees, delete regenerable build output (CLEAN_PATHS) "
             "from every quiet worktree, merged or not. Dry run unless --apply.",
    )
    parser.add_argument(
        "--archive-root", type=Path, metavar="PATH",
        help="Where ARCHIVED_FILES are copied before removal (default: <repo>-archive).",
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
    codex_root = (Path.home() / ".codex/worktrees").resolve()
    families = retention_families(base, agent_root, codex_root)
    unknown = sorted(set(args.family + args.only_family) - {family.name for family in families})
    if unknown:
        parser.error(
            f"unknown --family {', '.join(unknown)}; known families are "
            + ", ".join(family.name for family in families)
        )
    if args.only_family:
        enabled = frozenset(args.only_family + list(args.family))
    else:
        enabled = frozenset(
            [family.name for family in families if family.default_on] + list(args.family)
        )
    archive_root = (args.archive_root or repo.with_name(f"{repo.name}-archive")).resolve()
    if args.clean_builds and args.completed:
        parser.error("--clean-builds and --completed are different modes; pick one")
    if args.explain_policy:
        print_policy(families, enabled)
        return 0
    if args.min_idle_hours < 0:
        parser.error("--min-idle-hours must be non-negative")

    main_head = run_git(repo, "rev-parse", "main").stdout.strip()
    trees = parse_worktrees(run_git(repo, "worktree", "list", "--porcelain").stdout)
    gone = [tree.path for tree in trees if not tree.path.exists()]
    if gone:
        # Lossless: prune only drops the admin entry of a worktree whose directory no longer exists;
        # its branch and commits stay. A dry run reports and leaves them for --apply.
        if args.apply:
            run_git(repo, "worktree", "prune")
            trees = parse_worktrees(run_git(repo, "worktree", "list", "--porcelain").stdout)
            print(f"pruned {len(gone)} registered worktree(s) whose directory was gone: "
                  + ", ".join(path.name for path in gone))
        else:
            print(f"{len(gone)} registered worktree(s) whose directory is gone; --apply prunes them: "
                  + ", ".join(path.name for path in gone))
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
    if args.clean_builds:
        return run_clean_builds(
            trees, repo=repo, families=families, enabled=enabled, cwds=cwd_owners,
            min_idle_hours=args.min_idle_hours, now_epoch=now_epoch, apply=args.apply,
        )
    candidates: list[tuple[Worktree, int, RetentionFamily]] = []
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
            line = refusal_line(path, verdict)
            if line:
                print(line)
            continue
        candidates.append((tree, tree_size_kib(path), verdict.family))

    total_kib = sum(size for _, size, _ in candidates)
    kind = "completed checkout(s), branches preserved" if completed else "clean merged worktree(s)"
    print(f"main {main_head[:10]} — {len(candidates)} {kind}, {total_kib / 1048576:.2f} GiB")
    for tree, size, family in candidates:
        print(f"  {tree.path}  {size / 1048576:.2f} GiB  {tree.branch or '(detached ' + tree.head[:10] + ')'}  [{family.name}]")

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

    for tree, _, family in candidates:
        print(f"removing {tree.path}")
        for copy in archive_precious(tree.path, archive_root):
            print(f"  archived {copy}")
        swept = sweep_finder_metadata(tree.path)
        if swept:
            print(f"  swept {swept} .DS_Store file(s) first, so git is not left half-done")
        if (tree.path / ".gitmodules").exists():
            # submodule_problem already proved each initialized submodule's commit exists elsewhere.
            deinit = run_git(tree.path, "submodule", "deinit", "-f", "--all", check=False)
            if deinit.returncode != 0:
                print(f"  submodule deinit failed, skipping: {deinit.stderr.strip()}")
                continue
        # --force because git refuses a tree with an initialized submodule, and an untracked
        # SCRATCH_UNTRACKED directory, without it. Dirt and ignored work were rejected above, so
        # this does not widen what is removed.
        remove_args = ["worktree", "remove", "--force"]
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
                size_gib = tree_size_kib(tree.path) / 1048576
                finished, how = finish_interrupted_removal(
                    tree.path, roots=orphan_roots, repo=repo, cwds=live_cwds(),
                )
                if finished:
                    print(f"  git stopped part-way ({reason}) after unregistering it, with {size_gib:.2f} GiB")
                    print(f"      still on disk; the deletion git began was {how}")
                else:
                    leftovers = worktree_leftovers(tree.path)
                    print(f"  *** ORPHANED: {tree.path}")
                    print(f"      git declined ({reason}) AFTER unregistering it, so this checkout is")
                    print(f"      now on disk and invisible to `git worktree list`. It holds")
                    print(f"      {tree_size_kib(tree.path) / 1048576:.2f} GiB and no later run of this tool will see it.")
                    print(f"      finishing the removal was refused or failed: {how}")
                    if leftovers:
                        shown = ", ".join(item.name for item in leftovers[:8])
                        more = "" if len(leftovers) <= 8 else f", +{len(leftovers) - 8} more"
                        print(f"      what blocked it: {shown}{more}")
                    print(f"      reclaim by hand once you have looked: rm -rf {tree.path}")
                    continue
            else:
                print(f"  git declined: {reason}")
                continue
        if not completed and tree.branch is not None and family.delete_branch:
            deleted = run_git(repo, "branch", "-d", tree.branch, check=False)
            if deleted.returncode != 0:
                print(f"  branch kept: {deleted.stderr.strip() or deleted.stdout.strip()}")
    run_git(repo, "worktree", "prune")
    return 0 if budget_ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
