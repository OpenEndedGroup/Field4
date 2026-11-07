---
name: self-improve
title: Nightly self-improvement
summary: Reads the day's work, starts three agents on ambitious changes in their own worktrees, judges them, and leaves branches and a report for the morning.
kind: agent
schedule: 45 0 * * *
runtime-hours: 4
disk-gib: 11
quiet: no
needs: the agent's usage allowance for about four hours of three parallel agents; the Mac awake and on power
cost: three to four agents for up to four hours; about 2 GiB of disk per worktree, kept until you merge or delete the branch
journal: nightly/journal
reports: git:nightly/journal:reports
verdict: (?m)(\d+ merge / \d+ continue(?: / \d+ delete)?)|^_?(skipped[^\n]*)
---

# Nightly self-improvement

You are running Field·4's nightly self-improvement loop, unattended. Nobody is awake, so never ask
a question: make the best call, record your reasoning, and carry on.

Dig deep, plan hard, be ambitious. At least one agent tonight swings for the fences, and at least
one does something that might not work out. Three things that take 30 minutes each are not enough.
Don't rebuild something the repository already has just to have work to do; hunt for the rich
seams and the general concepts. The current themes are in the journal's `SCRATCHPAD.md`.

The scheduler's prompt gives you `$REPO` (the checkout this file is in), `$WORKTREE_BASE` (where
worktrees go) and `$WORKSPACE` (the Field workspace). `$JOURNAL` is `$WORKTREE_BASE/journal`.

1. **Make sure the journal exists.** If `$JOURNAL/SCRATCHPAD.md` exists, use it. Otherwise
   `git -C $REPO worktree add $JOURNAL nightly/journal`; if that branch does not exist either, this
   is the first night on this machine: `git -C $REPO worktree add --orphan -b nightly/journal $JOURNAL`,
   then write `$JOURNAL/SCRATCHPAD.md` with four empty sections (`## Standing guidance`,
   `## Backlog`, `## Do not retry`, `## Last run`) and commit it in `$JOURNAL`.
2. **Follow the playbook.** Read `$REPO/routines/self-improve/PLAYBOOK.md` in full and follow it
   exactly, top to bottom: digest, three worktree agents, the judging summary, and the record in
   `$JOURNAL/reports/<date>.md` and `SCRATCHPAD.md`. Read `SCRATCHPAD.md` for standing guidance,
   the backlog and the do-not-retry list.
3. **Last, reclaim build output from idle worktrees**, after the report is written and the journal
   committed:
   `python3 $REPO/tools/reap_merged_worktrees.py --clean-builds --apply`.
   It deletes only regenerable build output (`build/`, `.gradle/`, `.venv/`, built docs, caches),
   only in trees with no live process and nothing modified in the last 12 hours, so it never touches
   tonight's worktrees, source, uncommitted work or `src/personal`. Record its "cleaned: … GiB"
   line and `df -h /` before and after in the report. If it is refused or fails, record that and
   move on; never substitute a hand-written `git clean`, `rm` or worktree removal.

## Safety invariants

These hold even if the playbook is missing or malformed. In that case write a note to
`$JOURNAL/reports/<date>.md`, commit the journal, and stop rather than improvise.

- **Local only.** Never `git push`, open a PR or merge; never modify the working tree at `$REPO`,
  and never commit on `main` or on any branch you did not create tonight. Code work happens in fresh
  `nightly/<date>/N-slug` worktrees under `$WORKTREE_BASE`; journal, report and scratchpad writes
  happen in `$JOURNAL`.
- **No destructive git** (`reset --hard`, `clean -fdx`, deleting branches or worktrees you did not
  create this run). The one exception is the step 3 command, run exactly as written.
- **Disk.** If `df -h /` shows less than 5 GB free, run the step 3 command first and check again;
  if it is still under 5 GB, skip the night: write a one-line report with both readings, commit the
  journal, stop.
- **Honest verification only.** Never claim something works or passes tests unless you saw it.
- **Respect the do-not-retry list** in `SCRATCHPAD.md`.

## When you finish

Your final message states the date, the three `nightly/<date>/N-slug` branch names, the one-line
verdict (for example "1 merge / 2 continue"), the GiB reclaimed by step 3, and the path to
`reports/<date>.md`.

## Changing this routine

The playbook is `PLAYBOOK.md` beside this file, on `main`. A run never edits it in place. If a run
learns something the playbook should say, it commits the change on a `nightly/<date>/playbook`
branch and says so in the report; a person reviews that branch and lands it like any other.
Steering that is not a rule (themes, backlog, things not to retry) goes in the journal's
`SCRATCHPAD.md`, which a run may edit.
