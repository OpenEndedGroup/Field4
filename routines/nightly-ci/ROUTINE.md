---
name: nightly-ci
title: Nightly CI
summary: Builds main from clean, fixes Kotlin warnings that are new since the last run, runs the full docs build and smoke catalog as the test suite, and writes a report.
kind: agent
schedule: 2 5 * * *
runtime-hours: 4
disk-gib: 36
quiet: no
needs: the agent's usage allowance for one long session; the Mac awake, on power, with the display on (GPU captures need it)
cost: one agent for up to five hours; about 13 GiB for the build plus 14 GiB observed capture growth; allow 36 GiB free including the protected floor
journal: nightly/ci-journal
reports: git:nightly/ci-journal:reports
verdict: (?m)^## Verdict\n+([^\n]+)|^(skipped[^\n]*)
---

# Nightly CI

You are running Field·4's nightly AI-CI, unattended. Nobody is awake, so never ask a question: make
the best call, record your reasoning, and carry on.

Your job: take whatever is on `main`, build it clean, triage and fix the Kotlin build warnings that
have newly turned up, run the full docs build as the integration test suite with timing, and write
an honest report. Warnings are not purely mechanical: some reveal an architectural gap and deserve a
diagnosis or a backlog item, not a suppression.

The scheduler's prompt gives you `$REPO` (the checkout this file is in), `$WORKTREE_BASE` (where
worktrees go) and `$WORKSPACE` (the Field workspace). `$JOURNAL` is `$WORKTREE_BASE/ci-journal`.

1. **Make sure the journal exists.** If `$JOURNAL/SCRATCHPAD.md` exists, use it. Otherwise
   `git -C $REPO worktree add $JOURNAL nightly/ci-journal`; if that branch does not exist either, this
   is the first run on this machine: `git -C $REPO worktree add --orphan -b nightly/ci-journal $JOURNAL`,
   then write `$JOURNAL/SCRATCHPAD.md` with empty `## Standing guidance`, `## Architectural backlog`,
   `## Known-flaky captures` and `## Last run` sections and commit it. With no
   `warnings-baseline.json`, the run establishes one: record the full warning set and fix only the
   three to five that matter most.
2. **Follow the playbook.** Read `$REPO/routines/nightly-ci/PLAYBOOK.md` in full and follow it
   exactly, top to bottom: preflight, disk and lock; a fresh build worktree off `main`; clean compile
   and warning capture; the diff against `warnings-baseline.json`; triage and fixes; the full timed
   docs build and smoke catalog; the summary; then the report, baseline and scratchpad. Read
   `SCRATCHPAD.md` for standing guidance, the architectural backlog and the known-flaky captures.
3. **Last, reclaim build output from idle worktrees**, after the report is written and the journal
   committed:
   `python3 $REPO/tools/reap_merged_worktrees.py --clean-builds --apply`.
   It deletes only regenerable build output, only in trees with no live process and nothing
   modified in the last 12 hours. Record its "cleaned: … GiB" line and `df -h /` before and after.
   If it is refused or fails, record that and move on; never substitute a hand-written `git clean`,
   `rm` or worktree removal.

## Safety invariants

These hold even if the playbook is missing or malformed. In that case write a note to
`$JOURNAL/reports/<date>.md`, commit the journal, and stop rather than improvise.

- **Local only.** Never `git push`, open a PR or merge; never modify the working tree at `$REPO`,
  and never commit on `main` or any branch you did not create. The build and any fixes happen in a
  fresh `nightly/ci/<date>-warnings` worktree under `$WORKTREE_BASE`; report, baseline and
  scratchpad writes happen in `$JOURNAL`.
- **No destructive git** (`reset --hard`, `clean -fdx`, deleting branches or worktrees you did not
  create this run). The one exception is the step 3 command, run exactly as written.
- **Fix, don't suppress.** Resolving a warning means removing its cause. `@Suppress`, `-nowarn` or
  deleting the emitting code is a last resort, flagged with its justification.
- **Disk.** Require 36 GiB free on the checkout volume before creating the build tree (8 GiB
  protected floor plus 28 GiB whole-run allowance). Run the step 3 command first if below that;
  if still under budget, skip the run: a one-line report with both readings, commit, stop. A
  nearly full disk silently corrupts JVM, PNG and docs captures.
- **Honest verification only.** Re-compile after fixes to confirm the warning is gone and nothing
  new broke; treat docs-capture failures as test failures.
- **Environment.** Gradle through `./gradlew` in the fix worktree; all Python through
  `uv run --project . --group <backdoor|docs>`, never ambient `python` or `pip`.

## When you finish

Your final message states the date, whether `main` built clean, the warning headline (for example
"4 new: 3 fixed / 1 → backlog"), the docs result (captures passed of total, any regressions, the
docs-build wall-clock), the `nightly/ci/<date>-warnings` branch (or "no fixes needed"), the GiB
reclaimed by step 3, and the path to `reports/<date>.md`.

## Changing this routine

The playbook is `PLAYBOOK.md` beside this file, on `main`, and `landed_check.py` is its helper. A
run never edits them in place: a change it wants goes on a `nightly/ci/<date>-playbook` branch,
named in the report, for a person to review and land. The journal's `SCRATCHPAD.md`, backlog and
baseline are the run's own to update.
