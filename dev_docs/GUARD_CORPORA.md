# What each guard actually looks at

Written 20 September 2026, after the fourth independent report in eight days of the same shape of
defect: **a guard whose failure message names a rule over one population while the guard enforces it
over a smaller one.** Four agents found four instances on three different nights, none of them
looking for the family.

The point of this page is not the list. It is the two questions to ask of any guard in this tree,
and the numbers to compare against when you ask them.

## The rule

> A guard either enforces over the whole corpus its message names, or its message says which subset
> it covers — and every printed population is floored. Where a sibling branch can legitimately grow
> the corpus, the floor is a **named list**, not a count.

The last clause is the one earned tonight. `tools/test_demo_build_scripts.py` had a bare
`assertEqual(20, len(demos))`, and its own docstring records that this is the assertion that caught
`skinned_characters` arriving from another branch on the night of the 19th, when `git merge-tree` and
a file-set intersection were both clean because the two branches shared no file. It did its job by
failing with `20 != 19`. That is true and it is useless: it does not say which document arrived,
whether anything verifies it, or what to do next. A set difference says all three.

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

## The population of guards, as measured 2026-09-20

Recounted rather than inherited. The prior enumeration in the backlog was "9 `tools/check_*.py` plus
21 `*Guard*.kt`/`*ContractTest.kt`"; the first number still holds and the second is now 28.

| family | count | how |
| --- | --- | --- |
| `tools/check_*.py` | 9 | `ls -1 tools/check_*.py` |
| `tools/test_*.py` | 29 | `ls -1 tools/test_*.py` |
| `*Guard*.kt` + `*ContractTest.kt` | 28 | `find src -name '*Guard*.kt' -o -name '*ContractTest.kt'` |
| Gradle `check…` JavaExec guards | 10 | `grep -c 'tasks.register<JavaExec>("check' build.gradle.kts` |

**The naming convention is itself a half-corpus.** Four of the guards worked on tonight are named
none of those things: `DocumentedApiEvaluationTest`, `FieldMLStagePageTest`,
`TranscriptStackAutoCompileTest` and the new `AgentDemoRegistrationTest` all scan a corpus and hold
a floor, and a `*Guard*` search misses every one. An inventory selected by filename is the same
defect one level up — which is worth remembering before treating the table above as the denominator
for anything.

## What was run, and what it selects

Everything in this table was run on this tree tonight and the numbers are the guards' own output.
Nothing here is copied from prose.

| guard | corpus it selects | population measured 2026-09-20 | floor |
| --- | --- | --- | --- |
| `AgentTranscriptLayoutGuard` | `dev_docs/agents/transcripts` (recursive) **and** every `metadata.edn` under `dev_docs/agents/demos` | 147 transcripts + 20 demo documents, 550 create frames, 10 reviewed large frames | both corpora must exist; a missing one fails rather than shrinking the run |
| `AgentTranscriptIndexGuard` | top-level transcripts against `public/docs/agent_transcripts.md` | 139 transcripts, 139 entries | equality in both directions |
| `AgentDemoRegistrationTest` (new) | `dev_docs/agents/demos` against the three registries in `DemoTranscriptExecSmokeTest` | 20 demo documents — 19 with registered transcripts, 1 in `UNVERIFIED`; 139 transcripts against 139 registry entries | named set both ways, plus `>= 20` demos and `>= 139` transcripts |
| `AgentDemoDriftGuard` | inline-vs-loose file pairs in the demo documents | 20 documents, 75 inline files | empty inline set per document is a problem |
| `tools/test_demo_build_scripts.py` | the nine demo `build.yaml` scripts against their `_code_.kt` sidecars | 20 documents, 9 build scripts | named set (was a bare count until tonight) |
| `SourcePartitionGuard` | every Kotlin source file | 1481 files, 716652 lines, 49219 declarations | ratchets: 29 unattributed lines against 32, 2 index over-reports against 2 |
| `KDocAttachmentGuard` | every Kotlin source file | 1481 files, 21325 KDoc blocks | — |
| `BoxOutputChannelGuard` | box code across the corpus | 805 boxes, 369 top-level channel emissions | — |
| `checkCompletionCorpusFixtures` | `tools/completion-corpus/boxes` | 440 entries, 103 with a preamble | — |
| `FieldMLStagePageTest` | `<!-- stage: -->` markers in `public/docs/fieldml_stages.md` | 7 markers = 6 recomputed stages + `surface-pipe` | marker set read off the page and compared, since tonight |

The two counts of transcripts are both right and they are not the same measurement: the layout guard
walks recursively and so includes the 8 capture variants under `docs_capture/`, while the index guard
and the verification registries take the 139 at the top level. 139 + 8 = 147. If you quote one of
them, say which.

## What was NOT run

Listed so that the table above cannot be mistaken for a survey.

- **`tools/check_doc_site_links.py`** and its 4418-pair fragment floor. It needs a built
  `public/field4`, which this branch did not build. Its `AgainstTheRealBuiltSite` case skips when
  that site is absent and fails when it is stale, so the same committed source gives three different
  answers depending on the tree — which is why, since 2026-09-19, both the skip and the failure print
  the built site's mtime. **Read that line before comparing two runs**; on 2026-09-18 its absence
  produced a three-way disagreement between three correct measurements.
- Every `*Guard*.kt` / `*ContractTest.kt` class and `tools/test_*.py` module not named above. A
  crude grep for floor-shaped assertions (`assertGreater`, `MIN_`, `isNotEmpty`) puts roughly a
  third of them with no obvious floor, but that is a screen and not a measurement — several of the
  zero-scoring ones are equality guards over a fixed surface, where a floor would be meaningless.
  The honest next pass is to run each one and record what it prints, which is a night's work and was
  not this night's.

Filling those rows in from reading rather than from running would make this page exactly the kind of
artifact it is complaining about.

## The standard for a widened guard

**Red-first, or it is not evidence.** A guard that has never been observed to fail on the violation
it claims to catch is a claim. The four widenings on
`nightly/2026-09-20/2-guard-corpus-and-floors` were each observed failing first — two against real
state in the tree, two against an injected violation with the fix reverted — and the verbatim
messages are in the commit bodies. Where a message came out empty, that was itself fixed: an
assertion about a population has to print the population, or it reproduces the bug it tests for.

## The cross-branch part, which is the reason for named lists

The demo corpus and the transcript registries are the two places in this tree where two branches can
break each other with **no textual overlap at all**. Branch A adds `dev_docs/agents/demos/foo/`;
branch B tightens the rule that every demo is registered. Neither touches a file the other touches,
so `git merge-tree` is clean, a file-set intersection is empty, and the union is red. This happened
on 2026-09-11 and again on 2026-09-19.

There is no diff-shaped signal for it. What there can be is a good failure message, which is why
`DEMO_CORPUS` and `CORPUS` are lists of names that tell you what to add and where, rather than
integers that tell you that something changed.

## See also

- `dev_docs/DEMO_CORPUS_AUDIT.md` — the method for recounting the demo corpus, and the finding that
  opening an artifact finds what reading it does not.
- `dev_docs/agents/OPERATION_TRANSCRIPT.md` — what an ordinary box frame is, which is what the
  620 x 170 ceiling is a ceiling on.
- `dev_docs/testing/expected_test_failures.json` — the other two-sided registry in the tree, and the
  model for "an exception may not outlive its reason".
