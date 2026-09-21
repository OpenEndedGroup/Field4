# A λ-term you can hear — the measurements behind the page

Companion to [`public/docs/chain_lambda_sound.md`](../public/docs/chain_lambda_sound.md). The page
carries the figures; this carries the numbers, the reasoning, and the things that did not go on the
page. Every number here comes out of one run of the `synth.lambda.sound` FIELD_TEST
(`afield.four.synth.LambdaSoundDocs`), which is the same code the page's fences call.

Replay the whole thing with:

```
python3 tools/run_field_tests.py --id synth.lambda.sound
```

It takes about six seconds and writes nothing to the repository.

## The route

`LambdaTerms.parse` → `LambdaTerms.compile` → `Reducer.reduceDetailed` (`relaxIterations = 0`) →
one `LambdaSoundDocs.Beat` per reaction → a boolean β mask → `patCondense` → `patRenderStereo`.

Four subsystems: `afield.stdlib.lambdaterms`, `afield.stdlib.interactionnets`,
`afield.four.synth.PatCondense`, `afield.four.synth.PatSynth`. No missing bridge — everything the
route needed was already public, which is what makes this a C rather than a swing. The only new
code is the recorder and the assertions.

## What was measured, 2026-09-20

25 corpus terms × 4 reproducible `ActivationOrder`s = **100 reductions, 912 reactions.**

| claim | result |
|---|---|
| reaction count is the same under every scheduler | holds on all 25 |
| the *multiset of rules fired* is the same under every scheduler | holds on all 25 |
| the answer is α-equivalent to `referenceNormalOrder`, under every scheduler | holds on all 25 |
| the β count is the same under every scheduler | holds on all 25 |
| the β **mask** condenses to more than one notation | 9 of 25 terms |
| every reduced net ends with exactly one active pair | holds on all 100 |
| event kinds reached | `beta`, `prune`, `branch`, `annihilate` — 4 of the 6 declared |

Reaction counts, FIRST_FOUND (identical under the other three): `identity` 0, `church-zero` 0,
`bool-true` 0, `church-succ-zero` 3, `church-succ-two` 3, `self-apply-identity` 4,
`self-apply-redex` 5, `church-add-1-2` 6, `church-add-2-3` 6, `duplicate-a-redex` 6,
`share-under-lambda` 6, `skk` 7, `church-mul-2-3` 7, `church-pred-one` 7, `church-mul-3-3` 9,
`k-applied` 10, `bool-if-false` 10, `skk-applied` 12, `bool-and-applied` 12, `bool-if-true` 13,
`erase-nested` 13, `sks-applied` 17, `church-pred-three` 23, `erase-argument` 23,
`church-exp-two-cubed` 26.

Terms with more than one β spelling across the four schedulers: `sks-applied` (4),
`church-pred-three` (4), `church-exp-two-cubed` (4), `bool-and-applied` (3), `erase-argument` (3),
`skk-applied` (2), `church-mul-3-3` (2), `duplicate-a-redex` (2), `erase-nested` (2).

## The false claim this found

Three files said, in three wordings:

> `Run.steps` is a property of this rule book and this schedule, not of the term.

* `public/docs/interaction_nets_examples.md:19`
* `dev_docs/INTERACTION_NETS_LAMBDA.md`, "What to be careful of"
* the `LambdaTerms.Run` KDoc, `src/jvmMain/kotlin/afield/stdlib/lambdaterms.kt`

The rule-book half is right: a reaction here counts betas, fan meetings and erasures alike, so it
is not a β-step count and is not comparable to one. **The schedule half is false on every term
measured.** All three now say what was measured.

The reason is Lafont's, not this repository's: interaction nets are strongly confluent, because two
reactions available at once are on disjoint pairs of agents, so firing one can only move the other
and never destroy it — and a strongly confluent system that terminates takes the same number of
steps down every path. `RuleBook.findMatch` takes the *first* matching signature, so the rewrite
relation really is a function of the pair, which is the premise strong confluence needs.

### Why the corpus alone would not have been enough

The corpus is 25 terms the front end is known to be *right* on, so invariance measured only there
is a statement about a friendly population. `stdlib.lambda-terms.property` pinned four terms on
2026-09-19 where the fan bookkeeping is *wrong*, which is where a confluence failure would show.
`LambdaSoundDocs.CONFLUENCE_PROBES` copies those four verbatim and re-runs them:

| probe | reactions, all four orders |
|---|---|
| `quiet-wrong-branch` | 26 |
| `dup-of-an-erasing-abstraction` | 18 |
| `two-applied-to-two-unwalkable` | 19 |
| `compose-self-apply-scope-leak` | 12 |

Wrong answers, arrived at in a fixed number of steps. `assertTheProbesAreStillCounterexamples`
re-derives the disagreement before measuring, so if one of them ever starts agreeing with the
evaluator the probe fails rather than silently becoming four ordinary terms.

The copy is deliberate: `LambdaPropertyOracleTest.knownDisagreements` is private, and the two files
ask different questions of the same four terms. The assertion is what keeps the copy honest in the
direction that matters.

## Two smaller findings the page publishes

**Two of the six declared event kinds cannot be reached by a closed term**
(reproducer: `LambdaSoundDocs.kt#fun assertTheVocabularyIsWhatItSays`). `duplicate` comes only from
`dup-var`, `dup-const` and `dup-root`; `erase` only from `erase-var`, `erase-const` and
`erase-root`. All six need a nullary atom on the non-principal side — a *free* `VAR`, a `CONST`, or
the `ROOT` — and a closed term compiled by `LambdaTerms.compile` has no free variable, no constant
at all, and one `ROOT` whose principal faces the answer.
`LambdaSoundDocs.KINDS_UNREACHABLE_ON_CLOSED_TERMS` pins the pair with that reasoning attached, and
the reproducer asserts the seen set in **both** directions over all 100 reductions, so a rule-book
change that reaches one of the two fails rather than quietly widening the vocabulary.

**A normal form still has an active pair in it.** Exactly one, on all 100 reductions: `ROOT`'s
principal against the answer's principal, and nothing in `Lambda.rules` covers `ROOT ⟂ LAM`. So
"normal form" in this substrate means *no rule matches*, and a termination check written as
`net.activePairs().isEmpty()` never fires. `Reducer.step` already returns false correctly; the trap
is only for a caller who writes their own loop.

## What the audio is, and what it is not

Three offline scenes, all `patVoice` through `patRenderStereo`, no device and no realtime engine:

* `exp-first-found` — `three two`'s β mask under FIRST_FOUND, three cycles at 0.4 cps.
* `exp-rightmost` — the same term under RIGHTMOST_BY_ID. Same 26 reactions, same 8 βs, same answer.
* `erase-rule-melody` — `K I (mul 2 3)`'s rule sequence as pitch, one note per reaction. The only
  one of the page's four terms that reaches all four kinds a closed term can reach.

The two β bars have **identical length, peak and RMS by construction** — same strikes, same voice,
same tempo — so none of those three separates them and none of them is worth quoting. The harness
measures the correlation between the two waveforms instead and fails under 0.5; it is **0.375**.
That is the only honest form of "you can hear the difference" available here.

## Deliberate non-goals

* **No agent transcript.** Every figure is CPU work in a box, so `capture="box"` covers it. That
  keeps `DemoTranscriptExecSmokeTest`, `public/docs/agent_transcripts.md` and its three
  `<!-- transcript-index: totals -->` numbers, `regenerateAgentTranscriptStartingPoints` and
  `checkCompletionCorpusFixtures` entirely out of this change.
* **No `showInteractionNet` figure of the net itself.** `interaction_nets_examples.md` already has
  the five-panel reduction of `(λx. x x) (λy. y)` and does it better; repeating it here would be a
  second drawing of the same thing and would need `relaxIterations = 8`, which this page's whole
  argument is about not paying for.
* **No live reduction.** `Reducer` is `O(n²)` per relax iteration and `Net.incrementAges` is `O(n)`
  per reaction, so a watched reduction of anything interesting is a quadratic time budget. The page
  says so where it matters.
