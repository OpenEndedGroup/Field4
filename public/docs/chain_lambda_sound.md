# A λ-term you can hear

What might a computation sound like as it runs? Let's reduce a λ-term step by step and turn its
reactions into a rhythm you can listen to.
[`lambdaterms.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/lang/lambda/lambdaterms.kt) parses the term and
compiles it to an interaction net (a graph of agents that rewrites itself one
connected pair, a *reaction*, at a time), and the `Reducer` in
[`interactionnets.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/data/graph/interactionnets.kt) fires one reaction at a time, reporting each as a `ReductionStep`.
[`LambdaSoundDocs`](reference/patterns.md#lambdasounddocs-kt-lambdasounddocs) records those steps
as a time series. `patCondense`, which runs the mini-notation parser backwards, finds the shortest
notation that plays the resulting step mask, and `patRenderStereo` plays it.

A pattern with a figure in it condenses to a shorter notation than one without, so the notation
`patCondense` returns tells you something about the order the reactions fired in. That order is
what changes when you change the scheduler.

## One reduction, recorded

`three two` is Church exponentiation the other way round: the numeral three applied to the numeral
two, which is 2³. Most of its reactions are not β-steps (applying a function to its argument)
but the bookkeeping that keeps sharing
correct: fans copying, and scope delimiters (the agents that mark where a shared function body
begins and ends) moving and closing. The box below parses the term, compiles its net and records
its size after each reaction. Change `source` to reduce a term of your own, or `order` to choose
another scheduler. [`LambdaSoundDocs.trace`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--trace-2)
packages this same recording loop for reuse.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/one_reduction.png" wait="10" capture="box" open-outputs="true" w="1180" h="360" padding="18" timeout="180" name="chain-lambda-one" embed=true alt="A two-trace plot over eighty-six reactions: an agent count that climbs from about nineteen to a peak of forty-six around reactions thirty-five and forty-five and then falls, in steps, to twenty-six, and a lower trace of covered pairs that rises to fifteen and comes back down to zero, above a table giving the term, nineteen agents and three fans compiled, eighty-six reactions of which eight are beta, the rule tally led by delim-delim, and the answer λa. λb. a applied eight times to b"}
:::static
val name = "church-exp-two-cubed"
val source = LambdaTerms.corpus().getValue(name)
val order = ActivationOrder.FIRST_FOUND
val maxSteps = 20_000
val term = LambdaTerms.parse(source)
val compiled = LambdaTerms.compile(term)
val rules = Lambda.rules()
// This records reactions, so no layout relaxation is needed.
val reducer = Reducer(rules, order = order, relaxIterations = 0)
val initialNodes = compiled.net.nodes.size
val beats = mutableListOf<LambdaSoundDocs.Beat>()
reducer.reduceDetailed(compiled.net, maxSteps = maxSteps) { index, net, step ->
    val pairs = net.activePairs()
    beats +=
        LambdaSoundDocs.Beat(
            index = index,
            rule = step.ruleName,
            kinds = step.events.map { it.kind },
            nodes = net.nodes.size,
            activePairs = pairs.size,
            reduciblePairs = pairs.count { rules.findMatch(it.left, it.right) != null },
        )
}

:::fold the net's size, the choice the scheduler had, and what came out
// The record supplies the report's derived counts and formatting.
val t = LambdaSoundDocs.Trace(
    name = name,
    source = source,
    order = order,
    term = term,
    result = LambdaTerms.readback(compiled),
    beats = beats,
    fans = compiled.fans,
    initialNodes = initialNodes,
    net = compiled.net,
    budget = maxSteps,
)
showSignals(
    "agents standing" to t.nodeSeries,
    "pairs a rule covers" to t.choiceSeries,
    // Short, because a plotly title wider than the plot is clipped rather than wrapped.
    title = "three two: after each of its 86 reactions",
    xTitle = "reaction",
    channel = "series",
)

markdown(
    "readout",
    """
| | |
|---|---|
| term | `${t.source}` |
| compiled to | ${t.initialNodes} agents, ${t.fans} fans |
| reactions | ${t.steps} |
| of those, β | ${t.betas} |
| rules fired | ${t.ruleTally.entries.joinToString(", ") { "`${it.key}` x${it.value}" }} |
| read back as | `${t.printed}` |
| left standing | ${t.remainingNodes} agents, ${t.trailingReduciblePairs} pairs a rule covers |
""",
    width = 1080,
)
:::endfold
```


The lower trace is how many reactions were available at each moment, which is how much choice the
scheduler had. It stays small throughout: only a handful of pairs are ready to react at any time,
and the scheduler's job is to pick among them.

## The scheduler does not change how long it takes

`Reducer` takes an `ActivationOrder`, which chooses the next active pair to fire. The order can
change, but the reaction count and the number of times each rule fires stay the same. The box below
reduces every term in the λ front end's corpus under all four schedulers and fails if any term
breaks either rule.

Packaged versions of these loops are available for later reuse:
[`LambdaSoundDocs.corpusSweep`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--corpussweep) runs the reductions;
[`LambdaSoundDocs.stepCountIsScheduleInvariant`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--stepcountisscheduleinvariant) and
[`LambdaSoundDocs.ruleMultisetIsScheduleInvariant`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--rulemultisetisscheduleinvariant) check the two claims.
[`LambdaSoundDocs.spellingsPerTerm`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--spellingsperterm) counts the different condensed notations.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/sweep.png" wait="25" capture="box" open-outputs="true" w="1180" h="560" padding="18" timeout="300" name="chain-lambda-sweep" embed=true alt="A table of twenty-two corpus terms sorted by reaction count, from church-pred-three at 127 down to self-apply-identity at 10: every term but the last condenses to four distinct notations across the four schedulers, the last to three, and the FIRST_FOUND spellings are long mixtures of single strikes, rests and the odd replicate"}
:::static
// The recorder above is reused here; the comparisons are the experiment.
val orders = [
    ActivationOrder.FIRST_FOUND,
    ActivationOrder.LEFTMOST_BY_ID,
    ActivationOrder.RIGHTMOST_BY_ID,
    ActivationOrder.HIGHEST_PAIR_ENERGY,
]
val names = LambdaTerms.corpus().keys
val sweep = names.associateWith { name ->
    orders.associateWith { order -> LambdaSoundDocs.trace(name, order) }
}

val counts = sweep.mapValues { (name, traces) ->
    val measured = traces.values.map { it.steps }.distinct()
    check(measured.size == 1) { "$name: reaction counts differ: $measured" }
    measured.single()
}

// Equal counts alone would miss different mixtures of rules.
for ((name, traces) in sweep) {
    val tallies = traces.values.map { it.ruleTally.toSortedMap() }.distinct()
    check(tallies.size == 1) { "$name: rule multisets differ: $tallies" }
}
val multisets = sweep.size
val spellings = sweep.mapValues { (_, traces) ->
    traces.values.map { it.betaCondensation.notation }.distinct().size
}

:::fold the corpus, worst-spread first
val rows =
    sweep.entries
        .filter { counts.getValue(it.key) > 0 }
        .sortedWith(
            compareByDescending<Map.Entry<String, Map<ActivationOrder, LambdaSoundDocs.Trace>>> {
                    spellings.getValue(it.key)
                }
                .thenByDescending { counts.getValue(it.key) },
        )
        .joinToString("\n") { (name, traces) ->
            val first = traces.values.first()
            "| `$name` | ${counts.getValue(name)} | ${first.betas} | ${first.fans} | " +
                "${spellings.getValue(name)} | `${first.betaCondensation.notation}` |"
        }

markdown(
    "sweep",
    """
${sweep.size} terms x ${orders.size} schedulers = ${sweep.size * orders.size} reductions.
Reaction count is scheduler-invariant on **all ${counts.size}**; so is the rule multiset, on all $multisets.
Three corpus terms are already normal forms and are left out of the table.

| term | reactions | β | fans | distinct β spellings | the FIRST_FOUND spelling |
|---|---|---|---|---|---|
$rows
""",
    width = 1120,
)
:::endfold
```


This follows from a result of Lafont's: interaction nets are strongly confluent. Two reactions
available at once involve disjoint pairs of agents, so firing one cannot destroy the other, and a
strongly confluent system that terminates reaches its normal form in the same number of steps down
every path.

## What the scheduler does change

Open [Confluence grid](agent_transcripts.md#confluence_grid) to compare five terms under four
schedulers. A row follows one term under each scheduler, so you can see the different
intermediate graphs on the way to the same normal form.

Because the order differs, the β-steps land in different places, and `patCondense` can return a
different notation for each scheduler. The box below takes `three two` under all four schedulers
and draws where its β-steps fall. The loop below reuses the recorder above, then compares each readback with a plain evaluator.
The packaged equivalents are [`LambdaSoundDocs.traceAllOrders`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--traceallorders), for the reductions,
[`LambdaSoundDocs.answerIsScheduleInvariant`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--answerisscheduleinvariant) for the readbacks, and
[`LambdaSoundDocs.betaIsScheduleInvariant`](reference/patterns.md#lambdasounddocs-kt--lambdasounddocs--betaisscheduleinvariant).

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_lambda_sound/four_schedules.png" wait="14" capture="box" open-outputs="true" w="1180" h="420" padding="18" timeout="240" name="chain-lambda-four" embed=true alt="Four coloured piano-roll lanes of one cycle each, all with eight strikes in eighty-six slots but in visibly different places: FIRST_FOUND spreads four through the first half and packs its last four together near the end, LEFTMOST_BY_ID and HIGHEST_PAIR_ENERGY break theirs into pairs, and RIGHTMOST_BY_ID puts four early and spreads its last four out singly. Below, all four read back as the same answer, and a table gives every scheduler the same eighty-six reactions and eight betas against four different condensed notations and compressions of 0.28, 0.28, 0.26 and 0.27, all four signed for by the search"}
:::static
val name = "church-exp-two-cubed"
val orders = [
    ActivationOrder.FIRST_FOUND,
    ActivationOrder.LEFTMOST_BY_ID,
    ActivationOrder.RIGHTMOST_BY_ID,
    ActivationOrder.HIGHEST_PAIR_ENERGY,
]
val traces = orders.associateWith { order -> LambdaSoundDocs.trace(name, order) }

// Compare every readback with an independent, plain lambda-calculus evaluator.
val term = LambdaTerms.corpus().getValue(name)
val oracle = LambdaTerms.referenceNormalOrder(LambdaTerms.parse(term))
for ((order, trace) in traces) {
    check(trace.finished) { "$name/$order: exhausted ${trace.budget} reactions" }
    check(LambdaTerms.alphaEquivalent(trace.result, oracle)) {
        "$name/$order: ${trace.printed} differs from ${LambdaTerms.format(oracle)}"
    }
}
val answers = traces.values.map { LambdaTerms.deBruijnKey(it.result) }.distinct()
check(answers.size == 1) { "$name: schedulers disagree: $answers" }
val answer = LambdaTerms.format(oracle)

val betaCounts = traces.values.map { it.betas }.distinct()
check(betaCounts.size == 1) { "$name: beta counts differ: $betaCounts" }
val betas = betaCounts.single()

:::fold the four beta masks as lanes, then their spellings
showPatRollOf(
    *traces.map { (order, t) -> order.name to t.betaCondensation.pattern }.toTypedArray(),
    title = "three two: where the $betas beta-steps fall, under four schedulers",
    subtitle = "one slot per reaction, ${traces.values.first().steps} reactions, one cycle each",
    cycles = 1,
    channel = "masks",
)

val rows =
    traces.entries.joinToString("\n") { (order, t) ->
        "| `${order.name}` | ${t.steps} | ${t.betas} | `${t.betaCondensation.notation}` | " +
            "${t.betaCompression.f2} | ${t.betaCondensation.exact} |"
    }
markdown(
    "spellings",
    """
All four read back as `$answer`.

| scheduler | reactions | β | shortest notation | against a flat spelling | the search signed for it |
|---|---|---|---|---|---|
$rows
""",
    width = 1080,
)
:::endfold
```


FIRST_FOUND lands its last four β-steps together near the end of the bar and the notation says so: a
long rest and then `x!4`. RIGHTMOST_BY_ID spreads its last four out, so its notation has no
replicate at all, only single strikes and rests. Nothing in the condenser knows what a λ-term is; it
is looking for the shortest string that plays what it was handed.

Compression is a ratio of characters, so the length of the atom affects it. Both bars above use
`x`, as [the CA chain](chain_ca_sound.md) does. Compare two notations only when they use the same
atom.

Here is FIRST_FOUND's bar, three cycles at 0.4 cycles per second:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/exp-first-found.wav" audio-spec="exp-first-found" audio-renderer="afield.audio.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-first-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("exp-first-found").
```

Now listen to RIGHTMOST_BY_ID. It reaches the same answer with eighty-six reactions and eight
β-steps, but fires them in a different order:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_lambda_sound/exp-rightmost.wav" audio-spec="exp-rightmost" audio-renderer="afield.audio.synth.LambdaSoundDocs" embed=true hidden=true name="chain-lambda-rightmost-audio"}
// Offline-rendered by LambdaSoundDocs.renderWav("exp-rightmost").
```

The two clips have the same strikes, voice and tempo, so they match in length and loudness. They
differ only in where the strikes fall.

## The reduction as pitch

A rule emits an event when it fires. A closed term emits six kinds of event: `beta`, `branch`,
`prune` and `annihilate`, and `scope` and `unscope` for a scope delimiter moving and closing. Map
those to six degrees of a minor pentatonic and a reduction becomes a tune whose notes are its rule
sequence.

```diffstatic {data-lang=kotlin}
val name = "erase-argument"
val source = LambdaTerms.corpus().getValue(name)
val root = 45
val intervals = [
    "beta" to 0,
    "branch" to 5,
    "prune" to 7,
    "annihilate" to 12,
    "scope" to 15,
    "unscope" to 17,
].toMap()
val compiled = LambdaTerms.compile(LambdaTerms.parse(source))
val reducer = Reducer(Lambda.rules(), order = ActivationOrder.FIRST_FOUND, relaxIterations = 0)
val notes = mutableListOf<Int>()
reducer.reduceDetailed(compiled.net, maxSteps = 20_000) { _, _, step ->
    val kind = step.events.first().kind
    notes += root + (intervals[kind] ?: 0)
}
```

`K I (mul 2 3)` is the corpus's erasing term: it computes six and throws it away. Nine of its 120
reactions are `prune`, taking the discarded multiplication apart agent by agent, because an
interaction net cannot simply drop a wire; most of the rest are `scope` and `unscope`.

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
