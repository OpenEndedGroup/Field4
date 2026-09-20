# A λ-term you can hear

Four steps, four parts of Field. `lambdaterms.kt` parses a λ-term and compiles it to an interaction
net. `interactionnets.kt`'s `Reducer` fires one reaction at a time and hands back a `ReductionStep`
for each. [`LambdaSoundDocs`](reference/patterns.md#lambdasounddocs-kt-lambdasounddocs) records
those into a time series. `patCondense` — the mini-notation parser run backwards — finds the
shortest notation that plays the resulting step mask, and `patRenderStereo` plays it.

The reason to take a reduction through a *rhythm* compressor rather than a plot is that the
compressor is a measurement. A sequence with a figure in it gets a much shorter spelling than one
without, so the length of what `patCondense` returns is a number about the schedule that produced
the sequence. Which turns out to be the only thing about a reduction here that a schedule *can*
change.

## One reduction, recorded

`three two` is Church exponentiation the other way round — the numeral three applied to the numeral
two, which is 2³. It reduces in twenty-six reactions, eight of which are β and the rest of which
are the fan-and-eraser bookkeeping that makes sharing correct.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/one_reduction.png" wait="10" capture="box" open-outputs="true" w="1180" h="360" padding="18" timeout="180" name="chain-lambda-one" embed=true}
:::static
import afield.four.synth.LambdaSoundDocs
import afield.stdlib.ActivationOrder

// trace() compiles, reduces and records; it does not relax the layout, because nothing is
// watching. The answer it read back is checked against the plain normal-order evaluator below.
val t = LambdaSoundDocs.trace("church-exp-two-cubed", ActivationOrder.FIRST_FOUND)

:::fold the net's size, the choice the scheduler had, and what came out
showSignals(
    "agents standing" to t.nodeSeries,
    "pairs a rule covers" to t.choiceSeries,
    // Short, because a plotly title wider than the plot is clipped rather than wrapped.
    title = "three two: after each of its 26 reactions",
    xTitle = "reaction",
    channel = "series",
)

markdown("readout", """
| | |
|---|---|
| term | `${t.source}` |
| compiled to | ${t.initialNodes} agents, ${t.fans} labelled fans |
| reactions | ${t.steps} |
| of those, β | ${t.betas} |
| rules fired | ${t.ruleTally.entries.joinToString(", ") { "`${'$'}{it.key}` ×${'$'}{it.value}" }} |
| read back as | `${t.printed}` |
| left standing | ${t.remainingNodes} agents, ${t.trailingActivePairs} active pair |
""", width = 1080)
:::endfold
```

![A two-trace plot over twenty-six reactions: an agent count that climbs from about twenty to a peak near forty before falling to ten, and a lower trace of covered pairs that stays between one and four the whole way, above a table giving the term, its compiled size, twenty-six reactions of which eight are beta, the rule tally, and the answer as a lambda term](assets/chain_lambda_sound/one_reduction.png)

The lower trace is the part to read. It is how many reactions were *available* at each moment —
how much choice the scheduler had — and it is never zero until the end and never large. A
reduction here is not a wavefront sweeping a big net; it is a handful of live collisions at a time,
and picking among them is the scheduler's entire job.

!!! note "A normal form still has an active pair in it"

    The last column above says one, and it says one for every term in the corpus under every
    scheduler. `ROOT`'s principal port faces the answer's principal port, and nothing in
    `Lambda.rules` covers `ROOT ⟂ LAM`. So a normal form in this substrate is **"no rule
    matches"**, not "nothing is touching", and a termination check written as
    `net.activePairs().isEmpty()` never fires.

## The scheduler does not change how long it takes

`Reducer` takes an `ActivationOrder`, and the obvious expectation is that a different order costs a
different number of reactions. Over the front end's whole 25-term corpus under four schedulers —
100 reductions, 912 reactions — it does not. Not the count, and not even the mix: every schedule
fires *the same multiset of rules*, in a different order.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/sweep.png" wait="25" capture="box" open-outputs="true" w="1180" h="560" padding="18" timeout="300" name="chain-lambda-sweep" embed=true}
:::static
import afield.four.synth.LambdaSoundDocs

// Both of these throw rather than return false. Reaching the next line is the measurement:
// stepCountIsScheduleInvariant names the term and the counts if any term breaks it, and
// ruleMultisetIsScheduleInvariant is the stronger claim that the mixes match too.
val sweep = LambdaSoundDocs.corpusSweep()
val counts = LambdaSoundDocs.stepCountIsScheduleInvariant(sweep)
val multisets = LambdaSoundDocs.ruleMultisetIsScheduleInvariant(sweep)
val spellings = LambdaSoundDocs.spellingsPerTerm(sweep)

:::fold the corpus, worst-spread first
val rows = sweep.entries
    .filter { counts.getValue(it.key) > 0 }
    .sortedWith(compareByDescending<Map.Entry<String, Map<afield.stdlib.ActivationOrder, LambdaSoundDocs.Trace>>> {
        spellings.getValue(it.key)
    }.thenByDescending { counts.getValue(it.key) })
    .joinToString("\n") { (name, traces) ->
        val first = traces.values.first()
        "| `$name` | ${counts.getValue(name)} | ${first.betas} | ${first.fans} | " +
            "${spellings.getValue(name)} | `${first.betaCondensation.notation}` |"
    }

markdown("sweep", """
${sweep.size} terms x ${LambdaSoundDocs.ORDERS.size} schedulers = ${sweep.size * LambdaSoundDocs.ORDERS.size} reductions.
Reaction count is scheduler-invariant on **all ${counts.size}**; so is the rule multiset, on all $multisets.
Three corpus terms are already normal forms and are left out of the table.

| term | reactions | β | fans | distinct β spellings | the FIRST_FOUND spelling |
|---|---|---|---|---|---|
$rows
""", width = 1120)
:::endfold
```

![A table of twenty-two corpus terms sorted by how many distinct notations their beta mask condenses to: four terms at four spellings each, one at three, four at two, and the rest at one, with reaction counts from three to twenty-six and condensed notations ranging from x*3 to long mixtures of replicates and rests](assets/chain_lambda_sound/sweep.png)

That is a stronger statement than "the answer does not depend on the scheduler", which
`stdlib.lambda-terms.property` already established over 17 200 checks. The *route* does not either,
except in its order.

Interaction nets are strongly confluent: two reactions available at once are on disjoint pairs of
agents, so firing one cannot destroy the other, only move it — and a strongly confluent system that
terminates reaches its normal form in the same number of steps down every path. That is Lafont's
result and not a discovery of this page. What this page does is measure it in *this* implementation,
where it is not a given, because the fan labelling is known to be wrong on some terms.

!!! warning "A published page said the opposite, and this is the correction"

    [Interaction Nets examples](interaction_nets_examples.md) said, until this page was written:
    "`Run.steps` is a property of this rule book and this schedule, not of the term." The rule-book
    half is right. The schedule half is false on every term measured, and it is the half a reader
    would act on — it is the sentence that tells you a step count is not worth comparing.
    `dev_docs/INTERACTION_NETS_LAMBDA.md` carried the same claim and the `Run` KDoc carried it a
    third time. All three now say what was measured instead.

The four terms where the reducer is known to be *wrong* are the interesting stress case, because a
confluence failure is exactly what could break the count. `stdlib.lambda-terms.property` pinned four
of them on 2026-09-19 — a wrong normal form with no warning mark, a duplicated abstraction that
loses a copy to its eraser, an answer the readback cannot walk, and a readback of a closed term with
a free variable in it. `LambdaSoundDocs.CONFLUENCE_PROBES` re-runs those four under the four
schedulers and reports the counts: **26, 18, 19 and 12 reactions, the same under every order.**
Wrong answers, arrived at in a fixed number of steps.

## What the scheduler does change

Same reactions, same count, different *order* — so the β-steps land in different places, and
`patCondense` returns a different string. Nine of the twenty-five corpus terms condense to more than
one notation across the four schedulers. Here is one of them, four ways.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/four_schedules.png" wait="14" capture="box" open-outputs="true" w="1180" h="420" padding="18" timeout="240" name="chain-lambda-four" embed=true}
:::static
import afield.four.synth.LambdaSoundDocs

// The same term the two clips below play, under each of the four reproducible schedulers.
// answerIsScheduleInvariant checks all four against the plain normal-order evaluator, so the
// "one answer" column is a measurement and not a caption.
val traces = LambdaSoundDocs.traceAllOrders(LambdaSoundDocs.BAR_TERM)
val answer = LambdaSoundDocs.answerIsScheduleInvariant(traces)
val betas = LambdaSoundDocs.betaIsScheduleInvariant(traces)

:::fold the four beta masks as lanes, then their spellings
showPatRollOf(
    *traces.map { (order, t) -> order.name to t.betaCondensation.pattern }.toTypedArray(),
    title = "three two: where the $betas beta-steps fall, under four schedulers",
    subtitle = "one slot per reaction, ${traces.values.first().steps} reactions, one cycle each",
    cycles = 1,
    channel = "masks",
)

val rows = traces.entries.joinToString("\n") { (order, t) ->
    "| `${order.name}` | ${t.steps} | ${t.betas} | `${t.betaCondensation.notation}` | " +
        "${"%.2f".format(t.betaCompression)} | ${t.betaCondensation.exact} |"
}
markdown("spellings", """
All four read back as `$answer`.

| scheduler | reactions | β | shortest notation | against a flat spelling | the search signed for it |
|---|---|---|---|---|---|
$rows
""", width = 1080)
:::endfold
```

![Four piano-roll lanes of one cycle each, all with eight strikes in twenty-six slots but in visibly different places — the first clustered four-at-the-end, the others spread — above a table giving each scheduler's identical reaction and beta counts against four different condensed notations and compressions from 0.43 to 0.65](assets/chain_lambda_sound/four_schedules.png)

FIRST_FOUND lands its last four β-steps against the end of the bar and the notation says so: a long
rest and then `x!4`. RIGHTMOST_BY_ID spreads the same eight out far enough that the condenser finds
a Euclidean figure in them and spells part of the bar as `x(4,13,3)`. Nothing in the condenser knows
what a λ-term is; it is looking for the shortest string that plays what it was handed.

Compression is a ratio of characters, so the atom's length moves it — both bars above use `x`,
which is the same care [the CA chain](chain_ca_sound.md) takes for the same reason. Compare two
spellings only when they are spelled with the same atom.

Here is FIRST_FOUND's bar, three cycles at 0.4 cycles per second:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/exp-first-found.wav" audio-spec="exp-first-found" audio-renderer="afield.four.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-first-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("exp-first-found").
```

And RIGHTMOST_BY_ID's, same term, same twenty-six reactions, same eight β-steps, same answer:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/exp-rightmost.wav" audio-spec="exp-rightmost" audio-renderer="afield.four.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-rightmost-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("exp-rightmost").
```

Those two clips are the same length, the same peak and the same RMS, necessarily — same strikes,
same voice, same tempo. What separates them is where the strikes are, and the harness measures that
rather than asserting it: the two waveforms correlate at 0.375, which is the number it fails under.

## The reduction as pitch

A rule emits an event when it fires, and `Lambda.rules` declares a six-word vocabulary for them:
`beta`, `duplicate`, `branch`, `prune`, `erase`, `annihilate`. Map those to six degrees of a minor
pentatonic and a reduction becomes a tune whose notes are its rule sequence.

```diffstatic {data-lang=kotlin}
val notes = LambdaSoundDocs.trace("erase-argument").melody
// -> one MIDI note per reaction, pitched by the kind that reaction emitted
```

`K I (mul 2 3)` is the corpus's erasing term: it computes six and throws it away. Nine of its
twenty-three reactions are `prune`, taking the discarded multiplication apart agent by agent,
because an interaction net may not simply drop a wire.

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/erase-rule-melody.wav" audio-spec="erase-rule-melody" audio-renderer="afield.four.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-melody-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("erase-rule-melody").
```

!!! note "Two of the six degrees are silent on every closed term"

    `duplicate` comes only from `dup-var`, `dup-const` and `dup-root`, and `erase` only from
    `erase-var`, `erase-const` and `erase-root`. All six need a nullary atom on the non-principal
    side: a *free* `VAR`, a `CONST`, or the `ROOT` itself. A closed term compiled by
    `LambdaTerms.compile` has no free variable, no constant at all, and one `ROOT` whose principal
    port faces the answer. So the whole corpus only ever reaches four of the six kinds, and
    `LambdaSoundDocs.KINDS_UNREACHABLE_ON_CLOSED_TERMS` names the other two with that reasoning
    attached — asserted, so a rule-book change that reaches them fails a test rather than quietly
    widening the tune.

## Where each piece is documented

* [Interaction Nets examples](interaction_nets_examples.md) for the agents, the rule book, the
  `Reducer` and the λ front end, and for what the sharing gets wrong and on which shapes.
  `dev_docs/INTERACTION_NETS_LAMBDA.md` is the whole ledger.
* [Patterns & Rhythm](pattern_language.md) for the event model and the mini-notation, and
  `dev_docs/PAT_CONDENSE.md` for the condenser, the bounds it searches under and what it refuses.
* [Sound](sound.md) and [Making a Sound, Every Path There Is](sound_paths.md) for the stand-in voice
  and for why every audio figure in these docs comes out of the offline `wav-file` sink.
* [A cellular automaton you can hear](chain_ca_sound.md) is the neighbouring chain: the same
  `mask → patCondense → .wav` half of this route, fed by an elementary rule instead of by a
  rewrite schedule, and using the condensation to separate two rules rather than two schedulers.
