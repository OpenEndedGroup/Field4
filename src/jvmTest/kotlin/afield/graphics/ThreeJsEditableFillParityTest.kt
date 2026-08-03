package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.filled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `editable = true` and `editable = false` must agree about how a filled FLine is triangulated.
 *
 * They are two different triangulations of the same rings, and that is deliberate rather than
 * accidental: editable mode lets the user drag points, so the fill has to follow between server
 * round-trips, which means the client has to be able to re-triangulate on its own. Until 2026-08-03
 * it did that with a naive fan over each subpath — no holes, no projection, no concavity — so the
 * same FLine could come back with different holes *and* different facing depending on a flag that is
 * supposed to be about editing.
 *
 * `threejs_viewer.html` now runs the same rule ([PlanarTessellation], transliterated as
 * `tessellateCoplanarGroups`), and this test is the guard on the seam. It cannot execute the
 * JavaScript — that is verified by real browser captures, in
 * `dev_docs/agents/artifacts/tessellate_facing/multi_plane_fill_demo/` — but it can pin the number
 * the two sides report for the same geometry, which is the thing that goes wrong first when one of
 * them is changed and the other is not.
 */
class ThreeJsEditableFillParityTest {

    /** One face: an outward-wound unit square with a square hole, on the plane through [origin]. */
    private fun face(f: FLine, origin: Vec3, u: Vec3, v: Vec3) {
        fun at(a: Float, b: Float) = Vec3(
            origin.x + u.x * a + v.x * b,
            origin.y + u.y * a + v.y * b,
            origin.z + u.z * a + v.z * b
        )
        listOf(
            listOf(at(0f, 0f), at(1f, 0f), at(1f, 1f), at(0f, 1f)),
            listOf(at(0.3f, 0.3f), at(0.3f, 0.7f), at(0.7f, 0.7f), at(0.7f, 0.3f))
        ).forEach { ring ->
            f.moveTo(ring[0])
            ring.drop(1).forEach { f.lineTo(it) }
            f.close()
        }
    }

    /** Three faces of a cube, six subpaths, three planes, three holes — in one filled FLine. */
    private fun multiPlaneHoledLine(): FLine {
        val h = 0.5f
        val f = FLine()
        face(f, Vec3(h, -h, h), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f))
        face(f, Vec3(-h, h, h), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f))
        face(f, Vec3(-h, -h, h), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f))
        f.filled = true
        return f
    }

    private fun triangles(line: FLine, editable: Boolean): Int =
        buildThreeGeometryPayload("test", listOf(line), 48, autoFrame = true, editable = editable)
            .meta?.triangles ?: 0

    /**
     * Three squares with three holes: `3 * (4 + 4 - 2) = 18`? No — a quad with a quad hole is eight
     * triangles, not six, because the hole boundary has to be bridged. `3 * 8 = 24`. The old fan
     * estimate said `3 * (4 - 2) + 3 * (4 - 2) = 12`, counting each hole ring as a solid quad and
     * ignoring the bridging entirely.
     */
    @Test
    fun bothModesReportTheSameTriangleCountForAMultiPlaneHoledLine() {
        val editable = triangles(multiPlaneHoledLine(), editable = true)
        val fixed = triangles(multiPlaneHoledLine(), editable = false)
        assertEquals(24, fixed, "three quads each with a quad hole is 3 x 8 triangles")
        assertEquals(fixed, editable, "editable mode disagrees about how many triangles this line has")
    }

    /** And for the ordinary single-plane single-hole case, which is what most filled FLines are. */
    @Test
    fun bothModesAgreeOnASquareWithAHole() {
        fun line(): FLine {
            val f = FLine()
            face(f, Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(0f, 100f, 0f))
            f.filled = true
            return f
        }
        assertEquals(8, triangles(line(), editable = false))
        assertEquals(triangles(line(), editable = false), triangles(line(), editable = true))
    }

    /** A plain quad with no holes, so the guard is not only exercised on the interesting cases. */
    @Test
    fun bothModesAgreeOnAPlainFilledQuad() {
        fun line(): FLine {
            val f = FLine()
            f.moveTo(Vec3(0f, 0f, 0f)).lineTo(Vec3(100f, 0f, 0f))
                .lineTo(Vec3(100f, 100f, 0f)).lineTo(Vec3(0f, 100f, 0f)).close()
            f.filled = true
            return f
        }
        assertEquals(2, triangles(line(), editable = false))
        assertEquals(triangles(line(), editable = false), triangles(line(), editable = true))
    }

    /** An unfilled line has no fill triangles in either mode. */
    @Test
    fun anUnfilledLineHasNoTrianglesInEitherMode() {
        fun line(): FLine {
            val f = FLine()
            f.moveTo(Vec3(0f, 0f, 0f)).lineTo(Vec3(100f, 0f, 0f)).lineTo(Vec3(100f, 100f, 0f)).close()
            return f
        }
        assertTrue(triangles(line(), editable = false) == 0)
        assertTrue(triangles(line(), editable = true) == 0)
    }
}
