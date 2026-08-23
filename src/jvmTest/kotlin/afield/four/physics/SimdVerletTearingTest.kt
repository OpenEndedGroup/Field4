package afield.four.physics

import afield.stdlib.vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers [Tearing] — retiring a distance constraint that has been stretched past a strain
 * threshold, the symmetry that keeps the solver from being left with a one-way spring, the
 * determinism that makes a torn sheet testable at all, and the curvature hook that stops a torn
 * [Cloth] from healing itself in the picture.
 *
 * The recurring premise here is "the sheet was intact and under load first". A tear count of zero
 * satisfies "nothing tore wrongly", and a sheet that never fell satisfies "the tear is where it
 * should be", so every outcome is checked against the same scene with tearing off.
 */
class SimdVerletTearingTest {

    /**
     * A hanging sheet, pinned along the whole of row 0.
     *
     * The whole row, not the four nails the starting-place transcript uses, and that is the
     * difference between a tear sweep that measures something and one that does not. With four
     * pins every edge incident to a pin carries a quarter of the sheet, all eleven of them fail in
     * the same transient at *any* threshold from 4 % to 40 %, the sheet is in free fall from then
     * on with no strain anywhere, and the sweep reports 11, 11, 11, 11, 11. Measured, and the
     * reason this helper looks the way it does.
     */
    private fun hangingCloth(
        width: Int = 12,
        height: Int = 8,
        stiffness: Float = 8f
    ): Pair<SimdVerlet, Cloth> {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        // Two unrelated particles first: base == 0 hides every offset bug, as ClothTest says.
        sim.addParticle(x0 = -20f)
        sim.addParticle(x0 = -21f)
        val cloth = sim.addCloth(
            width = width, height = height, spacing = 0.5f, stiffness = stiffness,
            curvatureStrength = 0f,
            position = { c, r -> vec(c * 0.5f, -r * 0.5f, 0f) },
            pinned = { _, r -> r == 0 }
        )
        return sim to cloth
    }

    private fun slotsSignature(c: SimdVerlet.DirectedConstraints4, upTo: Int): String =
        buildString {
            for (i in 0 until upTo) {
                append(c.n0[i]).append(',').append(c.n1[i]).append(',')
                append(c.n2[i]).append(',').append(c.n3[i]).append(';')
            }
        }

    // ------------------------------------------------------------------------------------------
    // Strain
    // ------------------------------------------------------------------------------------------

    @Test
    fun peakStrainMeasuresWhatTheStiffnessTableCalledPeakStrain() {
        val (loose, _) = hangingCloth(stiffness = 0.8f)
        val (tight, _) = hangingCloth(stiffness = 60f)
        val looseCloth = loose.constraints4!!
        assertTrue(looseCloth.size > 0, "premise: there is a constraint set to measure")

        assertEquals(0f, loose.peakStrain(), 1e-5f, "a cloth laid out at rest length has no strain")

        repeat(200) { loose.step(1f / 60f, 0.99f, -4f, 8, loose.constraints4) }
        repeat(200) { tight.step(1f / 60f, 0.99f, -4f, 16, tight.constraints4) }
        val loosePeak = loose.peakStrain()
        val tightPeak = tight.peakStrain()
        println("peak strain: 0.8 x 8 -> %.2f, 60 x 16 -> %.3f".format(loosePeak, tightPeak))
        assertTrue(loosePeak > 1f, "premise: a 0.8 x 8 sheet is a web, peak strain $loosePeak")
        assertTrue(tightPeak < 0.35f, "a 60 x 16 sheet holds together, peak strain $tightPeak")
        assertTrue(loosePeak > tightPeak * 4f, "and the difference is an order of magnitude, not noise")
    }

    @Test
    fun liveEdgeCountMatchesTheGridAndFallsAsEdgesBreak() {
        val (sim, cloth) = hangingCloth(width = 6, height = 5)
        // A w x h grid has h*(w-1) horizontal plus w*(h-1) vertical edges.
        val expected = 5 * (6 - 1) + 6 * (5 - 1)
        assertEquals(expected, sim.liveEdgeCount(), "the whole grid, symmetric pairs counted once")

        val tear = sim.addTearing(threshold = 0.2f)
        assertTrue(tear.breakEdge(cloth[0, 0], cloth[1, 0]), "an edge that exists must break")
        assertFalse(tear.breakEdge(cloth[0, 0], cloth[1, 0]), "and not break twice")
        assertEquals(expected - 1, sim.liveEdgeCount(), "one edge gone")
        assertFalse(tear.breakEdge(cloth[0, 0], cloth[5, 4]), "an edge that never existed cannot break")
    }

    // ------------------------------------------------------------------------------------------
    // Symmetry
    // ------------------------------------------------------------------------------------------

    @Test
    fun breakingAnEdgeRetiresBothDirections() {
        val (sim, cloth) = hangingCloth(width = 5, height = 5)
        val c = sim.constraints4!!
        val a = cloth[2, 2]
        val b = cloth[3, 2]

        fun owns(from: Int, to: Int) = (0 until 4).any { s ->
            val n = when (s) { 0 -> c.n0[from]; 1 -> c.n1[from]; 2 -> c.n2[from]; else -> c.n3[from] }
            val k = when (s) { 0 -> c.s0[from]; 1 -> c.s1[from]; 2 -> c.s2[from]; else -> c.s3[from] }
            n == to && k != 0f
        }

        assertTrue(owns(a, b) && owns(b, a), "premise: a cloth grid wires every edge in both directions")
        sim.addTearing().breakEdge(a, b)
        assertFalse(owns(a, b), "the forward direction must go")
        assertFalse(
            owns(b, a),
            "and so must the reverse -- a one-way spring accelerates its own centre of mass, and " +
                "debugLines would still draw it as a live edge"
        )
    }

    @Test
    fun aTornEdgeStopsBeingDrawn() {
        val (sim, cloth) = hangingCloth(width = 5, height = 5)
        fun segments(): Int = sim.debugLines(points = false).sumOf { it.nodes.size } / 2
        val before = segments()
        assertTrue(before > 20, "premise: the debug view draws the grid, got $before segments")
        sim.addTearing().breakEdge(cloth[2, 2], cloth[3, 2])
        val after = segments()
        assertEquals(before - 1, after, "exactly one segment must disappear")
    }

    // ------------------------------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------------------------------

    @Test
    fun tearsTheSameWayTwice() {
        fun run(maxPerStep: Int): Pair<String, Int> {
            val (sim, cloth) = hangingCloth(width = 14, height = 9, stiffness = 8f)
            val tear = sim.addTearing(threshold = 0.28f, maxBreaksPerStep = maxPerStep)
            repeat(300) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 8) }
            return slotsSignature(sim.constraints4!!, sim.size) to tear.brokenTotal
        }

        for (rate in listOf(-1, 1, 3)) {
            val (a, na) = run(rate)
            val (b, nb) = run(rate)
            assertTrue(na > 4, "premise (rate $rate): the sheet must actually tear, only $na edges went")
            assertEquals(na, nb, "rate $rate: the same run must break the same number of edges")
            assertEquals(a, b, "rate $rate: and leave slot-for-slot the same constraint set")
        }

        // And rate-limiting must be a *slower* rip, not a different one. Measured on the time
        // course rather than the endpoint: by the last frame both have finished tearing whatever
        // the load can tear, and the interesting difference is how the failure was spread out.
        fun course(maxPerStep: Int): IntArray {
            val (sim, cloth) = hangingCloth(width = 14, height = 9, stiffness = 8f)
            val tear = sim.addTearing(threshold = 0.28f, maxBreaksPerStep = maxPerStep)
            val out = IntArray(300)
            repeat(300) { f ->
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 8)
                out[f] = tear.brokenTotal
            }
            return out
        }
        val fast = course(-1)
        val slow = course(1)
        println("edges torn by frame 1/5/20/300: unlimited ${fast[0]}/${fast[4]}/${fast[19]}/${fast[299]}, " +
            "1 per step ${slow[0]}/${slow[4]}/${slow[19]}/${slow[299]}")
        assertTrue(fast.last() > 4, "premise: the unthrottled sheet tears, ${fast.last()} edges")
        for (f in slow.indices) {
            assertTrue(slow[f] <= f + 1, "one break per step cannot exceed the frame count: ${slow[f]} by frame ${f + 1}")
        }
        val firstBurst = fast.indexOfFirst { it > 0 }
        assertTrue(firstBurst >= 0, "premise: the unthrottled sheet must break at some frame")
        assertTrue(
            fast[firstBurst] > slow[firstBurst],
            "the unthrottled sheet must go all at once and the throttled one must not: " +
                "${fast[firstBurst]} vs ${slow[firstBurst]} at frame $firstBurst"
        )
        // And it does NOT end in the same place. Rate limiting is not "the same rip, slower": a
        // sheet that fails one edge per frame goes on carrying load while it fails, so more of it
        // reaches the threshold. Measured here at 14 unthrottled against 22 at one per step. It is
        // a different rip, reproducibly so, and the KDoc says as much.
        assertTrue(
            slow.last() > fast.last(),
            "a throttled failure carries load longer and tears more, not less: ${slow.last()} vs ${fast.last()}"
        )
    }

    @Test
    fun aThresholdAboveTheSettledStrainTearsNothing() {
        val (sim, cloth) = hangingCloth(width = 12, height = 8, stiffness = 60f)
        val edgesBefore = sim.liveEdgeCount()
        val tear = sim.addTearing(threshold = 0.9f)
        repeat(400) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -4f, iterations = 16) }
        println("settled peak strain %.3f under a 0.9 threshold".format(tear.peakStrainLastStep))
        assertTrue(tear.peakStrainLastStep > 0.001f, "premise: the sheet is under some load")
        assertTrue(tear.peakStrainLastStep < 0.9f, "premise: and below the threshold")
        assertEquals(0, tear.brokenTotal, "so nothing may tear")
        assertEquals(edgesBefore, sim.liveEdgeCount(), "and the grid is intact")
    }

    /**
     * The threshold sweep: at what load does a sheet with this tear threshold fail?
     *
     * *Not* "how many edges tear", which is what this test measured first and which turns out to
     * be the wrong question. A uniform sheet uniformly pinned carries a uniform load, so its
     * fourteen attachment edges are at the same strain and fail in the same frame at every
     * threshold from 4 % to 25 % -- the sweep read 14, 14, 14, 14, 14, and the count is a fact
     * about the geometry rather than about the threshold. What the threshold sets is *when*.
     *
     * So the sheet is settled at `g = -2`, the load is then ramped, and the load at first failure
     * is what is recorded. For a Hookean fabric strain is proportional to load, so the ratio
     * `failure load / threshold` should be roughly constant down the column -- and it is, which is
     * the actual claim: the threshold is a material property in strain, and the load it survives
     * follows from the stiffness rather than from anything a tuner has to discover.
     */
    @Test
    fun tearThresholdSetsTheFailureLoad() {
        class Row(val threshold: Float, val failureLoad: Float, val torn: Int, val lost: Int)

        val rows = ArrayList<Row>()
        for (threshold in listOf(0.04f, 0.06f, 0.09f, 0.14f, 0.25f)) {
            val (sim, cloth) = hangingCloth(width = 14, height = 9, stiffness = 60f)
            val total = sim.liveEdgeCount()
            repeat(200) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -2f, iterations = 16) }
            val tear = sim.addTearing(threshold = threshold)
            var failureLoad = Float.NaN
            repeat(900) { f ->
                val g = -2f - 118f * f / 900f
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = g, iterations = 16)
                if (failureLoad.isNaN() && tear.brokenTotal > 0) failureLoad = -g
            }
            rows += Row(threshold, failureLoad, tear.brokenTotal, total - sim.liveEdgeCount())
        }

        println("14 x 9 sheet, 226 edges, stiffness 60 x 16, settled at g = -2 then ramped to -120")
        println("  threshold   failure load   load/threshold   edges torn   edges lost")
        for (r in rows) {
            println(
                "  %9.2f   %12.1f   %14.0f   %10d   %10d".format(
                    r.threshold, r.failureLoad, r.failureLoad / r.threshold, r.torn, r.lost
                )
            )
        }

        for (r in rows) {
            assertTrue(r.failureLoad.isFinite(), "premise: threshold ${r.threshold} must fail somewhere in the ramp")
            assertEquals(r.torn, r.lost, "every reported tear must be an edge the grid lost (${r.threshold})")
            assertTrue(r.torn > 0, "and there must be tears to report (${r.threshold})")
        }
        for (n in 1 until rows.size) {
            assertTrue(
                rows[n].failureLoad > rows[n - 1].failureLoad,
                "a tougher fabric must survive a heavier load: ${rows.map { it.failureLoad }}"
            )
        }
        val ratios = rows.map { it.failureLoad / it.threshold }
        assertTrue(
            ratios.max() < ratios.min() * 1.8f,
            "strain is proportional to load for a Hookean sheet, so load/threshold must be roughly " +
                "constant down the column: $ratios"
        )
        assertTrue(rows.first().failureLoad > 3f, "premise: even the weakest fabric survives more than its own weight")
        assertTrue(rows.last().failureLoad > 4f * rows.first().failureLoad, "and the range must be wide: $ratios")
    }

    // ------------------------------------------------------------------------------------------
    // The curvature hook
    // ------------------------------------------------------------------------------------------

    @Test
    fun tearingSuppressesTheCurvatureStencilAtBothLips() {
        val (sim, cloth) = hangingCloth(width = 9, height = 7, stiffness = 8f)
        cloth.curvatureStrength = 40f
        cloth.enableCurvature()
        val tear = cloth.enableTearing(threshold = 0.25f)

        val a = cloth[4, 3]
        val b = cloth[5, 3]
        assertFalse(cloth.isCurvatureSuppressed(a), "premise: the stencil starts intact")
        tear.breakEdge(a, b)
        assertTrue(cloth.isCurvatureSuppressed(a), "the owner's stencil must go")
        assertTrue(cloth.isCurvatureSuppressed(b), "and the neighbour's, since the edge is in both")
        assertFalse(cloth.isCurvatureSuppressed(cloth[1, 1]), "and nobody else's")
        assertFalse(cloth.isCurvatureSuppressed(0), "a particle outside the cloth is not this cloth's business")

        // And it must actually change the force. The sheet has to be *bent* first: a flat grid's
        // curvature term is exactly zero everywhere, so measuring it at rest would compare zero
        // against zero and call the suppression a success.
        repeat(60) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 8) }
        val state = sim.state
        SimdVerlet.zero3(state)
        cloth.accumulateCurvature(state)
        assertEquals(0f, state.ax[a], 0f, "a suppressed node accumulates nothing")
        assertEquals(0f, state.ay[a], 0f, "a suppressed node accumulates nothing")
        val liveInterior = cloth[2, 2]
        assertNotEquals(
            0f, state.ax[liveInterior] + state.ay[liveInterior] + state.az[liveInterior],
            "premise: an unsuppressed interior node must still be getting a curvature force"
        )
    }

    @Test
    fun aTornSheetActuallyComesApart() {
        // A cloth pinned at the two top corners only, with a heavy load: the middle must fail and
        // the two halves must separate. Compared against the same scene with tearing off.
        fun run(tearing: Boolean): Triple<Float, Int, Int> {
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            val cloth = sim.addCloth(
                width = 16, height = 10, spacing = 0.5f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> vec(c * 0.5f, -r * 0.5f, 0f) },
                pinned = { _, r -> r == 0 }
            )
            val edges = sim.liveEdgeCount()
            val tear = if (tearing) cloth.enableTearing(threshold = 0.12f) else null
            repeat(500) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16) }
            // How far the lowest particle has fallen: a torn sheet drops its middle.
            var lowest = Float.POSITIVE_INFINITY
            for (i in cloth.base until cloth.end) if (sim.state.y[i] < lowest) lowest = sim.state.y[i]
            return Triple(lowest, tear?.brokenTotal ?: 0, edges - sim.liveEdgeCount())
        }

        val (intactLowest, intactTears, intactLost) = run(tearing = false)
        assertEquals(0, intactTears, "premise: the control run has tearing off")
        assertEquals(0, intactLost, "premise: and loses no edges")
        assertTrue(intactLowest.isFinite(), "premise: and does not diverge, lowest $intactLowest")

        val (tornLowest, tears, lost) = run(tearing = true)
        println("lowest particle after 500 frames: intact %.2f, torn %.2f (%d edges)".format(intactLowest, tornLowest, tears))
        assertTrue(tears > 10, "the sheet must come apart, only $tears edges tore")
        assertEquals(tears, lost, "and every tear must be an edge the grid lost")
        assertTrue(
            tornLowest < intactLowest - 1.5f,
            "a torn sheet must drop appreciably further than an intact one: $intactLowest vs $tornLowest"
        )
    }

    @Test
    fun detachingStopsTearingAndTheRangeIsRespected() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val a = sim.addCloth(
            width = 8, height = 6, spacing = 0.5f, stiffness = 8f, curvatureStrength = 0f,
            position = { c, r -> vec(c * 0.5f, -r * 0.5f, 0f) },
            pinned = { _, r -> r == 0 }
        )
        val b = sim.addCloth(
            width = 8, height = 6, spacing = 0.5f, stiffness = 8f, curvatureStrength = 0f,
            position = { c, r -> vec(10f + c * 0.5f, -r * 0.5f, 0f) },
            pinned = { _, r -> r == 0 }
        )
        val bEdgesBefore = sim.liveEdgeCount(b.base until b.end)
        assertTrue(bEdgesBefore > 40, "premise: the second cloth is wired, $bEdgesBefore edges")

        val tear = a.enableTearing(threshold = 0.15f)
        repeat(200) { a.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 8) }
        assertTrue(tear.brokenTotal > 0, "premise: the first cloth tore, ${tear.brokenTotal} edges")
        assertEquals(bEdgesBefore, sim.liveEdgeCount(b.base until b.end), "the other cloth must be untouched")

        val torn = tear.brokenTotal
        tear.detach()
        repeat(200) { a.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 8) }
        assertEquals(torn, tear.brokenTotal, "a detached tearing pass must stop tearing")
        assertTrue("${a.name}.tearing" !in sim.constraintEdits, "and unregister itself")
    }

    /**
     * The whole world layer in one scene, and the claim the demo document makes about it.
     *
     * The `simdverlet_world` transcript's fourth box is a sheet with a weighted rope tied
     * to it, a sphere and a floor under it, wind across it and tearing on it, and its comment tells
     * the reader what moving one named knob will do. That sentence is a promise, so it is a test:
     * the same scene, the same numbers, the two ends of the claim asserted.
     */
    @Test
    fun theWholeWorldLayerInOneSceneBehavesAsTheDemoSaysItWill() {
        class Row(val threshold: Float, val torn: Int, val contacts: Int)

        fun run(threshold: Float): Row {
            val sim = SimdVerlet(SimdVerlet.State3.empty())
            val cloth = sim.addCloth(
                width = 20, height = 12, spacing = 0.22f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> vec(c * 0.22f, r * 0.22f, 0f) },
                pinned = { _, r -> r == 0 }
            )
            val rope = sim.addStrand(
                count = 16, spacing = 0.16f, stiffness = 150f, bendStiffness = 0.2f,
                position = { n -> vec(10 * 0.22f, 11 * 0.22f + 0.16f * (n + 1), 0f) }
            )
            rope.attachTo(cloth[10, 11], node = 0)
            sim.state.invMass!![rope[15]] = 1f / 24f
            val world = sim.addColliders(
                SphereCollider(1.1f, 4.6f, 0f, radius = 0.7f).also { it.friction = 0.6f },
                // A room, not a floor: a torn-free piece under a steady wind leaves the frame and
                // never comes back, which is fine for 900 frames and useless for a demo somebody
                // leaves running. Measured: at frame 17654 the live document's picture was empty
                // while the solver still held 443 edges.
                BoxCollider(-0.9f, -0.4f, -1.6f, 7.4f, 6.4f, 1.6f, inside = true)
                    .also { it.friction = 0.9f }
            )
            sim.addWind(velocityX = 3f, drag = 0.25f, turbulence = 0.8f, seed = 5)
            val tear = sim.addTearing(threshold = threshold, maxBreaksPerStep = 2)
            repeat(900) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 3f, iterations = 16) }
            assertTrue(
                (0 until sim.size).all { sim.state.y[it].isFinite() },
                "threshold $threshold: five subsystems in one simulation must not diverge"
            )
            return Row(threshold, tear.brokenTotal, world.contacts)
        }

        val rows = listOf(0.15f, 0.25f, 0.40f, 0.60f).map(::run)
        println("20 x 12 sheet + a 16-node rope with a mass-24 tip + a sphere + a floor + gusty wind, 900 frames")
        println("  tearThreshold   edges torn   collider contacts in the last frame")
        for (r in rows) println("  %13.2f   %10d   %34d".format(r.threshold, r.torn, r.contacts))

        for (n in 1 until rows.size) {
            assertTrue(
                rows[n].torn < rows[n - 1].torn,
                "the demo's knob must be monotone or its comment is a lie: ${rows.map { it.torn }}"
            )
        }
        assertTrue(rows[0].torn > 100, "at 0.15 the sheet must shred, ${rows[0].torn} edges")
        assertEquals(0, rows[3].torn, "at 0.60 it must hold, ${rows[3].torn} edges")
        // And the second half of the promise: intact, the sheet never reaches what is under it.
        assertEquals(0, rows[3].contacts, "an intact sheet must hang clear of the sphere and floor")
        assertTrue(rows[0].contacts > 50, "a shredded one must land on them, ${rows[0].contacts} contacts")
    }
}
