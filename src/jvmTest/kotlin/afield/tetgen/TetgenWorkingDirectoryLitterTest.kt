package afield.tetgen

import afield.manifold.ManifoldLibrary
import afield.stdlib.TriMesh
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TetGen writes files into the **process's current working directory** when it refuses a
 * self-intersecting surface, and neither the `Q` (quiet) switch nor `TETLIBRARY` stops it.
 *
 * Found on 2026-09-09 while measuring `public/docs/tetgen.md`: two `tetgen-tmpfile_skipped.node` /
 * `.face` files appeared at the root of a git worktree after a test run, holding the 16 vertices of
 * the two interpenetrating cubes that failed. In a live Field session the working directory is
 * wherever the server was launched from — the repository, or somebody's home directory.
 *
 * This is a *characterization* test. It does not assert that the behaviour is acceptable; it pins
 * what actually happens so that a future TetGen bump, or a fix in `field_tetgen.cpp`, is a visible
 * change rather than a silent one. It also cleans up, which is the only reason a run of the suite
 * does not leave the two files behind.
 *
 * The obvious fix — `chdir` into a temporary directory around the call — is *not* obviously right:
 * the working directory is process-global and Field's runloop, the docs runner and a user's own box
 * all share it, so a mesh running on a background thread would move everyone else's relative paths
 * for the duration. Recorded rather than fixed, deliberately.
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

    @Test fun aSelfIntersectingSurfaceLeavesTwoFilesInTheWorkingDirectory() {
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
        println("after a self-intersection refusal, TetGen left: $left in $here")
        // Not asserted as *required* — a TetGen version that stopped doing this would be an
        // improvement and must not fail the suite. Asserted only that we know which it was.
        println(if (left.size == 2) "  (the documented behaviour, unchanged)"
                else "  (TetGen's behaviour has changed — update public/docs/tetgen.md)")

        litter.forEach { File(here, it).delete() }
        assertTrue(litter.none { File(here, it).exists() }, "the cleanup must actually clean up")
    }
}
