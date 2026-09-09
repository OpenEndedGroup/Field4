package afield.tetgen

import afield.Chart
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.Vec2
import afield.stdlib.boundsOf
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bodies of `public/docs/tetgen.md`'s three runner fences, minus the `show…` calls that need a
 * live [afield.Box].
 *
 * A docs capture costs minutes and reports a compile error as a blank figure. This runs the same
 * code first, so a typo in a fence is a red test in seconds instead. Keep it in step with the page
 * by hand — a divergence here only means this stopped being a rehearsal, not that the page is wrong.
 */
class TetgenPageFenceDryRunTest {

    private fun available(): Boolean = TetgenLibrary.isAvailable && ManifoldLibrary.isAvailable

    private fun bracket(): Solid = Solid.scope {
        val foot = Solid.cube(80.0, 40.0, 15.0)
        val post = Solid.cube(15.0, 40.0, 70.0)
        val bolts = listOf(12.0, 28.0).map {
            Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
        }
        val eye = Solid.cylinder(60.0, 6.0, segments = 48)
            .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
        ((foot + post) - Solid.union(bolts + eye)).copy()
    }

    @Test fun theCutawayFence() {
        if (!available()) return
        val part = bracket()
        val surface = part.toTriMesh()
        val mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0)

        val centroid = mesh.centroids()
        val half = mesh.filtered { centroid[3 * it + 1] < 20.0 }

        val quality = half.qualities()
        val colors = FloatArray(3 * half.cellCount)
        for (t in 0 until half.cellCount) {
            val c = afield.Colormaps.sample("viridis", quality[t])
            colors[3 * t] = c.r.toFloat(); colors[3 * t + 1] = c.g.toFloat(); colors[3 * t + 2] = c.b.toFloat()
        }

        println("Manifold  ${"%.4f".format(part.volume)} mm³, ${part.triangleCount} triangles, genus ${part.genus}")
        println("TetGen    $mesh")
        println("          volume ${"%.4f".format(mesh.volume())} mm³")
        println("          inverted elements ${mesh.volumes().count { it <= 0.0 }}")
        println()
        println("cutaway   ${half.cellCount} of ${mesh.cellCount} elements, " +
            "${half.boundary().triangleCount} boundary triangles")
        println("quality   min ${"%.4f".format(quality.min())}, median " +
            "${"%.4f".format(quality.sorted()[quality.size / 2])}")

        val panels: Array<Pair<String, TriMesh>> = arrayOf(
            "the closed surface TetGen is given\n${surface.triangleCount} triangles" to surface,
            "half the elements, skinned\n${half.cellCount} tetrahedra" to half.boundary(),
            "the same half, elements shrunk to 0.78\ncolour is radius ratio, dark is worse" to
                half.toTriMesh(shrink = 0.78, cellColors = colors),
        )
        val view = MeshView(yawDegrees = 38.0, pitchDegrees = 24.0, width = 380, height = 300)
        println("panels ${panels.size}, view $view")
        part.close()
    }

    @Test fun theQualityFence() {
        if (!available()) return
        val part = bracket()
        val surface = part.toTriMesh()
        val mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = 3.0)
        val quality = mesh.qualities()
        val radiusEdge = mesh.radiusEdgeRatios()

        val worst = quality.indices.sortedBy { quality[it] }.take(400).toHashSet()
        val slivers = mesh.filtered { it in worst }

        val sorted = quality.sorted()
        println("${mesh.cellCount} elements at targetEdgeLength = 3.0")
        println("radius ratio     min ${"%.4f".format(sorted.first())}  " +
            "1st pct ${"%.4f".format(sorted[sorted.size / 100])}  " +
            "median ${"%.4f".format(sorted[sorted.size / 2])}  max ${"%.4f".format(sorted.last())}")
        println("below 0.05       ${sorted.count { it < 0.05 }}   (slivers)")
        println("below 0.10       ${sorted.count { it < 0.10 }}")
        println()
        println("radius-edge      min ${"%.4f".format(radiusEdge.min())}  " +
            "(the regular tetrahedron's floor is ${"%.4f".format(sqrt(6.0) / 4.0)})")
        println("                 max ${"%.4f".format(radiusEdge.max())}, " +
            "${radiusEdge.count { it > 1.2 }} above the 1.2 bound that was asked for")
        println()
        val worstCell = quality.indices.minBy { quality[it] }
        println("the single worst element: radius ratio ${"%.5f".format(quality[worstCell])}, " +
            "radius-edge ${"%.4f".format(radiusEdge[worstCell])}")
        println("the worst element by radius-edge: ${"%.4f".format(radiusEdge.max())}, " +
            "radius ratio ${"%.4f".format(quality[radiusEdge.indices.maxBy { radiusEdge[it] }])}")
        println("every element below quality 0.10, with its radius-edge:")
        quality.indices.filter { quality[it] < 0.10 }.sortedBy { quality[it] }.take(12).forEach {
            println("   q ${"%.5f".format(quality[it])}   re ${"%.4f".format(radiusEdge[it])}")
        }
        // Is a sliver invisible to the radius-edge bound? Compare the two populations.
        val inBound = quality.indices.filter { radiusEdge[it] <= 1.2 }
        println("elements inside the 1.2 radius-edge bound: ${inBound.size}, " +
            "of which below quality 0.10: ${inBound.count { quality[it] < 0.10 }}, " +
            "worst quality ${"%.5f".format(inBound.minOf { quality[it] })}")
        val outBound = quality.indices.filter { radiusEdge[it] > 1.2 }
        println("elements outside it: ${outBound.size}, " +
            "of which below quality 0.10: ${outBound.count { quality[it] < 0.10 }}, " +
            "worst quality ${"%.5f".format(outBound.minOf { quality[it] })}")

        Chart(title = "radius ratio over ${mesh.cellCount} elements",
              xLabel = "3 r_in / r_circ", yLabel = "elements")
            .histogram("elements", quality.toList(), bins = 40)
        println("slivers figure: ${slivers.boundary().triangleCount} triangles, " +
            "caption range ${"%.4f".format(sorted.first())} to ${"%.4f".format(sorted[399])}")
        part.close()
    }

    @Test fun theSharpEdgeFence() {
        if (!available()) return
        fun boxDistance(x: Double, y: Double, z: Double): Double {
            val dx = abs(x) - 1.0; val dy = abs(y) - 1.5; val dz = abs(z) - 2.0
            val out = sqrt(max(dx, 0.0) * max(dx, 0.0) + max(dy, 0.0) * max(dy, 0.0) + max(dz, 0.0) * max(dz, 0.0))
            return out + min(max(dx, max(dy, dz)), 0.0)
        }
        fun sphereDistance(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z) - 1.5

        val bounds = boundsOf(vec(-1.6, -2.1, -2.6), vec(1.6, 2.1, 2.6))

        fun worstError(xyz: DoubleArray, distance: (Double, Double, Double) -> Double): Double {
            var worst = 0.0
            for (v in 0 until xyz.size / 3) {
                worst = max(worst, abs(distance(xyz[3 * v], xyz[3 * v + 1], xyz[3 * v + 2])))
            }
            return worst
        }
        fun coordinates(m: TriMesh) = DoubleArray(m.positions.size) { m.positions[it].toDouble() }

        println("h      box, worst error        sphere, worst error")
        for (h in listOf(0.4, 0.2, 0.1, 0.05)) {
            val b = Solid.levelSet(bounds, h, parallel = true) { x, y, z -> -boxDistance(x, y, z) }
            val s = Solid.levelSet(bounds, h, parallel = true) { x, y, z -> -sphereDistance(x, y, z) }
            val be = worstError(coordinates(b.toTriMesh()), ::boxDistance)
            val se = worstError(coordinates(s.toTriMesh()), ::sphereDistance)
            println("${"%.2f".format(h)}   ${"%.6f".format(be)} = ${"%.4f".format(be / h)} h    " +
                "${"%.6f".format(se)} = ${"%.4f".format(se / h)} h")
            b.close(); s.close()
        }

        val exactBox = Solid.cube(2.0, 3.0, 4.0, center = true)
        val tet = Tetgen.tetrahedralize(exactBox.toTriMesh(), targetEdgeLength = 0.25)
        println()
        println("TetGen at h = 0.25: ${tet.cellCount} elements, volume ${tet.volume()}")
        println("  worst node outside the true box: " +
            worstError(tet.positions) { x, y, z -> max(boxDistance(x, y, z), 0.0) })

        // The corner cross sections the figure plots. The whole point of charting them rather than
        // rendering the two boxes side by side is that the rounding is 1.5% of the body at h = 0.4
        // and invisible in a render — which is what the first version of this figure proved.
        val exactCorner = exactBox.slice(0.0).toPolygons().first()
        println("exact cross section: ${exactCorner.size} points, " +
            "corner at (${exactCorner.maxOf { it.x }}, ${exactCorner.maxOf { it.y }})")
        for (h in listOf(0.4, 0.2, 0.1)) {
            Solid.levelSet(bounds, h, parallel = true) { x, y, z -> -boxDistance(x, y, z) }.use { b ->
                val outline = b.slice(0.0).toPolygons().first()
                val inWindow = outline.filter { it.x > 0.55 && it.y > 1.05 }
                println("  h = $h: ${outline.size} points, ${inWindow.size} in the corner window, " +
                    "nearest approach to (1.0, 1.5) = " +
                    "%.5f".format(outline.minOf {
                        sqrt((it.x - 1.0) * (it.x - 1.0) + (it.y - 1.5) * (it.y - 1.5))
                    }))
            }
        }

        // The clip the page's fence applies before charting, rehearsed for the reason the clip
        // exists: a Chart draws points outside its own domain anyway and the box output panel fits
        // the result, so one unclipped outline shrinks the whole figure to a stamp in the corner.
        // That failure is invisible in a green build and cost a capture on 2026-09-09. Every
        // coordinate below must be inside the window or a break.
        val (ex, ey) = clippedToWindow(exactCorner)
        val exFinite = ex.filter { it.isFinite() }
        val eyFinite = ey.filter { it.isFinite() }
        println("clipped exact: ${exFinite.size} points, ${ex.count { it.isNaN() }} breaks, " +
            "x ${"%.4f".format(exFinite.min())}..${"%.4f".format(exFinite.max())}, " +
            "y ${"%.4f".format(eyFinite.min())}..${"%.4f".format(eyFinite.max())}")
        assertTrue(exFinite.all { it >= 0.82 - 1e-9 && it <= 1.06 + 1e-9 }, "exact x outside the window")
        assertTrue(eyFinite.all { it >= 1.32 - 1e-9 && it <= 1.56 + 1e-9 }, "exact y outside the window")
        for (h in listOf(0.4, 0.2, 0.1)) {
            Solid.levelSet(bounds, h, parallel = true) { x, y, z -> -boxDistance(x, y, z) }.use { b ->
                val (cx, cy) = clippedToWindow(b.slice(0.0).toPolygons().first())
                val fx = cx.filter { it.isFinite() }
                val fy = cy.filter { it.isFinite() }
                println("  clipped h = $h: ${fx.size} points in the window, " +
                    "x ${"%.4f".format(fx.min())}..${"%.4f".format(fx.max())}, " +
                    "y ${"%.4f".format(fy.min())}..${"%.4f".format(fy.max())}")
                assertTrue(fx.isNotEmpty(), "h = $h left nothing in the window")
                assertTrue(fx.all { it >= 0.82 - 1e-9 && it <= 1.06 + 1e-9 }, "x outside the window at h = $h")
                assertTrue(fy.all { it >= 1.32 - 1e-9 && it <= 1.56 + 1e-9 }, "y outside the window at h = $h")
            }
        }
        exactBox.close()
    }

    /**
     * The clip `public/docs/tetgen.md`'s sharp-edge fence applies, line for line.
     *
     * Liang–Barsky against the chart's own window, with a `NaN` between disjoint runs so that one
     * outline stays one series and one legend entry. It is here as well as in the page because it is
     * the one piece of that fence whose failure is a layout failure rather than an exception.
     */
    private fun clippedToWindow(loop: List<Vec2>): Pair<List<Double>, List<Double>> {
        val xLo = 0.82; val xHi = 1.06; val yLo = 1.32; val yHi = 1.56
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[(i + 1) % loop.size]
            val ax = a.x.toDouble(); val ay = a.y.toDouble()
            val dx = b.x.toDouble() - ax; val dy = b.y.toDouble() - ay
            val edge = doubleArrayOf(-dx, dx, -dy, dy)
            val slack = doubleArrayOf(ax - xLo, xHi - ax, ay - yLo, yHi - ay)
            var t0 = 0.0
            var t1 = 1.0
            var outside = false
            for (k in 0..3) {
                if (edge[k] == 0.0) { if (slack[k] < 0.0) outside = true }
                else {
                    val t = slack[k] / edge[k]
                    if (edge[k] < 0.0 && t > t0) t0 = t
                    if (edge[k] > 0.0 && t < t1) t1 = t
                }
            }
            if (outside || t0 > t1) continue
            if (xs.isNotEmpty()) { xs += Double.NaN; ys += Double.NaN }
            xs += ax + t0 * dx; ys += ay + t0 * dy
            xs += ax + t1 * dx; ys += ay + t1 * dy
        }
        return xs to ys
    }
}
