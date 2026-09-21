# What each guard actually looks at

Written 20 September 2026, after the fourth independent report in eight days of the same shape of
defect: **a guard whose failure message names a rule over one population while the guard enforces it
over a smaller one.** Four agents found four instances on three different nights, none of them
looking for the family.

The point of this page is not the list. It is the two questions to ask of any guard in this tree,
and how to get the numbers to compare against when you ask them.

## The rule

> A guard either enforces over the whole corpus its message names, or its message says which subset
> it covers — and every printed population is floored. Where a sibling branch can legitimately grow
> the corpus, the floor is a **named list**, not a count.

The last clause is the one earned on the 20th. `tools/test_demo_build_scripts.py` had a bare
`assertEqual(20, len(demos))`, and its own docstring records that this is the assertion that caught
`skinned_characters` arriving from another branch on the night of the 19th, when `git merge-tree` and
a file-set intersection were both clean because the two branches shared no file. It did its job by
failing with `20 != 19`. That is true and it is useless: it does not say which document arrived,
whether anything verifies it, or what to do next. A set difference says all three.

## This page exhibited the defect it was written to name

**Read this before trusting a number on any page in `dev_docs/`, including this one.**

Published 20 September. Checked 21 September, against its own published re-derivation commands: five
rows were already false. `tools/test_*.py` said 29 and was 31. `*Guard*.kt` + `*ContractTest.kt`
said 28 and was 29. The corpus table said `147 transcripts / 20 demo documents / 550 create frames`
against `149 / 21 / 561`, and `139 transcripts / 139 entries` against `141 / 141`. Its closing
arithmetic said `139 + 8 = 147`.

Every one of those numbers was correct when it was typed. None was wrong through carelessness; they
were wrong because a sibling branch landed `bc71ed216` fourteen hours later and this page had no way
to know. It carried zero `<!--count:-->` markers and was absent from
`CitationContractTest.GUARDED_PAGES`, so `ProseNumeralContractTest` — the corpus-wide guard on
numbers written in prose, which exists for exactly this — never looked at it. A page written to name
a defect, exhibiting it, unguarded, within a day.

The repair is therefore not a re-measure. **A re-measure resets the clock and changes nothing.** The
numbers below are generated:

```
python tools/guard_corpora.py            # print the block and diff it against this page
python tools/guard_corpora.py --write    # rewrite it
```

Each row names a shell command, that command is the one printed in the row, and
`tools/guard_corpora.py` runs it and renders the row from what comes back.
`tools/test_guard_corpora.py` asserts the committed block is what those commands say today. It is
one of the `tools/test_*.py` suites that `checkPythonTools` carries into `./gradlew check`, so a
stale number here is a red build rather than a sentence somebody has to happen to notice. It also
refuses to pass vacuously: every row is floored at one, so a scrape that has stopped matching cannot
report a tidy zero; the demo corpus is compared as a **named set** against both hand-maintained
lists that claim to hold it, in two different languages, which nothing did before; and the
arithmetic this page got wrong on day one is re-derived rather than copied.

The commands are shell rather than a Python re-implementation on purpose. A re-implementation is a
second definition of the population, and two definitions that agree today are precisely how
`AgentTranscriptLayoutGuard`'s demo half sat at zero for a fortnight while the guard reported
success. You can paste any of these into a terminal and get the same answer, which is the only
property that makes a page like this checkable by a person at all.

## The populations

<!-- guard-corpora: generated begin -->

<!-- Written by `python tools/guard_corpora.py --write`. Checked by
     `tools/test_guard_corpora.py`, which runs every command below and compares. Do not
     hand-edit anything between the two markers: the whole point of this block is that no
     human types these numbers, because on 2026-09-20 a human typed them and five were
     wrong inside a day. -->

### The guard families

| family | count | how | what it selects |
| --- | --- | --- | --- |
| `tools/check_*.py` | 9 | `ls -1 tools/check_*.py` | standalone checker scripts |
| `tools/test_*.py` | 34 | `ls -1 tools/test_*.py` | unittest suites, all of them in `check` via `checkPythonTools` |
| `*Guard*.kt` + `*ContractTest.kt` | 29 | `find src -name '*Guard*.kt' -o -name '*ContractTest.kt'` | a **half-corpus by construction** — see the paragraph under this table |
| Gradle `check…` JavaExec guards | 10 | `grep -o 'tasks.register<JavaExec>("check[A-Za-z]*' build.gradle.kts` | the guards `check` reaches by spawning a JVM main |

### The corpora those guards are stated over

| family | count | how | what it selects |
| --- | --- | --- | --- |
| transcripts, top level | 143 | `ls -1 dev_docs/agents/transcripts/*.yaml` | what `AgentTranscriptIndexGuard` and the three verification registries count |
| transcripts, recursive | 151 | `find dev_docs/agents/transcripts -name '*.yaml' -o -name '*.yml'` | what `AgentTranscriptLayoutGuard` walks; the difference is the `docs_capture/` variants |
| demo documents | 21 | `ls -1d dev_docs/agents/demos/*/` | held as a named set by `AgentDemoRegistrationTest.DEMO_CORPUS` and `test_demo_build_scripts.CORPUS` |
| demos shipping a `build.yaml` | 9 | `ls -1 dev_docs/agents/demos/*/build.yaml` | the population `test_demo_build_scripts.py` diffs against its `_code_.kt` sidecars |
| entries on `public/docs/agent_transcripts.md` | 143 | `grep -oE '^### [a-z0-9_]+$' public/docs/agent_transcripts.md` | must equal the top-level transcript count, both directions |
| materialized starting-point bundles | 138 | `ls -1d public/docs/assets/agent_transcripts/*/` | transcripts minus `AgentTranscriptMaterializer.EXCLUDE` |

The two transcript counts are both right and they are not the same measurement: 151 = 143 + 8 under `docs_capture/`. If you quote one of them, say which.

<!-- guard-corpora: generated end -->

## Why the family keeps recurring

Three mechanics, all of them local and all of them reasonable at the time:

1. **A guard is widened and its message is not, or vice versa.** `AgentTranscriptLayoutGuard` gained
   the demo documents in `450c0ac9b` but its message had always said "the ordinary 620 x 170
   ceiling", so nothing about the message changed and nothing about the message was wrong — the
   discrepancy simply stopped existing without anyone noticing it had.
2. **A guard is widened and its *tests* are not.** Same guard: the demo half had been in the
   population since 5 September and, until 20 September, was exercised by zero assertions. On
   2026-09-18 a demo shipped boxes at 700 x 900 and twice 1180 x 900 — against a demo-corpus median
   box height of 136 — and every gate was green. The first report was a person opening the document
   and saying "why are those boxes so big?".
3. **A guard enumerates what it wants and then counts what it got.** `FieldMLStagePageTest` consumed
   seven `<!-- stage: -->` markers and asserted that it had consumed seven. That holds for any
   subset of the page, so an eighth marked fence was recomputed by nothing, on a page whose entire
   premise is that its listings are re-derived rather than captured.

Mechanic 3 is the general one, and it has a general remedy: **enumerate the population from the
artifact, then compare, rather than enumerating from the test and counting.**

There is a fourth, added 21 September, and it is not about a guard's *scope* at all:

4. **A guard that fires only where nobody looks is a slower version of no guard.** `bc71ed216` left
   `main` red in three assertions, from its own HEAD commit, for hours. All three worked perfectly:
   each named `marf_demo` and listed the remedies. The commit message shows its author checking this
   very family by hand — *"`checkAgentTranscriptLayouts` scans demo documents and is not in the
   pre-commit guard … Guard re-verified green: 21 demo documents, 561 create frames"* — and
   re-running the guard they had noticed, while missing the sibling wired into `check`. Nothing runs
   `check` on commit. Named sets fixed the diagnosis on the 20th and the branch still merged red on
   the 21st, because the diagnosis was never read. See "What pre-commit costs", below, for the
   measurement that a proposal about this needs.

## What each guard selects, and where it prints its own population

**No numbers in this table, deliberately.** Every guard here prints its population on every run, and
the previous version of this table copied those numbers into prose, where six of them went stale
inside a day. A command you can run beats a number somebody typed.

| guard | corpus it selects | prints its population as | floor |
| --- | --- | --- | --- |
| `AgentTranscriptLayoutGuard` | `dev_docs/agents/transcripts` (recursive) **and** every `metadata.edn` under `dev_docs/agents/demos` | `AgentTranscriptLayoutGuard ok — population: N transcript(s) … + M demo document(s) …, F create frame(s), L reviewed large frame(s)` (`./gradlew checkAgentTranscriptLayouts`) | both corpora must exist; a missing one fails rather than shrinking the run |
| `AgentTranscriptIndexGuard` | top-level transcripts against `public/docs/agent_transcripts.md` | `AgentTranscriptIndexGuard ok — N transcript(s), M entry/entries, …` (`./gradlew checkAgentTranscriptIndex`) | equality in both directions, plus the four numerals in the page's totals paragraph |
| `AgentDemoRegistrationTest` | `dev_docs/agents/demos` against the three registries in `DemoTranscriptExecSmokeTest` | its assertion messages name the documents; the registry sizes are the harness's last line | named set both ways, plus a floor on demos and on transcripts |
| `DemoTranscriptExecSmokeTest` | every transcript, at one of three depths | `DemoTranscriptExecSmokeTest ok — N demos verified (E executed, C compiled, J kotlinjs)` | an unclassified transcript fails on purpose |
| `AgentTranscriptMaterializer` | every transcript not in `EXCLUDE`, against the committed bundle | `AgentTranscriptMaterializer ok — materialized N document(s)` (`./gradlew checkAgentTranscripts`) | byte-for-byte against the committed starting points |
| `AgentDemoDriftGuard` | inline-vs-loose file pairs in the demo documents | documents and inline files, on its run line | an empty inline set per document is a problem |
| `DocumentedApiEvaluationTest` | documented APIs reached by the transcript corpus | `evaluated … / compiled but never run, anywhere … (ledger: N)` | floors on documented and on evaluated APIs, plus a two-way ledger |
| `tools/test_demo_build_scripts.py` | the demo `build.yaml` scripts against their `_code_.kt` sidecars | the `CORPUS` set difference, by name | named set (was a bare count until 2026-09-20) |
| `SourcePartitionGuard` | every Kotlin source file | files, lines, declarations | ratchets on unattributed lines and index over-reports |
| `KDocAttachmentGuard` | every Kotlin source file | `KDocAttachmentGuard: scanned N file(s), M KDoc block(s)` | — |
| `BoxOutputChannelGuard` | box code across the corpus | boxes, and top-level channel emissions | — |
| `checkCompletionCorpusFixtures` | `tools/completion-corpus/boxes`, **harvested from the transcript and template corpus** after real macro expansion | `DRIFT <transcript>/<box>: in the corpus but not in the fixtures`, then entries and how many carry a preamble | — |
| `FieldMLStagePageTest` | `<!-- stage: -->` markers in `public/docs/fieldml_stages.md` | the marker set, read off the page and compared | marker set read off the page, since 2026-09-20 |
| `tools/test_guard_corpora.py` | this page's generated block, against the commands it prints | every row's population, in the failure message | every row floored at one; demo corpus as a named set |

**The naming convention is itself a half-corpus.** `DocumentedApiEvaluationTest`,
`FieldMLStagePageTest`, `TranscriptStackAutoCompileTest` and `AgentDemoRegistrationTest` all scan a
corpus and hold a floor, and a `*Guard*` search misses every one. An inventory selected by filename
is the same defect one level up, which is worth remembering before treating the generated table as a
denominator for anything.

### Adding one transcript touches six things

Counted on 2026-09-21 by adding two and letting every gate say so in turn. Nobody had written this
down, and the order below is the order the failures arrive in, which is not the order a person would
guess:

1. `dev_docs/agents/transcripts/<name>.yaml`.
2. Exactly one of `EXECUTE` / `COMPILE_ONLY` / `KOTLINJS` in `DemoTranscriptExecSmokeTest` — an
   unclassified transcript fails on purpose.
3. A hand-written `### <name>` entry on `public/docs/agent_transcripts.md`, under a section whose
   `verified-by` marker matches the set, **and** the four numerals in its `totals` paragraph, which
   are checked as set membership rather than as a sum. No generator writes the entry; that is
   deliberate, and `AgentTranscriptIndexGuard`'s KDoc argues it at length.
4. The materialized starting point under `public/docs/assets/agent_transcripts/<name>/`, unless the
   transcript is in `AgentTranscriptMaterializer.EXCLUDE`. `./gradlew
   regenerateAgentTranscriptStartingPoints`.
5. The completion-corpus fixtures, `AFIELD_HARVEST_WRITE=1 ./gradlew
   checkCompletionCorpusFixtures`. This is the one nobody expects: the fixtures are harvested *from*
   the transcript corpus, so a new box with a preamble is a new fixture file and a changed manifest.
6. Possibly `DocumentedApiEvaluationTest.COMPILE_ONLY_REMAINDER`. A published page can teach an API
   that no transcript reached, in which case that API is invisible to the census; promoting a demo
   into the corpus makes it visible **for the first time**, as "compiled but never run", and the
   guard demands a written reason. Two arrived this way on 2026-09-21 and five did when
   `path_tracer_instrument` was promoted. It reads like a new problem and it is an old one becoming
   sayable.

## What is NOT covered

Listed so that the tables above cannot be mistaken for a survey.

- **`tools/check_doc_site_links.py`** and its 4418-pair fragment floor. It needs a built
  `public/field4`. Its `AgainstTheRealBuiltSite` case skips when that site is absent and fails when
  it is stale, so the same committed source gives three different answers depending on the tree —
  which is why, since 2026-09-19, both the skip and the failure print the built site's mtime. **Read
  that line before comparing two runs**; on 2026-09-18 its absence produced a three-way disagreement
  between three correct measurements.
- **`tools/check_doc_assets.py`** is in neither the pre-commit registry nor `build.gradle.kts`; it
  gates only `--strict` docs builds (`tools/build_zensical_docs.py`). It was exiting 1 on `main`
  from 2026-09-20 — a capture fence rendering and committing
  `public/docs/assets/neural_particles/mixture.png` and then displaying it nowhere, because it was
  the only one of that page's four such fences without `embed=true` — and nothing in `check` said
  so.
- **The demo `build.yaml` population itself.** Nothing asserts that a demo directory *has* a build
  script; only that the nine that do still match their documents. A demo can arrive without one and
  the only signal is the count in the generated block moving.
- Every `*Guard*.kt` / `*ContractTest.kt` class and `tools/test_*.py` module not named above. A
  crude grep for floor-shaped assertions (`assertGreater`, `MIN_`, `isNotEmpty`) puts roughly a
  third of them with no obvious floor, but that is a screen and not a measurement — several of the
  zero-scoring ones are equality guards over a fixed surface, where a floor would be meaningless.
  The honest next pass is to run each one and record what it prints, which is a night's work and has
  not been done.

Filling those rows in from reading rather than from running would make this page exactly the kind of
artifact it is complaining about.

## What pre-commit costs

Measured on this machine, 2026-09-21, because the standing proposal — *put `checkPythonTools` in the
pre-commit hook so a red `check` cannot sit for hours* — is not worth making without a number.

The hook is `tools/check_staged_generated_artifacts.py`, and it is not a flat tax: it looks at what
is staged and runs only the gates those paths can affect.

| what was run | wall clock |
| --- | --- |
| the hook, with one `src/jvmTest/**` Kotlin file staged — `no affected staged paths`, nothing ran | **0.09 s** |
| the hook, with two transcripts, `public/docs/**`, two `src/**` Kotlin files and a regenerated bundle staged — `checkKDocAttachment`, `checkAgentTranscripts`, `checkAgentTranscriptIndex`, `checkCompletionCorpusFixtures`, `citationGates` | **1 m 55 s** to **3 m 03 s** |
| all of `checkPythonTools` — `uv run … tools/run_python_tests.py`, 32 suites, 585 tests, warm cache | **37.9 s** |
| the corpus family alone — `python -m unittest tools.test_demo_build_scripts tools.test_guard_corpora`, 8 tests | **0.32 s** |

So the ~2-minute figure is real, but it is the *docs-and-transcripts* path rather than the cost of
staging a `.kt`; with nothing relevant staged the hook is free.

**And the obvious proposal is the wrong one.** "Put `checkPythonTools` in the hook" sounds cheap and
is not: 37.9 s on every commit, dominated by the suites that fork a docs runner or a backdoor
subprocess. That is half a minute of dead time on a one-line typo fix, every time, and it is exactly
the kind of tax people learn to `--no-verify` their way around — at which point the guard is worse
than absent, because it is believed in.

**The affordable version is the family, not the suite.** The two assertions that `bc71ed216` broke
are `tools/test_demo_build_scripts.py` and — now — `tools/test_guard_corpora.py`, together **0.32 s**
and eight tests, with no JVM, no daemon and no Gradle lock. That last one matters more than it
sounds: concurrent nightly sessions already contend for one Gradle daemon and one Kotlin daemon
ceiling, and a hook that takes either is a hook that occasionally hangs for reasons unrelated to the
commit. Three tenths of a second would have caught `bc71ed216` at the moment it was made.

The shape of the change, if someone takes it: a `--fast` group in `tools/run_python_tests.py` that
selects suites declaring themselves cheap and hermetic, so that the hook's cost is a property the
suites opt into rather than a list in the hook that goes stale — the same rule as everything else on
this page.

**Not implemented here.** `core.hooksPath` points every worktree at one hook directory, so changing
the hook from a nightly branch changes it for every concurrent session on the machine. That is the
human's call to make, not a nightly branch's.

## The standard for a widened guard

**Red-first, or it is not evidence.** A guard that has never been observed to fail on the violation
it claims to catch is a claim. The four widenings on
`nightly/2026-09-20/2-guard-corpus-and-floors` were each observed failing first — two against real
state in the tree, two against an injected violation with the fix reverted — and the verbatim
messages are in the commit bodies. Where a message came out empty, that was itself fixed: an
assertion about a population has to print the population, or it reproduces the bug it tests for.

`tools/test_guard_corpora.py` was held to the same standard: it was observed failing against the
five stale rows described at the top of this page, before the generated block replaced them, and the
verbatim message is in the commit body.

## The cross-branch part, which is the reason for named lists

The demo corpus and the transcript registries are the two places in this tree where two branches can
break each other with **no textual overlap at all**. Branch A adds `dev_docs/agents/demos/foo/`;
branch B tightens the rule that every demo is registered. Neither touches a file the other touches,
so `git merge-tree` is clean, a file-set intersection is empty, and the union is red. This happened
on 2026-09-11, again on 2026-09-19, and again on 2026-09-20.

There is no diff-shaped signal for it. What there can be is a good failure message, which is why
`DEMO_CORPUS` and `CORPUS` are lists of names that tell you what to add and where, rather than
integers that tell you that something changed — and why, since 2026-09-21, a third assertion
compares those two lists against *each other*, which nothing did: each was checked against the disk
by its own suite, in its own language, and two branches editing one each would have merged cleanly
into a corpus with two definitions.

## See also

- `dev_docs/DEMO_CORPUS_AUDIT.md` — the method for recounting the demo corpus, and the finding that
  opening an artifact finds what reading it does not.
- `dev_docs/agents/OPERATION_TRANSCRIPT.md` — what an ordinary box frame is, which is what the
  620 x 170 ceiling is a ceiling on.
- `dev_docs/testing/expected_test_failures.json` — the other two-sided registry in the tree, and the
  model for "an exception may not outlive its reason".
