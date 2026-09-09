package afield.tetgen

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.TriMesh
import afield.stdlib.signedVolume
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test

/**
 * Prints every number `public/docs/tetgen.md` quotes. Run it, read it, paste it — and re-run it when
 * the page is edited, because the page is only as honest as its last run of this.
 *
 * Not an assertion suite: the assertions live in [TetgenTest] and [TetMeshViewsTest]. This exists so
 * that a figure caption and a paragraph of prose can be checked against the same run rather than
 * against a remembered one.
 */
class TetgenPageMeasurementTest {

    private fun available(): Boolean =
        TetgenLibrary.isAvailable && ManifoldLibrary.isAvailable

    /** The page's worked example: an L-bracket in millimetres, sharp edges and three bores. */
    private fun bracket(): Solid = Solid.scope {
        val foot = Solid.cube(80.0, 40.0, 15.0)
        val post = Solid.cube(15.0, 40.0, 70.0)
        val down = Solid.cylinder(40.0, 5.0, segments = 48).rotated(0.0, 0.0, 0.0)
        val holes = listOf(
            down.translated(55.0, 12.0, -5.0),
            down.translated(55.0, 28.0, -5.0),
        )
        val through = Solid.cylinder(60.0, 6.0, segments = 48)
            .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
        ((foot + post) - Solid.union(holes + through)).copy()
    }

    private fun timedMesh(surface: TriMesh, edge: Double, repeats: Int = 5): Pair<TetMesh, Double> {
        var mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = edge)
        val times = DoubleArray(repeats) {
            val t0 = System.nanoTime()
            mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = edge)
            (System.nanoTime() - t0) / 1e6
        }
        return mesh to times.sorted()[repeats / 2]
    }

    @Test fun printEveryNumberThePageQuotes() {
        if (!available()) {
            println("SKIPPED: ${TetgenLibrary.describe()} / ${ManifoldLibrary.describe()}")
            return
        }
        println("=== version ===")
        println(TetgenLibrary.describe())

        println()
        println("=== A. the cube claim, re-derived ===")
        Solid.cube(2.0, 3.0, 4.0).use { cube ->
            val exact = cube.checked().volume
            val surface = cube.toTriMesh()
            println("input surface: ${surface.vertexCount} vertices, ${surface.triangleCount} triangles")
            println("Manifold volume: $exact")
            for (edge in listOf(1.0, 0.5, 0.25)) {
                val (mesh, ms) = timedMesh(surface, edge)
                val v = mesh.volume()
                println("  edge $edge -> ${mesh.nodeCount} nodes, ${mesh.cellCount} tets, " +
                    "volume $v, rel err ${abs(v - exact) / exact}, ${"%.1f".format(ms)} ms, " +
                    "inverted ${mesh.volumes().count { it <= 0.0 }}")
            }
        }

        println()
        println("=== B. the bracket, and the size sweep ===")
        bracket().use { part ->
            val exact = part.checked().volume
            val surface = part.toTriMesh()
            println("Manifold: volume $exact mm3, ${part.triangleCount} triangles, genus ${part.genus}")
            println("edge | nodes | tets | ms | rel volume err | q min | q median | q<0.05 | re max | re>1.2")
            for (edge in listOf(8.0, 5.0, 3.0, 2.0)) {
                val (mesh, ms) = timedMesh(surface, edge)
                val q = mesh.qualities().sorted()
                val re = mesh.radiusEdgeRatios()
                println("$edge | ${mesh.nodeCount} | ${mesh.cellCount} | ${"%.1f".format(ms)} | " +
                    "${"%.3e".format(abs(mesh.volume() - exact) / exact)} | " +
                    "${"%.4f".format(q.first())} | ${"%.4f".format(q[q.size / 2])} | " +
                    "${q.count { it < 0.05 }} | ${"%.3f".format(re.max())} | ${re.count { it > 1.2 }}")
            }

            println()
            println("=== C. quality histogram at edge 3.0 ===")
            val mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = 3.0)
            val q = mesh.qualities()
            val bins = IntArray(20)
            q.forEach { bins[(it * 20).toInt().coerceIn(0, 19)]++ }
            bins.forEachIndexed { i, n ->
                println("  ${"%.2f".format(i / 20.0)}-${"%.2f".format((i + 1) / 20.0)}: $n")
            }
            println("  cells ${mesh.cellCount}, min ${q.min()}, mean ${q.average()}")

            println()
            println("=== D. the quality switch, swept ===")
            for (bound in listOf(1.05, 1.1, 1.2, 1.414, 2.0)) {
                val m = Tetgen.tetrahedralize(surface, quality = bound, targetEdgeLength = 3.0)
                val qq = m.qualities().sorted()
                val re = m.radiusEdgeRatios()
                println("  -q$bound -> ${m.cellCount} tets, q min ${"%.4f".format(qq.first())}, " +
                    "q median ${"%.4f".format(qq[qq.size / 2])}, q<0.05 ${qq.count { it < 0.05 }}, " +
                    "re over bound ${re.count { it > bound }}")
            }

            println()
            println("=== E. preserveSurface ===")
            for (preserve in listOf(false, true)) {
                val m = Tetgen.tetrahedralize(surface, targetEdgeLength = 3.0, preserveSurface = preserve)
                val qq = m.qualities()
                println("  preserveSurface=$preserve -> ${m.nodeCount} nodes, ${m.cellCount} tets, " +
                    "q min ${"%.5f".format(qq.min())}, q<0.05 ${qq.count { it < 0.05 }}, " +
                    "boundary ${m.boundary().triangleCount} triangles")
            }

            println()
            println("=== F. what the default (no target size) does ===")
            val loose = Tetgen.tetrahedralize(surface)
            println("  no targetEdgeLength -> ${loose.nodeCount} nodes, ${loose.cellCount} tets, " +
                "rel volume err ${abs(loose.volume() - exact) / exact}")
        }

        println()
        println("=== G. the sharp edge, against the route this replaced ===")
        // The same 2x3x4 box, but reached by sampling its distance function onto a grid and
        // marching it — which is what a level-set mesher does before it ever sees an edge.
        Solid.cube(2.0, 3.0, 4.0, center = true).use { cube ->
            val exact = cube.volume
            fun boxField(x: Double, y: Double, z: Double): Double {
                val dx = abs(x) - 1.0; val dy = abs(y) - 1.5; val dz = abs(z) - 2.0
                val outside = kotlin.math.sqrt(
                    max(dx, 0.0) * max(dx, 0.0) + max(dy, 0.0) * max(dy, 0.0) + max(dz, 0.0) * max(dz, 0.0))
                return -(outside + kotlin.math.min(max(dx, max(dy, dz)), 0.0))
            }
            val bounds = afield.stdlib.boundsOf(
                afield.stdlib.vec(-1.6, -2.1, -2.6), afield.stdlib.vec(1.6, 2.1, 2.6))
            for (h in listOf(0.4, 0.2, 0.1, 0.05)) {
                Solid.levelSet(bounds, edgeLength = h, parallel = true, field = ::boxField).use { marched ->
                    val m = marched.toTriMesh()
                    // How far the marched surface's vertices sit from the true box surface.
                    var worst = 0.0
                    for (v in 0 until m.vertexCount) {
                        val dx = abs(m.positions[3 * v].toDouble()) - 1.0
                        val dy = abs(m.positions[3 * v + 1].toDouble()) - 1.5
                        val dz = abs(m.positions[3 * v + 2].toDouble()) - 2.0
                        val outside = kotlin.math.sqrt(
                            max(dx, 0.0) * max(dx, 0.0) + max(dy, 0.0) * max(dy, 0.0) + max(dz, 0.0) * max(dz, 0.0))
                        worst = max(worst, abs(outside + kotlin.math.min(max(dx, max(dy, dz)), 0.0)))
                    }
                    println("  level set h=$h -> ${m.triangleCount} triangles, volume ${marched.volume}, " +
                        "rel err ${"%.3e".format(abs(marched.volume - exact) / exact)}, " +
                        "worst boundary error ${"%.6f".format(worst)} = ${"%.3f".format(worst / h)} h")
                }
            }
            // And the same body through TetGen, which is handed the exact triangulation.
            val tet = Tetgen.tetrahedralize(cube.toTriMesh(), targetEdgeLength = 0.25)
            var worst = 0.0
            for (v in 0 until tet.nodeCount) {
                val dx = abs(tet.positions[3 * v]) - 1.0
                val dy = abs(tet.positions[3 * v + 1]) - 1.5
                val dz = abs(tet.positions[3 * v + 2]) - 2.0
                val outside = kotlin.math.sqrt(
                    max(dx, 0.0) * max(dx, 0.0) + max(dy, 0.0) * max(dy, 0.0) + max(dz, 0.0) * max(dz, 0.0))
                val d = outside + kotlin.math.min(max(dx, max(dy, dz)), 0.0)
                if (d > worst) worst = d          // only outward error counts; interior nodes are inside
            }
            println("  TetGen h=0.25 -> ${tet.cellCount} tets, volume ${tet.volume()}, " +
                "rel err ${"%.3e".format(abs(tet.volume() - exact) / exact)}, " +
                "worst node outside the true box ${worst}")
            println("  TetGen boundary encloses ${abs(tet.boundary().signedVolume())}")
        }

        println()
        println("=== H. the refusals, with their real text ===")
        val corners = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
        val faces = intArrayOf(
            0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
            1, 5, 6, 1, 6, 2, 2, 6, 7, 2, 7, 3)
        runCatching { Tetgen.tetrahedralize(TriMesh(corners, faces), targetEdgeLength = 0.25) }
            .onFailure { println("  open surface -> ${it::class.simpleName}: ${it.message}") }
        runCatching { Tetgen.tetrahedralize(TriMesh(FloatArray(0), IntArray(0))) }
            .onFailure { println("  empty -> ${it::class.simpleName}: ${it.message}") }
        runCatching { Tetgen.tetrahedralize(Solid.cube(1.0).use { it.toTriMesh() }, quality = 0.5) }
            .onFailure { println("  quality 0.5 -> ${it::class.simpleName}: ${it.message}") }
        runCatching { Tetgen.tetrahedralize(Solid.cube(1.0).use { it.toTriMesh() }, targetEdgeLength = -1.0) }
            .onFailure { println("  negative size -> ${it::class.simpleName}: ${it.message}") }
    }
}
