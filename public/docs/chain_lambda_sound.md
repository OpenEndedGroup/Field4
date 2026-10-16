# A λ-term you can hear

This page reduces a λ-term step by step and turns the reduction into a rhythm you can listen to.
[`lambdaterms.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/lang/lambda/lambdaterms.kt) parses the term and
compiles it to an interaction net, and the `Reducer` in
[`interactionnets.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/data/graph/interactionnets.kt) fires one reaction at a time, reporting each as a `ReductionStep`.
[`LambdaSoundDocs`](reference/patterns.md#lambdasounddocs-kt-lambdasounddocs) records those steps
as a time series. `patCondense`, which runs the mini-notation parser backwards, finds the shortest
notation that plays the resulting step mask, and `patRenderStereo` plays it.

A pattern with a figure in it condenses to a shorter notation than one without, so the notation
`patCondense` returns tells you something about the order the reactions fired in. That order is
what changes when you change the scheduler.

## One reduction, recorded

`three two` is Church exponentiation the other way round: the numeral three applied to the numeral
two, which is 2³. Most of its reactions are not β-steps but the fan-and-eraser bookkeeping that
keeps sharing correct. The box below reduces it and plots the size of the net after each reaction.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/one_reduction.png" wait="10" capture="box" open-outputs="true" w="1180" h="360" padding="18" timeout="180" name="chain-lambda-one" embed=true alt="A two-trace plot over twenty-six reactions: an agent count that saws upward from twelve to a plateau of twenty-four around reaction twelve and then falls steadily to ten, and a lower trace of covered pairs that never leaves the range one to seven, above a table giving the term, fourteen agents and three fans compiled, twenty-six reactions of which eight are beta, the rule tally, and the answer as a lambda term"}
:::static
import afield.audio.synth.LambdaSoundDocs

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
| rules fired | ${t.ruleTally.entries.joinToString(", ") { "`${it.key}` x${it.value}" }} |
| read back as | `${t.printed}` |
| left standing | ${t.remainingNodes} agents, ${t.trailingReduciblePairs} pairs a rule covers |
""", width = 1080)
:::endfold
```


The lower trace is how many reactions were available at each moment, which is how much choice the
scheduler had. It stays small throughout: only a handful of pairs are ready to react at any time,
and the scheduler's job is to pick among them.

## The scheduler does not change how long it takes

`Reducer` takes an `ActivationOrder`, which chooses the next active pair to fire. Whichever order
you pick, a term takes the same number of reactions, and fires the same rules the same number of
times; only the order differs. The box below reduces every term in the λ front end's corpus under
all four schedulers and fails if any term breaks either rule.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/sweep.png" wait="25" capture="box" open-outputs="true" w="1180" h="560" padding="18" timeout="300" name="chain-lambda-sweep" embed=true alt="A table of twenty-two corpus terms sorted by how many distinct notations their beta mask condenses to: three terms at four spellings each, two at three, four at two and thirteen at one, with reaction counts from three to twenty-six and condensed notations ranging from x*3 to long mixtures of replicates and rests"}
:::static
import afield.audio.synth.LambdaSoundDocs

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
    .sortedWith(compareByDescending<Map.Entry<String, Map<ActivationOrder, LambdaSoundDocs.Trace>>> {
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


This follows from a result of Lafont's: interaction nets are strongly confluent. Two reactions
available at once involve disjoint pairs of agents, so firing one cannot destroy the other, and a
strongly confluent system that terminates reaches its normal form in the same number of steps down
every path.

## What the scheduler does change

Because the order differs, the β-steps land in different places, and `patCondense` can return a
different notation for each scheduler. The box below takes `three two` under all four schedulers
and draws where its β-steps fall.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/four_schedules.png" wait="14" capture="box" open-outputs="true" w="1180" h="420" padding="18" timeout="240" name="chain-lambda-four" embed=true alt="Four coloured piano-roll lanes of one cycle each, all with eight strikes in twenty-six slots but in visibly different places: FIRST_FOUND ends with four strikes packed against the right edge, LEFTMOST_BY_ID and HIGHEST_PAIR_ENERGY break theirs into pairs across the middle, and RIGHTMOST_BY_ID spreads its last four out singly. Below, a table giving all four schedulers the same twenty-six reactions and eight betas against four different condensed notations and compressions of 0.43, 0.61, 0.57 and 0.65, all four signed for by the search"}
:::static
import afield.audio.synth.LambdaSoundDocs

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


FIRST_FOUND lands its last four β-steps against the end of the bar and the notation says so: a long
rest and then `x!4`. RIGHTMOST_BY_ID spreads the same eight out far enough that the condenser finds
a Euclidean figure in them and spells part of the bar as `x(4,13,3)`. Nothing in the condenser knows
what a λ-term is; it is looking for the shortest string that plays what it was handed.

Compression is a ratio of characters, so the length of the atom affects it. Both bars above use
`x`, as [the CA chain](chain_ca_sound.md) does. Compare two notations only when they use the same
atom.

Here is FIRST_FOUND's bar, three cycles at 0.4 cycles per second:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/exp-first-found.wav" audio-spec="exp-first-found" audio-renderer="afield.audio.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-first-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("exp-first-found").
```

And RIGHTMOST_BY_ID's, same term, same twenty-six reactions, same eight β-steps, same answer:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/exp-rightmost.wav" audio-spec="exp-rightmost" audio-renderer="afield.audio.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-rightmost-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("exp-rightmost").
```

The two clips have the same strikes, voice and tempo, so they match in length and loudness. They
differ only in where the strikes fall.

## The reduction as pitch

A rule emits an event when it fires. A closed term emits four kinds of event, `beta`, `branch`,
`prune` and `annihilate`; map those to four degrees of a minor pentatonic and a reduction becomes a
tune whose notes are its rule sequence.

```diffstatic {data-lang=kotlin}
val notes = LambdaSoundDocs.trace("erase-argument").melody
// -> one MIDI note per reaction, pitched by the kind that reaction emitted
```

`K I (mul 2 3)` is the corpus's erasing term: it computes six and throws it away. Many of its
reactions are `prune`, taking the discarded multiplication apart agent by agent, because an
interaction net cannot simply drop a wire.

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/erase-rule-melody.wav" audio-spec="erase-rule-melody" audio-renderer="afield.audio.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-melody-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("erase-rule-melody").
```

## Where each piece is documented

* [Interaction Nets examples](interaction_nets_examples.md) for the agents, the rule book, the
  `Reducer` and the λ front end, and for the rare untyped terms the readback cannot read.
* [Patterns & Rhythm](pattern_language.md) for the event model and the mini-notation, and
  [`PAT_CONDENSE.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/PAT_CONDENSE.md)
  for the condenser, the bounds it searches under and what it refuses.
* [Sound](sound.md) and [Making a Sound, Every Path There Is](sound_paths.md) for the voice used
  here and how audio is rendered offline for these pages.
* [A cellular automaton you can hear](chain_ca_sound.md) uses the same mask, condense and play
  steps, fed by an elementary cellular automaton instead of a reduction.
