# Petri notation cheatsheet

*The code is `afield.petri.*`: the model and DSL in `PetriNets.kt`, this notation's parser in
`PetriNotation.kt`. The worked examples are on [Petri nets](petri_nets.md).*

The `#petri` notation is the terse surface. Everything it can express, the Kotlin DSL can also
express, and the DSL can do more, because it can take a lambda. What the notation buys is that a
whole net fits on screen and one arc is one line, so you can edit it while it plays.

```diffstatic {data-lang=text}
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

| written | means |
| --- | --- |
| `!timing` | which firing semantics: `step`, `event` or `timed` |
| `!grid` | the tick, as a rational number of cycles — `!grid 1/16` |
| `!conflict` | how competitors for one token are resolved |
| `!seed` | the seed for `!conflict random`, so the same net plays the same way twice |
| `step` | maximal step: everything enabled fires on each grid tick. The default |
| `event` | one transition per tick, the first in conflict order |
| `timed` | firing takes time — a transition holds its tokens for its duration |
| `order` | resolve conflicts in declaration order |
| `priority` | highest `pri=` first. The default |
| `round-robin` | rotate the order each step so nobody starves |
| `random` | a deterministic weighted shuffle from `!seed` |

## Places

A place is created the first time anything mentions it, so you only declare one to give it a
marking, a capacity or a colour.

| written | means |
| --- | --- |
| `:` | separates a place's name from what it holds |
| `2` | that many black tokens — any bare whole number |
| `^` | a capacity: `dst : 0 ^4` never holds more than four |
| `"…"` | **one** token whose colour is that pattern — a section |
| `'…'` | one token coloured with that literal string |
| `#` | one token coloured with that number — `notes : #60, #64` |
| `,` | separates several tokens in one declaration |

The double-quote form is the asymmetry: a quoted string is a *pattern*, so one token can carry a
whole rhythm, and a place can hold a section of a song rather than a single hit.

## Arcs

| written | means |
| --- | --- |
| `-[` | opens a transition — `pre -[ name ]-> post` |
| `]->` | closes it |
| `+` | joins several places on either side |
| `*` | an arc weight: `2*hats` needs two tokens |
| `~` | inhibitor arc: enabled only while that place is **empty** |
| `&` | read arc: needs a token there, consumes none |

Either side may be empty: `-[ spawn ]-> pool` is a source and `pool -[ sink ]->` is a drain. A
transition may be named on several lines, which is how one with several inputs stays readable.

## Transition settings

Written after the name, inside the brackets, in any order.

| written | means |
| --- | --- |
| `@` | how long the firing's event lasts — `@1/16`. Under `timed` it also holds the tokens |
| `delay=` | shifts the emitted event without touching the net's logic — how you nudge a hit late |
| `pri=` | higher fires first when two transitions want the same token |
| `w=` | relative likelihood under `!conflict random` |
| `:` | what the firing sounds — a bare word, a `"quoted string"` or a `#number` |
| `=>` | a colour combinator for the tokens this transition produces |

## Combinators

What `=>` can name. Each takes the tokens a firing consumed and returns the colour of the token it
produces. The vocabulary is closed: these words and no others. For anything else, use the Kotlin
DSL, where an output arc takes a lambda.

| written | means |
| --- | --- |
| `carry` | the first consumed colour, unchanged — the default |
| `black` | no colour: produce a plain black token |
| `swap` | the second consumed colour, falling back to the first |
| `stack` | all consumed patterns layered on top of each other |
| `cat` | all consumed patterns, one per cycle |
| `fastcat` | all consumed patterns squeezed side by side into one cycle |
| `rev` | the first consumed pattern, reversed |
| `palindrome` | the first consumed pattern, then its reverse |
| `degrade` | the first consumed pattern with half its events dropped |
| `fast2` | the first consumed pattern at double speed |
| `slow2` | the first consumed pattern at half speed |
| `sum` | the numeric consumed colours added together |
| `inc` | the first numeric consumed colour plus one |
| `count` | how many tokens were consumed |

## Kotlin entry points

Not notation — the API around it.

| call | gives you |
| --- | --- |
| `petri("…")` | a `PetriNet` from the notation, throwing on the first error |
| `petriParse("…")` | the same, plus every diagnostic, and a net even when there are errors |
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
