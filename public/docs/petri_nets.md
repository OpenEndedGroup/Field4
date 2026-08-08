# Petri nets: rhythm, structure and control, in one graph

*The code is `afield.petri.*`, used from ordinary Field Kotlin: the model and DSL in `PetriNets.kt`,
the `#petri` parser in `PetriNotation.kt`, the pattern bridge in `PetriMusic.kt`, the invariants in
`PetriAnalysis.kt` and the drawing in `PetriVisual.kt`. The notation is summarised on the
[Petri cheatsheet](petri_cheatsheet.md). Start from [what's already in the box](reference/index.md).*

A Petri net is places holding tokens, transitions moving them, arrows saying where. It is one of
the few computational models with a *standard picture*, and it has about fifty years of theory
attached — most of which turns out to answer questions a live coder actually has.

Using one as a sequencer is not new. What is new here is that in Field a net does not have to be a
sequencer at all, because of one fact:

**A net with a deterministic conflict policy is a pure function of time — so a net is a
`PatPattern`.**

That sounds like an implementation note. It is the whole design. `PatPattern` is not a list of
notes; it is a function from a span of time to the events inside it, and every combinator in
[the pattern language](pattern_language.md) is free to call it with any span, in any order, as
often as it likes. A stateful sequencer cannot survive that. A memoised Petri net can. The moment
it does, `every`, `jux`, `off`, `fast`, `rev`, `with gain(sine())`, the offline renderer, the piano
roll and `respond {}` all apply to a Petri net without a line of new code.

---

## The smallest net that plays anything

One token, two places, two transitions. The token cannot enable both transitions at once, so the
net alternates — and that alternation *is* the rhythm.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/two_place_loop.png" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-two-place-loop" w="1240" padding="12"}
:::static
import afield.petri.*

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

Places are circles, tokens are dots inside them, transitions are bars — bright when they are ready
to fire, dim when they are not. The net is a cycle, so it is drawn as one without being asked.

Note the last line of the report: the net worked out its own period. Nothing in the source says
how long a bar is.

---

## The net tells you its own bar length

A *T-invariant* is a bag of firings that returns the net to the marking it started from. It is,
precisely, a loop — so a net's minimal T-invariants are its natural musical periods, and they come
out of the incidence matrix with no notion of time or tempo involved.

Here are two token rings sharing one net: five steps against three. Nothing in the source mentions
fifteen.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/polyrhythm.png" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-polyrhythm" w="1240" padding="12"}
:::static
import afield.petri.*

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

The companion question is the one that matters at two in the morning: **is this about to wedge?**

```kotlin
net.willDeadlock()      // false — it loops
net.deadTransitions()   // [] — every voice is sounding

petri("""
    armed : 1
    src   : 4
    ~armed + src -[ blocked : bd ]-> dst
""").willDeadlock()     // true — nothing can ever empty 'armed'
```

`~armed` is an inhibitor arc: enabled only while that place is *empty*. Nothing here ever empties
it, so `blocked` never fires, and `willDeadlock()` says so before you commit the edit rather than
after you hear the silence.

---

## The whole pattern language applies

This is the payoff. `net.emits()` is a `PatPattern<String>`, so everything already in the language
works on it — and a Euclidean token ring plays *exactly* what `euclid` plays, which is asserted in
the smoke suite rather than hoped for.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/language.png" wait="2.5" capture="box" open-outputs="true" embed=true name="petri-language" w="1240" padding="12"}
:::static
import afield.petri.*
import afield.four.synth.*

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

```kotlin
kit.emits().respond(cps = 2.0) { event -> dirt(event.value) }
```

---

## Tokens that are rhythms

Here is the part that a token-counting net cannot do. A token's colour can be **anything**,
including a whole `PatPattern`. So a place is a *section*, a transition is an *edit*, and a slow
net whose tokens are rhythms is an arrangement of the fast nets it schedules.

One mechanism, both timescales — which is the thing worth having.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/sections.png" wait="2.5" capture="box" open-outputs="true" embed=true name="petri-sections" w="1240" padding="12"}
:::static
import afield.petri.*
import afield.four.synth.*

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

`petriSong` builds the net; written out, it is the notation you would expect, and `!timing timed` is
what makes it work. Under timed semantics a firing *takes* its duration, so a transition with `@4`
genuinely holds a four-cycle section open rather than passing a token along instantly:

```
#petri
!timing timed

verse : "bd ~ sd ~"        // the only marked place, so only one section can sound

verse  -[ play-verse  @4 ]-> chorus
chorus -[ play-chorus @4 => rev ]-> verse
```

The `=>` word is a colour combinator. There are fourteen of them —
[the cheatsheet lists them all](petri_cheatsheet.md#combinators) — and they are pattern algebra:
`stack` layers the rhythms a transition consumed, `cat` plays them one per cycle, `fast2` doubles
one, `degrade` thins it out. A transition that combines the rhythms flowing into it is a graph
whose *edges carry music*.

---

## Markings as control values

A place's token count over time is a step function, and a step function is a signal. So tension
accumulating in a place can drive a filter, and the structure and the sound stop being two
separate things bolted together.

```kotlin
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

## Playing one live, and poking it

Everything above is the pure half — a function of time, safe for the combinators to re-query. A
performance is not a function of time, so it lives somewhere else on purpose.

```kotlin
val live = net.live()

showPetriNet(live)                 // click a place to inject a token, a transition to fire it
live.play(cps = 2.0) { firing -> firing.emit?.let { dirt(it.toString()) } }
```

`showPetriNet(live)` wires the drawing to the net through `FLine.onClick`, so the picture is the
instrument: clicking a place drops a token in, clicking a transition fires it whether or not the
clock was going to. `play` drives the net from a pure pattern of grid ticks through the language's
own `respond`, so a live net starts, stops and re-evaluates in a box with exactly the behaviour of
any other pattern.

Left alone, a live net fires *precisely* what its pure twin fires — the same engine underneath,
asserted in `petri.smoke`. Interfere with it and it diverges from that instant, and only from that
instant.

---

## Nets that are not music

Nothing in `PetriNets.kt` knows what a note is. The model borrows exactly one thing from the audio
side — `PatQ`, the exact rational used for time everywhere in Field — and is otherwise a plain model
of concurrency. So the classical concurrency problems are just nets, and the analysis that told a
drum kit its bar length will tell a dinner party that it is about to hang.

These also show what the **Kotlin DSL** is for. The `#petri` notation is a better way to write a net
you are editing while it plays; the DSL is the only way to write a net whose shape is *computed* —
five philosophers is a `for` loop, not five lines of text.

### Dining philosophers

Each philosopher needs the fork on their left and the fork on their right. Everyone reaches left
first, so in the very first maximal step all five forks are gone and nobody can pick up a second.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/philosophers.png" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-philosophers" w="1240" padding="12"}
:::static
import afield.petri.*

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
    net.deadTransitions().ifEmpty { listOf("—") }.joinToString(", ") + " |"

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

The middle row is the interesting one, and it is not what the textbook usually stops at. Breaking
the symmetry does remove the *deadlock* — but under `PetriConflict.ORDER` the first-declared
claimant wins the contested fork every single time, so the reversed philosopher never gets it and
never eats. That is **starvation**, not deadlock: the net never stops, it just permanently excludes
someone, and `willDeadlock()` correctly says no while `deadTransitions()` says who.

Two different faults, two different fixes. The asymmetry fixes one; a fair conflict policy fixes
the other.

### A bounded buffer, and a proof that it cannot leak

A *P-invariant* is a weighted sum of token counts that no transition can change — a conservation
law, read straight off the incidence matrix. For a producer/consumer buffer that is exactly the
property you want to be sure of.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/bounded_buffer.png" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-bounded-buffer" w="1240" padding="12"}
:::static
import afield.petri.*

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

`free + items` is the law worth having: a slot can never be lost or invented, so the buffer cannot
leak and cannot overflow. The other two say the producer and the consumer are each exactly one
worker. None of it was asserted — all three were computed from the arcs.

---

## What the notation cannot do

Three limits, stated once rather than discovered later:

- **Invariants are structural.** They read arc weights and nothing else, so read and inhibitor arcs
  are invisible to them — neither changes the marking, and the incidence matrix is a record of
  change. `net.report()` says so itself when a net uses them.
- **Recurrence compares colours by name.** Two different patterns both render as `PatPattern(...)`,
  so a section swap looks, to the loop detector, like nothing happening. That is usually the right
  coarseness for a *structural* period and occasionally not what you want.
- **Editor diagnostics are lexical.** `petri.js` can see that `@1/0` is not a duration and that
  `=> wobble` is not a combinator. It cannot see that a transition will never fire — that is a
  property of the graph, and the Kotlin parser reports it with the whole graph in front of it.
