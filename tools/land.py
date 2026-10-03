#!/usr/bin/env python3
"""The landing queue: agents enqueue a branch and stop; one lander puts it on `main`.

    uv run python tools/land.py enqueue [branch] [--note "..."]   # agents: this, then stop
    uv run python tools/land.py status [id]                       # what is queued, landed, bounced
    uv run python tools/land.py wait <id>                         # block until it lands or bounces
    uv run python tools/land.py run [--once]                      # the lander (one per clone)

Why: every agent used to land itself. Each merged `main`, ran ~45 minutes of guards, found `main` had
moved, and merged again, colliding with everyone else doing the same. Here only the lander writes to
`main`, one item at a time, so nobody races and nobody needs to wait for a landing.

What the lander does with each item, oldest first:

1. Resets its own worktree to `main` (it never touches yours).
2. Skips the item as already landed if its commit is already on `main`.
3. `tools/merge_regen.py <commit>`: merges, regenerates the generated artifacts on the merged tree,
   runs the full generated-artifact guard once, and commits `Land <branch> (<who>)`. That commit is
   the record of who landed what.
4. Fast-forwards `main` to it. If `main` moved meanwhile (a human merged by hand), it starts the item
   again from step 1.
5. On any failure it bounces the item: the reason and the log path go in `status`, and `main` is
   untouched. Fix the branch, commit, and enqueue again.

The queue is plain JSON files in the clone's common git dir (`field-land-queue/`), so every worktree
of the clone sees the same queue. The commit is pinned at enqueue time: later commits on the branch
are not landed until it is enqueued again.
"""

from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

PENDING, LANDED, BOUNCED = "pending", "landed", "bounced"
POLL_SECONDS = 15
MAX_MAIN_RACES = 3


def _git(cwd: Path, *args: str, check: bool = True) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=check, stdout=subprocess.PIPE, text=True).stdout.strip()


def _ok(cwd: Path, *args: str) -> bool:
    return subprocess.run(["git", *args], cwd=cwd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def _common_dir(cwd: Path) -> Path:
    return Path(_git(cwd, "rev-parse", "--path-format=absolute", "--git-common-dir"))


def _primary(cwd: Path) -> Path:
    # The first `worktree` line of the porcelain listing is the main working tree.
    return Path(_git(cwd, "worktree", "list", "--porcelain").splitlines()[0].split(" ", 1)[1])


def _queue_dir(cwd: Path) -> Path:
    queue = _common_dir(cwd) / "field-land-queue"
    for state in (PENDING, LANDED, BOUNCED):
        (queue / state).mkdir(parents=True, exist_ok=True)
    (queue / "logs").mkdir(exist_ok=True)
    return queue


def _now() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


def _items(queue: Path, state: str) -> list[dict]:
    items = []
    for path in sorted((queue / state).glob("*.json")):
        try:
            items.append(json.loads(path.read_text()))
        except (OSError, json.JSONDecodeError):
            continue
    return items


def _find(queue: Path, item_id: str) -> tuple[str, dict] | None:
    for state in (PENDING, LANDED, BOUNCED):
        path = queue / state / f"{item_id}.json"
        if path.exists():
            return state, json.loads(path.read_text())
    return None


def _move(queue: Path, item: dict, to_state: str) -> None:
    for state in (PENDING, LANDED, BOUNCED):
        (queue / state / f"{item['id']}.json").unlink(missing_ok=True)
    (queue / to_state / f"{item['id']}.json").write_text(json.dumps(item, indent=2) + "\n")


# ---------------------------------------------------------------------------- enqueue / status / wait


def cmd_enqueue(args: argparse.Namespace) -> int:
    here = Path.cwd()
    branch = args.branch or _git(here, "branch", "--show-current")
    if not branch:
        print("land: not on a branch; name the branch to land", file=sys.stderr)
        return 2
    if branch == "main":
        print("land: refusing to enqueue main itself; commit on a topic branch", file=sys.stderr)
        return 2
    if not _ok(here, "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}"):
        print(f"land: no local branch {branch}", file=sys.stderr)
        return 2
    if not args.branch:
        # Only uncommitted work in *this* worktree can be meant for this branch.
        dirty = _git(here, "status", "--porcelain", "--untracked-files=no")
        if dirty:
            print("land: this worktree has uncommitted changes, which would not land:", file=sys.stderr)
            print(dirty, file=sys.stderr)
            print("Commit them (or pass the branch name explicitly to land only what is committed).", file=sys.stderr)
            return 2
    sha = _git(here, "rev-parse", f"refs/heads/{branch}")
    queue = _queue_dir(here)
    if _ok(here, "merge-base", "--is-ancestor", sha, "refs/heads/main"):
        print(f"land: {branch} ({sha[:9]}) is already on main; nothing to land")
        return 0
    for item in _items(queue, PENDING):
        if item["branch"] == branch:
            # A re-enqueue replaces the older request: the newest commit is the one meant.
            (queue / PENDING / f"{item['id']}.json").unlink(missing_ok=True)
            print(f"land: replacing queued {item['id']} ({item['sha'][:9]})")
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    item = {
        "id": f"{stamp}-{re.sub(r'[^A-Za-z0-9._-]+', '-', branch)}",
        "branch": branch,
        "sha": sha,
        "by": args.by or os.environ.get("FIELD_LAND_BY") or f"{_git(here, 'config', 'user.name', check=False) or 'unknown'} @ {here.name}",
        "note": args.note or "",
        "enqueued": _now(),
    }
    _move(queue, item, PENDING)
    ahead = len(_items(queue, PENDING)) - 1
    print(f"land: queued {item['id']} ({sha[:9]}), {ahead} ahead of it.")
    print("You are done: the lander merges, regenerates, checks and fast-forwards main.")
    print(f"Outcome: uv run python tools/land.py status {item['id']}")
    return 0


def _describe(state: str, item: dict) -> str:
    line = f"{state:8} {item['id']}  {item['sha'][:9]}  by {item['by']}"
    if state == LANDED:
        line += f"  -> main {item.get('landed_as', '')[:9]} at {item.get('finished', '')}"
    if state == BOUNCED:
        line += f"\n         reason: {item.get('reason', '?')}\n         log: {item.get('log', '')}"
    if item.get("started") and state == PENDING:
        line += "  (landing now)"
    return line


def _lander_running(queue: Path) -> bool:
    # Ask the lock itself: a PID file outlives a killed lander, the kernel's flock does not.
    path = queue / "lander.lock"
    if not path.exists():
        return False
    with path.open("a") as probe:
        try:
            fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        fcntl.flock(probe, fcntl.LOCK_UN)
        return False


def cmd_status(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    if args.id:
        found = _find(queue, args.id)
        if not found:
            print(f"land: no item {args.id}", file=sys.stderr)
            return 2
        print(_describe(*found))
        return {PENDING: 3, LANDED: 0, BOUNCED: 1}[found[0]]
    pending = _items(queue, PENDING)
    print(f"{len(pending)} queued" + ("" if _lander_running(queue) else "  (no lander running: tools/land.py run)"))
    for item in pending:
        print(_describe(PENDING, item))
    for state in (BOUNCED, LANDED):
        recent = _items(queue, state)[-args.recent:]
        if recent:
            print(f"\nrecent {state}:")
            for item in recent:
                print(_describe(state, item))
    return 0


def cmd_wait(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    while True:
        found = _find(queue, args.id)
        if not found:
            print(f"land: no item {args.id}", file=sys.stderr)
            return 2
        if found[0] != PENDING:
            print(_describe(*found))
            return 0 if found[0] == LANDED else 1
        time.sleep(POLL_SECONDS)


# ---------------------------------------------------------------------------- the lander


def _lander_worktree(primary: Path, requested: str | None) -> Path:
    path = Path(requested) if requested else primary.parent / f"{primary.name}-worktrees" / "lander"
    if not (path / ".git").exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        _git(primary, "worktree", "add", "--detach", str(path), "main")
        subprocess.run(["git", "lfs", "checkout"], cwd=path)
        # yarn.lock is untracked but the JS build needs it.
        lock = primary / "kotlin-js-store/yarn.lock"
        if lock.exists() and not (path / "kotlin-js-store/yarn.lock").exists():
            (path / "kotlin-js-store").mkdir(exist_ok=True)
            shutil.copyfile(lock, path / "kotlin-js-store/yarn.lock")
    return path


def _reset(wt: Path, main_sha: str) -> None:
    if _ok(wt, "rev-parse", "-q", "--verify", "MERGE_HEAD"):
        _git(wt, "merge", "--abort", check=False)
    _git(wt, "checkout", "-q", "--detach", "--force", main_sha)
    _git(wt, "reset", "-q", "--hard", main_sha)
    # Untracked files only; ignored build output stays, which keeps Gradle warm between items.
    _git(wt, "clean", "-q", "-fd")


def _fast_forward_main(primary: Path, old: str, new: str) -> str | None:
    """Move main from old to new. Returns None on success, or why not."""
    holder = None
    current = None
    for line in _git(primary, "worktree", "list", "--porcelain").splitlines() + [""]:
        if line.startswith("worktree "):
            current = Path(line.split(" ", 1)[1])
        elif line == "branch refs/heads/main":
            holder = current
    if _git(primary, "rev-parse", "refs/heads/main") != old:
        return "main moved"
    if holder is None:
        return None if _ok(primary, "update-ref", "refs/heads/main", new, old) else "main moved"
    # Some worktree has main checked out (usually the primary, with other sessions' edits in it).
    # --ff-only refuses rather than overwrite a dirty file, so their work is safe either way.
    result = subprocess.run(
        ["git", "merge", "--ff-only", "-q", new], cwd=holder, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    if result.returncode == 0:
        return None
    if _git(primary, "rev-parse", "refs/heads/main") != old:
        return "main moved"
    return f"fast-forward refused in {holder}: {result.stdout.strip()}"


def _first_error(log: Path) -> str:
    text = log.read_text(errors="replace").splitlines()
    for pattern in (r"merge_regen: .*fail", r"source conflicts", r"FAILED", r"What went wrong", r"[Ee]rror"):
        for i, line in enumerate(text):
            if re.search(pattern, line):
                return " | ".join(l.strip() for l in text[i : i + 3] if l.strip())[:400]
    return (text[-1] if text else "no output")[:400]


def _land(item: dict, primary: Path, wt: Path, queue: Path) -> None:
    log = queue / "logs" / f"{item['id']}.log"
    item["log"] = str(log)
    item["started"] = _now()
    _move(queue, item, PENDING)
    if not _ok(primary, "cat-file", "-e", f"{item['sha']}^{{commit}}"):
        item.update(reason="its commit no longer exists", finished=_now())
        _move(queue, item, BOUNCED)
        return
    for _attempt in range(MAX_MAIN_RACES):
        main_sha = _git(primary, "rev-parse", "refs/heads/main")
        if _ok(primary, "merge-base", "--is-ancestor", item["sha"], main_sha):
            item.update(landed_as=main_sha, finished=_now(), reason="already on main")
            _move(queue, item, LANDED)
            return
        _reset(wt, main_sha)
        message = f"Land {item['branch']} ({item['by']})\n\nLanding queue item {item['id']}, commit {item['sha']}."
        if item.get("note"):
            message += f"\n\n{item['note']}"
        with log.open("a") as out:
            out.write(f"\n=== {_now()} merging {item['sha']} onto main {main_sha}\n")
            out.flush()
            code = subprocess.run(
                [sys.executable, str(wt / "tools/merge_regen.py"), item["sha"], "-m", message],
                cwd=wt, stdout=out, stderr=subprocess.STDOUT,
            ).returncode
        if code != 0:
            item.update(reason=_first_error(log), finished=_now())
            _reset(wt, main_sha)
            _move(queue, item, BOUNCED)
            return
        new = _git(wt, "rev-parse", "HEAD")
        why = _fast_forward_main(primary, main_sha, new)
        if why is None:
            item.update(landed_as=new, finished=_now())
            _move(queue, item, LANDED)
            return
        if why != "main moved":
            item.update(reason=why, finished=_now())
            _move(queue, item, BOUNCED)
            return
        with log.open("a") as out:
            out.write("main moved during the landing; starting again on the new main\n")
    item.update(reason=f"main moved under the lander {MAX_MAIN_RACES} times; enqueue again", finished=_now())
    _move(queue, item, BOUNCED)


def cmd_run(args: argparse.Namespace) -> int:
    here = Path.cwd()
    primary = _primary(here)
    queue = _queue_dir(here)
    lock_file = (queue / "lander.lock").open("w")
    try:
        fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("land: another lander is already running for this clone", file=sys.stderr)
        return 2
    try:
        if not _git(primary, "config", "--get", "merge.field-generated.driver", check=False):
            print("land: run `uv run python tools/install_git_hooks.py` first (merge driver missing)", file=sys.stderr)
            return 2
        wt = _lander_worktree(primary, args.worktree)
        print(f"land: lander up in {wt}; queue {queue}", flush=True)
        while True:
            pending = _items(queue, PENDING)
            if pending:
                item = pending[0]
                print(f"land: {_now()} landing {item['id']} ({item['sha'][:9]}, by {item['by']})", flush=True)
                _land(item, primary, wt, queue)
                state, done = _find(queue, item["id"]) or ("?", item)
                print(_describe(state, done), flush=True)
                continue
            if args.once:
                return 0
            time.sleep(POLL_SECONDS)
    finally:
        lock_file.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("enqueue", help="queue a branch for landing, then stop")
    p.add_argument("branch", nargs="?", help="default: the current branch")
    p.add_argument("--note", help="one line for the landing commit, e.g. what to look at if it bounces")
    p.add_argument("--by", help="who is asking (default: $FIELD_LAND_BY, else user @ worktree)")
    p.set_defaults(func=cmd_enqueue)
    p = sub.add_parser("status", help="show the queue, or one item (exit 0 landed, 1 bounced, 3 pending)")
    p.add_argument("id", nargs="?")
    p.add_argument("--recent", type=int, default=5)
    p.set_defaults(func=cmd_status)
    p = sub.add_parser("wait", help="block until an item lands (exit 0) or bounces (exit 1)")
    p.add_argument("id")
    p.set_defaults(func=cmd_wait)
    p = sub.add_parser("run", help="the lander: drain the queue onto main, one item at a time")
    p.add_argument("--once", action="store_true", help="drain the queue and exit instead of polling")
    p.add_argument("--worktree", help="the lander's own worktree (default: <primary>-worktrees/lander)")
    p.set_defaults(func=cmd_run)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
