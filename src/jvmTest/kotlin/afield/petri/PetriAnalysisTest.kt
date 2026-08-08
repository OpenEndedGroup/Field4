package afield.petri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analysis in `PetriAnalysis.kt`.
 *
 * Every number here is computed, never remembered: the invariants come out of the incidence matrix
 * and are checked against what the engine *actually does* when the net runs, which is the only
 * test of an invariant worth writing. If the algebra and the engine ever disagree the two halves
 * of this file will disagree with each other, and that is the point.
 */
class PetriAnalysisTest {

    private fun ring(name: String, steps: Int) = petri {
        grid = cyc(1, steps)
        for (index in 0 until steps) place("$name$index", tokens = if (index == 0) 1 else 0)
        for (index in 0 until steps) {
            t("$name$index>", dur = cyc(1, steps)) { from("$name$index"); to("$name${(index + 1) % steps}") }
        }
    }

    // ── incidence ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the incidence matrix records net change, so a self-loop is zero`() {
        val net = petri {
            place("a", tokens = 1)
            t("spin") { from("a"); to("a") }
            t("drain") { from("a"); to("b") }
        }
        val matrix = net.incidence()
        val a = net.placeNames.indexOf("a")
        val b = net.placeNames.indexOf("b")
        assertEquals(0, matrix[a][net.transitionNames.indexOf("spin")], "in and out of the same place cancels")
        assertEquals(-1, matrix[a][net.transitionNames.indexOf("drain")])
        assertEquals(1, matrix[b][net.transitionNames.indexOf("drain")])
    }

    @Test
    fun `arc weights show up in the incidence matrix`() {
        val net = petri {
            place("src", tokens = 6)
            t("burst") { from("src", weight = 3); to("dst", weight = 2) }
        }
        assertEquals(-3, net.incidence()[net.placeNames.indexOf("src")][0])
        assertEquals(2, net.incidence()[net.placeNames.indexOf("dst")][0])
    }

    @Test
    fun `read and inhibitor arcs are invisible to the incidence matrix, and the net says so`() {
        val net = petri {
            place("clock", tokens = 1)
            place("a", tokens = 1)
            t("tick") { read("clock"); inhibit("blocked"); from("a"); to("b") }
        }
        assertEquals(0, net.incidence()[net.placeNames.indexOf("clock")][0], "a read arc moves nothing")
        assertTrue(net.usesTestArcs)
        assertTrue("read or inhibitor" in net.report(), "the report must admit the limitation")
    }

    // ── T-invariants ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a ring's one T-invariant fires every one of its transitions once`() {
        val invariants = ring("k", 8).tInvariants()
        assertEquals(1, invariants.size, "a ring is one loop and no others")
        assertEquals(
            (0 until 8).associate { "k$it>" to 1 },
            invariants.single(),
            "going all the way round means each step exactly once",
        )
    }

    @Test
    fun `two independent rings are two separate invariants, not one combined one`() {
        val two = petri {
            grid = cyc(1, 15)
            for (index in 0 until 5) place("a$index", tokens = if (index == 0) 1 else 0)
            for (index in 0 until 3) place("b$index", tokens = if (index == 0) 1 else 0)
            for (index in 0 until 5) t("a$index>") { from("a$index"); to("a${(index + 1) % 5}") }
            for (index in 0 until 3) t("b$index>") { from("b$index"); to("b${(index + 1) % 3}") }
        }
        val invariants = two.tInvariants()
        assertEquals(2, invariants.size, "independence is exactly the statement that they are separate loops")
        assertEquals(setOf(5, 3), invariants.map { it.size }.toSet())
    }

    @Test
    fun `a weighted loop's invariant carries the multiplicity the weights force`() {
        // "pump" moves one token at a time; "sip" moves three at once. Getting back to where you
        // started therefore takes three pumps to every sip, and nothing smaller works.
        val net = petri {
            place("a", tokens = 3)
            t("pump") { from("a"); to("b") }
            t("sip") { from("b", weight = 3); to("a", weight = 3) }
        }
        assertEquals(mapOf("pump" to 3, "sip" to 1), net.tInvariants().single())

        // And the engine, which knows nothing about the incidence matrix, runs exactly that.
        assertEquals(mapOf("pump" to 3, "sip" to 1), net.settle()!!.firingCounts)
        assertNull(net.checkLoopIsInvariant())
    }

    @Test
    fun `a net that never returns anywhere has no T-invariants`() {
        val net = petri {
            place("a", tokens = 1)
            t("once") { from("a"); to("b") }
        }
        assertTrue(net.tInvariants().isEmpty())
    }

    @Test
    fun `every T-invariant really does leave the marking alone`() {
        // The defining property, checked directly against the matrix rather than trusted.
        for (net in listOf(ring("k", 8), ring("h", 5), pingPongNet)) {
            val matrix = net.incidence()
            for (invariant in net.tInvariants()) {
                val counts = net.transitionNames.map { invariant[it] ?: 0 }
                for ((row, place) in net.placeNames.withIndex()) {
                    val change = matrix[row].indices.sumOf { matrix[row][it] * counts[it] }
                    assertEquals(0, change, "invariant $invariant changes '$place' by $change")
                }
            }
        }
    }

    // ── P-invariants ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a ring conserves exactly one thing - the token going round it`() {
        val invariants = ring("k", 6).pInvariants()
        assertEquals(1, invariants.size)
        assertEquals(
            (0 until 6).associate { "k$it" to 1 },
            invariants.single(),
            "the total across the ring never changes, which is what 'one token walking' means",
        )
    }

    @Test
    fun `every P-invariant really is conserved by every transition`() {
        val net = ring("k", 6)
        val matrix = net.incidence()
        for (invariant in net.pInvariants()) {
            val weights = net.placeNames.map { invariant[it] ?: 0 }
            for ((column, transition) in net.transitionNames.withIndex()) {
                val change = matrix.indices.sumOf { row -> weights[row] * matrix[row][column] }
                assertEquals(0, change, "'$transition' changes the invariant $invariant by $change")
            }
        }
    }

    // ── period, measured ──────────────────────────────────────────────────────────────────

    private val pingPongNet = petri {
        grid = cyc(1, 4)
        place("a", tokens = 1)
        t("there", dur = cyc(1, 4)) { from("a"); to("b") }
        t("back", dur = cyc(1, 4)) { from("b"); to("a") }
    }

    @Test
    fun `a two-place loop on a quarter grid has a half-cycle period`() {
        assertEquals(cyc(1, 2), pingPongNet.period())
    }

    @Test
    fun `an eight-step ring on a one-eighth grid has a period of exactly one cycle`() {
        assertEquals(cyc(1), ring("k", 8).period())
    }

    @Test
    fun `a net tells you its own bar length rather than being told`() {
        // Five against three on a fifteenth-note grid. Nothing in the source says "fifteen".
        val polyrhythm = petri {
            grid = cyc(1, 15)
            for (index in 0 until 5) place("a$index", tokens = if (index == 0) 1 else 0)
            for (index in 0 until 3) place("b$index", tokens = if (index == 0) 1 else 0)
            for (index in 0 until 5) t("a$index>") { from("a$index"); to("a${(index + 1) % 5}") }
            for (index in 0 until 3) t("b$index>") { from("b$index"); to("b${(index + 1) % 3}") }
        }
        assertEquals(cyc(1), polyrhythm.period(), "fifteen steps of a fifteenth is one cycle")
        assertEquals(15, polyrhythm.settle()!!.loopSteps, "and it takes fifteen steps to get there")
    }

    @Test
    fun `a run-in before the loop is reported separately from the loop`() {
        val net = petri {
            grid = cyc(1, 4)
            place("start", tokens = 1)
            place("a", tokens = 0)
            t("enter") { from("start"); to("a") }
            t("there") { from("a"); to("b") }
            t("back") { from("b"); to("a") }
        }
        val lasso = net.settle()!!
        assertTrue(lasso.tailSteps >= 1, "getting out of 'start' happens once and never again")
        assertEquals(2, lasso.loopSteps)
    }

    @Test
    fun `the loop the engine runs is the T-invariant the algebra predicts`() {
        for (net in listOf(pingPongNet, ring("k", 8), ring("h", 5))) {
            assertNull(net.checkLoopIsInvariant(), "engine and algebra must agree about ${net.transitions.size} transitions")
        }
    }

    @Test
    fun `a TIMED net's period is in real cycles, not steps`() {
        val net = petri {
            timing = PetriTiming.TIMED
            place("a", tokens = 1)
            t("hold", dur = cyc(3)) { from("a"); to("b") }
            t("release", dur = cyc(1)) { from("b"); to("a") }
        }
        assertEquals(cyc(4), net.period(), "three cycles held plus one released")
    }

    // ── deadlock ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a net that runs out of tokens is reported as deadlocking, with the marking it stops in`() {
        val net = petri {
            place("a", tokens = 2)
            t("drain") { from("a"); to("b") }
        }
        assertTrue(net.willDeadlock())
        assertEquals(2, net.deadlockMarking()!!.count("b"))
    }

    @Test
    fun `a looping net does not deadlock`() {
        assertFalse(pingPongNet.willDeadlock())
        assertNull(pingPongNet.deadlockMarking())
    }

    @Test
    fun `an inhibitor arc that never releases starves the transition behind it`() {
        val net = petri {
            place("armed", tokens = 1)
            place("src", tokens = 4)
            t("blocked") { inhibit("armed"); from("src"); to("dst") }
        }
        assertTrue(net.willDeadlock(), "nothing can ever empty 'armed', so 'blocked' never goes")
        assertEquals(listOf("blocked"), net.deadTransitions())
    }

    @Test
    fun `a transition wired to the wrong place is found before it is heard`() {
        val net = petri {
            grid = cyc(1, 4)
            place("a", tokens = 1)
            t("there") { from("a"); to("b") }
            t("back") { from("b"); to("a") }
            t("typo") { from("nowhere"); to("a") }
        }
        assertEquals(listOf("typo"), net.deadTransitions(), "the voice that silently does nothing")
    }

    @Test
    fun `every transition of a healthy ring fires`() {
        assertTrue(ring("k", 8).deadTransitions().isEmpty())
    }

    // ── the report ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the report states the period, the invariants and the trouble`() {
        val healthy = ring("k", 8).report()
        assertTrue("period" in healthy, healthy)
        assertTrue("T-invariants (1)" in healthy, healthy)
        assertFalse("DEADLOCK" in healthy, healthy)

        val broken = petri {
            place("a", tokens = 1)
            t("once") { from("a"); to("b") }
        }.report()
        assertTrue("DEADLOCK" in broken, broken)
        assertTrue("no T-invariants" in broken, broken)
    }

    @Test
    fun `the incidence table names its rows and columns`() {
        val table = pingPongNet.incidenceTable()
        assertTrue("there" in table && "back" in table)
        assertTrue(table.lines().any { it.startsWith("a") })
    }

    // ── the analysis does not hang on nets that have no answer ────────────────────────────

    @Test
    fun `an unbounded net has no period and says so instead of running forever`() {
        val net = petri {
            t("spawn") { to("pool") }
        }
        assertNull(net.period(maxSteps = 64), "a place that only ever grows never returns to a marking")
        assertFalse(net.willDeadlock(within = 64))
    }

    @Test
    fun `a net with no transitions at all is handled rather than crashing`() {
        val net = petri { place("lonely", tokens = 1) }
        assertTrue(net.tInvariants().isEmpty())
        assertNotNull(net.report())
    }
}
