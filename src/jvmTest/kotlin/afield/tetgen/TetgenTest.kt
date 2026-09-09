package afield.tetgen

import afield.kratos.SimulationBounds
import afield.kratos.SimulationBox
import afield.kratos.SimulationBoolean
import afield.kratos.SimulationGroup
import afield.kratos.SimulationSphere
import afield.manifold.ManifoldLibrary
import afield.stdlib.TriMesh
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class TetgenTest {

    /** The wrapper, or null with a reason. Optional like Manifold and Ceres. */
    private fun available(): Boolean {
        if (!TetgenLibrary.isAvailable) {
            println("SKIPPED TetGen tests: ${TetgenLibrary.describe()}")
            return false
        }
        if (!ManifoldLibrary.isAvailable) {
            println("SKIPPED TetGen tests: ${ManifoldLibrary.describe()}")
            return false
        }
        return true
    }

    @Test fun missingLibraryIsReportedNotThrownAtClassLoad() {
        // Touching the object must not link anything; that is what makes this optional.
        assertTrue(TetgenLibrary.fileName.contains("field_tetgen_jvm"))
        assertTrue(TetgenLibrary.searchedPaths.isNotEmpty())
        assertTrue(TetgenLibrary.HOW_TO_BUILD.contains("build_tetgen_native.py"))
    }

    @Test fun aCubeMeshesToItsExactVolume() {
        if (!available()) return
        // Manifold's cube is exactly 12 triangles with exact corners, and its volume is exact, so
        // this is the sharp-edge claim measured rather than asserted: a mesher that rounded the
        // edges could not land within machine epsilon of 2*3*4.
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 2.0, 3.0, 4.0))
        val exact = cube.solid().use { it.checked().volume }
        assertEquals(24.0, exact, 1e-12)
        val mesh = Tetgen.tetrahedralize(cube.surface(), targetEdgeLength = 0.5)
        assertTrue(mesh.cellCount > 100, "expected a refined mesh, got ${mesh.cellCount}")
        assertTrue(abs(mesh.volume() - exact) / exact < 1e-12,
            "volume ${mesh.volume()} against exact $exact")
        assertEquals(0, mesh.volumes().count { it <= 0.0 }, "no cell may be inverted or degenerate")
    }

    @Test fun aBooleanBodyMatchesManifoldsOwnVolume() {
        if (!available()) return
        val body = SimulationGroup("body", SimulationBoolean.UNION, listOf(
            SimulationBox("slab", SimulationBounds(-.62, -.24, -.24, .18, .24, .24)),
            SimulationSphere("head", .34, 0.0, 0.0, .30, segments = 48)))
        val exact = body.solid().use { it.checked().volume }
        val mesh = Tetgen.tetrahedralize(body.surface(), targetEdgeLength = 0.125)
        assertTrue(abs(mesh.volume() - exact) / exact < 1e-6,
            "volume ${mesh.volume()} against Manifold's $exact")
        assertEquals(0, mesh.volumes().count { it <= 0.0 })
        // Refining changes the element count and must not change the shape.
        val finer = Tetgen.tetrahedralize(body.surface(), targetEdgeLength = 0.0625)
        assertTrue(finer.cellCount > mesh.cellCount)
        assertTrue(abs(finer.volume() - exact) / exact < 1e-6)
    }

    @Test fun anOpenSurfaceIsRefusedWithoutTakingTheProcessDown() {
        if (!available()) return
        // Five faces of a cube: closed enough to pass our own arity checks and get all the way
        // into TetGen, which signals a fatal condition by throwing. If that exception were allowed
        // to unwind through the FFM frames it would abort the VM rather than raise here, so this
        // test is really about the try/catch in field_tetgen.cpp.
        val corners = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
        val faces = intArrayOf(
            0, 1, 2, 0, 2, 3,      // bottom
            4, 6, 5, 4, 7, 6,      // top
            0, 4, 5, 0, 5, 1,      // front
            1, 5, 6, 1, 6, 2,      // right
            2, 6, 7, 2, 7, 3)      // back — the left face is missing
        val open = TriMesh(corners, faces)
        val failure = assertFails { Tetgen.tetrahedralize(open, targetEdgeLength = 0.25) }
        assertTrue(failure is TetgenException || failure is IllegalStateException,
            "expected a structured refusal, got ${failure::class.simpleName}: ${failure.message}")
        // And the library still works afterwards: the failure did not corrupt any global state.
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        assertTrue(Tetgen.tetrahedralize(cube.surface(), targetEdgeLength = 0.5).cellCount > 0)
    }

    @Test fun nonsensicalOptionsAreRefusedBeforeTetGenSeesThem() {
        if (!available()) return
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)).surface()
        assertFails { Tetgen.tetrahedralize(cube, quality = 0.5) }
        assertFails { Tetgen.tetrahedralize(cube, targetEdgeLength = 0.0) }
        assertFails { Tetgen.tetrahedralize(cube, targetEdgeLength = -1.0) }
        assertFails { Tetgen.tetrahedralize(cube, order = 3) }
        assertFails { Tetgen.tetrahedralize(cube, order = 0) }
        assertFails { Tetgen.tetrahedralize(TriMesh(FloatArray(0), IntArray(0))) }
    }

    @Test fun tetMeshValidatesItsOwnConnectivity() {
        assertFails { TetMesh(doubleArrayOf(0.0, 0.0), intArrayOf()) }
        assertFails { TetMesh(DoubleArray(12), intArrayOf(0, 1, 2)) }
        assertFails { TetMesh(DoubleArray(12), intArrayOf(0, 1, 2, 9)) }
        assertFails { TetMesh(DoubleArray(12), intArrayOf(0, 1, 2, 3), nodesPerCell = 5) }
        // 10 indices are not a whole number of 4-node cells, and must not be read as two-and-a-half.
        assertFails { TetMesh(DoubleArray(30), IntArray(10), nodesPerCell = 4) }
        val unit = TetMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            intArrayOf(0, 1, 2, 3))
        assertEquals(4, unit.nodeCount)
        assertEquals(1, unit.cellCount)
        assertEquals(1.0 / 6.0, unit.volume(), 1e-15)
    }

    /**
     * The load-bearing check for quadratic tetrahedra: every midside node is where Kratos's
     * `Tetrahedra3D10` will assume it is.
     *
     * Getting this wrong does not crash and does not look wrong — the element solves, and returns a
     * displacement field that is quietly incorrect. So the assertion is geometric and exhaustive:
     * for every element, node `4+k` must sit at the midpoint of the k-th edge in Kratos's order.
     */
    @Test
    fun quadraticMidsideNodesAreWhereKratosExpectsThem() {
        if (!available()) return
        val body = SimulationGroup("body", SimulationBoolean.UNION, listOf(
            SimulationBox("slab", SimulationBounds(-.62, -.24, -.24, .18, .24, .24)),
            SimulationSphere("head", .34, 0.0, 0.0, .30, segments = 32)))
        val mesh = Tetgen.tetrahedralize(body.surface(), targetEdgeLength = 0.2, order = 2)
        assertEquals(10, mesh.nodesPerCell)
        assertTrue(mesh.isQuadratic)
        assertTrue(mesh.cellCount > 20, "expected a real mesh, got ${mesh.cellCount}")

        val edges = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(0, 2),
                            intArrayOf(0, 3), intArrayOf(1, 3), intArrayOf(2, 3))
        var worst = 0.0
        for (cell in 0 until mesh.cellCount) {
            val base = 10 * cell
            for ((k, edge) in edges.withIndex()) {
                val a = mesh.cells[base + edge[0]]
                val b = mesh.cells[base + edge[1]]
                val mid = mesh.cells[base + 4 + k]
                for (c in 0 until 3) {
                    val expected = 0.5 * (mesh.positions[3 * a + c] + mesh.positions[3 * b + c])
                    worst = maxOf(worst, abs(mesh.positions[3 * mid + c] - expected))
                }
            }
        }
        // TetGen forms these as 0.5*(p+q) in double, so agreement is to the last bit, not to a
        // tolerance. Anything above zero here means the permutation is being inferred, not read.
        assertEquals(0.0, worst, 0.0, "midside node displaced by $worst from its edge midpoint")
    }

    @Test fun quadraticRefinesTheInterpolationAndNotTheShape() {
        if (!available()) return
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 2.0, 3.0, 4.0))
        val linear = Tetgen.tetrahedralize(cube.surface(), targetEdgeLength = 0.5)
        val quadratic = Tetgen.tetrahedralize(cube.surface(), targetEdgeLength = 0.5, order = 2)

        // Same tetrahedralization underneath: -o2 runs after refinement, so the element count and
        // the occupied volume are unchanged and only the node count grows.
        assertEquals(linear.cellCount, quadratic.cellCount)
        assertEquals(24.0, quadratic.volume(), 1e-10)
        assertTrue(quadratic.nodeCount > linear.nodeCount * 2,
            "10-node tetrahedra add a node per edge: ${linear.nodeCount} -> ${quadratic.nodeCount}")
        assertEquals(0, quadratic.volumes().count { it <= 0.0 })

        // And `corners()` is the linear view of it — same cells, same shape, no midside indices.
        val corners = quadratic.corners()
        assertEquals(4, corners.nodesPerCell)
        assertEquals(quadratic.cellCount, corners.cellCount)
        assertEquals(quadratic.volume(), corners.volume(), 1e-12)
        assertTrue(corners.cells.contentEquals(
            IntArray(4 * quadratic.cellCount) { quadratic.cells[10 * (it / 4) + it % 4] }))
        assertTrue(corners.cells.contentEquals(linear.cells),
            "promotion must not disturb the tetrahedralization it was handed")
    }

    /**
     * Adjacent tetrahedra must *share* the node on the edge between them.
     *
     * Giving each element its own copy would satisfy every midpoint check above and still be wrong:
     * the mesh would be a pile of tetrahedra touching at their corners only, free to open up along
     * every shared face. A solve on it converges to a body made of unglued pieces.
     */
    @Test
    fun midsideNodesAreSharedSoTheMeshStaysConforming() {
        if (!available()) return
        val cube = SimulationBox("c", SimulationBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        val linear = Tetgen.tetrahedralize(cube.surface(), targetEdgeLength = 0.34)
        val quadratic = linear.quadratic()

        // Count the distinct edges of the linear mesh independently of the promotion.
        val edges = HashSet<Long>()
        for (t in 0 until linear.cellCount) for (a in 0 until 4) for (b in a + 1 until 4) {
            val p = linear.cells[4 * t + a]
            val q = linear.cells[4 * t + b]
            edges.add((minOf(p, q).toLong() shl 32) or maxOf(p, q).toLong())
        }
        assertEquals(linear.nodeCount + edges.size, quadratic.nodeCount,
            "exactly one new node per distinct edge, shared by every element on it")

        // Every midside index must be one no corner uses, and every new node must be referenced.
        val referenced = quadratic.cells.toHashSet()
        assertEquals(quadratic.nodeCount, referenced.size, "no orphaned nodes")
        assertTrue(quadratic.isQuadratic)
        // Idempotent: promoting an already-quadratic mesh is the identity, not a 22-node element.
        assertTrue(quadratic.quadratic() === quadratic)
    }
}
