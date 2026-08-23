package afield.four.physics

import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [Strand] — the rope, the two constraint slots it deliberately leaves free, and what
 * [Strand.attachTo] spends them on.
 *
 * Like [ClothTest], every strand here goes into a *non-empty* simulation: `base == 0` hides every
 * offset bug in the wiring.
 */
class StrandTest {

    private fun simWith(preexisting: Int): SimdVerlet {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        repeat(preexisting) { sim.addParticle(x0 = -30f - it) }
        return sim
    }

    private fun slotsOf(c: SimdVerlet.DirectedConstraints4, i: Int): List<Int> =
        listOf(c.n0[i], c.n1[i], c.n2[i], c.n3[i])

    private fun liveSlots(c: SimdVerlet.DirectedConstraints4, i: Int): Set<Int> =
        listOf(c.n0[i] to c.s0[i], c.n1[i] to c.s1[i], c.n2[i] to c.s2[i], c.n3[i] to c.s3[i])
            .filter { it.first >= 0 && it.second != 0f }.map { it.first }.toSet()

    // ------------------------------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------------------------------

    @Test
    fun chainsItsNodesAndLeavesTwoSlotsFreeOnEveryOne() {
        val sim = simWith(5)
        val rope = sim.addStrand(count = 8, spacing = 0.25f)
        val c = sim.constraints4!!

        assertEquals(5, rope.base)
        assertEquals(13, rope.end)
        assertEquals(13, sim.size)

        for (n in 0 until 8) {
            val i = rope[n]
            val want = buildSet {
                if (n < 7) add(i + 1)
                if (n > 0) add(i - 1)
            }
            assertEquals(want, liveSlots(c, i), "node $n")
            // Slots 2 and 3 must be untouched -- attachTo's whole budget.
            assertEquals(-1, c.n2[i], "node $n slot 2 must stay free")
            assertEquals(-1, c.n3[i], "node $n slot 3 must stay free")
        }
        // Nothing outside the rope was written.
        for (i in 0 until 5) assertTrue(liveSlots(c, i).isEmpty(), "particle $i must be untouched")
        assertEquals(7, sim.liveEdgeCount(), "seven segments in an eight-node rope")
    }

    @Test
    fun aStrandAndAClothShareOneConstraintSet() {
        val sim = simWith(2)
        val cloth = sim.addCloth(width = 4, height = 4, spacing = 0.3f, curvatureStrength = 0f)
        val rope = sim.addStrand(count = 6, spacing = 0.3f)
        assertTrue(cloth.constraints === rope.constraints, "one set per simulation, not per primitive")
        assertTrue(rope.constraints.size >= sim.size, "and it grew to cover both")
        // The cloth's edges plus the rope's, and nothing shared.
        assertEquals(4 * 3 + 4 * 3 + 5, sim.liveEdgeCount())
    }

    @Test
    fun restLengthsCanBeMeasuredFromANonUniformLayout() {
        val sim = simWith(1)
        val rope = sim.addStrand(
            count = 5, spacing = 1f, restLengthFromLayout = true,
            position = { n -> vec(n * n * 0.1f, 0f, 0f) }
        )
        val c = sim.constraints4!!
        // Segment n -> n+1 is ((n+1)^2 - n^2) * 0.1 = (2n+1) * 0.1.
        for (n in 0 until 4) {
            assertEquals((2 * n + 1) * 0.1f, c.r0[rope[n]], 1e-5f, "segment $n")
        }
    }

    // ------------------------------------------------------------------------------------------
    // It behaves like a rope
    // ------------------------------------------------------------------------------------------

    @Test
    fun aPinnedRopeHangsInsteadOfFalling() {
        fun run(withConstraints: Boolean): Pair<Float, Float> {
            val sim = simWith(0)
            // A modest load on purpose: see aChainConcentratesLoadAndThatSetsAMaximumRopeLength
            // for why a long heavy rope in this solver is elastic whatever the stiffness.
            val rope = sim.addStrand(count = 12, spacing = 0.25f, stiffness = 150f)
            rope.pin(0)
            repeat(400) {
                if (withConstraints) rope.step(dt = 1f / 60f, damping = 0.99f, gravity = 3f, iterations = 16)
                else sim.step(1f / 60f, 0.99f, 3f, 0, null as SimdVerlet.DirectedConstraints4?)
            }
            val tip = rope.position(11)
            val anchor = rope.position(0)
            val span = sqrt(
                (tip.x - anchor.x) * (tip.x - anchor.x) + (tip.y - anchor.y) * (tip.y - anchor.y) +
                    (tip.z - anchor.z) * (tip.z - anchor.z)
            )
            return span to tip.y
        }

        val (freeSpan, freeY) = run(withConstraints = false)
        assertTrue(freeY > 20f, "premise: with no constraints the free nodes fall away, tip y $freeY")
        assertTrue(freeSpan > 20f, "premise: and the rope stops being a rope, span $freeSpan")

        val (span, tipY) = run(withConstraints = true)
        val nominal = 11 * 0.25f
        println("12-node rope, spacing 0.25, g = 3 (nominal reach %.3f): hung span %.3f, tip y %.3f".format(nominal, span, tipY))
        assertTrue(span.isFinite() && span < nominal * 1.15f, "a rope may not stretch much past its length, $span vs $nominal")
        assertTrue(span > nominal * 0.85f, "and hanging straight down it should be near it, $span vs $nominal")
        assertTrue(abs(tipY - span) < nominal * 0.05f, "and it hangs downward, tip y $tipY vs span $span")
    }

    /**
     * How long a rope this solver can actually hold — the measurement the test above needed before
     * it could choose a length, and the one thing about `Strand` a reader has to know before
     * reaching for a hundred nodes.
     *
     * A chain is far more demanding than a sheet, and for a structural reason rather than a tuning
     * one: in a cloth the load spreads across a whole pinned row, while in a rope *every* segment
     * above a node carries everything below it, so the top segment of an `n`-node rope carries
     * `(n-1)·g` and its strain is `(n-1)·g / (stiffness · iterations)`. The stability ceiling on
     * that product is the same `k·dt²/m` one the cloth has, so the answer is a length rather than
     * a setting: past a few dozen nodes at `dt = 1/60` a hanging rope is visibly elastic and there
     * is no stiffness that fixes it.
     */
    @Test
    fun aChainConcentratesLoadAndThatSetsAMaximumRopeLength() {
        class Row(val nodes: Int, val kEffective: Float, val strain: Float, val predicted: Float)

        val rows = ArrayList<Row>()
        for ((nodes, stiffness, iterations) in listOf(
            Triple(16, 100f, 16), Triple(32, 100f, 16), Triple(64, 100f, 16),
            Triple(64, 150f, 16), Triple(64, 150f, 24)
        )) {
            val sim = simWith(0)
            val rope = sim.addStrand(count = nodes, spacing = 0.12f, stiffness = stiffness)
            rope.pin(0)
            repeat(600) { rope.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = iterations) }
            val kEff = stiffness * iterations
            rows += Row(nodes, kEff, sim.peakStrain(), (nodes - 1) * 9.8f / (kEff * 0.12f))
        }

        println("rope hanging from one end, spacing 0.12, mass 1, dt = 1/60, 600 frames")
        println("  nodes   stiffness x iters   peak strain   (n-1)*g / (k*iters*spacing)")
        for (r in rows) {
            println("  %5d   %17.0f   %11s   %19.3f".format(
                r.nodes, r.kEffective, if (r.strain.isNaN()) "diverged" else "%.3f".format(r.strain), r.predicted
            ))
        }

        // The first four settings are inside the stability band and the model has to hold there.
        for (r in rows.take(4)) {
            assertTrue(r.strain.isFinite(), "${r.nodes} nodes at k*iters ${r.kEffective} must not diverge")
            assertTrue(
                r.strain > r.predicted * 0.7f && r.strain < r.predicted * 1.4f,
                "${r.nodes} nodes: measured strain ${r.strain} must be within 0.7x..1.4x of the " +
                    "predicted ${r.predicted}, or the load model in the KDoc is wrong"
            )
        }
        // The fifth is past the ceiling, and that is the point of including it: the setting that
        // would make a 64-node rope inextensible is not a setting this integrator has.
        val over = rows.last()
        assertTrue(
            !over.strain.isFinite() || over.strain > over.predicted * 5f,
            "k*iters ${over.kEffective} must be past the stability ceiling, got ${over.strain} " +
                "against a predicted ${over.predicted}"
        )
        // Length is the binding constraint, not stiffness: doubling and quadrupling the nodes at a
        // fixed setting multiplies the strain, and the settings that would undo it are past the
        // stability ceiling.
        assertTrue(rows[1].strain > rows[0].strain * 1.7f, "32 nodes must stretch far more than 16: ${rows.map { it.strain }}")
        assertTrue(rows[2].strain > rows[1].strain * 1.7f, "and 64 more than 32: ${rows.map { it.strain }}")
        assertTrue(rows[2].strain > 1f, "64 nodes at 100 x 16 must be hopeless, ${rows[2].strain}")
        // More of the product does help, proportionally -- it is just not available without limit.
        assertTrue(rows[3].strain < rows[2].strain * 0.85f, "1.5x the stiffness must help: ${rows.map { it.strain }}")
    }

    /**
     * Bending: how tightly the rope can fold — and the two negative results behind that phrasing.
     *
     * `bendStiffness` was a *force* first, on the same `stiffness * iterations` scale as the rest
     * of the solver, exactly like [Cloth.curvatureStrength]. The sweep that killed it is worth
     * keeping: tip droop on a clamped cantilever against bend strength, `dt = 1/60` —
     *
     *     bend      0     300    1200    2000    2600    3000    3400
     *     6 nodes   0.602 0.602  0.602   0.602   0.602   0.602   0.601
     *     8 nodes   0.904 0.904  0.904   0.903   0.903   flails  NaN
     *     12 nodes  1.511 1.511  1.509   1.508   flails  NaN     NaN
     *
     * Three significant figures of nothing up to 2000, then the explicit integrator loses it. The
     * "flails" entries hold the tip up at 112 and 124 degrees of joint angle, which is thrashing
     * rather than stiffness. Same trap as a penalty collider, arriving by a different road, and
     * fixed the same way: as a projection.
     *
     * The second negative result is that a *cantilever* is the wrong test for bending at all. A
     * rope hanging under gravity is already straight, so there is nothing for a curvature
     * constraint to correct, and the projection moves a 20-node clamped tip by 0.5 % across its
     * whole range. What bending controls is how tightly the rope can *fold*, which needs a rope in
     * compression — so this measures a 50-node rope heaped on a floor between two anchors 1.2
     * apart.
     */
    @Test
    fun bendStiffnessControlsHowTightlyTheRopeFolds() {
        val rows = ArrayList<Triple<Float, Float, Float>>()
        for (bend in listOf(0f, 0.2f, 0.5f, 1.0f)) {
            val sim = simWith(0)
            val rope = sim.addStrand(
                count = 50, spacing = 0.1f, stiffness = 150f, bendStiffness = bend,
                position = { n ->
                    val f = n / 49f
                    vec(f * 1.2f, 1.4f * kotlin.math.sin(f * 3.14159f), 0.02f * kotlin.math.sin(f * 25f))
                }
            )
            rope.pin(0)
            rope.pin(49)
            sim.addColliders(PlaneCollider(0f, -1f, 0f, offset = -1.6f).also { it.friction = 0.6f })
            repeat(900) { rope.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16) }
            var maxKink = 0f
            for (n in 1 until 49) {
                val a = rope.position(n - 1)
                val b = rope.position(n)
                val c = rope.position(n + 1)
                val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
                val vx = c.x - b.x; val vy = c.y - b.y; val vz = c.z - b.z
                val lu = sqrt(ux * ux + uy * uy + uz * uz)
                val lv = sqrt(vx * vx + vy * vy + vz * vz)
                if (lu < 1e-9f || lv < 1e-9f) continue
                val cs = ((ux * vx + uy * vy + uz * vz) / (lu * lv)).coerceIn(-1f, 1f)
                val ang = Math.toDegrees(kotlin.math.acos(cs).toDouble()).toFloat()
                if (ang > maxKink) maxKink = ang
            }
            rows += Triple(bend, rope.meanKinkDegrees(), maxKink)
        }
        println("50-node rope (4.9 long) heaped on a floor between anchors 1.2 apart, 150 x 16, 900 frames")
        println("  bendStiffness   mean joint angle (deg)   sharpest joint (deg)")
        for ((bend, mean, worst) in rows) println("  %13.2f   %21.2f   %20.2f".format(bend, mean, worst))

        for ((bend, mean, worst) in rows) {
            assertTrue(mean.isFinite() && worst.isFinite(), "bend $bend must not diverge -- a projection has no ceiling")
            assertTrue(worst <= 180.01f, "an angle over 180 degrees is a bug, bend $bend gave $worst")
        }
        assertTrue(rows.first().second > 30f, "premise: a limp rope in compression must fold hard, ${rows.first().second} deg")
        for (n in 1 until rows.size) {
            assertTrue(
                rows[n].second < rows[n - 1].second,
                "more bend stiffness must fold less tightly: ${rows.map { it.second }}"
            )
        }
        assertTrue(rows.last().second < rows.first().second * 0.3f, "1.0 must be a hose: ${rows.map { it.second }}")
        assertTrue(rows.last().third < rows.first().third * 0.7f, "and have no sharp corner left: ${rows.map { it.third }}")
    }

    @Test
    fun aRopeCollidesLikeAnythingElse() {
        val sim = simWith(0)
        val rope = sim.addStrand(
            count = 36, spacing = 0.1f, stiffness = 60f,
            position = { n -> vec(-1.75f + n * 0.1f, -1.2f, 0f) }
        )
        rope.pin(0)
        rope.pin(35)
        val capsule = CapsuleCollider(-0.7f, 0.4f, 0f, 0.7f, 0.4f, 0f, radius = 0.35f)
            .also { it.friction = 0.5f }
        sim.addColliders(capsule)

        var deepest = -10f
        repeat(500) {
            rope.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16)
            for (n in 0 until 36) {
                val p = rope.position(n)
                // Distance to the capsule's segment (which is axis-aligned in x here).
                val cx = p.x.coerceIn(-0.7f, 0.7f)
                val d = sqrt((p.x - cx) * (p.x - cx) + (p.y - 0.4f) * (p.y - 0.4f) + p.z * p.z)
                if (0.35f - d > deepest) deepest = 0.35f - d
            }
        }
        println("rope over a capsule: deepest penetration %.6f, capsule contacts %d".format(deepest, capsule.contacts))
        assertTrue(capsule.contacts > 3, "premise: the rope must land on the capsule, ${capsule.contacts} contacts")
        assertTrue(deepest < 1e-3f, "and never get inside it, deepest $deepest")
    }

    // ------------------------------------------------------------------------------------------
    // Tying it to things
    // ------------------------------------------------------------------------------------------

    @Test
    fun attachToTiesARopeEndToAClothCornerAndPullsIt() {
        val sim = simWith(0)
        val cloth = sim.addCloth(
            width = 10, height = 8, spacing = 0.25f, stiffness = 60f, curvatureStrength = 0f,
            position = { c, r -> vec(c * 0.25f, r * 0.25f, 0f) },
            pinned = { _, r -> r == 0 }
        )
        val corner = cloth[9, 7]
        val rope = sim.addStrand(
            count = 20, spacing = 0.12f, stiffness = 60f,
            position = { n -> vec(cloth.position(9, 7).x + 0.12f * (n + 1), cloth.position(9, 7).y, 0f) }
        )
        // The far end of the rope is nailed out to the side, further away than the rope can
        // comfortably reach, so the tether is taut rather than a hanging festoon.
        rope.teleport(19, vec(cloth.position(9, 7).x + 2.8f, cloth.position(9, 7).y, 0f))
        rope.pin(19)
        val rest = rope.attachTo(corner, node = 0)
        println("tied a 20-node rope to cloth corner $corner at rest length %.3f".format(rest))
        assertTrue(rest > 0f && rest < 0.3f, "the knot must be short, $rest")
        assertTrue(corner in liveSlots(sim.constraints4!!, rope[0]), "the rope must own the knot")
        assertTrue(rope[0] in liveSlots(sim.constraints4!!, corner), "and so must the cloth corner -- a knot is symmetric")

        // The corner must end up dragged out toward the rope's anchor rather than hanging.
        val untied = run {
            val s2 = simWith(0)
            val c2 = s2.addCloth(
                width = 10, height = 8, spacing = 0.25f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> vec(c * 0.25f, r * 0.25f, 0f) },
                pinned = { _, r -> r == 0 }
            )
            repeat(500) { c2.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16) }
            c2.position(9, 7).x
        }
        repeat(500) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16) }
        val tiedX = cloth.position(9, 7).x
        println("cloth corner x after 500 frames: untied %.3f, tied to the rope %.3f".format(untied, tiedX))
        assertTrue(untied.isFinite() && tiedX.isFinite(), "neither may diverge")
        assertTrue(tiedX > untied + 0.2f, "the rope must visibly pull the corner outward: $untied -> $tiedX")
    }

    @Test
    fun attachToRefusesAClothInteriorRatherThanFailingQuietly() {
        val sim = simWith(0)
        val cloth = sim.addCloth(width = 6, height = 6, spacing = 0.25f, curvatureStrength = 0f)
        val rope = sim.addStrand(count = 5, spacing = 0.1f)
        val interior = cloth[3, 3]
        assertEquals(4, liveSlots(sim.constraints4!!, interior).size, "premise: an interior node spends all four slots")

        val boom = assertFailsWith<IllegalStateException> { rope.attachTo(interior) }
        assertTrue("no free constraint slot" in (boom.message ?: ""), "the message must say why: ${boom.message}")
        assertTrue("$interior" in (boom.message ?: ""), "and which particle: ${boom.message}")

        // A corner has two free slots, so two ropes can be tied to the same corner.
        val cornerParticle = cloth[0, 0]
        assertEquals(2, liveSlots(sim.constraints4!!, cornerParticle).size, "premise: a corner spends two")
        rope.attachTo(cornerParticle, node = 0)
        val second = sim.addStrand(count = 5, spacing = 0.1f)
        second.attachTo(cornerParticle, node = 0)
        assertEquals(4, liveSlots(sim.constraints4!!, cornerParticle).size, "both knots must be there")
        // And a third must be refused.
        val third = sim.addStrand(count = 5, spacing = 0.1f)
        assertFailsWith<IllegalStateException> { third.attachTo(cornerParticle, node = 0) }
    }

    @Test
    fun aKnotIsAnEdgeAndTearsLikeOne() {
        val sim = simWith(0)
        val cloth = sim.addCloth(
            width = 8, height = 6, spacing = 0.25f, stiffness = 60f, curvatureStrength = 0f,
            position = { c, r -> vec(c * 0.25f, r * 0.25f, 0f) },
            pinned = { _, r -> r == 0 }
        )
        val corner = cloth[7, 5]
        val rope = sim.addStrand(
            count = 12, spacing = 0.12f, stiffness = 60f, mass = 8f,
            position = { n -> vec(7 * 0.25f, 5 * 0.25f + 0.12f * (n + 1), 0f) }
        )
        rope.attachTo(corner, node = 0)
        val edgesBefore = sim.liveEdgeCount()

        // Tearing over the WHOLE simulation, so the knot is a candidate like any other edge.
        val tear = sim.addTearing(threshold = 0.18f)
        var g = 9.8f
        repeat(900) {
            g += 0.15f
            rope.step(dt = 1f / 60f, damping = 0.99f, gravity = g, iterations = 16)
        }
        println("knot test: ${tear.brokenTotal} edges torn under a ramp to g = ${"%.0f".format(g)}")
        assertTrue(tear.brokenTotal > 0, "something must give under that load")
        assertEquals(edgesBefore - sim.liveEdgeCount(), tear.brokenTotal, "every tear must be an edge the graph lost")
        // The knot is gone, and nothing had to be written to make it a candidate.
        assertFalse(
            rope[0] in liveSlots(sim.constraints4!!, corner),
            "the knot must be among the things that can fail"
        )
    }

    @Test
    fun aRopeTakesTheWindLikeAnythingElse() {
        val sim = simWith(0)
        val rope = sim.addStrand(count = 24, spacing = 0.12f, stiffness = 60f)
        rope.pin(0)
        val still = run {
            repeat(300) { rope.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16) }
            rope.position(23).z
        }
        assertTrue(abs(still) < 1e-4f, "premise: with no wind the rope stays in its plane, z $still")

        sim.addWind(velocityZ = 8f, drag = 0.8f, range = rope.base until rope.end)
        repeat(300) { rope.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16) }
        val blown = rope.position(23).z
        println("rope tip z: still %.5f, in an 8-unit +z wind %.3f".format(still, blown))
        assertTrue(blown > 0.3f, "the wind must carry the rope downwind, tip z $blown")
    }

    @Test
    fun removeClearsItsSlotsAndDropsItsPass() {
        val sim = simWith(0)
        val rope = sim.addStrand(count = 6, spacing = 0.2f, bendStiffness = 0.4f)
        assertTrue("${rope.name}.bend" in sim.positionProjections, "premise: the bend projection is registered")
        assertEquals(5, sim.liveEdgeCount())
        rope.remove()
        assertEquals(0, sim.liveEdgeCount(), "every slot must be cleared")
        assertFalse("${rope.name}.bend" in sim.positionProjections, "and the projection unregistered")
        for (n in 0 until 6) assertTrue(slotsOf(sim.constraints4!!, rope[n]).all { it == -1 })
    }
}
