package afield.four.physics

import afield.stdlib.color
import afield.stdlib.pointed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [debugLines]: the counts it draws are the ones a reader would count off the picture, which
 * is the only property a debug view really owes anybody. A cloth is the fixture because its wiring
 * is symmetric — every interior edge is owned twice — so it pins down the deduplication rule.
 */
class SimdVerletDebugLinesTest {

    /** Nodes in the edge FLine: one moveTo + one lineTo per drawn segment. */
    private fun edgeCount(sim: SimdVerlet): Int =
        sim.debugLines(points = false).sumOf { it.nodes.size } / 2

    @Test
    fun drawsEachSymmetricConstraintOnce() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        sim.addCloth(width = 4, height = 4)

        // A 4x4 grid has 4*3 horizontal + 4*3 vertical edges. Every one of them is wired twice
        // (i -> j and j -> i), so a view that forgot to deduplicate would report 48.
        assertEquals(24, edgeCount(sim))
    }

    @Test
    fun drawsAOneWayConstraintFromItsOwner() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val cloth = sim.addCloth(width = 3, height = 3)
        val before = edgeCount(sim)

        // Break the symmetry: keep 4 -> 5 and drop 5 -> 4. The edge must survive — a dedup rule
        // that just skipped `j < i` would lose it, and losing a constraint is exactly the bug this
        // view exists to show.
        val c = sim.constraints4!!
        val a = cloth[1, 1]
        val b = cloth[2, 1]
        for (slot in 0 until 4) {
            val n = listOf(c.n0[b], c.n1[b], c.n2[b], c.n3[b])[slot]
            if (n == a) c.setSlot(b, slot, -1, 0f, 0f)
        }

        assertEquals(before, edgeCount(sim))
    }

    @Test
    fun unusedSlotsAndZeroStiffnessDrawNothing() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        sim.addCloth(width = 4, height = 4, stiffness = 0f)
        assertEquals(0, edgeCount(sim), "zero-stiffness slots are not live constraints")

        val loose = SimdVerlet(SimdVerlet.State3.empty())
        repeat(5) { loose.addParticle(x0 = it.toFloat()) }
        assertEquals(0, edgeCount(loose), "a simulation with no constraint set has no edges")
    }

    @Test
    fun pinnedParticlesAreTheirOwnLine() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        sim.addCloth(width = 5, height = 3)
        val lines = sim.debugLines(edges = false)

        assertEquals(2, lines.size, "free dots and pinned dots")
        assertTrue(lines.all { it.pointed })
        // 15 particles, of which the pinned top row is 5.
        assertEquals(10, lines[0].nodes.size)
        assertEquals(5, lines[1].nodes.size)
        assertFalse(lines[0].color == lines[1].color, "pinned dots need their own colour")
    }

    @Test
    fun velocityWhiskersAreOptionalAndOnlyForMovingParticles() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val cloth = sim.addCloth(width = 4, height = 4)

        assertEquals(0, sim.debugLines(edges = false, points = false).size, "no whiskers by default")

        // Nothing has moved yet, so even asking for whiskers draws none.
        assertEquals(
            0,
            sim.debugLines(edges = false, points = false, velocityScale = 1f).size,
            "a state at rest has no velocity to draw"
        )

        repeat(5) { cloth.step(gravity = -9.8f) }
        val whiskers = sim.debugLines(edges = false, points = false, velocityScale = 1f)
        assertEquals(1, whiskers.size)
        // One whisker per moving particle: the pinned top row never moves.
        assertEquals(2 * (16 - 4), whiskers[0].nodes.size)
    }

    @Test
    fun particleRangeSelectsOneClothOutOfSeveral() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val a = sim.addCloth(width = 4, height = 4)
        val b = sim.addCloth(width = 3, height = 3)

        val justB = sim.debugLines(particles = b.base until b.end, points = false)
        assertEquals(12, justB.sumOf { it.nodes.size } / 2, "3x3: 3*2 + 3*2 edges")

        val everything = sim.debugLines(points = false)
        assertEquals(24 + 12, everything.sumOf { it.nodes.size } / 2)

        // An out-of-range request is clamped rather than throwing.
        assertEquals(emptyList(), sim.debugLines(particles = 1000 until 2000))
        assertTrue(sim.debugLines(particles = -5 until a.end).isNotEmpty())
    }
}
