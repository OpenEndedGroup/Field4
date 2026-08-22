package afield.four.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [Cloth] — the grid wiring it puts into a [SimdVerlet.DirectedConstraints4], the
 * curvature bulk force it registers, and the invariants that let it share one simulation with
 * other particles (contiguous indices offset by `base`, a constraint set that grows with the
 * state, nothing written outside its own range).
 *
 * The cloth is deliberately added to a *non-empty* simulation in most cases: `base == 0` would
 * hide every offset bug in the wiring.
 */
class ClothTest {

    private fun emptyState() = SimdVerlet.State3(
        x = FloatArray(0), y = FloatArray(0), z = FloatArray(0),
        px = FloatArray(0), py = FloatArray(0), pz = FloatArray(0),
        ax = FloatArray(0), ay = FloatArray(0), az = FloatArray(0),
        invMass = FloatArray(0)
    )

    /** A simulation with [preexisting] unrelated particles already in it. */
    private fun simWith(preexisting: Int): SimdVerlet {
        val sim = SimdVerlet(emptyState())
        repeat(preexisting) { sim.addParticle(x0 = -10f - it, y0 = 0f, z0 = 0f) }
        return sim
    }

    private fun slotsOf(c: SimdVerlet.DirectedConstraints4, i: Int): Set<Int> =
        setOf(c.n0[i], c.n1[i], c.n2[i], c.n3[i]).filter { it >= 0 }.toSet()

    @Test
    fun appendsContiguousParticlesAfterWhateverWasAlreadyThere() {
        val sim = simWith(3)
        val cloth = sim.addCloth(width = 4, height = 5, spacing = 0.25f)

        assertEquals(3, cloth.base)
        assertEquals(20, cloth.count)
        assertEquals(23, sim.size)
        assertEquals(23, cloth.end)

        // Row-major, contiguous, and addressable by grid coordinate.
        for (row in 0 until 5) {
            for (col in 0 until 4) {
                assertEquals(3 + row * 4 + col, cloth[col, row], "index($col, $row)")
            }
        }
        assertTrue(cloth.base in cloth)
        assertFalse(cloth.base - 1 in cloth)

        // Default layout: flat grid in z = 0, spacing apart.
        val p = cloth.position(2, 3)
        assertEquals(2 * 0.25f, p.x, 1e-6f)
        assertEquals(3 * 0.25f, p.y, 1e-6f)
        assertEquals(0f, p.z, 1e-6f)
    }

    @Test
    fun wiresEveryAxisNeighborAndLeavesEverythingElseAlone() {
        val sim = simWith(2)
        val cloth = sim.addCloth(width = 4, height = 4, spacing = 0.5f, stiffness = 0.7f)
        val c = sim.constraints4!!

        // The particles that were there before the cloth keep an empty constraint row.
        for (i in 0 until cloth.base) {
            assertEquals(emptySet(), slotsOf(c, i), "pre-existing particle $i")
        }

        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val i = cloth[col, row]
                val expected = buildSet {
                    if (col > 0) add(cloth[col - 1, row])
                    if (col < 3) add(cloth[col + 1, row])
                    if (row > 0) add(cloth[col, row - 1])
                    if (row < 3) add(cloth[col, row + 1])
                }
                assertEquals(expected, slotsOf(c, i), "neighbors of ($col, $row)")
            }
        }

        // Interior particles use all four slots; a corner uses two and leaves the rest unused.
        assertEquals(4, slotsOf(c, cloth[1, 1]).size)
        assertEquals(2, slotsOf(c, cloth[0, 0]).size)

        // Rest length and stiffness landed on every used slot.
        val i = cloth[1, 1]
        val rests = listOf(c.r0[i], c.r1[i], c.r2[i], c.r3[i])
        val stiffs = listOf(c.s0[i], c.s1[i], c.s2[i], c.s3[i])
        assertTrue(rests.all { abs(it - 0.5f) < 1e-6f }, "rest lengths: $rests")
        assertTrue(stiffs.all { abs(it - 0.7f) < 1e-6f }, "stiffnesses: $stiffs")
    }

    @Test
    fun restLengthCanBeMeasuredFromANonUniformLayout() {
        val sim = SimdVerlet(emptyState())
        // Columns twice as far apart as rows, so the two axes must not share one rest length.
        val cloth = sim.addCloth(
            width = 3, height = 3, spacing = 1f,
            position = { col, row -> afield.stdlib.vec(col * 2f, row * 1f, 0f) },
            restLengthFromLayout = true
        )
        val c = sim.constraints4!!
        val i = cloth[1, 1]

        val horizontal = mutableListOf<Float>()
        val vertical = mutableListOf<Float>()
        for (slot in 0 until 4) {
            val n = listOf(c.n0[i], c.n1[i], c.n2[i], c.n3[i])[slot]
            val r = listOf(c.r0[i], c.r1[i], c.r2[i], c.r3[i])[slot]
            if (abs(n - i) == 1) horizontal += r else vertical += r
        }
        assertTrue(horizontal.all { abs(it - 2f) < 1e-5f }, "horizontal rests: $horizontal")
        assertTrue(vertical.all { abs(it - 1f) < 1e-5f }, "vertical rests: $vertical")
    }

    @Test
    fun pinsTheTopRowByDefaultAndTakesMassIntoAccount() {
        val sim = simWith(1)
        val cloth = sim.addCloth(width = 3, height = 3, mass = 4f)
        val invMass = sim.state.invMass!!

        for (col in 0 until 3) {
            assertTrue(cloth.isPinned(col, 2), "top row ($col, 2) should be pinned")
            assertEquals(0f, invMass[cloth[col, 2]])
            assertFalse(cloth.isPinned(col, 0), "bottom row ($col, 0) should be free")
            assertEquals(0.25f, invMass[cloth[col, 0]], 1e-6f)
        }

        cloth.pin(0, 0)
        assertTrue(cloth.isPinned(0, 0))
        cloth.pin(0, 0, pinned = false)
        assertEquals(0.25f, invMass[cloth[0, 0]], 1e-6f)
    }

    @Test
    fun pinnedParticlesDoNotMoveUnderGravity() {
        val sim = SimdVerlet(emptyState())
        val cloth = sim.addCloth(width = 4, height = 4)
        val top = cloth[0, 3]
        val startY = sim.state.y[top]

        repeat(30) { cloth.step(gravity = -9.8f) }

        assertEquals(startY, sim.state.y[top], 1e-5f)
        assertTrue(sim.state.y[cloth[0, 0]] < startY, "the free corner should have fallen")
    }

    @Test
    fun curvatureIsRegisteredAsOneBulkForceAndMatchesItsDefinition() {
        val sim = simWith(2)
        val cloth = sim.addCloth(width = 3, height = 3, curvatureStrength = 0.5f)

        assertEquals(setOf("${cloth.name}.curvature"), sim.bulkForces.keys)

        // Push the one interior node out of plane; curvature should pull it back toward the mean
        // of its four axis neighbors (which are all at z = 0), i.e. by -strength * z.
        val i = cloth[1, 1]
        val state = sim.state
        state.z[i] = 2f
        SimdVerlet.zero3(state)
        cloth.accumulateCurvature(state)

        assertEquals(-1f, state.az[i], 1e-6f)
        assertEquals(0f, state.ax[i], 1e-6f)
        assertEquals(0f, state.ay[i], 1e-6f)

        // Edge nodes and the particles outside the cloth get nothing from the pass.
        assertEquals(0f, state.az[cloth[0, 1]])
        assertEquals(0f, state.az[0])
    }

    @Test
    fun curvatureAloneFlattensTheSheet() {
        // stiffness = 0 switches the distance solve off, so this measures the curvature force and
        // nothing else: a node pushed out of plane must walk monotonically back toward it.
        fun run(strength: Float): List<Float> {
            val sim = SimdVerlet(emptyState())
            val cloth = sim.addCloth(width = 7, height = 7, stiffness = 0f, curvatureStrength = strength)
            val i = cloth[3, 3]
            sim.state.z[i] = 1f
            sim.state.pz[i] = 1f
            return (0 until 20).map { cloth.step(); sim.state.z[i] }
        }

        val flattening = run(5f)
        assertTrue(flattening.last() < 0.99f, "curvature should have pulled z down: ${flattening.last()}")
        assertTrue(flattening.last() > 0f, "and should not have overshot: ${flattening.last()}")
        assertTrue(
            flattening.zipWithNext().all { (a, b) -> b < a },
            "the descent should be monotonic: $flattening"
        )

        // Same sheet with the force switched off: nothing moves at all.
        assertTrue(run(0f).all { it == 1f }, "without curvature the node should stay put")
    }

    @Test
    fun curvatureStrengthOfZeroRegistersNothingButCanBeTurnedOnLater() {
        val sim = SimdVerlet(emptyState())
        val cloth = sim.addCloth(width = 4, height = 4, curvatureStrength = 0f)
        assertTrue(sim.bulkForces.isEmpty(), "expected no bulk force, got ${sim.bulkForces.keys}")

        cloth.enableCurvature(0.5f)
        assertEquals(setOf("${cloth.name}.curvature"), sim.bulkForces.keys)
        assertEquals(0.5f, cloth.curvatureStrength)

        cloth.disableCurvature()
        assertTrue(sim.bulkForces.isEmpty())
    }

    @Test
    fun linesFollowTheGrid() {
        val sim = simWith(2)
        val cloth = sim.addCloth(width = 5, height = 3, spacing = 0.5f)
        val lines = cloth.lines()

        assertEquals(8, lines.size)
        for (row in 0 until 3) assertEquals(5, lines[row].nodes.size, "row line $row")
        for (col in 0 until 5) assertEquals(3, lines[3 + col].nodes.size, "column line $col")

        // First node of the first row line is the cloth's first particle, not particle 0.
        val first = lines[0].nodes[0].to
        assertEquals(sim.state.x[cloth.base], first.x, 1e-6f)
        assertEquals(sim.state.y[cloth.base], first.y, 1e-6f)
    }

    @Test
    fun stepGrowsTheConstraintSetWhenTheSimulationGrows() {
        val sim = SimdVerlet(emptyState())
        val cloth = sim.addCloth(width = 4, height = 4)
        val set = sim.constraints4!!

        repeat(200) { sim.addParticle(x0 = it.toFloat()) }
        cloth.step()

        assertTrue(set.size >= sim.size, "constraint set ${set.size} must cover ${sim.size} particles")
        // Grown in place: everyone holding the set (here sim.constraints4) still sees the same object.
        assertTrue(set === sim.constraints4)
        assertTrue(set === cloth.constraints)
        // The grid survived the growth.
        assertEquals(4, slotsOf(set, cloth[1, 1]).size)
        // And the particles added afterwards are unconstrained.
        assertEquals(emptySet(), slotsOf(set, sim.size - 1))
    }

    @Test
    fun twoClothsShareOneConstraintSetAndOneSimulation() {
        val sim = SimdVerlet(emptyState())
        val a = sim.addCloth(width = 3, height = 3)
        val b = sim.addCloth(width = 3, height = 3, position = { col, row -> afield.stdlib.vec(col, row + 10, 0) })

        assertTrue(a.constraints === b.constraints)
        assertEquals(0, a.base)
        assertEquals(9, b.base)
        assertEquals(2, sim.bulkForces.size, "each cloth registers its own curvature: ${sim.bulkForces.keys}")

        val c = a.constraints
        assertEquals(4, slotsOf(c, a[1, 1]).size)
        assertEquals(4, slotsOf(c, b[1, 1]).size)
        // No slot crosses from one cloth into the other.
        assertTrue(slotsOf(c, b[1, 1]).all { it in b }, "b's neighbors: ${slotsOf(c, b[1, 1])}")
        assertTrue(slotsOf(c, a[1, 1]).all { it in a }, "a's neighbors: ${slotsOf(c, a[1, 1])}")

        b.step()
        assertTrue(sim.state.x.all { it.isFinite() })
    }

    @Test
    fun grabPullsAParticleTowardTheTargetAndReleaseLetsGo() {
        val sim = SimdVerlet(emptyState())
        val cloth = sim.addCloth(width = 5, height = 5, spacing = 0.5f)

        val corner = cloth.position(0, 0)
        assertEquals(-1, cloth.grabNearest(corner.x + 10f, corner.y, 0f), "nothing is within reach")
        assertEquals(-1, cloth.grabbedIndex)

        val grabbed = cloth.grabNearest(corner.x, corner.y, 0f)
        assertEquals(cloth[0, 0], grabbed)
        assertEquals(grabbed, cloth.grabbedIndex)

        assertTrue(cloth.moveGrabbed(corner.x, corner.y, 1f))
        repeat(60) { cloth.step() }
        assertTrue(sim.state.z[grabbed] > 0.5f, "grabbed particle should have been pulled to z=1: ${sim.state.z[grabbed]}")

        assertTrue(cloth.releaseGrabbed())
        assertEquals(-1, cloth.grabbedIndex)
        assertFalse(cloth.releaseGrabbed())
        assertFalse(cloth.moveGrabbed(0f, 0f, 0f))
    }

    @Test
    fun teleportMovesANodeWithoutThrowingIt() {
        val sim = SimdVerlet(emptyState())
        val cloth = sim.addCloth(width = 3, height = 3)
        val i = cloth[0, 0]

        cloth.teleport(0, 0, afield.stdlib.vec(5, 6, 7))
        assertEquals(5f, sim.state.x[i], 1e-6f)
        // Previous position moved with it, so the Verlet velocity is unchanged (still zero).
        assertEquals(sim.state.x[i] - sim.state.px[i], 0f, 1e-6f)

        cloth.teleport(0, 0, afield.stdlib.vec(6, 6, 7), keepVelocity = true)
        assertEquals(1f, sim.state.x[i] - sim.state.px[i], 1e-6f)
    }

    @Test
    fun removeDetachesTheClothFromTheSimulation() {
        val sim = SimdVerlet(emptyState())
        val cloth = sim.addCloth(width = 3, height = 3)
        val set = sim.constraints4!!

        cloth.grabNearest(cloth.position(0, 0).x, cloth.position(0, 0).y)
        cloth.remove()

        assertTrue(sim.bulkForces.isEmpty())
        assertEquals(-1, cloth.grabbedIndex)
        for (i in cloth.base until cloth.end) {
            assertEquals(emptySet(), slotsOf(set, i), "slots of $i after remove()")
        }
        // The particles are still there; remove() deliberately doesn't renumber the simulation.
        assertEquals(9, sim.size)
    }
}
