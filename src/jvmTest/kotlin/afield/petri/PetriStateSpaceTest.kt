package afield.petri

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
}
