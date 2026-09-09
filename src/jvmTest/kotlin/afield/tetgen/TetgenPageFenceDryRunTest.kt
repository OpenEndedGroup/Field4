package afield.tetgen

import afield.Chart
import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.boundsOf
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test

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

        val marched = Solid.levelSet(bounds, 0.4, parallel = true) { x, y, z -> -boxDistance(x, y, z) }
        println("panels: marched ${marched.toTriMesh().triangleCount} triangles, " +
            "tetgen boundary ${tet.boundary().triangleCount} triangles")
        marched.close(); exactBox.close()
    }
}
