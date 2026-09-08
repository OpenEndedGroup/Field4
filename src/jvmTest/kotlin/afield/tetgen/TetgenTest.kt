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
        assertFails { Tetgen.tetrahedralize(TriMesh(FloatArray(0), IntArray(0))) }
    }

    @Test fun tetMeshValidatesItsOwnConnectivity() {
        assertFails { TetMesh(doubleArrayOf(0.0, 0.0), intArrayOf()) }
        assertFails { TetMesh(DoubleArray(12), intArrayOf(0, 1, 2)) }
        assertFails { TetMesh(DoubleArray(12), intArrayOf(0, 1, 2, 9)) }
        val unit = TetMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            intArrayOf(0, 1, 2, 3))
        assertEquals(4, unit.nodeCount)
        assertEquals(1, unit.cellCount)
        assertEquals(1.0 / 6.0, unit.volume(), 1e-15)
    }
}
