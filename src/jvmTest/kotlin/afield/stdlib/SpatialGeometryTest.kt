package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The plane-geometry family: marching squares in `contours.kt`, the JTS-backed Delaunay/Voronoi
 * corner of `graph_connectivity.kt`, the weighted (Apollonius) diagram in `dirichlet.kt`, the
 * partitioners in `clustering.kt`, and the `FLine` bridges in `spatial_field.kt`.
 *
 * Written alongside `public/docs/spatial_geometry_examples.md` — every number the page states about
 * these functions is asserted here, so the page cannot rot silently. Two of the tests are
 * regressions for defects the page's own research turned up:
 *
 *  - [contourThroughAGridCornerSurvives] — the marcher used to drop *every* crossing out of a corner
 *    whose sample was exactly `iso`, so `f(x, y) = x` at `iso = 0` returned nothing and the unit
 *    circle came back severed into four arcs.
 *  - [kMedoidsRunsAtAllAndFindsPlantedClusters] — `vec2KMedoids` threw
 *    `IllegalArgumentException: Too large maxNeighbor: 100` for every sample count below ~100.
 */
class SpatialGeometryTest {

    // ==========================================================================================
    // contours.kt — marching squares
    // ==========================================================================================

    @Test
    fun contourThroughAGridCornerSurvives() {
        // f(x, y) = x at iso 0. With an even cell count the middle column of samples is exactly 0,
        // which used to suppress the crossing on both edges out of every one of those corners and
        // return an empty list.
        val plane: ScalarField2D = { x, _ -> x }
        for (nx in listOf(2, 4, 8)) {
            val contours = generateIsoContours(plane, -1.0, 1.0, -1.0, 1.0, nx, 4, 0.0)
            assertEquals(1, contours.size, "f(x,y)=x at nx=$nx should give exactly one contour")
            assertEquals(5, contours[0].size, "…spanning all 5 rows of samples")
            // The recovered line sits within one thousandth of a cell of x = 0 — the deliberate nudge.
            val cell = 2.0 / nx
            contours[0].forEach { p ->
                assertTrue(
                    abs(p.x.toDouble()) <= cell * 1.01e-3,
                    "crossing at x=${p.x} should be within 1e-3 of a cell ($cell) of zero",
                )
            }
        }

        // The unit circle sampled over [-2, 2] passes exactly through grid corners at (+-1, 0) and
        // (0, +-1) when nx = 16. It used to come back as four disconnected 6-point arcs.
        val circle: ScalarField2D = { x, y -> hypot(x, y) - 1.0 }
        val severed = generateIsoContours(circle, -2.0, 2.0, -2.0, 2.0, 16, 16, 0.0)
        assertEquals(1, severed.size, "the grid-aligned unit circle should be one loop, not four arcs")
        assertEquals(28, severed[0].size)

        // …and at a coarseness where every sampled crossing is exactly on a corner, we still get a
        // (crude) loop rather than nothing at all.
        val crude = generateIsoContours(circle, -2.0, 2.0, -2.0, 2.0, 4, 4, 0.0)
        assertEquals(1, crude.size)
        assertEquals(4, crude[0].size)
    }

    @Test
    fun contourPerimeterConvergesOnTheCircle() {
        // Off-grid on purpose: this is the case that already worked, and it has to keep working.
        val circle: ScalarField2D = { x, y -> hypot(x, y) - 1.0 }
        var previousError = Double.MAX_VALUE
        for (nx in listOf(16, 32, 64, 128)) {
            val contours = generateIsoContours(circle, -2.03, 2.07, -2.011, 2.09, nx, nx, 0.0)
            assertEquals(1, contours.size, "one loop at nx=$nx")
            val loop = contours[0]
            var perimeter = 0.0
            for (i in loop.indices) {
                val a = loop[i]
                val b = loop[(i + 1) % loop.size]
                perimeter += hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            }
            val error = abs(perimeter - 2.0 * PI)
            assertTrue(
                error < previousError,
                "perimeter error should shrink with resolution: at nx=$nx it is $error, was $previousError",
            )
            previousError = error
        }
        assertTrue(previousError < 2e-3, "at nx=128 the perimeter should be within 2e-3 of 2pi, was off by $previousError")
    }

    @Test
    fun theSaddleCellSplitsTheXIntoTwoBranchesOnTheZeroSet() {
        // f = x*y at iso 0 is the two axes crossing. Marching squares cannot draw an X, so it must
        // give two branches, and every point on them must really be on the zero set.
        val saddle: ScalarField2D = { x, y -> x * y }
        val contours = generateIsoContours(saddle, -1.05, 1.03, -1.02, 1.07, 8, 8, 0.0)
        assertEquals(2, contours.size)
        contours.flatten().forEach { p ->
            assertTrue(
                abs(p.x.toDouble() * p.y.toDouble()) < 0.05,
                "(${p.x}, ${p.y}) claims x*y = 0 but is ${p.x.toDouble() * p.y.toDouble()}",
            )
        }
    }

    /**
     * Which pairing the marcher chose in the one saddle cell of `f = x*y`, read off the output.
     *
     * The two crossings that share a corner are joined, so the answer is visible in *which elbow the
     * far-left end of the x-axis ends up on*. Pairing P isolates the top-left and bottom-right
     * corners, so the polyline containing the leftmost point also contains the top of the y-axis and
     * its y-extent is positive. Pairing Q isolates bottom-left and top-right, so that same polyline
     * runs *down* instead.
     */
    private fun saddlePairingOf(contours: List<List<Vec2>>): String {
        val branch = contours.minByOrNull { c -> c.minOf { it.x } }!!
        val top = branch.maxOf { it.y.toDouble() }
        val bottom = branch.minOf { it.y.toDouble() }
        return if (top > -bottom) "P" else "Q"
    }

    @Test
    fun theSaddleTieIsBrokenTheSameWayAtEveryResolution() {
        // A field that is *itself* bilinear makes the asymptotic decider's numerator
        // (s0*s2 - s1*s3) exactly zero: the saddle sits precisely on iso, the level set is an X
        // meeting at a point, and both pairings are equally correct. That is not an exotic case, it
        // is every bilinear field — and in doubles the numerator comes out as +-1e-20 noise. Before
        // SADDLE_TIE_RELATIVE_EPSILON the pairing therefore flipped with resolution:
        //
        //   nx =   4  numerator = -2.71e-20  Q        nx =  32  numerator =  0.00e+00  P
        //   nx =   5  numerator =  0.00e+00  P        nx =  64  numerator = +6.62e-24  P
        //   nx =   8  numerator = -1.69e-21  Q        nx = 128  numerator = -8.27e-25  Q
        //
        // …moving two segments by a full cell each time it flipped. The tie must be settled by the
        // code, not by which way a multiplication rounded.
        val saddle: ScalarField2D = { x, y -> x * y }
        val pairings = listOf(4, 5, 8, 9, 16, 32, 64, 128).associateWith { n ->
            val contours = generateIsoContours(saddle, -1.05, 1.03, -1.02, 1.07, n, n, 0.0)
            assertEquals(2, contours.size, "f=x*y should give two branches at nx=$n")
            saddlePairingOf(contours)
        }
        assertEquals(
            setOf("P"), pairings.values.toSet(),
            "the tie must resolve to the documented pairing P at every resolution, got $pairings",
        )
    }

    @Test
    fun theSaddleDeciderReadsTheCornerMagnitudesNotTheirSigns() {
        // A single cell whose four corner values are prescribed, so the decider is the only thing
        // under test. Corners: v0 bottom-left, v1 bottom-right, v2 top-right, v3 top-left.
        fun oneCell(v0: Double, v1: Double, v2: Double, v3: Double): List<List<Vec2>> {
            val f: ScalarField2D = { x, y ->
                if (x < 0.5) (if (y < 0.5) v0 else v3) else (if (y < 0.5) v1 else v2)
            }
            return generateIsoContours(f, 0.0, 1.0, 0.0, 1.0, 1, 1, 0.0)
        }

        fun elbowCorners(contours: List<List<Vec2>>): Set<Pair<Int, Int>> {
            assertEquals(2, contours.size, "a saddle cell emits two segments")
            return contours.map { c ->
                assertEquals(2, c.size)
                // Each 2-point segment cuts one corner off the unit cell; name that corner.
                val cx = c.sumOf { it.x.toDouble() } / 2.0
                val cy = c.sumOf { it.y.toDouble() } / 2.0
                (if (cx > 0.5) 1 else 0) to (if (cy > 0.5) 1 else 0)
            }.toSet()
        }

        // The saddle value is (s0*s2 - s1*s3)/(s0+s2-s1-s3) = (9-1)/(-8) = -1, i.e. on the same side
        // as the diagonal v0/v2, so v0 and v2 join through the centre and the OTHER two corners —
        // bottom-right and top-left — are the ones cut off.
        assertEquals(
            setOf(1 to 0, 0 to 1),
            elbowCorners(oneCell(-3.0, 1.0, -3.0, 1.0)),
            "the decider should isolate the two shallow (+1) corners",
        )

        // Keep the sign pattern and swap which diagonal is the DEEP one: (-1, +3, -1, +3). The same
        // corners are inside as before, but the saddle is now (1-9)/(-8) = +1, so it is the *outside*
        // that joins through the centre and the cut corners must move to bottom-left and top-right.
        //
        // This is the pair that shows the decider is reading the corner *magnitudes*, not their signs
        // — an implementation that looked only at which corners are inside would give the same answer
        // for both, and the pre-decider marcher, which always joined (bottom, right) + (top, left),
        // gives the same answer for both by construction and so draws two long diagonals here.
        assertEquals(
            setOf(0 to 0, 1 to 1),
            elbowCorners(oneCell(-1.0, 3.0, -1.0, 3.0)),
            "moving the deep diagonal must move the pairing",
        )

        // …and the segments really are short corner elbows in both cases, not diagonals. A diagonal
        // pairing would give length ~1.06 on the unit cell; a corner elbow gives ~0.354.
        listOf(oneCell(-3.0, 1.0, -3.0, 1.0), oneCell(-1.0, 3.0, -1.0, 3.0)).forEach { contours ->
            contours.forEach { c ->
                val len = hypot((c[0].x - c[1].x).toDouble(), (c[0].y - c[1].y).toDouble())
                assertTrue(len < 0.6, "expected a corner elbow, got a segment of length $len")
            }
        }

        // The invariant that pins it from the other side, and the one I got wrong first: negating
        // EVERY value must leave the pairing exactly where it was. The level set {f = 0} and
        // {-f = 0} are the same set of points, so any marcher that moved the crossing would be
        // reporting a different curve for the same curve. It works out because the two flips cancel:
        // negation swaps which corners are inside AND flips the sign of the saddle value, and the
        // decider compares the two.
        listOf(
            listOf(-3.0, 1.0, -3.0, 1.0),
            listOf(-1.0, 3.0, -1.0, 3.0),
            listOf(-2.0, 5.0, -0.5, 1.0),
        ).forEach { v ->
            assertEquals(
                elbowCorners(oneCell(v[0], v[1], v[2], v[3])),
                elbowCorners(oneCell(-v[0], -v[1], -v[2], -v[3])),
                "negating every corner value must not move the crossing: $v",
            )
        }
    }

    @Test
    fun contouringADegenerateFieldMatchesTheGenericLimit() {
        // *The* argument that the strictly-below convention is right, rather than merely different.
        // `inside(v) = v < iso` is exactly the limit of contouring the same field at `iso - delta` as
        // delta shrinks to zero, so the answer on a degenerate field (one with a sample landing
        // exactly on iso) must equal the answer on an infinitesimally perturbed one — which is how
        // every degenerate case in computational geometry is defined. The old three-way
        // classification failed this badly: for f = x it returned nothing at all while the perturbed
        // field returned a line.
        val cases = listOf<Triple<String, ScalarField2D, DoubleArray>>(
            Triple("plane f=x", { x, _ -> x }, doubleArrayOf(-1.0, 1.0, -1.0, 1.0)),
            Triple("circle on the grid", { x, y -> hypot(x, y) - 1.0 }, doubleArrayOf(-2.0, 2.0, -2.0, 2.0)),
            Triple("saddle on the grid", { x, y -> x * y }, doubleArrayOf(-1.0, 1.0, -1.0, 1.0)),
            Triple("diamond |x|+|y|-1", { x, y -> abs(x) + abs(y) - 1.0 }, doubleArrayOf(-2.0, 2.0, -2.0, 2.0)),
        )
        for ((name, field, box) in cases) for (n in listOf(4, 8, 16, 32)) {
            val degenerate = generateIsoContours(field, box[0], box[1], box[2], box[3], n, n, 0.0)
            val perturbed = generateIsoContours(
                { x, y -> field(x, y) + 1e-9 }, box[0], box[1], box[2], box[3], n, n, 0.0,
            )
            assertEquals(
                perturbed.map { it.size }.sorted(), degenerate.map { it.size }.sorted(),
                "$name at nx=$n: contouring the degenerate field must match the generic limit",
            )
            assertTrue(degenerate.isNotEmpty(), "$name at nx=$n should find its level set")
        }
    }

    @Test
    fun aFieldThatOnlyTouchesTheIsoLevelStillYieldsNoContour() {
        // The guard against over-correcting. "Recover the contour that touches a grid corner" must
        // NOT become "invent a contour wherever the field grazes iso". Relaxing the edge test to a
        // non-strict sign change — the obvious wrong fix — would light up every cell of the first
        // case and ring the origin in the second.
        val flat: ScalarField2D = { _, _ -> 0.0 }
        assertEquals(
            emptyList(), generateIsoContours(flat, -1.0, 1.0, -1.0, 1.0, 16, 16, 0.0),
            "a field identically equal to iso has an empty sublevel set and no boundary",
        )

        // f = x^2 + y^2 touches iso 0 at exactly one point, the origin, which is a grid sample here.
        // {f < 0} is empty, so there is no boundary to draw.
        val bowl: ScalarField2D = { x, y -> x * x + y * y }
        assertEquals(
            emptyList(), generateIsoContours(bowl, -1.0, 1.0, -1.0, 1.0, 8, 8, 0.0),
            "a field that only grazes iso from above must not produce a contour",
        )

        // And a level nobody's samples reach at all.
        assertEquals(emptyList(), generateIsoContours(bowl, -1.0, 1.0, -1.0, 1.0, 8, 8, -1.0))
    }

    @Test
    fun theCrossingClampMovesNothingByMoreThanAThousandthOfACell() {
        // Provenance for the one number in the marcher's risk statement. ISO_CROSSING_EPSILON pushes
        // a crossing off a cell corner, and it can move a crossing by *at most* 1e-3 of the cell
        // edge it lies on — analytically, because it is a coerceIn on the interpolation parameter.
        // Two halves: a generic crossing must be untouched, and the worst case must attain the bound
        // and not exceed it.

        // t = 0.5 exactly: the crossing must land on the cell midpoint, unclamped.
        val plane: ScalarField2D = { x, _ -> x }
        val mid = generateIsoContours(plane, 0.0, 4.0, 0.0, 1.0, 4, 1, 1.5).single()
        mid.forEach { p ->
            assertEquals(1.5, p.x.toDouble(), 1e-6, "a crossing at t=0.5 must not be nudged")
        }

        // t = 1 exactly, on a 1-unit cell: displaced by the epsilon and no more — plus one Float
        // rounding, because a marched point is a `Vec2` and therefore single precision. Nothing about
        // the marcher's output is meaningful below about 1e-7 of the coordinate's own magnitude, and
        // the clamp bound has to be stated on top of that rather than instead of it.
        val onGrid = generateIsoContours(plane, 0.0, 4.0, 0.0, 1.0, 4, 1, 2.0).single()
        onGrid.forEach { p ->
            val drift = 2.0 - p.x.toDouble()
            val bound = 1.0e-3 * 1.0 + Math.ulp(2.0f).toDouble()
            assertTrue(drift > 0.0, "the recovered contour sits on the inside of an exactly-on-iso level")
            assertTrue(drift <= bound, "drift $drift must not exceed $bound on a 1.0-wide cell")
            assertTrue(drift > 0.9e-3, "…and it should actually attain the bound, not sit well under it")
        }

        // The same bound scaled: a 0.01-wide cell displaces by at most 1e-5. The clamp is a fraction
        // of the CELL, so the drift shrinks with resolution — which is why quoting it as a fraction of
        // the domain (as "7e-6" once was here) says nothing without also quoting nx.
        val fine = generateIsoContours(plane, 0.0, 1.0, 0.0, 0.05, 100, 1, 0.5).single()
        fine.forEach { p ->
            val drift = abs(0.5 - p.x.toDouble())
            val bound = 1.0e-3 * 0.01 + Math.ulp(0.5f).toDouble()
            assertTrue(drift <= bound, "drift $drift must scale with the cell, not the domain (bound $bound)")
        }
    }

    @Test
    fun theDeciderIsAPureFunctionOfTheCellCorners() {
        // Two cells that share an edge must agree about the crossing on it, or the stitcher cannot
        // join them. Contour a field with plenty of saddles and check that the result is a small
        // number of long curves rather than a heap of two-point fragments.
        val ripple: ScalarField2D = { x, y -> kotlin.math.sin(x * 3.0) * kotlin.math.cos(y * 3.0) }
        val contours = generateIsoContours(ripple, -2.0, 2.0, -2.0, 2.0, 40, 40, 0.0)
        assertTrue(contours.isNotEmpty())
        val fragments = contours.count { it.size <= 2 }
        assertEquals(0, fragments, "no contour should be a lone 2-point fragment; got $fragments of ${contours.size}")
        val meanLength = contours.sumOf { it.size }.toDouble() / contours.size
        assertTrue(meanLength > 10.0, "stitched contours should average >10 points, got $meanLength")
    }

    @Test
    fun isoContourClosedDistinguishesLoopsFromRunsOffTheEdge() {
        val circle: ScalarField2D = { x, y -> hypot(x, y) - 1.0 }
        // A window that contains the whole circle: the contour is a loop.
        val loop = generateIsoContours(circle, -2.03, 2.07, -2.011, 2.09, 32, 32, 0.0).single()
        assertTrue(isoContourClosed(loop, -2.03, 2.07, -2.011, 2.09, boundaryEpsilon = 0.15))

        // A window that cuts the circle: the contour ends on the window and is open.
        val cut = generateIsoContours(circle, -0.5, 2.07, -2.011, 2.09, 32, 32, 0.0)
        assertTrue(cut.isNotEmpty())
        cut.forEach { contour ->
            assertTrue(
                !isoContourClosed(contour, -0.5, 2.07, -2.011, 2.09, boundaryEpsilon = 0.15),
                "a contour cut by the window must not be reported closed",
            )
        }
    }

    @Test
    fun anFLineTubeContoursIntoSmoothClosedGeometry() {
        // The path most callers take: an FLine in, a fatter FLine out.
        val stroke = fline {
            moveTo(0.0, 0.0)
            lineTo(60.0, 20.0)
            lineTo(120.0, -10.0)
        }
        val outline = generateSmoothIsoContours(stroke, radius = 14.0, spatialStep = 2.0)
        assertEquals(1, outline.size, "one stroke of one tube radius should give one outline")
        assertTrue(outline[0].nodes.size > 20, "the outline should be a real curve, got ${outline[0].nodes.size} nodes")
        val bounds = outline[0].fast2DBounds()
        // The outline encloses the stroke plus a radius on each side, both axes.
        assertTrue(bounds.w > 120.0 + 2.0 * 14.0 - 4.0, "outline width ${bounds.w} should be about 120 + 2*14")
        assertTrue(bounds.h > 30.0 + 2.0 * 14.0 - 6.0, "outline height ${bounds.h} should be about 30 + 2*14")
    }

    // ------------------------------------------------------------------------------------------
    // contours.kt — the weight callbacks, and the flattening they are keyed on
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFlattenedGraphSaysWhichInstructionEachPointCameFrom() {
        // `nodeIndex` is what makes per-node data interpolable across the flattening, so its
        // convention is the contract: integral at a moveTo/lineTo, and running from just past the
        // previous instruction up to `i` across a cubic.
        val line = fline {
            moveTo(0.0, 0.0)
            cubicTo(10.0, 0.0, 20.0, 0.0, 30.0, 0.0)
            lineTo(40.0, 0.0)
        }
        val graph = pointEdgeGraph2D(line, samplesPerCubic = 8)
        assertEquals(1 + 8 + 1, graph.points.size, "one point per moveTo/lineTo, samplesPerCubic per cubic")
        assertEquals(graph.points.size, graph.nodeIndex.size, "one node index per point")
        assertEquals(0.0, graph.nodeIndex.first(), "the moveTo is instruction 0")
        for (k in 1..8) {
            assertEquals(
                k / 8.0, graph.nodeIndex[k], 1e-12,
                "sample $k of the cubic at instruction 1 should sit at (1-1) + $k/8",
            )
        }
        assertEquals(1.0, graph.nodeIndex[8], 1e-12, "the cubic's end point lands exactly on its own index")
        assertEquals(2.0, graph.nodeIndex.last(), 1e-12, "the trailing lineTo is instruction 2")
    }

    @Test
    fun theFlatteningAgreesWithUniformLinearizeAboutTheInstructionIndex() {
        // The differential test that keeps the two flatteners from drifting apart. contours.kt has
        // its own sampler, but it must key per-node data the same way the renderer's
        // FLine.uniform_linearize does, or a weight and a colour authored on the same node would
        // ramp differently.
        val line = fline {
            moveTo(0.0, 0.0)
            cubicTo(10.0, 30.0, 40.0, 30.0, 50.0, 0.0)
            lineTo(80.0, 10.0)
            cubicTo(90.0, 20.0, 100.0, 20.0, 110.0, 0.0)
        }
        val samples = 8
        val fromRenderer = mutableListOf<Pair<Double, Vec2>>()
        line.uniform_linearize(samplesPerCubic = samples) { at, instr ->
            // Negative instruction values are subpath-break sentinels, and re-emit a point the walk
            // has already visited. The graph has an edge list instead, so it has no equivalent.
            if (instr >= 0f) fromRenderer += instr.toDouble() to vec(at.x, at.y)
        }

        val graph = pointEdgeGraph2D(line, samplesPerCubic = samples)
        // The graph's extra point is the moveTo, which uniform_linearize only ever emits as a break.
        val fromGraph = graph.nodeIndex.drop(1).zip(graph.points.drop(1))
        assertEquals(fromRenderer.size, fromGraph.size, "the two flattenings should produce the same samples")
        fromRenderer.forEachIndexed { i, (instr, point) ->
            val (graphInstr, graphPoint) = fromGraph[i]
            assertEquals(instr, graphInstr, 1e-5, "sample $i: instruction index must agree")
            assertEquals(point.x.toDouble(), graphPoint.x.toDouble(), 1e-3, "sample $i: x must agree")
            assertEquals(point.y.toDouble(), graphPoint.y.toDouble(), 1e-3, "sample $i: y must agree")
        }
    }

    @Test
    fun theEdgeCountIsNotAFunctionOfTheInstructionCount() {
        // Why the weights could not be lists. Three points, and *not* two edges: the coincident pair
        // is joined by nothing at all, so no caller could have sized an `edgeWeights` list from the
        // line they passed in.
        val degenerate = fline {
            moveTo(0.0, 0.0)
            lineTo(0.0, 0.0)
            lineTo(10.0, 0.0)
        }
        val graph = pointEdgeGraph2D(degenerate, samplesPerCubic = 4)
        assertEquals(3, graph.points.size)
        assertEquals(1, graph.edges.size, "the zero-length lineTo contributes a point but no edge")

        // Same instruction list, one coordinate moved: now both edges exist.
        val healthy = fline {
            moveTo(0.0, 0.0)
            lineTo(5.0, 0.0)
            lineTo(10.0, 0.0)
        }
        assertEquals(2, pointEdgeGraph2D(healthy, samplesPerCubic = 4).edges.size)
    }

    @Test
    fun isoWeightsRampsBetweenTheNodesThatSetItAndHoldsAtTheEnds() {
        val line = fline {
            moveTo(0.0, 0.0)
            lineTo(1.0, 0.0)
            lineTo(2.0, 0.0)
            lineTo(3.0, 0.0)
            lineTo(4.0, 0.0)
        }
        line.nodes[0].isoWeight = 1.0
        line.nodes[4].isoWeight = 0.2
        val w = line.isoWeights()
        // Keyframes, not carry-forward: the intervening nodes interpolate. Under the carry-forward
        // convention of colorsPer()/pointSizePer() every one of these would read 1.0 and the taper
        // would collapse into a step at the last node.
        assertEquals(1.0, w(0.0, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.8, w(1.0, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.6, w(2.0, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.4, w(3.0, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.2, w(4.0, vec(0.0, 0.0)), 1e-12)
        // A fractional index — the cubic case — interpolates too.
        assertEquals(0.7, w(1.5, vec(0.0, 0.0)), 1e-12)

        // A keyframe in the middle makes it piecewise, and the ends hold flat.
        line.nodes[1].isoWeight = 0.5
        val piecewise = line.isoWeights()
        assertEquals(1.0, piecewise(0.0, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.75, piecewise(0.5, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.5, piecewise(1.0, vec(0.0, 0.0)), 1e-12)
        assertEquals(0.4, piecewise(2.0, vec(0.0, 0.0)), 1e-12)

        // No node sets one: the line-level property, then 1.0.
        val bare = fline { moveTo(0.0, 0.0); lineTo(1.0, 0.0) }
        assertEquals(1.0, bare.isoWeights()(0.0, vec(0.0, 0.0)), 1e-12)
        bare.isoWeight = 0.3
        assertEquals(0.3, bare.isoWeights()(1.0, vec(0.0, 0.0)), 1e-12)

        // Reading a node's weight must not make it a keyframe — the delegate has no default to
        // materialise, so `bare` still has exactly one weight in it and it is the line's.
        assertEquals(null, bare.nodes[0].isoWeight)
        assertEquals(null, bare.nodes[1].isoWeight)
        assertEquals(0.3, bare.isoWeights()(0.5, vec(0.0, 0.0)), 1e-12)
    }

    @Test
    fun anEdgeTakesTheMinimumOfItsEndpointWeightsSoACapsuleCannotBulge() {
        // The other half of the weight contract, and the one that is a real choice. Two sparse points
        // 40 apart, one heavy and one light: the capsule between them gets the *light* radius, so the
        // tube is thin all the way along until it reaches the fat disc. Deriving the edge weight from
        // the maximum instead would put a 10-wide capsule under a 2-wide end and the taper would
        // vanish exactly where it was asked for.
        val points = listOf(vec(0.0, 0.0), vec(40.0, 0.0))
        val field = pointEdgeScalarField2D(
            points = points,
            edges = listOf(0 to 1),
            radius = 10.0,
            pointWeight = { index, _ -> if (index == 0) 1.0 else 0.2 },
        )
        assertTrue(field(20.0, 0.0) > 0.0, "the centreline is inside the capsule")
        assertEquals(2.0, field(20.0, 0.0), 1e-9, "…by the light end's radius, 10 * 0.2")
        assertTrue(field(20.0, 3.0) < 0.0, "3 out from the centreline is outside a 2-wide capsule")
        assertTrue(field(1.0, 8.0) > 0.0, "…while the heavy end's own disc is still 10 wide")
    }

    @Test
    fun aTaperedTubeIsWideWhereTheWeightIsHighAndNarrowWhereItIsLow() {
        // What the whole mechanism is for. A cubic flattens densely enough that the union of discs
        // *is* the taper, so the tube's half-width should track the weight along the line.
        val stroke = fline {
            moveTo(0.0, 0.0)
            cubicTo(33.0, 0.0, 66.0, 0.0, 100.0, 0.0)
        }
        stroke.nodes[0].isoWeight = 1.0
        stroke.nodes[1].isoWeight = 0.4
        val radius = 20.0
        val field = pointEdgeScalarField2D(
            line = stroke,
            radius = radius,
            samplesPerCubic = 64,
            pointWeight = stroke.isoWeights(),
        )

        // Half-width at an x: how far off the axis the field stays non-negative.
        fun halfWidth(x: Double): Double {
            var y = 0.0
            while (y < radius * 2.0 && field(x, y) >= 0.0) y += 0.01
            return y
        }

        // The cubic's parameter is not arclength, so the weight at a given x is only approximately
        // linear in x; the assertions are on the shape, plus the two ends where the mapping is exact.
        assertEquals(radius * 1.0, halfWidth(0.0), 0.2, "the fat end is a full-radius disc")
        assertEquals(radius * 0.4, halfWidth(100.0), 0.2, "the thin end is 0.4 of one")
        val widths = listOf(10.0, 30.0, 50.0, 70.0, 90.0).map { halfWidth(it) }
        widths.zipWithNext { a, b ->
            assertTrue(b < a, "the tube must narrow monotonically along the stroke, got $widths")
        }

        // And the outline the caller actually gets is one closed taper, not a string of lumps.
        val outline = generateSmoothIsoContours(
            stroke,
            radius = radius,
            spatialStep = 1.5,
            samplesPerCubic = 64,
            pointWeight = stroke.isoWeights(),
        )
        assertEquals(1, outline.size, "a tapered stroke is still one outline")
    }

    @Test
    fun aWeightOverOneStillFitsInsideTheSamplingWindow() {
        // The window is grown by the *widest* weighted radius, not the nominal one — otherwise a
        // weight above 1.0 would run the tube off the edge of the grid and the outline would come
        // back open, cut by the window rather than closed around the stroke.
        val stroke = fline {
            moveTo(0.0, 0.0)
            lineTo(60.0, 0.0)
        }
        stroke.nodes[0].isoWeight = 3.0
        stroke.nodes[1].isoWeight = 3.0
        val outline = generateSmoothIsoContours(
            stroke,
            radius = 10.0,
            spatialStep = 1.5,
            pointWeight = stroke.isoWeights(),
        )
        assertEquals(1, outline.size)
        val bounds = outline[0].fast2DBounds()
        assertTrue(
            bounds.h > 2.0 * 10.0 * 3.0 - 2.0,
            "a 3x weighted tube should be about 60 tall, got ${bounds.h} — the window clipped it",
        )
    }

    @Test
    fun aNegativeWeightIsRejectedWithAMessageThatNamesTheCallback() {
        val failure = assertFailsWith<IllegalArgumentException> {
            pointEdgeScalarField2D(
                points = listOf(vec(0.0, 0.0), vec(10.0, 0.0)),
                edges = listOf(0 to 1),
                radius = 5.0,
                pointWeight = { index, _ -> if (index == 1) -1.0 else 1.0 },
            )
        }
        assertTrue(
            failure.message!!.contains("pointWeight"),
            "the message should name the callback, not a list: ${failure.message}",
        )
    }

    // ==========================================================================================
    // graph_connectivity.kt — 2D Delaunay and the Voronoi dual
    // ==========================================================================================

    private fun jitteredGrid(cols: Int, rows: Int, seed: Long): List<Vec2> {
        val rng = java.util.Random(seed)
        return (0 until cols * rows).map { i ->
            vec(
                (i % cols) + rng.nextDouble() * 0.55 - 0.275,
                (i / cols) + rng.nextDouble() * 0.55 - 0.275,
            )
        }
    }

    @Test
    fun voronoiIsTheDualOfDelaunay() {
        val points = jitteredGrid(6, 5, seed = 17)
        val delaunay = vec2DelaunayGraph(points)
        val dual = vec2VoronoiDualGraph(points)
        assertEquals(delaunay.edges.toSet(), dual.edges.toSet(), "vec2VoronoiDualGraph is the Delaunay graph")

        // Euler's formula for a planar triangulation of n points with h on the hull:
        //   triangles = 2n - 2 - h,  edges = 3n - 3 - h.
        val hull = vec2ConvexHull(points)
        val triangles = vec2DelaunayTriangles(points)
        val n = points.size
        val h = hull.hullIndices.size
        assertEquals(2 * n - 2 - h, triangles.size, "triangle count should be 2n - 2 - h ($n points, $h on the hull)")
        assertEquals(3 * n - 3 - h, delaunay.edges.size, "edge count should be 3n - 3 - h")
    }

    @Test
    fun everyVoronoiCellContainsItsOwnSite() {
        val points = jitteredGrid(6, 5, seed = 23)
        val diagram = vec2VoronoiDiagram(points)
        assertEquals(points.size, diagram.cells.size)

        var checked = 0
        diagram.cells.forEach { cell ->
            if (cell.vertices.size < 3) return@forEach
            val site = points[cell.siteIndex]
            // Every vertex of a cell is by definition equidistant from its site and at least two
            // others, so no vertex may be strictly nearer to a different site than to this one.
            cell.vertices.forEach { v ->
                val own = hypot((v.x - site.x).toDouble(), (v.y - site.y).toDouble())
                val nearest = points.minOf { p -> hypot((v.x - p.x).toDouble(), (v.y - p.y).toDouble()) }
                assertTrue(
                    own <= nearest + 1e-4,
                    "cell ${cell.siteIndex} has a vertex $own from its site but $nearest from the nearest site",
                )
            }
            // And the site itself is inside its own cell.
            assertTrue(pointInPolygon(site, cell.vertices), "site ${cell.siteIndex} is outside its own cell")
            checked++
        }
        assertTrue(checked >= points.size - 2, "expected nearly every cell to be a real polygon, checked $checked")
    }

    private fun pointInPolygon(p: Vec2, ring: List<Vec2>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val t = (p.y - a.y).toDouble() / (b.y - a.y).toDouble()
                if (p.x < a.x + t * (b.x - a.x)) inside = !inside
            }
            j = i
        }
        return inside
    }

    // ==========================================================================================
    // dirichlet.kt — multiplicatively weighted (Apollonius) Voronoi
    // ==========================================================================================

    /** The score `CircularDirichlet` minimises: squared distance over squared weight. */
    private fun apollonius(site: Vec3, x: Double, y: Double): Double {
        val dx = x - site.x
        val dy = y - site.y
        val w = site.z.toDouble()
        return (dx * dx + dy * dy) / (w * w)
    }

    @Test
    fun equalWeightsCollapseEveryBisectorToALine() {
        val sites = listOf(vec(0.0, 0.0, 1.0), vec(1.0, 0.0, 1.0), vec(0.5, 0.9, 1.0))
        val diagram = CircularDirichlet.build(sites)
        assertEquals(3, diagram.cells.size)
        diagram.cells.forEach { cell ->
            assertEquals(2, cell.pieces.size, "each of three equal sites has two neighbours")
            cell.pieces.forEach { piece ->
                assertTrue(
                    piece is CircularDirichlet.LinePiece,
                    "with equal weights every bisector is a straight line, got ${piece::class.simpleName}",
                )
            }
        }
    }

    @Test
    fun aHeavySiteSwallowsItsNeighbourIntoACompleteDisc() {
        // Weight 2.6 against weight 1: site 0's whole cell is the inside of one Apollonius circle,
        // and site 2's bisector never reaches it. So site 0's boundary is a single full-sweep arc.
        val sites = listOf(
            vec(0.0, 0.0, 1.0),
            vec(1.6, 0.0, 2.6),
            vec(0.8, 1.4, 1.0),
        )
        val diagram = CircularDirichlet.build(sites)
        val light = diagram[0]
        assertEquals(1, light.pieces.size, "the swallowed cell is bounded by exactly one curve")
        val arc = light.pieces[0]
        assertTrue(arc is CircularDirichlet.ArcPiece)
        assertTrue(arc.sweepAngle > 2.0 * PI - 1e-3, "…and that curve is a complete circle, swept ${arc.sweepAngle}")
        assertEquals(null, arc.startCorner, "a full circle has no corners")

        // The heavy site's cell is bounded by TWO complete circles — it is the region outside both,
        // which is a cell with holes, not a polygon.
        val heavy = diagram[1]
        assertEquals(2, heavy.pieces.size)
        assertTrue(heavy.pieces.all { it is CircularDirichlet.ArcPiece })

        // Apollonius geometry: the circle bounding site 0 against site 1 has centre and radius
        // fixed by the weight ratio. Check the analytic answer.
        val ratioSquared = (2.6 * 2.6) / (1.0 * 1.0)
        val centreX = (0.0 * ratioSquared - 1.6) / (ratioSquared - 1.0)
        assertTrue(
            abs(arc.support.center.x - centreX) < 1e-4,
            "arc centre x ${arc.support.center.x} should match the analytic ${centreX}",
        )
    }

    @Test
    fun everyDirichletBoundarySampleIsATieBetweenItsTwoSites() {
        val sites = listOf(
            vec(0.0, 0.0, 1.0),
            vec(2.2, 0.3, 1.35),
            vec(0.9, 2.0, 0.8),
            vec(-1.4, 1.1, 1.1),
            vec(1.1, -1.6, 1.6),
        )
        val diagram = CircularDirichlet.build(sites)
        var samples = 0
        diagram.cells.forEach { cell ->
            cell.pieces.forEach { piece ->
                val p = piece.samplePoint()
                val x = p.x.toDouble()
                val y = p.y.toDouble()
                val own = apollonius(sites[cell.siteIndex], x, y)
                val neighbour = apollonius(sites[piece.neighborSite], x, y)
                // The sample is on the bisector of these two sites: their scores tie.
                assertTrue(
                    abs(own - neighbour) <= 1e-3 * kotlin.math.max(1.0, own),
                    "cell ${cell.siteIndex} / neighbour ${piece.neighborSite}: scores $own vs $neighbour do not tie at ($x, $y)",
                )
                // And it is really on the boundary of the cell: no third site scores strictly lower.
                val best = sites.indices.minOf { apollonius(sites[it], x, y) }
                assertTrue(
                    own <= best + 1e-3 * kotlin.math.max(1.0, own),
                    "cell ${cell.siteIndex}'s boundary sample at ($x, $y) is inside site ${sites.indices.minByOrNull { apollonius(sites[it], x, y) }}'s cell",
                )
                samples++
            }
        }
        assertTrue(samples >= 10, "expected a boundary sample per piece, got $samples")
    }

    @Test
    fun aZeroOrNegativeWeightIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CircularDirichlet.build(listOf(vec(0.0, 0.0, 1.0), vec(1.0, 0.0, 0.0)))
        }
    }

    // ==========================================================================================
    // clustering.kt
    // ==========================================================================================

    private fun plantedClusters(seed: Long = 3L): Pair<List<Vec2>, List<Vec2>> {
        val rng = java.util.Random(seed)
        val centres = listOf(vec(0.0, 0.0), vec(3.0, 0.2), vec(1.4, 2.6))
        val points = ArrayList<Vec2>()
        centres.forEach { c ->
            repeat(30) {
                points += vec(c.x + rng.nextGaussian() * 0.35, c.y + rng.nextGaussian() * 0.35)
            }
        }
        return points to centres
    }

    @Test
    fun kMedoidsRunsAtAllAndFindsPlantedClusters() {
        // 90 samples used to throw: Smile 5's CLARANS floors maxNeighbor at min(100, k*(n-k)) and
        // then rejects any maxNeighbor above n.
        val (points, centres) = plantedClusters()
        assertEquals(90, points.size)

        val result = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 11L))
        assertEquals(3, result.k)
        assertTrue(result.converged, "k-medoids should converge on well-separated clusters")
        assertEquals(listOf(30, 30, 30), result.clusterSizes.toList(), "each planted cluster recovered whole")

        // Every medoid is an actual input sample — the defining property of k-medoids.
        result.medoids.forEachIndexed { c, medoid ->
            assertTrue(
                points.any { it.x == medoid.x && it.y == medoid.y },
                "medoid $c $medoid is not one of the input points",
            )
            assertEquals(points[result.medoidIndices[c]].x, medoid.x)
        }

        // …and each sits near a planted centre.
        result.medoids.forEach { medoid ->
            val nearest = centres.minOf { c -> hypot((medoid.x - c.x).toDouble(), (medoid.y - c.y).toDouble()) }
            assertTrue(nearest < 0.4, "medoid $medoid is $nearest from any planted centre")
        }

        // Labels agree with the medoids they were assigned to.
        points.forEachIndexed { i, p ->
            val own = result.medoids[result.labels[i]]
            val ownDistance = abs(p.x - own.x) + abs(p.y - own.y)
            val best = result.medoids.minOf { m -> abs(p.x - m.x) + abs(p.y - m.y) }
            assertTrue(ownDistance <= best + 1e-6, "point $i is labelled for a medoid that is not its nearest")
        }
    }

    @Test
    fun kMedoidsCoversItsWholeInputRangeAndNotJustAboveTheOldCliff() {
        // The Smile version threw for every n below about 100. Showing that n = 90 now works says
        // nothing about n = 2, so here is the whole range, ends included. `distance` defaults to
        // MANHATTAN, so every distortion below is an L1 sum.

        // n = 1 is rejected — one sample cannot be partitioned — and says so.
        val message = assertFailsWith<IllegalArgumentException> {
            vec2KMedoids(listOf(vec(0.0, 0.0)), 1)
        }.message
        assertTrue(
            message != null && message.contains("vec2KMedoids") && message.contains("2 samples"),
            "the n=1 rejection should name the function and the requirement, got: $message",
        )
        assertFailsWith<IllegalArgumentException> { vec2KMedoids(emptyList(), 1) }

        // n = 2, k = 1 — the smallest problem that exists. One point becomes the medoid, and the
        // distortion is exactly the L1 distance to the other.
        val two = listOf(vec(0.0, 0.0), vec(4.0, 3.0))
        val pair = vec2KMedoids(two, 1, KMedoidsOptions(randomSeed = 1L))
        assertEquals(1, pair.k)
        assertEquals(listOf(2), pair.clusterSizes.toList())
        assertEquals(7.0, pair.distortion, 1e-9, "L1 distance between the only two samples")
        assertTrue(pair.converged)

        // k = n - 1, the largest k the function accepts: three medoids over four points leaves
        // exactly one non-medoid, one L1 unit from its nearest neighbour.
        val four = listOf(vec(0.0, 0.0), vec(0.0, 1.0), vec(5.0, 0.0), vec(5.0, 1.0))
        val kMax = vec2KMedoids(four, 3, KMedoidsOptions(randomSeed = 2L))
        assertEquals(3, kMax.k)
        assertEquals(4, kMax.labels.size)
        assertEquals(1.0, kMax.distortion, 1e-9)
        assertEquals(3, kMax.medoidIndices.toSet().size, "the medoids must be three distinct samples")

        // k = n, k > n, k = 0 and k < 0 are all rejected rather than silently clamped.
        listOf(4, 5, 40, 0, -1).forEach { k ->
            assertFailsWith<IllegalArgumentException>("k=$k should be rejected for n=4") {
                vec2KMedoids(four, k)
            }
        }

        // k = 1 must find the *exact* 1-medoid, because the re-medoid step enumerates the cluster.
        // Brute-force it and insist on equality, not proximity.
        val (points, _) = plantedClusters()
        val brute = points.indices.minOf { i ->
            points.sumOf { p -> abs(p.x - points[i].x).toDouble() + abs(p.y - points[i].y).toDouble() }
        }
        val one = vec2KMedoids(points, 1, KMedoidsOptions(randomSeed = 3L))
        assertEquals(1, one.k)
        assertEquals(listOf(90), one.clusterSizes.toList())
        assertEquals(brute, one.distortion, 1e-6, "k=1 must be the exact medoid of the whole set")
    }

    @Test
    fun kMedoidsSurvivesCoincidentAndIdenticalSamples() {
        // Duplicates make the distance matrix singular in the way that breaks D²-weighted seeding:
        // every remaining sample coincides with something already chosen, so the roulette's total
        // weight is zero. That path has to pick a seed anyway rather than loop or under-fill.

        // Three locations, ten copies each, k = 3: a perfect partition with zero distortion.
        val stacked = listOf(vec(0.0, 0.0), vec(10.0, 0.0), vec(0.0, 10.0)).flatMap { c -> List(10) { vec(c.x, c.y) } }
        val exact = vec2KMedoids(stacked, 3, KMedoidsOptions(randomSeed = 4L))
        assertEquals(0.0, exact.distortion, 1e-12, "k = the number of distinct locations costs nothing")
        assertEquals(listOf(10, 10, 10), exact.clusterSizes.sorted())
        assertTrue(exact.converged)

        // More clusters than distinct locations: still zero distortion, still k clusters reported,
        // and no exception. Some medoids necessarily coincide in value.
        val over = vec2KMedoids(stacked, 5, KMedoidsOptions(randomSeed = 5L))
        assertEquals(5, over.k)
        assertEquals(0.0, over.distortion, 1e-12)
        assertEquals(30, over.clusterSizes.sum())

        // Every sample identical. There is one location and nothing to partition, but the caller
        // asked for k clusters and must get k back rather than an exception.
        val flat = List(8) { vec(2.0, -1.0) }
        val degenerate = vec2KMedoids(flat, 3, KMedoidsOptions(randomSeed = 6L))
        assertEquals(3, degenerate.k)
        assertEquals(0.0, degenerate.distortion, 1e-12)
        assertEquals(8, degenerate.clusterSizes.sum())
        assertTrue(degenerate.converged, "nothing can move, so the first round must already converge")
        degenerate.medoids.forEach { assertEquals(2.0f, it.x) }

        // The Euclidean metric takes the same paths.
        val euclidean = vec2KMedoids(
            stacked, 3, KMedoidsOptions(distance = MedoidDistance.EUCLIDEAN, randomSeed = 7L),
        )
        assertEquals(0.0, euclidean.distortion, 1e-12)
        assertEquals(listOf(10, 10, 10), euclidean.clusterSizes.sorted())

        // A single degenerate option is rejected rather than looping forever.
        assertFailsWith<IllegalArgumentException> { vec2KMedoids(stacked, 3, KMedoidsOptions(maxIterations = 0)) }
        assertFailsWith<IllegalArgumentException> { vec2KMedoids(stacked, 3, KMedoidsOptions(runs = 0)) }
    }

    @Test
    fun kMedoidsIsReproducibleAndDistortionFallsWithK() {
        val (points, _) = plantedClusters(seed = 5L)
        val a = vec2KMedoids(points, 4, KMedoidsOptions(randomSeed = 7L))
        val b = vec2KMedoids(points, 4, KMedoidsOptions(randomSeed = 7L))
        assertEquals(a.distortion, b.distortion, "a fixed randomSeed must give a repeatable answer")
        assertEquals(a.medoidIndices.toList(), b.medoidIndices.toList())

        var previous = Double.MAX_VALUE
        for (k in 2..6) {
            val r = vec2KMedoids(points, k, KMedoidsOptions(randomSeed = 31L))
            assertTrue(r.distortion < previous, "distortion at k=$k (${r.distortion}) should beat k=${k - 1} ($previous)")
            previous = r.distortion
        }
    }

    @Test
    fun kMediansAgreesWithKMedoidsOnWellSeparatedData() {
        val (points, _) = plantedClusters(seed = 9L)
        val medoids = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 3L))
        val medians = vec2KMedians(points, 3, KMediansOptions(randomSeed = 3L))
        assertEquals(listOf(30, 30, 30), medians.clusterSizes.sorted())
        assertEquals(listOf(30, 30, 30), medoids.clusterSizes.sorted())
        // A median is free to sit between samples, so it can only do better on the L1 objective.
        assertTrue(
            medians.objective <= medoids.distortion + 1e-9,
            "k-medians objective ${medians.objective} should be <= k-medoids distortion ${medoids.distortion}",
        )
    }

    /**
     * The cloud the `spatial_geometry_examples` clustering figure draws: three tight blobs of 28 with
     * centres 2.408 apart, plus one stray at [strayDistance]-ish from the nearest of them. The stray
     * direction is fixed; only how far out it sits varies.
     */
    private fun blobsWithOneStray(stray: Vec2): List<Vec2> {
        val rng = java.util.Random(7)
        val centres = listOf(vec(0.0, 0.0), vec(2.4, 0.2), vec(1.2, 2.2))
        val points = ArrayList<Vec2>()
        centres.forEach { c ->
            repeat(28) { points += vec(c.x + rng.nextGaussian() * 0.30, c.y + rng.nextGaussian() * 0.30) }
        }
        points += stray
        return points
    }

    /** The L1 objective — sum of |dx| + |dy| to each cluster's own median — of any partition at all. */
    private fun l1ObjectiveOf(points: List<Vec2>, labels: IntArray, k: Int): Double =
        (0 until k).sumOf { c ->
            val members = points.indices.filter { labels[it] == c }
            if (members.isEmpty()) return@sumOf 0.0
            val mx = members.map { points[it].x }.sorted()[members.size / 2]
            val my = members.map { points[it].y }.sorted()[members.size / 2]
            members.sumOf { (abs(points[it].x - mx) + abs(points[it].y - my)).toDouble() }
        }

    private fun sortedSizes(layer: ClusterLayer): List<Int> =
        (0 until layer.clusterCount).map { layer.indices(it).size }.sorted()

    @Test
    fun oneStrayPointBuysAWardClusterButNotAMedoidOne() {
        // The discriminating claim the clustering figure is built on. Ward minimises a sum of
        // SQUARED deviations, so one far point can be worth more than two real clusters; k-medoids
        // and k-medians minimise sums of distances, so it cannot be.
        val stray = vec(9.0, 8.0)
        val points = blobsWithOneStray(stray)
        assertEquals(85, points.size)

        val tree = vec2Agglomerative(points)
        val cut3 = tree.partition(3)
        val medoids = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 11L))
        val medians = vec2KMedians(points, 3, KMediansOptions(randomSeed = 11L))

        // Ward gives the single stray a cluster of its own, and pays by merging two 28-point blobs.
        assertEquals(listOf(1, 28, 56), sortedSizes(cut3), "Ward's k=3 cut of the stray cloud")
        // The robust pair absorb it and keep every planted blob whole.
        assertEquals(listOf(28, 28, 29), medoids.clusterSizes.sorted(), "k-medoids' k=3 cut")
        assertEquals(listOf(28, 28, 29), medians.clusterSizes.sorted(), "k-medians' k=3 cut")

        // Scored on one ruler, Ward's answer is far worse — and *how much* worse is the number the
        // page prints. This is the assertion that fails on well-separated blobs (see the control arm
        // in `wellSeparatedBlobsCannotDiscriminateBetweenTheStrategies`), where the ratio is 1.000.
        val wardCost = l1ObjectiveOf(points, cut3.labels, 3)
        val ratio = wardCost / medians.objective
        assertTrue(
            ratio > 1.8,
            "Ward's cut should cost far more on the L1 ruler; got $wardCost vs ${medians.objective} = ${"%.3f".format(ratio)}x",
        )

        // The backbone invariants still hold on this harder data.
        medoids.medoids.forEach { m ->
            assertTrue(points.any { it.x == m.x && it.y == m.y }, "medoid $m is not an input sample")
        }
        assertTrue(
            medians.objective < medoids.distortion,
            "k-medians ${medians.objective} should beat k-medoids ${medoids.distortion} on the L1 objective",
        )

        // Cutting the SAME tree deeper undoes the damage: the stray is alone, and every planted blob
        // is a union of k=6 clusters even though the k=3 cut ran across two of them.
        val cut6 = tree.partition(6)
        assertEquals(listOf(1, 11, 12, 16, 17, 28), sortedSizes(cut6), "the k=6 cut of the same tree")
        for (fine in 0 until cut6.clusterCount) {
            assertEquals(
                1, cut6.indices(fine).map { cut3.labels[it] }.toSet().size,
                "k=6 cluster $fine straddles two k=3 clusters",
            )
        }
        val plantedOf = { i: Int -> if (i < 84) i / 28 else -1 }
        for (fine in 0 until cut6.clusterCount) {
            val planted = cut6.indices(fine).map(plantedOf).toSet()
            assertEquals(1, planted.size, "k=6 cluster $fine mixes planted blobs $planted")
        }
    }

    @Test
    fun theWardFlipHappensAtThePredictedStrayDistance() {
        // Ward should abandon the stray exactly when absorbing it costs more than merging two blobs:
        // 28/29 · d² > 28·28/56 · 2.408², i.e. d > sqrt(81.2 / 0.966) = 9.17. Bracket that.
        val nearest = vec(1.2, 2.2)   // the blob centre the stray is closest to
        fun distanceOf(stray: Vec2) =
            hypot((stray.x - nearest.x).toDouble(), (stray.y - nearest.y).toDouble())
        fun wardIsolatesTheStray(stray: Vec2): Boolean {
            val points = blobsWithOneStray(stray)
            return sortedSizes(vec2Agglomerative(points).partition(3)).first() == 1
        }

        val inside = vec(8.0, 7.0)
        val outside = vec(9.0, 8.0)
        assertTrue(distanceOf(inside) < 9.17, "the near stray should be inside the predicted threshold")
        assertTrue(distanceOf(outside) > 9.17, "the far stray should be outside it")
        assertTrue(!wardIsolatesTheStray(inside), "at d=${distanceOf(inside)} Ward should absorb the stray")
        assertTrue(wardIsolatesTheStray(outside), "at d=${distanceOf(outside)} Ward should isolate the stray")
    }

    @Test
    fun wellSeparatedBlobsCannotDiscriminateBetweenTheStrategies() {
        // The control arm, and the reason the clustering figure was rebuilt. On the well-separated
        // 30/30/30 cloud the figure used to draw, every assertion in
        // `oneStrayPointBuysAWardClusterButNotAMedoidOne` is either trivially true or false: the
        // three strategies return the same partition and score within 0.5% of each other, so the
        // figure cannot demonstrate any difference between them.
        val (points, _) = plantedClusters()
        val cut3 = vec2Agglomerative(points).partition(3)
        val medoids = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 11L))
        val medians = vec2KMedians(points, 3, KMediansOptions(randomSeed = 11L))

        assertEquals(listOf(30, 30, 30), sortedSizes(cut3))
        assertEquals(listOf(30, 30, 30), medoids.clusterSizes.sorted())
        assertEquals(listOf(30, 30, 30), medians.clusterSizes.sorted())

        // No singleton anywhere, so `sizes(cut3).first() == 1` is false…
        assertEquals(30, sortedSizes(cut3).first(), "no strategy isolates anything on this data")
        // …and the L1 ratio that reaches 1.86 on the stray cloud is exactly 1 here, because Ward and
        // k-medians found the very same partition.
        val ratio = l1ObjectiveOf(points, cut3.labels, 3) / medians.objective
        assertEquals(
            1.0, ratio, 1e-6,
            "on well-separated blobs Ward's cut and k-medians' cut score identically, ratio $ratio",
        )
        assertTrue(
            abs(medoids.distortion - medians.objective) / medians.objective < 0.01,
            "the two objectives differ by under 1% here: ${medoids.distortion} vs ${medians.objective}",
        )
    }

    @Test
    fun agglomerativeCutsAreNestedAndCanonicallyLabelled() {
        val (points, _) = plantedClusters(seed = 12L)
        val tree = vec2Agglomerative(points)
        assertEquals(points.size, tree.sampleCount)
        assertEquals(points.size - 1, tree.heights.size)

        val three = tree.partition(3)
        assertEquals(3, three.clusterCount)
        assertEquals(listOf(30, 30, 30), (0 until 3).map { three.indices(it).size }.sorted())
        // Labels are renumbered by first appearance, so sample 0 is always in cluster 0.
        assertEquals(0, three.labels[0])

        // A finer cut refines a coarser one: every k=6 cluster lies entirely inside one k=3 cluster.
        val six = tree.partition(6)
        for (fine in 0 until six.clusterCount) {
            val coarseLabels = six.indices(fine).map { three.labels[it] }.toSet()
            assertEquals(1, coarseLabels.size, "k=6 cluster $fine straddles k=3 clusters $coarseLabels")
        }
    }

    // ==========================================================================================
    // spatial_field.kt — the FLine bridges
    // ==========================================================================================

    @Test
    fun graphAndTriangleBridgesEmitTheRightNodeCounts() {
        val points = jitteredGrid(5, 4, seed = 41)
        val graph = vec2DelaunayGraph(points)
        val line = graph.toFLine(points)
        // moveTo + lineTo per edge.
        assertEquals(graph.edges.size * 2, line.nodes.size)

        val triangles = vec2DelaunayTriangles(points)
        val faces = delaunayTrianglesToFLine(points, triangles)
        // moveTo + three lineTo per triangle (the third returns to the start).
        assertEquals(triangles.size * 4, faces.nodes.size)

        assertEquals(points.size, pointCloudFLine(points).nodes.size)

        assertFailsWith<IllegalArgumentException> {
            graph.toFLine(points.take(3))
        }
    }

    @Test
    fun voronoiCellsBridgeToClosedRings() {
        val points = jitteredGrid(5, 4, seed = 43)
        val diagram = vec2VoronoiDiagram(points)
        val lines = diagram.toFLines(palette = okhslSet(points.size, 0.7, 0.6, 0.4f), filled = true)
        assertEquals(diagram.cells.count { it.vertices.size >= 3 }, lines.size)
        lines.forEachIndexed { i, line ->
            assertTrue(line.filled, "cell $i should be filled")
            // polylineFrom(closed = true) appends a Close node, so nodes > vertices.
            assertTrue(line.nodes.size >= 4, "cell $i has only ${line.nodes.size} nodes")
        }
    }

    @Test
    fun weightedCellsBridgeToOneLinePerPiece() {
        val sites = listOf(vec(0.0, 0.0, 1.0), vec(1.6, 0.0, 2.6), vec(0.8, 1.4, 1.0))
        val diagram = CircularDirichlet.build(sites)
        val clip = CircularDirichlet.Aabb(vec(-3.0, -3.0), vec(4.5, 4.5))
        val lines = diagram.toFLines(clip = clip, palette = okhslSet(3, 0.75, 0.62))
        assertTrue(lines.size >= diagram.cells.sumOf { it.pieces.size }, "at least one FLine per boundary piece")
        lines.forEach { line -> assertTrue(line.nodes.size >= 2, "a tessellated piece needs at least two nodes") }

        // Unclipped, the full-sweep arcs still tessellate — they need no window.
        val unclipped = diagram[0].toFLines()
        assertEquals(1, unclipped.size)
        assertTrue(unclipped[0].nodes.size > 8, "a full circle should tessellate into a real ring")
    }

    @Test
    fun clusterLayerBridgeDrawsOneDotLayerPerCluster() {
        val (points, _) = plantedClusters(seed = 15L)
        val result = vec2KMedoids(points, 3, KMedoidsOptions(randomSeed = 2L))
        val layer = result.asLayer()
        val lines = layer.toFLines(points, centers = result.medoids, drawSpokes = false)
        // Three dot layers plus two markers per centre.
        assertEquals(3 + 3 * 2, lines.size)
        assertEquals(points.size, lines.take(3).sumOf { it.nodes.size })

        assertFailsWith<IllegalArgumentException> {
            layer.toFLines(points.take(5))
        }
    }

    @Test
    fun planeFigureHtmlEmbedsRealSvg() {
        val points = jitteredGrid(4, 4, seed = 51)
        val html = planeFigureHtml(
            listOf(vec2DelaunayGraph(points).toFLine(points), pointCloudFLine(points)),
            title = "delaunay",
            caption = "16 sites",
            width = 320,
            height = 240,
        )
        assertTrue("<svg" in html, "the figure must contain a real SVG, not a placeholder")
        assertTrue("delaunay" in html)
        assertTrue("16 sites" in html)
        // The lines were fitted into the frame, so nothing is drawn far outside it.
        assertTrue(html.length > 500, "an SVG of 30+ edges should not be tiny; got ${html.length} chars")
    }

    /**
     * `Box.showPlane`'s default output is the live `lines` viewer, and the browser half of that
     * (`afield/outputareas.kt`) reads the whole figure off this markup. Which makes the markup a
     * contract, and this the JVM-side half of `tools/run_ui_regressions.py --id
     * show-plane-interactive`: that regression drives the real pan, zoom and double-click-refit in a
     * browser, and this pins what it is driving.
     *
     * The two claims that matter are geometric. **One SVG**, because the viewer clones the children
     * of one source SVG and dots in a second one would be dropped on the floor. **Bounds that are
     * the frame** rather than the ink's own extent, because that is what makes the viewer's opening
     * transform the identity — the interactive figure opens as exactly the still card, `window` keeps
     * meaning what it says, and an animation calling `showPlane` per frame cannot jitter.
     */
    @Test
    fun theInteractiveFigureIsOneSvgFramedByItsOwnWindow() {
        val points = jitteredGrid(3, 3, seed = 7)
        val html = planeFigureHtml(
            listOf(vec2DelaunayGraph(points).toFLine(points), pointCloudFLine(points)),
            title = "delaunay",
            caption = "9 sites",
            width = 320,
            height = 240,
            interactive = true,
        )

        assertTrue("data-afield-lines-output=\"true\"" in html, "the viewer only picks up its own wrapper")
        assertEquals(1, Regex("<svg").findAll(html).count(), "the dots belong inside the geometry's own SVG")
        assertEquals(points.size, Regex("<circle ").findAll(html).count(), "one <circle> per point, still")

        // The frame, not the ink: a fitted figure's bounds are the box it was fitted into.
        assertTrue("data-afield-bounds-x=\"0\" data-afield-bounds-y=\"0\"" in html)
        assertTrue("data-afield-bounds-w=\"320\" data-afield-bounds-h=\"240\"" in html)
        assertTrue("data-afield-figure-width=\"320\" data-afield-figure-height=\"240\"" in html)

        // The chrome travels as data, and the styles are the still card's own.
        assertTrue("data-afield-figure-title=\"delaunay\"" in html)
        assertTrue("data-afield-figure-caption=\"9 sites\"" in html)
        val card = planeFigureHtml(listOf(pointCloudFLine(points)), width = 320, height = 240)
        for (token in listOf("#0d1219", "#202b38", "#91a4ba", "#7f92a8", "width:320px")) {
            assertTrue(token in html, "the interactive chrome lost `$token`")
            assertTrue(token in card, "the still card lost `$token`")
        }

        // A figure with no dots must still be one well-formed SVG — the splice has nothing to splice.
        val ringOnly = planeFigureHtml(
            listOf(vec2DelaunayGraph(points).toFLine(points)), width = 64, height = 64, interactive = true,
        )
        assertEquals(1, Regex("<svg").findAll(ringOnly).count())
        assertEquals(0, Regex("<circle ").findAll(ringOnly).count())
    }
}
