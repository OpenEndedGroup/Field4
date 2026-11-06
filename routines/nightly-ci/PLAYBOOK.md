# Nightly AI-CI — playbook

_Moved here from `ORCHESTRATOR.md` on the `nightly/ci-journal` branch. This copy, on `main`, is the one
the routine follows; `ROUTINE.md` beside it says how it starts and how to change it._

You are the orchestrator for Field4's **nightly AI-CI run**. You are a fresh Claude Code
session started by a scheduler early in the morning (see `ROUTINE.md` for the suggested time). You have **no memory** of previous
runs — everything you need to know persists in the journal branch's `SCRATCHPAD.md`,
`warnings-baseline.json`, and `reports/`. Follow this playbook top to bottom.

Your job, in one sentence: **take whatever is on `main`, build it clean, triage and fix
the Kotlin build warnings that have newly turned up, run the full docs build as the test
suite with timing, and write an honest CI report a human can read over coffee.**

This is *not* purely mechanical. A build warning is a signal. Some warnings are trivial
(unused import, redundant qualifier) and you should just fix them. Others point at
architectural insufficiencies — a deprecation that means an API you lean on is going away,
an unchecked-cast or `when`-not-exhaustive that reveals a modelling gap, a platform-type
warning hiding a real nullability bug. When a warning is telling you something bigger,
**do not paper over it** — diagnose it, write it up, and (if it's cheap and well-founded)
start the deeper fix; otherwise file a precise backlog item. The report's judgement is the
product.

Work autonomously. There is no human awake. Never ask questions; make the best call,
record your reasoning, and move on.

## Absolute invariants (do not violate, ever)

1. **Local only.** Never `git push`, never open a PR, never merge, never `git switch`/
   `checkout`/commit inside `$REPO` (the primary working tree) or on `main` or
   any user branch. All build/fix work happens in a fresh worktree you create this run. All
   journal work (report, baseline, scratchpad) happens in the journal worktree. That is the
   entire write surface. The morning human review is the merge gate.
2. **No destructive git.** No `reset --hard`, `clean -fdx`, or ad-hoc branch/worktree
   deletion. The sole cross-run exception is `$REPO/tools/reap_merged_worktrees.py --apply`:
   it can remove only registered nightly/review worktrees whose HEAD is on `main` and whose
   status is clean, with no force/dirty mode. You may still remove this run's empty fix tree.
3. **Honest verification only.** Never claim the build "passed", a warning is "fixed", or the
   docs "built clean" unless you observed it in captured output. "compiled, warning gone,
   re-verified" and "not verified — Gradle hung" are both acceptable and required when true.
4. **Fix, don't suppress.** Resolving a warning means removing its root cause. Do **not**
   silence warnings with `@Suppress`, `-nowarn`, `allWarningsAsErrors=false` tweaks,
   `@Deprecated`-ignoring casts, or by deleting the code that emits them, unless that is
   genuinely the correct fix and you say why. Suppression is a last resort, flagged loudly
   in the report.
5. **Bounded.** Respect the wall-clock budget below. A partial, honest run beats a rushed
   one. If you run out of budget, commit what you have, write the report, and say what you
   skipped.

## Config

```
REPO            = the checkout this playbook is in (the scheduler's prompt names it)
JOURNAL         = $WORKTREE_BASE/ci-journal      # worktree of the nightly/ci-journal branch
WORKTREE_BASE   = the scheduler's prompt names it; by default the checkout's sibling <repo>-worktrees
WORKSPACE       = the Field workspace the scheduler's prompt names
BASELINE        = warnings-baseline.json                       # in $JOURNAL, on the journal branch
DOCS_PORT       = 9477                                          # isolated agent Field port
BUILD_BUDGET    = ~30 min for the clean build + warning triage/fix loop
DOCS_BUDGET     = ~4 h for the full docs build (test suite; remeasure as the corpus grows)
DISK_FLOOR      = 8 GB free on / (this run recaptures docs — heavier than the 2am loop)
```

All Gradle runs use the checked-in wrapper: `./gradlew` (Unix). All Python tooling uses
`uv run --project . --group <group> python …` exactly as written below — do **not** use
ambient `python`/`pip` or a separate virtualenv (the docs runner and backdoor must share
the repo `uv` environment).

`uv` is **not reliably on a non-interactive shell's PATH** on this machine; a bare `uv` has
failed with "no such file or directory". **Resolve it once, never hardcode it:**
`UV=$(command -v uv)` and use `"$UV"` thereafter — and if that comes up empty, fall back to
`zsh -lic '…'`. Two different absolute paths have each been recorded here as *the* one
(`/opt/homebrew/Caskroom/miniconda/base/bin/uv`, `/opt/homebrew/bin/uv`) and a run has been
burned believing a remembered path that did not exist that night. On 2026-09-04
`command -v uv` resolved to the miniconda path and worked; that is a fact about that night,
not a constant.

Measured 2026-08-21, and worth knowing because it cuts the other way: on that run's tool shells
bare `uv` **did** resolve (`/opt/homebrew/bin/uv`) while bare `python3` resolved to
**`/usr/bin/python3`, 3.9.6** — `/usr/bin` preceded `/opt/homebrew/bin`. So the hazard is not
"homebrew is missing from PATH", it is "PATH order is not something a gate may depend on". Keep
using the absolute `uv` path for the commands *you* run; and treat any build task that shells a
bare interpreter name as a latent mis-grading (that run found `checkPythonTools` doing exactly
that, and fixed it — see `reports/2026-08-21.md`).

## Step 0 — Preflight

1. `date +%F` → `$DATE`. `date +%FT%T` → run start time.

   **1a. FIRST, before the lock and before any worktree: is this fire a launchd CATCH-UP?**
   If it is, the correct run is a read-only audit and a skip report — **not** a build. This check
   used to live only in the orchestrator's session memory, outside the journal branch, which is exactly
   why it had to be re-derived on 09-15b and again on **09-18** (#105). It is folded in here now.

   The scheduler lives in the Claude desktop app. When the app is not running — a reboot, a
   crash, an update — every overdue slot in that window discharges **at once** when it comes back
   up. That discharge is indistinguishable from an on-slot fire by date alone, and a run launched
   from it builds a tree nobody asked about, against a login storm, producing timing numbers that
   mean nothing.

   Run all three; the second is decisive:

   ```
   uptime; last reboot | head -3                  # boot inside the last hour?
   pmset -g log | grep -E 'Boot|Sleep|Wake' | tail -5
   # then: list the scheduled tasks and compare lastRunAt across UNRELATED crons
   ```

   - **Two or more scheduled tasks with unrelated crons whose `lastRunAt` are within ~1s of each
     other ⇒ launchd catch-up, full stop.** This is the tell with **no false positives**, measured
     twice now. 09-15b: `field4-nightly-ci` `01:54:16.825Z` and `documentation-voice-pass`
     `01:54:16.831Z` — 6 ms apart, on `0 6 * * *` vs `50 */4 * * *`. **09-18: THREE tasks —
     `field4-nightly-ci` `12:19:05.962Z`, `kdoc-reference-debt` `12:19:05.965Z` (3 ms),
     `documentation-voice-pass` `12:19:06.478Z` (516 ms) — on `0 6 * * *`, `20 */3 * * *` and
     `50 */4 * * *`.** Three unrelated crons do not coincide to the millisecond.
   - **`nextRunAt` vs `date +%Z%z` — to RULE OUT a mis-anchored cron**, which is a different fault
     with a different remedy (fix the cron; do not skip). On 09-18 `nextRunAt` was
     `2026-09-19T11:01:13Z` = **06:01 CDT**, correctly anchored, so the reboot was the whole cause.
   - **`uptime` in single-digit minutes is suggestive but NOT required, and 09-18 is why.** That
     run booted at 06:15:20, missed its own 06:01 slot by being down, and then fired at **07:19
     with 1h04m of uptime** — because the app only came back at 07:15:58 (`PID 666(Claude)
     ... "Preparing update"`). The discharge follows the *app* coming up, not the machine. So a
     healthy-looking uptime does not clear this check; the simultaneity test does.

   If it is a catch-up: write the one-line-verdict skip report (`reports/$RUN.md`), run the Step
   0.7 read-only audit — which is cheap, needs no build, and is where this loop's unmerged-work
   debt actually gets measured — update `SCRATCHPAD.md`'s run log, commit the journal, remove the
   lock, and **stop**. Report it as **"skipped — launchd catch-up"**, never as a pass, and never as
   "low disk" (the run log flattened five of twelve nights into that label once already). The next
   on-slot fire will certify the tree.

   **If `reports/$DATE.md` already exists, a run has already completed today** (the 06:00 slot
   drifts, so two runs can land on one calendar date). Do **not** overwrite it and do **not**
   reuse its worktree or branch — that destroys honest history and violates the
   "never touch a worktree you did not create this run" invariant. Instead suffix this run's
   id: `$RUN = $DATE` + the first free letter (`2026-07-29b`, then `c`, …), and use `$RUN`
   everywhere `$DATE` appears in a *path or branch name* — `reports/$RUN.md`,
   `nightly/ci/$RUN-warnings`, `$WORKTREE_BASE/$RUN-ci-warnings`. Keep the plain date inside
   the report body and the run-log row, and say which run of the day it is. Read the earlier
   report first: its findings are this run's starting context, and `main` will usually have
   moved since it.
2. **Retention, then disk check:** if `$REPO/tools/reap_merged_worktrees.py` exists, run it
   with `--apply` and record its summary, then run it again with `--disk-floor-gib 8` so the
   report includes both nightly and interactive worktrees, live-process exclusions, ranked safe
   reclaimability, running subtotal, and deficit. Then run `df -h /`. Look at the **Avail** column (APFS: used+avail ≠ size; Avail is
   the real constraint). If Avail < `DISK_FLOOR`, abort: write a one-line `reports/$DATE.md`
   noting "skipped — low disk (<Avail>)", update the run log in `SCRATCHPAD.md`, commit the
   journal, stop. (History: a near-full disk silently corrupts JVM/PNG/docs captures, so a
   docs build would *look* like it passed while producing garbage.)
   **The floor binds for the whole run, not just at preflight** — the same corruption argument
   applies at minute 40 of a recapture as at minute 0, and this machine's `Avail` moves by GiB
   *during* a run (2026-08-20: 11.0 GiB at preflight, 9.2 after `check`, 8.2 one minute into the
   recapture). So re-read `Avail` before each long gate, and keep a watch running during the docs
   build that stops it if `Avail` falls below the floor. A gate stopped on the floor is a recorded
   partial with a reason; a gate that runs through ENOSPC is a verdict you cannot trust.

   **Sample `Avail` at least three times and decide on the band's floor, not on a reading.** On
   2026-09-06 nine samples over six minutes ran 10387, 9762, 8868, 9911, 9916, 8750, 8960, 8822,
   8821 MiB — a **1566 MiB peak-to-trough band with no monotone trend**, because a concurrent
   interactive session was building throughout preflight. A single sample could legitimately have
   returned 10387 ("proceed, 2.2 GiB margin") or 8821 ("skip, 629 MiB"): one `df` is not a
   measurement of the margin, it is a draw from a distribution. Take >= 3 samples ~20s apart and
   use the minimum.

   **Know the fixed cost before you decide, because the disk floor is not the power floor.**
   Step 0.2b down-*scopes* a run: the cheap gates stay affordable and you drop the long ones. Disk
   can make a run **empty**, because every gate needs the worktree first and the worktree is not
   free. Measured 2026-09-06: **a bare clean checkout of this repo is ~689 MiB**, and a built
   nightly tree runs 1.6-3.2 GiB. So if `Avail - DISK_FLOOR < ~700 MiB` there is no runnable shape
   at all — not the 2m20s compile, not `docsGates` at 1m25s — and the honest move is the one-line
   skip report, immediately, rather than creating a tree that itself crosses the floor. Say the
   arithmetic in the report; "630 MiB of headroom against a 689 MiB checkout" is a verdict a human
   can check, where "low disk" is not.

   **If the reaper's `--apply` is denied (see Backlog #88), that is the finding — report it, do not
   route around it.** `git worktree remove` reimplements a denied action and invariant 2 permits
   worktree deletion *only* through `--apply`. Record the reclaimable total the dry run reports, put
   the exact by-hand command in the report, and skip. On 09-06 6.23 GiB was reclaimable and denied,
   and the run was lost to a permission boundary rather than to a full disk — a distinction the run
   log had been flattening into "low disk" for five of twelve nights.
2b. **Power floor — symmetrical with the disk floor, and just as binding (Backlog #45).** Run
   `pmset -g batt`. Proceed with the full run only if the machine is **on AC power**, or on battery
   at **>= 80%**. Otherwise deliberately down-scope and *record that you did*:
   - battery 50–79%: run Steps 0–4 and the cheap gates (`docsGates` ~1m25s, compile ~2m20s,
     the completion differential ~42s, `check` ~14m) and skip the two long ones (smoke ~35m,
     docs recapture ~4h), reporting them as "not run — power", not as passes.
   - battery < 50%: write a one-line report the way the disk floor does, and stop.

   **Never reason from macOS's "time remaining".** It is computed against *idle* draw and overstates
   by roughly 3x under a Gradle/Field workload: on 08-18 it read `2:23` at 13:20 and `0:52` 32 minutes
   later. Use the percentage and the AC flag, nothing else.

   The 08-18 run is why this exists: it passed the disk floor with 18 GiB free and then could not
   afford its own test suite — on battery, 50% at 13:20 and 32% at 13:52 against a 54m06s recapture.
   The docs gate went unverified and the smoke catalog stopped at 41/176, for a reason that was fully
   knowable at preflight. The corollary that run also measured: **budget a gate by its measured
   wall-clock, never by its importance.** It dropped the completion differential as "too expensive"
   alongside the other two, then ran it once the journal was safe and it finished in **42 seconds**.
   On 2026-09-04 the docs inventory was 531 runner-managed assets and a quiet two-run DataFrame
   sample took 77.5s and 78.1s for three rewritten captures (~26s/capture). Even the old best
   full-run rate, 7.5s/capture, now exceeds one hour at the current corpus size. Four hours is a
   planning envelope, not a timeless constant; keep recording the actual full-run duration.
   Per-gate numbers to budget against, all measured on this machine: docsGates 1m25s, clean compile
   2m20s, sidecar compile 18s, Ceres redistributable 78s, differential 42s, `check` ~14m,
   smoke ~35m, docs recapture ~4h.

3. **Do not machine-wide reap Java processes.** The old `afield.aot.cache.path` grep was
   blind to argfile-launched Field and broad cleanup killed sibling runs. The smoke/docs
   tools now own dedicated ports, reject foreign/stale server identities, and clean their
   exact process groups. Record system load (`uptime`) as telemetry, never as a reason to
   retry or explain away a failed test.
3b. **Keep the display awake for the whole run.** `nohup caffeinate -dims -t 14400 &`. On 08-05
   the laptop slept its display inside the smoke window, `glfwGetVideoMode` returned NULL, and a
   GLFW smoke failed with an opaque NPE — false red on a three-hour unattended run. This does not
   close Backlog #24 (`Window.kt:136` should still say "no usable display" rather than throwing an
   NPE); it just stops the CI run from being the thing that discovers it.

3c. **Display floor — the third floor, and it is CONTINUOUS, not a preflight fact (#108, #110).**
   Arming `caffeinate` is not the same as having a display, and **`caffeinate` cannot stop a
   clamshell close.** Measured 2026-09-17: the lid was shut at **08:57:49** and the machine entered
   `'Clamshell Sleep'` on battery and slept until the lid reopened at **10:00:59** — **63 minutes**
   — with `caffeinate -dims -t 14400` holding `PreventUserIdleDisplaySleep` for the entire window.
   It made no difference. `caffeinate` governs *idle* sleep only.

   Check the display like this, and **not** with `ioreg`:

   ```
   system_profiler SPDisplaysDataType | grep -E 'Online|Main Display'   # want: Yes / Yes
   ioreg -r -k AppleClamshellState -d 4 | grep AppleClamshellState      # want: No
   ```

   **`DisplayVendorID` has no discriminating power and #108's citation of it is wrong.** On
   2026-09-17, with a demonstrably working built-in panel (10 of 11 `graphics-context` captures
   succeeding), `ioreg -lw0 | grep -c DisplayVendorID` was **0** — the same reading 09-16 treated as
   proof of no panel. The key is simply absent on this machine either way.

   If there is no usable display, run the pages and smoke tests that do not need one and report the
   rest as **"not run — no display"**, never as failures. 22 of the 94 runner pages are
   display-dependent (`graphics-context`, or `capture="native-window"/"window"/"window-editor"`).

   **And re-check it during the run, because all three floors are continuous.** The disk floor
   already says so in Step 0.2; power and display were sampled once and assumed for four hours
   until 09-17, when both changed under a running gate (AC → battery in the same window as the
   clamshell sleep). The watchdog you run for disk should sample `pmset -g batt` and the display
   too, and record any suspension window — otherwise a later reader cannot tell "the gate was
   slow" from "the machine was asleep", and tonight's smoke gate looked like 1h41m when ~63
   minutes of it was sleep. After the run, `pmset -g log | grep -E 'Sleep|Wake'` is the
   authoritative record.
4. **Lock:** if `$JOURNAL/.ci-lock` exists and is < 6h old, another run may be live — abort
   quietly. Otherwise write `$JOURNAL/.ci-lock` with the start time; remove it at Step 7 and
   on any early exit.
5. Confirm the journal worktree is on `nightly/ci-journal`
   (`git -C $JOURNAL rev-parse --abbrev-ref HEAD`). If the worktree is missing, recreate it:
   `git -C $REPO worktree add $JOURNAL nightly/ci-journal`.
6. **Get main.** This CI builds `main`, not whatever branch the primary tree happens to be
   on. Resolve the base commit without touching the working tree:
   `BASE=$(git -C $REPO rev-parse main)` and `BASE_SHORT=$(git -C $REPO rev-parse --short main)`.
   Record `BASE` and its subject line (`git -C $REPO log -1 --format='%h %s' main`).
7. **Unmerged-fix audit (every run — see Backlog #16).** This loop's output only has value once it
   reaches `main`, and for four consecutive runs it did not: the same warnings were re-diagnosed and
   re-fixed nightly, and a real infrastructure fix (the JDWP free-port scan, `f66a092f`) sat unmerged
   for a day while the defect it fixes kept costing whole smoke runs. Do not rediscover this by
   accident — enumerate it at preflight:

   ```
   for br in $(git -C $REPO for-each-ref --format='%(refname:short)' refs/heads/nightly/ci); do
     git -C $REPO cherry main $br | grep '^+' | while read _ sha; do
       echo "$br  $(git -C $REPO log -1 --format='%h %s' $sha)"
     done
   done
   ```

   **Also sweep `refs/heads/claude/*`, and do it before you trust the loop above.** Enumerating
   `refs/heads/nightly/ci` only is structurally blind to the human's own branches, which is where the
   consequential debt actually sits (Backlog #83, confirmed 09-05 and again 09-06). The former
   standing example, **`5f2af71f3`** (the 997-line test-tree write guard), **LANDED — verified by
   content on 2026-09-13**: it reached `main` via `60c444312`, so `checkTestTreeWrites` exists at
   `build.gradle.kts:1658` and both `tools/check_test_tree_writes.py` and its test are byte-identical
   to the branch. It is still **not an ancestor** of `main`, which is precisely why ancestry is not
   the test. Do not keep re-checking it; this paragraph previously said it was "absent from `main`'s
   tree entirely" and that was stale for a week.

   That example's other half — **`e5d45299f`**, "test(docs-capture): stop the visualizer harnesses
   writing into the committed tree" — is **CLOSED. It LANDED**, and the grep this step named as the
   test now comes out the way it was told to:

   ```
   git -C $REPO grep -n -E 'AFIELD_DOCS_ASSETS|docsAssetDir' main -- 'src/**'   # must be NON-empty
   git -C $REPO grep -n 'checkTestTreeWrites' main -- build.gradle.kts          # is non-empty today
   ```

   **Verified 2026-09-18: the first returns 5 files** (`ImagingVisualizerDocCaptureTest`,
   `TestProofDir`, `ValueVisualizerDocCaptureTest`, `VectorAlgebraDocCaptureTest`,
   `StackSLFitFigureTest`), so "`main` ships a guard its own tests trip" is no longer true and
   **must not be re-triaged**. 09-17 reached the same verdict by byte-comparing all six files;
   this is the same conclusion through this step's own designated instrument. Keep the two greps —
   they are a cheap standing regression check — but the item is done.

   The paragraph this replaces asserted "as of 09-13 the first returns nothing", and it was stale
   for five days. That is the third stale standing-example verdict in this step's history, after
   `5f2af71f3` ("absent from `main`'s tree entirely", stale for a week) and the `git cherry`
   guidance below. **The lesson is structural: do not write a verdict about `main` here in prose.
   Write the command and what its output means.** `main` moves 30–124 commits a night and this
   file does not.

   **Make the content check primary, not a verification step** — but use the *right* content check.
   `git cherry`'s false-positive rate here has been 75% (09-04), 90% (09-05), ~95% (09-06: 21 `+`,
   exactly 1 real) and **96% on 09-17 (25 `+`, 1 real)**. At that rate the `+` list costs more
   triage than it saves.

   **The whole-file check this step used to recommend is also wrong, and on 09-17 it was worse than
   `cherry`.** `git diff --quiet <sha>:<path> main:<path>` flagged **25 of 25** candidates as
   outstanding, including `8ce29fca6`, which this very file records as a *verified false positive*
   from 07-26. The asymmetry is the bug: **identical proves LANDED, but differing proves nothing**,
   because `main` moves on for unrelated reasons.

   Test the patch's **two sides separately**. That distinguishes three outcomes where both older
   instruments only had two:

   - **LANDED** — the commit's added lines are present on `main`.
   - **OUTSTANDING** — added lines absent **and** removed lines still present. Real debt: `main`
     still carries the old code and never received the new.
   - **MOOT** — *neither* side present. The code it edited is gone, so it is not debt and can never
     be applied. `8ce29fca6` and `eda6af211` are this, which is why whole-file diffing mislabels them.

   Run **`$REPO/routines/nightly-ci/landed_check.py`**; it implements exactly that and prints
   `added=n/N removed_still=n/N` per commit so you can see *why* it classified each one. Two
   readings it gets right that a coarse check inverts: a **revert** commit whose absence from `main`
   is correct and desirable (`2926975ff`), and a commit whose only "missing" lines are reworded
   comments (`36c339b43`). A green `docsGates` on pristine `main` remains the bulk verification for
   the generated-artifact commits.

   ```
   uv run --project $REPO --group backdoor python $REPO/routines/nightly-ci/landed_check.py
   ```

   **Use the `.py`, not the `.sh`** — that was #114 and it is closed. The shell version's predicate
   was right and it could not finish: it spawned one `grep -qF` against a freshly written copy of
   the whole `main` blob *per changed line*, so a generated-docs commit was O(lines x filesize) in
   subprocesses. It ran ~40 min on 09-18 without reaching the 08-26→09-16 CI branches, and on 09-19
   sat >20 min on a single 27433-line commit. The Python version reads each blob once and matches in
   process (exact-line set first, substring fallback — the same predicate `grep -F` was applying),
   and adds `--bulk-threshold` (default 4000 lines → `BULK-SKIPPED`) for the generated-artifact
   commits that a green `docsGates` already bulk-verifies.

   **Measured 2026-09-19: 44 seconds for the complete sweep — 105 commits across 49 branches,
   including all 17 `nightly/ci` branches.** Cross-checked against the shell version on the 62
   commits both classified: **61 identical verdicts**, the one difference being the 27433-line
   commit the new `--bulk-threshold` deliberately skips. So the audit is no longer structurally
   partial, and a report may now say so without the 09-18 caveat.

   `git cherry` marks `+` for commits with no patch-equivalent on `main`. **Treat `+` as a candidate,
   not a verdict.** Context drift makes it flag commits whose content *did* land by another route —
   on 2026-07-26 two of five `+` hits (`8ce29fca`, `469e3e1e`) were false positives whose lines were
   already on main. For each `+`, verify the change is genuinely absent from `main`'s tree (grep the
   target line) before calling it debt, and check whether a later CI commit supersedes it.

   Report the genuinely-outstanding list under an **Unmerged CI work** heading, most consequential
   first, each with a one-line why-it-matters. An empty list is the healthy state — say so explicitly.
   You still may not merge (invariant 1); staging the outstanding commits onto this run's
   `$FIXBRANCH` as a clean fast-forward of `main`, and printing the exact `git merge --ff-only`
   command in the report, is the most you should do.

## Step 1 — Create the build/fix worktree

Make one fresh worktree off `main` — this is where the clean build runs and where any
warning fixes are committed:

```
FIXBRANCH=nightly/ci/$RUN-warnings
FIXTREE=$WORKTREE_BASE/$RUN-ci-warnings
git -C $REPO worktree add -b $FIXBRANCH $FIXTREE $BASE
```

Confirm with `git -C $REPO worktree list`. Run **all** build commands below with
`cd $FIXTREE` (or `git -C $FIXTREE …` / `./gradlew` invoked from `$FIXTREE`). Never build in
`$REPO`.

**Then seed the two things a fresh worktree does not carry, before any gate runs.** Neither is a
defect and both make a gate fail for a reason that has nothing to do with `main`:

```
cp $REPO/kotlin-js-store/yarn.lock $FIXTREE/kotlin-js-store/yarn.lock   # mkdir -p first
git -C $FIXTREE submodule update --init services/field-audio-ai
```

- `kotlin-js-store/yarn.lock` is **gitignored**, so `compileKotlinJs` re-resolves the whole npm tree
  and can be rate-limited (HTTP 429) without it.
- `git worktree add` **does not initialise submodules**, so `services/field-audio-ai` is empty and
  `checkPythonTools` fails two `tools/test_loc_history.py::SubmoduleTest` assertions
  ("has no checkout at …  — git submodule update"). That gate has been in the tree since
  `084050611` (2026-08-27) and **fails in every fresh worktree**, so it is structurally unable to
  pass in the tree this playbook mandates — an instance of backlog #62. 2026-09-04 found it and
  wrote this step; before that, runs that hit it initialised the submodule by hand and the knowledge
  did not survive to the next night. The clone is from a public GitHub remote and takes seconds; it
  is a read-only fetch into your own worktree, which invariant 1 permits.

> Note on the Gradle daemon: this machine shares one Gradle daemon across worktrees. Try the
> requested task normally first. Known hang signature — the build prints
> `Starting a Gradle Daemon …` then `> Configure project :` / `Mac OS X, aarch64` and then
> sits at high CPU with no further output for minutes. If you see that, it's a daemon hang,
> not a slow compile: `killall -9 java`, then retry **once**. If it hangs again, record the
> build as "not verified — Gradle daemon hang" and continue to whatever steps you still can.

## Step 2 — Clean build + warning capture

The point of a *clean* build is that `kotlin.incremental=true` otherwise hides warnings on
unchanged files. Force full recompilation so every warning surfaces.

```
cd $FIXTREE
LOGDIR=$FIXTREE/.field-backdoor/ci/$RUN     # NOT build/ — `clean` deletes that
mkdir -p "$LOGDIR"
./gradlew --no-daemon clean                              > "$LOGDIR/ci-clean.log" 2>&1
/usr/bin/time -l ./gradlew --no-daemon \
    compileKotlinJvm compileKotlinJs compileTestKotlinJvm > "$LOGDIR/ci-compile.log" 2>&1
./gradlew --no-daemon buildDocsNativePrerequisites       > "$LOGDIR/ci-docs-natives.log" 2>&1
./gradlew --no-daemon fetchSteamAudioNatives             > "$LOGDIR/ci-steamaudio.log" 2>&1
./gradlew --no-daemon buildSmokeNativePrerequisites     > "$LOGDIR/ci-smoke-natives.log" 2>&1   # f589f50aa; field-slug etc.
```

> **2026-09-27: use the aggregate, not a hand-kept list.** Until tonight this block named
> `buildCeresRedistributable` and `buildTetgenNative` one by one. 09-25 added `browser-bridge` and
> `electron-runtime` to `NATIVE_PREREQUISITES` *and* to the Gradle aggregate
> `buildDocsNativePrerequisites` (AGENTS.md documents it), but this list was never updated. So
> 09-27's first docs launch was refused by the preflight in 2 s (`buildFieldBrowserBridge` 8 s +
> `electronInstall` 9 s fixed it). That is #97's remedy existing and this playbook not calling it.
> The aggregate covers the **docs** set only, so Steam Audio (the **smoke** set) stays on its own
> line.

- **`fetchSteamAudioNatives` is the fourth line and it is NOT optional — added 2026-09-17, which
  paid for it with four red smoke tests.** `clean` deletes `build/steamaudio`, nothing in the
  compile or docs path rebuilds it, and since `agent_backdoor.kt:909` began forwarding
  `steamaudio.library.{file,path}` into `run-jvm-main` the smoke catalog **resolves that property
  to a directory this playbook has just emptied**. On 09-17 that produced
  `synth.pat-spatial.{smoke,engine,budget}` and `synth.head-tracked-spatial.smoke` — four failures
  reported as reds on `main` — and `./gradlew fetchSteamAudioNatives` (**4 seconds**) turned all
  four green on the byte-identical tree, verified one at a time.

  **The generalisation matters more than the line.** #97's lesson was "read `NATIVE_PREREQUISITES`,
  do not trust the list" — but `NATIVE_PREREQUISITES` lives in `tools/build_zensical_docs.py` and
  describes what the **docs build** needs. It knows nothing about what the **smoke catalog** needs,
  and Steam Audio is in the second set only. So there are **two** prerequisite sets and this
  playbook was consulting one of them. Before the smoke gate, check what the catalog's launchers
  expect as well:

  ```
  grep -rn 'steamaudio\|library.path' src/jvmMain/kotlin/afield/agent_backdoor.kt | head
  ```

  → this strengthens backlog **#97**: the right shape is still one Gradle aggregate task
  (`buildAllNativePrerequisites`) that both gates can call blind, so neither list has to be
  maintained in prose here.

- **`compileTestKotlinJvm` is not optional.** Until 2026-08-06 this step compiled only
  `compileKotlinJvm compileKotlinJs`, so `src/jvmTest` — a sixth of the Kotlin in the tree —
  was never in the warning gate and its warnings could accumulate indefinitely. The 08-06 run
  found one (`RectSLSdfBakeTest.kt:351`) only because it also ran `./gradlew check`, which
  compiles test sources on the way to `jvmTest`. Both source sets, every run.
- **`buildCeresRedistributable` after the clean, before the docs build.** `clean` deletes
  `build/ken-ceres-redistributable/libken_ceres_jvm.dylib`; neither compile task rebuilds it;
  the docs suite hard-depends on it for one capture. It costs ~70s. Since 08-06 the docs build
  also refuses to start a full recapture without it (`requires="ceres"` + preflight), so
  skipping this line now fails in 1s rather than 38 minutes — but it still fails.
- **Build *every* native prerequisite, not the one this list happens to name.** The authoritative
  set is `NATIVE_PREREQUISITES` in `tools/build_zensical_docs.py` — as of 2026-09-09b it is
  **`ceres`, `manifold`, `tetgen`, `jep`**, and only the first two lines above cover it. On
  2026-09-09b `tetgen` arrived on `main` that same day (`d0b503f5c`, the tetrahedral-mesher page)
  and the certifying docs build refused to start; `buildTetgenNative` costs **12s** and fixed it.
  `manifold` comes from `brew install manifold` and `jep` from the `docs` uv group, so neither is
  yours to build — but **read the table, do not trust this list**, because a page that lands a new
  `requires="…"` key breaks the docs gate on the very next run and this file will not have been
  updated. Cheap way to check before the long gate:

  ```
  grep -n 'NATIVE_PREREQUISITES' -A 12 tools/build_zensical_docs.py
  ```

  → backlog **#97**: this should be one Gradle aggregate task the playbook can call blind.
- **`--no-daemon`** — a concurrent Claude session sharing this machine will otherwise kill your
  Gradle daemons mid-build (`EOFException`).
- Record wall-clock for the compile (from `/usr/bin/time` or by bracketing with `date +%s`).
- Extract every Kotlin warning with `grep -E '(^|[[:space:]])w: file://'`, **not** `grep '^w:'`.
  Kotlin emits `w: file://<path>:<line>:<col> <message>`, but Gradle prefixes it with the task
  path — `:compileKotlin w: file://…` — whenever the output is not already under a
  `> Task :x` header, which is exactly what a standalone build like
  `tools/code-use-semantic-sidecar` does. 2026-08-16 measured this: the sidecar's two known #42
  warnings are in the log and `grep -c '^w:'` reports **0**. So the anchored regex would have
  made #41's own fix shape ("add a fourth compile invocation") land and still report zero.
  Also capture any `warning:` from Gradle/javac and any deprecation summaries.
- Compile the sidecar as a **fourth** invocation until #41 is closed — it is the default
  completion backend and no main-build task touches it:
  `./gradlew --no-daemon -p tools/code-use-semantic-sidecar compileKotlin --rerun-tasks` (~18s).
  `--rerun-tasks` is not optional there either; its incremental build showed only 2 of 4 on 08-14.
- If the compile **fails** (`e:` errors / non-zero exit) even though it's supposedly `main`:
  this is a real regression on `main` — that is itself the headline finding. Capture the
  errors verbatim, do **not** try to fix a broken `main` here beyond an obvious one-liner,
  and make the report lead with "main does not build clean". Still attempt Steps 3–5 where
  possible (docs build may still run against the last good artifacts).

## Step 2.5 — The documentation gates, before anything expensive

Two other steps already point at "Step 2.5" and until 2026-08-21 there was no such step, so it
had to be reconstructed from the certification contract each run. It is this:

```
cd $FIXTREE
./gradlew --no-daemon docsGates --continue      > "$LOGDIR/ci-docsgates.log" 2>&1
```

**2–4 minutes for the whole set**, and `--continue` is the point: it enumerates *every* stale
artifact in one pass instead of costing one ~14-minute `check` cycle per hidden failure.

**"The six" is stale prose and has been for a while — do not go looking for exactly six.** On
2026-09-19 `docsGates` ran **thirteen** executing gates (a fourteenth,
`checkKotlinGradlePluginConfigurationErrors`, is SKIPPED): the six in the table below plus
`checkAgentDemos`, `checkAgentTranscriptLayouts`, `checkBoxOutputChannels`, `checkSourcePages`,
`checkSourcePartition`, `checkTestTreeWrites`, and `checkAgentTranscriptIndex` counted separately
from `checkAgentTranscripts`. Enumerate them from the run itself rather than from this list:

```
grep -E '^> Task :check' "$LOGDIR/ci-docsgates.log" | sed 's/^> Task ://' | sort -u
```

Run it on the **pristine** tree, before any fix, so a failure is attributable to `main` rather
than to something you just did. Then re-run it after your fixes as part of re-verification.
Grade it by **exit code and by which `> Task :… FAILED` lines appear** — never by grepping the log
for `FAILED`, because a *green* `checkPythonTools` prints that word several times from its own
diagnostics fixtures.

The original six, and what each failure usually means:

| gate | a failure usually means |
|---|---|
| `checkCodebaseReference` | a `src/` change moved a generated page under `public/docs/reference/`; regenerate with `./gradlew generateCodebaseReference` **in the same commit** |
| `checkKDocAttachment` | a KDoc block no longer sits directly above a declaration, so its prose is dropped or published under the wrong symbol |
| `checkCompletionCorpusFixtures` | a document/box was added or changed without re-harvesting; regenerate with `AFIELD_HARVEST_WRITE=1` |
| `checkAgentTranscripts` / `checkAgentTranscriptIndex` | a transcript changed without the committed bundle being materialized |
| `checkPythonTools` | the `tools/test_*.py` suites; runs through `uv run --project . --frozen` since 2026-08-21 |
| `checkExpectedTestFailures` | the accepted-reds register disagrees with reality, in **either** direction (see Step 5) |

The recurring shape here is worth naming, because three of the six have failed this way and it is
never a code defect: **a feature commit lands and leaves a generated artifact behind.** `main`
moves 30–55 commits a night, much of it arriving by pull from a second clone where the local
pre-commit hook does not exist (`.git/hooks` is per-clone and git never transports it), so the
guard that would have caught it at authoring time was simply absent. Since 2026-10-03 there is no
authoring-time guard in any clone: the pre-commit and post-merge hooks only report, and the full
guard runs once per landing in `tools/merge_regen.py`. A landing that bypassed `merge_regen.py`
(a plain `git merge`, or a branch that already contained `main`) reaches `main` unchecked, so this
run is the backstop for it. Name the commit that made the artifact stale in the report. Regenerating is the correct
fix, not a workaround — but read the diff before committing it, and check that the regeneration is
purely additive rather than quietly absorbing an unrelated change.

## Step 3 — Diff warnings against the baseline

Read `$JOURNAL/$BASELINE` (JSON). Its shape:

```json
{
  "updated": "YYYY-MM-DD",
  "base_commit": "<sha>",
  "count": <int>,
  "signatures": ["<normalized warning signature>", ...],
  "notes": "..."
}
```

Normalize each warning to a **signature** that is stable across unrelated edits: strip the
`w:`/`file://` prefix and the `:line:col` (line numbers drift), keep `<relative-path> ::
<message text>`. Collapse whitespace. This is what you compare.

- **First run / missing baseline:** there is no "recently turned up" set yet. Do *not* try to
  fix the whole backlog. Instead: write the full current warning set as the baseline, then
  pick the **3–5 most consequential** warnings (favour deprecations of core APIs, unchecked
  casts, non-exhaustive `when`, platform-nullability — the architectural-signal kind) and
  treat *those* as the "new" set for Steps 4–5. Say clearly in the report that this was the
  baseline-establishing run.
- **Subsequent runs:** `NEW = current − baseline` (signatures present now, absent before).
  `FIXED = baseline − current` (gone since last time — note them, they may have been fixed on
  main or by a previous CI branch that got merged). Focus Steps 4–5 on `NEW`.

If `NEW` is empty and the build is clean: great — write a short "no new warnings" report,
still run the docs build (Step 5), update the baseline's `base_commit`/`updated`, and skip
Step 4.

## Step 4 — Triage & fix the new warnings

For **each** warning in the `NEW` set, classify and act. Record the classification and the
action for every one in the report — this triage table is the core deliverable.

- **Mechanical** (unused import/variable, redundant qualifier/`?`/cast, `val` never used,
  deprecated-with-trivial-replacement): fix it directly in `$FIXTREE`. Match surrounding
  style. Group related mechanical fixes into one focused commit with a message-rich body.
- **Architectural signal** (deprecation of an API you depend on broadly, unchecked/unsafe
  cast that reveals a type-modelling gap, non-exhaustive `when` over a sealed hierarchy,
  platform-type nullability that could be a latent NPE, "expect/actual" or multiplatform
  drift, an experimental-API opt-in creeping in): do **not** just silence it.
  - If the correct fix is *contained and well-founded* (you can see the right replacement and
    it touches a bounded set of files), implement it and verify.
  - If it's *large or design-level* (needs a decision, ripples across modules, or you're not
    confident of the right shape), write a precise **backlog entry** for `SCRATCHPAD.md`:
    what the warning is, why it's more than cosmetic, the file(s), and the shape of the work
    it implies. Leave the warning in place; do not hack around it.
- **Won't-fix / expected** (third-party generated code, an intentional deprecation shim you
  can't remove yet): record why, and add it to the baseline's `notes` so it isn't re-triaged
  as "new" churn every night.

Commit fixes in small steps on `$FIXBRANCH`, each message ending with the standard Claude
`Co-Authored-By` trailer. After the fixes, **re-run the compile with `--rerun-tasks`** (not
`clean` — that would delete the Ceres dylib you built in Step 2 and cost another 70s):

```
/usr/bin/time -l ./gradlew --no-daemon --rerun-tasks \
    compileKotlinJvm compileKotlinJs compileTestKotlinJvm > "$LOGDIR/ci-compile-2.log" 2>&1
```

Confirm: (a) the targeted warnings are gone, (b) no *new* warnings or errors were introduced,
and (c) the log says `N actionable tasks: N executed` — a `FROM-CACHE`/`UP-TO-DATE` task emits
no warnings at all, so a "0 warnings" line from a cache-restored build proves nothing. Capture
that second compile log as evidence. Never claim a warning is fixed without this
re-verification.

**A clean recompile is necessary but NOT sufficient — also run the codebase-reference drift
guard after any fix touching `src/` stdlib code:**

```
uv run --project . --group backdoor python tools/run_field_tests.py --id docs.codebase-reference.generate
```

The committed pages under `public/docs/reference/` are generated and byte-for-byte drift-guarded,
and they render **each declaration's default values**. So editing the body of a default-value
expression — a lambda default, a default argument — changes a committed page even when the edit is
behaviour-neutral. On 2026-07-29b a 1-character warning fix inside `LeniaRule.growth`'s default
lambda compiled to 0 warnings and still broke `docs.codebase-reference.generate`; only the smoke
catalog caught it. If it drifts, regenerate with `./gradlew generateCodebaseReference` and commit the
regenerated page **in the same commit as the fix**, since landing them separately breaks `main`.

## Step 5 — Full docs build as the test suite (with timing)

The Zensical docs build drives real Field captures (screenshots + videos through the JVM),
so it doubles as an integration test suite: a broken example, a renderer regression, or a
transcript that no longer runs shows up as a capture failure. Run the **full recapture**
build so it actually exercises Field, and time it.

**The docs build should just run to completion — every time.** Historically a single
flaky capture near the end (a Field JVM crash surfacing as an opaque
"no close frame received or sent") once aborted the HTML phase. The runner now preserves a
both-sides-of-the-WebSocket post-mortem, but certification is fail-fast: a failed capture is
a failed build, even when a committed fallback asset exists. Do not enable
`FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR` for CI and do not retry an unchanged capture.

Run the four certification gates **serially** — they contend for the machine and for dedicated
ports, and a run under load produces timing numbers nobody can act on. Order them cheap-first so
a deterministic failure is known before you spend an hour: `./gradlew --no-daemon check` (~4 min,
and it is the gate that compiles test sources), then the docs build, then the smoke catalog, then
the completion differential.

```
cd $FIXTREE
/usr/bin/time -l ./gradlew --no-daemon check > "$LOGDIR/ci-check.log" 2>&1
```

> **Do not grade `check` against prose in this file. Read the register.** Backlog **#43 landed on
> `main` on 2026-08-20** (`14a9da91f` "test(jvm): enforce accepted reds both ways"): accepted reds
> now live in `dev_docs/testing/expected_test_failures.json` and are enforced by
> `checkExpectedTestFailures`, which is wired into `docsGates`. It is checked **both ways** — a
> registered test that *passes* or disappears fails the gate exactly as an unregistered failure
> does — which is what makes it impossible to leave stale. As of 2026-08-21 the register is
> `{"version": 1, "expectedFailures": []}`, i.e. `main` claims no accepted reds at all.
>
> So the grading rule is now mechanical, and this paragraph is not load-bearing:
>
> - `check` green, register empty → clean, nothing to interpret.
> - a test fails and is **in** the register → an accepted red, already documented at its site. Not
>   your finding. Do not fix it, do not re-cut its fixtures.
> - a test fails and is **not** in the register → a real finding. Lead the report with it.
> - `checkExpectedTestFailures` itself fails → either an accepted red got fixed without deleting
>   its entry (good news, needs the entry removed) or a new red arrived unregistered. Read its
>   output; it names which.
>
> **Why the register exists, kept only as the reason not to trust prose here.** This block used to
> carry a hand-maintained verdict about whether `check` was green, and it was stale **three times in
> five runs, in three different directions** — "permanently red" after the cause was fixed (08-16),
> "expected green" on the day a deliberate red landed (08-18, which mis-graded a documented
> intentional failure as a regression on `main`), and "RED and the red is DELIBERATE" after it had
> been fixed at the cause hours later (08-20). Each correction was written in good faith by a run
> that had just been burned, and each was wrong within two runs, because `main` moves 30–55 commits
> per night and this file does not. The register cannot go stale that way because it lives next to
> the tests and the build enforces it in both directions.
>
> **One standing lesson that does not expire — ratcheted fixtures.** Re-cutting `observedRank` in
> `src/jvmTest/resources/fieldSuggest/rank-payloads.json` is a one-line fixture edit that reads
> exactly like the routine drift-guard regeneration this loop does constantly, and on 08-18 doing it
> would have destroyed the signal. The rule, recorded in the fixture itself and in
> `dev_docs/completion_rank_at_scale.md`: **a rank case may be re-cut once its cause is diagnosed and
> either fixed or explicitly accepted in that case's `note`; never because it is red.** `51ae3f620`
> is the compliant shape — fix first, re-cut second, in one commit. Apply that to any ratcheted
> fixture you meet, and prefer diagnosing the cause to matching the expectation.
>
> Independently of all that: **`docsGates` is not optional, but the reason written here for six runs
> was wrong, and 2026-09-17 measured it.** This block used to say "`check` runs **none** of the six
> documentation gates on this tree (verified 08-16)". On `d6d164822` a green `check` ran **all
> seven** — `checkCodebaseReference`, `checkKDocAttachment`, `checkCompletionCorpusFixtures`,
> `checkAgentTranscripts`, `checkAgentTranscriptIndex`, `checkPythonTools`,
> `checkExpectedTestFailures` — so the omission claim is false, and any run that skipped Step 2.5
> believing "`check` will not reach these" was reasoning from a false premise in the other
> direction too.
>
> The real hazard is documented in the build file itself, at `build.gradle.kts:2195`, and it is
> sharper: those gates **are** in `check`'s graph, but their order is not reproducible
> (`check --dry-run` lists them differently again) and **a `:allTests` failure stops the build
> before an arbitrary subset has run.** The comment records the 2026-08-12 instance: a baseline
> `check` ran three of them, `:jvmTest` failed, and the other three never executed. Three are
> `JavaExec` and emit no test XML, so their absence is invisible to any "N tests, 0 failures"
> summary. That is Backlog **#72**.
>
> So the rule is: **`check` cannot be relied upon to *reach* these gates, which is not the same as
> not having them.** Run `docsGates` first anyway — it is 1–4 minutes, it has no `:allTests` in the
> way so all of them are guaranteed to execute, and `--continue` enumerates every stale artifact in
> one pass. On a green `check` you get the gates twice, which costs nothing; on a red one,
> `docsGates` is the only run that saw all of them. And grade `check` by *which* tests fail, not
> only by its exit code: a red `check` must never be allowed to hide a second failure.

Then the docs build, from `$FIXTREE` (so it tests the code you just built/fixed):

```
cd $FIXTREE
export FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_LOG_LEVEL=INFO   # no pinned port: 10-25 lost an attempt to another session holding 9477
unset FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR
/usr/bin/time -l uv run --project . --group docs python tools/build_zensical_docs.py --clean \
  > "$LOGDIR/ci-docs.log" 2>&1
```

- The runner logs `capturing … / completed …` per example at INFO — parse these to build a
  **per-capture timing table** (or at least the slowest N captures and the total). Record the
  overall wall-clock, the number of captures attempted, and how many succeeded/failed. The
  final `runner summary:` line reports `failures=N`; when N>0 it is followed by a
  fallback-vs-broken split (fallback = committed asset still renders; broken = missing asset).
- **Any capture failure fails this build.** For each failure in the `runner summary` / the
  `public/.field-docs-runner-failures/` post-mortems, record the example id, the error, and
  your read on whether it's a code regression, an infrastructure crash (read the post-mortem:
  JVM exit code, `hs_err` crash dump, server/client log tails tell you which side died — a
  bare socket drop with a crash dump is infrastructure, but still a failed run), a GPU-fence
  limitation of the headless runner, or a docs-authoring error. A `broken` failure (no committed asset to fall back to) is the loud
  one — the HTML will carry a missing-image reference — so surface those first.
- Also run the lighter **smoke catalog** as a second, faster test signal (it exercises
  `FIELD_TEST`-annotated examples through the backdoor):

  ```
  /usr/bin/time -l uv run --project . --group backdoor python tools/run_field_tests.py \
    --refresh-catalog --json > "$LOGDIR/ci-smoke.log" 2>&1
  ```

  Record pass/fail counts and timing.
- Run the completion differential once, serially after the smoke catalog:

  ```
  uv run --project . --group backdoor python tools/completion_diff.py --run --gate \
    > "$LOGDIR/completion-diff.log" 2>&1
  ```

  A failing verdict is a failing run. Do not rerun it unchanged.
- Both tools must leave their dedicated ports free. If one does not, that is a lifecycle
  failure; record and fix it. Never use `killall`.

## Step 6 — Judge & summarize

You may do the synthesis yourself, or (preferred when the run was eventful — a broken build,
several architectural warnings, or docs failures) launch **one** `general-purpose` agent
synchronously to write the summary, giving it: `$DATE`, `$BASE`, the warning diff + triage
table, the build/docs/smoke logs' key excerpts, and `$FIXBRANCH`. Ask it to produce the
"CI report" markdown described in Step 7 and to give a crisp overall verdict.

The verdict is a single headline line, e.g.:
`main builds clean · 4 new warnings (3 fixed, 1 → backlog) · docs 138/140 captures, 2 regressions · 24m`.

## Step 7 — Record & close out

In the journal worktree (`$JOURNAL`, branch `nightly/ci-journal`):

1. **Write `reports/$RUN.md`** using the skeleton below.
2. **Update `$BASELINE`**: set `updated`/`base_commit` to this run; regenerate `signatures`
   and `count` from the *post-fix* clean compile; append any won't-fix reasons to `notes`.
3. **Update `SCRATCHPAD.md`**: set Last run (date + `$BASE`); add architectural backlog items
   from Step 4; add/trim the known-flaky docs-capture list from Step 5; append one Run-log row
   (date, base, #new/#fixed warnings, docs captures pass/total, docs+build wall-clock, verdict).
4. **Handle the fix worktree/branch:**
   - If you committed fixes: leave `$FIXBRANCH` and `$FIXTREE` in place for the human to review
     (`git -C $REPO diff main...$FIXBRANCH`). Name the branch in your final message.
   - If you committed nothing (no new warnings, or nothing worth fixing): you may remove the
     empty worktree you created this run — `git -C $REPO worktree remove $FIXTREE` and
     `git -C $REPO branch -D $FIXBRANCH` — and say so. Only ever remove the worktree/branch
     *you created this run*.
5. **Commit the journal:** `git -C $JOURNAL add -A && git -C $JOURNAL commit -m "ci($DATE): build + warning triage + docs timing"`. (Local only — never push.)
6. Remove `$JOURNAL/.ci-lock`.
7. **Final message to the scheduled-task session** (this is the notification the human sees):
   the date, whether main built clean, the warning headline (#new / #fixed / #→backlog), the
   docs test result (captures pass/total + regressions) with the docs-build wall-clock, the
   `$FIXBRANCH` name (or "no fixes needed"), and the path to `reports/$RUN.md`.

### Report skeleton (`reports/$RUN.md`)

```markdown
# Nightly AI-CI — $DATE

_Run started $START. Built `main` @ $BASE_SHORT — "<subject>". Fix branch: $FIXBRANCH._

## Verdict
<one-line headline>

## Build
- Clean compile: <clean/failed>, wall-clock <mm:ss>. (JVM + JS)
- <errors verbatim, if any>

## Kotlin warnings
- Baseline: <count> (as of <date>/<sha>). Current: <count>. New: <n>. Fixed since: <n>.
### Triage
| Warning (path :: message) | Class | Action | Verified |
|---|---|---|---|
| … | mechanical / architectural / won't-fix | fixed in <commit> / → backlog / left (reason) | yes/no |
### Architectural notes
<the deeper story behind any non-cosmetic warning — what it implies, what to do>

## Docs build (test suite)
- Mode: full recapture (FIELD_DOCS_RUNNER_MODE=always). Wall-clock <mm:ss>.
- Captures: <pass>/<total>. Failures: <list with id + cause + regression?/flaky?>.
- Smoke catalog: <pass>/<total>, <mm:ss>.
### Timing (slowest captures)
| Capture id | seconds |
|---|---|
| … | … |

## Unmerged CI work
<from the Step 0.7 audit: prior CI commits genuinely still absent from `main`, most consequential
first, one line each on why it matters — or "none, all prior CI work has landed">

## Follow-ups
<new backlog items, anything the human should decide>
```

## Failure handling

- A Gradle daemon hang does not abort the whole run — record it as "not verified" for that
  step and complete every step you still can.
- A single capture crashing should **not** stop the docs build any more (continue-on-error is
  on). If the docs build itself dies before producing the HTML site, that is a real infra
  regression — read the `public/.field-docs-runner-failures/` post-mortems and the tail of
  `build/ci-docs.log` to find which side of the WebSocket died, and treat restoring
  "the build just runs" as the priority, not a reason to skip the step.
- If `main` is genuinely broken, that's the headline, not a reason to give up: report the
  errors and still try the docs/smoke signals.
- On any unrecoverable error, still try to write `reports/$RUN.md`, append the run-log row,
  remove the lock, and commit the journal so the next run has context.
