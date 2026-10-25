package afield.geometry.mesh.tetgen

import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.mesh.TriMesh
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Refusing a self-intersecting surface must leave the process's working directory as it was.
 *
 * Stock TetGen dumps `tetgen-tmpfile_skipped.node` / `.face` into the cwd at that point, and neither
 * the `Q` (quiet) switch nor `TETLIBRARY` stops it; in a live Field session the cwd is wherever the
 * server was launched from. `tools/build_tetgen_native.py` compiles the dump out of the pinned
 * source under `TETLIBRARY`, and this pins that it stays out.
 *
 * `chdir` into a temporary directory around the call would have been wrong: the working directory
 * is process-global, and Field's runloop, the docs runner and a user's own box all share it.
 */
class TetgenWorkingDirectoryLitterTest {

    private val litter = listOf("tetgen-tmpfile_skipped.node", "tetgen-tmpfile_skipped.face")

    private fun cube(offsetX: Float): Pair<FloatArray, IntArray> {
        val corners = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f)
        for (i in corners.indices step 3) corners[i] += offsetX
        return corners to intArrayOf(
            0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
            1, 5, 6, 1, 6, 2, 2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0)
    }

    @Test fun aSelfIntersectingSurfaceLeavesTheWorkingDirectoryClean() {
        if (!TetgenLibrary.isAvailable || !ManifoldLibrary.isAvailable) {
            println("SKIPPED: ${TetgenLibrary.describe()}")
            return
        }
        val here = File(".").absoluteFile.parentFile
        litter.forEach { File(here, it).delete() }

        val (a, faces) = cube(0f)
        val (b, _) = cube(0.5f)
        val positions = a + b
        val indices = faces + IntArray(faces.size) { faces[it] + 8 }

        val failure = runCatching { Tetgen.tetrahedralize(TriMesh(positions, indices), targetEdgeLength = 0.4) }
            .exceptionOrNull()
        assertTrue(failure is TetgenException, "expected a refusal, got $failure")
        assertTrue(failure.message!!.contains("aborted with code"),
            "this input is meant to reach TetGen's own fatal path: ${failure.message}")

        val left = litter.filter { File(here, it).exists() }
        litter.forEach { File(here, it).delete() }
        assertTrue(left.isEmpty(), "TetGen wrote $left into $here; is the native library built from the patched source?")
    }
}
