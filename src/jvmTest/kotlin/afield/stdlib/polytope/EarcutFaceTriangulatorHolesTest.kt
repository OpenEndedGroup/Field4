package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [EarcutFaceTriangulator]'s hole support, which is the thing the convention change bought.
 *
 * Before this branch the triangulator projected a y-dominant face onto `(x, z)` — the *mirror* of the
 * right-handed cycle `y -> (z, x)` — and then flipped each returned triangle back against the face's
 * 3D normal. That reached the right answer for a face with no holes, and it could not be extended to
 * one with holes: `FloatEarcut` decides which of the rings it is handed is the outer one from the
 * *sign of its projected area*, so a mirrored frame inverts the hole-versus-outer decision itself,
 * before any triangle exists to flip. `FieldAdapters.kt` said as much in a comment; this test is what
 * that comment becomes now that the frame is fixed.
 *
 * Holes are not reachable from [PolyhedralObject.triangulate] — a `faceCycle` is a single cycle and
 * the topology has no notion of a hole in a face — so [HoleAwareFaceTriangulator] is the surface, and
 * this is what makes it a capability rather than a claim.
 */
class EarcutFaceTriangulatorHolesTest {

    // ----------------------------------------------------------------------------------- machinery

    /** A rigid rotation, so the same figure can be asked in every orientation. */
    private class Rot(val name: String, private val m: DoubleArray) {
        operator fun invoke(p: Vec3): Vec3 = Vec3(
            (m[0] * p.x + m[1] * p.y + m[2] * p.z).toFloat(),
            (m[3] * p.x + m[4] * p.y + m[5] * p.z).toFloat(),
            (m[6] * p.x + m[7] * p.y + m[8] * p.z).toFloat()
        )

        fun then(o: Rot): Rot {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += o.m[3 * i + k] * m[3 * k + j]
                r[3 * i + j] = s
            }
            return Rot("$name+${o.name}", r)
        }

        companion object {
            fun identity() = Rot("XY", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

            fun aboutX(deg: Double): Rot {
                val c = cos(Math.toRadians(deg))
                val s = sin(Math.toRadians(deg))
                return Rot("rotX($deg)", doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
            }

            fun aboutY(deg: Double): Rot {
                val c = cos(Math.toRadians(deg))
                val s = sin(Math.toRadians(deg))
                return Rot("rotY($deg)", doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
            }
        }
    }

    /** Every orientation that matters, both hemispheres of each principal axis, plus an oblique. */
    private fun orientations(): List<Rot> = listOf(
        Rot.identity(),
        Rot.aboutX(180.0),
        Rot.aboutX(-90.0),
        Rot.aboutX(90.0),
        Rot.aboutY(90.0),
        Rot.aboutY(-90.0),
        Rot.aboutX(35.0).then(Rot.aboutY(24.0))
    )

    private fun square(x: Float, y: Float, size: Float): List<Vec3> = listOf(
        Vec3(x, y, 0f), Vec3(x + size, y, 0f), Vec3(x + size, y + size, 0f), Vec3(x, y + size, 0f)
    )

    private fun cross(a: Vec3, b: Vec3) =
        Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    private fun normalize(a: Vec3): Vec3 {
        val l = sqrt(dot(a, a))
        return if (l == 0f) a else Vec3(a.x / l, a.y / l, a.z / l)
    }

    /** Newell normal of an ordered ring of realization indices. */
    private fun newell(ring: IntArray, r: Realization3): Vec3 {
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in ring.indices) {
            val p = r[ring[i]]
            val q = r[ring[(i + 1) % ring.size]]
            nx += (p.y - q.y) * (p.z + q.z)
            ny += (p.z - q.z) * (p.x + q.x)
            nz += (p.x - q.x) * (p.y + q.y)
        }
        return Vec3(nx, ny, nz)
    }

    /** Total area of a returned index triple list, and the part of it wound against [normal]. */
    private fun measure(indices: IntArray, r: Realization3, normal: Vec3): Pair<Float, Float> {
        val n = normalize(normal)
        var total = 0f
        var against = 0f
        for (i in indices.indices step 3) {
            val a = r[indices[i]]
            val c = cross(sub(r[indices[i + 1]], a), sub(r[indices[i + 2]], a))
            val area = 0.5f * sqrt(dot(c, c))
            total += area
            if (0.5f * dot(c, n) < 0f) against += area
        }
        return total to against
    }

    // ------------------------------------------------------------------------------------- a hole

    /**
     * A 100x100 face with a 40x40 hole, in every orientation. Area 8400 — so the hole really is
     * subtracted rather than filled or double counted — and every triangle wound with the face's own
     * 3D normal, with no after-the-fact flip anywhere in the path.
     */
    @Test
    fun aFaceWithAHoleTriangulatesToItsOwnAreaInEveryOrientation() {
        val failures = StringBuilder()
        orientations().forEach { xf ->
            val points = (square(0f, 0f, 100f) + square(30f, 30f, 40f).reversed()).map { xf(it) }
            val realization = Realization3(points)
            val outer = intArrayOf(0, 1, 2, 3)
            val hole = intArrayOf(4, 5, 6, 7)

            val indices = EarcutFaceTriangulator.triangulate(outer, listOf(hole), realization)
            if (indices.size % 3 != 0 || indices.isEmpty()) {
                failures.append("\n  ${xf.name}: ${indices.size} indices")
                return@forEach
            }
            if (indices.any { it !in points.indices }) {
                failures.append("\n  ${xf.name}: referenced a vertex outside the face")
            }
            val (total, against) = measure(indices, realization, newell(outer, realization))
            if (abs(total - 8400f) > 1f) {
                failures.append("\n  ${xf.name}: area $total, expected 8400 (100x100 less 40x40)")
            }
            if (against > 1f) {
                failures.append("\n  ${xf.name}: $against of $total is wound against the face normal")
            }
        }
        assertTrue(failures.isEmpty(), "a face with a hole is not triangulated correctly:$failures")
    }

    /** Two disjoint holes, so hole classification has to survive more than one candidate parent. */
    @Test
    fun twoHolesAreBothSubtracted() {
        val points = square(0f, 0f, 100f) +
                square(10f, 10f, 20f).reversed() +
                square(60f, 60f, 30f).reversed()
        val realization = Realization3(points)
        val outer = intArrayOf(0, 1, 2, 3)
        val indices = EarcutFaceTriangulator.triangulate(
            outer, listOf(intArrayOf(4, 5, 6, 7), intArrayOf(8, 9, 10, 11)), realization
        )
        val (total, against) = measure(indices, realization, newell(outer, realization))
        assertTrue(abs(total - (10000f - 400f - 900f)) < 1f, "area was $total")
        assertTrue(against < 1f, "$against of $total is wound backwards")
    }

    /**
     * A hole ring wound the *same* way as its outer ring is still a hole. Nesting decides what is a
     * hole, not winding — and [afield.graphics.FloatEarcut] normalises whatever winding it is given
     * anyway, which is exactly why the projected frame has to be right.
     */
    @Test
    fun aHoleWoundTheSameWayAsItsFaceIsStillAHole() {
        val points = (square(0f, 0f, 100f) + square(30f, 30f, 40f)).map { Rot.aboutX(-90.0)(it) }
        val realization = Realization3(points)
        val outer = intArrayOf(0, 1, 2, 3)
        val indices = EarcutFaceTriangulator.triangulate(outer, listOf(intArrayOf(4, 5, 6, 7)), realization)
        val (total, against) = measure(indices, realization, newell(outer, realization))
        assertTrue(abs(total - 8400f) < 1f, "area was $total, expected 8400")
        assertTrue(against < 1f, "$against of $total is wound backwards")
    }

    // ------------------------------------------------------------------------- the old entry point

    /** The no-holes signature still exists, and agrees with passing an empty hole list. */
    @Test
    fun theSingleCycleEntryPointIsTheSameCallWithNoHoles() {
        val points = square(0f, 0f, 100f).map { Rot.aboutY(90.0)(it) }
        val realization = Realization3(points)
        val cycle = intArrayOf(0, 1, 2, 3)
        assertEquals(
            EarcutFaceTriangulator.triangulate(cycle, listOf(), realization).toList(),
            (EarcutFaceTriangulator as FaceTriangulator).triangulate(cycle, realization).toList()
        )
    }

    /** The preconditions that were there before are still there, and a hole ring gets its own. */
    @Test
    fun degenerateInputStillFailsLoudly() {
        val realization = Realization3(square(0f, 0f, 100f) + listOf(Vec3(1f, 1f, 0f), Vec3(2f, 2f, 0f)))
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                EarcutFaceTriangulator.triangulate(intArrayOf(0, 1), realization)
            }.message.orEmpty().contains("at least three face vertices")
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                EarcutFaceTriangulator.triangulate(intArrayOf(0, 1, 2, 3), listOf(intArrayOf(4, 5)), realization)
            }.message.orEmpty().contains("hole ring 0 needs at least three vertices")
        )
        val flat = Realization3(listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f)))
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                EarcutFaceTriangulator.triangulate(intArrayOf(0, 1, 2), flat)
            }.message.orEmpty().contains("zero projected area")
        )
    }
}
