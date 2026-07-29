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
    fun theSaddleCellIsResolvedSymmetrically() {
        // f = x*y at iso 0 is the two axes. Marching squares cannot draw an X, so it must give two
        // branches — and because the field is symmetric under swapping x and y, the two branches
        // must have the SAME length. The old arbitrary pairing gave 8 and 10 points.
        val saddle: ScalarField2D = { x, y -> x * y }
        val contours = generateIsoContours(saddle, -1.05, 1.03, -1.02, 1.07, 8, 8, 0.0)
        assertEquals(2, contours.size)
        assertEquals(contours[0].size, contours[1].size, "the two branches of x*y=0 should be equal length")

        // Every point on a branch really is on the zero set, to within a cell.
        contours.flatten().forEach { p ->
            assertTrue(
                abs(p.x.toDouble() * p.y.toDouble()) < 0.05,
                "(${p.x}, ${p.y}) claims x*y = 0 but is ${p.x.toDouble() * p.y.toDouble()}",
            )
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
        arc as CircularDirichlet.ArcPiece
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
}
