# AI4 as the lander's fixer, measured over history (2026-10-29)

The question: how often can AI4 (Field's own agent, `FIELD_AGENT=field`) do the job the lander's
external fixer did on real bounces? Every `Lander fixup:` commit on history is a case with a known
answer: the bounced tree (its parent), the failure the lander saw, and the edit that made it pass.
`tools/field_fixer_bench.py` replays those cases through AI4 and grades what it does.

## How a case is replayed

- **The case.** A fixup F is committed straight onto the bounced branch tip, so F^ is the tree the
  fixer was handed (the lander actually gave it main merged with that tip; F^ alone is close and
  far cheaper). `field-land-queue/fixer.jsonl` gives the branch and the reason, and the round's
  `logs/batch-*.log` (+ `.failures.txt`) gives main's sha, the log and the failing tests.
- **The brief** is the lander's own: `land.FIXER_PROMPT` + `land.fixer_context_text` over the round's
  log cut at the line "asking the fixer" and the ledger cut at the case, so no later fixup leaks in.
  `--context v0` replays the brief as it was on 2026-10-28 (a frozen copy of the prompt and the old
  context builder) for before/after comparisons.
- **AI4** runs through `field_repo_agent.py` with the fixer's command set minus `gradle`: one scratch
  worktree (`.nightly-tmp/replay`, checked out to F^ per case) cannot be built inside the fixer's
  budget. The brief says so.
- **Grading.** (a) The failing check, run against the scratch tree with *this* checkout's compiled
  checker classes (JUnit classes, `KDocAttachmentGuard`, `AgentTranscriptIndexGuard`) or the tree's own
  `tools/` Python. A case is graded only when its controls hold: the check is red on F^ and green on
  F. When today's KDoc guard also flags files the round never touched, it is scoped to the fixup's
  files. (b) `land._fixup_problem`'s rules. (c) Similarity of the changed lines to the real fixup's
  (difflib ratio), and **identical** when the edited files equal F's.

Outcomes, in order: `declined`, `rejected` (lander rules), `identical`, `edited the check` (AI4
changed the compiled check's own source, which this grader cannot see: it is how a guard gets
widened), `check passes`, `check red`.

## Results

52 fixups on history joined to the ledger; 30 had a runnable check whose controls held. The other 22
are graded by nothing here: Gradle-only checks (`compileKotlinJvm`, regenerations such as the
completion-corpus manifest), or tests whose today's version is red on both F^ and F
(`DocumentedApiEvaluationTest` ×7, `LanguagesCategoryTest` ×3, `AgentTranscriptIndexGuard` ×2).
All runs `openai/gpt-6.1-sol` (`AFIELD_AI_PROVIDER=openai`), Field on port 9611.

`v0` is the 2026-10-28 brief, `land` the brief after this branch's two changes to `tools/land.py`
(below). Similarity in brackets.

| case | branch | graded by | v0 brief | land brief | rounds | tokens in / out | wall |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `044143604` | claude/physical-media-expansion-stages-d589a3 | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 8 | 293929 / 1238 | 45 s |
| `f3d47a579` | nightly/2026-10-13/1-gesture-paint-replay | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 7 | 230928 / 1008 | 36 s |
| `064247dc9` | configurable-agent | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 6 | 236186 / 1167 | 41 s |
| `a22451e72` | claude/terminal-output-area-160980 | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 7 | 283519 / 1010 | 39 s |
| `c607dc559` | docs/language-p3-shared | ImpossibilityClaimContractTest | **check passes** (0.0) | **check passes** (0.0) | 7 | 341642 / 1471 | 44 s |
| `77996cfc5` | handoffs/code-20261015-2234 | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 5 | 230431 / 1083 | 31 s |
| `0f0d8b30e` | handoffs/code-20261015-2234 | ServerSurfaceLedgerTest | **check passes** (0.6) | **check passes** (0.6) | 12 | 578424 / 2799 | 75 s |
| `040682d0f` | handoffs/code-20261015-2234 | PersonalBoundaryTest, KDocAttachmentGuard | **check red** (0.89) | **check passes** (0.95) | 8 | 352028 / 2319 | 63 s |
| `935c5748b` | handoffs/code-20261016-1144 | ServerSurfaceLedgerTest | **check passes** (0.6) | **identical** (1.0) | 10 | 503357 / 2663 | 66 s |
| `76aad7f69` | handoffs/code-20261016-1144 | py | **identical** (1.0) | **identical** (1.0) | 5 | 188819 / 851 | 27 s |
| `22974893d` | handoffs/code-20261016-1144 | KDocAttachmentGuard | **check passes** (0.57) | **check passes** (0.5) | 7 | 292083 / 1153 | 43 s |
| `8581bcdfb` | nightly/2026-10-15/1-python-box-parity | py, py | **identical** (1.0) | **check passes** (0.5) | 17 | 768672 / 3030 | 118 s |
| `121aaabf5` | docs/kdoc-symbol-links-2 | KDocAttachmentGuard | **check passes** (0.86) | **check passes** (0.85) | 11 | 697760 / 2622 | 81 s |
| `41a73cb06` | nightly/2026-10-18/3-external-edit-guide | DocsFenceTreatmentTest | **identical** (1.0) | **identical** (1.0) | 6 | 225097 / 855 | 35 s |
| `84cea37ed` | fix/three-turntable-video | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 6 | 202308 / 1104 | 38 s |
| `4add273b7` | claude/contour-trees | py | **check passes** (1.0) | **declined** (0.0) | 7 | 288931 / 1789 | 55 s |
| `96753d9c2` | claude/genetic-algorithm-water-bug-cb6eff | PersonalBoundaryTest | **check passes** (0.67) | **check passes** (0.55) | 5 | 190746 / 1233 | 36 s |
| `98f9f3ab4` | claude/field4-timestral-vmo-8f092b | KDocAttachmentGuard | **check passes** (0.57) | **check passes** (0.57) | 6 | 214442 / 1024 | 33 s |
| `e3995fe22` | claude/canvas-flight-filming | py | **identical** (1.0) | **identical** (1.0) | 4 | 154780 / 605 | 26 s |
| `927c9e659` | claude/madp-geometry-d1fb28 | DocsFenceTreatmentTest | **identical** (1.0) | **identical** (1.0) | 11 | 358902 / 721 | 45 s |
| `a51e43ffb` | codex/presentation-studio | DocsFenceTreatmentTest | **identical** (1.0) | **identical** (1.0) | 8 | 313889 / 936 | 45 s |
| `6abc940aa` | nightly/2026-10-22/3-layers-guide | ServerSurfaceLedgerTest | **check passes** (0.45) | **check passes** (0.45) | 6 | 238815 / 1585 | 46 s |
| `023343117` | codex/kit-library | PersonalBoundaryTest | **check passes** (0.4) | **check passes** (0.4) | 11 | 570344 / 1798 | 59 s |
| `5165d12b6` | claude/demo-timeline-harvest | KDocAttachmentGuard | **identical** (1.0) | **identical** (1.0) | 7 | 276555 / 1293 | 39 s |
| `64c03befa` | claude/handwriting-pen | DocsFenceTreatmentTest | **identical** (1.0) | **identical** (1.0) | 7 | 281563 / 1281 | 43 s |
| `0ec3f217d` | nightly/2026-10-26/4-sheet-constraints | ImpossibilityClaimContractTest | **check passes** (0.33) | **check passes** (0.33) | 9 | 488167 / 1799 | 59 s |
| `36addffcd` | claude/ai4-attach-lines | PersonalBoundaryTest | **check passes** (0.5) | **check passes** (0.5) | 7 | 298691 / 1273 | 52 s |
| `d5ed45dc2` | handoffs/code-20261015-2234 | KDocAttachmentGuard (scoped) | **identical** (1.0) | **identical** (1.0) | 7 | 310438 / 1173 | 42 s |
| `d37569dca` | handoffs/code-20261015-2234 | KDocAttachmentGuard (scoped) | **identical** (1.0) | **identical** (1.0) | 11 | 538703 / 1598 | 58 s |
| `f33009088` | handoffs/code-20261015-2234 | KDocAttachmentGuard (scoped) | **identical** (1.0) | **identical** (1.0) | 7 | 274998 / 1370 | 41 s |

Repeats:

- `040682d0f-v0r2` (v0 brief): **edited the check** (0.91), 9 rounds, 73 s
- `040682d0f-v0r3` (v0 brief): **edited the check** (0.91), 10 rounds, 92 s
- `040682d0f-after1` (land brief): **check passes** (0.95), 11 rounds, 85 s
- `040682d0f-after2` (land brief): **check passes** (0.95), 10 rounds, 63 s
- `040682d0f-after3` (land brief): **check passes** (0.95), 8 rounds, 53 s

Totals, over the cases each brief ran:

- v0 brief: 30 cases — 17 identical, 12 check passes, 1 check red; **29/30 pass the real check**.
- land brief: 30 cases — 17 identical, 12 check passes, 1 declined; **29/30 pass the real check**.
- 65 AI4 runs in all (corpus + repeats): 23.8 M input tokens of which 21.0 M cache reads, 106 k output tokens, 57 min of wall time.

Cost: a median replay is 7 rounds, ~45 s and ~290 k input tokens, nearly all of them cache reads,
and about 1.5 k output tokens.

What the `check passes` rows with low similarity are: the same fix put differently — a citation to
a different line of the same file (`c607dc559`, `0ec3f217d`), `package afield.example` where the lander
wrote `afield.x` (`36addffcd`), the stacked KDoc moved the other way (`22974893d`, `98f9f3ab4`), the
inventory row worded differently (`0f0d8b30e`, `6abc940aa`). None is wrong on reading.

## Failure modes, and what changed

1. **Widening the guard (measured, fixed).** On `040682d0f` the branch wrote a Python
   `import importlib.metadata` inside a Kotlin string, which `PersonalBoundaryTest` took for a Kotlin
   import. The lander rewrote the string. AI4, with the v0 brief, added `"importlib"` to the test's
   `PUBLIC_IMPORT_ROOTS` and bumped the count in `dev_docs/private_boundary.md`, 3 runs out of 3: the
   test passes, and a guard is a little weaker. Nothing in the lander refuses that (the test file is
   not a denied path). `FIXER_PROMPT` now says not to clear a finding by widening the guard that made
   it, and that adding the row a ledger check asks for is not widening. With the new brief, 3 runs
   out of 3 reworded the import (`from importlib.metadata import version`) — similarity 0.95 to the
   real fix, check green. The rest of the corpus was rerun on the new brief to look for collateral.
2. **A finding a thousand lines above the tail (fixed in the context).** Gradle prints a failed task's
   output before its `> Task :x FAILED` header, and with `--continue` the 200-line tail the fixer is
   given can hold only "Execution failed for task ':checkKDocAttachment'". `fixer_context_text` now
   quotes each failed task's own output above the tail. No case flipped on this alone: AI4 found the
   finding by reading the guard's source and the branch's diff.
3. **Judging a branch by its tip.** The context now lists the branch's commits and names its whole
   range. This came from a first pass whose declines turned out to be the harness's own fault (next
   item); after that was fixed no case failed this way, so this change is not separately measured.
4. **Declining on the replay's own limits (open).** `4add273b7` with the new brief declined because
   "the required starting-point bundle is also missing and cannot be faithfully regenerated or
   verified without the unavailable JVM build" — true of the replay, not of the lander, whose fixer
   has a warm Gradle. With the v0 brief the same case was fixed; one run each, so this is as likely
   variance as the longer context.

The harness's own mistake, recorded because it would have been a false finding: the first pass took
main's sha from the round log's first line, which is blank in older logs, and fell back to a
merge-base with today's main — the branch tip itself once it has landed — so the branch's diff in
the brief was empty and AI4 declined four cases as "not this branch's change". Those results were
discarded; `_main_before` now refuses a log without the header rather than guess.

## What this does and does not show

- AI4 can do the lander's fixer job on the fixups that a cheap check can grade: 29/30 passing the
  real check with either brief, 17 of them byte-identical, none rejected by the lander's rules. The
  one miss differs: v0 widened a guard (fixed by the prompt change), the new brief declined one case.
  These are the mechanical kinds the fixer exists for (KDoc placement, fences, ledgers, idioms).
- It does not show the cases graded by Gradle (compile errors, regeneration), the ones whose test has
  since changed, or a real lander round (merged tree, warm build, `FIELD_AGENT=field` in the launchd
  lander). The grader runs today's checks; the controls are what make that honest per case.
- One run per case except `040682d0f`. The `declined` case shows the outcome varies.

## Replaying

```
./gradlew compileTestKotlinJvm                       # the checkers' classes
./gradlew -q -I <init script printing the jvm test runtime classpath> ... > .nightly-tmp/test.cp
AFIELD_AI_PROVIDER=openai PORT=9611 ./gradlew runJvm --args="--workspace $PWD/.nightly-tmp/ws" &
uv run --project . --group backdoor python tools/field_fixer_bench.py cases
uv run --project . --group backdoor python tools/field_fixer_bench.py run --id 64c03befa --port 9611
uv run --project . --group backdoor python tools/field_fixer_bench.py run --corpus --port 9611 --suffix=-tag
uv run --project . --group backdoor python tools/field_fixer_bench.py table
```

`run --corpus` is the nightly form: every case whose controls hold, about 45 minutes. Per-case
artifacts (`AgentTaskResult`, tool log, reply, diff) are in `ai4_fixer_runs/bench/<id>[suffix]/`,
one JSON line per run in `ai4_fixer_runs/bench/results.jsonl`. The brief (`prompt.md`) is not
committed, since `field_fixer_bench.py` rebuilds it from the fixup commit. Neither are the diffs of
the four cases that fixed the personal-boundary guard (023343117, 040682d0f, 36addffcd, 96753d9c2):
they quote private paths and fixture packages that `PersonalBoundaryTest` refuses in tracked files,
so re-run those cases to see them. The Field demo
`dev_docs/ai/ai4_fixer_bench_demo.yaml` shows the table and one case's diffs from those files.

The classpath step is the clumsy part: the bench reads `.nightly-tmp/test.cp`. A Gradle task that
writes it would retire the init script.
