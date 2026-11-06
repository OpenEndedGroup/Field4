#!/usr/bin/env python3
"""Field's scheduled routines: list them, show how each last went, and adopt them into a scheduler.

A routine is a folder `routines/<name>/` holding a `ROUTINE.md`: a frontmatter block saying what it
is, when to run it and what it needs, then the instructions a run follows. Agent routines (the
nightly self-improvement loop, nightly CI, the weekly What's New) are followed by a coding agent;
command routines (the feel benchmark, the worktree reaper) are a script a scheduler can run with no
agent at all. The format is described in `routines/README.md`.

Field does not schedule anything itself. This tool renders a routine for the scheduler you already
have:

  prompt    a paste-able request for any agent with a scheduling tool (Claude Code's scheduled
            tasks, Codex, ...): "create a task at this time whose prompt is ..."
  launchd   a macOS LaunchAgent that runs `tools/routines.py run <name>` (`--apply` installs it)
  cron      the crontab line that does the same

`run <name>` is what launchd and cron call: a command routine runs its command, an agent routine is
handed to the configured agent (`tools/field_agent.py`) as a one-shot unattended session. Each run's
output is kept in `~/.afield/routines/<name>/`, which `status` reads alongside each routine's own
reports (journal branches, report files, commits on main).

Stdlib only, so the Field server can run it with any Python (`/routines.html`).

    python3 tools/routines.py list
    python3 tools/routines.py status [--json]
    python3 tools/routines.py install <name> --harness prompt|launchd|cron [--apply] [--json]
    python3 tools/routines.py run <name> [--dry-run]
    python3 tools/routines.py check
"""

from __future__ import annotations

import argparse
import datetime as dt
import glob
import json
import math
import os
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
from contextlib import contextmanager
from zoneinfo import ZoneInfo

from verification_resources import KeepAwake, lock_file
from dataclasses import dataclass, field
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
HERE = TOOLS.parent
RUN_LOGS = Path.home() / ".afield" / "routines"
LAUNCH_AGENTS = Path.home() / "Library" / "LaunchAgents"
CLAUDE_TASKS = Path.home() / ".claude" / "scheduled-tasks"
LABEL_PREFIX = "com.field4.routine."
NAME = re.compile(r"^[a-z][a-z0-9-]{1,40}$")
HARNESSES = ("prompt", "launchd", "cron")
KINDS = ("agent", "command")
REQUIRED = ("name", "title", "summary", "kind", "schedule", "runtime-hours", "needs", "cost", "reports")
KNOWN = set(REQUIRED) | {"command", "disk-gib", "quiet", "journal", "verdict", "status-command"}
RECENT = 7
LOG_DAYS = 30


# ---------------------------------------------------------------------------------------------
# Reading routines


@dataclass
class Routine:
    name: str
    folder: Path
    meta: dict[str, str]
    body: str
    errors: list[str] = field(default_factory=list)

    def get(self, key: str, default: str = "") -> str:
        return self.meta.get(key, default)

    @property
    def kind(self) -> str:
        return self.get("kind")

    @property
    def runtime_hours(self) -> float:
        try:
            return float(self.get("runtime-hours", "0"))
        except ValueError:
            return 0.0


def parse_routine(path: Path) -> Routine:
    """`---` frontmatter of flat `key: value` lines, then Markdown. Values are kept as strings;
    a regex value is taken literally, so nothing in it needs quoting."""
    text = path.read_text(encoding="utf-8")
    routine = Routine(path.parent.name, path.parent, {}, text)
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        routine.errors.append("no frontmatter (the file must begin with ---)")
        return routine
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        routine.errors.append("frontmatter is not closed with ---")
        return routine
    for number, line in enumerate(lines[1:end], start=2):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition(":")
        if not sep:
            routine.errors.append(f"line {number}: not `key: value`")
            continue
        routine.meta[key.strip()] = value.strip()
    routine.body = "\n".join(lines[end + 1:]).lstrip("\n")
    return routine


def discover(repo: Path = HERE) -> list[Routine]:
    return [parse_routine(p) for p in sorted((repo / "routines").glob("*/ROUTINE.md"))]


def find(name: str, repo: Path = HERE) -> Routine:
    if not NAME.match(name):
        raise SystemExit(f"not a routine name: {name!r}")
    path = repo / "routines" / name / "ROUTINE.md"
    if not path.is_file():
        known = ", ".join(r.name for r in discover(repo)) or "none"
        raise SystemExit(f"no routine {name!r} (known: {known})")
    return parse_routine(path)


# ---------------------------------------------------------------------------------------------
# Paths a run is told about


def primary_checkout(repo: Path) -> Path:
    """The main working tree of the repository *repo* belongs to: a scheduler should name the
    checkout `main` lives in, not whichever worktree this tool happened to be run from."""
    try:
        common = subprocess.run(["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir"],
                                capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return repo
    path = Path(common)
    return path.parent if common and path.name == ".git" else repo


def worktree_base(repo: Path) -> Path:
    configured = os.environ.get("FIELD_WORKTREE_BASE", "").strip()
    return Path(configured).expanduser() if configured else repo.parent / f"{repo.name}-worktrees"


def workspace(repo: Path) -> Path:
    """The workspace `./field.sh` would open: this checkout's record, then the machine-wide one,
    then the default (the same order as the launcher)."""
    configured = os.environ.get("FIELD_WORKSPACE", "").strip()
    if configured:
        return Path(configured).expanduser()
    for record in (repo / ".afield-local" / "last-workspace.txt", Path.home() / ".afield" / "last-workspace.txt"):
        try:
            value = record.read_text(encoding="utf-8").strip()
        except OSError:
            continue
        if value:
            return Path(value).expanduser()
    return Path.home() / "Documents" / "AFieldWorkspace"


def run_paths(repo: Path) -> dict[str, str]:
    repo = primary_checkout(repo)
    return {"REPO": str(repo), "WORKTREE_BASE": str(worktree_base(repo)), "WORKSPACE": str(workspace(repo))}


# ---------------------------------------------------------------------------------------------
# Schedules


def cron_fields(schedule: str) -> list[str] | None:
    parts = schedule.split()
    return parts if len(parts) == 5 else None


def _valid_cron_field(value: str, low: int, high: int) -> bool:
    for item in value.split(","):
        base, separator, step = item.partition("/")
        if separator and (not step.isdigit() or int(step) == 0):
            return False
        if base == "*":
            continue
        start, _, stop = base.partition("-")
        if stop and start.isdigit() and stop.isdigit() and int(start) > int(stop):
            return False
        for bound in (start, stop) if stop else (start,):
            if not bound.isdigit() or not low <= int(bound) <= high:
                return False
    return True


def cron_valid(schedule: str) -> bool:
    parts = cron_fields(schedule)
    limits = ((0, 59), (0, 23), (1, 31), (1, 12), (0, 7))
    return parts is not None and all(_valid_cron_field(v, lo, hi) for v, (lo, hi) in zip(parts, limits))


DAYS = ("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")


def describe_schedule(schedule: str) -> str:
    parts = cron_fields(schedule)
    if not parts:
        return schedule
    minute, hour, dom, month, dow = parts
    if minute.isdigit() and hour.isdigit() and dom == "*" and month == "*":
        at = f"{int(hour):02d}:{int(minute):02d}"
        if dow == "*":
            return f"every day at {at}"
        if dow.isdigit():
            return f"{DAYS[int(dow)]} at {at}"
    if minute.isdigit() and hour.startswith("*/") and dom == month == dow == "*":
        return f"every {hour[2:]} hours, at :{int(minute):02d}"
    return f"cron {schedule}"


def daily_start(schedule: str) -> int | None:
    """Minute of the day a daily (or weekly) schedule starts, or None for anything else."""
    parts = cron_fields(schedule)
    if parts and parts[0].isdigit() and parts[1].isdigit() and parts[2] == parts[3] == "*":
        return int(parts[1]) * 60 + int(parts[0])
    return None


def conflicts(routines: list[Routine]) -> list[str]:
    """A `quiet: yes` routine whose window overlaps another routine's: the benchmark would measure
    the other routine. Only daily and weekly schedules are compared (a weekly one as if daily)."""
    windows = []
    for r in routines:
        start = daily_start(r.get("schedule"))
        if start is not None and r.runtime_hours > 0:
            windows.append((r, start, start + int(r.runtime_hours * 60)))
    found = []
    for quiet, qs, qe in windows:
        if quiet.get("quiet").lower() not in ("yes", "true"):
            continue
        for other, os_, oe in windows:
            if other is quiet:
                continue
            for shift in (-1440, 0, 1440):
                if qs < oe + shift and os_ + shift < qe:
                    found.append(f"{quiet.name} needs a quiet machine but its window "
                                 f"({_hhmm(qs)}-{_hhmm(qe)}) overlaps {other.name} ({_hhmm(os_)}-{_hhmm(oe)})")
                    break
    return found


def _hhmm(minutes: int) -> str:
    minutes %= 1440
    return f"{minutes // 60:02d}:{minutes % 60:02d}"


# ---------------------------------------------------------------------------------------------
# What a scheduler is given


def stub_prompt(routine: Routine, paths: dict[str, str]) -> str:
    """The whole prompt a scheduler holds. Everything else lives in the repository, so changing a
    routine is a commit, not a re-scheduling."""
    return (
        f'Run the Field·4 routine "{routine.get("title")}". You are unattended: nobody is awake to answer '
        f"questions, so make the best call and record your reasoning.\n\n"
        f"$REPO = {paths['REPO']}\n$WORKTREE_BASE = {paths['WORKTREE_BASE']}\n$WORKSPACE = {paths['WORKSPACE']}\n\n"
        f"Read {paths['REPO']}/routines/{routine.name}/ROUTINE.md in full and follow it exactly, using the "
        f"paths above wherever it says $REPO, $WORKTREE_BASE or $WORKSPACE. If that file is missing or "
        f"unreadable, change nothing and reply with one line saying so."
    )


def python_for_schedulers() -> str:
    """A python3 that will still be there at 3 am: the system one where it exists, since the
    interpreter running this may be a uv or virtualenv build that is gone next week. The tool is
    stdlib-only, so any python3 will do."""
    import shutil  # noqa: PLC0415
    for candidate in ("/usr/bin/python3", shutil.which("python3"), sys.executable):
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK) and "/.cache/" not in candidate:
            return candidate
    return "python3"


def run_argv(routine: Routine, repo: Path) -> list[str]:
    return [python_for_schedulers(), str(repo / "tools" / "routines.py"), "run", routine.name]


def render_install(routine: Routine, harness: str, repo: Path = HERE) -> dict[str, str]:
    paths = run_paths(repo)
    checkout = Path(paths["REPO"])
    schedule = routine.get("schedule")
    when = describe_schedule(schedule)
    if harness == "prompt":
        stub = stub_prompt(routine, paths)
        text = (
            f'Please create a recurring scheduled task named "field4-{routine.name}" that runs {when} '
            f"(cron `{schedule}`, local time), titled \"{routine.get('title')}\". Its prompt is everything "
            f"between the lines below, verbatim. It works in {paths['REPO']} and must be allowed to edit "
            f"files and run commands there without asking.\n\n-----\n{stub}\n-----\n"
        )
        return {"harness": harness, "text": text, "prompt": stub, "schedule": schedule}
    argv = run_argv(routine, checkout)
    log = RUN_LOGS / routine.name / "scheduler.log"
    if harness == "cron":
        line = f"{schedule} cd {shlex.quote(str(checkout))} && {shlex.join(argv)} >> {shlex.quote(str(log))} 2>&1"
        text = (f"# {routine.get('title')}: {when}. Add this line with `crontab -e`:\n{line}\n"
                f"# cron runs it with a minimal PATH; `routines.py run` finds uv, claude and codex in the usual places.\n")
        return {"harness": harness, "text": text, "line": line, "schedule": schedule}
    if harness == "launchd":
        label = LABEL_PREFIX + routine.name
        plist = launchd_plist(routine, label, argv, checkout, log)
        path = LAUNCH_AGENTS / f"{label}.plist"
        body = plistlib.dumps(plist).decode()
        text = (f"<!-- {routine.get('title')}: {when}. Install with\n"
                f"     python3 tools/routines.py install {routine.name} --harness launchd --apply\n"
                f"     Remove with: launchctl bootout gui/$(id -u)/{label} && rm {path} -->\n{body}")
        return {"harness": harness, "text": text, "path": str(path), "label": label, "schedule": schedule}
    raise SystemExit(f"unknown harness {harness!r} (one of {', '.join(HARNESSES)})")


def launchd_plist(routine: Routine, label: str, argv: list[str], checkout: Path, log: Path) -> dict:
    plist: dict = {"Label": label, "ProgramArguments": argv, "WorkingDirectory": str(checkout),
                   "StandardOutPath": str(log), "StandardErrorPath": str(log), "ProcessType": "Background"}
    parts = cron_fields(routine.get("schedule")) or []
    minute, hour, _dom, _month, dow = parts if parts else ("0", "0", "*", "*", "*")
    if minute.isdigit() and hour.startswith("*/") and hour[2:].isdigit():
        plist["StartCalendarInterval"] = [{"Hour": h, "Minute": int(minute)} for h in range(0, 24, int(hour[2:]))]
    else:
        entry: dict = {}
        if minute.isdigit():
            entry["Minute"] = int(minute)
        if hour.isdigit():
            entry["Hour"] = int(hour)
        if dow.isdigit():
            entry["Weekday"] = int(dow) % 7
        plist["StartCalendarInterval"] = entry
    if routine.kind == "command":
        plist["LowPriorityIO"] = True
    return plist


def apply_launchd(rendered: dict[str, str], routine: Routine) -> str:
    path = Path(rendered["path"])
    (RUN_LOGS / routine.name).mkdir(parents=True, exist_ok=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    body = rendered["text"].split("-->\n", 1)[1]
    path.write_text(body, encoding="utf-8")
    domain = f"gui/{os.getuid()}"
    subprocess.run(["launchctl", "bootout", f"{domain}/{rendered['label']}"], capture_output=True)
    done = subprocess.run(["launchctl", "bootstrap", domain, str(path)], capture_output=True, text=True)
    if done.returncode != 0:
        raise SystemExit(f"wrote {path}, but launchctl bootstrap failed: {done.stderr.strip() or done.returncode}")
    return f"installed {path} ({describe_schedule(routine.get('schedule'))})"


# ---------------------------------------------------------------------------------------------
# Running


def local_clock() -> dt.datetime:
    """Use a real timezone where available so weekly/DST diagnostics retain their offset."""
    name = os.environ.get("FIELD_ROUTINE_TIMEZONE")
    if name:
        return dt.datetime.now(ZoneInfo(name))
    target = str(Path("/etc/localtime").resolve())
    for marker in ("zoneinfo/", "zoneinfo.default/"):
        if marker in target:
            return dt.datetime.now(ZoneInfo(target.split(marker, 1)[1]))
    return dt.datetime.now().astimezone()


def latest_slot(routine: Routine, now: dt.datetime) -> dt.datetime | None:
    """Daily, weekly and every-N-hours schedules; unsupported expressions stay explicit.

    Walk actual instants, so the spring gap has no slot and the autumn fold has
    two. A dated external report can only establish a run day, not an exact slot.
    """
    parts = cron_fields(routine.get("schedule"))
    if not parts or not cron_valid(routine.get("schedule")):
        return None
    minute, hour, dom, month, dow = parts
    if not minute.isdigit() or dom != "*" or month != "*" or not (dow == "*" or dow.isdigit()):
        return None
    if hour.isdigit():
        hours = {int(hour)}
    elif re.fullmatch(r"\*/[1-9][0-9]*", hour):
        hours = set(range(0, 24, int(hour[2:])))
    else:
        return None
    instant = now.astimezone(dt.timezone.utc).replace(second=0, microsecond=0)
    for offset in range(8 * 24 * 60):
        candidate = (instant - dt.timedelta(minutes=offset)).astimezone(now.tzinfo)
        weekday = (candidate.weekday() + 1) % 7
        if candidate.minute == int(minute) and candidate.hour in hours and (dow == "*" or weekday == int(dow) % 7):
            return candidate
    return None


def _running(name: str) -> bool:
    path = RUN_LOGS / name / "run.lock"
    if not path.exists():
        return False
    try:
        with path.open("r+b") as handle:
            try:
                lock_file(handle, blocking=False)
            except (BlockingIOError, OSError):
                return True
    except FileNotFoundError:
        pass
    return False


def schedule_health(routine: Routine, recent: list[dict], scheduled: bool, *, now=None,
                    check_running=True, journal_locks=()) -> dict:
    now = now or local_clock()
    slot = latest_slot(routine, now)
    result = {"state": "unknown", "checkedAt": now.isoformat(), "timezone": str(now.tzinfo),
              "expectedAt": slot.isoformat() if slot else None, "evidence": None,
              "scheduleSource": "routine definition; installed scheduler timing is unverified"}
    if not scheduled:
        result.update(state="unscheduled", message="No scheduler found on this machine")
        return result
    if slot is None:
        result.update(message="Schedule health is supported for daily, weekly and every-N-hours schedules")
        return result
    if not math.isfinite(routine.runtime_hours) or routine.runtime_hours <= 0:
        result.update(message="Routine runtime-hours must be finite and positive")
        return result
    deadline = (slot.astimezone(dt.timezone.utc) +
                dt.timedelta(hours=max(routine.runtime_hours, 1))).astimezone(now.tzinfo)
    result["deadlineAt"] = deadline.isoformat()
    if check_running and _running(routine.name):
        result.update(state="running", message="A routine runner holds the run lock")
        return result
    for lock in journal_locks:
        if lock.exists():
            result.update(state="unknown", message=f"Journal lock exists; an external run may be active: {lock}",
                          evidence=str(lock))
            return result
    try:
        state = json.loads((RUN_LOGS / routine.name / "last-run.json").read_text())
        started = dt.datetime.fromisoformat(state["startedAt"])
        if started.tzinfo is None:
            raise ValueError("run timestamp lacks timezone")
        if slot.timestamp() <= started.timestamp() <= now.timestamp():
            result["evidence"] = state.get("log")
            result.update(state=("recorded" if state.get("exit") == 0 else "failed") if "exit" in state else "interrupted",
                          message=(f"Runner exited {state['exit']}" if "exit" in state else
                                   "Runner disappeared without a completion record"))
            return result
    except (OSError, ValueError, KeyError, TypeError):
        pass
    # External agent schedulers bypass `run`. A dated report establishes an
    # attempt, including an explicit skip, but cannot prove the scheduler time.
    for report in recent:
        if slot.date().isoformat() <= report.get("date", "") <= now.date().isoformat():
            source = str(report.get("source", ""))
            if source.endswith(".log"):
                try:
                    text = Path(source).read_text(encoding="utf-8", errors="replace")
                    header = re.search(r"(?m)^# routine \S+ at (\S+) in ", text)
                    if not header:
                        continue
                    started = dt.datetime.fromisoformat(header.group(1))
                    if started.tzinfo is None:
                        started = started.replace(tzinfo=now.tzinfo)
                    if not slot.timestamp() <= started.timestamp() <= now.timestamp():
                        continue
                    exit_line = re.search(r"(?m)^# exit (-?\d+)\s*$", text)
                    if not exit_line:
                        result.update(state="unknown", message="An older runner log has no completion record",
                                      evidence=source)
                    else:
                        code = int(exit_line.group(1))
                        result.update(state="recorded" if code == 0 else "failed",
                                      message=f"Runner log records exit {code}", evidence=source)
                    return result
                except (OSError, ValueError):
                    continue
            if len((cron_fields(routine.get("schedule")) or ["", ""])[1].split("/")) > 1:
                result.update(state="unknown", message="Dated report cannot establish which hourly slot ran")
                return result
            result.update(state="recorded", message="Report found (day precision; timing unverified)",
                          evidence=report.get("source"))
            return result
    if routine.get("reports").startswith("log:"):
        result.update(state="unknown", message="Commit history cannot prove that a no-change run was missed")
        return result
    result.update(state="overdue" if now.timestamp() > deadline.timestamp() else "awaiting",
                  message="No run report found for the expected slot")
    return result


def journal_lock_paths(routine: Routine, repo: Path) -> list[Path]:
    branch = routine.get("journal")
    if not branch:
        return []
    journal = worktree_base(primary_checkout(repo)) / branch.rsplit("/", 1)[-1]
    return [journal / ".lock", journal / ".ci-lock"]


@contextmanager
def routine_lock(name: str):
    folder = RUN_LOGS / name
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / "run.lock").open("a+b") as handle:
        if handle.tell() == 0:
            handle.write(b" ")
            handle.flush()
        try:
            lock_file(handle, blocking=False)
        except (BlockingIOError, OSError) as exc:
            raise RuntimeError(f"{name}: another routine runner is active") from exc
        yield


def run(routine: Routine, repo: Path = HERE, dry_run: bool = False, catch_up: bool = False) -> int:
    paths = run_paths(repo)
    checkout = Path(paths["REPO"])
    env = dict(os.environ, FIELD_REPO=paths["REPO"], FIELD_WORKTREE_BASE=paths["WORKTREE_BASE"],
               FIELD_WORKSPACE=paths["WORKSPACE"])
    env["PATH"] = os.pathsep.join([env.get("PATH", "")] + [str(Path.home() / ".local" / "bin"), "/opt/homebrew/bin", "/usr/local/bin"])
    stdin = None
    if routine.kind == "command":
        argv = shlex.split(routine.get("command"))
    else:
        sys.path.insert(0, str(TOOLS))
        import field_agent  # noqa: PLC0415  (stdlib-only; imported late so `status` never needs it)
        argv = field_agent.headless_argv(edits=True)
        if argv is None:
            print(f"no agent installed for {field_agent.configured_agent()!r} (FIELD_AGENT or ~/.afield/agent)", file=sys.stderr)
            return 2
        stdin = stub_prompt(routine, paths)
    if dry_run:
        print("cwd:  " + str(checkout))
        print("argv: " + shlex.join(argv))
        if stdin:
            print("stdin:\n" + stdin)
        return 0
    try:
        with routine_lock(routine.name):
            if catch_up:
                health = schedule_health(routine, recent_runs(routine, checkout), True, check_running=False,
                                         journal_locks=journal_lock_paths(routine, checkout))
                if health["state"] == "unknown":
                    print(health["message"], file=sys.stderr)
                    return 2
                if health["state"] != "overdue":
                    print(f"{routine.name}: no missing slot to catch up ({health['state']})")
                    return 0
                env["FIELD_ROUTINE_CATCH_UP"] = "1"
            logs = RUN_LOGS / routine.name
            started = local_clock()
            log = logs / f"{started.strftime('%Y%m%d-%H%M%S-%f')}.log"
            state = {"startedAt": started.isoformat(), "log": str(log), "catchUp": catch_up}
            def record():
                temporary = logs / "last-run.tmp"
                temporary.write_text(json.dumps(state, indent=2) + "\n")
                temporary.replace(logs / "last-run.json")
            record()
            with KeepAwake(), log.open("w", encoding="utf-8") as out:
                out.write(f"# routine {routine.name} at {started.isoformat()} in {checkout}\n")
                out.write(f"# argv: {shlex.join(argv)}\n")
                out.flush()
                try:
                    required = float(routine.get("disk-gib", "0"))
                    if not math.isfinite(required) or required < 0:
                        raise ValueError("disk-gib must be finite and non-negative")
                    free = shutil.disk_usage(checkout).free / 1024**3
                    out.write(f"# disk preflight: {free:.2f} GiB free; {required:g} GiB required\n")
                    out.flush()
                    if free < required:
                        out.write("# not run: insufficient whole-run disk budget; no cleanup attempted\n")
                        code = 2
                    else:
                        done = subprocess.run(argv, cwd=checkout, env=env, input=stdin, text=True,
                                              stdout=out, stderr=subprocess.STDOUT)
                        code = done.returncode
                except (OSError, ValueError) as exc:
                    out.write(f"\n# launch failed: {exc}\n")
                    code = 2
                out.write(f"\n# exit {code}\n")
                state.update(exit=code, finishedAt=local_clock().isoformat())
                record()
            _prune(logs)
            print(f"{routine.name}: exit {code}, log {log}")
            return code
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 2


def _prune(logs: Path) -> None:
    cutoff = dt.datetime.now().timestamp() - LOG_DAYS * 86400
    for old in logs.glob("2*.log"):
        try:
            if old.stat().st_mtime < cutoff:
                old.unlink()
        except OSError:
            pass


# ---------------------------------------------------------------------------------------------
# Status


DATE = re.compile(r"(\d{4})-?(\d{2})-?(\d{2})")


def _date_of(name: str) -> str:
    match = DATE.search(name)
    return f"{match.group(1)}-{match.group(2)}-{match.group(3)}" if match else ""


def _verdict(routine: Routine, text: str) -> str:
    pattern = routine.get("verdict")
    if pattern:
        try:
            match = re.search(pattern, text)
        except re.error:
            match = None
        if match:
            groups = [g for g in match.groups() if g] or [match.group(0)]
            return _clip(groups[0].strip())
    lines = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]
    return _clip(lines[-1] if lines else "")


def _clip(text: str, limit: int = 240) -> str:
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _git(repo: Path, *args: str) -> str | None:
    try:
        done = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return None
    return done.stdout if done.returncode == 0 else None


def recent_runs(routine: Routine, repo: Path) -> list[dict[str, str]]:
    """Newest first: {date, verdict, source}. Reads the routine's own reports, plus the logs that
    `run` keeps, so a routine adopted through launchd or cron is visible whatever it writes."""
    spec = routine.get("reports")
    runs: list[dict[str, str]] = []
    if spec.startswith("git:"):
        _, branch, folder = spec.split(":", 2)
        listing = _git(repo, "ls-tree", "--name-only", f"refs/heads/{branch}", f"{folder.rstrip('/')}/")
        # Journal folders also contain dated audits, archives and evidence notes.
        # Only the routine's date-named reports (including same-day b/c runs) are attempts.
        names = sorted((n for n in (listing or "").splitlines()
                        if re.fullmatch(r"\d{4}-\d{2}-\d{2}[a-z]?\.md", Path(n).name)), reverse=True)
        for name in names[:RECENT]:
            text = _git(repo, "show", f"refs/heads/{branch}:{name}") or ""
            runs.append({"date": _date_of(Path(name).name), "verdict": _verdict(routine, text), "source": f"{branch}:{name}"})
    elif spec.startswith("file:"):
        for path in sorted(glob.glob(os.path.expanduser(spec[5:])), reverse=True)[:RECENT]:
            try:
                text = Path(path).read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            runs.append({"date": _date_of(Path(path).name), "verdict": _verdict(routine, text), "source": path})
    elif spec.startswith("log:"):
        out = _git(repo, "log", "refs/heads/main", f"-n{RECENT}", "-E", f"--grep={spec[4:]}", "--format=%cs%x09%h %s") or ""
        for line in out.splitlines():
            day, _, subject = line.partition("\t")
            runs.append({"date": day, "verdict": subject, "source": "main"})
    logs = RUN_LOGS / routine.name
    if logs.is_dir():
        for path in sorted(logs.glob("2*.log"), reverse=True)[:RECENT]:
            text = path.read_text(encoding="utf-8", errors="replace")
            runs.append({"date": _date_of(path.name), "verdict": _verdict(routine, text), "source": str(path)})
    return sorted(runs, key=lambda r: (r["date"], r["source"].endswith(".log"), r["source"]), reverse=True)[:RECENT]


def adopted(routine: Routine, repo: Path) -> list[str]:
    """Where this machine already schedules the routine, as far as can be seen from here."""
    found = []
    if (LAUNCH_AGENTS / f"{LABEL_PREFIX}{routine.name}.plist").is_file():
        found.append("launchd")
    marker = f"routines/{routine.name}/ROUTINE.md"
    if CLAUDE_TASKS.is_dir():
        for skill in CLAUDE_TASKS.glob("*/SKILL.md"):
            try:
                if marker in skill.read_text(encoding="utf-8", errors="replace"):
                    found.append(f"Claude scheduled task {skill.parent.name}")
            except OSError:
                pass
    try:
        crontab = subprocess.run(["crontab", "-l"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        crontab = ""
    if f"routines.py run {routine.name}" in crontab:
        found.append("cron")
    return found


def status_command(routine: Routine, repo: Path) -> str:
    command = routine.get("status-command")
    if not command:
        return ""
    argv = shlex.split(command)
    if argv and argv[0] == "python3":
        argv[0] = python_for_schedulers()
    try:
        done = subprocess.run(argv, cwd=repo, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as error:
        return f"({error})"
    lines = [line for line in (done.stdout or done.stderr).splitlines() if line.strip()]
    return lines[-1].strip() if lines else ""


def status(repo: Path = HERE) -> dict:
    routines = discover(repo)
    checkout = primary_checkout(repo)
    entries = []
    for r in routines:
        recent = recent_runs(r, checkout)
        schedulers = adopted(r, checkout)
        entries.append({
            "health": schedule_health(r, recent, bool(schedulers), journal_locks=journal_lock_paths(r, checkout)),
            "name": r.name, "title": r.get("title"), "summary": r.get("summary"), "kind": r.kind,
            "schedule": r.get("schedule"), "when": describe_schedule(r.get("schedule")),
            "runtimeHours": r.runtime_hours, "diskGib": r.get("disk-gib"), "quiet": r.get("quiet"),
            "needs": r.get("needs"), "cost": r.get("cost"), "journal": r.get("journal"),
            "path": f"routines/{r.name}/ROUTINE.md", "recent": recent,
            "now": status_command(r, repo), "adopted": schedulers, "errors": r.errors,
        })
    return {"repo": str(checkout), "routines": entries, "conflicts": conflicts(routines)}


# ---------------------------------------------------------------------------------------------
# Check


def check(repo: Path = HERE) -> list[str]:
    faults = []
    routines = discover(repo)
    if not routines:
        faults.append("no routines/*/ROUTINE.md found")
    for r in routines:
        where = f"routines/{r.name}/ROUTINE.md"
        faults += [f"{where}: {e}" for e in r.errors]
        if not NAME.match(r.name):
            faults.append(f"{where}: folder name is not a routine name")
        if r.get("name") != r.name:
            faults.append(f"{where}: name {r.get('name')!r} differs from its folder")
        faults += [f"{where}: missing `{k}`" for k in REQUIRED if not r.get(k)]
        faults += [f"{where}: unknown key `{k}`" for k in r.meta if k not in KNOWN]
        if r.kind not in KINDS:
            faults.append(f"{where}: kind must be one of {', '.join(KINDS)}")
        if r.kind == "command":
            argv = shlex.split(r.get("command"))
            if not argv:
                faults.append(f"{where}: a command routine needs `command`")
            else:
                scripts = [a for a in argv if a.startswith("routines/") or a.startswith("tools/")]
                faults += [f"{where}: command names {a}, which does not exist" for a in scripts if not (repo / a).exists()]
        if not cron_valid(r.get("schedule")):
            faults.append(f"{where}: schedule {r.get('schedule')!r} is not a five-field cron expression")
        if not math.isfinite(r.runtime_hours) or r.runtime_hours <= 0:
            faults.append(f"{where}: runtime-hours must be a positive number")
        try:
            disk = float(r.get("disk-gib", "0"))
            if not math.isfinite(disk) or disk < 0:
                raise ValueError()
        except ValueError:
            faults.append(f"{where}: disk-gib must be finite and non-negative")
        spec = r.get("reports")
        if spec and not (spec == "runs" or re.match(r"^(git:[^:]+:.+|file:.+|log:.+)$", spec)):
            faults.append(f"{where}: reports must be runs, git:<branch>:<folder>, file:<glob> or log:<regex>")
        if r.get("verdict"):
            try:
                re.compile(r.get("verdict"))
            except re.error as error:
                faults.append(f"{where}: verdict is not a regex ({error})")
        for linked in re.findall(r"\$REPO/(routines/[\w./-]+|tools/[\w./-]+)", r.body):
            if not (repo / linked).exists():
                faults.append(f"{where}: refers to $REPO/{linked}, which does not exist")
    return faults


# ---------------------------------------------------------------------------------------------
# Command line


def _print_status(report: dict) -> None:
    for entry in report["routines"]:
        last = entry["recent"][0] if entry["recent"] else None
        print(f"{entry['name']:<18} {entry['when']:<28} {entry['kind']:<8} "
              + (f"last {last['date']}: {last['verdict']}" if last else "no runs found"))
        health = entry.get("health", {})
        if health:
            print(f"{'':<18} {health['state']}: {health['message']} "
                  f"(expected {health['expectedAt']}; timezone {health['timezone']})")
        if entry["now"]:
            print(f"{'':<18} now: {entry['now']}")
        if entry["adopted"]:
            print(f"{'':<18} scheduled here by: {', '.join(entry['adopted'])}")
    for line in report["conflicts"]:
        print(f"warning: {line}")


def notify_health(report: dict) -> bool:
    """Notify on newly actionable states, not on every periodic check or recovery."""
    path = RUN_LOGS / "audit-notifications.json"
    with routine_lock("_audit-notifications"):
        try:
            previous = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(previous, dict):
                previous = {}
        except (OSError, ValueError):
            previous = {}
        current = {}
        for entry in report["routines"]:
            state = entry["health"]["state"]
            name = entry["name"]
            if state in ("overdue", "interrupted", "failed"):
                current[name] = state
            elif state == "unknown" and name in previous:
                current[name] = previous[name]
        changed = {name: state for name, state in current.items() if previous.get(name) != state}
        if changed:
            message = "; ".join(f"{name}: {state}" for name, state in sorted(changed.items()))
            if sys.platform != "darwin":
                print(f"routine notification unavailable on {sys.platform}: {message}", file=sys.stderr)
                return False
            try:
                result = subprocess.run(
                    ["osascript", "-e", "on run argv", "-e",
                     'display notification (item 1 of argv) with title "Field routines"',
                     "-e", "end run", message], capture_output=True, text=True, timeout=15,
                )
            except (OSError, subprocess.SubprocessError) as exc:
                print(f"routine notification failed: {exc}", file=sys.stderr)
                return False
            if result.returncode:
                print(f"routine notification failed: {result.stderr.strip()}", file=sys.stderr)
                return False
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(current, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)
        return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", type=Path, default=HERE, help=argparse.SUPPRESS)
    sub = parser.add_subparsers(dest="mode", required=True)
    sub.add_parser("list", help="One line per routine.")
    p_status = sub.add_parser("status", help="How each routine last went, where it is scheduled, and schedule clashes.")
    p_status.add_argument("--json", action="store_true")
    p_audit = sub.add_parser("audit", help="Report scheduled-run health; exit 1 on failures, 2 on unknown health.")
    p_audit.add_argument("--json", action="store_true")
    p_audit.add_argument("--notify", action="store_true", help="Notify on newly actionable states on macOS.")
    p_install = sub.add_parser("install", help="Render a routine for a scheduler.")
    p_install.add_argument("name")
    p_install.add_argument("--harness", choices=HARNESSES, default="prompt")
    p_install.add_argument("--apply", action="store_true", help="launchd only: write the LaunchAgent and load it.")
    p_install.add_argument("--json", action="store_true")
    p_run = sub.add_parser("run", help="Run a routine now, as a scheduler would.")
    p_run.add_argument("name")
    p_run.add_argument("--catch-up", action="store_true", help="Run the latest missing slot once; never replay older slots.")
    p_run.add_argument("--dry-run", action="store_true", help="Print what would run, and the prompt, and stop.")
    sub.add_parser("check", help="Validate every routines/*/ROUTINE.md.")
    args = parser.parse_args(argv)
    repo = args.repo.resolve()

    if args.mode == "list":
        for r in discover(repo):
            print(f"{r.name:<18} {describe_schedule(r.get('schedule')):<28} {r.kind:<8} {r.get('summary')}")
        return 0
    if args.mode in ("status", "audit"):
        report = status(repo)
        if args.json:
            print(json.dumps(report, indent=2))
        else:
            _print_status(report)
        if args.mode == "audit":
            if args.notify and not notify_health(report):
                return 2
            if any(r["health"]["state"] in ("overdue", "interrupted", "failed") for r in report["routines"]):
                return 1
            if any(r["health"]["state"] == "unknown" for r in report["routines"]):
                return 2
        return 0
    if args.mode == "install":
        routine = find(args.name, repo)
        rendered = render_install(routine, args.harness, repo)
        if args.apply:
            if args.harness != "launchd":
                raise SystemExit("--apply installs launchd agents only; for cron use `crontab -e`, for an agent paste the prompt")
            print(apply_launchd(rendered, routine))
            return 0
        print(json.dumps(rendered, indent=2) if args.json else rendered["text"])
        return 0
    if args.mode == "run":
        return run(find(args.name, repo), repo, args.dry_run, args.catch_up)
    if args.mode == "check":
        faults = check(repo)
        for fault in faults:
            print(fault)
        print(f"{len(discover(repo))} routines, {len(faults)} faults")
        return 1 if faults else 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
