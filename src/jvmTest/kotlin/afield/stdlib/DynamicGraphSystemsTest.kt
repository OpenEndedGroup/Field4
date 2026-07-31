package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First coverage for `dynamic_graph_systems.kt`, written alongside
 * `public/docs/dynamic_graph_systems_examples.md`. Every number the page states about this family is
 * asserted here, so the page cannot rot silently.
 *
 * The house rule for this material is **control arms, not thresholds**. A dynamic graph is a system
 * whose output looks plausible under almost any parameters, so "the mean weight went up" is worth
 * nothing on its own; every claim below is either an exact identity (a conservation law, a closed-form
 * decay, a rigid count) or a comparison against the *same* run with the mechanism under test switched
 * off.
 *
 * Five of these are regressions for defects this page's research turned up, all of which shipped
 * un-noticed because nothing had ever run the file:
 *
 *  - [growingTheCloudDoesNotThrow] / [morphogenesisStepSurvivesGrowth] — `DynamicEdgeList.pointCount`
 *    was a constructor `val` while three processes append points, so the first birth threw
 *    `IllegalArgumentException: DynamicEdgeList.getOrCreate: b out of range`. The whole
 *    `DynamicPointProcess` half of the family, including `exampleDynamicMorphogenesisStep`, could not
 *    complete a run.
 *  - [kNearestOnASmallCloudIsNotQuadraticallySlow] — `kNearest` with the default infinite `maxRadius`
 *    expanded its cell ring to the hard cap of 128 whenever the cloud held fewer than `k` other
 *    points, which is 257³ bucket probes: measured at 2569 ms for a single call on a 4-point cloud.
 *  - [snapshotAfterPruningReportsTheSurvivingPointCount] — `toPointGraph()` kept reporting the
 *    original point count after a prune had compacted the cloud.
 *  - [mergingTwoPointsKeepsTheAbsorbedPointsEdges] — the merge marked the absorbed point's edges with
 *    a flag and then deleted them, so a "merge" disconnected the survivor instead of joining it.
 */
class DynamicGraphSystemsTest {

    // ==========================================================================================
    // Fixtures
    // ==========================================================================================

    /** A ring of [n] points of radius [r] in the z = 0 plane. Deterministic, no RNG anywhere here. */
    private fun ring(n: Int, r: Double = 0.5): MutableList<Vec3> = MutableList(n) { i ->
        val a = 2.0 * PI * i / n
        Vec3((r * cos(a)).toFloat(), (r * sin(a)).toFloat(), 0f)
    }

    /** A deterministic phyllotaxis disc — a cloud with genuinely varying local density. */
    private fun disc(n: Int, r: Double = 0.5): MutableList<Vec3> = MutableList(n) { i ->
        val a = i * 2.39996322972865332
        val rad = r * sqrt((i + 0.5) / n)
        Vec3((rad * cos(a)).toFloat(), (rad * sin(a)).toFloat(), 0f)
    }

    private fun kinetics(n: Int): DynamicPointKinetics =
        DynamicPointKinetics(mutableListOf(), mutableListOf(), mutableListOf()).also { it.ensureSize(n) }

    private fun degreeOf(index: Int, edges: DynamicEdgeList): Int =
        edges.values().count { it.i == index || it.j == index }

    // ==========================================================================================
    // Vec3SpatialHash — the neighbour query everything else is built on
    // ==========================================================================================

    /**
     * The hash is an acceleration structure, so the only interesting question is whether it agrees
     * with the brute-force answer it is standing in for. Both queries, on a cloud with real density
     * variation, against an O(N²) oracle computed here.
     */
    @Test
    fun spatialHashAgreesWithBruteForce() {
        val points = disc(300, 0.5)
        val hash = Vec3SpatialHash(0.07)
        hash.rebuild(points)

        for (i in listOf(0, 1, 7, 100, 299)) {
            val brute = points.indices
                .filter { it != i }
                .map { Neighbor(it, points[i].distanceSquared(points[it]).toDouble()) }
                .sortedBy { it.distanceSquared }

            // Radius query: the *set* must match exactly. (Ties in distance make the order of equal
            // -distance entries unspecified, so compare sets of indices.)
            val r = 0.12
            val got = hash.radiusNeighbors(i, r).map { it.index }.toSet()
            val want = brute.filter { it.distanceSquared <= r * r }.map { it.index }.toSet()
            assertEquals(want, got, "radiusNeighbors($i, $r) must equal the brute-force answer")

            // kNN: distances must match element-for-element (indices can differ only on a tie).
            val k = 12
            val gotK = hash.kNearest(i, k).map { it.distanceSquared }
            val wantK = brute.take(k).map { it.distanceSquared }
            assertEquals(wantK.size, gotK.size, "kNearest($i, $k) must return k neighbours")
            for (n in wantK.indices) {
                assertTrue(
                    abs(wantK[n] - gotK[n]) < 1e-9,
                    "kNearest($i, $k)[$n] distance ${gotK[n]} != brute force ${wantK[n]}",
                )
            }
        }
    }

    /**
     * Regression. `kNearest`'s ring-expansion loop had two exits — "I have k" and "the ring is wider
     * than maxRadius" — and the default `maxRadius` is `+Infinity`, so on a cloud holding fewer than
     * `k` other points *neither* fired and it ground out to the hard `cr > 128` cap: 257³ bucket
     * probes, measured at 2569 ms for one call. Reachable in normal use, because
     * `PruneIsolatedPointsProcess` shrinks clouds.
     *
     * The fix is a third exit that is *exact* rather than a heuristic: `rebuild` records the occupied
     * cell extent, and a ring wider than that extent cannot contain a bucket. So this asserts the
     * answer as well as the time — a fast wrong answer would be worse than the bug.
     */
    @Test
    fun kNearestOnASmallCloudIsNotQuadraticallySlow() {
        val points = ring(4, 0.3)
        val hash = Vec3SpatialHash(0.05)
        hash.rebuild(points)

        val start = System.nanoTime()
        val got = hash.kNearest(0, 8)
        val elapsedMs = (System.nanoTime() - start) / 1e6

        // Correctness first: a 4-point cloud has exactly 3 other points, all of them inside an
        // infinite maxRadius. (Points 1 and 3 are exactly equidistant from point 0, so their relative
        // order is a tie and is not asserted; the antipode must come last.)
        assertEquals(3, got.size, "a 4-point cloud can only answer with 3 neighbours")
        assertEquals(setOf(1, 2, 3), got.map { it.index }.toSet())
        assertEquals(2, got.last().index, "…the antipode is furthest")

        // The bug was 2569 ms. Two orders of magnitude of headroom, so this is a bug detector and not
        // a machine-speed detector.
        assertTrue(elapsedMs < 250.0, "kNearest on a 4-point cloud took $elapsedMs ms; the ring expansion is unbounded again")
    }

    /** `sample` is the off-point query, so it must include a point sitting exactly on the centre. */
    @Test
    fun sampleIsTheOffPointQueryAndIncludesTheCentre() {
        val points = ring(8, 0.3)
        val hash = Vec3SpatialHash(0.1)
        hash.rebuild(points)

        val onAPoint = hash.sample(points[0], 1e-6)
        assertEquals(listOf(0), onAPoint.map { it.index }, "sample() must not exclude the point it lands on")

        // The whole cloud, from the centre of the ring.
        assertEquals(8, hash.sample(Vec3(0f, 0f, 0f), 0.31).size)
        assertEquals(0, hash.sample(Vec3(0f, 0f, 0f), 0.29).size, "…and nothing from just inside it")
    }

    // ==========================================================================================
    // DynamicEdgeList — weight, ttl and the two decay laws
    // ==========================================================================================

    /**
     * `decayExponential(dt, lambda)` must be the closed form `w · e^(-λt)`, not an approximation of
     * it, and in particular must be independent of the step size it is reached by. The control is
     * `decayLinear`, whose 20-step and 1-step answers agree with *each other* but describe a
     * different curve.
     */
    @Test
    fun bothDecayLawsMatchTheirClosedForm() {
        fun weightAfter(steps: Int, total: Float, exponential: Boolean): Double {
            val edges = DynamicEdgeList(2)
            edges.reinforce(0, 1, 1.0, ttl = 1000f, maxWeight = 1.0)
            val dt = total / steps
            repeat(steps) { if (exponential) edges.decayExponential(dt, 0.35) else edges.decayLinear(dt, 0.35) }
            return edges.get(0, 1)?.weight ?: 0.0
        }

        // Exponential: exactly e^(-0.35 * 2) after two seconds, however you get there.
        val closedForm = exp(-0.35 * 2.0)
        for (steps in listOf(1, 4, 120)) {
            val w = weightAfter(steps, 2f, exponential = true)
            assertTrue(abs(w - closedForm) < 1e-6, "exponential decay in $steps steps gave $w, closed form is $closedForm")
        }

        // Linear: exactly 1 - 0.35*2, and a *different* number from the exponential — which is the
        // whole reason both exist.
        for (steps in listOf(1, 4, 120)) {
            val w = weightAfter(steps, 2f, exponential = false)
            assertTrue(abs(w - (1.0 - 0.7)) < 1e-6, "linear decay in $steps steps gave $w, expected 0.3")
        }
        assertTrue(abs(closedForm - 0.3) > 0.19, "the two laws must not be the same curve")

        // Half-life, stated the way the page states it.
        val edges = DynamicEdgeList(2)
        edges.reinforce(0, 1, 1.0, ttl = 1000f, maxWeight = 1.0)
        val halfLife = ln(2.0) / 0.35
        edges.decayExponential(halfLife.toFloat(), 0.35)
        assertTrue(abs(edges.get(0, 1)!!.weight - 0.5) < 1e-6, "ln2/lambda must halve the weight")
    }

    /** ttl expiry removes an edge even when its weight is still high; that is what ttl is *for*. */
    @Test
    fun ttlRemovesAnEdgeThatIsStillHeavy() {
        val edges = DynamicEdgeList(2)
        edges.reinforce(0, 1, 8.0, ttl = 0.5f, maxWeight = 8.0)
        repeat(4) { edges.decayExponential(0.1f, 1e-9) }  // lambda ~ 0: weight is untouched
        assertEquals(1, edges.values().size, "still alive at t = 0.4 with ttl 0.5")
        assertTrue(edges.get(0, 1)!!.weight > 7.99, "…and still heavy")
        edges.decayExponential(0.2f, 1e-9)
        assertEquals(0, edges.values().size, "ttl expiry must drop a heavy edge")

        // The control: the same schedule with the ttl refreshed each step keeps it.
        val kept = DynamicEdgeList(2)
        kept.reinforce(0, 1, 8.0, ttl = 0.5f, maxWeight = 8.0)
        repeat(6) {
            kept.reinforce(0, 1, 0.0, ttl = 0.5f, maxWeight = 8.0)
            kept.decayExponential(0.1f, 1e-9)
        }
        assertEquals(1, kept.values().size, "a refreshed ttl must survive the same 0.6 s")
    }

    /** Undirected keys collapse (a,b) and (b,a); directed keys do not. */
    @Test
    fun edgeIdentityFollowsTheUndirectedFlag() {
        val undirected = DynamicEdgeList(4, undirected = true)
        undirected.reinforce(2, 1, 1.0, 5f, 8.0)
        undirected.reinforce(1, 2, 1.0, 5f, 8.0)
        assertEquals(1, undirected.values().size)
        assertEquals(2.0, undirected.get(1, 2)!!.weight, 1e-12)
        assertEquals(1 to 2, undirected.values().first().let { it.i to it.j }, "…stored low-index-first")

        val directed = DynamicEdgeList(4, undirected = false)
        directed.reinforce(2, 1, 1.0, 5f, 8.0)
        directed.reinforce(1, 2, 1.0, 5f, 8.0)
        assertEquals(2, directed.values().size, "a directed store must keep both arcs")
    }

    /** `maxWeight` is a hard ceiling however many times an edge is reinforced. */
    @Test
    fun reinforcementSaturatesAtMaxWeight() {
        val edges = DynamicEdgeList(2)
        repeat(1000) { edges.reinforce(0, 1, 0.5, 5f, maxWeight = 3.0) }
        assertEquals(3.0, edges.get(0, 1)!!.weight, 1e-12)
    }

    // ==========================================================================================
    // The six DynamicGraphSystems
    // ==========================================================================================

    /**
     * `mutualOnly` is the kNN system's one structural switch, and it is the difference between a
     * relation and a symmetric relation. The control arm is the same cloud, same clock, same k, with
     * the flag flipped: a mutual graph must be a strict subset of the one-way graph.
     */
    @Test
    fun mutualOnlyIsAStrictSubsetOfTheOneWayGraph() {
        fun run(mutual: Boolean): Set<Pair<Int, Int>> {
            val state = DynamicPointState(disc(120, 0.5))
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.08)
            val system = Vec3BreathingAnisotropicKnnSystem(
                k = 5, ttl = 10f, reinforce = 1.0, maxWeight = 8.0,
                mutualOnly = mutual, queryRadius = 0.25,
            )
            repeat(10) { stepDynamicGraph(1f / 60f, state, edges, hash, system) }
            return edges.values().map { it.i to it.j }.toSet()
        }

        val oneWay = run(mutual = false)
        val mutual = run(mutual = true)
        assertTrue(mutual.isNotEmpty(), "the mutual graph must not be empty")
        assertTrue(oneWay.containsAll(mutual), "every mutual edge is also a one-way edge")
        assertTrue(
            mutual.size < oneWay.size,
            "mutualOnly must actually reject something: ${mutual.size} vs ${oneWay.size}",
        )
    }

    /**
     * "Breathing" is the claim that the *same* static cloud yields a different neighbour set as the
     * clock advances, because the distance metric is warped by an axis and a stretch that both move
     * with `state.time`. The control is the identical cloud stepped with the clock **held**: if the
     * anisotropy were inert, the two would agree.
     */
    @Test
    fun theBreathingMetricActuallyChangesWithTheClock() {
        fun neighboursAt(time: Float): Set<Pair<Int, Int>> {
            val state = DynamicPointState(disc(90, 0.5), time = time)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.09)
            // One step only: the edge set is then a pure readout of the metric at `time`.
            stepDynamicGraph(
                1f / 60f, state, edges, hash,
                Vec3BreathingAnisotropicKnnSystem(4, 10f, 1.0, 8.0, mutualOnly = false, queryRadius = 0.3),
            )
            return edges.values().map { it.i to it.j }.toSet()
        }

        val t0 = neighboursAt(0f)
        val tSame = neighboursAt(0f)
        val tLater = neighboursAt(1.7f)
        assertEquals(t0, tSame, "the metric must be a deterministic function of the clock")
        assertTrue(t0.isNotEmpty())
        val moved = (t0 - tLater).size + (tLater - t0).size
        assertTrue(moved > 0, "the anisotropy is inert: t=0 and t=1.7 gave identical neighbour sets")
    }

    /**
     * Hysteresis: an edge that has formed survives a separation that would have prevented it forming.
     * Two points are walked apart from `d < rEnter` to `rEnter < d < rExit`. The control arm is the
     * same walk with `rExit == rEnter`, which is the no-hysteresis system, and it must lose the edge.
     */
    @Test
    fun hysteresisKeepsAnEdgeThroughASeparationThatWouldNotFormIt() {
        fun walk(rExit: Double): Boolean {
            val a = Vec3(0f, 0f, 0f)
            val b = Vec3(0.05f, 0f, 0f)
            val state = DynamicPointState(mutableListOf(a, b))
            val edges = DynamicEdgeList(2)
            val hash = Vec3SpatialHash(0.2)
            val system = Vec3PersistentProximityHysteresisSystem(
                rEnter = 0.10, rExit = rExit, ttl = 0.2f, reinforce = 4.0, maxWeight = 8.0,
            )
            // 30 frames together, then walked out to 0.13 — beyond rEnter, inside a 0.16 rExit.
            repeat(30) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
            val formed = edges.get(0, 1) != null
            assertTrue(formed, "the edge must form while the points are 0.05 apart")
            state.points[1] = Vec3(0.13f, 0f, 0f)
            repeat(60) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
            return edges.get(0, 1) != null
        }

        assertTrue(walk(rExit = 0.16), "with hysteresis the edge must survive the separation")
        assertTrue(!walk(rExit = 0.10), "with rExit == rEnter it must not — that is the control arm")
    }

    /**
     * The alpha-event system's whole point is that it closes *triangles*: it reinforces all three
     * sides of every small, non-degenerate triple, so **every edge it emits belongs to a closed
     * triangle**. That is an exact structural property rather than a density statistic — a
     * triangles-per-edge ratio would only be a statement about how tightly the two systems were tuned
     * — and the kNN system on the identical cloud fails it, which is the control arm.
     */
    @Test
    fun everyAlphaEventEdgeBelongsToATriangleAndNotEveryKnnEdgeDoes() {
        fun closedFraction(system: DynamicGraphSystem): Pair<Int, Double> {
            val state = DynamicPointState(disc(140, 0.5))
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.08)
            repeat(10) { stepDynamicGraph(1f / 60f, state, edges, hash, system) }
            val adjacency = Array(state.pointCount) { HashSet<Int>() }
            for (e in edges.values()) { adjacency[e.i].add(e.j); adjacency[e.j].add(e.i) }
            val closed = edges.values().count { e -> adjacency[e.i].any { it != e.j && adjacency[e.j].contains(it) } }
            return edges.values().size to closed.toDouble() / edges.values().size
        }

        val (alphaEdges, alphaClosed) = closedFraction(
            Vec3AlphaEventHypergraphCollapseSystem(
                neighborhoodK = 8, alphaRadius = 0.10, ttl = 10f, reinforce = 1.0,
                maxWeight = 8.0, maxNeighborRadius = 0.12,
            ),
        )
        assertTrue(alphaEdges > 100, "the alpha system produced only $alphaEdges edges")
        assertEquals(1.0, alphaClosed, 1e-12, "every alpha-event edge must be part of a triangle")

        val (knnEdges, knnClosed) = closedFraction(
            Vec3BreathingAnisotropicKnnSystem(
                k = 3, ttl = 10f, reinforce = 1.0, maxWeight = 8.0, mutualOnly = true, queryRadius = 0.10,
            ),
        )
        assertTrue(knnEdges > 100, "the control produced only $knnEdges edges")
        assertTrue(knnClosed < 1.0, "the kNN control closed $knnClosed of its edges — it must not close all of them")
    }

    /**
     * Regression. `minArea` is documented as an area and was compared against the *squared* area, so
     * its effective units were area² and the default 1e-5 rejected every triangle under 3.2e-3 —
     * 316× larger than it reads. Pinned here against a triangle of a known, hand-computed area.
     */
    @Test
    fun theAlphaSystemsMinAreaIsAnArea() {
        // A right triangle with legs 0.02: area exactly 2e-4. Point 3 is far away and takes no part.
        val a = 2e-4
        fun edgeCountWithMinArea(minArea: Double): Int {
            val state = DynamicPointState(
                mutableListOf(Vec3(0f, 0f, 0f), Vec3(0.02f, 0f, 0f), Vec3(0f, 0.02f, 0f), Vec3(5f, 5f, 5f)),
            )
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.03)
            stepDynamicGraph(
                1f / 60f, state, edges, hash,
                Vec3AlphaEventHypergraphCollapseSystem(
                    neighborhoodK = 3, alphaRadius = 0.05, ttl = 10f, reinforce = 100.0,
                    maxWeight = 8.0, maxNeighborRadius = 0.05, minArea = minArea,
                ),
            )
            return edges.values().size
        }

        assertEquals(3, edgeCountWithMinArea(a * 0.5), "a triangle of area $a clears a minArea of ${a * 0.5}")
        assertEquals(0, edgeCountWithMinArea(a * 2.0), "…and does not clear a minArea of ${a * 2}")
    }

    /**
     * The routing system emits at most `routeCount` arcs per point per step and picks them by score,
     * so raising `routeCount` can only ever *add* edges to the set the lower setting produced.
     */
    @Test
    fun routeCountIsAMonotoneBudget() {
        fun run(routeCount: Int): Set<Pair<Int, Int>> {
            val state = DynamicPointState(disc(100, 0.5))
            val edges = DynamicEdgeList(state.pointCount, undirected = false)
            val hash = Vec3SpatialHash(0.09)
            stepDynamicGraph(
                1f / 60f, state, edges, hash,
                Vec3SignalRoutingGraphSystem(6, 10f, 1.0, 8.0, routeCount = routeCount, maxNeighborRadius = 0.2),
            )
            return edges.values().map { it.i to it.j }.toSet()
        }
        val one = run(1)
        val three = run(3)
        assertTrue(one.isNotEmpty())
        assertTrue(three.containsAll(one), "route 3 must contain every arc route 1 chose")
        assertTrue(three.size > one.size, "…and strictly more of them: ${three.size} vs ${one.size}")
    }

    /**
     * The tangent-triangulation system fits a local frame and fans the neighbours in angular order,
     * so on a **planar** cloud the neighbour-to-neighbour edges it emits should form a ring around
     * each centre. The measurable consequence: it produces edges between points that are *not* each
     * other's nearest neighbours, unlike a pure kNN pass — a fan is not a star.
     */
    @Test
    fun tangentTriangulationEmitsNeighbourToNeighbourEdges() {
        val state = DynamicPointState(disc(120, 0.5))
        val edges = DynamicEdgeList(state.pointCount)
        val hash = Vec3SpatialHash(0.08)
        stepDynamicGraph(
            1f / 60f, state, edges, hash,
            Vec3LocalTangentTriangulationSystem(6, 10f, 1.0, 8.0, maxNeighborRadius = 0.16),
        )
        assertTrue(edges.values().size > state.pointCount, "a fan emits more than one edge per centre")

        // Every edge is short — the fan is local, which is the property that makes it a triangulation
        // rather than a random pairing.
        val longest = edges.values().maxOf { state.points[it.i].distance(state.points[it.j]).toDouble() }
        assertTrue(longest <= 2.0 * 0.16, "fan edges span at most two neighbourhood radii, found $longest")
    }

    // ==========================================================================================
    // DynamicPointProcess — the half that could not run
    // ==========================================================================================

    /**
     * Regression for the fatal defect. Three processes append to `state.points`, and
     * `DynamicEdgeList.pointCount` used to be a constructor `val` guarded by a hard `require`, so the
     * first birth threw. Each of the three is exercised here, plus the system step that follows it.
     */
    @Test
    fun growingTheCloudDoesNotThrow() {
        // 1. DirectedTipGrowthProcess reinforces parent -> child in the same step it creates the
        //    child, so this used to throw on the very first birth.
        //    A 20-point star: every spoke carries a single weight-2 edge, so every one of the 20
        //    points clears the default coherenceThreshold of 1.25 and spawns.
        fun star(threshold: Double, cap: Int): Triple<Int, Int, DynamicEdgeList> {
            val state = DynamicPointState(ring(20, 0.3))
            val kin = kinetics(state.pointCount)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.12)
            for (i in 1 until 20) edges.reinforce(0, i, 2.0, 10f, 8.0)
            hash.rebuild(state.points)
            DirectedTipGrowthProcess(coherenceThreshold = threshold, maxPointCount = cap)
                .step(1f / 60f, state, kin, edges, hash)
            return Triple(state.pointCount, edges.pointCount, edges)
        }

        val (grown, storeCount, grownEdges) = star(threshold = 1.25, cap = Int.MAX_VALUE)
        assertEquals(40, grown, "all 20 tips of the star clear the threshold")
        assertEquals(40, storeCount, "…and the edge store's index space must have followed them")
        for (child in 20 until 40) {
            assertTrue(grownEdges.incident(child).isNotEmpty(), "child $child must be joined to its parent")
        }
        // Two control arms on the same fixture: an unreachable threshold, and the population cap.
        assertEquals(20, star(threshold = 100.0, cap = Int.MAX_VALUE).first, "nothing is coherent enough")
        assertEquals(25, star(threshold = 1.25, cap = 25).second, "maxPointCount must stop the births")

        // 2 & 3. The two density-seeded processes do not add edges themselves, but they used to
        //    poison the store for the next system step.
        for (process in listOf<DynamicPointProcess>(
            AddNearLowDensityProcess(attemptsPerStep = 3),
            AddNearHighDensityProcess(probeRadius = 0.3, minNeighbors = 2, attemptsPerStep = 3),
        )) {
            val state = DynamicPointState(disc(30, 0.3))
            val kin = kinetics(state.pointCount)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.12)
            hash.rebuild(state.points)
            process.step(1f / 60f, state, kin, edges, hash)
            assertEquals(33, state.pointCount, "${process::class.simpleName} must add 3 points")
            stepDynamicGraph(
                1f / 60f, state, edges, hash,
                Vec3BreathingAnisotropicKnnSystem(4, 10f, 1.0, 8.0, mutualOnly = false, queryRadius = 0.25),
            )
            assertTrue(edges.values().isNotEmpty(), "…and the next system step must be able to use them")
            assertEquals(33, edges.pointCount)
        }
    }

    /**
     * Regression, and the page's headline number. `exampleDynamicMorphogenesisStep` is the file's own
     * "wires a whole frame together" entry point, and it threw `IllegalArgumentException` on the frame
     * of its **first birth** — so it had never once produced a point. On this fixture that frame is 109,
     * which is asserted here, because "it grew" and "it grew on the frame it used to die on" are
     * different claims.
     */
    @Test
    fun morphogenesisStepSurvivesGrowth() {
        val state = DynamicPointState(ring(40, 0.5))
        val kin = kinetics(state.pointCount)
        val edges = DynamicEdgeList(state.pointCount)
        val hash = Vec3SpatialHash(0.12)
        val system = Vec3PersistentProximityHysteresisSystem(
            rEnter = 0.12, rExit = 0.18, ttl = 3f, reinforce = 2.0, maxWeight = 8.0,
        )

        var firstBirth = -1
        for (frame in 0 until 200) {
            exampleDynamicMorphogenesisStep(state, kin, edges, hash, system, maxPointCount = 1200)
            if (firstBirth < 0 && state.pointCount > 40) firstBirth = frame
            // Invariants that must hold on EVERY frame, not just at the end.
            assertEquals(state.pointCount, edges.pointCount, "frame $frame: the index space must track the cloud")
            assertEquals(state.pointCount, kin.alive.size, "frame $frame: kinetics must track the cloud")
            assertTrue(state.pointCount <= 1200, "frame $frame: maxPointCount must cap the population")
        }

        assertEquals(109, firstBirth, "the first birth used to be the frame this threw on")
        for (e in edges.values()) {
            assertTrue(e.i in 0 until state.pointCount && e.j in 0 until state.pointCount, "edge $e dangles")
        }
        assertEquals(state.pointCount, edges.toPointGraph().pointCount)

        // The cap is the only population control this composition has — its prune stage is configured
        // `minDegree = 0`, which cannot mark anything — so it must be doing the work by frame 200.
        assertEquals(1200, state.pointCount, "uncapped, this reaches 3603 points by frame 199")
    }

    /**
     * The measured growth curve, without the cap: pinned at exactly the seed population for 109
     * frames, then compounding. This is the number `DirectedTipGrowthProcess`'s KDoc quotes, and it is
     * the reason the cap exists.
     */
    @Test
    fun uncappedTipGrowthCompoundsAtAboutFivePercentPerFrame() {
        val state = DynamicPointState(ring(40, 0.5))
        val kin = kinetics(state.pointCount)
        val edges = DynamicEdgeList(state.pointCount)
        val hash = Vec3SpatialHash(0.12)
        val system = Vec3PersistentProximityHysteresisSystem(0.12, 0.18, 3f, 2.0, 8.0)

        var at108 = 0
        repeat(160) { frame ->
            exampleDynamicMorphogenesisStep(state, kin, edges, hash, system)
            if (frame == 108) at108 = state.pointCount
        }
        assertEquals(40, at108, "nothing may grow before the edges reach the coherence threshold")
        // 40 * 1.05^51 is about 470; measured 1940 at frame 160, so the rate is comfortably above 1.05
        // and this bound is a floor rather than a fit.
        assertTrue(state.pointCount > 1000, "frame 160 reached only ${state.pointCount} points")
    }

    /**
     * Regression. A prune compacts the cloud, and the snapshot has to describe the *compacted* cloud
     * — a `PointGraph` claiming 10 vertices when 2 survive hands eight phantoms to every traversal
     * downstream.
     */
    @Test
    fun snapshotAfterPruningReportsTheSurvivingPointCount() {
        val state = DynamicPointState(ring(10, 0.3))
        val kin = kinetics(state.pointCount)
        val edges = DynamicEdgeList(state.pointCount)
        val hash = Vec3SpatialHash(0.12)
        hash.rebuild(state.points)
        edges.reinforce(0, 1, 1.0, 5f, 8.0)

        PruneIsolatedPointsProcess(minDegree = 1).step(1f / 60f, state, kin, edges, hash)

        assertEquals(2, state.pointCount, "only the two joined points survive minDegree = 1")
        assertEquals(2, edges.pointCount)
        assertEquals(2, edges.toPointGraph().pointCount, "the snapshot must not report phantom vertices")
        assertEquals(1, edges.toPointGraph().edges.size)

        // The control: minDegree = 0 keeps everyone, so the same call is a no-op.
        val kept = DynamicPointState(ring(10, 0.3))
        val keptKin = kinetics(kept.pointCount)
        val keptEdges = DynamicEdgeList(kept.pointCount)
        hash.rebuild(kept.points)
        PruneIsolatedPointsProcess(minDegree = 0).step(1f / 60f, kept, keptKin, keptEdges, hash)
        assertEquals(10, kept.pointCount)
    }

    /**
     * Regression. The merge used to set `flags or 1` on every edge incident to the absorbed point and
     * then delete exactly those edges, so a "merge" silently disconnected the survivor from
     * everything only the absorbed point had been joined to. Here point 1 is absorbed into point 0
     * while carrying the only link to points 2, 3 and 4.
     */
    @Test
    fun mergingTwoPointsKeepsTheAbsorbedPointsEdges() {
        val state = DynamicPointState(
            mutableListOf(
                Vec3(0f, 0f, 0f), Vec3(0.01f, 0f, 0f),          // 0 and 1: within mergeDistance
                Vec3(0.4f, 0f, 0f), Vec3(0f, 0.4f, 0f), Vec3(0f, 0f, 0.4f),
            ),
        )
        val kin = kinetics(state.pointCount)
        val edges = DynamicEdgeList(state.pointCount)
        val hash = Vec3SpatialHash(0.2)
        hash.rebuild(state.points)
        edges.reinforce(0, 1, 4.0, 10f, 8.0)                    // the merge candidate
        for (far in 2..4) edges.reinforce(1, far, 2.0, 10f, 8.0)  // reachable ONLY through point 1
        assertEquals(1, degreeOf(0, edges), "before: point 0 knows only point 1")

        MergeClosePointsProcess(mergeDistance = 0.05, minCombinedWeight = 1.5).step(1f / 60f, state, kin, edges, hash)

        assertEquals(4, state.pointCount, "one point was absorbed")
        assertEquals(3, degreeOf(0, edges), "the survivor must inherit all three of the absorbed point's links")
        assertTrue(edges.values().all { it.i != it.j }, "no self-loops")
        // The transferred edges are flagged, which is what the flag was always meant to record.
        assertEquals(3, edges.values().count { it.flags and 1 != 0 })

        // The control arm: the same graph with the pair too far apart to merge is left alone.
        val apart = DynamicPointState(
            mutableListOf(Vec3(0f, 0f, 0f), Vec3(0.2f, 0f, 0f), Vec3(0.4f, 0f, 0f), Vec3(0f, 0.4f, 0f), Vec3(0f, 0f, 0.4f)),
        )
        val apartKin = kinetics(apart.pointCount)
        val apartEdges = DynamicEdgeList(apart.pointCount)
        hash.rebuild(apart.points)
        apartEdges.reinforce(0, 1, 4.0, 10f, 8.0)
        for (far in 2..4) apartEdges.reinforce(1, far, 2.0, 10f, 8.0)
        MergeClosePointsProcess(mergeDistance = 0.05, minCombinedWeight = 1.5).step(1f / 60f, apart, apartKin, apartEdges, hash)
        assertEquals(5, apart.pointCount, "nothing within mergeDistance, so nothing merges")
        assertEquals(1, degreeOf(0, apartEdges), "…and point 0 inherits nothing")
    }

    /**
     * Diffusion along the edges is a **conservation law**, and that is an exact invariant rather than
     * a threshold: whatever leaves point `i` arrives at point `j`. The control arm is the same
     * process with `diffusionRate = 0`, under which no resource moves at all.
     */
    @Test
    fun signalDiffusionConservesTotalResourceExactly() {
        fun run(rate: Double): Pair<Double, DoubleArray> {
            val state = DynamicPointState(ring(24, 0.3))
            val kin = kinetics(state.pointCount)
            kin.resource[0] = 100.0
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.12)
            hash.rebuild(state.points)
            for (i in 0 until 24) edges.reinforce(i, (i + 1) % 24, 1.0, 100f, 8.0)
            // chemoGain 0: this test is about the resource field, not about the motion it drives.
            val process = SignalDiffusionAndChemotaxisProcess(diffusionRate = rate, chemoGain = 0.0)
            repeat(200) { process.step(1f / 60f, state, kin, edges, hash) }
            return kin.resource.sum() to kin.resource.toDoubleArray()
        }

        val (total, spread) = run(0.35)
        assertTrue(abs(total - 100.0) < 1e-9, "diffusion must conserve the total exactly, found $total")
        // …and it must actually have spread. Two claims, both structural rather than numerical: the
        // source has given most of it away, and the profile falls off monotonically with hop distance
        // around the ring — which is what makes it diffusion and not leakage.
        assertTrue(spread[0] < 40.0, "the source should have drained to ${spread[0]}")
        assertTrue(spread[12] > 0.0, "even the antipode, 12 hops away, must have received something")
        for (hop in 1..11) {
            assertTrue(
                spread[hop] > spread[hop + 1],
                "resource must fall off with hop distance: r[$hop]=${spread[hop]} !> r[${hop + 1}]=${spread[hop + 1]}",
            )
            assertTrue(abs(spread[hop] - spread[24 - hop]) < 1e-9, "…symmetrically about the source")
        }

        val (controlTotal, controlSpread) = run(0.0)
        assertTrue(abs(controlTotal - 100.0) < 1e-9)
        assertEquals(100.0, controlSpread[0], 1e-12, "with rate 0 nothing may move — the control arm")
        assertEquals(0.0, controlSpread[12], 1e-12)
    }

    /**
     * `SpringMotionProcess` pulls edges toward a rest length, so the honest claim is about the spread
     * of edge lengths: a spring-relaxed graph has a *smaller* coefficient of variation of edge length
     * than the same graph with the springs off. The control arm is `springK = 0`, which leaves the
     * cloud exactly where it started.
     *
     * The rest length is `restLengthScale / weight` — see the KDoc — so the scale here is chosen for
     * the weight the fixture actually carries rather than left at its default.
     */
    @Test
    fun springMotionEvensOutEdgeLengths() {
        fun run(springK: Double): Pair<Double, Double> {
            // A ring with deliberately uneven spacing: every third gap is stretched.
            val points = MutableList(24) { i ->
                val a = 2.0 * PI * (i + if (i % 3 == 0) 0.45 else 0.0) / 24
                Vec3((0.5 * cos(a)).toFloat(), (0.5 * sin(a)).toFloat(), 0f)
            }
            val state = DynamicPointState(points)
            val kin = kinetics(state.pointCount)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.2)
            hash.rebuild(state.points)
            val weight = 4.0
            for (i in 0 until 24) edges.reinforce(i, (i + 1) % 24, weight, 1000f, 8.0)
            fun cv(): Double {
                val lengths = edges.values().map { state.points[it.i].distance(state.points[it.j]).toDouble() }
                val mean = lengths.average()
                return sqrt(lengths.sumOf { (it - mean) * (it - mean) } / lengths.size) / mean
            }
            val before = cv()
            // rest = restLengthScale / weight; the mean gap on this ring is about 0.13.
            val process = SpringMotionProcess(springK = springK, restLengthScale = 0.13 * weight, damping = 2.0, maxSpeed = 0.5)
            repeat(400) { process.step(1f / 60f, state, kin, edges, hash) }
            return before to cv()
        }

        val (before, after) = run(springK = 3.0)
        assertTrue(after < before * 0.5, "springs must even the ring out: cv $before -> $after")

        val (controlBefore, controlAfter) = run(springK = 0.0)
        assertEquals(controlBefore, controlAfter, 1e-9, "with springK = 0 nothing may move — the control arm")
    }

    /**
     * `RelaxToMidpointProcess` is Laplacian smoothing on the current edge set, so its fixed point on
     * a regular ring is the ring itself and its effect on a perturbed ring is to remove the
     * perturbation. The control arm is `gain = 0`.
     */
    @Test
    fun relaxToMidpointRemovesAPerturbationAndLeavesARegularRingAlone() {
        fun run(gain: Double, perturb: Boolean): Double {
            val points = MutableList(32) { i ->
                val a = 2.0 * PI * i / 32
                val r = 0.5 * (if (perturb && i % 4 == 0) 1.35 else 1.0)
                Vec3((r * cos(a)).toFloat(), (r * sin(a)).toFloat(), 0f)
            }
            val state = DynamicPointState(points)
            val kin = kinetics(state.pointCount)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.2)
            hash.rebuild(state.points)
            for (i in 0 until 32) edges.reinforce(i, (i + 1) % 32, 1.0, 1000f, 8.0)
            repeat(600) { RelaxToMidpointProcess(gain = gain).step(1f / 60f, state, kin, edges, hash) }
            // Roughness: mean absolute second difference of the radius around the ring.
            val radii = state.points.map { sqrt((it.x * it.x + it.y * it.y).toDouble()) }
            return (0 until 32).sumOf { i ->
                abs(radii[(i + 31) % 32] - 2 * radii[i] + radii[(i + 1) % 32])
            } / 32.0
        }

        val relaxed = run(gain = 4.0, perturb = true)
        val untouched = run(gain = 0.0, perturb = true)
        assertTrue(relaxed < untouched * 0.25, "smoothing must remove the spikes: $untouched -> $relaxed")
        assertTrue(run(gain = 4.0, perturb = false) < 1e-3, "a regular ring is (near enough) a fixed point")
    }

    /** `JitterProcess` is a deterministic function of the clock, so the same clock gives the same cloud. */
    @Test
    fun jitterIsDeterministicInTheClock() {
        fun run(time: Float, amplitude: Double): List<Vec3> {
            val state = DynamicPointState(ring(10, 0.3), time = time)
            val kin = kinetics(state.pointCount)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.2)
            hash.rebuild(state.points)
            JitterProcess(amplitude = amplitude).step(1f / 60f, state, kin, edges, hash)
            return state.points.map { Vec3(it) }
        }
        assertEquals(run(0.5f, 0.5).map { it.x }, run(0.5f, 0.5).map { it.x })
        assertTrue(run(0.5f, 0.5).zip(run(0.9f, 0.5)).any { (a, b) -> a.distance(b) > 1e-6f }, "…and moves with it")
        // The control arm: amplitude 0 leaves the cloud untouched.
        assertEquals(ring(10, 0.3).map { it.x }, run(0.5f, 0.0).map { it.x })
    }

    /** `DynamicProcessPipeline` runs its stages in order, each seeing the previous stage's cloud. */
    @Test
    fun thePipelineRebuildsTheHashBetweenStages() {
        val state = DynamicPointState(disc(40, 0.3))
        val kin = kinetics(state.pointCount)
        val edges = DynamicEdgeList(state.pointCount)
        val hash = Vec3SpatialHash(0.12)
        // Stage 1 grows the cloud; stage 2 queries the hash. If the hash were not rebuilt between
        // them, stage 2's `sample` would index a point list shorter than the cloud.
        DynamicProcessPipeline(listOf(AddNearLowDensityProcess(attemptsPerStep = 4), JitterProcess(amplitude = 0.01)))
            .step(1f / 60f, state, kin, edges, hash)
        assertEquals(44, state.pointCount)
        assertEquals(44, kin.velocity.size)
    }

    // ==========================================================================================
    // The shipped examples in afield.examples.stdlib.DynamicGraphSystemsExample
    // ==========================================================================================

    /** The three shipped example functions run, and produce a graph that is actually connected up. */
    @Test
    fun theShippedExamplesProduceUsableGraphs() {
        val points = disc(120, 0.45).toList()
        val pipeline = afield.examples.stdlib.exampleDynamicGraphPipeline(points)
        assertEquals(120, pipeline.pointCount)
        assertTrue(pipeline.edges.size > 40, "the flowline pipeline produced only ${pipeline.edges.size} edges")

        val switched = afield.examples.stdlib.exampleSystemSwitching(points)
        assertTrue(switched.edges.size > pipeline.edges.size, "five systems in turn should build more than one")

        val cover = afield.examples.stdlib.exampleDynamicGraphToPathCover(points, anchor = 0)
        assertTrue(cover.paths.isNotEmpty(), "the snapshot must be usable by the static path utilities")
    }

    // ==========================================================================================
    // dynamic_graph_field.kt — the raster the page's figures are made of
    // ==========================================================================================

    /**
     * A figure nobody has looked at pixel by pixel is decoration. Two points and one edge, in a window
     * chosen so both endpoints land on exactly known raster coordinates: the endpoints must be lit at
     * full intensity, the midpoint at the edge's own `weight / maxWeight`, and a pixel off the line at
     * nothing at all.
     */
    @Test
    fun theRasterPutsPixelsWhereTheGeometrySaysAndAtTheWeightItClaims() {
        val points = listOf(Vec3(-1f, 0f, 0f), Vec3(1f, 0f, 0f))
        val edges = DynamicEdgeList(2)
        edges.reinforce(0, 1, 2.0, 10f, maxWeight = 8.0)

        val window = doubleArrayOf(-2.0, -2.0, 2.0, 2.0)
        val raster = dynamicGraphImg(
            points, edges.values(), window, width = 101, height = 101,
            maxWeight = 8.0, pointRadius = 0, minEdgeIntensity = 0.0f,
        )
        assertEquals(0, raster.clipped, "nothing is outside a window twice the cloud's size")
        assertEquals(2.0, raster.heaviestWeight, 1e-12)

        val img = raster.img
        // The window spans -2..2 over 101 pixels, so world x maps to pixel (x + 2) * 25.
        assertEquals(1f, img.at(25, 50), "point 0 at world (-1, 0) is pixel (25, 50), at full intensity")
        assertEquals(1f, img.at(75, 50), "point 1 at world (1, 0) is pixel (75, 50)")
        // The midpoint of the edge carries weight / maxWeight = 2 / 8.
        assertEquals(0.25f, img.at(50, 50), 1e-6f, "the edge's own brightness is its weight over maxWeight")
        assertEquals(0f, img.at(50, 40), "…and ten pixels off the line, nothing")

        // The control arm: an edge at the ceiling is drawn at 1.0, an edge at nothing at 0.0. If
        // brightness were normalised per frame these two would be indistinguishable.
        val heavy = DynamicEdgeList(2).also { it.reinforce(0, 1, 8.0, 10f, 8.0) }
        assertEquals(
            1f,
            dynamicGraphImg(points, heavy.values(), window, 101, 101, maxWeight = 8.0, pointRadius = 0).img.at(50, 50),
        )
        val faint = DynamicEdgeList(2).also { it.reinforce(0, 1, 1e-9, 10f, 8.0) }
        assertEquals(
            0f,
            dynamicGraphImg(
                points, faint.values(), window, 101, 101,
                maxWeight = 8.0, pointRadius = 0, minEdgeIntensity = 0f,
            ).img.at(50, 50),
            1e-6f,
        )
    }

    /** Clipping is reported rather than silently cropping the subject. */
    @Test
    fun theRasterReportsWhatItCouldNotFit() {
        val points = listOf(Vec3(0f, 0f, 0f), Vec3(50f, 0f, 0f), Vec3(-50f, 0f, 0f))
        val raster = dynamicGraphImg(points, emptyList(), doubleArrayOf(-1.0, -1.0, 1.0, 1.0), 64, 64)
        assertEquals(2, raster.clipped, "two of three points are outside the window")
        assertTrue(raster.img.max() > 0f, "…and the one inside it is still drawn")
    }

    /** `dynamicGraphWindow` is square and encloses the cloud with room to spare. */
    @Test
    fun theWindowIsSquareAndEnclosesTheCloud() {
        val points = disc(50, 0.4)
        val w = dynamicGraphWindow(points, padFraction = 0.2)
        assertEquals(w[2] - w[0], w[3] - w[1], 1e-9, "the window must be square")
        for (p in points) {
            assertTrue(p.x > w[0] && p.x < w[2] && p.y > w[1] && p.y < w[3], "$p is outside $${w.toList()}")
        }
        assertEquals(0, dynamicGraphImg(points, emptyList(), w, 128, 128).clipped)
    }

    /** The caption line reports the state, including the case a figure must not hide: isolation. */
    @Test
    fun theSummaryLineNamesTheStateItIsDrawing() {
        val state = DynamicPointState(ring(6, 0.3), time = 1.5f)
        val edges = DynamicEdgeList(6)
        edges.reinforce(0, 1, 2.0, 10f, 8.0)
        val summary = dynamicGraphSummary(state, edges)
        assertTrue(summary.startsWith("6 pts, 1 edges"), summary)
        assertTrue(summary.contains("4 isolated"), "four of the six points touch no edge: $summary")
        assertTrue(summary.contains("t=1.50s"), summary)
    }

    // ==========================================================================================
    // The numbers public/docs/dynamic_graph_systems_examples.md prints
    //
    // Each of these reproduces one of the page's fence bodies exactly and asserts the figure it
    // publishes. The page quotes them in prose and burns them into its panel labels, so without
    // these a parameter change would silently make the page a work of fiction.
    // ==========================================================================================

    /** Figure 1's table: edges, triangle-closure fraction and peak weight for all six systems. */
    @Test
    fun pageFigureOneSixSystemsTable() {
        val cloud = disc(220, 0.5)
        val ceiling = 4.0
        fun measure(system: DynamicGraphSystem): Triple<Int, Int, Double> {
            val state = DynamicPointState(cloud.map { Vec3(it) }.toMutableList())
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.06)
            repeat(20) { stepDynamicGraph(1f / 60f, state, edges, hash, system) }
            val adjacency = Array(state.pointCount) { HashSet<Int>() }
            for (e in edges.values()) { adjacency[e.i].add(e.j); adjacency[e.j].add(e.i) }
            val closed = edges.values().count { e -> adjacency[e.i].any { it != e.j && adjacency[e.j].contains(it) } }
            val percent = Math.round(100.0 * closed / edges.values().size).toInt()
            return Triple(edges.values().size, percent, edges.values().maxOf { it.weight })
        }

        // "534e 88%tri" etc. -- the strings the figure's own panel labels carry.
        val knn = measure(Vec3BreathingAnisotropicKnnSystem(5, 10f, 4.0, ceiling, true, 0.12))
        assertEquals(534, knn.first); assertEquals(88, knn.second)
        val flowline = measure(Vec3FlowlineAdvectionGraphSystem(0.10, 0.08, 10f, 4.0, ceiling))
        assertEquals(158, flowline.first)
        assertEquals(0, flowline.second, "flowline advection is a functional map: exactly 0 % of its edges close")
        val fan = measure(Vec3LocalTangentTriangulationSystem(6, 10f, 4.0, ceiling, 0.10))
        assertEquals(715, fan.first); assertEquals(100, fan.second, "the tangent fan builds faces")
        val hyst = measure(Vec3PersistentProximityHysteresisSystem(0.055, 0.075, 10f, 4.0, ceiling))
        assertEquals(153, hyst.first); assertEquals(2, hyst.second)
        val routing = measure(Vec3SignalRoutingGraphSystem(6, 10f, 1.0, ceiling, 1, 0.10))
        assertEquals(260, routing.first); assertEquals(2, routing.second)
        val alpha = measure(Vec3AlphaEventHypergraphCollapseSystem(6, 0.075, 10f, 4.0, ceiling, 0.09, 1e-4))
        assertEquals(351, alpha.first)
        assertEquals(100, alpha.second, "the alpha-event system builds faces")

        // The page's warning box: the alpha system's increment is scaled by triangle area, so its
        // weights land three orders of magnitude below everyone else's on the SAME reinforce and
        // ceiling. That is the reason its panel is faint on a fixed brightness scale.
        assertEquals(4.0, knn.third, 1e-12, "kNN saturates at the shared ceiling")
        assertEquals(4.0, fan.third, 1e-12, "so does the tangent fan")
        assertTrue(abs(alpha.third - 0.0137) < 0.0002, "alpha-event peak weight was ${alpha.third}, page says 0.0137")
        assertTrue(alpha.third < 0.005 * ceiling, "…which is under 0.5 % of the ceiling")
    }

    /** Figure 3's table: how much work each arm does to hold a graph over a wobbling cloud. */
    @Test
    fun pageFigureThreeHysteresisFlickerCounts() {
        fun flicker(rExit: Double): Triple<Int, Int, Int> {
            val n = 200
            val base = disc(n, 0.45)
            val state = DynamicPointState(base.map { Vec3(it) }.toMutableList())
            val edges = DynamicEdgeList(n)
            val hash = Vec3SpatialHash(0.06)
            val system = Vec3PersistentProximityHysteresisSystem(0.055, rExit, 0.35f, 3.0, 3.0)
            var appeared = 0
            var vanished = 0
            var present = HashSet<Pair<Int, Int>>()
            repeat(150) { frame ->
                for (i in 0 until n) {
                    val phase = 0.31 * i + 0.16 * frame
                    state.points[i] = Vec3(base[i]).add(vec(0.012 * cos(phase), 0.012 * sin(1.7 * phase), 0.0))
                }
                stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.9)
                val now = edges.values().map { it.i to it.j }.toHashSet()
                appeared += (now - present).size
                vanished += (present - now).size
                present = now
            }
            return Triple(appeared, vanished, present.size)
        }

        assertEquals(Triple(553, 81, 472), flicker(rExit = 0.075), "the hysteresis arm, as the page's table states")
        assertEquals(Triple(996, 637, 359), flicker(rExit = 0.055), "the rExit == rEnter control arm")
    }

    /**
     * Figure 6, which is the page's strongest claim: a weight threshold recovers the co-moving groups
     * out of a graph that has only ever seen positions and a clock.
     */
    @Test
    fun pageFigureSixThresholdRecoversTheCoMovingGroups() {
        val nBg = 240
        val perCluster = 22
        val n = nBg + 3 * perCluster
        val background = disc(nBg, 0.50)
        val blobs = List(3) { disc(perCluster, 0.070) }
        val starts = listOf(Vec3(-0.44f, -0.20f, 0f), Vec3(-0.30f, 0.40f, 0f), Vec3(0.34f, -0.44f, 0f))
        val velocities = listOf(Vec3(0.20f, 0.07f, 0f), Vec3(0.16f, -0.19f, 0f), Vec3(-0.12f, 0.20f, 0f))
        val group = IntArray(n) { if (it < nBg) -1 else (it - nBg) / perCluster }

        val points = MutableList(n) { Vec3(0f, 0f, 0f) }
        for (i in 0 until nBg) points[i] = Vec3(background[i])
        val state = DynamicPointState(points)
        val edges = DynamicEdgeList(n)
        val hash = Vec3SpatialHash(0.055)
        val system = Vec3PersistentProximityHysteresisSystem(0.062, 0.075, 0.4f, 2.5, 3.0)
        repeat(240) { f ->
            val t = f / 60.0
            for (c in 0 until 3) {
                val centre = Vec3(starts[c]).add(Vec3(velocities[c]).mul(t.toFloat()))
                for (k in 0 until perCluster) {
                    state.points[nBg + c * perCluster + k] = Vec3(blobs[c][k]).add(centre)
                }
            }
            stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.30)
        }

        fun componentSizes(graph: PointGraph): List<Int> {
            val parent = IntArray(graph.pointCount) { it }
            fun find(a: Int): Int {
                var x = a
                while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
                return x
            }
            for (e in graph.edges) { val ra = find(e.i); val rb = find(e.j); if (ra != rb) parent[ra] = rb }
            val sizes = HashMap<Int, Int>()
            for (v in 0 until graph.pointCount) sizes.merge(find(v), 1) { a, b -> a + b }
            return sizes.values.sortedDescending()
        }

        fun row(cut: Double): Triple<Int, Int, List<Int>> {
            val g = edges.toPointGraph(minWeight = cut)
            return Triple(g.edges.size, g.edges.count { group[it.i] != group[it.j] }, componentSizes(g).filter { it > 1 })
        }

        // The page's table, row for row.
        assertEquals(Triple(1313, 450, listOf(306)), row(0.0), "minWeight 0: everything welded into one blob")
        assertEquals(Triple(1027, 190, listOf(306)), row(0.5), "minWeight 0.5: still one blob")
        assertEquals(
            Triple(791, 0, listOf(240, 22, 22, 22)), row(1.2),
            "minWeight 1.2 must recover exactly the background mesh and the three clusters",
        )
        assertEquals(Triple(664, 0, listOf(240, 22, 22, 22)), row(2.0))

        // The control arm for the whole figure: the same threshold on a graph built from a cloud that
        // never moved has nothing to separate, because no edge is transient. (The clusters are pinned
        // at their starting positions; everything else is identical.)
        val stillPoints = MutableList(n) { Vec3(0f, 0f, 0f) }
        for (i in 0 until nBg) stillPoints[i] = Vec3(background[i])
        for (c in 0 until 3) for (k in 0 until perCluster) {
            stillPoints[nBg + c * perCluster + k] = Vec3(blobs[c][k]).add(starts[c])
        }
        val still = DynamicPointState(stillPoints)
        val stillEdges = DynamicEdgeList(n)
        val stillHash = Vec3SpatialHash(0.055)
        val stillSystem = Vec3PersistentProximityHysteresisSystem(0.062, 0.075, 0.4f, 2.5, 3.0)
        repeat(240) { stepDynamicGraphWithExponentialDecay(1f / 60f, still, stillEdges, stillHash, stillSystem, 0.30) }
        val lowCut = stillEdges.toPointGraph(minWeight = 0.0).edges.size
        val highCut = stillEdges.toPointGraph(minWeight = 1.2).edges.size
        assertTrue(
            highCut > 0.9 * lowCut,
            "on a static cloud the threshold has nothing to remove: $lowCut -> $highCut. " +
                "That is why the figure has to move something.",
        )
    }

    /** Figure 5's table: the growing pipeline against the same pipeline with tip growth removed. */
    @Test
    fun pageFigureFiveMorphogenesisCounts() {
        val seedRing = MutableList(64) { i ->
            val a = 2.0 * PI * i / 64
            Vec3((0.42 * cos(a)).toFloat(), (0.42 * sin(a)).toFloat(), 0f)
        }
        val ceiling = 3.0
        fun run(grow: Boolean): Pair<Map<Int, Pair<Int, Int>>, Int> {
            val state = DynamicPointState(seedRing.map { Vec3(it) }.toMutableList())
            val kin = kinetics(state.pointCount)
            for (i in 0 until 16) kin.resource[i] = 6.0
            val stages = mutableListOf<DynamicPointProcess>(
                SignalDiffusionAndChemotaxisProcess(diffusionRate = 0.5, chemoGain = 0.35, maxSpeed = 0.30),
                SpringMotionProcess(springK = 1.2, damping = 3.0, restLengthScale = 0.075, maxSpeed = 0.30),
            )
            if (grow) stages += DirectedTipGrowthProcess(
                coherenceThreshold = 1.6, spawnDistance = 0.035, localDensityRadius = 0.05,
                localDensityMax = 6, childEdgeWeight = 1.5, childEdgeTtl = 1.5f, maxWeight = ceiling,
                maxPointCount = 900,
            )
            stages += MergeClosePointsProcess(mergeDistance = 0.012, minCombinedWeight = 2.0)
            stages += RelaxToMidpointProcess(gain = 1.5)
            stages += JitterProcess(amplitude = 0.04)
            val pipeline = DynamicProcessPipeline(stages)
            val edges = DynamicEdgeList(state.pointCount)
            val hash = Vec3SpatialHash(0.05)
            val system = Vec3PersistentProximityHysteresisSystem(0.05, 0.07, 0.8f, 3.0, ceiling)
            val samples = HashMap<Int, Pair<Int, Int>>()
            var cappedAt = -1
            for (s in 1..560) {
                stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.6)
                pipeline.step(1f / 60f, state, kin, edges, hash)
                if (cappedAt < 0 && state.pointCount >= 900) cappedAt = s
                if (s % 80 == 0) samples[s] = state.pointCount to edges.values().size
            }
            return samples to cappedAt
        }

        val (grown, cappedAt) = run(grow = true)
        assertEquals(79, cappedAt, "the page says the 900-point cap is reached at step 79")
        assertEquals(900 to 9065, grown[80], "step 80")
        assertEquals(900 to 2673, grown[160], "step 160")
        assertEquals(900 to 1344, grown[240], "step 240 -- the springs have pulled the burst apart")
        assertEquals(899 to 2376, grown[400], "step 400")
        assertEquals(899 to 2950, grown[560], "step 560")

        val (flat, neverCapped) = run(grow = false)
        assertEquals(-1, neverCapped, "the control arm must never approach the cap")
        assertEquals(64 to 76, flat[80], "step 80")
        assertEquals(64 to 55, flat[160], "step 160")
        assertEquals(64 to 61, flat[240], "step 240")
        assertEquals(62 to 94, flat[400], "step 400 -- the merges have begun")
        assertEquals(
            56 to 86, flat[560],
            "step 560: the control arm ends at 56, NOT 64 -- MergeClosePointsProcess is in both pipelines",
        )
    }
}
