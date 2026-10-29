#!/usr/bin/env python3
"""The landing queue: agents enqueue a branch and stop; one lander puts it on `main`.

    uv run python tools/land.py precheck [--plan]                 # first: the guards this diff can trip
    uv run python tools/land.py enqueue [branch] [--note "..."]   # agents: this, then stop
    uv run python tools/land.py status [id] [--watch]             # what is queued, landed, bounced
    uv run python tools/land.py wait <id>                         # block until it lands or bounces
    uv run python tools/land.py mine [branch]                     # the mailbox: this branch's attempts
    uv run python tools/land.py sync [--push]                     # bring origin/main in via the queue
    uv run python tools/land.py doc                               # build the local docs when its turn arrives
    uv run python tools/land.py huh [--context]                   # ask the agent what the queue is doing
    uv run python tools/land.py bouncy [--hours 12] [--context]   # ask the agent about stranded bounces
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
5. On a red guard it first asks the fixer (below). Otherwise, or if the fixer declines, it bounces
   the item: the reason and the log path go in `status`, and `main` is untouched. Fix the branch,
   commit, and enqueue again.

The fixer: at a red round, the agent (`claude -p`, or `codex exec` with FIELD_AGENT=codex; see
tools/field_agent.py) runs in the lander's worktree on the exact tree that failed,
once per item, for up to FIELD_LAND_FIXER_MIN (15) minutes. It decides whether the failure is a
small mechanical slip one branch made (a missed count, a citation to something that moved, a
misplaced KDoc). If so it edits; the lander checks the edit (at most 6 files and 60 lines, no
deletions, no edits to the accepted-red registers, the guards, the hooks, the build or this script,
no disabled tests), commits it on the culprit's tip as `fixup/<branch>` without moving the branch
itself, and runs the whole round again with the full guard. If not, its diagnosis goes into the
bounce. Every attempt is in `field-land-queue/fixer.jsonl`. FIELD_LAND_FIXER=off turns it off.

`doc` is a queue barrier: preceding landings finish before it builds `public/field4/` in the
checkout holding `main`; later landings wait until the build finishes. It reuses committed captures
and reports its own failure without undoing a landing. It refuses to build while that checkout has
uncommitted changes under src/ or public/docs, because the site would mix them with pages generated
from HEAD. The running lander must be restarted after
changes to this script are landed, because Python does not reload it while running.

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
import signal
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import field_agent  # noqa: E402  (which agent to ask: claude or codex)

PENDING, LANDED, BOUNCED = "pending", "landed", "bounced"
DOC = "doc"
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


def _is_doc(item: dict) -> bool:
    return item.get("kind") == DOC


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
    replaced: dict | None = None
    for item in _items(queue, PENDING):
        # An item the lander has started stays: removing its file mid-landing hid it from status
        # while it landed anyway. The new commit queues behind it instead.
        if item.get("branch") == branch and not item.get("started"):
            # A re-enqueue replaces the older request: the newest commit is the one meant. It keeps
            # the older one's place in line, and says whose it was, since two agents can enqueue
            # the same branch and one would otherwise silently undo the other.
            (queue / PENDING / f"{item['id']}.json").unlink(missing_ok=True)
            replaced = item
            print(f"land: replacing queued {item['id']} ({item['sha'][:9]}, enqueued by {item['by']}), keeping its place")
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    replaced_seq = replaced.get("seq") if replaced else None
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
        "seq": replaced_seq or time.time_ns(),
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


def cmd_doc(args: argparse.Namespace) -> int:
    """Queue one HTML build in the checkout whose `main` Field normally serves."""
    here = Path.cwd()
    primary = _primary(here)
    holder = _main_holder(primary)
    if holder is None:
        print("land: doc needs a checkout with main checked out", file=sys.stderr)
        return 2
    queue = _queue_dir(here)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    item = {
        "id": f"{stamp}-doc",
        "kind": DOC,
        "checkout": str(holder.resolve()),
        "by": os.environ.get("FIELD_LAND_BY") or f"{_git(here, 'config', 'user.name', check=False) or 'unknown'} @ {here.name}",
        "enqueued": _now(),
        "seq": time.time_ns(),
    }
    base, n = item["id"], 1
    while _find(queue, item["id"]):
        n += 1
        item["id"] = f"{base}-{n}"
    _move(queue, item, PENDING)
    print(f"land: queued docs build {item['id']} for {holder}; {len(_items(queue, PENDING)) - 1} ahead of it.")
    if dirty := _dirty_doc_inputs(holder):
        print(f"land: warning: it will be refused unless this changes by its turn: {_dirty_doc_reason(holder, dirty)}", file=sys.stderr)
    print(f"Progress: uv run python tools/land.py status {item['id']}")
    print(f"Outcome: uv run python tools/land.py wait {item['id']}")
    return 0


_SUBJECTS: dict[str, str] = {}


def _subject(item: dict) -> str:
    """The first line of the branch tip's commit message: a branch name like
    claude/distracted-mccarthy-deeb34 says nothing about what is landing."""
    if _is_doc(item):
        return "Build the local documentation site"
    sha = item.get("fixup", {}).get("from", item["sha"])  # the branch's own subject, not the fixup's
    if sha not in _SUBJECTS:
        result = subprocess.run(["git", "log", "-1", "--format=%s", sha], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True)
        _SUBJECTS[sha] = result.stdout.strip()
    return _SUBJECTS[sha]


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
    if _is_doc(item):
        return False
    return any(line.startswith("> Task :") for line in _log_lines(item))


def _phase(item: dict) -> tuple[str, str]:
    """(what the landing is doing, the last thing it printed), read from its log."""
    lines = _log_lines(item)
    if _is_doc(item):
        return "building local docs", (lines[-1][:400] if lines else "")
    if not lines:
        return "starting", ""
    # The fixer prints nothing while it works (its reply goes to the log at the end), so the line
    # announcing it is the newest one until it answers.
    asked = [n for n, line in enumerate(lines) if line.startswith("land: ") and "asking the fixer" in line]
    if asked and not any(line.startswith("land: fixer: ") for line in lines[asked[-1]:]):
        return "red; " + lines[asked[-1]].split("red; ", 1)[-1], lines[asked[-1]][:400]
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
    return step, lines[-1][:400]


def _typical(queue: Path, gradle: bool) -> float | None:
    """Median duration of recent landings of the same kind (with or without a Gradle guard)."""
    took = [t for item in _items(queue, LANDED)[-20:] if not _is_doc(item) and _ran_gradle(item) == gradle and (t := _took(item)) is not None]
    return sorted(took)[len(took) // 2] if took else None


def _superseded_by(queue: Path, item: dict) -> dict | None:
    """A later landing of the same branch, which makes this bounce history rather than news."""
    # Doc builds (`land.py doc`) are queue items with no branch; they never supersede anything.
    if _is_doc(item) or not item.get("branch"):
        return None
    # A later attempt: enqueued later (seq is nanoseconds; finished times are per-second, so a fix
    # landing in the bounce's own second was missed), falling back to time for items without seq.
    def later_than(other: dict) -> bool:
        if other.get("seq") and item.get("seq"):
            return other["seq"] > item["seq"]
        return other.get("finished", "") > item.get("finished", "")
    later = [other for other in _items(queue, LANDED)
             if other.get("branch") == item["branch"] and other["id"] != item["id"] and later_than(other)]
    return later[0] if later else None


def _content_on_main(item: dict) -> bool:
    """The bounced commit's changes are already on `main`: merging it now would change nothing.
    True when it was merged into a branch that landed, and also when its changes were copied onto
    one with new SHAs (cherry-picked, or a subset of a bigger branch). Such a bounce needs nobody."""
    sha = item.get("sha")
    if not sha:
        return False
    repo = _primary(Path.cwd())
    if _ok(repo, "merge-base", "--is-ancestor", sha, "refs/heads/main"):
        return True
    if not _ok(repo, "cat-file", "-e", f"{sha}^{{commit}}"):
        return False
    merged = subprocess.run(["git", "merge-tree", "--write-tree", "refs/heads/main", sha], cwd=repo,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    return merged.returncode == 0 and merged.stdout.split("\n", 1)[0] == _git(repo, "rev-parse", "refs/heads/main^{tree}")


def _branch_gone(item: dict) -> bool:
    """Its branch has been deleted, so nobody is going to fix and re-enqueue it: abandoned on purpose."""
    branch = item.get("branch")
    return bool(branch) and not _ok(_primary(Path.cwd()), "show-ref", "--verify", "--quiet", f"refs/heads/{branch}")


def _settled(item: dict) -> str | None:
    """Why a bounce with no later landing of its own branch still needs nobody, or None if it does."""
    if _content_on_main(item):
        return "its changes reached main another way"
    if _branch_gone(item):
        return "its branch was deleted"
    return None


def _describe(state: str, item: dict, queue: Path | None = None) -> str:
    if _is_doc(item):
        head = f"{item['id']}  docs for {item['checkout']}  by {item['by']}"
        lines = [f"{'building' if state == PENDING and item.get('started') else 'queued' if state == PENDING else 'built' if state == LANDED else 'failed':8} {head}"]
        if state == PENDING and item.get("started"):
            lines.append(f"         building for {_span(_since(item['started']))}; log: {item.get('log', '')}")
        elif state == LANDED:
            lines.append(f"         main {item.get('built_from', '')[:9]}, {_span(_since(item.get('finished')))} ago")
        elif state == BOUNCED:
            lines.append(f"         {_span(_since(item.get('finished')))} ago: {item.get('reason', '?')}")
            lines.append(f"         log: {item.get('log', '')}")
        return "\n".join(lines)
    head = f"{item['id']}  {item['sha'][:9]}  by {item['by']}"
    lines = [f"{state:8} {head}"]
    if _subject(item):
        lines.append(f"         \u201c{_subject(item)}\u201d")
    if item.get("note"):
        lines.append(f"         note: {item['note']}")
    if state == PENDING and item.get("fixup"):
        lines.append(f"         lander fixup {item['fixup']['sha'][:9]} on {item['fixup']['branch']}: {item['fixup'].get('reason', '')}")
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
        if item.get("blocked"):
            b = item["blocked"]
            lines.append(f"         BLOCKED {_span(_since(b.get('since')))}: uncommitted edits in {b.get('where')} to "
                         + ", ".join(b.get("files", [])) + " (lands as soon as they are committed)")
    elif state == LANDED:
        took = _took(item)
        how = "Gradle guard" if _ran_gradle(item) else "no Gradle"
        lines.append(
            f"         -> main {item.get('landed_as', '')[:9]}, {_span(_since(item.get('finished')))} ago"
            + (f", took {_span(took)} ({how})" if took is not None else "")
            + (f"; {item['reason']}" if item.get("reason") else "")
        )
        if item.get("fixup"):
            f = item["fixup"]
            lines.append(f"         with a lander fixup {f['sha'][:9]} on {f['branch']} ({f.get('reason', '')}); "
                         f"your branch lacks it: git merge {f['branch']}")
        if item.get("pushed_as"):
            lines.append(f"         pushed main {item['pushed_as'][:9]} to origin")
        elif item.get("push_error"):
            lines.append(f"         NOT pushed: {item['push_error']}")
        elif item.get("push"):
            lines.append("         push requested")
    elif state == BOUNCED:
        fixed = _superseded_by(queue, item) if queue is not None else None
        if fixed:
            lines[0] = f"resolved {head}"
            lines.append(f"         bounced {_span(_since(item.get('finished')))} ago, then landed as {fixed['id']} "
                         f"-> main {fixed.get('landed_as', '')[:9]}")
            return "\n".join(lines)
        if why := _settled(item):
            lines[0] = f"resolved {head}"
            lines.append(f"         bounced {_span(_since(item.get('finished')))} ago; {why}")
            return "\n".join(lines)
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


# ---------------------------------------------------------------------------- huh?


def _huh_context(queue: Path) -> str:
    """Everything a person would look at when the queue seems stuck, as one plain-text bundle."""
    here = Path.cwd()
    primary = _primary(here)
    out: list[str] = []

    def section(title: str, body: str) -> None:
        out.append(f"===== {title} =====\n{body.rstrip()}\n")

    def run(*cmd: str, cwd: Path = primary) -> str:
        try:
            r = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=20)
            return r.stdout
        except (OSError, subprocess.TimeoutExpired) as e:
            return f"({' '.join(cmd)} failed: {e})"

    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        cmd_status(argparse.Namespace(id=None, recent=8, plain=True, watch=False))
    section("land.py status --plain", buf.getvalue())
    try:
        section("lander.json", (queue / "lander.json").read_text())
    except OSError:
        section("lander.json", "(missing)")
    active = [i for i in _items(queue, PENDING) if i.get("started")]
    if active and active[0].get("log"):
        try:
            lines = Path(active[0]["log"]).read_text(errors="replace").splitlines()
            section(f"tail of the current round's log ({len(lines)} lines total)", "\n".join(lines[-150:]))
            log_age = time.time() - Path(active[0]["log"]).stat().st_mtime
            section("current log last written", f"{int(log_age)} s ago")
        except OSError:
            pass
    for item in _items(queue, BOUNCED)[-3:]:
        if item.get("log"):
            failures = Path(item["log"]).with_suffix(".failures.txt")
            if failures.exists():
                section(f"failures of bounced {item['id']}", failures.read_text()[:3000])
    section("load", run("uptime"))
    section("lander, gradle and kotlin processes",
            "\n".join(l for l in run("ps", "-eo", "pid,ppid,etime,pcpu,rss,command").splitlines()
                      if any(k in l for k in ("land.py", "merge_regen", "GradleDaemon", "GradleWrapperMain", "KotlinCompileDaemon", "gradlew")))[:6000])
    holder = _main_holder(primary)
    if holder:
        section(f"uncommitted files in main's checkout ({holder})", run("git", "status", "--porcelain", cwd=holder)[:3000])
    section("main", run("git", "log", "-5", "--format=%h %cr %s", "refs/heads/main"))
    section("verification locks", run("sh", "-c", "ls -la .field-backdoor/verification.lock* 2>/dev/null; "
                                       f"ls -la {_lander_worktree_path(primary)}/.field-backdoor/verification.lock* 2>/dev/null"))
    return "\n".join(out)


def _lander_worktree_path(primary: Path) -> Path:
    try:
        return Path(json.loads((_queue_dir(primary) / "lander.json").read_text())["worktree"])
    except (OSError, KeyError, json.JSONDecodeError):
        return primary.parent / f"{primary.name}-worktrees" / "lander"


HUH_PROMPT = """You are looking at Field4's landing queue (tools/land.py): agents enqueue branches, and one lander
merges them in batches, regenerates generated files, runs one guard (Gradle), and fast-forwards main.
The person asking pressed "huh?" because something looks wrong or slow. From the context below:
1. Say in one or two sentences what the queue is doing right now.
2. Say whether it is stuck or merely slow, and the evidence (log age, CPU of the Gradle processes,
   load, a lock, a blocked item, a dead lander).
3. Give the one or two concrete things to do next, as commands where possible. Do not suggest
   merging into main by hand: only the lander may move main.
Be brief and specific. Plain text, no markdown headers.

"""


def _ask_huh(queue: Path) -> str:
    return _ask_agent(HUH_PROMPT, _huh_context(queue), timeout=240)


def _ask_agent(prompt: str, context: str, timeout: int = 300) -> str:
    """Ask the configured agent (field_agent: claude -p or codex exec) about [context], read-only."""
    agent = field_agent.configured_agent()
    argv = field_agent.headless_argv(agent)
    if argv is None:
        return f"`{agent}` is not installed, so there is nothing to ask. The context it would have read:\n\n" + context
    try:
        # stdout is the answer; codex narrates its progress on stderr, kept only for when there is no answer.
        result = subprocess.run(argv, input=prompt + context, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, timeout=timeout)
        return result.stdout.strip() or result.stderr.strip()[-2000:] or f"({agent} returned nothing)"
    except subprocess.TimeoutExpired:
        return f"{field_agent.describe(agent)} took more than {timeout // 60} minutes; giving up."


# ---------------------------------------------------------------------------- bouncy?


def _stranded_bounces(queue: Path, hours: float) -> list[dict]:
    """Bounces in the last [hours] that nothing has followed up: no later landing of the branch and
    no attempt of it still queued. The ones somebody has to act on."""
    cutoff = time.time() - hours * 3600
    queued = {i.get("branch") for i in _items(queue, PENDING)}
    out = []
    for item in _items(queue, BOUNCED):
        when = _when(item.get("finished"))
        if _is_doc(item) or not when or when.timestamp() < cutoff:
            continue
        if _superseded_by(queue, item) or item.get("branch") in queued:
            continue
        # Landed some other way, or its branch was deleted: not stranded.
        if _settled(item):
            continue
        out.append(item)
    # One per branch: the newest attempt is the one that matters.
    newest: dict[str, dict] = {}
    for item in out:
        newest[item["branch"]] = item
    return list(newest.values())


def _bouncy_context(queue: Path, items: list[dict]) -> str:
    primary = _primary(Path.cwd())
    worktrees = {}
    current = None
    for line in _git(primary, "worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            current = line.split(" ", 1)[1]
        elif line.startswith("branch refs/heads/"):
            worktrees[line[len("branch refs/heads/"):]] = current
    out = []
    for item in items:
        branch = item["branch"]
        tip = _git(primary, "rev-parse", "-q", "--verify", f"refs/heads/{branch}", check=False)
        moved = ""
        if tip and tip != item["sha"]:
            moved = _git(primary, "log", "--format=%h %cr %s", f"{item['sha']}..{tip}", check=False) or "(rewritten)"
        on_main = _ok(primary, "merge-base", "--is-ancestor", item["sha"], "refs/heads/main")
        out.append(f"===== bounce {item['id']} =====")
        out.append(f"branch: {branch}\nbounced commit: {item['sha']}  \u201c{_subject(item)}\u201d")
        out.append(f"enqueued by: {item.get('by')}\nbounced: {item.get('finished')} ({_span(_since(item.get('finished')))} ago)")
        out.append(f"history: {item.get('history', [])}")
        out.append(f"reason: {' '.join(str(item.get('reason', '?')).split())}")
        out.append(f"branch tip now: {tip or '(branch deleted)'}; worktree: {worktrees.get(branch, '(none)')}")
        out.append(f"bounced commit already on main: {on_main}")
        out.append("commits on the branch since the bounce (fixed but never re-enqueued?):\n" + (moved or "(none)"))
        log = Path(item.get("log", ""))
        failures = log.with_suffix(".failures.txt") if item.get("log") else None
        if failures and failures.exists():
            out.append("failure messages:\n" + failures.read_text()[:4000])
        if log.is_file():
            lines = log.read_text(errors="replace").splitlines()
            out.append("log tail:\n" + "\n".join(lines[-60:]))
        out.append("")
    return "\n".join(out)


BOUNCY_PROMPT = """You are looking at Field4's landing queue (tools/land.py). Agents enqueue branches; a lander merges
them onto main, regenerates generated files, runs a guard, and bounces a branch that fails, with a
reason. The bounces below happened recently and nothing followed them up: the branch has not landed
since and is not queued again. For EACH bounce, briefly:
1. Whose fault: the branch's own change, or the lander/tooling/environment (a false alarm, a killed
   build, a dirty checkout, a hang)? Give the evidence.
2. What has happened since: fixed on the branch but never re-enqueued, untouched, or already on main.
3. What to do: if it was not the branch's fault, or it is already fixed, the exact
   `uv run python tools/land.py enqueue <branch>` to run. Otherwise the concrete fix.
4. A short message (3-5 sentences) Marc can paste to the agent that owns the branch.
Be brief and specific. Plain text, no markdown headers. Never suggest merging into main by hand.

"""


def _ask_bouncy(queue: Path, hours: float) -> str:
    items = _stranded_bounces(queue, hours)
    if not items:
        return f"Nothing bounced in the last {hours:g} h is left stranded: every bounce since then has landed or is queued again."
    head = f"{len(items)} stranded bounce(s) in the last {hours:g} h: " + ", ".join(i["branch"] for i in items) + "\n\n"
    return head + _ask_agent(BOUNCY_PROMPT, _bouncy_context(queue, items))


def cmd_bouncy(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    items = _stranded_bounces(queue, args.hours)
    if args.context:
        print(_bouncy_context(queue, items) if items else "(no stranded bounces)")
        return 0
    print(_ask_bouncy(queue, args.hours))
    return 1 if items else 0


def cmd_huh(args: argparse.Namespace) -> int:
    queue = _queue_dir(Path.cwd())
    if args.context:
        print(_huh_context(queue))
        return 0
    print(_ask_huh(queue))
    return 0


def _pager(text: str, stdin: int) -> None:
    """A small pager on the alternate screen: j/k or arrows to scroll, space for a page, q back."""
    import textwrap
    cols, rows = shutil.get_terminal_size((100, 24))
    lines = [w for para in text.splitlines() for w in (textwrap.wrap(para, cols - 2) or [""])]
    top = 0
    while True:
        view = lines[top: top + rows - 2]
        sys.stdout.write("\033[H" + "\033[K\n".join(view) + "\033[K\n\033[J")
        sys.stdout.write(_Style().dim(f"huh? \u00b7 {top + 1}-{min(top + rows - 2, len(lines))} of {len(lines)} "
                                      "\u00b7 j/k scroll \u00b7 space page \u00b7 q back"))
        sys.stdout.flush()
        key = os.read(stdin, 3)
        if key in (b"q", b"Q", b"\x1b", b"h"):
            return
        if key in (b"j", b"\x1b[B"):
            top = min(top + 1, max(len(lines) - (rows - 2), 0))
        elif key in (b"k", b"\x1b[A"):
            top = max(top - 1, 0)
        elif key == b" ":
            top = min(top + rows - 3, max(len(lines) - (rows - 2), 0))


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
            "push": "\uf062" if nerd else "^", "pull": "\uf063" if nerd else "v",
            "fix": "\uf0ad" if nerd else "%",
        }
        if nerd:
            # Nerd Font icons draw wider than the one cell the terminal gives them; pad so they don't touch the text.
            self.g = {k: v + " " for k, v in self.g.items()}

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
    cols = shutil.get_terminal_size((100, 20)).columns
    width = min(cols, 110)  # rules only; text lines use the whole terminal
    rule = lambda title: print(st.dim("\u2500\u2500 ") + st.bold(title) + " " + st.dim("\u2500" * max(width - len(title) - 4, 0)))

    def clip(text: str, indent: int) -> str:
        """Fit `text` after `indent` columns of the terminal, marking a cut with an ellipsis."""
        room = max(cols - indent - 1, 20)
        return text if len(text) <= room else text[: room - 1] + "\u2026"

    def who(item: dict) -> str:
        if _is_doc(item):
            return f"{st.magenta('docs')}  {st.dim(item['checkout'])}  {st.dim(g['user'] + ' ' + item['by'])}"
        line = f"{st.magenta(g['branch'] + ' ' + item['branch'])}  {st.dim(item['sha'][:9])}  {st.dim(g['user'] + ' ' + item['by'])}"
        subject = _subject(item)
        # The tip's commit subject, on its own line: the branch name rarely says what is landing.
        return line + (f"\n      {clip(subject, 6)}" if subject else "")

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

    def fixed_line(item: dict) -> str:
        f = item["fixup"]
        head = f"{g['fix']} fixer {f['sha'][:9]} on {f['branch']}: "
        return st.yellow(head) + clip(f.get("reason", ""), 6 + len(head))

    pending = _items(queue, PENDING)
    active = [i for i in pending if i.get("started")]
    waiting = [i for i in pending if not i.get("started")]

    rule("landing")
    if not active:
        print(st.dim("  idle"))
    # One progress block per batch: its branches share one merge, one regeneration and one guard.
    batches: dict[str, list[dict]] = {}
    for item in active:
        batches.setdefault(item.get("batch", item["id"]), []).append(item)
    for members in batches.values():
        item = members[0]
        running = _since(item["started"])
        step, last = _phase(item)
        gradle = _ran_gradle(item)
        typical = _typical(queue, gradle)
        if len(members) > 1:
            print(f"  {st.dim('batch of ' + str(len(members)) + ', one guard for all:')}")
        for member in members:
            print(f"  {st.yellow(g['land'])} {who(member)}")
            if member.get("note"):
                print(f"    {st.dim(g['note'] + ' ' + clip(member['note'], 6))}")
            if member.get("fixup"):
                print(f"    {fixed_line(member)}")
        if typical:
            frac = running / typical
            tail = f"~{_span(typical - running)} left" if running < typical else st.yellow(f"{_span(running - typical)} over typical")
            print(f"    {st.cyan(_bar(min(frac, 1.0)))}  {st.bold(_span(running))} {st.dim('/ ~' + _span(typical))}  {tail}")
        else:
            print(f"    {st.bold(_span(running))} {st.dim('elapsed, no typical duration yet')}")
        print(f"    {st.dim('step')}  {clip(step, 10)}")
        if last:
            print(f"    {st.dim('last')}  {st.dim(clip(last, 10))}")
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
        if item.get("fixup"):
            print(f"     {fixed_line(item)}")
        if item.get("blocked"):
            b = item["blocked"]
            print(f"     {st.yellow(g['bounce'] + ' blocked ' + _span(_since(b.get('since'))))} "
                  + st.yellow("by uncommitted edits in " + str(b.get("where")) + ": " + ", ".join(b.get("files", []))))
        ahead += typical
    print()

    finished = sorted(
        [(LANDED, i) for i in _items(queue, LANDED)] + [(BOUNCED, i) for i in _items(queue, BOUNCED)]
        + [("push", e) for e in _events(queue, "push")],
        key=lambda pair: pair[1].get("finished", ""),
    )[-recent:]
    rule("recent")
    if not finished:
        print(st.dim("  nothing yet"))
    for state, item in reversed(finished):
        ago = _span(_since(item.get("finished")))
        if state == "push":
            commits = item.get("commits", "?")
            what = f"main {item.get('sha', '')[:9]} \u2192 origin  ({commits} commit{'s' if commits != '1' else ''})"
            if item.get("error"):
                print(f"  {st.red(g['push'] + ' push failed')}  {what}  {st.dim(ago + ' ago')}")
                print(f"    {st.red(clip(item['error'], 4))}")
            else:
                print(f"  {st.cyan(g['push'] + ' pushed')}  {what}  {st.dim(ago + ' ago')}")
            continue
        took = _took(item)
        if _is_doc(item):
            label = st.green(g['ok'] + ' built') if state == LANDED else st.red(g['bounce'] + ' docs failed')
            print(f"  {label}  {who(item)}  {st.dim(ago + ' ago')}")
            if state == BOUNCED:
                print(f"    {st.red(clip(str(item.get('reason', '?')), 4))}")
            continue
        if state == LANDED and _is_pull(item):
            origin_note = "from origin \u00b7 " + g["main"] + " " + item.get("landed_as", "")[:9]
            print(f"  {st.cyan(g['pull'] + ' pulled')}  {who(item)}  {st.dim(origin_note)}"
                  f"  {st.dim(ago + ' ago')}" + (st.dim(f" \u00b7 took {_span(took)}") if took is not None else ""))
            continue
        if state == LANDED:
            how = (g["gradle"] + " guard") if _ran_gradle(item) else "no gradle"
            print(f"  {st.green(g['ok'])} {who(item)}  {st.dim(g['main'] + ' ' + item.get('landed_as', '')[:9])}"
                  f"  {st.dim(ago + ' ago')}" + (st.dim(f" \u00b7 took {_span(took)} \u00b7 {how}") if took is not None else ""))
            if item.get("fixup"):
                print(f"      {fixed_line(item)}")
        elif _superseded_by(queue, item) or _settled(item):
            continue  # bounced, then landed (or landed another way, or abandoned): history, not news
        else:
            reason = " ".join(str(item.get("reason", "?")).split())
            print(f"  {st.red(g['bounce'])} {who(item)}  {st.dim(ago + ' ago')}")
            print(f"    {st.red(clip(reason, 4))}")


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
                footer = _Style().dim(f"{_now()} \u00b7 every 2s \u00b7 h huh? \u00b7 b bouncy? \u00b7 q to quit")
                sys.stdout.write("\033[H" + "\033[K\n".join(lines) + "\033[K\n" + footer + "\033[K\033[J")
                sys.stdout.flush()
                if stdin is None:
                    time.sleep(2)
                elif select.select([stdin], [], [], 2)[0]:
                    key = os.read(stdin, 1)
                    if key in (b"q", b"Q", b"\x1b", b"\x03"):
                        break
                    if key in (b"b", b"B"):
                        sys.stdout.write("\033[2J\033[H" + _Style().yellow("bouncy? looking for bounces nobody followed up (then the agent; ~30-60 s)") + "\n")
                        sys.stdout.flush()
                        _pager(_ask_bouncy(queue, 12), stdin)
                        sys.stdout.write("\033[2J")
                    if key in (b"h", b"H"):
                        sys.stdout.write("\033[2J\033[H" + _Style().yellow("huh? asking the agent about the queue (gathering context, then the agent; ~30-60 s)") + "\n")
                        sys.stdout.flush()
                        _pager(_ask_huh(queue), stdin)
                        sys.stdout.write("\033[2J")
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
            detail = f"docs for {item['checkout']}" if _is_doc(item) else item['sha'][:9]
            print(f"  {n}. {item['id']}  {detail}  by {item['by']}  waiting {_span(_since(item.get('enqueued')))}{estimate}")
            if item.get("blocked"):
                b = item["blocked"]
                print(f"     BLOCKED {_span(_since(b.get('since')))} by uncommitted edits in {b.get('where')}: " + ", ".join(b.get("files", [])))
            ahead += typical
    else:
        print("\nqueued   nothing")
    pushes = _events(queue, "push")[-args.recent:]
    if pushes:
        print("\nrecent pushes (oldest first):")
        for e in pushes:
            line = f"push     main {e.get('sha', '')[:9]} -> origin, {e.get('commits', '?')} commit(s), {_span(_since(e.get('finished')))} ago"
            print(line + (f"\n         FAILED: {e['error']}" if e.get("error") else ""))
    for state in (BOUNCED, LANDED):
        recent = _items(queue, state)[-args.recent:]
        if recent:
            print(f"\nrecent {state} (oldest first):")
            for item in recent:
                print(_describe(state, item, queue))
    return 0


def cmd_mine(args: argparse.Namespace) -> int:
    """The mailbox: every attempt to land this branch, newest first. Exit 1 if the newest bounced."""
    here = Path.cwd()
    branch = args.branch or _git(here, "branch", "--show-current")
    queue = _queue_dir(here)
    attempts = sorted(
        ((state, item) for state in (PENDING, LANDED, BOUNCED) for item in _items(queue, state) if item.get("branch") == branch),
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
    # Most specific first: the docs builder's own verdict, a failing test, a compiler error, Gradle's
    # own account, and only then merge_regen's summary line, which names no cause.
    for pattern in (r"^docs build failed: ", r" > .* FAILED$", r"^e: ", r"source conflicts", r"What went wrong", r"FAILED",
                    r"merge_regen: .*fail", r"[Ee]rror"):
        for i, line in enumerate(text):
            if re.search(pattern, line):
                return " | ".join(" ".join(l.split()) for l in text[i : i + 3] if l.strip())[:400]
    return (text[-1] if text else "no output")[:400]


def _message(item: dict) -> str:
    message = f"Land {item['branch']} ({item['by']})\n\nLanding queue item {item['id']}, commit {item['sha']}."
    if item.get("note"):
        message += f"\n\n{item['note']}"
    if item.get("fixup"):
        f = item["fixup"]
        message += (f"\n\nThe guard failed on {f['from'][:12]}; the lander's fixer added {f['sha'][:12]} "
                    f"({f['branch']}): {f.get('reason', '')}")
    return message


def _requeue(queue: Path, item: dict, why: str) -> None:
    """Back to waiting, as if never started: a later round takes it again."""
    for key in ("started", "batch", "batch_size", "merged_as", "log"):
        item.pop(key, None)
    item.setdefault("history", []).append(f"{_now()} {why}")
    _move(queue, item, PENDING)


def _main_holder(primary: Path) -> Path | None:
    """The worktree with main checked out, if any (usually the primary)."""
    current = None
    for line in _git(primary, "worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            current = Path(line.split(" ", 1)[1])
        elif line == "branch refs/heads/main":
            return current
    return None


def _blocking_edits(primary: Path, item: dict) -> list[str]:
    """Uncommitted files in main's checkout that this branch changes: a fast-forward would refuse
    to overwrite them. That is nobody's fault in the branch, so it waits rather than bounces."""
    holder = _main_holder(primary)
    if holder is None:
        return []
    # Raw output: _git strips, and stripping eats the leading space of " M path", the first line's
    # status column, which turned "seed.txt" into "eed.txt".
    status = subprocess.run(["git", "status", "--porcelain", "-uall"], cwd=holder,
                            stdout=subprocess.PIPE, text=True).stdout
    dirty = {line[3:].split(" -> ")[-1].strip('"') for line in status.splitlines() if len(line) > 3}
    if not dirty:
        return []
    changed = set(_git(primary, "diff", "--name-only", f"refs/heads/main...{item['sha']}", check=False).split())
    return sorted(dirty & changed)


# The lander's own Gradle user home. Sharing ~/.gradle with every other session's daemons made the
# lander's build wait on their cross-process cache locks: at load 90 one round spent an hour in
# configuration, handing the file-hash cache lock back and forth with another daemon. Its own home
# means its own daemons and caches; seeded as an APFS copy-on-write clone (cp -c) of ~/.gradle, so
# it starts warm and costs almost no disk. FIELD_LAND_GRADLE_HOME=shared opts out.
WATCHDOG_MINUTES = float(os.environ.get("FIELD_LAND_WATCHDOG_MIN", "30"))


def _lander_gradle_home() -> Path | None:
    setting = os.environ.get("FIELD_LAND_GRADLE_HOME", "")
    if setting == "shared":
        return None
    home = Path(setting) if setting else Path.home() / ".field4" / "lander-gradle-home"
    if (home / ".seeded").exists():
        return home
    source = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    home.mkdir(parents=True, exist_ok=True)
    print(f"land: seeding the lander's Gradle home {home} from {source} (copy-on-write)", flush=True)
    for entry in sorted(source.iterdir()) if source.is_dir() else []:
        # Daemon state and profiling output are per-process; the rest is what makes a build warm.
        if entry.name in ("daemon", "kotlin-profile", "build-scan-data") or (home / entry.name).exists():
            continue
        for cmd in (["cp", "-cR", str(entry), str(home / entry.name)],              # macOS APFS clone
                    ["cp", "-a", "--reflink=auto", str(entry), str(home / entry.name)]):  # Linux
            if subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                break
    # Locks and the file-hash databases are the shared-state part: start them fresh.
    for stale in list(home.glob("**/*.lock")) + list(home.glob("caches/*/fileHashes")):
        if stale.is_dir():
            shutil.rmtree(stale, ignore_errors=True)
        else:
            stale.unlink(missing_ok=True)
    (home / ".seeded").write_text(f"seeded {_now()} from {source}\n")
    return home


# What keeps the machine awake while a build runs: macOS's `caffeinate`. The lander runs on a laptop,
# and asleep a build makes no progress. FIELD_LAND_CAFFEINATE names another command, or "off".
CAFFEINATE = os.environ.get("FIELD_LAND_CAFFEINATE", "caffeinate" if sys.platform == "darwin" else "off")


def _hold_awake(pid: int) -> subprocess.Popen | None:
    """Keeps the machine from idle sleep until [pid] exits (`caffeinate -i -w <pid>`), or None where
    there is no such command. Closing a laptop's lid still sleeps it; see _run_watched for why that
    no longer bounces the build."""
    exe = shutil.which(CAFFEINATE) if CAFFEINATE and CAFFEINATE != "off" else None
    if not exe:
        return None
    try:
        return subprocess.Popen([exe, "-i", "-w", str(pid)], stdin=subprocess.DEVNULL,
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        return None


_CURRENT_BUILD: dict[str, int] = {}


def _group_alive(pgid: int) -> bool:
    try:
        os.killpg(pgid, 0)
        return True
    except (ProcessLookupError, PermissionError):
        return False


def _stop_group(pgid: int) -> None:
    """TERM the build's process group, then KILL whatever is left after 10 s."""
    with contextlib.suppress(OSError):
        os.killpg(pgid, 15)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline and _group_alive(pgid):
        time.sleep(0.2)
    with contextlib.suppress(OSError):
        os.killpg(pgid, 9)


def _stop_orphaned_build(queue: Path) -> None:
    """A lander that died without cleaning up (kill -9, a crash) leaves its build running under the
    next one: two builds in one worktree, which corrupts Kotlin output. Stop it before landing."""
    record = queue / "build.json"
    try:
        info = json.loads(record.read_text())
    except (OSError, json.JSONDecodeError):
        return
    pgid = int(info.get("pgid", 0))
    if pgid and info.get("lander") != os.getpid() and _group_alive(pgid):
        leader = subprocess.run(["ps", "-o", "command=", "-p", str(pgid)], stdout=subprocess.PIPE, text=True).stdout
        if "merge_regen" in leader or not leader.strip():  # only ever the lander's own build
            print(f"land: stopping an orphaned build from a previous lander (process group {pgid})", flush=True)
            _stop_group(pgid)
    record.unlink(missing_ok=True)


def _run_watched(cmd: list[str], cwd: Path, env: dict, log: Path, out) -> int | None:
    """Run [cmd] with its output in [log], holding the machine awake while it runs. None if it went
    silent for WATCHDOG_MINUTES: a build that writes nothing for that long is wedged (the hour in
    configuration), and waiting longer only holds up everyone behind it. Its whole process group and
    the lander's Gradle daemons are stopped.

    Silence is timed on the monotonic clock, which on macOS stops while the machine sleeps, so a
    build that a closed lid paused is not taken for a wedged one. Timing it from the log's mtime
    with the wall clock bounced a whole batch of seven on 2026-10-07."""
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=out, stderr=subprocess.STDOUT, env=env, start_new_session=True)
    awake = _hold_awake(proc.pid)
    # Its own session, so the watchdog can stop the whole build: which also means stopping the lander
    # no longer stops it. Record it, so a SIGTERM'd lander stops it and a new lander finds an orphan.
    record = log.parent.parent / "build.json"
    pending = record.with_suffix(".json.tmp")
    pending.write_text(json.dumps({"pgid": proc.pid, "lander": os.getpid(), "started": _now(), "log": str(log)}) + "\n")
    pending.replace(record)
    _CURRENT_BUILD["pgid"] = proc.pid
    try:
        return _watch(proc, cwd, env, log, out)
    finally:
        _CURRENT_BUILD.pop("pgid", None)
        record.unlink(missing_ok=True)
        if awake is not None and awake.poll() is None:
            awake.terminate()
            with contextlib.suppress(subprocess.TimeoutExpired):
                awake.wait(timeout=5)


def _watch(proc: subprocess.Popen, cwd: Path, env: dict, log: Path, out) -> int | None:
    last_write = log.stat().st_mtime
    quiet_since = time.monotonic()
    while True:
        try:
            return proc.wait(timeout=min(30.0, max(WATCHDOG_MINUTES * 60 / 4, 0.5)))
        except subprocess.TimeoutExpired:
            pass
        written = log.stat().st_mtime
        if written != last_write:
            last_write = written
            quiet_since = time.monotonic()
            continue
        silent = time.monotonic() - quiet_since
        if silent < WATCHDOG_MINUTES * 60:
            continue
        out.write(f"\nland: watchdog: no output for {silent / 60:.0f} min; stopping the build\n")
        out.flush()
        try:
            os.killpg(proc.pid, 15)
            proc.wait(timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            try:
                os.killpg(proc.pid, 9)
            except OSError:
                pass
        # The daemon outlives its client; stop the ones in the lander's own Gradle home.
        if env.get("GRADLE_USER_HOME"):
            subprocess.run([str(cwd / "gradlew"), "--stop"], cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT)
        return None


def _push_main(primary: Path, sha: str, out) -> str | None:
    """Push main to origin. None on success, else why not. Never forces, never touches local main."""
    out.write(f"merge_regen: pushing main {sha[:9]} to origin\n")
    out.flush()
    count = _git(primary, "rev-list", "--count", f"refs/remotes/origin/main..{sha}", check=False) or "?"
    result = subprocess.run(["git", "push", "origin", f"{sha}:refs/heads/main"], cwd=primary,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out.write(result.stdout)
    why = None
    if result.returncode != 0:
        if "rejected" in result.stdout or "fetch first" in result.stdout or "non-fast-forward" in result.stdout:
            why = "origin/main has commits main lacks; run `uv run python tools/land.py sync --push`"
        else:
            why = " ".join(result.stdout.split())[-300:] or f"git push exited {result.returncode}"
    _record_event(_queue_dir(primary), "push", sha=sha, commits=count, error=why)
    return why


def _record_event(queue: Path, kind: str, **fields) -> None:
    """Pushes (and anything else that is not a queue item) for status's `recent`, one JSON per line."""
    with (queue / "events.jsonl").open("a") as f:
        f.write(json.dumps({"kind": kind, "finished": _now(), **fields}) + "\n")


def _events(queue: Path, kind: str) -> list[dict]:
    try:
        lines = (queue / "events.jsonl").read_text().splitlines()
    except OSError:
        return []
    out = []
    for line in lines:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("kind") == kind:
            out.append(event)
    return out


def _is_pull(item: dict) -> bool:
    """A sync item: origin's main, landed through the queue."""
    return not _is_doc(item) and item["branch"].startswith("sync/origin")


def _collect_failures(wt: Path, since: float, log: Path) -> str | None:
    """Gradle prints a failing test as "AssertionError at Foo.kt:769"; the message itself is only in
    the JUnit XML inside the lander's worktree, which the next round overwrites. Copy every failure
    written during this round next to the log, and return a one-line summary of the first.
    `checkPythonTools` writes the same XML (build/test-results/pythonTools), message = exception
    line, body = traceback; both are kept."""
    import xml.etree.ElementTree as ET
    found: list[tuple[str, str, str]] = []
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
                message = (failure.get("message") or "").strip()
                body = (failure.text or "").strip()
                found.append((name, message or body, body if body != message else ""))
    if not found:
        return None
    path = log.with_suffix(".failures.txt")
    path.write_text("".join(f"## {name}\n{message}\n" + (f"{body}\n" if body else "") + "\n"
                            for name, message, body in found))
    first_name, first_message, _ = found[0]
    first_line = next((line.strip() for line in first_message.splitlines() if line.strip()), "")
    more = f" (+{len(found) - 1} more)" if len(found) > 1 else ""
    return f"{first_name}: {first_line[:220]}{more}; full messages: {path}"


# ---------------------------------------------------------------------------- the fixer


# Most bounces are a missed number, a citation pointing at a symbol that moved, a KDoc in the wrong
# place: a minute's fix, followed by a re-enqueue and (in a batch) an hour of bisecting. At a red
# round the lander gives the agent (field_agent.py) one chance, in its own worktree on the exact tree that failed,
# to make the fix and say whose branch it belongs to. A fix that passes the checks below is committed
# onto that branch's tip as `fixup/<branch>` (the branch itself is never moved: it is usually checked
# out in somebody's worktree) and the whole batch goes round again with the full guard. The guard,
# not the fixer, decides whether it lands. FIELD_LAND_FIXER=off turns it off.
FIXER = os.environ.get("FIELD_LAND_FIXER", "on")
FIXER_MINUTES = float(os.environ.get("FIELD_LAND_FIXER_MIN", "15"))
FIXUP_MAX_FILES = 6
FIXUP_MAX_LINES = 60
# Making the guard pass by changing the guard is not a fix: the registers of accepted reds, the guards
# themselves, the landing machinery, and the hooks are refused outright.
FIXUP_DENIED_PATHS = re.compile(
    r"^(dev_docs/testing/expected_\w+\.json|tools/(land|merge_regen|install_git_hooks)\.py|tools/check_[^/]+\.py"
    r"|\.githooks/|githooks/|build\.gradle\.kts|settings\.gradle\.kts|gradle/)")
# ...and so is switching a test off.
FIXUP_DENIED_ADDED = re.compile(r"@Ignore|@Disabled|pytest\.mark\.skip|unittest\.skip|\bxit\(|\bxdescribe\(")
FIXER_VERDICT = "FIXER-VERDICT:"

FIXER_PROMPT = """You are the landing queue's fixer for the Field repository. The lander merged the
branches below onto main and the guard failed. You are in the lander's own worktree, on the exact
merged tree that failed; build output is warm, and nothing else is building here. AGENTS.md in this
directory is the repo guide.

Your job is a judgement call: is this failure a small, mechanical slip that one of these branches
obviously made (a count or number not updated, a citation pointing at something that moved or was
renamed, a KDoc in the wrong place, a missing entry in a list a guard checks, a typo in a reference)?

- If yes: fix it with the smallest edit that is plainly right, then re-run the narrowest command that
  reproduces the failing check (one test class, one checker script, one Gradle task) and confirm it
  passes. Do not run the whole guard; the lander does that afterwards.
- If the failure is about behaviour, timing, flakiness, design, or anything you would have to explain
  with "probably", or it needs more than a few lines, do not edit anything; say why. Your diagnosis
  goes into the bounce and is useful on its own.

Hard rules (the lander rejects a fix that breaks them):
- Do not run git commands that change anything: no commit, stash, checkout, reset, merge, or branch.
  The lander commits your edit itself.
- Do not edit dev_docs/testing/expected_*.json, tools/check_*.py, tools/land.py, tools/merge_regen.py,
  the git hooks, or the Gradle build files; do not delete files; do not disable or skip a test.
- At most {max_files} files and {max_lines} changed lines.
- The fix must belong to ONE branch below: the one whose change the failure follows from.

End your reply with exactly one line, and nothing after it:
{verdict} {{"verdict": "fix" or "decline", "culprit": "<branch name>" or null, "reason": "<one sentence>"}}

"""


def _fixer_command() -> list[str] | None:
    custom = os.environ.get("FIELD_LAND_FIXER_CMD")
    if custom:
        import shlex
        return shlex.split(custom)
    return field_agent.headless_argv(edits=True)


def _fixer_context(members: list[dict], main_sha: str, wt: Path, log: Path, failures: str | None, queue: Path) -> str:
    parts = [f"main before this round: {main_sha}\n\nBranches in the round, each merged as its own commit:"]
    for m in members:
        stat = _git(wt, "diff", "--stat=120", f"{main_sha}...{m['sha']}", check=False)
        parts.append(f"\n## {m['branch']} ({m['sha'][:9]}, by {m['by']})\n{_subject(m) or ''}\n{stat}")
    if failures:
        parts.append(f"\nFailing tests (first): {failures}")
        path = log.with_suffix(".failures.txt")
        if path.exists():
            parts.append(path.read_text(errors="replace")[:6000])
    tail = log.read_text(errors="replace").splitlines()[-200:]
    parts.append("\nThe end of the landing log:\n" + "\n".join(tail))
    ledger = _fixer_ledger(queue)[-8:]
    if ledger:
        parts.append("\nWhat the fixer did on recent reds (for calibration):\n"
                     + "\n".join(f"- {e.get('outcome')}: {e.get('reason', '')}" for e in ledger))
    return "\n".join(parts)


def _fixer_ledger(queue: Path) -> list[dict]:
    try:
        lines = (queue / "fixer.jsonl").read_text().splitlines()
    except OSError:
        return []
    out = []
    for line in lines:
        with contextlib.suppress(json.JSONDecodeError):
            out.append(json.loads(line))
    return out


def _record_fixer(queue: Path, out, **fields) -> None:
    fields = {"finished": _now(), **fields}
    with (queue / "fixer.jsonl").open("a") as f:
        f.write(json.dumps(fields) + "\n")
    out.write(f"land: fixer: {fields.get('outcome')}: {fields.get('reason', '')}\n")
    out.flush()


def _run_fixer(cmd: list[str], prompt: str, wt: Path, env: dict, out) -> str | None:
    """The fixer's whole reply, or None if it overran FIXER_MINUTES (its process group is stopped)."""
    proc = subprocess.Popen(cmd, cwd=wt, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, start_new_session=True)
    awake = _hold_awake(proc.pid)
    _CURRENT_BUILD["pgid"] = proc.pid
    try:
        reply, _ = proc.communicate(prompt, timeout=FIXER_MINUTES * 60)
        return reply
    except subprocess.TimeoutExpired:
        _stop_group(proc.pid)
        proc.communicate()
        return None
    finally:
        _CURRENT_BUILD.pop("pgid", None)
        if awake is not None and awake.poll() is None:
            awake.terminate()
        out.flush()


def _fixer_verdict(reply: str) -> dict | None:
    for line in reversed(reply.splitlines()):
        if line.strip().startswith(FIXER_VERDICT):
            with contextlib.suppress(json.JSONDecodeError):
                verdict = json.loads(line.strip()[len(FIXER_VERDICT):])
                return verdict if isinstance(verdict, dict) else None
    return None


def _fixup_problem(wt: Path, base: str) -> str | None:
    """Why the staged edit is not acceptable as a fixup, or None."""
    status = _git(wt, "diff", "--cached", "--name-status", base, check=False).splitlines()
    if not status:
        return "it changed nothing"
    paths = [line.split("\t")[-1] for line in status]
    if any(line.startswith("D") for line in status):
        return "it deletes a file"
    denied = [p for p in paths if FIXUP_DENIED_PATHS.search(p)]
    if denied:
        return f"it edits {', '.join(denied)}, which a fixup may not touch"
    if len(paths) > FIXUP_MAX_FILES:
        return f"it touches {len(paths)} files (at most {FIXUP_MAX_FILES})"
    lines = 0
    for row in _git(wt, "diff", "--cached", "--numstat", base, check=False).splitlines():
        added, removed = row.split("\t")[:2]
        lines += int(added) if added.isdigit() else FIXUP_MAX_LINES
        lines += int(removed) if removed.isdigit() else 0
    if lines > FIXUP_MAX_LINES:
        return f"it changes {lines} lines (at most {FIXUP_MAX_LINES})"
    diff = _git(wt, "diff", "--cached", "-U0", base, check=False)
    if any(FIXUP_DENIED_ADDED.search(l) for l in diff.splitlines() if l.startswith("+") and not l.startswith("+++")):
        return "it disables or skips a test"
    return None


def _try_fixer(members: list[dict], main_sha: str, wt: Path, queue: Path, log: Path, failures: str | None,
               env: dict, out) -> tuple[dict, str] | None:
    """Give the fixer one go at the red tree in [wt]. On an accepted fix returns (culprit item, the
    fixup commit on the culprit's tip, also at refs/heads/fixup/<branch>); otherwise None, with the
    fixer's reason in the log and the ledger. The caller resets [wt] either way."""
    # One attempt per item: the bisect rounds after a decline would only ask the same question again.
    eligible = [m for m in members if not _is_pull(m) and not m.get("fixer_tried")]
    cmd = _fixer_command() if FIXER != "off" else None
    if not eligible or cmd is None:
        return None
    for m in eligible:
        m["fixer_tried"] = True
    # The failed tree, regeneration output included, as a scratch commit on the lander's own branch:
    # whatever the fixer changes is then exactly the diff against it. Plumbing, so no hook runs.
    _git(wt, "add", "-A")
    base = _git(wt, "commit-tree", _git(wt, "write-tree"), "-p", "HEAD", "-m", "lander: the tree that failed")
    _git(wt, "update-ref", "HEAD", base)
    names = ", ".join(m["branch"] for m in eligible)
    out.write(f"\nland: {_now()} red; asking the fixer (up to {FIXER_MINUTES:.0f} min) about {names}\n")
    out.flush()
    prompt = FIXER_PROMPT.format(max_files=FIXUP_MAX_FILES, max_lines=FIXUP_MAX_LINES, verdict=FIXER_VERDICT)
    reply = _run_fixer(cmd, prompt + _fixer_context(eligible, main_sha, wt, log, failures, queue), wt, env, out)
    if reply is None:
        _record_fixer(queue, out, outcome="timeout", reason=f"no verdict within {FIXER_MINUTES:.0f} min", branches=names)
        return None
    out.write("land: fixer reply:\n" + "\n".join("  | " + l for l in reply.splitlines()[-40:]) + "\n")
    verdict = _fixer_verdict(reply) or {}
    reason = " ".join(str(verdict.get("reason") or "no verdict line").split())[:300]
    culprit = next((m for m in eligible if m["branch"] == verdict.get("culprit")), None)
    for m in eligible:
        m["fixer_note"] = reason
    if verdict.get("verdict") != "fix":
        _record_fixer(queue, out, outcome="declined", reason=reason, branches=names)
        return None
    if _git(wt, "rev-parse", "HEAD") != base:
        _record_fixer(queue, out, outcome="rejected", reason=f"it moved HEAD itself ({reason})", branches=names)
        return None
    if culprit is None:
        _record_fixer(queue, out, outcome="rejected", reason=f"culprit {verdict.get('culprit')!r} is not in the round ({reason})", branches=names)
        return None
    _git(wt, "add", "-A")
    problem = _fixup_problem(wt, base)
    if problem:
        _record_fixer(queue, out, outcome="rejected", reason=f"{problem} ({reason})", branch=culprit["branch"])
        return None
    files = _git(wt, "diff", "--cached", "--name-only", base).split()
    fix = _git(wt, "commit-tree", _git(wt, "write-tree"), "-p", base, "-m", "lander fixer edit")
    # The same edit on the culprit's own tip: a three-way merge in the object store, nothing checked out.
    merged = subprocess.run(["git", "merge-tree", "--write-tree", f"--merge-base={base}", culprit["sha"], fix],
                            cwd=wt, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if merged.returncode != 0:
        _record_fixer(queue, out, outcome="rejected", branch=culprit["branch"],
                      reason=f"the fix does not apply to {culprit['branch']} on its own, so it is not that branch's alone ({reason})")
        return None
    message = (f"Lander fixup: {reason}\n\nMade by the landing queue's fixer ({field_agent.describe()}) when {log.stem} went red;\n"
               f"the full guard ran again on it before landing.\n\n"
               f"Lander-Fixup: {culprit['id']} {culprit['sha'][:12]}\n")
    fixer_env = {**env, "GIT_AUTHOR_NAME": "Field lander fixer", "GIT_AUTHOR_EMAIL": "lander-fixer@field.invalid"}
    tip = subprocess.run(["git", "commit-tree", merged.stdout.split()[0], "-p", culprit["sha"], "-m", message],
                         cwd=wt, env=fixer_env, check=True, stdout=subprocess.PIPE, text=True).stdout.strip()
    _git(wt, "update-ref", f"refs/heads/fixup/{culprit['branch']}", tip)
    _record_fixer(queue, out, outcome="fixed", reason=reason, branch=culprit["branch"], item=culprit["id"],
                  files=files, sha=tip)
    return culprit, tip


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
    gradle_home = _lander_gradle_home()
    if gradle_home:
        env["GRADLE_USER_HOME"] = str(gradle_home)

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
            code = _run_watched(
                [sys.executable, str(wt / "tools/merge_regen.py"), "--regen-since", main_sha,
                 "-m", f"Regenerate generated artifacts after landing {len(members)} branch(es)\n\n{names}"],
                wt, env, log, out,
            )
        if code is None:
            # Wedged, which says nothing about the branches. Retry the round once; a second
            # wedge bounces them, so a build that always hangs cannot hold the queue forever.
            _reset(wt, main_sha)
            for item in members:
                item["watchdog"] = item.get("watchdog", 0) + 1
                if item["watchdog"] >= 2:
                    item.update(reason=f"the landing build went silent for {WATCHDOG_MINUTES:.0f} min twice "
                                       f"(watchdog); see the log", finished=_now())
                    _move(queue, item, BOUNCED)
                else:
                    _requeue(queue, item, f"{batch_id}: watchdog stopped a build silent for {WATCHDOG_MINUTES:.0f} min; retrying")
            return "blocked"
        if code != 0:
            failures = _collect_failures(wt, round_started, log)
            with log.open("a") as out:
                fixed = _try_fixer(members, main_sha, wt, queue, log, failures, env, out)
            _reset(wt, main_sha)
            if fixed:
                culprit, tip = fixed
                culprit["fixup"] = {"sha": tip, "branch": f"fixup/{culprit['branch']}", "from": culprit["sha"],
                                    "reason": culprit.get("fixer_note", "")}
                culprit["sha"] = tip
                for item in members:
                    item.pop("fixer_note", None)
                    _requeue(queue, item, f"red {batch_id}; the fixer committed {tip[:9]} on fixup/{culprit['branch']}; "
                                          f"the round goes again with the full guard")
                return "fixed"
            if len(members) == 1:
                item = members[0]
                found = f" (found by bisecting a red batch of {bisecting})" if bisecting else ""
                note = f"; fixer: {item.pop('fixer_note')}" if item.get("fixer_note") else ""
                item.update(reason=(failures or _first_error(log)) + found + note, finished=_now())
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
            # Someone made an edit in main's checkout during the round. The branches are fine; they
            # wait (the next poll marks exactly which are blocked, and by which files). A refusal the
            # pre-check cannot explain must not loop forever, so the third one bounces.
            for item in members:
                item["ff_refusals"] = item.get("ff_refusals", 0) + 1
                if item["ff_refusals"] >= 3:
                    item.update(reason=why, finished=_now())
                    _move(queue, item, BOUNCED)
                else:
                    _requeue(queue, item, f"{batch_id}: {why}")
            return "blocked"
        with log.open("a") as out:
            out.write("main moved during the landing; starting again on the new main\n")
        live = members
    for item in live:
        _requeue(queue, item, f"main moved under the lander {MAX_MAIN_RACES} times")
    return "green"


# What the docs build reads from the working tree rather than from a commit: the source mirror
# (`generateSourcePages`) walks every `.kt` under src/, tracked or not, and the site is built from
# public/docs. The committed reference pages cite source lines as of HEAD, so a build over uncommitted
# edits installs source pages whose line anchors no longer match those citations, and the link check
# fails on links that are fine on main (20261022-111619-doc: 116 `#L` anchors, all into files edited
# but not committed).
DOC_BUILD_INPUTS = ("src", "public/docs")


def _dirty_doc_inputs(checkout: Path) -> list[str]:
    """Paths under DOC_BUILD_INPUTS that differ from HEAD: modified, staged, or untracked and not ignored."""
    changed = _git(checkout, "diff", "--name-only", "HEAD", "--", *DOC_BUILD_INPUTS, check=False).splitlines()
    untracked = _git(checkout, "ls-files", "--others", "--exclude-standard", "--", *DOC_BUILD_INPUTS, check=False).splitlines()
    return sorted({p for p in changed + untracked if p})


def _dirty_doc_reason(checkout: Path, dirty: list[str]) -> str:
    shown = ", ".join(dirty[:5]) + (f" and {len(dirty) - 5} more" if len(dirty) > 5 else "")
    return (f"{checkout} has {len(dirty)} uncommitted change(s) under {' or '.join(DOC_BUILD_INPUTS)} that the docs "
            f"build would read ({shown}); commit or set them aside, then queue `land.py doc` again")


def _run_doc(item: dict, primary: Path, queue: Path) -> None:
    """Build the served checkout after earlier queue items, without changing Git history."""
    checkout = Path(item["checkout"])
    log = queue / "logs" / f"{item['id']}.log"
    item.update(started=_now(), log=str(log))
    _move(queue, item, PENDING)
    holder = _main_holder(primary)
    if holder is None or holder.resolve() != checkout.resolve():
        item.update(reason="the checkout holding main changed; queue a new doc item for the current checkout", finished=_now())
        _move(queue, item, BOUNCED)
        return
    script = checkout / "tools" / "build_zensical_docs.py"
    if not script.is_file():
        item.update(reason=f"docs builder missing in {checkout}", finished=_now())
        _move(queue, item, BOUNCED)
        return
    # Refuse rather than build a site that is neither main nor the working tree. Checked here, at the
    # item's turn, because the tree it would build is the one on disk now, not at enqueue time.
    if dirty := _dirty_doc_inputs(checkout):
        item.update(reason=_dirty_doc_reason(checkout, dirty), finished=_now())
        _move(queue, item, BOUNCED)
        return
    item["built_from"] = _git(checkout, "rev-parse", "HEAD")
    _move(queue, item, PENDING)
    env = {**os.environ, "FIELD_DOCS_RUNNER_MODE": "never", "PYTHONUNBUFFERED": "1"}
    with log.open("w") as out:
        out.write(f"land: building local docs in {checkout} from main {item['built_from'][:9]}\n")
        out.flush()
        # The builder stops a wedged zensical itself within minutes (FIELD_DOCS_ZENSICAL_STALL_SEC),
        # refuses a site missing pages, and only then replaces public/field4/; --gate-site-links makes
        # a failing link check fail the job too, so "built" means a site that was checked.
        code = _run_watched([sys.executable, str(script), "--gate-site-links"], checkout, env, log, out)
    if code == 0:
        item["finished"] = _now()
        _move(queue, item, LANDED)
    else:
        item.update(reason=(f"docs build went silent for {WATCHDOG_MINUTES:.0f} min" if code is None else _first_error(log)), finished=_now())
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
        _stop_orphaned_build(queue)

        def stop(signum, _frame):
            # launchd's kickstart -k and bootout send SIGTERM. Take the build down with us, or it
            # keeps running under the next lander.
            pgid = _CURRENT_BUILD.get("pgid")
            if pgid:
                print(f"land: signal {signum}; stopping the running build (process group {pgid})", flush=True)
                _stop_group(pgid)
                (queue / "build.json").unlink(missing_ok=True)
            raise SystemExit(128 + signum)

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        print(f"land: lander up in {wt}; queue {queue}", flush=True)
        limit, bisecting, suspects = args.batch, None, set()
        while True:
            pending = _items(queue, PENDING)
            # A docs item is an exact boundary. Later landings cannot pass it, and it cannot
            # pass an earlier landing that is waiting on edits in main's checkout.
            doc_index = next((n for n, item in enumerate(pending) if _is_doc(item)), None)
            before_doc = pending[:doc_index] if doc_index is not None else pending
            ready = []
            for item in before_doc:
                blocking = _blocking_edits(primary, item)
                if blocking:
                    if item.get("blocked", {}).get("files") != blocking:
                        item["blocked"] = {"files": blocking, "since": item.get("blocked", {}).get("since", _now()),
                                           "where": str(_main_holder(primary))}
                        _move(queue, item, PENDING)
                elif item.get("blocked"):
                    item.pop("blocked")
                    _move(queue, item, PENDING)
                    ready.append(item)
                else:
                    ready.append(item)
            if ready:
                batch = ready[:limit]
                wt = _lander_worktree(primary, args.worktree)  # re-created if something removed it
                print(f"land: {_now()} landing {len(batch)}: " + ", ".join(i["branch"] for i in batch), flush=True)
                result = _land_batch(batch, primary, wt, queue, bisecting)
                if result in ("blocked", "fixed"):
                    continue
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
            if doc_index == 0:
                item = pending[0]
                print(f"land: {_now()} building docs in {item['checkout']}", flush=True)
                _run_doc(item, primary, queue)
                state, done = _find(queue, item["id"]) or ("?", item)
                print(_describe(state, done, queue), flush=True)
                continue
            if args.once and not _items(queue, PENDING):
                return 0
            if args.once:
                print("land: everything left is blocked by uncommitted edits in main's checkout; exiting", flush=True)
                return 0
            time.sleep(POLL_SECONDS)
    finally:
        lock_file.close()


def cmd_precheck(argv: list[str]) -> int:
    # Imported here: it pulls in the guard selection, which the queue commands never need (and which a
    # copy of this script used on its own, as tools/test_docs_notes.py makes, does not have).
    import land_precheck  # noqa: PLC0415

    return land_precheck.main(argv)


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["precheck"]:
        return cmd_precheck(argv[1:])
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    # Dispatched above, before parsing; listed here so `land.py --help` names it.
    sub.add_parser("precheck", help="before enqueue: merge main, run only the guards this diff can trip (minutes)")
    p = sub.add_parser("enqueue", help="queue a branch for landing, then stop")
    p.add_argument("branch", nargs="?", help="default: the current branch")
    p.add_argument("--note", help="one line for the landing commit, e.g. what to look at if it bounces")
    p.add_argument("--push", action="store_true", help="push main to origin once this lands (only if it lands)")
    p.add_argument("--by", help="who is asking (default: $FIELD_LAND_BY, else user @ worktree)")
    p.set_defaults(func=cmd_enqueue)
    p = sub.add_parser("doc", help="queue a local docs build after preceding landings")
    p.set_defaults(func=cmd_doc)
    p = sub.add_parser("status", help="show the queue, or one item (exit 0 landed, 1 bounced, 3 pending)")
    p.add_argument("id", nargs="?")
    p.add_argument("--recent", type=int, default=5)
    p.add_argument("--plain", action="store_true", help="the plain text agents see, even on a terminal")
    p.add_argument("--watch", action="store_true", help="on a terminal, redraw every 2 seconds; q, Esc or ctrl-c quits")
    p.set_defaults(func=cmd_status)
    p = sub.add_parser("bouncy", help="ask the agent about recent bounces nobody followed up (what --watch's b key does)")
    p.add_argument("--hours", type=float, default=12.0, help="how far back to look (default 12)")
    p.add_argument("--context", action="store_true", help="print the context it would send, and do not ask")
    p.set_defaults(func=cmd_bouncy)
    p = sub.add_parser("huh", help="ask the agent what is going on with the queue (what --watch's h key does)")
    p.add_argument("--context", action="store_true", help="print the context it would send, and do not ask")
    p.set_defaults(func=cmd_huh)
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
