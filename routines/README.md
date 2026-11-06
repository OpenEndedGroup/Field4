# Routines

Work that runs on a schedule, unattended, against this checkout: the overnight loops that improve,
test and benchmark Field, and the housekeeping that keeps their worktrees from filling the disk.
Each folder here is one routine. Field does not schedule anything itself: `tools/routines.py`
renders a routine for whatever scheduler you already use, and `/routines.html` in a running Field
shows how each one last went. The public page is `public/docs/routines.md`.

| Routine | What it does | Kind |
| --- | --- | --- |
| [`self-improve`](self-improve/ROUTINE.md) | Three agents make ambitious changes in their own worktrees overnight; a judge writes the morning report | agent |
| [`nightly-ci`](nightly-ci/ROUTINE.md) | Clean build of `main`, new Kotlin warnings fixed, the docs build and smoke catalog as the test suite | agent |
| [`feel-bench`](feel-bench/ROUTINE.md) | `tools/feel_bench.py` against this computer's baseline, once the machine is quiet | command |
| [`weekly-changelog`](weekly-changelog/ROUTINE.md) | The week's entries for the public What's New page, through the landing queue | agent |
| [`reap-worktrees`](reap-worktrees/ROUTINE.md) | Merged, idle worktrees removed; build output stripped from idle ones | command |

## The format

`ROUTINE.md` starts with a frontmatter block of flat `key: value` lines (values are literal, so a
regex needs no quoting), then the instructions a run follows.

| Key | Meaning |
| --- | --- |
| `name` | The folder name. |
| `title`, `summary` | What `/routines.html` and `routines.py list` show. |
| `kind` | `agent`: a coding agent follows the body. `command`: a scheduler runs `command` directly, no agent. |
| `command` | For `command` routines: an argv, relative to the checkout. |
| `schedule` | The suggested time, as five-field cron in local time. |
| `runtime-hours` | How long a run takes, used to spot clashes. |
| `quiet` | `yes` if the routine measures the machine and must not overlap any other routine. |
| `disk-gib` | Free space a run needs when it starts. |
| `needs`, `cost` | Plain words for the reader: what must be true, and what a run uses. |
| `journal` | The orphan branch an agent routine keeps its reports and scratchpad on. |
| `reports` | Where status finds past runs: `git:<branch>:<folder>`, `file:<glob>`, `log:<regex over main's commit subjects>`, or `runs` (only the logs `routines.py run` keeps). |
| `verdict` | A regex whose first matching group is a run's one-line outcome. |
| `status-command` | A command whose last line says how things stand now. |

The body is written to the agent (or, for a command routine, to an agent asked to run the command
when that is the only scheduler available). It uses `$REPO`, `$WORKTREE_BASE` and `$WORKSPACE`; the
scheduler's prompt says what each one is on this machine. A long routine keeps its step-by-step
playbook in a `PLAYBOOK.md` beside it.

## Journals

An agent routine remembers across nights through its journal: an orphan branch (`nightly/journal`,
`nightly/ci-journal`) checked out under `$WORKTREE_BASE`, holding `SCRATCHPAD.md` (standing
guidance, backlog, what not to retry) and `reports/<date>.md`. The journal is that machine's state,
so it is never on `main` and never pushed; the routine creates it on its first run. The definition,
the playbook and the safety rules are here, on `main`, so they are reviewed and land like any other
change.

## Changing a routine

Edit the files here on a branch and land it. A scheduled run reads `main`'s copy the next time it
starts, so nothing needs re-scheduling. A run never edits its own definition: when it learns
something the playbook should say, it commits the change on a branch of its own and names it in
its report.

`python3 tools/routines.py check` validates every routine; `tools/test_routines.py` runs it, so a
routine naming a script that does not exist fails the build rather than the night.

## Adopting one

```bash
python3 tools/routines.py status
python3 tools/routines.py install self-improve --harness prompt      # paste into your agent
python3 tools/routines.py install reap-worktrees --harness launchd --apply
python3 tools/routines.py install feel-bench --harness cron          # a crontab line
python3 tools/routines.py run nightly-ci --dry-run                   # what a scheduler would start
```

`run` is what launchd and cron call. A command routine runs its command; an agent routine goes to
the agent `tools/field_agent.py` picks (`FIELD_AGENT` or `~/.afield/agent`) as one unattended
session that may edit and run commands in the checkout. Its output is kept in
`~/.afield/routines/<name>/` for 30 days.
