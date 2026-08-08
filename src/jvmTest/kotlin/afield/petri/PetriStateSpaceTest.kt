package afield.petri

import afield.four.synth.pat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exhaustive analysis in `PetriStateSpace.kt`.
 *
 * The textbook nets are here because their answers are known independently of this code: a
 * symmetric dining table deadlocks and an asymmetric one cannot, and any implementation that
 * disagrees with those two facts is wrong however elegant it is. Where a number *is* remembered —
 * 82 reachable markings, a counterexample of length 5 — it is pinned exactly rather than as a
 * bound, because the interesting failure is a search that quietly stops exploring, and a loose
 * floor is exactly what such a search would still satisfy.
 *
 * The other half of the file is about the *difference* between this analysis and the deterministic
 * one next door. Several tests assert that `deadTransitions()` and `neverFires()` disagree, on nets
 * where they should: that disagreement is the reason this code exists and if it ever went away
 * something would have quietly become wrong.
 */
class PetriStateSpaceTest {

    // ── the nets ──────────────────────────────────────────────────────────────────────────

    /** The dining table from the workspace demo, verbatim: five philosophers, five forks. */
    private fun table(reverseLast: Boolean, fairly: Boolean = false) = petri {
        conflict = if (fairly) PetriConflict.ROUND_ROBIN else PetriConflict.ORDER
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

    /** The producer/consumer buffer from `public/docs/petri_nets.md`, verbatim. */
    private fun boundedBuffer() = petri {
        grid = cyc(1, 8)
        place("free", tokens = 4)
        place("items", capacity = 4)
        place("producer", tokens = 1)
        place("consumer", tokens = 1)
        t("produce") { from("producer"); from("free"); to("items"); to("producer") }
        t("consume") { from("consumer"); from("items"); to("free"); to("consumer") }
    }

    // ── the deadlock, and the sequence that causes it ─────────────────────────────────────

    @Test
    fun `the symmetric table wedges, and the counterexample is every philosopher taking one fork`() {
        val graph = table(reverseLast = false).stateSpace()

        assertEquals(PetriSearchOutcome.COMPLETE, graph.outcome, "82 markings is well inside the budget")
        assertEquals(82, graph.stateCount, "the symmetric table has exactly 82 reachable markings")
        assertEquals(265, graph.edgeCount)

        val trace = assertNotNull(graph.deadlockTrace(), "a symmetric table must be able to wedge")
        assertEquals(5, trace.length, "five philosophers, one grab each — nothing shorter can wedge")
        assertEquals(
            (0 until 5).map { "grab.$it" }.toSet(), trace.transitions.toSet(),
            "the shortest way to wedge is every philosopher picking up exactly one fork",
        )
        assertNull(trace.verify(), "the counterexample must survive being re-fired through the engine")

        // Not merely "some marking is dead": the *specific* dead marking is everyone holding one
        // fork and no fork left on the table.
        for (i in 0 until 5) {
            assertEquals(1, trace.end.count("holding.$i"), "philosopher $i should be holding one fork")
            assertEquals(0, trace.end.count("fork.$i"), "no fork should be left on the table")
        }
        assertTrue(graph.net.enabled(trace.end).isEmpty(), "the end of a deadlock trace enables nothing")
    }

    @Test
    fun `the asymmetric table is provably deadlock-free, not merely un-deadlocked in one run`() {
        val graph = table(reverseLast = true).stateSpace()

        assertEquals(PetriSearchOutcome.COMPLETE, graph.outcome)
        assertEquals(70, graph.stateCount, "breaking the symmetry removes exactly twelve markings")
        assertEquals(219, graph.edgeCount)
        assertTrue(graph.deadMarkings.isEmpty(), "no reachable marking may enable nothing")
        assertEquals(false, graph.canDeadlock, "COMPLETE plus no dead markings is a proof, not a guess")
        assertEquals(true, graph.isLive, "every philosopher can always eat again")
        assertEquals(true, graph.isReversible, "the table can always be cleared")
        assertEquals(70, graph.homeMarkings?.size, "one bottom component, so every marking is a home marking")
    }

    @Test
    fun `the symmetric table's only home marking is the deadlock`() {
        // A pleasing and slightly alarming consequence of a single bottom component: the one state
        // this table can always get back to is the one it can never get out of.
        val graph = table(reverseLast = false).stateSpace()
        assertEquals(listOf(graph.deadMarkings.single()), graph.homeMarkings)
        assertEquals(false, graph.isReversible)
        assertEquals(false, graph.isLive)
        assertEquals(emptySet(), graph.liveTransitions, "a reachable deadlock makes nothing live")
    }

    @Test
    fun `every recorded shortest path really reaches the state it is recorded for`() {
        // The search's own bookkeeping, checked against the engine 82 times. A wrong parent pointer
        // or a state interned under the wrong key would show up here and nowhere else.
        val graph = table(reverseLast = false).stateSpace()
        for (id in 0 until graph.stateCount) {
            val trace = graph.pathTo(id)
            assertNull(trace.verify(), "state $id: ${trace.verify()}")
            assertEquals(graph.states[id], trace.end, "state $id is not where its path ends")
            assertEquals(graph.depth(id), trace.length)
        }
    }

    // ── the deterministic answer and the classical one, side by side ──────────────────────

    @Test
    fun `deadTransitions names a starved philosopher where neverFires correctly names nobody`() {
        // The human's own demo already notices this: under ORDER the first-declared claimant always
        // wins the contested fork, so the reversed philosopher never eats. That is starvation, a
        // property of the *policy*. Deadness is a property of the *net*, and the net is fine.
        val net = table(reverseLast = true, fairly = false)

        assertTrue("eat.4" in net.deadTransitions(), "under ORDER the reversed philosopher starves")
        assertFalse(net.willDeadlock(), "…but the net never stops")

        val graph = net.stateSpace()
        assertEquals(emptyList(), graph.neverFires(), "some interleaving does feed philosopher 4")
        assertTrue("eat.4" in graph.quasiLive)
    }

    /**
     * A net whose conflict policy steers round its own cliff: `keep` outranks `fall`, so the token
     * is claimed by the self-loop every tick and the run goes forever.
     */
    private fun cliff() = petri {
        place("token", tokens = 1)
        t("keep", priority = 1) { from("token"); to("token") }
        t("fall") { from("token"); to("gone") }
    }

    @Test
    fun `the deterministic check misses a deadlock the state space finds`() {
        // `willDeadlock` runs one trajectory, and under PRIORITY that trajectory never takes the
        // branch that wedges. The net can still wedge, and this is the whole argument for the file:
        // the two answers are to two different questions and only one of them is about the net.
        val net = cliff()
        assertFalse(net.willDeadlock(), "the policy always picks the self-loop, so the run never stops")
        assertEquals(emptyList(), net.deadTransitions().filter { it == "keep" })
        assertTrue("fall" in net.deadTransitions(), "the deterministic run never even tries it")

        val graph = net.stateSpace()
        assertEquals(true, graph.canDeadlock, "one firing of the branch nobody takes and it is over")
        assertEquals(1, assertNotNull(graph.deadlockTrace()).length)
        assertEquals(listOf("fall"), graph.deadlockTrace()!!.transitions)
        assertEquals(emptyList(), graph.neverFires(), "'fall' is perfectly fireable; it is just never chosen")
    }

    // ── boundedness ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the four-slot buffer has exactly five reachable markings and cannot wedge`() {
        val graph = boundedBuffer().stateSpace()

        assertEquals(PetriSearchOutcome.COMPLETE, graph.outcome)
        assertEquals(5, graph.stateCount, "free+items is conserved at 4, so there are five markings")
        assertEquals(8, graph.edgeCount, "five markings, two transitions, two of them blocked at the ends")
        assertEquals(4, graph.bound("items"), "four slots, and the state space measures four")
        assertEquals(4, graph.bound("free"))
        assertEquals(1, graph.bound("producer"))
        assertEquals(false, graph.isSafe, "four tokens in one place is not 1-bounded")
        assertEquals(false, graph.canDeadlock)
        assertEquals(true, graph.isLive)
        assertEquals(true, graph.isReversible)
    }

    @Test
    fun `an unbounded place is an omega in the coverability graph and a budget in the state space`() {
        val leak = petri {
            place("run", tokens = 1)
            t("make") { from("run"); to("run"); to("queue") }
        }

        val cover = leak.coverability()
        assertTrue(cover.sound, "a plain P/T net is exactly what Karp-Miller is for")
        assertEquals(PetriSearchOutcome.COMPLETE, cover.outcome, "the acceleration must terminate")
        assertEquals(2, cover.nodes.size, "one initial marking and one accelerated one, and that is all")
        assertEquals(listOf("queue"), cover.unboundedPlaces)
        assertNull(cover.bound("queue"), "null here means omega, not unknown")
        assertEquals(1, cover.bound("run"))
        assertTrue(cover.nodes.any { it.isOmega("queue") })

        // The same net through the exhaustive search: no answer, and no pretence of one.
        val graph = leak.stateSpace(budget = 64)
        assertEquals(PetriSearchOutcome.BUDGET, graph.outcome)
        assertNull(graph.canDeadlock, "a truncated search must say unknown, never 'safe'")
        assertNull(graph.bounds)
        assertNull(graph.isSafe)
        assertNull(graph.neverFires())
        assertNull(graph.isLive)
        assertTrue("BUDGET EXHAUSTED" in graph.report(), graph.report())
    }

    @Test
    fun `Karp-Miller declares itself unsound on the three features that break it`() {
        assertTrue(boundedBuffer().coverability().caveats.single().contains("bounded places"))

        val inhibited = petri {
            place("a", tokens = 1)
            t("go") { from("a"); inhibit("stop"); to("b") }
        }
        assertTrue(inhibited.coverability().caveats.single().contains("inhibitor"))

        val coloured = petri {
            place("in", colours = listOf("x"))
            t("move") { from("in"); to("out") }
        }
        assertTrue(coloured.isColourDependent)
        assertTrue(coloured.coverability().caveats.single().contains("colour"))

        // A capacity is the one of the three the construction can still be exact about, because a
        // capped place is bounded by its capacity whatever the pumping argument says. ω is never
        // assigned there, so the answer for that place is right even though the caveat stands.
        val capped = petri {
            place("run", tokens = 1)
            place("queue", capacity = 3)
            t("make") { from("run"); to("run"); to("queue") }
        }
        assertFalse(capped.coverability().sound)
        assertEquals(3, capped.coverability().bound("queue"), "a capped place is never ω")
        assertTrue(capped.coverability().isBounded)
        assertEquals(4, capped.stateSpace().stateCount, "and the exhaustive search agrees: 0..3 in the queue")

        // …and the exhaustive search handles all three exactly, which is the trade the file makes.
        assertEquals(PetriSearchOutcome.COMPLETE, inhibited.stateSpace().outcome)
        assertEquals(PetriSearchOutcome.COMPLETE, coloured.stateSpace().outcome)
    }

    // ── what the incidence matrix cannot see ──────────────────────────────────────────────

    @Test
    fun `an inhibitor arc enforces mutual exclusion, and only the state space can tell`() {
        // The places are declared explicitly, in one order, because auto-declaration follows first
        // mention and the inhibitor arc mentions the other side's critical place early — which
        // would permute the rows and make the matrices below incomparable for an uninteresting
        // reason.
        fun net(guarded: Boolean) = petri {
            place("idle.a", tokens = 1)
            place("idle.b", tokens = 1)
            place("crit.a")
            place("crit.b")
            t("enter.a") { from("idle.a"); if (guarded) inhibit("crit.b"); to("crit.a") }
            t("leave.a") { from("crit.a"); to("idle.a") }
            t("enter.b") { from("idle.b"); if (guarded) inhibit("crit.a"); to("crit.b") }
            t("leave.b") { from("crit.b"); to("idle.b") }
        }

        val safe = net(guarded = true)
        val unsafe = net(guarded = false)

        // The two nets have *the same incidence matrix*, because an inhibitor arc moves no token.
        // So no amount of algebra can distinguish them, and the P-invariants of both are the same
        // two "a process is either idle or critical" laws — neither of which relates the two
        // critical sections to each other.
        assertEquals(
            safe.incidence().map { it.toList() }, unsafe.incidence().map { it.toList() },
            "the inhibitor arc is invisible to the incidence matrix, which is the point",
        )
        assertTrue(
            safe.pInvariants().none { "crit.a" in it && "crit.b" in it },
            "no conservation law couples the two critical sections",
        )

        // The search asks the engine instead, and gets the real answer for each.
        assertEquals(3, safe.stateSpace().stateCount, "idle/idle, a critical, b critical")
        assertEquals(true, safe.stateSpace().mutuallyExclusive("crit.a", "crit.b"))

        val breach = assertNotNull(unsafe.stateSpace().mutualExclusionFailure("crit.a", "crit.b"))
        assertEquals(2, breach.length, "without the guard, two firings put both processes inside")
        assertNull(breach.verify())
        assertEquals(false, unsafe.stateSpace().mutuallyExclusive("crit.a", "crit.b"))
    }

    @Test
    fun `a read arc gates the search without being consumed by it`() {
        val net = petri {
            place("key", tokens = 1)
            place("waiting", tokens = 1)
            t("unlock") { from("key"); to("door") }
            t("pass") { read("door"); from("waiting"); to("through") }
        }

        assertEquals(
            listOf("unlock"), net.successors(net.initial).map { it.first },
            "'pass' must not be enabled while the door is shut",
        )

        val trace = assertNotNull(net.stateSpace().witness { it.count("through") > 0 })
        assertEquals(listOf("unlock", "pass"), trace.transitions)
        assertEquals(1, trace.end.count("door"), "a read arc tests; it does not consume")
        assertNull(trace.verify())
    }

    @Test
    fun `the search refuses a firing that would overfill a place, exactly as the engine does`() {
        val net = petri {
            place("slot", tokens = 1, capacity = 1)
            t("spin") { from("slot"); to("slot") }
        }
        // PetriRun checks capacity against the marking as it stood *before* the transition
        // consumed, so a self-loop on a full place is blocked. The state space must describe the
        // same net or it is describing nothing.
        assertTrue(net.willDeadlock(), "the engine's answer")
        val graph = net.stateSpace()
        assertEquals(1, graph.stateCount)
        assertEquals(0, graph.edgeCount)
        assertEquals(0, assertNotNull(graph.deadlockTrace()).length, "stuck before it ever fires")
    }

    // ── the state key ─────────────────────────────────────────────────────────────────────

    private class Opaque(@Suppress("unused") val id: Int) {
        override fun toString(): String = "opaque"
    }

    @Test
    fun `the state key is strictly finer than signature, which is why it is not signature`() {
        val net = petri {
            place("a", colours = listOf(Opaque(1)))
            place("b", colours = listOf(Opaque(2)))
            t("pushA") { from("a"); to("pool") }
            t("pushB") { from("b"); to("pool") }
        }

        val graph = net.stateSpace()
        // Five markings: the start, one push either way, and the pool holding both — which is
        // reachable in two arrival orders, and those are two *different* markings because
        // consumption is FIFO and so they hand different tokens to whatever consumes next.
        assertEquals(5, graph.stateCount)
        assertEquals(
            4, graph.states.map { it.signature() }.distinct().size,
            "signature() sorts colours and renders them with toString, so two of the five collide",
        )
        // Which is exactly the merge that would be unsound: two markings, one signature, different
        // futures. Keyed on signature this search would have visited four states, not five.
        assertEquals(5, graph.states.distinct().size, "the five markings really are distinct")
    }

    // ── queries and traces ────────────────────────────────────────────────────────────────

    @Test
    fun `a witness is the shortest route and an invariant is the shortest violation`() {
        val graph = boundedBuffer().stateSpace()

        val full = assertNotNull(graph.witness { it.count("items") == 4 })
        assertEquals(4, full.length, "four produces and nothing else")
        assertEquals(List(4) { "produce" }, full.transitions)

        assertNull(graph.invariant { it.count("items") + it.count("free") == 4 }, "a genuine invariant")
        val broken = assertNotNull(graph.invariant { it.count("items") <= 2 })
        assertEquals(3, broken.length, "the third produce is the first violation")
    }

    @Test
    fun `a trace that does not hold up says so instead of being believed`() {
        val net = boundedBuffer()
        val bogus = PetriTrace(net, listOf("consume"), listOf(net.initial, net.initial))
        assertNotNull(bogus.verify(), "an empty buffer cannot be consumed from")
        assertTrue("not enabled" in bogus.verify()!!, bogus.verify()!!)

        val absent = PetriTrace(net, listOf("teleport"), listOf(net.initial, net.initial))
        assertNotNull(absent.verify(), "a transition that does not exist cannot fire")
    }

    // ── the report ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the report distinguishes wedging, being able to wedge, and being proved not to`() {
        val wedged = table(reverseLast = false, fairly = false).report()
        assertTrue("DEADLOCK at" in wedged, wedged)
        assertTrue("DEADLOCK reachable in 5 firings" in wedged, wedged)

        // A net that does *not* wedge as played but can. This is the line the old report could not
        // write at all, and it is the whole reason for the change.
        val steered = cliff().report()
        assertFalse("DEADLOCK at" in steered, steered)
        assertTrue("period" in steered, steered)
        assertTrue("DEADLOCK reachable in 1 firings: fall" in steered, steered)

        val proved = table(reverseLast = true).report()
        assertTrue("deadlock-free: no reachable marking enables nothing" in proved, proved)
        assertTrue("reversible" in proved, proved)

        // `maxSteps` is small on purpose: the *deterministic* half of the report materialises a
        // trajectory, and an unbounded net's markings grow quadratically as it does. That is a
        // pre-existing trap — `PetriAnalysisTest` caps this net at 64 steps for the same reason —
        // and it is why the state-space half is the one that gets to answer here.
        val leaking = petri {
            place("run", tokens = 1)
            t("make") { from("run"); to("run"); to("queue") }
        }.report(maxSteps = 64, stateBudget = 256)
        assertTrue("BUDGET EXHAUSTED" in leaking, leaking)
        assertTrue("unbounded, so the state space is infinite: queue" in leaking, leaking)

        assertFalse("state space" in table(reverseLast = true).report(stateBudget = 0), "budget 0 opts out")
    }

    // ── the picture ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the state graph draws itself with the counterexample laid over it`() {
        val graph = table(reverseLast = false).stateSpace()
        val trace = assertNotNull(graph.deadlockTrace())

        assertEquals(5, graph.diameterFound, "six BFS layers, and the deadlock is in the last one")

        // One line per edge, one per node, and a ring each for the initial marking and the single
        // deadlock. Pinned exactly, because the failure this is here for is a drawing that quietly
        // stops emitting geometry and still returns a plausible-looking list.
        val bare = graph.toFLines(highlight = null, labels = false)
        assertEquals(graph.edgeCount + graph.stateCount + 2, bare.size)
        assertEquals(349, bare.size)

        val highlighted = graph.toFLines(highlight = trace, labels = false)
        assertEquals(bare.size, highlighted.size, "highlighting recolours lines; it does not add any")

        // Labels are glyph outlines and need a font, which a headless build may not have — the
        // drawing is designed to survive that rather than throw. Where there *is* a font, the five
        // transition names plus 'start' and 'stuck' must actually be drawn, because a picture whose
        // counterexample is unlabelled is the failure mode this whole rendering exists to avoid.
        val labelled = graph.toFLines(highlight = trace, labels = true)
        assertTrue(labelled.size >= highlighted.size)
        val hasFont = runCatching { afield.stdlib.typeface(java.awt.Font.MONOSPACED, 13.0) }.isSuccess
        if (hasFont) {
            assertTrue(
                labelled.size >= highlighted.size + 30,
                "expected glyph outlines for grab.0…grab.4, start and stuck; got ${labelled.size - highlighted.size}",
            )
        }

        val svg = graph.toSvg(trace, w = 1400.0, h = 820.0)
        assertTrue("<svg" in svg && svg.length > 20_000, "the SVG is suspiciously small (${svg.length} chars)")
    }

    @Test
    fun `the layered layout separates the depths and spreads each of them out`() {
        // A collapsed layout draws every node on top of every other and still returns the right
        // number of lines, so the count assertions above cannot catch it. Positions can.
        val graph = boundedBuffer().stateSpace()
        val svg = graph.toSvg(w = 900.0, h = 380.0)
        assertTrue("<path" in svg, svg.take(400))

        val table = table(reverseLast = false).stateSpace()
        val drawn = table.toFLines(labels = false)
        val xs = drawn.flatMap { line -> line.nodes.mapNotNull { it.toOrNull?.x } }
        val ys = drawn.flatMap { line -> line.nodes.mapNotNull { it.toOrNull?.y } }
        assertTrue(xs.max() - xs.min() > 900f, "the layers should fill the width, spanning ${xs.max() - xs.min()}")
        assertTrue(ys.max() - ys.min() > 500f, "the widest layer should fill the height, spanning ${ys.max() - ys.min()}")
    }

    // ── the nets the human actually writes ────────────────────────────────────────────────

    /**
     * The six boxes of `~/Documents/AFieldWorkspace/petri/document.md`, verbatim.
     *
     * The textbook nets above say the algorithm is right. These say it is *useful*: they are the
     * nets this feature exists to be pointed at, they use the parts of the model the theory chapter
     * does not (a `PatPattern` as a token colour, a read arc, a weighted arc, a capacity, three
     * priority levels), and their sizes are the honest answer to "what happens when I try this on my
     * own work".
     */
    private fun liveDocumentNets(): List<Pair<String, PetriNet>> = listOf(
        "loop" to petri("#petri\n!grid 1/8\nkick : 1\nkick -[ hit @1/8 : bd ]-> hats\nhats -[ back @1/8 : sd ]-> kick"),
        "tokens" to petriSong("verse" to (pat("bd ~ sd ~") to 4), "chorus" to (pat("bd*4 hh*8") to 4)),
        "structure" to petri(
            """
            #petri
            !grid 1/4
            verse.a : 1
            verse.a -[ v1 @1   ]-> verse.b
            verse.b -[ v2 @1   ]-> chorus
            chorus  -[ back @1 ]-> verse.a
            beat : 1
            &verse.a + beat -[ vbeat @1/4 : bd ]-> beat2
            &verse.b + beat -[ vbeat2 @1/4 : bd ]-> beat2
            &chorus + beat  -[ cbeat @1/4 : sd ]-> beat2
            beat2 -[ off @1/4 : hh ]-> beat
            """
        ),
        "polyrhythm" to petri(
            """
            #petri
            !grid 1/15
            a0 : 1
            a0 -[ a0.go : bd ]-> a1
            a1 -[ a1.go      ]-> a2
            a2 -[ a2.go : bd ]-> a3
            a3 -[ a3.go      ]-> a4
            a4 -[ a4.go      ]-> a0
            b0 : 1
            b0 -[ b0.go : hh ]-> b1
            b1 -[ b1.go      ]-> b2
            b2 -[ b2.go : hh ]-> b0
            """
        ),
        "philosophers" to table(reverseLast = false),
        "playground" to petri(
            """
            #petri
            !grid 1/8
            !conflict priority
            pulse : 1
            fill  : 0 ^3
            pulse  -[ a @1/8 pri=9 : bd ]-> mid
            mid    -[ b @1/8 pri=9 : hh ]-> pulse
            &mid   -[ build @1/8 pri=3      ]-> fill
            3*fill -[ crash @1/8 pri=1 : sd ]->
            """
        ),
    )

    @Test
    fun `every net in the live petri document is small enough to answer exactly`() {
        val sizes = liveDocumentNets().associate { (name, net) ->
            val graph = net.stateSpace()
            assertEquals(PetriSearchOutcome.COMPLETE, graph.outcome, "$name did not finish")
            name to graph.stateCount
        }
        // Exact, because "it fits" is not the interesting claim — *how much room is left* is.
        assertEquals(
            mapOf(
                "loop" to 2, "tokens" to 2, "structure" to 6,
                "polyrhythm" to 15, "philosophers" to 82, "playground" to 8,
            ),
            sizes,
        )
        // The whole document, every box, costs less than one percent of the default budget.
        assertTrue(sizes.values.sum() < PETRI_STATE_BUDGET / 100, "total ${sizes.values.sum()}")
    }

    @Test
    fun `the playground groove's read arc, weighted arc and capacity are all honoured`() {
        // Box 6 is the one that uses every feature the incidence matrix cannot see. `build` reads
        // `mid` without consuming it and `crash` drains three `fill` at once into nothing, and the
        // net is claimed in the document to stay bounded and periodic. Now it is checked.
        val net = liveDocumentNets().single { it.first == "playground" }.second
        val graph = net.stateSpace()
        assertTrue(net.usesTestArcs, "the read arc is the point of this net")
        assertEquals(3, graph.bound("fill"), "the capacity `^3` really does cap it")
        assertEquals(false, graph.canDeadlock, "the document claims this groove does not wedge")
        assertEquals(true, graph.isLive)
        assertEquals(true, graph.isReversible)
        assertEquals(emptyList(), graph.neverFires(), "every voice is reachable")
    }

    @Test
    fun `the deadTransitions fix changes nothing the live document prints`() {
        // `deadTransitions` now stops at `within` instead of running to maxUnroll, which is a
        // semantic change to a public function the human's boxes call through `report()`. This
        // replays the old behaviour beside the new one on the nets those boxes build. If it ever
        // starts differing, that is a fact somebody needs to be told rather than discover.
        //
        // `maxUnroll` is capped at 20 000 rather than left at the default 200 000, and the reason is
        // itself part of the argument: replaying the old behaviour at its real ceiling exhausts the
        // heap when this runs inside the full suite. `PetriRun` keeps every step and `PetriMarking.add`
        // copies a place's token list, so a long replay costs quadratic memory — which is exactly the
        // trap the fix removes from `report()`. So this proves the narrower, affordable claim: no
        // transition in these nets first fires between `within` and 20 000 steps, so nothing the
        // document prints moves.
        fun before(net: PetriNet, within: Int): List<String> {
            val run = net.run(maxUnroll = 20_000)
            run.lasso(within)
            val fired = run.steps(Int.MAX_VALUE).flatMap { step -> step.firings.map { it.transition } }.toSet()
            return net.transitionNames.filter { it !in fired }
        }
        val nets = liveDocumentNets() + listOf(
            "philosophers asym" to table(reverseLast = true),
            "philosophers fair" to table(reverseLast = true, fairly = true),
        )
        for ((name, net) in nets) for (within in listOf(2_048, 4_096)) {
            assertEquals(before(net, within), net.deadTransitions(within), "$name at within=$within")
        }
    }

    // ── partial-order reduction ───────────────────────────────────────────────────────────

    private fun philosophers(n: Int, reverseLast: Boolean) = petri {
        conflict = PetriConflict.ORDER
        grid = cyc(1, 4)
        for (i in 0 until n) place("fork.$i", tokens = 1)
        for (i in 0 until n) {
            val left = i
            val right = (i + 1) % n
            val reversed = reverseLast && i == n - 1
            t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
            t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
            t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
        }
    }

    @Test
    fun `the reduced search reaches the same verdict as the full one on every net here`() {
        // The only verification a partial-order reduction can really be given: run both, and demand
        // they agree. A reduction that is unsound shows up here as a `false` where the full search
        // says `true`, and there is no amount of elegant argument that survives that.
        val nets = liveDocumentNets() +
            (2..9).map { "philosophers n=$it" to philosophers(it, reverseLast = false) } +
            (2..9).map { "asymmetric n=$it" to philosophers(it, reverseLast = true) } +
            listOf("buffer" to boundedBuffer(), "cliff" to cliff())

        for ((name, net) in nets) {
            val full = net.stateSpace()
            val reduced = net.stateSpace(reduction = PetriReduction.STUBBORN)
            assertEquals(full.canDeadlock, reduced.canDeadlock, "$name: the two searches disagree")
            assertEquals(
                full.deadlockTrace()?.length, reduced.deadlockTrace()?.length,
                "$name: the reduced counterexample is a different length",
            )
            reduced.deadlockTrace()?.let { assertNull(it.verify(), "$name: $it does not re-fire") }
            assertTrue(
                reduced.stateCount <= full.stateCount,
                "$name: the reduction made the graph bigger (${reduced.stateCount} > ${full.stateCount})",
            )
        }
    }

    @Test
    fun `the reduction turns the philosophers' exponential into a quadratic`() {
        // The full search grows like the companion Pell numbers, a(n) = 2a(n-1) + a(n-2), so about
        // 2.41 times per philosopher. Both sequences are pinned exactly: a reduction that quietly
        // stopped reducing, or a search that quietly stopped exploring, moves one of them.
        val full = (2..9).map { philosophers(it, reverseLast = false).stateSpace().stateCount }
        assertEquals(listOf(6, 14, 34, 82, 198, 478, 1154, 2786), full)
        for (i in 2 until full.size) {
            assertEquals(2 * full[i - 1] + full[i - 2], full[i], "the full growth is no longer Pell")
        }

        for (n in 2..12) {
            val reduced = philosophers(n, reverseLast = false).stateSpace(reduction = PetriReduction.STUBBORN)
            assertEquals(PetriSearchOutcome.COMPLETE, reduced.outcome)
            assertEquals(2 * n * n - 2 * n + 2, reduced.stateCount, "reduced size at n=$n")
        }

        val eleven = philosophers(11, reverseLast = false)
        val ratio = eleven.stateSpace().stateCount.toDouble() /
            eleven.stateSpace(reduction = PetriReduction.STUBBORN).stateCount
        assertTrue(ratio > 70.0, "expected better than a 70x reduction at eleven philosophers, got $ratio")
    }

    @Test
    fun `the reduction answers a table the full search has to give up on`() {
        // Twelve philosophers is past the wall: 20 000 markings is not enough to reach depth 12, so
        // the full search returns BUDGET and — correctly — refuses to say anything. The reduced
        // search finishes in 266 markings and produces the counterexample.
        val net = philosophers(12, reverseLast = false)

        val full = net.stateSpace()
        assertEquals(PetriSearchOutcome.BUDGET, full.outcome)
        assertNull(full.canDeadlock, "a truncated search must not answer")

        val reduced = net.stateSpace(reduction = PetriReduction.STUBBORN)
        assertEquals(PetriSearchOutcome.COMPLETE, reduced.outcome)
        assertEquals(266, reduced.stateCount)
        assertEquals(true, reduced.canDeadlock)
        val trace = assertNotNull(reduced.deadlockTrace())
        assertEquals(12, trace.length, "twelve philosophers, twelve grabs")
        assertNull(trace.verify())
    }

    @Test
    fun `a reduced graph refuses every property the theorem does not cover`() {
        val reduced = table(reverseLast = true).stateSpace(reduction = PetriReduction.STUBBORN)

        // Valmari's theorem is about terminal markings and nothing else, so this is all it may say.
        assertTrue(reduced.complete)
        assertFalse(reduced.exact, "a completed reduced search is still not the whole state space")
        assertEquals(false, reduced.canDeadlock, "…but deadlock-freedom is exactly what it proves")

        // Everything below would be a lower bound or a non-proof wearing the word "exact".
        assertNull(reduced.bounds)
        assertNull(reduced.bound("fork.0"))
        assertNull(reduced.isSafe)
        assertNull(reduced.isLive)
        assertNull(reduced.liveTransitions)
        assertNull(reduced.isReversible)
        assertNull(reduced.homeMarkings)
        assertNull(reduced.neverFires())
        assertNull(reduced.mutuallyExclusive("eating.0", "eating.1"), "no witness found is not a proof here")
        // …but a witness it *did* find is still real, because a positive is always sound: two
        // forks on the table at once is a marking the reduced search actually visited.
        assertEquals(false, reduced.mutuallyExclusive("fork.0", "fork.1"))
        assertNull(assertNotNull(reduced.mutualExclusionFailure("fork.0", "fork.1")).verify())
        assertNull(reduced.proves { it.size > 0 })
        assertTrue("deadlock is exact here, nothing else is" in reduced.report(), reduced.report())

        // The unreduced graph answers all of them, which is the trade being made.
        val full = table(reverseLast = true).stateSpace()
        assertTrue(full.exact)
        assertEquals(true, full.isLive)
        assertEquals(true, full.proves { marking -> (0 until 5).sumOf { marking.count("eating.$it") } <= 2 })
    }

    @Test
    fun `a colour-dependent net declines the reduction rather than guessing at it`() {
        // The closure reasons about arcs. A guard, an `enabledIf` or a colour-valued production is
        // an arbitrary predicate it cannot see, so the search silently falls back to exploring
        // everything — and records that it did, so `exact` stays true and nothing is lost.
        val song = petriSong("verse" to (pat("bd ~ sd ~") to 4), "chorus" to (pat("bd*4 hh*8") to 4))
        assertTrue(song.isColourDependent)
        val graph = song.stateSpace(reduction = PetriReduction.STUBBORN)
        assertEquals(PetriReduction.NONE, graph.reduction, "a coloured net must not be reduced")
        assertTrue(graph.exact, "…and so it is still exact")
        assertEquals(2, graph.stateCount)
    }
}
