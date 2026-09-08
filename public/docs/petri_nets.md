# Petri nets: rhythm, structure and control, in one graph

*The code is `afield.petri.*`, used from ordinary Field Kotlin: the model and DSL in `PetriNets.kt`,
the `#petri` parser in `PetriNotation.kt`, the pattern bridge in `PetriMusic.kt`, the invariants in
`PetriAnalysis.kt` and the drawing in `PetriVisual.kt`. The notation is summarised on the
[Petri cheatsheet](petri_cheatsheet.md). Start from [what's already in the box](reference/index.md).*

A Petri net is places holding tokens, transitions moving them, arrows saying where. It is one of
the few computational models with a *standard picture*, and it has about fifty years of theory
attached, most of which turns out to answer questions a live coder actually has.

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

Places are circles, tokens are dots inside them, transitions are bars: bright when they are ready
to fire, dim when they are not. The net is a cycle, so it is drawn as one without being asked.

Note the last line of the report: the net worked out its own period. Nothing in the source says
how long a bar is.

---

## The net tells you its own bar length

A *T-invariant* is a bag of firings that returns the net to the marking it started from. It is,
precisely, a loop, so a net's minimal T-invariants are its natural musical periods, and they come
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

```diffstatic {data-lang=kotlin}
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

`net.emits()` is a `PatPattern<String>`, so everything already in the language works on it, and a
Euclidean token ring plays *exactly* what `euclid` plays, which the smoke suite asserts.

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

```diffstatic {data-lang=kotlin}
kit.emits().respond(cps = 2.0) { event -> dirt(event.value) }
```

---

## Tokens that are rhythms

Here is the part that a token-counting net cannot do. A token's colour can be **anything**,
including a whole `PatPattern`. So a place is a *section*, a transition is an *edit*, and a slow
net whose tokens are rhythms is an arrangement of the fast nets it schedules.

One mechanism, both timescales.

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

```diffstatic {data-lang=text}
#petri
!timing timed

verse : "bd ~ sd ~"        // the only marked place, so only one section can sound

verse  -[ play-verse  @4 ]-> chorus
chorus -[ play-chorus @4 => rev ]-> verse
```

The `=>` word is a colour combinator. There are fourteen of them
([the cheatsheet lists them all](petri_cheatsheet.md#combinators)) and they are pattern algebra:
`stack` layers the rhythms a transition consumed, `cat` plays them one per cycle, `fast2` doubles
one, `degrade` thins it out. A transition that combines the rhythms flowing into it is a graph
whose *edges carry music*.

---

## Markings as control values

A place's token count over time is a step function, and a step function is a signal. So tension
accumulating in a place can drive a filter, and the structure and the sound stop being two
separate things bolted together.

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

## Playing one live, and poking it

Everything above is the pure half: a function of time, safe for the combinators to re-query. A
performance is not a function of time, so it lives somewhere else.

```diffstatic {data-lang=kotlin}
val live = net.live()

showPetriNet(live)                 // click a place to inject a token, a transition to fire it
live.play(cps = 2.0) { firing -> firing.emit?.let { dirt(it.toString()) } }
```

`showPetriNet(live)` wires the drawing to the net through `FLine.onClick`, so the picture is the
instrument: clicking a place drops a token in, clicking a transition fires it whether or not the
clock was going to. `play` drives the net from a pure pattern of grid ticks through the language's
own `respond`, so a live net starts, stops and re-evaluates in a box with exactly the behaviour of
any other pattern.

Left alone, a live net fires *precisely* what its pure twin fires, the same engine underneath,
asserted in `petri.smoke`. Interfere with it and it diverges from that instant, and only from that
instant.

---

## Nets that are not music

Nothing in `PetriNets.kt` knows what a note is. The model borrows exactly one thing from the audio
side (`PatQ`, the exact rational used for time everywhere in Field) and is otherwise a plain model
of concurrency. So the classical concurrency problems are just nets, and the analysis that told a
drum kit its bar length will tell a dinner party that it is about to hang.

These also show what the **Kotlin DSL** is for. The `#petri` notation is a better way to write a net
you are editing while it plays; the DSL is the only way to write a net whose shape is *computed*:
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

The middle row is the interesting one, and it is past where the textbook usually stops. Breaking
the symmetry does remove the *deadlock* — but under `PetriConflict.ORDER` the first-declared
claimant wins the contested fork every single time, so the reversed philosopher never gets it and
never eats. That is **starvation**, not deadlock: the net never stops, it just permanently excludes
someone, and `willDeadlock()` correctly says no while `deadTransitions()` says who.

Two different faults, two different fixes. The asymmetry fixes one; a fair conflict policy fixes
the other.

### Laying it out

The drawing above is `PetriLayout.AUTO`, which is what `showPetriNet` picks unless you say
otherwise. AUTO draws a net that is a single cycle as a ring, and anything else with `FORCE`: a
spring model that relaxes from 48 seeded starts and keeps whichever result scores best on crossings,
overlaps, and arcs passing under unrelated nodes. The seed comes from the net's own structure, so a
net always draws the same way. `RING` and `LAYERED` ask for the other two directly, a circle in
graph order and left-to-right by distance from the initially marked places.

`PetriLayout.TALA` sends the net to TALA instead, the engine d2 uses for architecture diagrams,
which places each node at its real size and routes the arcs. Both drawings, of one net:

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/tala-vs-force.png" wait="3.0" capture="box" open-outputs="true" embed=true name="petri-tala-vs-force" w="1240" padding="12"}
:::static
import afield.petri.*
import afield.layout.D2Layout

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

Each panel counts its own crossings, and on this table FORCE wins: none against TALA's three.
That is the result to expect more often than not, because crossings are the thing FORCE is
optimising. It relaxes from 48 random starts and keeps whichever result scores best on exactly this
metric, so where it succeeds it is hard to beat on it. Neither drawing carries names, because past
28 nodes `toFLines` drops the labels and this table has 30.

The order does reverse. On the smaller table in `dev_docs/agents/transcripts/petri_tala_layout.yaml`
FORCE crosses five times and TALA once, and TALA gets there in 2.1 s against FORCE's 4.5 s. So the
choice is per net rather than settled, which is why `AUTO` never makes it for you.

The two panels do not draw their arcs the same way, which is the other half of what TALA gives you.
A spring model places points and leaves the arcs to whatever runs between them, so FORCE's are
straight lines from centre to centre. TALA routes each arc around the nodes in its way and
`toFLines` follows those polylines, which is why the right-hand panel turns corners. On the smaller
table in the transcript that routing is what takes TALA from one crossing to none. It is also why
the two sides are not quite comparable as pictures: one is a graph drawing and the other is closer
to a schematic.

The three in that caption is already the best of four tries. TALA accepts a direction bias, and it
is worth more than a bigger seed budget: this table crosses itself five times with no bias and three
under the best of up, down and right, so `PetriLayout.TALA` lays the net out once per direction and
keeps whichever crosses itself least. Four layouts of a 30-node net still cost less than one FORCE.
On a net that is already clean the sweep finds nothing to improve and costs the four layouts anyway.

TALA runs in `native/field-d2-layout`, a Go co-process that Field neither builds nor ships. Without
it `PetriLayout.TALA` draws with `FORCE`, so a document that asks for TALA still opens on a machine
that has no Go toolchain, and `D2Layout.available` tells you which of the two you are looking at.
Field prints the build command when a box asks for TALA and cannot find the binary, with an install
hint when `go` is missing as well.

### Every interleaving, exactly — and the sequence that breaks it

Both of those answers are narrower than they sound. `willDeadlock()` runs the net forward **under
its own conflict policy** and reports whether *that one* interleaving wedged; `deadTransitions()`
names the transitions that did not fire in *that one* run. That is the deterministic question, and
for a live coder the deterministic question is usually the right one, because the conflict policy is
part of the instrument.

It is not the question a dinner party asks. A symmetric table deadlocks under *some* schedules and
not others, so running it once and finding it still going proves nothing at all, which is why the
`reverseLast` flag above had to be written by hand. `net.stateSpace()` computes the other answer:
every marking reachable by **any** interleaving, breadth-first, and the classical properties read
straight off the graph.

```diffstatic_runner {data-lang=kotlin screenshot="assets/petri_nets/state_space.png" wait="2.0" capture="box" open-outputs="true" embed=true name="petri-state-space" w="1240" padding="12"}
:::static
import afield.petri.*

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

The picture is the state space itself: breadth-first depth left to right, one dot per reachable
marking, the shortest route to the deadlock in orange with its transitions written on it, and a red
ring on the marking it gets stuck in. Every philosopher picking up one fork: five firings, and
there is nothing shorter.

Eighty-two markings is about as far as that picture goes. Above `PETRI_STATE_DRAW_LIMIT` (400
markings) the dot radius has bottomed out at two pixels and the layers overlap, so the drawing
**declines**: it shows the counterexample alone, laid out one firing per column, over a histogram of
how many markings sit at each breadth-first depth, and it writes on the figure that it did so. `graph.tooDenseToDraw()` answers the question in advance, and
`maxNodes` on `toFLines`, `toSvg` and `showPetriStateSpace` moves the line; `Int.MAX_VALUE` insists
on the hairball if you have measured that you want it. A grey rectangle with an orange line in it is
not a denser version of the information; it is the absence of it wearing the same shape.

Three things follow that the deterministic analysis cannot say:

- **A "no" means something.** `canDeadlock` is `true`, `false` or `null`, and `null` means the
  search hit its budget. Nothing here reports an unfinished search as safe: every accessor that
  would be a claim about markings nobody visited returns `null` instead of `false`.
- **The counterexample is the deliverable.** `graph.witness { … }` is `EF` with the shortest route
  attached and `graph.invariant { … }` is `AG` returning the shortest violation, so
  `mutuallyExclusive("crit.a", "crit.b")` either proves the property or hands you the two firings
  that break it. `PetriTrace.verify()` re-fires the whole sequence through the engine, so a
  counterexample proves itself rather than restating the search that produced it.
- **Read and inhibitor arcs are visible again.** The invariants cannot see them because the
  incidence matrix is a record of *change*; the search asks the engine whether a transition is
  enabled, and the engine knows. Two nets with identical incidence matrices, one of which enforces
  mutual exclusion with an inhibitor arc, are told apart by `stateSpace()` and by nothing else here.

Applied to the table above, the two analyses disagree, and the disagreement is the diagnosis:

```diffstatic {data-lang=kotlin}
val net = table(reverseLast = true)      // asymmetric, PetriConflict.ORDER
net.deadTransitions()                    // [grab.4, eat.4, done.4]  ← starved by the policy
net.stateSpace().neverFires()            // []                       ← the *net* is fine
```

Starvation belongs to the conflict policy; deadness belongs to the net. `report()` prints both
halves, and says which of four things it means: *this configuration wedged*, *some interleaving
wedges and here is the sequence*, *proved deadlock-free over N exhausted markings*, or *the budget
ran out and the answer is unknown*.

#### Where the wall is

An exhaustive search is exponential in the number of things happening at once, so the useful
question is not "does this work" but "how far does it get before it stops working". Measured, at the
default 20 000-marking budget:

| net | markings | note |
| --- | --- | --- |
| every net in `petri/document.md` | 2 – 82 | the whole document costs under 1 % of the budget |
| an *N*-slot buffer | *N* + 1 | linear; 1 024 slots is 1 025 markings in 35 ms |
| an *N*-section `petriSong` | *N* | linear, even though every token carries a `PatPattern` |
| *N* philosophers | 6, 14, 34, 82, 198, 478, 1154, 2786 … | `a(n) = 2a(n-1) + a(n-2)` — about **2.41× per philosopher** |

So a musical net is nowhere near the wall and a concurrency puzzle can be. Eleven philosophers is
16 238 markings in 230 ms and `COMPLETE`; **twelve is past the budget**, and the search says
`BUDGET` and returns `null` from `canDeadlock` rather than guessing.

The coloured-token row is the one to check. A `petriSong` carries a whole `PatPattern` in each
token's colour, and a strict key distinguishes colours that `signature()` would have merged, so the
space could have multiplied; but the sections live in separate places and exactly one token
circulates, so it stays linear. Colour multiplies the state space
only when the same place can hold different colours; a net that mints a *fresh* colour per firing has
no finite state space at all, and gets `BUDGET`.

#### Moving the wall: stubborn sets

Almost all of that 2.41ⁿ is the same firings shuffled. Five philosophers each reaching for their left
fork is one event five times over, and a full search dutifully visits all 5! orderings of it. A
**partial-order reduction** explores one ordering and proves that is enough:

```diffstatic {data-lang=kotlin}
net.stateSpace(reduction = PetriReduction.STUBBORN)
```

At each marking it explores a *stubborn set* rather than every enabled transition: seed with an
enabled transition, add everything that could disable an enabled member, add everything that could
unblock one specific reason a disabled member is stuck, repeat, and take the smallest set any seed
produces. On the philosophers the exponential becomes a quadratic:

| philosophers | full | reduced | speed-up |
| ---: | ---: | ---: | ---: |
| 5 | 82 | 42 | 2.0× |
| 8 | 1 154 | 114 | 10× |
| 11 | 16 238 | 222 | **73×** (227 ms → 5 ms) |
| 12 | *budget exhausted* | 266 | answered, at 12 firings |

`2n² − 2n + 2` reduced markings against `2.41ⁿ` full ones. The groove in box 6 of the live document
reduces 4×, `polyrhythm` 3×, `structure` 2×; the bounded buffer reduces not at all, because a
sequential net has no independence to exploit.

Three limits bound what it will answer:

- **It answers deadlock and refuses everything else.** The theorem is about terminal markings. A
  reduced graph's state count is not the reachable state count, so a bound read off it would be a
  lower bound wearing the word "exact". `graph.exact` is `complete && reduction == NONE`, and
  `bounds`, `isSafe`, `isLive`, `isReversible`, `homeMarkings`, `neverFires()`, `mutuallyExclusive`
  and `proves { }` all return `null` on a reduced graph. `canDeadlock` and `deadlockTrace` do not.
- **It is not a shortest path.** The reduced search reaches a deadlock that really is reachable, by a
  route at least as long as the true shortest. Every trace still passes `verify()`; it is just not
  guaranteed minimal. Measured, that gap has never yet opened: over 758 generated nets where both
  searches found a counterexample, the two lengths were equal every time — but the theorem does not
  promise it, so do not build on it.
- **A colour-dependent net is not reduced.** The closure reasons about arcs, and a guard or an
  `enabledIf` is an arbitrary predicate no arc analysis can see, so `stateSpace(reduction = STUBBORN)`
  on such a net returns an unreduced (and therefore still `exact`) graph, with no complaint and with
  `graph.reduction == PetriReduction.NONE` saying so.

#### The corpus the reduction is checked against

The three limits above are what the theorem says; the reduction is about eighty lines of code
deciding what *not* to explore, and a theorem does not check code. So both searches are run over
generated nets and made to agree, on something sharper than "does a deadlock exist": existence is
the weakest possible oracle, because a net with three deadlocks agrees on existence while the
reduction loses two of them. What is compared is the **set** of reachable terminal markings, the
length of the counterexample, containment of the reduced state set in the full one, and every
accessor gated on `exact` refusing.

The corpus is 1 497 nets over six families across a fixed seed list, half of them plain
place/transition nets as a control and half saturated with the read arcs, inhibitor arcs, capacities
and arc weights that are this model's additions to the classical one, which is precisely where the
classical argument stops being free.

| | |
| --- | ---: |
| nets compared | 1 497 |
| reachable markings across the corpus | 137 001 |
| markings the reduced search visited | 27 727 — **20.2%** |
| nets strictly pruned | 840 |
| nets that can deadlock / cannot | 758 / 739 |
| nets with two or more distinct deadlocks | 247 |
| widest single net | 3 465 markings |
| disagreements | 0 |

The closure has to be symmetric, and that is what the saturated half of the corpus is for. A
stubborn set must be closed under "this transition may disable that one" as well as under the
reverse. In a plain place/transition net the two coincide: if firing `t` disables `t'` they share an
input place, so `t'` is in the set already. They stop coinciding once a transition can starve a
**read** arc it does not consume from, or arm an **inhibitor** arc by producing into its place, and a
one-directional closure can then call a net deadlock-free that the full search wedges in five
firings, which is the one direction of error this layer exists not to make. A three-transition net
and a four-transition net pin each case.

The pruning ratio is itself asserted at a floor just under its measured value, because a reduction
that stopped reducing without saying so would agree with the full search perfectly and prove nothing at all.

#### When the state space is infinite

An unbounded net has infinitely many markings, so the exhaustive search can only ever report
`BUDGET` on one — true, and not what you wanted to know. `net.coverability()` is the Karp–Miller
construction: a marking that can be pumped is written with an **ω** in the growing place, which
makes the graph finite for *every* net.

```diffstatic {data-lang=kotlin}
val leak = petri {
    place("run", tokens = 1)
    t("make") { from("run"); to("run"); to("queue") }
}

leak.stateSpace(budget = 500).outcome    // BUDGET — and bounds, isSafe, neverFires() are all null
leak.coverability().unboundedPlaces      // [queue]
leak.coverability().bound("queue")       // null — which here means ω, not "unknown"
```

The two are complements. **The exhaustive search handles
every feature of the model exactly (read arcs, inhibitor arcs, colour guards, `enabledIf`,
capacities) and may not terminate. Karp–Miller always terminates and is only valid for a plain
place/transition net.** Inhibitor arcs, place capacities and colour-dependent behaviour each break
the monotonicity argument that ω rests on, so each adds a line to `coverability().caveats` and flips
`sound` to false rather than being ignored.

`petri_state_space` in the [transcript gallery](agent_transcripts.md) is all of this as five
runnable boxes.

### A bounded buffer, and a proof that it cannot leak

A *P-invariant* is a weighted sum of token counts that no transition can change: a conservation
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

`free + items` is the law you want: a slot can never be lost or invented, so the buffer cannot leak
and cannot overflow. The other two say the producer and the consumer are each exactly one worker.
All three come out of the arcs; nothing declares them.

---

## What the notation cannot do

Three limits:

- **Invariants are structural.** They read arc weights and nothing else, so read and inhibitor arcs
  are invisible to them: neither changes the marking, and the incidence matrix is a record of
  change. `net.report()` says so itself when a net uses them, and points at `stateSpace()`, which
  asks the engine instead and therefore does see them.
- **Recurrence compares colours by name.** Two different patterns both render as `PatPattern(...)`,
  so a section swap looks, to the loop detector, like nothing happening. That is usually the right
  coarseness for a *structural* period and occasionally not what you want. `stateSpace()`
  does **not** use that key (a coarse key merges markings with different
  futures and can hide a deadlock), so a net that mints fresh colours exhausts its budget there
  instead.
- **Editor diagnostics are lexical.** `petri.js` can see that `@1/0` is not a duration and that
  `=> wobble` is not a combinator. It cannot see that a transition will never fire: that is a
  property of the graph, and the Kotlin parser reports it with the whole graph in front of it.
