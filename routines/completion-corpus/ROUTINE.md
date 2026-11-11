---
name: completion-corpus
title: Completion-corpus refresh
summary: Every night before nightly CI, re-harvests the completion-corpus fixtures from main's transcripts and templates and lands the refresh, so landings no longer have to.
kind: command
command: zsh routines/completion-corpus/refresh.sh
schedule: 15 4 * * *
runtime-hours: 0.5
disk-gib: 4
quiet: no
needs: the landing queue running (tools/land.py); a worktree's worth of disk for the first run
cost: no agent; one Gradle compile and harvest, a few minutes when main has not moved much
reports: runs
verdict: (?m)^CORPUS: (.+)$
---

# Completion-corpus refresh

`tools/completion-corpus/boxes` is harvested from the transcript and template corpus after real
macro expansion, and the completion tests in `jvmTest` read it. Until 2026-11-11 every landing that
touched a transcript, a template, a macro or the Kotlin compiler front end re-harvested it, which
was most landings, and cost each of them one to three minutes (ten on a loaded machine). No landing
runs `jvmTest`, so nothing on a landing could catch what that bought.

Now a landing re-harvests only when the harvester itself (`CompletionCorpus.kt`,
`CompletionCorpusHarvest.kt`) or the committed fixtures change, and this routine catches up the
rest once a night. It works in its own worktree, `$WORKTREE_BASE/completion-corpus-nightly`, reset
to local `main` each run, and runs `AFIELD_HARVEST_WRITE=1 ./gradlew checkCompletionCorpusFixtures`.
If the fixtures changed it commits them on `nightly/completion-corpus-<date>` and hands that branch to
the landing queue. It is scheduled at 04:15 so the refresh has landed before nightly CI starts at
05:02 and its `docsGates` checks the corpus. Its last line is `CORPUS: <verdict>`.

Between runs the committed corpus can trail `main` by up to a day, so a `./gradlew check` or
`docsGates` in the daytime may report `checkCompletionCorpusFixtures` drift that is nobody's
mistake. Run this routine (`tools/routines.py run completion-corpus`) to settle it.

## When an agent runs it

Run `zsh $REPO/routines/completion-corpus/refresh.sh` from `$REPO` as a background command and
report its `CORPUS:` line. A harvest failure prints the log's last lines and the path to the full
log; report those rather than retrying. If it enqueued a branch, `uv run python tools/land.py mine`
from the worktree says whether it landed.
