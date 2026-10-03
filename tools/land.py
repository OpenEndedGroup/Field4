#!/usr/bin/env python3
"""The landing queue: agents enqueue a branch and stop; one lander puts it on `main`.

    uv run python tools/land.py enqueue [branch] [--note "..."]   # agents: this, then stop
    uv run python tools/land.py status [id] [--watch]             # what is queued, landed, bounced
    uv run python tools/land.py wait <id>                         # block until it lands or bounces
    uv run python tools/land.py mine [branch]                     # the mailbox: this branch's attempts
    uv run python tools/land.py sync [--push]                     # bring origin/main in via the queue
    uv run python tools/land.py run [--once]                      # the lander (one per clone)

Why: every agent used to land itself. Each merged `main`, ran ~45 minutes of guards, found `main` had
moved, and merged again, colliding with everyone else doing the same. Here only the lander writes to
`main`, one item at a time, so nobody races and nobody needs to wait for a landing.

Batching: the lander takes up to --batch (default 8) of the oldest items per round, merges each as
its own commit, and runs ONE regeneration and ONE guard for all of them. A red round is bisected:
the batch halves and retries from the oldest until the culprit bounces alone, and every retry builds
on the current main, so a pair that only breaks together lands the first and bounces the second. A
branch that conflicts only with another branch in its batch is deferred to a later round, not bounced.

What the lander does with each round:

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
import contextlib
import datetime as dt
import fcntl
import io
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
    for path in (queue / state).glob("*.json"):
        try:
            items.append(json.loads(path.read_text()))
        except (OSError, json.JSONDecodeError):
            continue
    # Arrival order; items queued before `seq` existed fall back to their id, which is a timestamp.
    return sorted(items, key=lambda item: (item.get("seq", 0), item["id"]))


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
        # An item the lander has started stays: removing its file mid-landing hid it from status
        # while it landed anyway. The new commit queues behind it instead.
        if item["branch"] == branch and not item.get("started"):
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
        # Push main to origin once this lands (and only if it lands).
        "push": bool(getattr(args, "push", False)),
        "enqueued": _now(),
        # Queue order. Ids only resolve to the second, so sorting by them put two branches enqueued in
        # the same second in alphabetical order instead of the order they arrived.
        "seq": time.time_ns(),
    }
    # Ids are per second; a second enqueue of the same branch within it must not overwrite the first.
    base, n = item["id"], 1
    while _find(queue, item["id"]):
        n += 1
        item["id"] = f"{base}-{n}"
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
        # citationGates names each guard class as it goes (build.gradle.kts adds a test listener).
        done = [line for line in lines if line.startswith("citationGates: [")]
        running = [line for line in lines if line.startswith("citationGates: running ")]
        if tasks[-1] == ":citationGates" and (done or running):
            count = done[-1].split("]")[0][len("citationGates: ["):] if done else "0/?"
            current = running[-1][len("citationGates: running "):] if running else ""
            finished_current = done and current and f"] {current} " in done[-1]
            step += f" {count}" + (f", running {current}" if current and not finished_current else "")
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
        if item.get("pushed_as"):
            lines.append(f"         pushed main {item['pushed_as'][:9]} to origin")
        elif item.get("push_error"):
            lines.append(f"         NOT pushed: {item['push_error']}")
        elif item.get("push"):
            lines.append("         push requested")
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


# ---------------------------------------------------------------------------- terminal rendering
#
# Only when stdout is a terminal: agents and pipes get the plain text above, which stays stable.
# NO_COLOR (https://no-color.org) turns colour off; FIELD_LAND_PLAIN=1 swaps Nerd Font glyphs for ASCII.


class _Style:
    def __init__(self) -> None:
        self.color = "NO_COLOR" not in os.environ
        nerd = os.environ.get("FIELD_LAND_PLAIN") != "1"
        self.g = {
            "up": "\uf111" if nerd else "*", "down": "\uf05e" if nerd else "x",
            "land": "\uf135" if nerd else ">", "wait": "\uf017" if nerd else "~",
            "ok": "\uf00c" if nerd else "+", "bounce": "\uf00d" if nerd else "!",
            "branch": "\ue0a0" if nerd else "", "user": "\uf007" if nerd else "",
            "gradle": "\ue660" if nerd else "", "note": "\uf249" if nerd else "#",
            "log": "\uf15c" if nerd else "", "main": "\uf126" if nerd else "",
        }

    def c(self, code: str, text: str) -> str:
        return f"\033[{code}m{text}\033[0m" if self.color else text

    def dim(self, t: str) -> str: return self.c("2", t)
    def bold(self, t: str) -> str: return self.c("1", t)
    def green(self, t: str) -> str: return self.c("32", t)
    def red(self, t: str) -> str: return self.c("31", t)
    def yellow(self, t: str) -> str: return self.c("33", t)
    def cyan(self, t: str) -> str: return self.c("36", t)
    def magenta(self, t: str) -> str: return self.c("35", t)


def _bar(fraction: float, width: int = 24) -> str:
    full = max(0, min(width, round(fraction * width)))
    return "\u2588" * full + "\u2591" * (width - full)


def _pretty_status(queue: Path, recent: int) -> None:
    st = _Style()
    g = st.g
    width = min(shutil.get_terminal_size((100, 20)).columns, 110)
    rule = lambda title: print(st.dim("\u2500\u2500 ") + st.bold(title) + " " + st.dim("\u2500" * max(width - len(title) - 4, 0)))

    def who(item: dict) -> str:
        return f"{st.magenta(g['branch'] + ' ' + item['branch'])}  {st.dim(item['sha'][:9])}  {st.dim(g['user'] + ' ' + item['by'])}"

    # Lander
    if _lander_running(queue):
        try:
            info = json.loads((queue / "lander.json").read_text())
        except (OSError, json.JSONDecodeError):
            info = {}
        mode = "--once, exits when empty" if info.get("once") else f"via {info.get('via', '?')}"
        print(f"{st.green(g['up'])} {st.bold('lander up')}  "
              + st.dim(f"pid {info.get('pid', '?')} \u00b7 {_span(_since(info.get('started')))} \u00b7 {mode}"))
    else:
        print(f"{st.red(g['down'])} {st.bold(st.red('lander down'))}  nothing lands. "
              + st.cyan("uv run python tools/land.py run"))
    print()

    pending = _items(queue, PENDING)
    active = [i for i in pending if i.get("started")]
    waiting = [i for i in pending if not i.get("started")]

    rule("landing")
    if not active:
        print(st.dim("  idle"))
    for item in active:
        running = _since(item["started"])
        step, last = _phase(item)
        gradle = _ran_gradle(item)
        typical = _typical(queue, gradle)
        print(f"  {st.yellow(g['land'])} {who(item)}")
        if item.get("note"):
            print(f"    {st.dim(g['note'] + ' ' + item['note'])}")
        if typical:
            frac = running / typical
            tail = f"~{_span(typical - running)} left" if running < typical else st.yellow(f"{_span(running - typical)} over typical")
            print(f"    {st.cyan(_bar(min(frac, 1.0)))}  {st.bold(_span(running))} {st.dim('/ ~' + _span(typical))}  {tail}")
        else:
            print(f"    {st.bold(_span(running))} {st.dim('elapsed, no typical duration yet')}")
        print(f"    {st.dim('step')}  {step}")
        if last:
            print(f"    {st.dim('last')}  {st.dim(last[: width - 12])}")
        print(f"    {st.dim(g['log'] + ' ' + item.get('log', ''))}")
    print()

    rule(f"queued \u00b7 {len(waiting)}")
    if not waiting:
        print(st.dim("  empty"))
    typical = _typical(queue, True) or _typical(queue, False) or 0.0
    ahead = 0.0
    if active:
        ahead = max((_typical(queue, _ran_gradle(active[0])) or typical) - _since(active[0]["started"]), 0.0)
    for n, item in enumerate(waiting, 1):
        eta = f"  starts ~{_span(ahead)}" if typical else ""
        print(f"  {st.dim(str(n) + '.')} {st.cyan(g['wait'])} {who(item)}  {st.dim('waiting ' + _span(_since(item.get('enqueued'))) + eta)}")
        ahead += typical
    print()

    finished = sorted(
        [(LANDED, i) for i in _items(queue, LANDED)] + [(BOUNCED, i) for i in _items(queue, BOUNCED)],
        key=lambda pair: pair[1].get("finished", ""),
    )[-recent:]
    rule("recent")
    if not finished:
        print(st.dim("  nothing yet"))
    for state, item in reversed(finished):
        ago = _span(_since(item.get("finished")))
        took = _took(item)
        if state == LANDED:
            how = (g["gradle"] + " guard") if _ran_gradle(item) else "no gradle"
            print(f"  {st.green(g['ok'])} {who(item)}  {st.dim(g['main'] + ' ' + item.get('landed_as', '')[:9])}"
                  f"  {st.dim(ago + ' ago')}" + (st.dim(f" \u00b7 took {_span(took)} \u00b7 {how}") if took is not None else ""))
        else:
            reason = " ".join(str(item.get("reason", "?")).split())
            print(f"  {st.red(g['bounce'])} {who(item)}  {st.dim(ago + ' ago')}")
            print(f"    {st.red(reason[: width - 6])}")


def cmd_status(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    if args.id:
        found = _find(queue, args.id)
        if not found:
            print(f"land: no item {args.id}", file=sys.stderr)
            return 2
        print(_describe(found[0], found[1], queue))
        return {PENDING: 3, LANDED: 0, BOUNCED: 1}[found[0]]
    if sys.stdout.isatty() and not args.plain:
        if not args.watch:
            _pretty_status(queue, args.recent)
            return 0
        # Read keys one at a time without echo, so `q` or Esc quits: not every embedded terminal
        # delivers ctrl-c. The tty is restored however the loop ends.
        import select, termios, tty
        stdin = sys.stdin.fileno() if sys.stdin.isatty() else None
        saved = termios.tcgetattr(stdin) if stdin is not None else None
        try:
            if stdin is not None:
                tty.setcbreak(stdin)
            # The alternate screen, as top and less use: the redraw never reaches the scrollback, and
            # the shell's screen comes back untouched on exit.
            sys.stdout.write("\033[?1049h\033[?25l\033[2J")
            while True:
                frame = io.StringIO()
                with contextlib.redirect_stdout(frame):
                    _pretty_status(queue, args.recent)
                # Clip to the window: a frame taller than the terminal scrolls on every redraw, which
                # stacked frames into the scrollback. Clear each line's tail, since a shorter line
                # would otherwise keep the old text.
                rows = shutil.get_terminal_size((100, 24)).lines
                lines = frame.getvalue().split("\n")
                if len(lines) > rows - 2:
                    lines = lines[: rows - 3] + [_Style().dim(f"  \u2026 {len(lines) - rows + 3} more lines (make the window taller)")]
                footer = _Style().dim(f"{_now()} \u00b7 every 2s \u00b7 q to quit")
                sys.stdout.write("\033[H" + "\033[K\n".join(lines) + "\033[K\n" + footer + "\033[K\033[J")
                sys.stdout.flush()
                if stdin is None:
                    time.sleep(2)
                elif select.select([stdin], [], [], 2)[0] and os.read(stdin, 1) in (b"q", b"Q", b"\x1b", b"\x03"):
                    break
        except KeyboardInterrupt:
            pass
        finally:
            if saved is not None:
                termios.tcsetattr(stdin, termios.TCSADRAIN, saved)
            sys.stdout.write("\033[?25h\033[?1049l")
        return 0
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


def cmd_sync(args: argparse.Namespace) -> int:
    """Bring origin/main in through the queue (and optionally push after), never by merging main."""
    here = Path.cwd()
    primary = _primary(here)
    fetch = subprocess.run(["git", "fetch", "origin", "main"], cwd=primary)
    if fetch.returncode != 0:
        print("land: git fetch origin main failed", file=sys.stderr)
        return fetch.returncode
    upstream = _git(primary, "rev-parse", "refs/remotes/origin/main")
    behind = int(_git(primary, "rev-list", "--count", "refs/heads/main..refs/remotes/origin/main"))
    ahead = int(_git(primary, "rev-list", "--count", "refs/remotes/origin/main..refs/heads/main"))
    print(f"land: main is {ahead} ahead of origin/main and {behind} behind")
    if behind == 0:
        if not args.push or ahead == 0:
            print("land: nothing to bring in" + ("" if args.push else "; pass --push to push main"))
            return 0
        with (_queue_dir(here) / "logs" / "push.log").open("a") as out:
            why = _push_main(primary, _git(primary, "rev-parse", "refs/heads/main"), out)
        print("land: pushed main to origin" if why is None else f"land: push failed: {why}")
        return 0 if why is None else 1
    branch = f"sync/origin-main-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}"
    _git(primary, "branch", branch, upstream)
    print(f"land: {branch} = origin/main {upstream[:9]}")
    return cmd_enqueue(argparse.Namespace(
        branch=branch, by=args.by, push=args.push,
        note=f"bring in {behind} commit(s) from origin/main" + ("; push after" if args.push else ""),
    ))


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
    # Most specific first: a failing test, a compiler error, Gradle's own account, and only then
    # merge_regen's summary line, which names no cause.
    for pattern in (r" > .* FAILED$", r"^e: ", r"source conflicts", r"What went wrong", r"FAILED",
                    r"merge_regen: .*fail", r"[Ee]rror"):
        for i, line in enumerate(text):
            if re.search(pattern, line):
                return " | ".join(" ".join(l.split()) for l in text[i : i + 3] if l.strip())[:400]
    return (text[-1] if text else "no output")[:400]


def _message(item: dict) -> str:
    message = f"Land {item['branch']} ({item['by']})\n\nLanding queue item {item['id']}, commit {item['sha']}."
    if item.get("note"):
        message += f"\n\n{item['note']}"
    return message


def _requeue(queue: Path, item: dict, why: str) -> None:
    """Back to waiting, as if never started: a later round takes it again."""
    for key in ("started", "batch", "batch_size", "merged_as", "log"):
        item.pop(key, None)
    item.setdefault("history", []).append(f"{_now()} {why}")
    _move(queue, item, PENDING)


def _push_main(primary: Path, sha: str, out) -> str | None:
    """Push main to origin. None on success, else why not. Never forces, never touches local main."""
    out.write(f"merge_regen: pushing main {sha[:9]} to origin\n")
    out.flush()
    result = subprocess.run(["git", "push", "origin", f"{sha}:refs/heads/main"], cwd=primary,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out.write(result.stdout)
    if result.returncode == 0:
        return None
    if "rejected" in result.stdout or "fetch first" in result.stdout or "non-fast-forward" in result.stdout:
        return "origin/main has commits main lacks; run `uv run python tools/land.py sync --push`"
    return " ".join(result.stdout.split())[-300:] or f"git push exited {result.returncode}"


def _collect_failures(wt: Path, since: float, log: Path) -> str | None:
    """Gradle prints a failing test as "AssertionError at Foo.kt:769"; the message itself is only in
    the JUnit XML inside the lander's worktree, which the next round overwrites. Copy every failure
    written during this round next to the log, and return a one-line summary of the first."""
    import xml.etree.ElementTree as ET
    found: list[tuple[str, str]] = []
    for xml in sorted((wt / "build" / "test-results").glob("*/TEST-*.xml")):
        if xml.stat().st_mtime < since:
            continue
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                name = f"{case.get('classname', '').rsplit('.', 1)[-1]}.{case.get('name')}"
                found.append((name, (failure.get("message") or failure.text or "").strip()))
    if not found:
        return None
    path = log.with_suffix(".failures.txt")
    path.write_text("".join(f"## {name}\n{message}\n\n" for name, message in found))
    first_name, first_message = found[0]
    first_line = next((line.strip() for line in first_message.splitlines() if line.strip()), "")
    more = f" (+{len(found) - 1} more)" if len(found) > 1 else ""
    return f"{first_name}: {first_line[:220]}{more}; full messages: {path}"


def _land_batch(items: list[dict], primary: Path, wt: Path, queue: Path, bisecting: int | None) -> str:
    """Land [items] together: one merge commit each, then one regeneration and one guard for all.

    Returns "green" (everything that could land did), "red" (the combined guard failed with more
    than one branch in it: all of them are back in the queue and the caller halves the batch), or
    "bounced" (a single branch failed on its own and has been bounced).
    """
    batch_id = f"batch-{dt.datetime.now().strftime('%Y%m%d-%H%M%S-%f')}"
    log = queue / "logs" / f"{batch_id}.log"
    for item in items:
        item.update(log=str(log), started=_now(), batch=batch_id, batch_size=len(items))
        _move(queue, item, PENDING)
    live = []
    for item in items:
        if _ok(primary, "cat-file", "-e", f"{item['sha']}^{{commit}}"):
            live.append(item)
        else:
            item.update(reason="its commit no longer exists", finished=_now())
            _move(queue, item, BOUNCED)
    env = {**os.environ, "PYTHONUNBUFFERED": "1"}

    for _attempt in range(MAX_MAIN_RACES):
        main_sha = _git(primary, "rev-parse", "refs/heads/main")
        todo = []
        for item in live:
            if _ok(primary, "merge-base", "--is-ancestor", item["sha"], main_sha):
                item.update(landed_as=main_sha, finished=_now(), reason="already on main")
                _move(queue, item, LANDED)
            else:
                todo.append(item)
        if not todo:
            return "green"
        _reset(wt, main_sha)
        round_started = time.time()
        members: list[dict] = []
        with log.open("a") as out:
            out.write(f"\n=== {_now()} {batch_id}: {len(todo)} branch(es) onto main {main_sha}"
                      + (f" (bisecting a red batch of {bisecting})" if bisecting else "") + "\n")
            for item in todo:
                out.write(f"merge_regen: merging {item['branch']} {item['sha'][:9]}\n")
                out.flush()
                subprocess.run(["git", "merge", "--no-ff", "--no-commit", item["sha"]], cwd=wt, stdout=out,
                               stderr=subprocess.STDOUT, env=env)
                conflicts = _git(wt, "diff", "--name-only", "--diff-filter=U", check=False).split()
                merging = _ok(wt, "rev-parse", "-q", "--verify", "MERGE_HEAD")
                if conflicts or not merging:
                    if merging:
                        _git(wt, "merge", "--abort", check=False)
                    files = ", ".join(conflicts) or "see the log"
                    if members:
                        # It may only conflict with a batch member that has yet to pass the guard; try
                        # it again in a later round rather than blame it for someone else's change.
                        out.write(f"merge_regen: {item['branch']} conflicts with an earlier branch in this batch ({files}); deferred\n")
                        _requeue(queue, item, f"deferred: conflicted with {', '.join(m['branch'] for m in members)} in {batch_id}")
                    else:
                        item.update(reason=f"merge conflict with main: {files}", finished=_now())
                        _move(queue, item, BOUNCED)
                    continue
                subprocess.run(["git", "commit", "-q", "-m", _message(item)], cwd=wt, stdout=out, stderr=subprocess.STDOUT, env=env)
                item["merged_as"] = _git(wt, "rev-parse", "HEAD")
                members.append(item)
            if not members:
                return "green"
            names = "\n".join(f"- {m['branch']} ({m['id']})" for m in members)
            out.write(f"merge_regen: one regeneration and one guard for {len(members)} branch(es)\n")
            out.flush()
            code = subprocess.run(
                [sys.executable, str(wt / "tools/merge_regen.py"), "--regen-since", main_sha,
                 "-m", f"Regenerate generated artifacts after landing {len(members)} branch(es)\n\n{names}"],
                cwd=wt, stdout=out, stderr=subprocess.STDOUT, env=env,
            ).returncode
        if code != 0:
            failures = _collect_failures(wt, round_started, log)
            _reset(wt, main_sha)
            if len(members) == 1:
                item = members[0]
                found = f" (found by bisecting a red batch of {bisecting})" if bisecting else ""
                item.update(reason=(failures or _first_error(log)) + found, finished=_now())
                _move(queue, item, BOUNCED)
                return "bounced"
            for item in members:
                _requeue(queue, item, f"in red {batch_id} of {len(members)}; bisecting")
            return "red"
        new = _git(wt, "rev-parse", "HEAD")
        why = _fast_forward_main(primary, main_sha, new)
        if why is None:
            for item in members:
                item.update(landed_as=item["merged_as"], finished=_now())
            if any(item.get("push") for item in members):
                with log.open("a") as out:
                    pushed = _push_main(primary, new, out)
                for item in members:
                    if item.get("push"):
                        item.update(pushed_as=new) if pushed is None else item.update(push_error=pushed)
            for item in members:
                _move(queue, item, LANDED)
            return "green"
        if why != "main moved":
            if len(members) > 1:
                # Usually one branch touches a file someone has dirty in the primary: narrow it down.
                for item in members:
                    _requeue(queue, item, f"{batch_id}: {why}")
                return "red"
            members[0].update(reason=why, finished=_now())
            _move(queue, members[0], BOUNCED)
            return "bounced"
        with log.open("a") as out:
            out.write("main moved during the landing; starting again on the new main\n")
        live = members
    for item in live:
        _requeue(queue, item, f"main moved under the lander {MAX_MAIN_RACES} times")
    return "green"


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
        limit, bisecting, suspects = args.batch, None, set()
        while True:
            pending = _items(queue, PENDING)
            if pending:
                batch = pending[:limit]
                wt = _lander_worktree(primary, args.worktree)  # re-created if something removed it
                print(f"land: {_now()} landing {len(batch)}: " + ", ".join(i["branch"] for i in batch), flush=True)
                result = _land_batch(batch, primary, wt, queue, bisecting)
                if result == "red":
                    # Halve and retry from the oldest. Each round builds on the current main, so a pair
                    # that only breaks together is caught too: the earlier lands, the later fails on it.
                    if not suspects:
                        bisecting, suspects = len(batch), {item["id"] for item in batch}
                    limit = max(1, len(batch) // 2)
                else:
                    # Keep halving through the rest of the red batch; back to full batches once every
                    # branch that was in it has landed or bounced (or the red was a flake).
                    suspects -= {item["id"] for item in batch if (_find(queue, item["id"]) or (PENDING,))[0] != PENDING}
                    if not suspects:
                        limit, bisecting = args.batch, None
                for item in batch:
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
    p.add_argument("--push", action="store_true", help="push main to origin once this lands (only if it lands)")
    p.add_argument("--by", help="who is asking (default: $FIELD_LAND_BY, else user @ worktree)")
    p.set_defaults(func=cmd_enqueue)
    p = sub.add_parser("status", help="show the queue, or one item (exit 0 landed, 1 bounced, 3 pending)")
    p.add_argument("id", nargs="?")
    p.add_argument("--recent", type=int, default=5)
    p.add_argument("--plain", action="store_true", help="the plain text agents see, even on a terminal")
    p.add_argument("--watch", action="store_true", help="on a terminal, redraw every 2 seconds; q, Esc or ctrl-c quits")
    p.set_defaults(func=cmd_status)
    p = sub.add_parser("sync", help="fetch origin/main and land it through the queue (--push: push main after)")
    p.add_argument("--push", action="store_true", help="push main to origin once it has landed")
    p.add_argument("--by", help="who is asking (default: $FIELD_LAND_BY, else user @ worktree)")
    p.set_defaults(func=cmd_sync)
    p = sub.add_parser("mine", help="every landing attempt for this branch, newest first (exit 1 if it bounced)")
    p.add_argument("branch", nargs="?", help="default: the current branch")
    p.set_defaults(func=cmd_mine)
    p = sub.add_parser("wait", help="block until an item lands (exit 0) or bounces (exit 1)")
    p.add_argument("id")
    p.set_defaults(func=cmd_wait)
    p = sub.add_parser("run", help="the lander: drain the queue onto main, one item at a time")
    p.add_argument("--once", action="store_true", help="drain the queue and exit instead of polling")
    p.add_argument("--batch", type=int, default=int(os.environ.get("FIELD_LAND_BATCH", "8")),
                   help="most branches landed together, sharing one regeneration and one guard "
                        "(default $FIELD_LAND_BATCH or 8; 1 lands them one at a time)")
    p.add_argument("--worktree", help="the lander's own worktree (default: <primary>-worktrees/lander)")
    p.set_defaults(func=cmd_run)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
