package afield.geometry.mesh.tetgen

import afield.geometry.mesh.TriMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TetgenSeamWarningTest {
    private val corners = floatArrayOf(
        0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
        0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
    private val closed = intArrayOf(
        0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
        1, 5, 6, 1, 6, 2, 2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0)

    @Test fun aCleanCubeHasNoSeamProblems() {
        assertEquals(emptyList(), Tetgen.seamProblems(TriMesh(corners, closed)))
    }

    @Test fun anUnweldedCornerIsNamedBeforeMeshing() {
        // the page's coincident-corner cube: meshes to a plausible 2/3 of a cube
        val collapsed = corners.copyOf().also { it[3] = 0f; it[4] = 0f; it[5] = 0f }
        val problems = Tetgen.seamProblems(TriMesh(collapsed, closed))
        assertTrue(problems.any { "not welded" in it }, problems.toString())
    }
}
