---
name: feel-bench
title: Nightly feel benchmark
summary: When the machine is quiet, times Field's first run, reruns, edits and shader compiles against this computer's committed baseline, and reports any step that got slower.
kind: command
command: zsh routines/feel-bench/nightly.sh --give-up 00:10
schedule: 0 23 * * *
runtime-hours: 1.75
disk-gib: 4
quiet: yes
needs: a baseline for this computer in dev_docs/testing/feel_baselines/ (see FEEL_BENCHMARKS.md); AC power; nothing else running
cost: no agent; about 30 minutes of one Field instance, after a wait for the machine to go quiet
reports: file:~/.afield/feel-bench/nightly-*.txt
verdict: (?m)^NIGHTLY: (.+)$
---

# Nightly feel benchmark

Runs `tools/feel_bench.py` 100 times over on local `main` and checks the result against this
computer's baseline. It is report-only: it never commits, re-baselines, lands or kills anything.

The script needs no agent, so a scheduler can run it directly (`tools/routines.py run feel-bench`).
It waits until the Mac is on AC power and the one-minute load average has stayed under 4 for three
minutes, then runs for about half an hour. If the machine never goes quiet before `--give-up`, it
skips the night and says so. Schedule it to finish before any heavy routine starts: a benchmark
taken while three agents are compiling measures the agents.

A computer with no baseline gets "check not comparable". Make one first, from three runs:
`dev_docs/testing/FEEL_BENCHMARKS.md`, section "The ratchet".

## When an agent runs it

If your scheduler only runs agents, the agent does this:

1. Run `zsh $REPO/routines/feel-bench/nightly.sh --give-up 00:10` from `$REPO` as a background
   command. It can take a few hours, most of it waiting. Do not poll; wait for it to finish, and do
   not start a second copy.
2. Read its report, `~/.afield/feel-bench/nightly-<YYYYMMDD>.txt`. Its last line is
   `NIGHTLY: <verdict>`. If it reached the check, also read
   `$WORKTREE_BASE/feel-nightly/build/feel-bench/nightly-check.md`.
3. Reply with a short report: the verdict; the commit measured; when the run started and the load
   then; every step marked regressed, failed, missing or faster, with baseline → now in ms and the
   top spans; and, if it skipped, why. If the script itself failed, quote the first concrete error
   from the report or from `$WORKTREE_BASE/feel-nightly/build/feel-bench-nightly-run.log`.

Do not edit any file in a checkout, kill any process, try to fix a regression, or rerun.
