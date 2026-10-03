#!/usr/bin/env python3
"""The landing queue: agents enqueue a branch and stop; one lander puts it on `main`.

    uv run python tools/land.py enqueue [branch] [--note "..."]   # agents: this, then stop
    uv run python tools/land.py status [id]                       # what is queued, landed, bounced
    uv run python tools/land.py wait <id>                         # block until it lands or bounces
    uv run python tools/land.py mine [branch]                     # the mailbox: this branch's attempts
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
# The lander's worktree sits on this branch rather than a detached HEAD, because a clean, merged,
# detached worktree is exactly what tools/reap_merged_worktrees.py removes; it protects this name.
LANDER_BRANCH = "land/lander"
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
    print(f"Progress: uv run python tools/land.py status {item['id']}")
    print("If you need the outcome, run this as a background command; it exits 0 when landed, 1 when bounced:")
    print(f"  uv run python tools/land.py wait {item['id']}")
    print("Coming back to this branch later: uv run python tools/land.py mine")
    return 0


def _when(iso: str | None) -> dt.datetime | None:
    try:
        return dt.datetime.fromisoformat(iso) if iso else None
    except ValueError:
        return None


def _span(seconds: float) -> str:
    seconds = int(max(seconds, 0))
    if seconds < 60:
        return f"{seconds}s"
    if seconds < 3600:
        return f"{seconds // 60}m{seconds % 60:02d}s"
    return f"{seconds // 3600}h{(seconds % 3600) // 60:02d}m"


def _since(iso: str | None) -> float:
    start = _when(iso)
    return (dt.datetime.now().astimezone() - start).total_seconds() if start else 0.0


def _took(item: dict) -> float | None:
    start, end = _when(item.get("started")), _when(item.get("finished"))
    return (end - start).total_seconds() if start and end else None


def _log_lines(item: dict) -> list[str]:
    try:
        text = Path(item["log"]).read_text(errors="replace").splitlines()
    except (KeyError, OSError):
        return []
    # Only the latest attempt: a landing that restarted after main moved begins again at "===".
    starts = [i for i, line in enumerate(text) if line.startswith("=== ")]
    return [line for line in text[starts[-1] if starts else 0 :] if line.strip()]


def _ran_gradle(item: dict) -> bool:
    return any(line.startswith("> Task :") for line in _log_lines(item))


def _phase(item: dict) -> tuple[str, str]:
    """(what the landing is doing, the last thing it printed), read from its log."""
    lines = _log_lines(item)
    if not lines:
        return "starting", ""
    steps = [line for line in lines if line.startswith("merge_regen:")]
    tasks = [line[len("> Task "):].split()[0] for line in lines if line.startswith("> Task :")]
    step = steps[-1][len("merge_regen: "):] if steps else "merging onto main"
    if tasks and not lines[-1].startswith("merge_regen:"):
        step += f"; Gradle at {tasks[-1]}"
    return step, lines[-1][:160]


def _typical(queue: Path, gradle: bool) -> float | None:
    """Median duration of recent landings of the same kind (with or without a Gradle guard)."""
    took = [t for item in _items(queue, LANDED)[-20:] if _ran_gradle(item) == gradle and (t := _took(item)) is not None]
    return sorted(took)[len(took) // 2] if took else None


def _describe(state: str, item: dict, queue: Path | None = None) -> str:
    head = f"{item['id']}  {item['sha'][:9]}  by {item['by']}"
    lines = [f"{state:8} {head}"]
    if item.get("note"):
        lines.append(f"         note: {item['note']}")
    if state == PENDING and item.get("started"):
        waited = (_when(item["started"]) - _when(item["enqueued"])).total_seconds() if _when(item.get("enqueued")) else 0
        running = _since(item["started"])
        step, last = _phase(item)
        lines[0] = f"landing  {head}"
        lines.append(f"         landing for {_span(running)} (waited {_span(waited)} in the queue)")
        lines.append(f"         now: {step}")
        if last:
            lines.append(f"         last log line: {last}")
        if queue is not None:
            gradle = _ran_gradle(item)
            typical = _typical(queue, gradle)
            kind = "with a Gradle guard" if gradle else "without Gradle so far"
            if typical:
                left = typical - running
                lines.append(
                    f"         typical landing {kind}: {_span(typical)}"
                    + (f", so about {_span(left)} to go" if left > 0 else f"; this one is {_span(-left)} over")
                )
        lines.append(f"         log: {item.get('log', '')}")
    elif state == PENDING:
        lines.append(f"         waiting {_span(_since(item.get('enqueued')))}")
    elif state == LANDED:
        took = _took(item)
        how = "Gradle guard" if _ran_gradle(item) else "no Gradle"
        lines.append(
            f"         -> main {item.get('landed_as', '')[:9]}, {_span(_since(item.get('finished')))} ago"
            + (f", took {_span(took)} ({how})" if took is not None else "")
            + (f"; {item['reason']}" if item.get("reason") else "")
        )
    elif state == BOUNCED:
        lines.append(f"         {_span(_since(item.get('finished')))} ago: {' '.join(str(item.get('reason', '?')).split())}")
        lines.append(f"         log: {item.get('log', '')}")
    return "\n".join(lines)


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


def _lander_line(queue: Path) -> str:
    if not _lander_running(queue):
        return "lander: NOT RUNNING, so nothing lands. Start it: uv run python tools/land.py run"
    try:
        info = json.loads((queue / "lander.json").read_text())
    except (OSError, json.JSONDecodeError):
        return "lander: running"
    mode = "one-off --once run, exits when the queue is empty" if info.get("once") else f"via {info.get('via', '?')}"
    return f"lander: running, pid {info.get('pid')}, up {_span(_since(info.get('started')))} ({mode}), worktree {info.get('worktree')}"


def cmd_status(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    if args.id:
        found = _find(queue, args.id)
        if not found:
            print(f"land: no item {args.id}", file=sys.stderr)
            return 2
        print(_describe(found[0], found[1], queue))
        return {PENDING: 3, LANDED: 0, BOUNCED: 1}[found[0]]
    print(_lander_line(queue))
    pending = _items(queue, PENDING)
    active = [item for item in pending if item.get("started")]
    waiting = [item for item in pending if not item.get("started")]
    print()
    for item in active:
        print(_describe(PENDING, item, queue))
    if not active:
        print("landing  nothing")
    if waiting:
        # A rough start time: what is left of the current landing plus a typical landing per item ahead.
        typical = _typical(queue, True) or _typical(queue, False) or 0.0
        ahead = 0.0
        if active:
            ahead = max((_typical(queue, _ran_gradle(active[0])) or typical) - _since(active[0]["started"]), 0.0)
        print(f"\nqueued ({len(waiting)}), in landing order:")
        for n, item in enumerate(waiting, 1):
            estimate = f", starts in about {_span(ahead)}" if typical else ""
            print(f"  {n}. {item['id']}  {item['sha'][:9]}  by {item['by']}  waiting {_span(_since(item.get('enqueued')))}{estimate}")
            ahead += typical
    else:
        print("\nqueued   nothing")
    for state in (BOUNCED, LANDED):
        recent = _items(queue, state)[-args.recent:]
        if recent:
            print(f"\nrecent {state} (oldest first):")
            for item in recent:
                print(_describe(state, item))
    return 0


def cmd_mine(args: argparse.Namespace) -> int:
    """The mailbox: every attempt to land this branch, newest first. Exit 1 if the newest bounced."""
    here = Path.cwd()
    branch = args.branch or _git(here, "branch", "--show-current")
    queue = _queue_dir(here)
    attempts = sorted(
        ((state, item) for state in (PENDING, LANDED, BOUNCED) for item in _items(queue, state) if item["branch"] == branch),
        key=lambda pair: pair[1]["id"], reverse=True,
    )
    if not attempts:
        print(f"land: nothing has been enqueued from {branch}")
        return 0
    for state, item in attempts:
        print(_describe(state, item, queue))
    newest = attempts[0][0]
    if newest == BOUNCED:
        print(f"\nThe latest attempt bounced. Fix it on {branch}, commit, and enqueue again.")
    return {PENDING: 3, LANDED: 0, BOUNCED: 1}[newest]


def cmd_wait(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    while True:
        found = _find(queue, args.id)
        if not found:
            print(f"land: no item {args.id}", file=sys.stderr)
            return 2
        if found[0] != PENDING:
            print(_describe(found[0], found[1], queue))
            return 0 if found[0] == LANDED else 1
        time.sleep(POLL_SECONDS)


# ---------------------------------------------------------------------------- the lander


def _lander_worktree(primary: Path, requested: str | None) -> Path:
    path = Path(requested) if requested else primary.parent / f"{primary.name}-worktrees" / "lander"
    if not (path / ".git").exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        _git(primary, "worktree", "add", "-B", LANDER_BRANCH, str(path), "main")
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
    _git(wt, "checkout", "-q", "--force", "-B", LANDER_BRANCH, main_sha)
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
    # The reference-transaction hook refuses every other writer of main.
    env = {**os.environ, "FIELD_LANDER": "1"}
    if holder is None:
        moved = subprocess.run(["git", "update-ref", "refs/heads/main", new, old], cwd=primary, env=env)
        return None if moved.returncode == 0 else "main moved"
    # Some worktree has main checked out (usually the primary, with other sessions' edits in it).
    # --ff-only refuses rather than overwrite a dirty file, so their work is safe either way.
    result = subprocess.run(
        ["git", "merge", "--ff-only", "-q", new], cwd=holder, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    if result.returncode == 0:
        return None
    if _git(primary, "rev-parse", "refs/heads/main") != old:
        return "main moved"
    return f"fast-forward refused in {holder}: {' '.join(result.stdout.split())}"


def _first_error(log: Path) -> str:
    text = log.read_text(errors="replace").splitlines()
    for pattern in (r"merge_regen: .*fail", r"source conflicts", r"FAILED", r"What went wrong", r"[Ee]rror"):
        for i, line in enumerate(text):
            if re.search(pattern, line):
                return " | ".join(" ".join(l.split()) for l in text[i : i + 3] if l.strip())[:400]
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
                # Unbuffered, so `status` can read which step it is on while it runs.
                cwd=wt, stdout=out, stderr=subprocess.STDOUT, env={**os.environ, "PYTHONUNBUFFERED": "1"},
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
        via = "launchd" if os.environ.get("XPC_SERVICE_NAME", "").startswith("com.field") else (
            "a terminal" if sys.stdin.isatty() else "a background process")
        (queue / "lander.json").write_text(json.dumps(
            {"pid": os.getpid(), "started": _now(), "once": args.once, "via": via, "worktree": str(wt)}) + "\n")
        print(f"land: lander up in {wt}; queue {queue}", flush=True)
        while True:
            pending = _items(queue, PENDING)
            if pending:
                item = pending[0]
                wt = _lander_worktree(primary, args.worktree)  # re-created if something removed it
                print(f"land: {_now()} landing {item['id']} ({item['sha'][:9]}, by {item['by']})", flush=True)
                _land(item, primary, wt, queue)
                state, done = _find(queue, item["id"]) or ("?", item)
                print(_describe(state, done, queue), flush=True)
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
    p = sub.add_parser("mine", help="every landing attempt for this branch, newest first (exit 1 if it bounced)")
    p.add_argument("branch", nargs="?", help="default: the current branch")
    p.set_defaults(func=cmd_mine)
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
