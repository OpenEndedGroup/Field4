#!/usr/bin/env python3
"""Make a worktree's tracked files copy-on-write clones of the primary checkout's.

`git worktree add` writes every tracked file out afresh, so each worktree of this repository held
its own 4.8 GiB of `public/docs/` (3 GiB of it `public/docs/assets/web_publications`), plus `libs/`,
`src/` and `dev_docs/`. On 2026-11-20 a nightly worktree measured 17 GB with the disk at 6.9 GiB
free; the docs builder's two copies became clones that day (`tools/cow_clone.py`), and git's checkout
was the third. Here a tracked file whose content the primary checkout already holds is cloned from
it instead: on APFS that costs metadata, not bytes, until one side writes, and writing either side
never changes the other.

A file is cloned only when all of these hold:

- both indexes hold the path at stage 0, as a regular file (mode 100644 or 100755), with the same
  blob and mode, and neither marks it skip-worktree or assume-unchanged;
- the primary's working file is clean (`git diff` does not name it) and a regular file whose size
  is the blob's, or the path is LFS-filtered (the primary holds the real jar, the blob its pointer);
- in `seed` mode, the target's working file is clean too, and unchanged since that was checked.

The clone is written beside the target and renamed over it. Then `git update-index --refresh`
re-hashes every clone, so git itself, not this script, decides each is the blob it claims to be; a
file that differs (the primary was edited mid-seed) is checked out again by git. `git status` is
clean afterwards, exactly as after a plain `git worktree add`.

`add` creates the worktree with `--no-checkout`, clones what it can, and lets `git reset --hard`
write the rest, so a seeded worktree never holds a second full copy, even for a moment. Where the
filesystem cannot clone (not APFS/btrfs/XFS, or another volume) it is an ordinary checkout. An
`--orphan` worktree shares nothing with the primary and is passed straight to git.

Sharing blocks with a checkout other sessions edit is safe: an editor's write to the primary
allocates new blocks (or a new inode, for write-and-rename), so a clone's content cannot change
under it. The cost is only accounting: shared blocks are freed when the last file holding them goes.

Usage:
    python3 tools/worktree_seed.py add [-C REPO] [--source DIR] [-b NEW | -B NEW | --detach] PATH [COMMIT-ISH]
    python3 tools/worktree_seed.py seed WORKTREE [--source DIR]   # re-share an existing worktree
    python3 tools/worktree_seed.py measure PATH...                 # files, logical and private bytes
    python3 tools/worktree_seed.py claude-worktree-create < HOOK_JSON  # Claude Code's WorktreeCreate hook

`claude-worktree-create` is what `.claude/settings.json` runs in place of Claude Code's own
`git worktree add`, for `claude --worktree`, `EnterWorktree` and agents with `isolation: "worktree"`.
It reads the hook's JSON (`name`, `cwd`) on stdin, creates `<primary>/.claude/worktrees/<name>` on
branch `claude/<name>` from `main`, and prints that path as its only line of stdout, which is the
hook's whole contract. With no `WorktreeRemove` hook Claude removes the worktree itself, as
`git worktree remove --force` would, and leaves the branch for the reaper.

Stdlib only and Python 3.9 compatible, for the routines' shell scripts.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import shutil
import stat
import subprocess
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cow_clone import private_bytes, tree_bytes, try_clone  # noqa: E402
from git_isolation import scrubbed_env  # noqa: E402

REGULAR_MODES = ("100644", "100755")
SUFFIX = ".seed-clone"


@dataclasses.dataclass
class SeedResult:
    worktree: Path
    source: Optional[Path]
    tracked: int = 0
    cloned: int = 0
    cloned_bytes: int = 0
    rewritten: int = 0  # clones git found stale and checked out again
    note: str = ""

    def summary(self) -> str:
        if self.note:
            return f"worktree_seed: {self.worktree}: {self.note}"
        return (f"worktree_seed: {self.worktree}: {self.cloned} of {self.tracked} tracked files are clones of "
                f"{self.source} ({human(self.cloned_bytes)} shared)"
                + (f"; {self.rewritten} changed mid-seed and were checked out by git" if self.rewritten else ""))


def _git(cwd: Path, *args: str, env: Optional[Dict[str, str]] = None, check: bool = True,
         stdin: Optional[bytes] = None) -> bytes:
    environment = scrubbed_env(env)
    environment["GIT_OPTIONAL_LOCKS"] = "0"  # never write another checkout's index behind its back
    result = subprocess.run(["git", "-C", str(cwd), *args], input=stdin, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, env=environment, check=False)
    if check and result.returncode != 0:
        raise subprocess.CalledProcessError(result.returncode, ["git", "-C", str(cwd), *args],
                                            result.stdout, result.stderr)
    return result.stdout


def _git_locking(cwd: Path, *args: str, env: Optional[Dict[str, str]] = None, check: bool = True) -> None:
    """A git command that must write its own index, in its own worktree."""
    subprocess.run(["git", "-C", str(cwd), *args], env=scrubbed_env(env), check=check,
                   stdout=sys.stderr, stderr=sys.stderr)


def primary_checkout(repo: Path, env: Optional[Dict[str, str]] = None) -> Optional[Path]:
    """The main working tree of [repo]'s repository, or None for a bare one."""
    common = Path(_git(repo, "rev-parse", "--path-format=absolute", "--git-common-dir", env=env).decode().strip())
    return common.parent if common.name == ".git" and (common.parent / ".git").is_dir() else None


def _entries(checkout: Path, env: Optional[Dict[str, str]]) -> Dict[str, Tuple[str, str]]:
    """path -> (mode, blob) for each plainly tracked regular file of [checkout]'s index."""
    entries: Dict[str, Tuple[str, str]] = {}
    for record in _git(checkout, "ls-files", "-s", "-t", "-z", env=env).split(b"\0"):
        if not record:
            continue
        head, _, path = record.partition(b"\t")
        tag, mode, blob, stage = head.decode().split(" ")
        if tag == "H" and stage == "0" and mode in REGULAR_MODES:
            entries[os.fsdecode(path)] = (mode, blob)
    return entries


def _changed(checkout: Path, env: Optional[Dict[str, str]]) -> Set[str]:
    """Tracked paths whose working file differs from the index, by content (`git diff`)."""
    out = _git(checkout, "diff", "--name-only", "--no-renames", "--no-ext-diff", "-z", env=env)
    return {os.fsdecode(p) for p in out.split(b"\0") if p}


def _blob_sizes(checkout: Path, blobs: Iterable[str], env: Optional[Dict[str, str]]) -> Dict[str, int]:
    unique = sorted(set(blobs))
    out = _git(checkout, "cat-file", "--batch-check=%(objectname) %(objectsize)", env=env,
               stdin="".join(b + "\n" for b in unique).encode())
    sizes = {}
    for line in out.decode().splitlines():
        parts = line.split(" ")
        if len(parts) == 2 and parts[1].isdigit():
            sizes[parts[0]] = int(parts[1])
    return sizes


def _lfs_paths(checkout: Path, paths: Sequence[str], env: Optional[Dict[str, str]]) -> Set[str]:
    if not paths:
        return set()
    out = _git(checkout, "check-attr", "-z", "--stdin", "filter", env=env,
               stdin=b"".join(os.fsencode(p) + b"\0" for p in paths)).split(b"\0")
    return {os.fsdecode(out[i]) for i in range(0, len(out) - 2, 3) if out[i + 2] == b"lfs"}


def _same_file(a: os.stat_result, b: os.stat_result) -> bool:
    return (a.st_ino, a.st_dev, a.st_size, a.st_mtime_ns) == (b.st_ino, b.st_dev, b.st_size, b.st_mtime_ns)


def candidates(target: Path, source: Path, *, target_checked_out: bool,
               env: Optional[Dict[str, str]] = None) -> Tuple[int, List[str]]:
    """(tracked files in [target], the paths that may be cloned from [source])."""
    mine = _entries(target, env)
    theirs = _entries(source, env)
    shared = [p for p, entry in mine.items() if theirs.get(p) == entry]
    excluded = _changed(source, env)
    if target_checked_out:
        excluded |= _changed(target, env)
    shared = [p for p in shared if p not in excluded]
    sizes = _blob_sizes(source, (mine[p][1] for p in shared), env)
    ready, odd_size = [], []
    for path in shared:
        try:
            st = os.lstat(source / path)
        except OSError:
            continue
        if not stat.S_ISREG(st.st_mode):
            continue
        (ready if st.st_size == sizes.get(mine[path][1], -1) else odd_size).append(path)
    ready += sorted(_lfs_paths(source, odd_size, env))
    return len(mine), sorted(ready)


def _clone_into(target: Path, source: Path, paths: Sequence[str], *, replace_existing: bool,
                result: SeedResult) -> List[str]:
    """Clone each of [paths]; returns those cloned. Stops at the first refusal of the first file,
    which means this filesystem (or this pair of volumes) cannot clone."""
    cloned: List[str] = []
    for path in paths:
        destination = target / path
        before: Optional[os.stat_result] = None
        if replace_existing:
            try:
                before = os.lstat(destination)
            except OSError:
                continue  # deleted in the target: leave it deleted
            if not stat.S_ISREG(before.st_mode):
                continue
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
        staging = destination.with_name(f".{destination.name}{SUFFIX}")
        if not try_clone(source / path, staging):
            if not cloned:
                result.note = "this filesystem cannot clone; git checked the files out"
                return cloned
            continue
        if before is not None:
            try:
                now = os.lstat(destination)
            except OSError:
                now = None
            if now is None or not _same_file(before, now):  # edited while we worked: theirs wins
                os.unlink(staging)
                continue
            os.utime(staging, ns=(before.st_atime_ns, before.st_mtime_ns))
        os.replace(staging, destination)
        cloned.append(path)
        result.cloned_bytes += os.lstat(destination).st_size
    return cloned


def _refresh_and_repair(target: Path, cloned: Sequence[str], env: Optional[Dict[str, str]]) -> int:
    """Have git re-hash every clone; check out again any it finds is not its blob. Returns how many."""
    _git_locking(target, "update-index", "-q", "--ignore-missing", "--refresh", env=env, check=False)
    stale = {os.fsdecode(p) for p in _git(target, "diff-files", "--name-only", "-z", env=env).split(b"\0") if p}
    stale &= set(cloned)
    if stale:
        subprocess.run(["git", "-C", str(target), "checkout-index", "-f", "-u", "-z", "--stdin"],
                       input=b"".join(os.fsencode(p) + b"\0" for p in sorted(stale)),
                       env=scrubbed_env(env), check=True, stdout=sys.stderr, stderr=sys.stderr)
    return len(stale)


def seed_worktree(worktree: Path, source: Optional[Path] = None, *,
                  env: Optional[Dict[str, str]] = None) -> SeedResult:
    """Replace each clean tracked file of an existing [worktree] with a clone of [source]'s
    (default: the primary checkout). Files edited in either checkout are left alone."""
    worktree = Path(worktree).resolve()
    source = Path(source).resolve() if source else primary_checkout(worktree, env)
    result = SeedResult(worktree, source)
    if source is None or source == worktree:
        result.note = "no other checkout to clone from"
        return result
    result.tracked, paths = candidates(worktree, source, target_checked_out=True, env=env)
    cloned = _clone_into(worktree, source, paths, replace_existing=True, result=result)
    result.cloned = len(cloned)
    if cloned:
        result.rewritten = _refresh_and_repair(worktree, cloned, env)
        result.cloned -= result.rewritten
    return result


def add_worktree(repo: Path, path: Path, commitish: Optional[str] = None, *, new_branch: Optional[str] = None,
                 reset_branch: Optional[str] = None, detach: bool = False, source: Optional[Path] = None,
                 env: Optional[Dict[str, str]] = None) -> SeedResult:
    """`git worktree add` whose checkout clones what [source] (default: the primary) already holds.

    Same arguments as git's: `-b new_branch`, `-B reset_branch`, `--detach`, then path and
    commit-ish. Raises `CalledProcessError` when git cannot create the worktree. Once it exists,
    any failure of the seeding leaves an ordinary, complete checkout.
    """
    repo, path = Path(repo), Path(os.path.abspath(path))
    argv = ["worktree", "add", "--no-checkout"]
    if new_branch:
        argv += ["-b", new_branch]
    if reset_branch:
        argv += ["-B", reset_branch]
    if detach:
        argv += ["--detach"]
    argv += [str(path)] + ([commitish] if commitish else [])
    _git_locking(repo, *argv, env=env)
    result = SeedResult(path, None)
    try:
        source = Path(source).resolve() if source else primary_checkout(repo, env)
        result.source = source
        _git_locking(path, "read-tree", "HEAD", env=env)
        if source is None or source == path.resolve():
            result.note = "no other checkout to clone from; git checked the files out"
        else:
            result.tracked, paths = candidates(path, source, target_checked_out=False, env=env)
            cloned = _clone_into(path, source, paths, replace_existing=False, result=result)
            result.cloned = len(cloned)
            if cloned:
                # Hashes every clone; a stale one stays stat-dirty and the reset below rewrites it.
                _git_locking(path, "update-index", "-q", "--ignore-missing", "--refresh", env=env, check=False)
                stale = {os.fsdecode(p) for p in _git(path, "diff-files", "--name-only", "-z", env=env).split(b"\0") if p}
                result.rewritten = len(stale & set(cloned))
                result.cloned -= result.rewritten
    except Exception as failure:  # noqa: BLE001 -- whatever went wrong, finish the checkout
        result.note = f"seeding failed ({failure}); git checked the files out"
        for staging in path.rglob(f"*{SUFFIX}"):
            staging.unlink()
    finally:
        # Writes every file that is missing or not its blob and leaves the up-to-date clones alone.
        _git_locking(path, "reset", "-q", "--hard", "HEAD", env=env)
        head = _git(path, "rev-parse", "HEAD", env=env).decode().strip()
        _git_locking(path, "hook", "run", "--ignore-missing", "post-checkout", "--", "0" * len(head), head, "1",
                     env=env, check=False)
    return result


CLAUDE_BRANCH_PREFIX = "claude/"
_HOOK_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def claude_worktree_create(payload: Dict[str, object], *, project: Optional[Path] = None,
                           env: Optional[Dict[str, str]] = None) -> Path:
    """Claude Code's `WorktreeCreate` hook: the seeded worktree for [payload]'s `name`.

    The worktree goes where Claude's own would, `<primary>/.claude/worktrees/<name>`, so the reaper
    and the desktop app find it, on branch `claude/<name>`. A new branch starts at `main` (`HEAD` if
    the repository has none); a `claude/<name>` that already exists and is checked out nowhere is
    checked out as it stands, so a resumed name keeps its commits. Raises `ValueError` for a name
    that is not a plain slug and `CalledProcessError` when git refuses, which fails the hook.
    """
    name = str(payload.get("name") or "")
    if not _HOOK_NAME.fullmatch(name) or ".." in name:
        raise ValueError(f"not a worktree name: {name!r}")
    start = Path(project or str(payload.get("cwd") or os.getcwd()))
    repo = primary_checkout(start, env) or Path(_git(start, "rev-parse", "--show-toplevel", env=env).decode().strip())
    path = repo / ".claude" / "worktrees" / name
    if path.exists():
        raise ValueError(f"{path} already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    branch = CLAUDE_BRANCH_PREFIX + name

    def has(ref: str) -> bool:
        return subprocess.run(["git", "-C", str(repo), "rev-parse", "--verify", "--quiet", ref],
                              env=scrubbed_env(env), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0

    if has(f"refs/heads/{branch}"):
        result = add_worktree(repo, path, branch, env=env)
    else:
        result = add_worktree(repo, path, "main" if has("refs/heads/main") else "HEAD", new_branch=branch, env=env)
    print(result.summary(), file=sys.stderr)
    # Ignored, so never checked out, and the JS build needs it (as tools/docs_notes.py does).
    lock = repo / "kotlin-js-store" / "yarn.lock"
    if lock.is_file() and not (path / "kotlin-js-store" / "yarn.lock").exists():
        (path / "kotlin-js-store").mkdir(exist_ok=True)
        if not try_clone(lock, path / "kotlin-js-store" / "yarn.lock"):
            shutil.copy2(lock, path / "kotlin-js-store" / "yarn.lock")
    return path


def _claude_hook_main(stdin: str) -> int:
    try:
        payload = json.loads(stdin or "{}")
        project = os.environ.get("CLAUDE_PROJECT_DIR")
        path = claude_worktree_create(payload, project=Path(project) if project else None)
    except (ValueError, subprocess.CalledProcessError) as failure:
        print(f"worktree_seed: WorktreeCreate failed: {failure}", file=sys.stderr)
        return 1
    print(path)  # the hook's contract: the worktree path, alone, as the last line of stdout
    return 0


def human(size: int) -> str:
    for unit, scale in (("GiB", 2**30), ("MiB", 2**20), ("KiB", 2**10)):
        if size >= scale:
            return f"{size / scale:.2f} {unit}"
    return f"{size} B"


def _measure(paths: Sequence[str]) -> int:
    for path in paths:
        files, logical, private = tree_bytes([path])
        exact = private_bytes(path) is not None or Path(path).is_dir()
        print(f"{path}: {files} files, {human(logical)} logical, {human(private)} private" + ("" if exact else " (private size unavailable)"))
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    add = sub.add_parser("add", help="git worktree add, seeded with clones")
    add.add_argument("-C", dest="repo", default=".", help="repository to add the worktree to (default: .)")
    add.add_argument("--source", help="checkout to clone from (default: the primary checkout)")
    group = add.add_mutually_exclusive_group()
    group.add_argument("-b", dest="new_branch")
    group.add_argument("-B", dest="reset_branch")
    group.add_argument("--detach", action="store_true")
    add.add_argument("path")
    add.add_argument("commitish", nargs="?")
    seed = sub.add_parser("seed", help="re-share an existing worktree's clean tracked files")
    seed.add_argument("worktree")
    seed.add_argument("--source")
    measure = sub.add_parser("measure", help="files, logical and APFS-private bytes under each path")
    measure.add_argument("paths", nargs="+")
    sub.add_parser("claude-worktree-create", help="Claude Code's WorktreeCreate hook (JSON on stdin)")
    args = parser.parse_args(argv)
    if args.command == "claude-worktree-create":
        return _claude_hook_main(sys.stdin.read())
    if args.command == "measure":
        return _measure(args.paths)
    if args.command == "seed":
        result = seed_worktree(Path(args.worktree), Path(args.source) if args.source else None)
    else:
        try:
            result = add_worktree(Path(args.repo), Path(args.path), args.commitish, new_branch=args.new_branch,
                                  reset_branch=args.reset_branch, detach=args.detach,
                                  source=Path(args.source) if args.source else None)
        except subprocess.CalledProcessError as failure:
            return failure.returncode or 1
    print(result.summary(), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
