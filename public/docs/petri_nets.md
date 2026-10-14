# Petri nets

*The notation is summarised on the [Petri cheatsheet](petri_cheatsheet.md). To try the musical
examples on this page, open the [petri_sound](agent_transcripts.md#petri_sound) starting point.*

A Petri net is a graph of places that hold tokens and transitions that move them, with arrows
saying where they go. It has a standard way of being drawn, and a body of theory that answers
practical questions such as "how long is this loop?" and "can this get stuck?".

In Field a Petri net can be used as a rhythm, as the structure of a piece, or as a control signal.
That works because a net whose conflicts are always resolved the same way gives the same firings
for the same span of time, so `net.emits()` can be a `PatPattern`: a function from a span of time
to the events inside it. Everything in [the pattern language](pattern_language.md) (`every`, `jux`,
`off`, `fast`, `rev`, `with gain(sine())`), the offline renderer, the piano roll and `respond {}`
work on a Petri net as they do on any other pattern.

---

## A net that plays a rhythm

This net has one token, two places and two transitions. Each transition plays a sound when it
fires. The token can only be in one place at a time, so the two transitions take turns, and that
alternation is the rhythm. The example draws the net and prints the first few steps.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/two_place_loop.png" alt="three stacked output channels. The drawing is a diamond: the place kick at the top holding one token, hats at the bottom holding none, and between them the transitions hit (green, ready to fire) and back (grey). The summary panel lists kick 1, hats 0, two places, two transitions, step at grid 1/8. The text panel below tabulates six steps at 0, 1/8, 1/4, 3/8, 1/2 and 5/8 with the token alternating between the two places, then reports period 1/4 cycles, the single T-invariant back + hit, and a state space of two markings that is deadlock-free, safe, quasi-live, live and reversible" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-two-place-loop" w="1240" padding="12"}
:::static
import afield.simulation.petri.*

val net = petri("""
    #petri
    !grid 1/8

    kick : 1
    kick -[ hit  @1/8 : bd ]-> hats
    hats -[ back @1/8 : sd ]-> kick
""")

:::fold draw the net and print what it plays
showPetriNet(net, w = 760.0, h = 420.0)
markdown("main", """
    #### one token, going round

    ~~~
${net.asciiTimeline(6).trimEnd()}
    ~~~

    ~~~
${net.report().trimEnd()}
    ~~~
""")
displayOpen("main")
:::endfold
```

Places are circles, tokens are dots inside them, transitions are bars: bright when they are ready
to fire, dim when they are not. A net that is a single cycle is drawn as a ring.

The report ends with the net's period, worked out from the net itself: nothing in the source says
how long a bar is.

---

## Finding a net's loop length

A *T-invariant* is a set of firings that brings the net back to where it started: a loop. A net's
smallest T-invariants are its natural periods, and Field works them out from the arcs alone,
without running the net.

This example puts two token rings in one net, one of five steps and one of three, and asks how
long the whole net takes to repeat. The answer, fifteen, appears nowhere in the source.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/polyrhythm.png" alt="two token rings drawn side by side and touching at one point: a five-place ring a0 to a4 on the left and a three-place ring b0 to b2 on the right, each with its one marked place ringed and its next transition green. The summary reads 8 places, 8 transitions, step at grid 1/15. Below it: the two rings are independent, settling after 15 steps, with two T-invariants of sizes 5 and 3 rather than one combined" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-polyrhythm" w="1240" padding="12"}
:::static
import afield.simulation.petri.*

val net = petri("""
    #petri
    !grid 1/15

    a0 : 1
    a0 -[ a0.go : x ]-> a1
    a1 -[ a1.go     ]-> a2
    a2 -[ a2.go : x ]-> a3
    a3 -[ a3.go     ]-> a4
    a4 -[ a4.go     ]-> a0

    b0 : 1
    b0 -[ b0.go : y ]-> b1
    b1 -[ b1.go     ]-> b2
    b2 -[ b2.go : y ]-> b0
""")

val lasso = net.settle()!!

:::fold draw it and report the period it found
showPetriNet(net, w = 820.0, h = 520.0)
markdown("main", """
    #### five against three

    The two rings are independent, which the analysis states as *two separate T-invariants*
    rather than one combined one. Independence is exactly what lets them be played at
    different tempi without the net coming apart.

    - settles after **${lasso.loopSteps} steps** = **${lasso.duration} cycle**
    - T-invariants: **${net.tInvariants().size}**, of sizes ${net.tInvariants().map { it.size }}
    - the loop the engine ran is a genuine invariant: **${net.checkLoopIsInvariant() == null}**
""")
displayOpen("main")
:::endfold
```

You can also ask whether a net will get stuck, before you play it:

```diffstatic {data-lang=kotlin}
net.willDeadlock()      // false — it loops
net.deadTransitions()   // [] — every voice is sounding

petri("""
    armed : 1
    src   : 4
    ~armed + src -[ blocked : bd ]-> dst
""").willDeadlock()     // true — nothing can ever empty 'armed'
```

`~armed` is an inhibitor arc: the transition is enabled only while that place is empty. Nothing
here ever empties it, so `blocked` never fires, and `willDeadlock()` tells you so before you hear
the silence.

---

## Using a net as a pattern

`net.emits()` is a `PatPattern<String>`, so everything in the pattern language works on it. This
example builds a kick and a hat as two token rings, doubles the speed every fourth cycle and adds a
reversed copy in the other channel, then counts the onsets. It also checks that a token walking a
ring of eight places plays the same rhythm as `euclid(3, 8)`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/language.png" alt="two sixteen-place rings drawn as a figure of eight, kick on the left and hat on the right, each with one ringed marked place and one green transition. The summary lists all 32 places and 32 transitions at grid 1/16. The text panel tabulates onsets in cycle 0 against cycle 1: kit.emits() gives 15 and 15, .every(4) { it.fast(2) } gives 30 and 15, .jux { it.rev() } on top gives 60 and 30. Underneath, a token walking a ring of eight lands on 0, 3/8 and 5/8, the same positions euclid(3, 8) gives" wait="2.5" capture="box" open-outputs="true" embed=true name="petri-language" w="1240" padding="12"}
:::static
import afield.simulation.petri.*
import afield.audio.synth.*

// A four-on-the-floor kick and a syncopated hat, as two Euclidean token rings in one net.
val kit = petri {
    grid = cyc(1, 16)
    petriRingInto(this, "kick", 4, 16, emit = "bd")
    petriRingInto(this, "hat", 11, 16, emit = "hh")
}

// From here it is just a pattern.
val voice = kit.emits()
    .every(4) { it.fast(2) }
    .jux { it.rev() }
    .with(gain(sine().range(0.4, 1.0)))

:::fold count what came out, and check it against the language's own euclid
val fromNet = petriRing("k", 3, 8, emit = "bd").emits()
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }.map { it.arc.start }
val fromLanguage = euclid(3, 8)
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.value }.map { it.arc.start }

fun onsets(p: PatPattern<*>, c: Long) =
    p.events(PatArc(PatQ.of(c), PatQ.of(c + 1))).count { it.hasOnset }

showPetriNet(kit, w = 900.0, h = 560.0)
markdown("main", """
    #### a net, put through the pattern language

    | | onsets in cycle 0 | onsets in cycle 1 |
    | --- | --- | --- |
    | `kit.emits()` | ${onsets(kit.emits(), 0)} | ${onsets(kit.emits(), 1)} |
    | `.every(4) { it.fast(2) }` | ${onsets(kit.emits().every(4) { it.fast(2) }, 0)} | ${onsets(kit.emits().every(4) { it.fast(2) }, 1)} |
    | `.jux { it.rev() }` on top | ${onsets(voice, 0)} | ${onsets(voice, 1)} |

    A token walking a ring of eight places lands on the same positions as `euclid(3, 8)`:

    - from the net: `${fromNet.joinToString(" ")}`
    - from `euclid`: `${fromLanguage.joinToString(" ")}`
    - equal: **${fromNet.sorted() == fromLanguage.sorted()}**
""")
displayOpen("main")
:::endfold
```

To play it, hand it to `respond` like any other pattern:

```diffstatic {data-lang=kotlin}
kit.emits().respond(cps = 2.0) { event -> dirt(event.value) }
```

---

## Tokens that carry rhythms

A token can carry a value, called its colour, and the value can be a whole `PatPattern`. Then a
place is a section of a piece, a transition moves from one section to the next, and a slow net
whose tokens are rhythms arranges the piece. This example builds a verse and a chorus with
`petriSong` and prints eight bars.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/sections.png" alt="the same two-place diamond as before, now labelled verse and chorus with the transitions play-verse and play-chorus between them, verse marked and tagged '1 pattern', timed at grid 1/16. The text panel lists eight cycles: cycles 0 to 3 each play bd sd, cycles 4 to 7 each play bd bd bd bd. Exactly one place is marked at a time, so exactly one section is sounding" wait="2.5" capture="box" open-outputs="true" embed=true name="petri-sections" w="1240" padding="12"}
:::static
import afield.simulation.petri.*
import afield.audio.synth.*

// One place per section, one token circulating, each section held for its own number of cycles.
// The token carries the pattern that should be sounding next.
val song = petriSong(
    "verse"  to (pat("bd ~ sd ~") to 4),
    "chorus" to (pat("bd*4") to 4),
)

// `unfold` plays whatever pattern each firing consumed, across that firing's arc.
val notes = song.pattern().unfold<String>()

:::fold render eight bars of the arrangement
fun bar(c: Long) = notes.events(PatArc(PatQ.of(c), PatQ.of(c + 1)))
    .filter { it.hasOnset }.sortedBy { it.arc.start }.joinToString(" ") { it.value }

showPetriNet(song, w = 700.0, h = 380.0)
markdown("main", """
    #### eight bars, from a two-place net

    ~~~
${(0L..7L).joinToString("\n") { "    cycle %d   %s".format(it, bar(it)) }}
    ~~~

    Four bars of one rhythm, four of the other, and then round again — from two places and
    two transitions. Only one place is marked: exactly one section can be sounding, which is
    what "an arrangement" means and is enforced by the net rather than by a scheduler.
""")
displayOpen("main")
:::endfold
```

Written in the notation, a net like this looks as follows. `!timing timed` makes each firing take
its duration, so a transition with `@4` holds its section for four cycles instead of passing the
token on at once:

```diffstatic {data-lang=text}
#petri
!timing timed

verse : "bd ~ sd ~"        // the only marked place, so only one section can sound

verse  -[ play-verse  @4 ]-> chorus
chorus -[ play-chorus @4 => rev ]-> verse
```

The word after `=>` is a colour combinator, which changes the pattern a transition passes on:
`stack` layers the rhythms a transition consumed, `cat` plays them one per cycle, `fast2` doubles
the speed of one, `degrade` thins it out. [The cheatsheet lists them all](petri_cheatsheet.md#combinators).

---

## Token counts as control signals

The number of tokens in a place changes over time, and `net.run()` turns that into a signal you
can use like any other. Here tokens pile up in `tension` and open a filter on the kit from the
previous section:

```diffstatic {data-lang=kotlin}
val net = petri("""
    !grid 1/4
    src     : 8
    tension : 0 ^8
    src -[ build @1/4 ]-> tension
""")
val run = net.run()

// `level` normalises against the place's declared capacity, which is the form `rangex` wants.
kit.emits() with cutoff(run.level("tension").rangex(200, 8000))

// and the raw count, a gate, and a smoothed ramp are all there too
run.signal("tension")                       // 0, 1, 2, … as a continuous pattern
run.gate("armed")                           // 1 while occupied, 0 when not
run.signal("tension", PetriInterp.SMOOTH)   // no corners, safe on a cutoff
```

Use `level` rather than `signal` when the value is going into `range` or `rangex`: those map
`0..1` onto their interval and *extrapolate* outside it, so a raw count of 3 would send a sweep
three times past the top.

---

## Playing a net live

Everything above plays the same way every time. `net.live()` makes a net you can change while it
plays, by clicking on its drawing:

```diffstatic {data-lang=kotlin}
val live = net.live()

showPetriNet(live)                 // click a place to inject a token, a transition to fire it
live.play(cps = 2.0) { firing -> firing.emit?.let { dirt(it.toString()) } }
```

Clicking a place in the drawing adds a token; clicking a transition fires it, whether or not it was
about to fire. `play` drives the net through `respond`, so a live net starts, stops and re-runs in
a box like any other pattern. If you leave it alone it plays exactly what `net.emits()` plays; once
you click, it follows your changes from that moment on.

---

## Deadlock and starvation

A Petri net doesn't have to make sound. The same analysis that finds a drum loop's length can
tell you whether processes that share resources can get stuck.

These examples use the Kotlin DSL, `petri { }`, instead of the `#petri` notation. The notation is
easier to edit while a net plays; the DSL lets you compute a net's shape, so five philosophers is
a `for` loop rather than five copies of the same lines.

### Dining philosophers

Five philosophers sit round a table with a fork between each pair. Each needs the fork on their
left and the fork on their right to eat. If everyone reaches left first, all five forks are taken
at once and nobody can pick up a second. This example builds that table and two fixes, and asks
each one whether it deadlocks and which transitions never fire.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/philosophers.png" alt="a tangled fifteen-place net with five ringed fork places marked and five green grab transitions ready, drawn without names because the drawing is past the label limit. The summary lists fork.0 to fork.4 holding one token each and every holding and eating place empty. The table below compares three tables: all left-handed deadlocks and never fires any eat or done; one reaches right first does not deadlock but never fires grab.4, eat.4 or done.4; round-robin conflict does not deadlock and starves nobody. The deadlocked table stops with five philosophers each holding one fork" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-philosophers" w="1240" padding="12"}
:::static
import afield.simulation.petri.*

// The shape of this net is computed, which is exactly what the notation cannot express.
fun table(reverseLast: Boolean, fairly: Boolean) = petri {
    conflict = if (fairly) PetriConflict.ROUND_ROBIN else PetriConflict.ORDER
    grid = cyc(1, 4)
    for (i in 0 until 5) place("fork.$i", tokens = 1)
    for (i in 0 until 5) {
        val left = i
        val right = (i + 1) % 5
        // The classic fix: the last philosopher reaches right first, breaking the symmetry.
        val reversed = reverseLast && i == 4
        t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}

val naive = table(reverseLast = false, fairly = false)
val asymmetric = table(reverseLast = true, fairly = false)
val fairTable = table(reverseLast = true, fairly = true)

:::fold draw the deadlocked table, then compare all three
fun row(label: String, net: PetriNet) = "| $label | " +
    (if (net.willDeadlock()) "**yes**" else "no") + " | " +
    net.deadTransitions().ifEmpty { ["—"] }.joinToString(", ") + " |"

showPetriNet(naive, w = 900.0, h = 560.0)
markdown("main", """
    #### five philosophers, three tables

    | table | deadlocks? | never fires |
    | --- | --- | --- |
    ${row("all left-handed", naive)}
    ${row("one reaches right first", asymmetric)}
    ${row("…and round-robin conflict", fairTable)}

    The deadlocked table stops with **${naive.deadlockMarking()!!.occupied.count { it.startsWith("holding") }}**
    philosophers each holding one fork and no fork left on the table.
""")
displayOpen("main")
:::endfold
```

The middle row reverses one philosopher, which removes the deadlock. But under
`PetriConflict.ORDER` the first-declared claimant always wins the contested fork, so the reversed
philosopher never gets it and never eats. This is starvation: the net keeps running but one
philosopher is permanently left out. `willDeadlock()` says no, and `deadTransitions()` says who
is starving. Reversing a philosopher fixes the deadlock; a fair conflict policy fixes the
starvation.

### Conservation laws in a bounded buffer

A *P-invariant* is a weighted sum of token counts that no transition can change, worked out from
the arcs. This example builds a four-slot buffer between a producer and a consumer, lists its
P-invariants, and checks one of them against a run.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/bounded_buffer.png" alt="a four-place net: producer and consumer each holding one token, items empty, and free holding four, drawn as a cloverleaf of four dots. The green produce transition takes from producer and free, the grey consume takes from consumer and items. The summary reads free 4, items 0, producer 1, consumer 1. The text panel lists three invariants (producer, consumer, and free + items each never change) and reports that over 40 steps items + free took the single value 4" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-bounded-buffer" w="1240" padding="12"}
:::static
import afield.simulation.petri.*

val buffer = petri {
    grid = cyc(1, 8)
    place("free", tokens = 4)          // four empty slots
    place("items", capacity = 4)       // …and never more than four full ones
    place("producer", tokens = 1)
    place("consumer", tokens = 1)
    // Each worker holds a token it puts straight back: that is what makes it *one* worker
    // rather than an unbounded supply of them.
    t("produce") { from("producer"); from("free"); to("items"); to("producer") }
    t("consume") { from("consumer"); from("items"); to("free"); to("consumer") }
}

:::fold draw it, state the laws, and check them against a real run
val laws = buffer.pInvariants().joinToString("\n    ") { invariant ->
    "- `" + invariant.entries.sortedBy { it.key }
        .joinToString(" + ") { if (it.value == 1) it.key else "${it.value}·${it.key}" } +
        "` never changes"
}
val run = buffer.run()
val totals = run.steps(40).map { it.after.count("items") + it.after.count("free") }.distinct()

showPetriNet(buffer, w = 760.0, h = 420.0)
markdown("main", """
    #### a four-slot buffer that provably cannot leak

    $laws

    Over 40 steps `items + free` took the values **$totals** — a set of one element, which is
    what a conservation law looks like from the outside. The capacity on `items` is belt and
    braces: the invariant already makes overflow impossible.
""")
displayOpen("main")
:::endfold
```

`free + items` never changing means a slot can never be lost or invented, so the buffer cannot leak
or overflow. The other two say the producer and the consumer are each exactly one worker. None of
these are declared anywhere; they come from the arcs.

---

## Checking every possible order

`willDeadlock()` and `deadTransitions()` run the net once, resolving each conflict with the net's
own conflict policy, and report what happened in that one run. For music that is usually the
question you want answered, because the conflict policy is part of how the piece plays.

But a table of philosophers can deadlock in some orders of events and not in others, so one run
that keeps going proves nothing. `net.stateSpace()` finds every marking (every arrangement of
tokens) the net can reach in any order, and answers questions about all of them. This example
draws the state space of the deadlocking table, with the shortest route to the deadlock marked.
The [petri_state_space](agent_transcripts.md#petri_state_space) starting point has these examples
as runnable boxes.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/state_space.png" alt="the reachability graph drawn as six columns of pale dots joined by a dense grey web, with one thick orange path traced from a green 'start' node on the left through grab.0, grab.1, grab.2, grab.3 and grab.4 to a red node labelled 'stuck' on the right. The summary panel reads 82 markings, 265 firings, depth 5, tagged can deadlock, safe (1-bounded), not live, not reversible, with the shortest counterexample spelled out. The text panel repeats those and names the fifteen transitions that can be lost for good" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-state-space" w="1240" padding="12"}
:::static
import afield.simulation.petri.*

fun table(reverseLast: Boolean) = petri {
    conflict = PetriConflict.ORDER
    grid = cyc(1, 4)
    for (i in 0 until 5) place("fork.$i", tokens = 1)
    for (i in 0 until 5) {
        val left = i
        val right = (i + 1) % 5
        val reversed = reverseLast && i == 4
        t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}

val graph = table(reverseLast = false).stateSpace()
val trace = graph.deadlockTrace()!!

:::fold draw the state space with the counterexample on it
showPetriStateSpace(graph, w = 1080.0, h = 560.0)
markdown("main", """
    #### ${graph.stateCount} reachable markings, and the ${trace.length} firings that ruin them

    ~~~
${graph.report().trimEnd()}
    ~~~

    The counterexample, re-fired through the engine's own enabling test:
    **${trace.verify() ?: "it checks out"}**. Breaking the symmetry (`reverseLast = true`)
    leaves **${table(reverseLast = true).stateSpace().stateCount}** markings and *proves*
    there is no deadlock among them.
""")
displayOpen("main")
:::endfold
```

Each dot is a reachable marking, arranged left to right by how many firings it takes to reach it.
The orange path is the shortest route to the deadlock, with its transitions written on it, and the
red ring is the marking where it gets stuck: every philosopher holding one fork.

A drawing of the whole state space is only readable for small nets. Above
`PETRI_STATE_DRAW_LIMIT` (400 markings) the dots and layers would overlap, so the figure shows the
counterexample alone, one firing per column, over a histogram of how many markings sit at each
breadth-first depth, and says on the figure that it has done so. `graph.tooDenseToDraw()` tells you
in advance; `maxNodes` on `toFLines`, `toSvg` and `showPetriStateSpace` moves the limit, and
`Int.MAX_VALUE` draws every marking regardless.

The state space answers three things that running the net once cannot:

- **Whether it can deadlock at all.** `canDeadlock` is `true`, `false` or `null`, and `null` means
  the search ran out of budget. Any answer about markings the search did not visit comes back as
  `null`, never as `false`.
- **How it goes wrong.** `graph.witness { … }` finds the shortest route to a marking with a property
  (`EF`), and `graph.invariant { … }` returns the shortest route to a marking that breaks one
  (`AG`), so `mutuallyExclusive("crit.a", "crit.b")` either proves the property or gives you the two
  firings that break it. `PetriTrace.verify()` replays a route through the net to confirm it.
- **What read and inhibitor arcs do.** Invariants only see how arcs change token counts, so they
  miss read and inhibitor arcs, which test a place without changing it. The state space follows
  the real firing rules, so it sees them.

On the table with one reversed philosopher, the two kinds of check disagree, and that tells you
what is wrong:

```diffstatic {data-lang=kotlin}
val net = table(reverseLast = true)      // asymmetric, PetriConflict.ORDER
net.deadTransitions()                    // [grab.4, eat.4, done.4]  ← starved by the policy
net.stateSpace().neverFires()            // []                       ← the *net* is fine
```

The starving philosopher comes from the conflict policy, not from the net: some order of events
lets every transition fire. `report()` prints both results and says which case you are in: this
run got stuck; some order gets stuck, and here is the sequence; no order gets stuck; or the search
ran out of budget and the answer is unknown.

---

## Drawing a net

`showPetriNet` uses `PetriLayout.AUTO` unless you choose another layout. AUTO draws a net that is a
single cycle as a ring, and anything else with `FORCE`, a spring model that tries several starting
positions and keeps the drawing with the fewest crossings and overlaps. The same net always draws
the same way. `RING` draws a circle in the order the net was built, and `LAYERED` draws left to
right by distance from the places that start with tokens.

`PetriLayout.TALA` uses TALA, the layout engine of the d2 diagram tool, which places each node at
its real size and routes the arcs around nodes. This example draws one net both ways and labels
each drawing with its number of arc crossings. The [petri_tala_layout](agent_transcripts.md#petri_tala_layout)
starting point has a smaller net to compare on.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/tala-vs-force.png" alt="the same thirty-node table drawn twice, side by side and unlabelled. The left panel, headed 'FORCE, spring model - 0 arc crossings', is a loose organic cluster whose arcs are straight lines between node centres. The right panel, headed 'TALA, d2 v0.9.0 - 3 arc crossings', spreads the same net over a wide rectangle with every arc running in horizontal and vertical segments that turn corners around the nodes in the way" wait="3.0" capture="box" open-outputs="true" embed=true name="petri-tala-vs-force" w="1240" padding="12"}
:::static
import afield.simulation.petri.*
import afield.ui.layout.D2Layout

fun table() = petri {
    for (i in 0 until 5) place("fork.$i", tokens = 1)
    for (i in 0 until 5) {
        val left = i
        val right = (i + 1) % 5
        t("grab.$i") { from("fork.$left"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.$right"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}

val net = table()

:::fold draw it twice, labelling each panel with the engine and what it cost in crossings
// Both labels are generated, so the figure reports itself rather than asking you to take a claim
// on trust. On a machine with no helper the right-hand panel says so, instead of showing FORCE's
// drawing under TALA's name.
val forceDrawing = net.frame(PetriLayout.FORCE)
val talaDrawing = net.frame(PetriLayout.TALA)
val engine = if (D2Layout.available) "TALA, d2 " + D2Layout.version else "helper absent: FORCE again"

fun label(name: String, drawing: PetriDrawing) =
    name + " - " + drawing.arcCrossings() + " arc crossings"

showPlaneGrid(
    label("FORCE, spring model", forceDrawing) to forceDrawing.toFLines(),
    label(engine, talaDrawing) to talaDrawing.toFLines(),
    columns = 2,
    cellWidth = 560,
    cellHeight = 470,
)
displayOpen("main")
:::endfold
```

Each panel counts its own crossings. FORCE often has fewer, because crossings are what it
minimises; on other nets TALA wins, and is sometimes faster too, so `AUTO` never picks TALA for you
— try both on your net. Neither drawing has labels, because `toFLines` drops them on nets this
large.

The arcs are drawn differently as well. FORCE places the nodes and draws each arc as a straight
line from centre to centre. TALA routes each arc around the nodes in its way, so the right-hand
panel turns corners and reads more like a schematic than a graph drawing. `PetriLayout.TALA` also
lays the net out once per direction (up, down, right, and no preference) and keeps the drawing with
the fewest crossings.

TALA needs a helper program, `native/field-d2-layout`, written in Go, which Field does not build or
ship. Without it `PetriLayout.TALA` falls back to `FORCE`, so a document that asks for TALA still
opens, and `D2Layout.available` tells you which one you got. When a box asks for TALA and the
helper is missing, Field prints the command that builds it.

---

## Limits

- **Invariants only see token counts.** Read and inhibitor arcs don't change token counts, so the
  invariants can't see them. `net.report()` says so when a net uses them and suggests
  `stateSpace()`, which does see them.
- **Loop detection compares colours by name.** Two different patterns both print as
  `PatPattern(...)`, so to the loop detector swapping one section for another looks like nothing
  happening. `stateSpace()` compares colours exactly, so a net that makes a new colour on every
  firing runs out of search budget there instead.
- **The editor checks spelling, not behaviour.** The editor marks `@1/0` as a bad duration and
  `=> wobble` as an unknown combinator as you type. It can't tell that a transition will never
  fire; the Kotlin parser reports that when the box runs.

---

## Large and unbounded nets

### How large a net can be searched

The number of markings grows exponentially with the number of things that can happen at once, so
large concurrent nets exhaust the search budget. These are the sizes at the default 20 000-marking
budget:

| net | markings | note |
| --- | --- | --- |
| the examples on this page | 2 – 82 | far inside the budget |
| an *N*-slot buffer | *N* + 1 | linear |
| an *N*-section `petriSong` | *N* | linear, even though every token carries a `PatPattern` |
| *N* philosophers | 6, 14, 34, 82, 198, 478, 1154, 2786 … | `a(n) = 2a(n-1) + a(n-2)` — about **2.41× per philosopher** |

Musical nets stay far inside the budget; concurrency puzzles can exceed it. Eleven philosophers
still fit; twelve do not, and the search reports `BUDGET` and returns `null` from `canDeadlock`
rather than guessing.

A `petriSong` stays linear even though each token carries a whole `PatPattern`, because its
sections live in separate places and only one token circulates. Colour multiplies the state space
only when the same place can hold different colours; a net that mints a *fresh* colour per firing has
no finite state space at all, and gets `BUDGET`.

### Searching larger nets: stubborn sets

Most of those philosopher markings are the same firings in a different order: five philosophers
each reaching for their left fork can happen in 5! orders, and a full search visits all of them. A
partial-order reduction explores one order when the others cannot change the answer:

```diffstatic {data-lang=kotlin}
net.stateSpace(reduction = PetriReduction.STUBBORN)
```

At each marking it explores a *stubborn set*, a subset of the enabled transitions chosen so that
the transitions it skips cannot change whether a deadlock is reachable. On the philosophers the
number of markings grows as `2n² − 2n + 2` instead of about `2.41ⁿ`:

| philosophers | full | reduced |
| ---: | ---: | ---: |
| 5 | 82 | 42 |
| 8 | 1 154 | 114 |
| 11 | 16 238 | 222 |
| 12 | *budget exhausted* | 266 |

Nets where many things happen independently reduce the most. A net where only one thing can happen
at a time, like the bounded buffer, does not reduce at all.

Three limits bound what it will answer:

- **It answers only deadlock questions.** A reduced graph does not contain every reachable
  marking, so bounds read off it would be wrong. `graph.exact` is `complete && reduction == NONE`, and
  `bounds`, `isSafe`, `isLive`, `isReversible`, `homeMarkings`, `neverFires()`, `mutuallyExclusive`
  and `proves { }` all return `null` on a reduced graph. `canDeadlock` and `deadlockTrace` do not.
- **It is not a shortest path.** The reduced search reaches a deadlock that really is reachable, by a
  route at least as long as the true shortest. Every trace still passes `verify()`; it is just not
  guaranteed to be the shortest. In practice the two are usually the same length, but do not rely
  on it.
- **A colour-dependent net is not reduced.** The closure reasons about arcs, and a guard or an
  `enabledIf` is an arbitrary predicate no arc analysis can see, so `stateSpace(reduction = STUBBORN)`
  on such a net returns an unreduced (and therefore still `exact`) graph, with no complaint and with
  `graph.reduction == PetriReduction.NONE` saying so.

??? aside "How the reduction is checked"
    A differential test runs both searches over generated nets and compares the set of reachable
    terminal markings, the length of the counterexample, containment of the reduced state set in the
    full one, and that every accessor gated on `exact` refuses. `dev_docs/PETRI_REDUCTION_AGREEMENT.md`
    has the corpus, the floors and a run's numbers.

    Half of that corpus uses read arcs, inhibitor arcs, capacities and arc weights. A stubborn set
    must be closed under "this transition may disable that one" as well as under the reverse. In a
    plain place/transition net the two coincide: if firing `t` disables `t'` they share an input
    place, so `t'` is in the set already. They stop coinciding once a transition can starve a read
    arc it does not consume from, or arm an inhibitor arc by producing into its place, and a
    one-directional closure could then call a net deadlock-free that the full search deadlocks. A
    three-transition net and a four-transition net pin each case.

### When the state space is infinite

A net whose token counts can grow forever has infinitely many markings, so the search can only
report `BUDGET` on it. `net.coverability()` builds the Karp–Miller graph instead: a place that can
grow without limit is written as **ω**, which keeps the graph finite for every net.

```diffstatic {data-lang=kotlin}
val leak = petri {
    place("run", tokens = 1)
    t("make") { from("run"); to("run"); to("queue") }
}

leak.stateSpace(budget = 500).outcome    // BUDGET — and bounds, isSafe, neverFires() are all null
leak.coverability().unboundedPlaces      // [queue]
leak.coverability().bound("queue")       // null — which here means ω, not "unknown"
```

Use the two together. `stateSpace()` handles every feature (read arcs, inhibitor arcs, colour
guards, `enabledIf`, capacities) but may run out of budget. `coverability()` always finishes, but
is only correct for nets without inhibitor arcs, place capacities or colour-dependent behaviour.
When a net has any of those, it adds a line to `coverability().caveats` and sets `sound` to false.

See also: [Interaction nets](interaction_nets_examples.md), [the pattern language](pattern_language.md), [A Petri net that throws sparks](chain_petri_sparks.md), [A Deadlock, Sculpted and Traced](chain_petri_sculpture.md).

