# The rank a person sees, measured over 578 carets

**Status, 2026-08-17.** The popup's ranking is now a measured system, and the decision it was measured
for — should `CATEGORY_WEIGHT` learn the server's category vocabulary? — is **refused, with the
number**. The exposure figure that has stood in `completion_aa_sidecar.md` since 2026-08-07 (11 of the
16 categories the server sends score 0; measured here at **89.6% of all recorded rows**) is real and
**not load-bearing**: closing it changes visible recall by 0.000 and MRR by +0.005.

Everything below is reproducible from this checkout. Nothing in it is an estimate.

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

Measured 2026-08-17 on this checkout. Floors sit just under each figure, in `probe.mjs` beside the code
that computes them.

| form | cases | in payload | contested | recall | contested recall | MRR | median rank |
| --- | --- | --- | --- | --- | --- | --- | --- |
| prefixed | 294 | 0.881 | 101 | 0.881 | **0.822** | **0.647** | 1 |
| bare | 284 | 0.908 | 236 | 0.694 | **0.703** | **0.124** | 13 |
| all | 578 | 0.894 | 337 | 0.789 | 0.739 | 0.390 | 3 |

"Contested" means the caret sent more rows than the popup can show, so the popup had to leave something
out. The headline is restricted to that subset for a reason that has bitten this project repeatedly: a
recall-at-80 over carets that send fewer than 80 rows is 1.0 by construction and *could not have
failed*. The test asserts that at least 80 bare carets are contested, that at least 100 rows resolve a
`@CommonMembers` prior, that at least 10 answers are declared `universal`, and that at least 100 answers
carry a category `CATEGORY_WEIGHT` scores 0 — i.e. that every term the conclusion rests on is exercised.

**The two left-hand columns are the point of the exercise.** Payload recall is what every other
completion measurement in this tree reports; visible recall is the user's. At a bare dot they are
**0.908 and 0.694** — a 21-point gap, 88 carets whose answer the server computed and the popup did not
show. That is "96% recall hid the answer at index 159 of 249", at population scale.

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

| effect | cases moved (of 456 found) | median | p90 of those moved |
| --- | --- | --- | --- |
| sectioning (visible − flat) | 96 | 0 | 13 rows |
| category weight (neutral − flat) | 120 | 0 | 6 rows |

So after last night's classification fix, **sectioning is an outlier cost and not a systemic one** —
`FLine.moveTo` rendered at row 76 was the tail of the distribution, not its shape. The case counts are
reported next to the medians deliberately: a median of 0 with 96 cases moved and a p90 of 13 is a real
effect that a median alone denies.

### Per category, over the carets whose answer carries that category

`w` marks a category `CATEGORY_WEIGHT` scores above zero.

| category | w | cases | recall | median rank |
| --- | --- | --- | --- | --- |
| Kotlin | · | 165 | 0.758 | 7 |
| (uncategorised) | · | 107 | 0.916 | 2 |
| (absent) | · | 61 | 0.000 | — |
| Graphics | w | 61 | 0.967 | 1 |
| External | · | 36 | 0.889 | 9.5 |
| Audio | · | 33 | 1.000 | 2 |
| Data | · | 26 | 1.000 | 2 |
| Math | · | 20 | 0.950 | 2 |
| Visualization | · | 19 | 0.947 | 2 |
| Polytopes | · | 15 | 1.000 | 10 |
| ALife | · | 13 | 1.000 | 2 |
| Function | · | 9 | 0.778 | 4 |
| Imaging | · | 7 | 0.714 | 2 |

`(absent)` at recall exactly 0.000 is a consistency check on the truth matcher, not a finding.
`(uncategorised)` is a *classification* gap rather than a weighting one, and at 0.916 / rank 2 it is not
costing anything today.

The shape that decides the question is in the first and fifth rows. **The two worst-ranked populations
in the popup are `Kotlin` (165 cases, 0.758, median 7) and `External` (36, 0.889, median 9.5)** — and
those are exactly the two categories that "teach the table Field's vocabulary" pushes *down*.

## The decision: the rebalance is refused

`tools/rank_weight_lab.py` runs the whole sweep in about a minute. Every row is the same 578 carets and
the same recorded payloads; only the constant differs.

| variant | prefixed c-recall | bare c-recall | all recall | MRR (all) | exposure |
| --- | --- | --- | --- | --- | --- |
| R0 as shipped | 0.822 | **0.703** | **0.789** | 0.390 | 89.6% |
| R1 server vocabulary at 24, kotlin/external 0 | 0.822 | **0.648** | 0.766 | 0.403 | 71.6% |
| R2 server vocabulary at 12, kotlin 8, external 4 | 0.822 | **0.703** | **0.789** | 0.395 | 10.6% |
| R3 kotlin 24 only | 0.822 | 0.699 | 0.787 | 0.389 | 32.6% |
| R4 kotlin 24, external 24 | 0.822 | 0.699 | 0.787 | 0.390 | 28.6% |
| R5 `universal` −85 → −40 | 0.822 | 0.703 | 0.789 | 0.389 | 89.6% |
| R6 `universal` −85 → −140 | 0.822 | 0.703 | 0.789 | 0.390 | 89.6% |

Read R2 against R0. **A rebalance that teaches `CATEGORY_WEIGHT` every category the server sends —
taking the exposure figure from 89.6% of rows to 10.6% — changes visible recall by 0.000 in all three
forms and MRR by +0.005.** Nobody would feel that. Read R1 against R0: the same rebalance at a
magnitude large enough to reorder sections costs **5.5 points of bare-dot contested recall**, 13 answers
pushed off the screen entirely, because the 80 rows are a fixed budget and lifting one bucket by 24
points evicts another bucket's rows. Recall is a cliff; a rank of 2 versus 1 is not. That trade is the
wrong way round.

R5 and R6 are worth recording for the same reason: **changing the largest single term in the ranker by
±55 points moves nothing measurable.** At a bare dot every `universal` row takes the same penalty, so
the term is a uniform shift within a group and only bites at a group boundary.

So the honest two-part statement is:

1. `CATEGORY_WEIGHT` measurably affects the **order** of about a quarter of answers (120 of 456), by up
   to 6 rows at p90.
2. It affects the **visibility** of 0 of 578 answers at any magnitude that does not also evict
   something, and −5.5 points at a magnitude that does.

**The claim that would have made this refusal fail, and why it is not vacuous:** if the harness were
insensitive to `CATEGORY_WEIGHT`, "changing it does nothing" would be a bug in the measurement rather
than a fact about the ranker. R1 is that falsification, and it is in the recorded sweep: the same
harness, the same corpus, moves bare contested recall by −0.055 when the table changes by 24 points. The
harness is sensitive; the popup is not.

### What this says to do instead

The bare dot's real number is `bare recall 0.694` against `in payload 0.908`. That gap is 88 carets, and
none of the seven variants above closes any of it. What is left, in order of measured size:

- **The 80-row budget itself.** Every contested bare caret is the ranker choosing 80 of 100–571 rows,
  and no reweighting creates a row. Whether the popup should show more than 80 rows when the prefix is
  empty is a *product* question this corpus can now answer either way.
- **More `@CommonMembers` priors.** `Graphics`, the one annotated receiver family, is the best-ranked
  population in the table (0.967, median rank 1). Only `FLine` carries the annotation. 100 distinct
  receivers in the corpus do not.
- **The `(uncategorised)` 107.** Not urgent (0.916 / rank 2), but it is the classification gap that
  `declaredIn` did not close.

Whatever is tried, `tools/rank_weight_lab.py` measures it in ten seconds against a corpus that can fail.

## Regenerating

The corpus needs re-recording when the sidecar's candidate set or ordering changes. Start a sidecar
built from your own checkout (`/health` reports its `workspaceRoot`) and re-run
`tools/record_rank_corpus.py`; the ranks will move and the floors in `probe.mjs` will say by how much.
Keep both caret forms and keep `proximity`, or the numbers stop being the popup's.
