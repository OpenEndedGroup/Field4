# Petri notation cheatsheet

*The code is `afield.petri.*`: the model and DSL in `PetriNets.kt`, this notation's parser in
`PetriNotation.kt`. The worked examples are on [Petri nets](petri_nets.md).*

The `#petri` notation is the terse surface. Everything it can express, the Kotlin DSL can also
express, and the DSL can do more, because it can take a lambda. What the notation buys is that a
whole net fits on screen and one arc is one line, so you can edit it while it plays.

```diffstatic {data-lang=petri}
#petri
!grid 1/16

kick : 1
kick -[ hit  @1/16 : bd ]-> hats
hats -[ back @1/16 : sd ]-> kick
```

Every operator in the tables below has a worked example on the examples page or in the gallery.
`//` starts a comment; a leading `#petri` line is the editor's marker and is otherwise ignored.

## Directives

Settings for the whole net. One per line, anywhere in the source.

<div class="field-reference" data-code-lang="petri" markdown>

| Example | What it means |
| --- | --- |
| `!timing step` | Chooses the firing semantics: `step`, `event` or `timed`. |
| `!grid 1/16` | Sets the tick to one sixteenth of a cycle. Durations and delays are in cycles, so changing playback speed changes their length in seconds. |
| `!conflict priority` | Chooses how competitors for one token are resolved: `order`, `priority`, `round-robin` or `random`. |
| `!seed 7` | Seeds `!conflict random`, so the same net plays the same way twice. |

</div>

The three firing semantics and the four conflict policies:

<div class="field-reference" data-code-lang="petri" markdown>

| Value | What it means |
| --- | --- |
| `step` | Maximal step: every enabled, non-conflicting transition fires on each grid tick. The default. |
| `event` | One transition per tick, the first in conflict order. |
| `timed` | A transition holds its consumed tokens for its duration before the firing completes. |
| `order` | Resolves competition in declaration order, so moving a transition earlier can change the rhythm. |
| `priority` | Largest `pri=` first. The default. |
| `round-robin` | Rotates the ordering from step to step, so an early declaration cannot always win a shared token. |
| `random` | A seeded weighted shuffle; each transition’s `w=` sets its relative weight. |

</div>

## Places

A place is created the first time anything mentions it, so you only declare one to give it a
marking, a capacity or a colour.

<div class="field-reference" data-code-lang="petri" markdown>

| Example | What it means |
| --- | --- |
| `:` <br> `kick : 1` | Declares `kick` with one black token. A place mentioned only by an arc starts empty. |
| `2` <br> `hats : 2` | Places two black tokens in `hats`. Bare whole numbers are token counts, rather than numeric token colours. |
| `^` <br> `dst : 0 ^4` | Creates an empty place with capacity four. Firings must respect the destination’s capacity. |
| `"…"` <br> `verse : "bd ~ sd ~"` | Stores one pattern-valued token containing a whole rhythm. Quoted patterns are not expanded into several tokens at declaration time. |
| `'…'` <br> `label : 'lead'` | Stores one literal string colour. Single quotes distinguish a label from a double-quoted musical pattern. |
| `#` <br> `notes : #60` | Stores one token whose numeric colour is 60. Use `#` when the number is a value to transform, rather than a token count. |
| `,` <br> `notes : #60, #64, #67` | Stores three separately coloured tokens in one place. Commas separate tokens; the marking is three, not their numeric sum. |

</div>

The double-quote form is the asymmetry: a quoted string is a *pattern*, so one token can carry a
whole rhythm, and a place can hold a section of a song rather than a single hit.

## Arcs

<div class="field-reference" data-code-lang="petri" markdown>

| Example | What it means |
| --- | --- |
| `-[` <br> `pre -[ hit ]-> post` | Declares transition `hit`, consuming from `pre` and producing into `post`. Its name can be reused on another line to add more arcs. |
| `]->` <br> `pool -[ sink ]->` | Closes the transition with no output place, making a drain. Conversely, `-[ spawn ]-> pool` is a source. |
| `+` <br> `kick + clock -[ hit ]-> hats` | Requires tokens from both input places in one firing. Use `+` on the output side to produce into several places. |
| `*` <br> `2*hats -[ roll ]-> fill` | Consumes two tokens from `hats` for each firing. Arc weights apply independently to each named place. |
| `~` <br> `~armed -[ fill ]-> hats` | Enables `fill` only while `armed` is empty. The inhibitor tests absence and consumes nothing. |
| `&` <br> `&clock -[ tick ]-> bar` | Requires a token in `clock` but leaves it there. A read arc lets several transitions observe the same condition. |

</div>

Either side may be empty: `-[ spawn ]-> pool` is a source and `pool -[ sink ]->` is a drain. A
transition may be named on several lines, which is how one with several inputs stays readable.

## Transition settings

Written after the name, inside the brackets, in any order.

<div class="field-reference" data-code-lang="petri" markdown>

| Example | What it means |
| --- | --- |
| `@` <br> `pre -[ hit @1/16 : bd ]-> post` | Gives the emitted event a duration of one sixteenth of a cycle. Under timed semantics, the transition also holds its tokens for this duration. |
| `delay=` <br> `pre -[ hit delay=1/32 : bd ]-> post` | Shifts the audible event later by one thirty-second of a cycle. The net’s enabling and token movement stay on their original schedule. |
| `pri=` <br> `pre -[ hit pri=2 : bd ]-> post` | Gives this transition priority two. Larger values win when the conflict policy is `priority`. |
| `w=` <br> `pre -[ hit w=2 : bd ]-> post` | Assigns relative weight two in random conflict ordering. The weight matters only under `!conflict random`. |
| `:` <br> `pre -[ hit : bd ]-> post` | Emits the sound token `bd` when the transition fires. A quoted string or `#number` can be emitted instead. |
| `=>` <br> `pre -[ move => rev ]-> post` | Transforms consumed colours before producing output tokens. Here the first consumed pattern is reversed. |

</div>

## Combinators

What `=>` can name. Each takes the tokens a firing consumed and returns the colour of the token it
produces. The vocabulary is closed: these words and no others. For anything else, use the Kotlin
DSL, where an output arc takes a lambda.

<div class="field-reference" data-code-lang="petri" markdown>

| Example | What it means |
| --- | --- |
| `carry` <br> `pre -[ move => carry ]-> post` | Passes the first consumed colour through unchanged. This is the default when no combinator is written. |
| `black` <br> `pre -[ move => black ]-> post` | Produces an uncoloured black token. Use it when the next place needs occupancy rather than the incoming payload. |
| `swap` <br> `pre -[ move => swap ]-> post` | Selects the second consumed colour, falling back to the first when there is only one. Input ordering therefore matters. |
| `stack` <br> `pre -[ move => stack ]-> post` | Layers all consumed patterns simultaneously. Their events share the output pattern’s cycle. |
| `cat` <br> `pre -[ move => cat ]-> post` | Plays the consumed patterns one per cycle. This preserves each section’s full cycle length. |
| `fastcat` <br> `pre -[ move => fastcat ]-> post` | Fits all consumed patterns side by side into one cycle. More inputs make each section occupy a smaller fraction of that cycle. |
| `rev` <br> `pre -[ move => rev ]-> post` | Reverses the first consumed pattern. Use it to alternate a section with its backward version. |
| `palindrome` <br> `pre -[ move => palindrome ]-> post` | Alternates the first consumed pattern with its reverse. The output carries both directions as a pattern. |
| `degrade` <br> `pre -[ move => degrade ]-> post` | Drops half the first consumed pattern’s events probabilistically. This thins a section without changing the net’s token logic. |
| `fast2` <br> `pre -[ move => fast2 ]-> post` | Plays the first consumed pattern at double speed. Its material repeats twice over the original span. |
| `slow2` <br> `pre -[ move => slow2 ]-> post` | Plays the first consumed pattern at half speed. Its material stretches across twice the original span. |
| `sum` <br> `pre -[ move => sum ]-> post` | Adds the numeric colours of the consumed tokens. Use numeric tokens such as `#60` for arithmetic payloads. |
| `inc` <br> `pre -[ move => inc ]-> post` | Adds one to the first consumed numeric colour. A loop through this transition can carry a counter. |
| `count` <br> `pre -[ move => count ]-> post` | Produces the number of tokens consumed by this firing. This measures the firing’s inputs rather than the total marking of the net. |

</div>

## Kotlin entry points

Not notation — the API around it.

<div class="field-reference" data-code-lang="kotlin" markdown>

| call | gives you |
| --- | --- |
| `petri("…")` | Parses the notation into a `PetriNet` and throws when parsing fails. Use `petriParse` when you need to display diagnostics instead. |
| `petriParse("…")` | Returns collected diagnostics and a nullable parsed net. Check `ok` before treating the result as valid. |
| `petri { … }` | a `PetriNet` from the Kotlin DSL, where arcs can take lambdas |
| `petriRing(name, pulses, steps)` | a Euclidean token ring — the drum primitive |
| `petriSong("verse" to (pat("…") to 8), …)` | a linear form whose sections are pattern-valued tokens |
| `net.run()` | a memoised, deterministic trajectory |
| `net.live()` | a stateful instance you can `inject`, `remove` and `force` |
| `net.pattern()` | `PatPattern<PetriFiring>` — the whole language now applies |
| `net.emits()` | `PatPattern<String>`, ready for `notes()`, Dirt, or a synth |
| `pattern.unfold<String>()` | plays the pattern each firing consumed, across that firing's arc |
| `run.signal(place)` | the place's token count as a continuous control signal |
| `run.level(place, full)` | the same, normalised to `0..1` for `range` and `rangex` |
| `run.gate(place)` | 1 while the place is occupied, 0 when it is not |
| `net.period()` | how long the net takes to come back round, in cycles |
| `net.tInvariants()` | its minimal loops, from the incidence matrix |
| `net.willDeadlock()` | whether it wedges — the check to run before committing an edit |
| `net.deadTransitions()` | the voices that silently never fire |
| `net.report()` | all of the above, as a paragraph |
| `net.stateSpace()` | every marking reachable by **any** interleaving — the classical answers |
| `graph.deadlockTrace()` | the shortest firing sequence that wedges it, or null |
| `graph.witness { … }` | `EF`: the shortest route to a marking satisfying the predicate |
| `graph.invariant { … }` | `AG`: the shortest violation, or null when the property holds |
| `graph.mutuallyExclusive("a", "b")` | true / false / null — null means the search was truncated |
| `graph.bound(place)`, `graph.isSafe`, `graph.isLive` | exact, or null when the budget ran out |
| `graph.neverFires()` | fireable in *no* interleaving — the strict `deadTransitions()` |
| `trace.verify()` | re-fires the counterexample through the engine; null means it holds up |
| `net.stateSpace(reduction = PetriReduction.STUBBORN)` | partial-order reduction — 73× on eleven philosophers, deadlock only |
| `graph.exact` | `complete && reduction == NONE`; everything but deadlock needs it |
| `graph.tooDenseToDraw()` | above `PETRI_STATE_DRAW_LIMIT` the drawing summarises instead of drawing |
| `graph.proves { … }` | true / false / null — the three-valued form of `invariant` |
| `net.coverability()` | Karp-Miller with ω — terminates even when the state space does not |
| `box.showPetriStateSpace(graph)` | the state graph, with the counterexample drawn over it |
| `box.showPetriStateSpace(graph, maxNodes = …)` | raise the node bound, or `Int.MAX_VALUE` to insist |
| `box.showPetriNet(live)` | the picture, clickable: click a place to inject, a transition to fire |
| `live.play(cps = 2.0) { … }` | plays a live net in a box, on the beat |
| `box.showPetriNet(net, layout = …)` | `AUTO` (the default), `RING`, `FORCE`, `LAYERED`, `TALA` |
| `PetriLayout.TALA` | d2's diagram engine, via the optional `native/field-d2-layout`; draws with `FORCE` when it is absent |
| `clearPetriLayoutCache()` | re-solve, after building that helper under a running Field |

</div>
