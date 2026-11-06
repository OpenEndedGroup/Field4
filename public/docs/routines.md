# Overnight Routines

Field's checkout comes with a handful of jobs meant to run on a schedule while you are away: an
agent that makes improvements overnight, a nightly build-and-test run, a benchmark that notices
when Field gets slower, a weekly entry for [What's New](changelog.md), a scout that reads the
day's links for ideas worth bringing in, and a reaper that keeps the agents' worktrees from filling
your disk. Each one is a folder in [`routines/`](https://github.com/OpenEndedGroup/Field4/blob/main/routines/README.md). Field does not
schedule anything itself. You choose which routines to run and adopt each into a scheduler you
already have: an agent's scheduled tasks, `launchd` or `cron`.

None of them is needed to use Field. They are for people who work on Field, or on a fork of it,
and want the same overnight help.

## The routines

| Routine | What it does | Needs an agent | Suggested time |
| --- | --- | --- | --- |
| `self-improve` | Reads the day's commits and your workspace, gives three agents an ambitious change each in its own worktree, and has a fourth judge them. In the morning there is a report and three branches to merge, continue or delete. | yes | 00:45 |
| `nightly-ci` | Builds `main` from clean, fixes Kotlin warnings that are new since the last run, then runs the full docs build and the smoke catalog as the test suite. Fixes go on a branch of their own. | yes | 05:02 |
| `feel-bench` | Times first runs, reruns, edits and shader compiles 100 times over and compares them with your computer's baseline. It waits for the machine to go quiet first. | no | 23:00 |
| `weekly-changelog` | Writes the week's entries for the What's New page and sends them to the [landing queue](landing_queue.md). | yes | Sundays 21:30 |
| `reap-worktrees` | Removes worktrees that are merged, clean and idle, and deletes rebuildable build output from idle ones. | no | every 4 hours |
| `scout` | Collects the day's links from Hacker News, lobste.rs, a list of feeds and searches for topics Field already works on, reads every page that might matter, and writes a card for each one worth bringing into Field: what it is, what Field would add, the figures a version in Field must reproduce, and its license. Once a week the cards become a digest you answer with yes, no or later. | yes | 19:15 |

None of the agent routines pushes, opens a pull request or commits on `main`. Each one leaves its
work on branches for you to review, and `weekly-changelog` lands only through the queue. The
details, and the rules each run must follow, are in that routine's `ROUTINE.md` (for example [`routines/nightly-ci/ROUTINE.md`](https://github.com/OpenEndedGroup/Field4/blob/main/routines/nightly-ci/ROUTINE.md)).

## Seeing them

On the welcome page, open the **AI** panel and press **Routines**. The page that opens lists every
routine with its suggested time, what it needs and uses, how its recent runs went, and whether this
computer already schedules it. From a terminal, in your checkout:

```diffstatic {data-lang=bash}
:::static
python3 tools/routines.py status
```

```diffstatic {data-lang=text}
:::static
feel-bench         every day at 23:00           command  last 2026-11-06: skipped: load never held under 4 before 04:50 (load 9.84)
nightly-ci         every day at 05:02           agent    last 2026-11-06: main builds clean · 0 new warnings · docsGates green (10m13s) · …
reap-worktrees     every 4 hours, at :20        command  no runs found
self-improve       every day at 00:45           agent    last 2026-11-06: 3 merge / 0 continue / 0 delete
weekly-changelog   Sundays at 21:30             agent    no runs found
                   now: 18 landings on main not yet in an entry, 2026-11-04 .. 2026-11-06
```

If a routine that needs a quiet machine overlaps another routine at their suggested times,
`status` says so. A benchmark taken while three agents are compiling measures the agents.

## Adopting one

Each routine's **Adopt it** buttons, and `tools/routines.py install`, produce what a scheduler
needs, in one of three forms.

**An agent's scheduled tasks.** If your agent can schedule its own tasks (Claude Code's scheduled
tasks, for example), ask it to create one:

```diffstatic {data-lang=bash}
:::static
python3 tools/routines.py install self-improve --harness prompt
```

This prints a request to paste into the agent. The task it asks for has a short prompt that names
your checkout and tells the agent to follow `routines/self-improve/ROUTINE.md`. Because the prompt
only points at the routine, a change to the routine reaches the schedule the next night, with no
re-scheduling. Allow the task to edit files and run commands without asking: nobody will be there
to answer.

**launchd**, on a Mac:

```diffstatic {data-lang=bash}
:::static
python3 tools/routines.py install reap-worktrees --harness launchd --apply
```

This writes a LaunchAgent to `~/Library/LaunchAgents/` and loads it. Leave out `--apply` to see the
file first. The printed comment says how to remove it.

**cron**, anywhere:

```diffstatic {data-lang=bash}
:::static
python3 tools/routines.py install feel-bench --harness cron
```

This prints a line to add with `crontab -e`.

Both `launchd` and `cron` run `tools/routines.py run <name>`. A routine that needs no agent runs
its script directly. One that does is handed to the agent the landing queue uses, as a single
session that may edit and run commands in your checkout. That is Claude Code unless you choose Codex
([Which agent](landing_queue.md#which-agent)). Each run's output is kept in
`~/.afield/routines/<name>/` for 30 days. To see what a run would start without starting it:

```diffstatic {data-lang=bash}
:::static
python3 tools/routines.py run nightly-ci --dry-run
```

!!! note "Long agent routines have mostly run as scheduled agent tasks"
    `self-improve` and `nightly-ci` run for hours and start agents of their own. They have been
    run night after night as an agent's scheduled tasks. Running them from `launchd` or `cron`
    through a one-shot agent session is newer. Try one with `run --dry-run`, then once by hand,
    before you leave it to the night.

## What they need

The agent routines use your agent's allowance: `self-improve` runs three or four agents for up to
four hours, and `nightly-ci` one agent for up to five. Both need the computer awake and on power.
`nightly-ci` also needs the display on, because the docs build captures GPU output. Each run
checks free disk space before it starts and skips the night if there is too little. That is 11 GiB
for `self-improve` and 8 GiB for `nightly-ci`. A built worktree takes 2 to 16 GiB, so if you run
the agent routines, run `reap-worktrees` too.

`feel-bench` compares each run with a baseline recorded on your computer. Until you have made one,
its result is "check not comparable". Record a baseline from three runs, as described in
[`dev_docs/testing/FEEL_BENCHMARKS.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/testing/FEEL_BENCHMARKS.md).

## Their memory

An agent routine starts every night with no memory of the last, so it keeps a journal. This is a
branch of its own (`nightly/journal` for `self-improve`, `nightly/ci-journal` for `nightly-ci`),
checked out beside your checkout. It holds a `SCRATCHPAD.md` of standing guidance, a backlog and
ideas not to try again, plus a report for every night. The routine creates the journal on its first
run. It stays on your computer: it is never merged into `main` and never pushed.

To steer a routine, edit its journal's `SCRATCHPAD.md`: add a theme, a backlog item, or something
it should stop trying. To read a night, open `reports/<date>.md` there, then
`git diff main...<branch>` for each branch it recommends.

## Changing a routine

What a routine does is in `routines/<name>/ROUTINE.md`, and the long ones keep a step-by-step
`PLAYBOOK.md` beside it. Change them on a branch and land it like any other change, and the next
scheduled run follows the new version. A routine never rewrites its own instructions. When a run
learns something its playbook should say, it commits the change on a branch of its own and names it
in its report. The file format, and how to add a routine of your own, are in [`routines/README.md`](https://github.com/OpenEndedGroup/Field4/blob/main/routines/README.md).
