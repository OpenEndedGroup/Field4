# The rank a person sees, measured over 578 carets

**Status, 2026-08-18.** The popup's ranking is now deterministic under a shuffled source walk. Package
lookups no longer borrow the first categorised file they happen to encounter: they answer only when the
package's files agree. The order test now agrees on all **6 679** distinct rows, the seven-case ratchet
was re-cut at its one genuine movement, and the 578-caret population was measured again.

That clean measurement overturns one earlier conclusion. The conservative R2 weight table now raises
bare-foreign contested recall from **0.420 to 0.540** and all bare contested recall from **0.657 to
0.708**, while bare-owned contested recall stays **0.900**. That is well above this corpus's resolution,
so the old refusal does not survive. No weight change ships with the determinism repair: R2 also pushes
the median visible foreign answer from 32 to 41 and moves `List.forEach` from 36 to 42, so adopting it is
a separate product decision with a real, recorded trade-off rather than cleanup hidden in a bug fix.

Everything below is reproducible from this checkout. Nothing in it is an estimate.

> ## Scope: this measures the AA (`k2-aa`) backend, and the editor may not be on it
>
> **Read this before quoting any number here.** These 578 carets were recorded by POSTing to the
> code-use semantic sidecar's `/complete`, whose reply stamps its own `backend`. That string is now
> written into the corpus header (`#meta backend k2-aa`) and asserted, so the claim outlives the session
> that made it; it used to be a console line that nothing re-checked. (`/health` on the same sidecar
> answers `k2-standalone` — two endpoints' vocabularies for one process, not a disagreement.)
>
> The structural leg is the stronger one. `Metadata.declarationProximity` has exactly **one** producer in
> the tree — `execution.kt:3226`, copying `extraInfo[AaCompletionClient.DECLARATION_PROXIMITY]`, which
> only the AA client populates — so a row carrying it **cannot** have come from K1. 5 018 of the 6 679
> distinct recorded rows (75.1%) carry one: `own` 4 047, `supertype` 932, `universal` 39. And
> `CommonMemberPrior` resolves a prior for FLine's named members at the scores `fieldSuggest.js` expects
> (40, 38, 36, 34, 32, 30 …). Both asserted, not observed.
>
> **Mind the direction of that inference.** What is used here is *present ⇒ AA*. The converse,
> *blank ⇒ K1*, is **false**, and an earlier draft of this note leaned on it: proximity is populated only
> for **member** completions, so a genuine AA reply to a non-member caret carries none at all — branch 3
> demonstrated exactly that, an AA response with it blank on all 44 rows. The retracted premise is not
> needed. What does still stand from branch 3's live capture is the other half: scores descending by
> exactly `originalIndex * 0.012`, i.e. every other term at zero, which is a signature no AA-served
> member panel produces.
>
> So this document describes the **AA** path. What is measured here — that a conservative category
> rebalance changes foreign visibility, that sectioning is not the lever, and that owned and foreign
> receivers are more than a factor of ten apart in reciprocal rank — is a
> statement about a popup served by the sidecar, and says **nothing** about one served by the K1 fallback,
> where the prior contributes nothing because it never crosses the wire at all. Which backend a given
> user's editor is on is branch 3's task and is deliberately not touched here.
>
> The one thing that would make this document wrong rather than merely scoped is if the *recording* had
> been served by K1 too — because a silently absent prior looks exactly like a prior that does not help.
> It was not: the proximity distribution above cannot come from a path that sends no proximity, and it is
> asserted in `FieldSuggestPresentationTest` so it cannot rot into a false claim.

## Why a wide corpus had to exist first

Every other measurement of completion in this tree measures the *payload*: is the member the author
wrote somewhere in the response. The popup renders at most `MAX_VISIBLE_ITEMS = 80` rows, so payload
recall and the rank a person can see are two different numbers, and only the second is the user's.
Measured 2026-08-07: 96% payload recall while `moveTo` at a bare `f.` sat at response index 159 of 249
and was not on screen at all.

Until today the only place in the repository that asserted a *rank* was a hand-cut 7-case fixture. That
is a fine ratchet and a hopeless basis for a weighting decision, which is what
`dev_docs/completion_aa_sidecar.md` has said since 2026-08-07 — "widen `rank-payloads.json` first, then
rebalance, in that order, or it is tuning on four carets" — and what commit `69ca4d5cb` meant by
leaving two expectations deliberately un-recut: "whether `CATEGORY_WEIGHT` should learn the server's
vocabulary is a decision and not a measurement."

## The pipeline

    tools/scrape_completion_corpus.py --wide --out tools/completion-corpus/rank-carets.tsv
    ./gradlew -p tools/code-use-semantic-sidecar run --args="--port <p> --workspace $PWD"
    python3 tools/record_rank_corpus.py --port <p>
    ./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'

1. **Harvest.** For `expr.member` in code a human wrote and shipped in a doc, the member they wrote
   *is* the label — free, large and unarguable. The harvest was 22 carets from 8 named docs; 70 of the
   96 files in `public/docs` have a Kotlin block. `--wide` takes 400 per caret form from all 70.
   The default path is unchanged and byte-identical, because those 22 are the input to the K1-vs-AA
   *recall* differential, where each fixture costs a full AA analysis.
2. **Two caret forms, not one.** `fuzzyScoreText` opens with `if (!q) return 0`, so a bare `expr.` and
   a typed `expr.ab` are different questions: the first is decided by category weight and the
   `@CommonMembers` prior, the second by fuzzy match. Every scraped caret keeps two characters after
   the dot, so the whole harvest was the prefixed gesture and the bare dot — the case the whole
   `CATEGORY_WEIGHT` argument is about — had six instances in the repository. It now has 284.
3. **Record through the real server.** Each caret is POSTed to the code-use semantic sidecar's own
   `/complete`, the endpoint `AaCompletionClient` posts to per keystroke, same request shape, same
   `limit = 1200` (anything smaller deletes rows the ranker never sees and makes the measurement
   kinder than the popup). 578 of 800 carets return candidates, median 12 ms each once the AA session
   is warm; the other 222 are carets AA resolves no receiver for (a caret inside a string literal, a
   doc-only DSL) and are counted, not dropped silently.
4. **Score with the shipped ranker.** `FieldSuggestPresentationTest` classifies every row through the
   server's own `KotlinCompletionSupport.classifyMetadata`, gives it the server's own
   `CommonMemberPrior`, stages the verbatim `src/commonMain/resources/fieldSuggest.js` off the
   classpath, and `probe.mjs rankmetrics` runs it under node. Staging off the classpath is what makes
   `fieldSuggest.js` a declared input of `jvmTest`, so editing it cannot leave a stale UP-TO-DATE pass.

The recording is checked in: `src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz`, 39 221 payload
rows over 6 679 distinct ones, 191 KB. 101 distinct receivers, `kotlin.collections.List` 41 times,
`java.lang.Math` 12, `afield.graphics.FLine` 10.

It carries one field the 7-case fixture never has: **`proximity`**. `fieldSuggest.js` scores
`own`/`supertype`/`universal` at 0 / −20 / −85, its largest negative term, and `rank-payloads.json`
records text/displayText/tail/icon/declaredIn only. Every rank asserted in this repository before today
was computed with that term silently zero for every row.

## The measurement

Measured 2026-08-18 on this checkout, after removing package walk-order dependence. Floors sit just
under each figure, in `probe.mjs` beside the code
that computes them.

| form | cases | in payload | contested | recall | contested recall | MRR | median rank |
| --- | --- | --- | --- | --- | --- | --- | --- |
| prefixed | 294 | 0.881 | 101 | 0.881 | **0.822** | **0.652** | 1 |
| bare | 284 | 0.908 | 236 | 0.655 | **0.657** | **0.123** | 12 |
| all | 578 | 0.894 | 337 | 0.770 | 0.706 | 0.392 | 2 |

"Contested" means the caret sent more rows than the popup can show, so the popup had to leave something
out. The headline is restricted to that subset for a reason that has bitten this project repeatedly: a
recall-at-80 over carets that send fewer than 80 rows is 1.0 by construction and *could not have
failed*. The test asserts that at least 80 bare carets are contested, that at least 100 rows resolve a
`@CommonMembers` prior, that at least 10 answers are declared `universal`, and that at least 100 answers
carry a category `CATEGORY_WEIGHT` scores 0 — i.e. that every term the conclusion rests on is exercised.

**The two left-hand columns are the point of the exercise.** Payload recall is what every other
completion measurement in this tree reports; visible recall is the user's. At a bare dot they are
**0.908 and 0.655** — a 25-point gap, 72 carets whose answer the server computed and the popup did not
show. That is "96% recall hid the answer at index 159 of 249", at population scale.

### That bare figure was averaging two populations a factor of ten apart

It is the wrong unit, and `69ca4d5cb` said so before anyone measured it: *"a foreign receiver you cannot
annotate has no prior and no weight, so a bare dot on it is alphabetical."* A receiver this tree declares
can carry `@CommonMembers` and a `@file:CompletionCategory`; `java.lang.Math` and
`kotlin.collections.List` cannot. Split on it:

| bare dot on | cases | in payload | contested | recall | MRR | median rank |
| --- | --- | --- | --- | --- | --- | --- |
| a receiver this tree **declares** (`afield.*`) | 146 | 0.986 | 120 | **0.918** | **0.219** | 9.5 |
| a receiver it does **not** (`kotlin.*`, `java.*`) | 100 | 0.940 | 100 | **0.420** | **0.018** | 32 |

Recall 0.918 against 0.420, MRR 0.219 against 0.018 — still a factor of twelve in reciprocal rank — and *every* foreign bare caret is
contested, 100 of 100. So the popup is not uniformly mediocre at a bare dot: it is good on Field's own
types and close to useless on the ones nobody here can annotate. That is precisely the population the
deferred decision was about, and precisely what the global figure hid. Both rows are floored in
`probe.mjs`, and the test asserts the corpus holds at least 60 foreign bare carets so the split cannot go
vacuous.

The two receivers `69ca4d5cb` names, caret by caret, as shipped:

| caret | rows sent | visible rank |
| --- | --- | --- |
| `Math.` → `cos` | 110 | 32 |
| `Math.` → `sqrt`, `Math.` → `sin` | 110 | not shown |
| `List.` → `forEach` | 331 | 36 |
| `List.` → `map`, `mapIndexed`, `indices`, `joinToString`, `firstOrNull`, `sortedByDescending` | 331 | not shown |
| the same members with two characters typed | 1–32 | 1–20 |

The prefixed column is why nobody has filed this as a bug: typing two letters fixes it completely. The
bare gesture on a foreign receiver is the one that does not work.

### The section effect and the weight effect, separated

`filterAndLimit` cuts to `MAX_VISIBLE_ITEMS` on the **flat** ranked list and only then calls
`orderItemsForGroupedDisplay`. So sectioning is a permutation *within* the visible rows — it never
decides which rows are visible — and `rankItems` writes its own `score` onto every item it returns. So:

- **flat rank** = position in `controller.items` re-sorted by descending `score` (weights only);
- **visible rank** = position in `controller.items` as rendered (weights and sections);
- **neutral rank** = the same payload replayed with every category replaced by one constant, which
  both collapses the sections into one group and ties every `categoryScore` at 0.

No re-implementation of the ranker, and the rendered row count is checked against `controller.items`
per case, so a divergence fails loudly instead of quietly invalidating the decomposition.

| effect | cases moved (of 445 found) | median | p90 of those moved |
| --- | --- | --- | --- |
| sectioning (visible − flat) | 71 | 0 | 8 rows |
| category weight (neutral − flat) | 107 | 0 | 16 rows |

So after last night's classification fix, **sectioning is an outlier cost and not a systemic one** —
`FLine.moveTo` rendered at row 76 was the tail of the distribution, not its shape. The case counts are
reported next to the medians deliberately: a median of 0 with 71 cases moved and a p90 of 8 is a real
effect that a median alone denies.

### Per category, over the carets whose answer carries that category

`w` marks a category `CATEGORY_WEIGHT` scores above zero.

| category | w | cases | recall | median rank |
| --- | --- | --- | --- | --- |
| Kotlin | · | 165 | 0.685 | 7 |
| (uncategorised) | · | 118 | 0.898 | 2 |
| (absent) | · | 61 | 0.000 | — |
| Graphics | w | 63 | 0.984 | 1 |
| External | · | 36 | 0.889 | 9.5 |
| Audio | · | 33 | 1.000 | 2 |
| Data | · | 24 | 1.000 | 2 |
| Math | · | 20 | 0.950 | 2 |
| Visualization | · | 18 | 1.000 | 2 |
| Polytopes | · | 15 | 1.000 | 10 |
| ALife | · | 10 | 1.000 | 2 |
| Imaging | · | 5 | 0.600 | 2 |
| Function | · | 5 | 1.000 | 13 |
| Machine learning | · | 3 | 1.000 | 2 |

`(absent)` at recall exactly 0.000 is a consistency check on the truth matcher, not a finding.
`(uncategorised)` is a *classification* gap rather than a weighting one, and at 0.898 / rank 2 it is not
costing anything today.

The shape that decides the question is in the first and fifth rows. **The two worst-ranked populations
in the popup are `Kotlin` (165 cases, 0.685, median 7) and `External` (36, 0.889, median 9.5)** — and
those are exactly the two categories that "teach the table Field's vocabulary" pushes *down*.

## The decision: the old refusal is withdrawn; R2 is reopened

`tools/rank_weight_lab.py` runs the whole sweep in about a minute. Every row is the same 578 carets and
the same recorded payloads; only the constant differs.

| variant | prefixed c-recall | bare c-recall | all recall | MRR (all) | exposure |
| --- | --- | --- | --- | --- | --- |
| R0 as shipped | 0.822 | **0.657** | **0.770** | 0.392 | 90.2% |
| R1 server vocabulary at 24, kotlin/external 0 | 0.822 | 0.653 | 0.768 | 0.403 | 75.4% |
| R2 server vocabulary at 12, kotlin 8, external 4 | 0.822 | **0.708** | **0.791** | 0.393 | 14.4% |
| R3 kotlin 24 only | 0.822 | 0.695 | 0.785 | 0.394 | 33.2% |
| R4 kotlin 24, external 24 | 0.822 | 0.695 | 0.785 | 0.395 | 29.2% |
| R5 `universal` −85 → −40 | 0.822 | 0.657 | 0.770 | 0.391 | 90.2% |
| R6 `universal` −85 → −140 | 0.822 | 0.657 | 0.770 | 0.392 | 90.2% |

Read R2 against R0. Teaching the table the server vocabulary at a conservative magnitude raises bare
contested recall by **0.051** and all recall by **0.021**. The order-dependent classifier had hidden that
gain completely: the pre-fix run reported 0.000. This is not an MRR rounding argument; twelve additional
foreign-receiver answers cross the 80-row visibility cliff. The harness remains sensitive in the other
direction too: R1's larger weights lower bare contested recall slightly, so magnitude still matters.

That makes R2 a credible candidate, not an automatic shipment. Its exposure remains 14.4% because
`other` and uncategorised rows are still unweighted; eliminating exposure is not itself the objective.
More importantly, the same table raises foreign recall while worsening the median rank among answers
that remain visible. The next decision should therefore be made on the split below, not on one global
score.

R5 and R6 are worth recording for the same reason: **changing the largest single term in the ranker by
±55 points moves nothing measurable.** At a bare dot every `universal` row takes the same penalty, so
the term is a uniform shift within a group and only bites at a group boundary.

### And on the population the decision was actually about

The table above is the global figure, which the split shows is the wrong unit. So here is the same sweep
restricted to a bare dot on a receiver this tree cannot annotate — the 100 carets `69ca4d5cb` was talking
about — beside what each variant costs the 146 it can:

| variant | foreign recall | foreign MRR | foreign median rank | owned recall |
| --- | --- | --- | --- | --- |
| R0 as shipped | **0.420** | 0.018 | 32 | **0.900** |
| R1 server vocabulary at 24 | **0.420** | 0.017 | 33 | 0.892 |
| R2 server vocabulary at 12 | **0.540** | 0.030 | 41 | **0.900** |
| R3 `kotlin` 24 only | **0.530** | 0.036 | 32 | 0.883 |
| R4 `kotlin` 24, `external` 24 | **0.530** | 0.039 | 32 | 0.883 |
| R5/R6 proximity ±55 | 0.420 | 0.018 | 32 | 0.900 |

R2 gains **0.120 — twelve carets in a hundred — with no owned contested-recall cost**. R3 and R4 gain
0.110 but give up 0.017 of owned recall, so they are dominated by R2 on recall. Named-receiver detail
keeps the result honest: `Math.cos` stays at 32 under R2, while `List.forEach` moves from 36 to 42 and
the other named bare members remain off screen. The gain comes from other foreign carets, not from every
example improving uniformly.

The mechanism is still a fixed 80-row budget, but the clean corpus no longer says the shipped allocation
is a local optimum. R2 admits more answers by redistributing that budget; its higher foreign median rank
shows the admitted answers tend to arrive near the cutoff.

So the honest three-part statement is:

1. As shipped, `CATEGORY_WEIGHT` affects the **order** of 107 found answers, with a p90 movement of 16
   rows among those moved.
2. R2 affects **visibility** materially: +0.051 bare contested recall and +0.120 foreign recall.
3. That recall gain leaves owned contested recall unchanged, but foreign median rank worsens by nine
   rows. The weight table is reopened, not settled.

## Would fixing sectioning buy anything? No — measured, and the hypothesis is retired

`69ca4d5cb` closes with *"sectioning happens above ranking, so no prior can cross it"*, which reads like
the next lever. It is not, and the reason is structural rather than empirical: `filterAndLimit` cuts to
`MAX_VISIBLE_ITEMS` on the **flat** ranked list and only then groups. So an answer is visible in the flat
order exactly when it is visible in the grouped one, and **ranking across sections instead of into them
recovers 0 of the 72 payload-present missing answers.** That is not an argument in prose — `probe.mjs` fails if any answer
is visible in one order and not the other, which is also the guard that catches someone moving the
`.slice()` to after the grouping.

What collapsing sections *would* buy is order, and that is small:

| population | MRR as shipped | MRR ranked across sections | median rank | flat median |
| --- | --- | --- | --- | --- |
| prefixed | 0.652 | 0.660 | 1 | 1 |
| bare | 0.123 | 0.128 | 12 | 11 |
| bare-owned | 0.219 | 0.229 | 9.5 | 7 |
| bare-foreign | 0.018 | 0.018 | 32 | 31 |

So sectioning costs the median answer nothing and would buy at most **+0.008 MRR** if removed.
`FLine.moveTo` rendered at row 76 was the tail of this distribution
(71 of 445 answers move at all, p90 8 rows), not its shape — and after the classification fix that tail
is the *symptom* of a misfiled category, which is why the ceiling on the median shift is a useful gate and
a redesign is not a useful project.

## Is any of this decided by the order the source tree was walked?

Asked because it would invalidate everything above: the measurement classifies every row through
`KotlinSourceIndex`, whose index is a flat list of definitions in scan order, and three lookups in that
file still resolve a contested simple name by first match — `getKDocForSymbol`,
`bestDefinitionForSimpleName`, `resolveSourceCard`. This repository has been burned by that shape before:
`~/.afield/filestore` rewritten between runs once gave four different ranks for one caret.

The argument was that none of the three is on this path, because `classifyMetadata` reaches the index only
through `getCompletionCategoryForSymbol`. **That argument was wrong.** The test
`the corpus's categories do not depend on the order the source tree was walked` shuffles the definition
list with a fixed seed and compares the category of all 6 679 distinct rows. Before the repair it found:

> **68 of 6 679 distinct recorded rows (1.0%) get a different category when the source tree is walked in a
> different order.**

Discriminated from the obvious alternative — a `latestIndex` cache confound serving both runs one index —
by a marker assertion that the two runs report *different* first definitions. They do, so the permutation
reached the index and the disagreement belongs to the classifier, not to the harness.

The mechanism was visible in every disagreeing row: `declaredIn` is a **package**, never a class. It was the
top-level-extension branch that `69ca4d5cb` itself added,

```kotlin
val inPackage = matches.filter { it.filePath.contains("/$packagePath/") }
return inPackage.mapNotNull { it.categoryOrNull() }.distinct().singleOrNull()
```

and `afield.stdlib` holds files carrying different `@file:CompletionCategory` values. The old
`firstNotNullOfOrNull` answered with whichever file the walk reached first:

| row | `declaredIn` | one walk order | another |
| --- | --- | --- | --- |
| `max(` | `afield.stdlib` | Math | Graphics |
| `main(` | `afield.stdlib` | Math | Visualization |
| `lineTo(` | `afield.stdlib` | Graphics | Solvers |
| `summary(` | `afield.stdlib` | Function | Polytopes |
| `loop(` | `afield` | Audio | Synthesis |

So this was precisely the defect `69ca4d5cb` diagnosed for the *name* vote, surviving one layer down in
the vote that replaced it. The repair takes the package's distinct non-null categories and returns
`singleOrNull()`: a contested package declines and falls through to path inference instead of borrowing
whichever file sorted first.

After the repair, the same test proves that two genuinely different definition orders classify all
6 679 rows identically. One of seven hand-cut cases moved (`List.filter`, visible rank 71 → 78), and the
population moved enough to invalidate the old weighting conclusion. That is why every table above was
recomputed rather than described as bounded by the old 1.0% figure.

## What this says to do instead

The shipped bare dot's real number is **0.420 recall on a receiver this tree cannot annotate**, against
0.918 on one it can. R2 closes 0.120 of that gap without lowering owned contested recall, but makes the
median visible foreign answer later. That measured trade-off is now the next ranking decision. Beyond it:

- **`@CommonMembers` on the receivers people actually use.** This is the one lever with a measured
  factor-of-ten behind it. `Graphics` — the only annotated receiver family — is the best-ranked population
  in the corpus (0.967, median rank 1), and the owned-versus-foreign split is the same story from the
  other side. Only `FLine` carries the annotation; the corpus holds 101 receivers.
- **The 80-row budget itself,** for foreign receivers specifically, where 100 of 100 bare carets are
  contested. Every one is the ranker choosing 80 of 110–571 rows, and no reweighting creates a row.
  Whether an empty prefix should show more than 80 rows is a product question this corpus can now answer.
- **A prior for types this tree does not own.** `@CommonMembers` cannot be written on `java.lang.Math`,
  which is the whole reason its bare dot is alphabetical. An external-annotation side table would be the
  first thing the corpus could measure honestly.
- **The `(uncategorised)` 118.** Not urgent (0.898 / rank 2), but it is the classification gap that
  `declaredIn` did not close.

Whatever is tried, `tools/rank_weight_lab.py` measures it in ten seconds against a corpus that can fail.

## Regenerating

The corpus needs re-recording when the sidecar's candidate set or ordering changes. Start a sidecar
built from your own checkout (`/health` reports its `workspaceRoot`) and re-run
`tools/record_rank_corpus.py`; the ranks will move and the floors in `probe.mjs` will say by how much.
Keep both caret forms and keep `proximity`, or the numbers stop being the popup's.
