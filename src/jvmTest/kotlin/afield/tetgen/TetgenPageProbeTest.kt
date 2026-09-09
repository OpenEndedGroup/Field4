package afield.tetgen

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import afield.stdlib.TriMesh
import afield.stdlib.signedVolume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test

/** Follow-ups on three things the first measurement run said that the dev docs did not. */
class TetgenPageProbeTest {

    private fun available(): Boolean = TetgenLibrary.isAvailable && ManifoldLibrary.isAvailable

    @Test fun whereDoesTheBracketsVolumeErrorActuallyComeFrom() {
        if (!available()) return
        Solid.scope {
            val part = (Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)) -
                Solid.union(listOf(
                    Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, 12.0, -5.0),
                    Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, 28.0, -5.0),
                    Solid.cylinder(60.0, 6.0, segments = 48).rotated(0.0, 90.0, 0.0)
                        .translated(-20.0, 20.0, 52.0)))
            val manifoldVolume = part.checked().volume
            val surface = part.toTriMesh()
            // TriMesh stores Float. If the whole discrepancy is that narrowing, then the volume of
            // the float32 surface — computed in double, from the same numbers TetGen was handed —
            // must equal TetGen's answer to machine epsilon, and Manifold's must be the outlier.
            val surfaceVolume = abs(surface.signedVolume())
            val tet = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0)
            println("Manifold double volume : $manifoldVolume")
            println("float32 surface volume : $surfaceVolume  rel ${abs(surfaceVolume - manifoldVolume) / manifoldVolume}")
            println("TetGen mesh volume     : ${tet.volume()}  rel to surface " +
                "${abs(tet.volume() - surfaceVolume) / surfaceVolume}")
            Unit
        }
    }

    @Test fun whichBadSurfaceActuallyReachesTheCatchInTheWrapper() {
        if (!available()) return
        fun probe(name: String, mesh: TriMesh) {
            val outcome = runCatching { Tetgen.tetrahedralize(mesh, targetEdgeLength = 0.4) }
            println("$name -> " + outcome.fold(
                {
                    "OK, ${it.cellCount} tets, volume ${"%.6f".format(it.volume())}, " +
                        "surface volume ${"%.6f".format(abs(mesh.signedVolume()))}, " +
                        "boundary ${it.boundary().triangleCount} triangles"
                },
                { "${it::class.simpleName}: ${it.message}" }))
        }

        val cubeCorners = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
        val closed = intArrayOf(
            0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
            1, 5, 6, 1, 6, 2, 2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0)
        probe("closed cube (control)", TriMesh(cubeCorners, closed))
        probe("five faces of a cube", TriMesh(cubeCorners, closed.copyOf(30)))
        probe("one face of a cube", TriMesh(cubeCorners, closed.copyOf(6)))
        probe("inside-out cube", TriMesh(cubeCorners, IntArray(closed.size) { i ->
            closed[3 * (i / 3) + intArrayOf(0, 2, 1)[i % 3]]
        }))
        // A closed cube with one triangle duplicated: every edge of it now has three incident faces.
        probe("cube + a duplicated face", TriMesh(cubeCorners, closed + intArrayOf(0, 1, 2)))
        // Two closed cubes that overlap: each is fine, the pair self-intersects.
        val two = FloatArray(48)
        System.arraycopy(cubeCorners, 0, two, 0, 24)
        for (i in 0 until 24) two[24 + i] = cubeCorners[i] + (if (i % 3 == 0) 0.5f else 0f)
        probe("two interpenetrating cubes", TriMesh(two, closed + IntArray(closed.size) { closed[it] + 8 }))
        // A closed cube with two coincident vertices.
        val welded = cubeCorners.copyOf()
        welded[3] = 0f; welded[4] = 0f; welded[5] = 0f      // vertex 1 lands on vertex 0
        probe("cube with two coincident corners", TriMesh(welded, closed))
        // A zero-volume closed surface: the same triangle forwards and backwards.
        probe("a degenerate two-triangle 'solid'", TriMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
            intArrayOf(0, 1, 2, 0, 2, 1, 0, 1, 3, 0, 3, 1)))

        // TetGen dumps the facets it skipped into the working directory on the self-intersection
        // path. See TetgenWorkingDirectoryLitterTest; without this the suite leaves them in the
        // repository root.
        val here = java.io.File(".").absoluteFile.parentFile
        listOf("tetgen-tmpfile_skipped.node", "tetgen-tmpfile_skipped.face")
            .forEach { java.io.File(here, it).delete() }
    }

    @Test fun doesMarchingCubesConvergeFasterOnASphereThanOnABox() {
        if (!available()) return
        val bounds = afield.stdlib.boundsOf(
            afield.stdlib.vec(-1.6, -2.1, -2.6), afield.stdlib.vec(1.6, 2.1, 2.6))

        fun boxDistance(x: Double, y: Double, z: Double): Double {
            val dx = abs(x) - 1.0; val dy = abs(y) - 1.5; val dz = abs(z) - 2.0
            val out = sqrt(max(dx, 0.0) * max(dx, 0.0) + max(dy, 0.0) * max(dy, 0.0) + max(dz, 0.0) * max(dz, 0.0))
            return out + kotlin.math.min(max(dx, max(dy, dz)), 0.0)
        }
        fun sphereDistance(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z) - 1.5

        for ((name, distance) in listOf<Pair<String, (Double, Double, Double) -> Double>>(
            "box   " to ::boxDistance, "sphere" to ::sphereDistance)) {
            for (h in listOf(0.4, 0.2, 0.1, 0.05)) {
                Solid.levelSet(bounds, edgeLength = h, parallel = true,
                    field = { x, y, z -> -distance(x, y, z) }).use { marched ->
                    val m = marched.toTriMesh()
                    var worst = 0.0
                    for (v in 0 until m.vertexCount) {
                        worst = max(worst, abs(distance(
                            m.positions[3 * v].toDouble(),
                            m.positions[3 * v + 1].toDouble(),
                            m.positions[3 * v + 2].toDouble())))
                    }
                    println("$name h=$h -> worst ${"%.6f".format(worst)} = ${"%.4f".format(worst / h)} h" +
                        "   (${m.triangleCount} triangles)")
                }
            }
        }
    }

    @Test fun doesTheOrderTheSolidWasBuiltInChangeTheMesh() {
        if (!available()) return
        // Two constructions of the SAME solid, differing only in the order the operands are
        // created. If Manifold's output vertex order depends on that, TetGen sees a different
        // (equally valid) input and refines it differently — which would explain a docs capture
        // reporting 66,108 elements where every test reports 66,489.
        fun bolts() = listOf(12.0, 28.0).map {
            Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
        }
        fun eye() = Solid.cylinder(60.0, 6.0, segments = 48)
            .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)

        val blocksFirst = Solid.scope {
            val foot = Solid.cube(80.0, 40.0, 15.0)
            val post = Solid.cube(15.0, 40.0, 70.0)
            val holes = Solid.union(bolts() + eye())
            ((foot + post) - holes).copy()
        }
        val holesFirst = Solid.scope {
            val holes = Solid.union(bolts() + eye())
            ((Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)) - holes).copy()
        }
        for ((label, solid) in listOf("blocks first" to blocksFirst, "holes first" to holesFirst)) {
            val s = solid.toTriMesh()
            val m = Tetgen.tetrahedralize(s, targetEdgeLength = 3.0)
            println("$label: volume ${solid.volume}, positions ${s.positions.contentHashCode()}, " +
                "indices ${s.indices.contentHashCode()}, first triangle " +
                "${s.indices.take(3)}, ${m.nodeCount} nodes, ${m.cellCount} elements, " +
                "worst quality ${"%.5f".format(m.qualities().min())}, " +
                "volume ${"%.6f".format(m.volume())}")
            solid.close()
        }
    }

    @Test fun isTetGenDeterministic() {
        if (!available()) return
        // The docs capture reported 66,108 elements where a test run of the same code reported
        // 66,489. Either TetGen randomises, or the two runs were not given the same surface.
        Solid.scope {
            val part = (Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)) -
                Solid.union(listOf(
                    Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, 12.0, -5.0),
                    Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, 28.0, -5.0),
                    Solid.cylinder(60.0, 6.0, segments = 48).rotated(0.0, 90.0, 0.0)
                        .translated(-20.0, 20.0, 52.0)))
            val surface = part.toTriMesh()
            println("surface digest: ${surface.positions.contentHashCode()} / " +
                "${surface.indices.contentHashCode()}, ${surface.triangleCount} triangles")
            // Same surface object, five times.
            repeat(5) {
                val m = Tetgen.tetrahedralize(surface, targetEdgeLength = 3.0)
                println("  run $it: ${m.nodeCount} nodes, ${m.cellCount} elements, " +
                    "volume ${m.volume()}, worst quality ${"%.6f".format(m.qualities().min())}")
            }
            // And a freshly built surface, in case the Boolean is what varies.
            repeat(2) {
                val fresh = part.toTriMesh()
                println("  fresh surface digest ${fresh.positions.contentHashCode()}: " +
                    "${Tetgen.tetrahedralize(fresh, targetEdgeLength = 3.0).cellCount} elements")
            }
            Unit
        }
    }

    @Test fun howMuchDoesTheQualitySwitchActuallyBuy() {
        if (!available()) return
        // The dev-doc claim is that -q1.2 removes the last poor element "for no measurable cost".
        // On the page's bracket it does not, so this prints the whole picture including wall time.
        Solid.scope {
            val part = (Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)) -
                Solid.union(listOf(
                    Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, 12.0, -5.0),
                    Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, 28.0, -5.0),
                    Solid.cylinder(60.0, 6.0, segments = 48).rotated(0.0, 90.0, 0.0)
                        .translated(-20.0, 20.0, 52.0)))
            val surface = part.toTriMesh()
            for (bound in listOf(1.05, 1.1, 1.2, 1.414, 2.0)) {
                Tetgen.tetrahedralize(surface, quality = bound, targetEdgeLength = 5.0)
                val times = DoubleArray(3) {
                    val t0 = System.nanoTime()
                    Tetgen.tetrahedralize(surface, quality = bound, targetEdgeLength = 5.0)
                    (System.nanoTime() - t0) / 1e6
                }
                val m = Tetgen.tetrahedralize(surface, quality = bound, targetEdgeLength = 5.0)
                val q = m.qualities().sorted()
                println("-q$bound -> ${m.cellCount} tets, ${"%.0f".format(times.sorted()[1])} ms, " +
                    "q min ${"%.4f".format(q.first())}, q 1st percentile " +
                    "${"%.4f".format(q[q.size / 100])}, q median ${"%.4f".format(q[q.size / 2])}, " +
                    "q<0.05 ${q.count { it < 0.05 }}, q<0.1 ${q.count { it < 0.1 }}")
            }
            Unit
        }
    }
}
