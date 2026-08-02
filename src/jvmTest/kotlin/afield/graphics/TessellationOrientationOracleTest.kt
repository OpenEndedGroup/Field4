package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An orientation oracle for [FLine.tessellate].
 *
 * `e4b30939` made the central filled-FLine tessellation path project onto the dominant Newell axis
 * before handing the ring to [FloatEarcut], so that a filled FLine that is planar in *any*
 * orientation can be filled — not just one lying in XY. That is the right idea, and this test is
 * the thing that was missing when it landed: the invariant that makes "any orientation" a
 * meaningful claim.
 *
 * **The invariant.** Tessellation is a geometric operation, so it must commute with rigid motion.
 * Take one polygon (with holes), tessellate it, then take the *same polygon rigidly rotated* into
 * another plane and tessellate that. The two results must agree on everything that is not the
 * choice of coordinates:
 *
 *  * the same number of triangles,
 *  * the same total surface area (so holes are still holes, not filled and not double-counted),
 *  * every triangle wound consistently *with respect to the polygon's own normal* — because the
 *    consumers of these triangles ([ThreeJsViewer.appendFilledMesh], `ThreeJsViewer.kt:850`, and
 *    `StandardDrawing.linesOut`, `StandardDrawing.kt:134`) put them straight into a mesh whose
 *    facing is read from that winding.
 *
 * The reference orientation is the XY plane, which is the only one the pre-existing tests ever
 * exercised.
 */
class TessellationOrientationOracleTest {

    // ----------------------------------------------------------------------------------- geometry

    /** A rigid rotation, stored row-major, applied to points and to normals alike. */
    private class Rot(val name: String, private val m: FloatArray) {
        operator fun invoke(p: Vec3): Vec3 = Vec3(
            m[0] * p.x + m[1] * p.y + m[2] * p.z,
            m[3] * p.x + m[4] * p.y + m[5] * p.z,
            m[6] * p.x + m[7] * p.y + m[8] * p.z
        )

        fun then(o: Rot): Rot {
            val r = FloatArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += o.m[3 * i + k] * m[3 * k + j]
                r[3 * i + j] = s
            }
            return Rot("${name}+${o.name}", r)
        }

        companion object {
            fun identity() = Rot("XY (identity)", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

            fun aboutX(deg: Double): Rot {
                val c = cos(Math.toRadians(deg)).toFloat()
                val s = sin(Math.toRadians(deg)).toFloat()
                return Rot("rotX($deg)", floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c))
            }

            fun aboutY(deg: Double): Rot {
                val c = cos(Math.toRadians(deg)).toFloat()
                val s = sin(Math.toRadians(deg)).toFloat()
                return Rot("rotY($deg)", floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c))
            }

            fun aboutZ(deg: Double): Rot {
                val c = cos(Math.toRadians(deg)).toFloat()
                val s = sin(Math.toRadians(deg)).toFloat()
                return Rot("rotZ($deg)", floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f))
            }
        }
    }

    private fun cross(a: Vec3, b: Vec3) = Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    )

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    private fun length(a: Vec3) = sqrt(dot(a, a))

    /** Newell normal of a ring, in the ring's authored winding. */
    private fun newell(ring: List<Vec3>): Vec3 {
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in ring.indices) {
            val p = ring[i]
            val q = ring[(i + 1) % ring.size]
            nx += (p.y - q.y) * (p.z + q.z)
            ny += (p.z - q.z) * (p.x + q.x)
            nz += (p.x - q.x) * (p.y + q.y)
        }
        return Vec3(nx, ny, nz)
    }

    private fun normalize(a: Vec3): Vec3 {
        val l = length(a)
        return if (l == 0f) a else Vec3(a.x / l, a.y / l, a.z / l)
    }

    // ------------------------------------------------------------------------------------- shapes

    /** A CCW unit square scaled to [size], offset to [at]. */
    private fun square(x: Float, y: Float, size: Float): List<Vec3> = listOf(
        Vec3(x, y, 0f),
        Vec3(x + size, y, 0f),
        Vec3(x + size, y + size, 0f),
        Vec3(x, y + size, 0f)
    )

    /** The reference figure: a 100x100 square with a 40x40 hole. Area 10000 - 1600 = 8400. */
    private fun squareWithHole(): List<List<Vec3>> = listOf(
        square(0f, 0f, 100f),
        square(30f, 30f, 40f).reversed()
    )

    /** Build a filled FLine out of rings, each vertex passed through [xf]. */
    private fun lineOf(rings: List<List<Vec3>>, xf: Rot): FLine {
        val f = FLine()
        rings.forEach { ring ->
            val moved = ring.map { xf(it) }
            f.moveTo(moved[0])
            moved.drop(1).forEach { f.lineTo(it) }
            f.close()
        }
        return f
    }

    // ------------------------------------------------------------------------------------ measure

    private class Tri(val a: Vec3, val b: Vec3, val c: Vec3)

    private fun triangulate(f: FLine): List<Tri> {
        val out = ArrayList<Tri>()
        f.tessellate(4) { verts, index ->
            var i = 0
            while (i + 2 < index.size) {
                fun v(k: Int) = Vec3(verts[3 * k], verts[3 * k + 1], verts[3 * k + 2])
                out.add(Tri(v(index[i]), v(index[i + 1]), v(index[i + 2])))
                i += 3
            }
        }
        return out
    }

    /** What we assert about one tessellation, expressed in coordinate-free terms. */
    private class Measured(
        val triangles: Int,
        val area: Float,
        /** Signed area along the polygon's own normal: +area if wound with it, -area if against. */
        val signedAlongNormal: Float,
        /** How much area is wound the *wrong* way relative to the majority. */
        val inconsistentArea: Float
    )

    private fun measure(tris: List<Tri>, normal: Vec3): Measured {
        val n = normalize(normal)
        var total = 0f
        var signed = 0f
        var pos = 0f
        var neg = 0f
        tris.forEach { t ->
            val c = cross(Vec3(t.b.x - t.a.x, t.b.y - t.a.y, t.b.z - t.a.z),
                Vec3(t.c.x - t.a.x, t.c.y - t.a.y, t.c.z - t.a.z))
            val a = 0.5f * length(c)
            total += a
            val s = 0.5f * dot(c, n)
            signed += s
            if (s >= 0) pos += a else neg += a
        }
        return Measured(tris.size, total, signed, minOf(pos, neg))
    }

    private fun measureRotated(rings: List<List<Vec3>>, xf: Rot): Measured {
        val line = lineOf(rings, xf)
        val normal = newell(rings[0].map { xf(it) })
        return measure(triangulate(line), normal)
    }

    // ------------------------------------------------------------------------------- orientations

    /**
     * Every orientation we care about. The three axis-aligned planes appear in *both* hemispheres,
     * because a face whose normal points along -y is exactly as ordinary as one pointing along +y
     * (it is the other side of a wall) and the projection has to cope with both.
     */
    private fun orientations(): List<Rot> = listOf(
        Rot.identity(),                                  // XY, normal +z
        Rot.aboutX(180.0),                               // XY, normal -z
        Rot.aboutX(-90.0),                               // XZ, normal +y
        Rot.aboutX(90.0),                                // XZ, normal -y
        Rot.aboutY(90.0),                                // YZ, normal +x
        Rot.aboutY(-90.0),                               // YZ, normal -x
        Rot.aboutX(35.0).then(Rot.aboutY(24.0)),         // oblique
        Rot.aboutZ(17.0).then(Rot.aboutX(-62.0)).then(Rot.aboutY(41.0)) // oblique, other side
    )

    // --------------------------------------------------------------------------------------- area

    /**
     * The headline invariant: rotate the figure anywhere and it still has the same triangle count
     * and the same area. This is the part that says holes are still holes in every plane.
     */
    @Test
    fun aFilledPolygonWithAHoleTessellatesTheSameInEveryPlane() {
        val rings = squareWithHole()
        val reference = measureRotated(rings, Rot.identity())

        assertEquals(8400f, reference.area, 1f, "sanity: the XY reference figure is 100x100 less 40x40")

        val failures = StringBuilder()
        orientations().forEach { xf ->
            val m = measureRotated(rings, xf)
            if (m.triangles != reference.triangles) {
                failures.append("\n  ${xf.name}: ${m.triangles} triangles, reference has ${reference.triangles}")
            }
            if (abs(m.area - reference.area) > 1f) {
                failures.append("\n  ${xf.name}: area ${m.area}, reference has ${reference.area}")
            }
        }
        assertTrue(failures.isEmpty(), "tessellation is not invariant under rigid rotation:$failures")
    }

    // ------------------------------------------------------------------------------------ winding

    /**
     * The invariant the dominant-axis projection actually breaks.
     *
     * [FloatEarcut] *normalises* the ring orientation it is given (`FloatEarcut.kt:705` reverses the
     * outer ring when its signed area has the wrong sign), so the winding of the triangles that come
     * back is decided entirely by the projected coordinate frame — never by the input winding. That
     * makes the choice of projected axis pair a choice about which way every emitted triangle faces.
     *
     * For the projection to preserve orientation, the pair must be right-handed about the dominant
     * axis: x -> (y, z), y -> (z, x), z -> (x, y). And the pair has to be *flipped* when the
     * polygon's normal points down the negative half of that axis, or the whole face comes back
     * inside out.
     */
    @Test
    fun everyTriangleFacesTheWayThePolygonDoes() {
        val rings = squareWithHole()
        val failures = StringBuilder()
        orientations().forEach { xf ->
            val m = measureRotated(rings, xf)
            if (m.inconsistentArea > 1f) {
                failures.append("\n  ${xf.name}: ${m.inconsistentArea} of ${m.area} is wound against the rest")
            }
            if (m.signedAlongNormal < 0f) {
                failures.append(
                    "\n  ${xf.name}: triangles face away from the polygon normal " +
                            "(signed area ${m.signedAlongNormal}, unsigned ${m.area})"
                )
            }
        }
        assertTrue(failures.isEmpty(), "emitted triangle winding does not follow the polygon:$failures")
    }

    /**
     * Winding, reduced to the smallest figure that shows it: one triangle, in each of the six
     * axis-aligned orientations. No holes, no concavity — so a failure here is unambiguously the
     * projection and nothing else.
     */
    @Test
    fun aSingleTriangleFacesTheWayItWasAuthoredInAllSixAxisDirections() {
        val tri = listOf(listOf(Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(0f, 100f, 0f)))
        val failures = StringBuilder()
        orientations().forEach { xf ->
            val m = measureRotated(tri, xf)
            if (m.signedAlongNormal < 0f) {
                failures.append("\n  ${xf.name}: signed area ${m.signedAlongNormal}, expected +${m.area}")
            }
        }
        assertTrue(failures.isEmpty(), "a single triangle is emitted back-to-front:$failures")
    }

    // -------------------------------------------------------------------------------------- holes

    /**
     * Nested rings: a square, a hole, and an island inside the hole. Depth parity decides what is
     * solid, and that decision runs on the projected coordinates too.
     */
    @Test
    fun nestedRingsKeepTheirParityInEveryPlane() {
        val rings = listOf(
            square(0f, 0f, 100f),
            square(20f, 20f, 60f).reversed(),
            square(40f, 40f, 20f)
        )
        val expected = 10000f - 3600f + 400f
        val failures = StringBuilder()
        orientations().forEach { xf ->
            val m = measureRotated(rings, xf)
            if (abs(m.area - expected) > 1f) {
                failures.append("\n  ${xf.name}: area ${m.area}, expected $expected")
            }
        }
        assertTrue(failures.isEmpty(), "nested-ring parity is not rotation invariant:$failures")
    }

    /**
     * Two disjoint filled subpaths, each with its own hole, so the parent search has to distinguish
     * "inside a different ring" from "inside a sibling's bounding box".
     */
    @Test
    fun disjointHolesAreClassifiedAgainstTheRightParentInEveryPlane() {
        val rings = listOf(
            square(0f, 0f, 100f),
            square(30f, 30f, 40f).reversed(),
            square(200f, 0f, 100f),
            square(230f, 30f, 40f).reversed()
        )
        val expected = 2 * (10000f - 1600f)
        val failures = StringBuilder()
        orientations().forEach { xf ->
            val m = measureRotated(rings, xf)
            if (abs(m.area - expected) > 1f) {
                failures.append("\n  ${xf.name}: area ${m.area}, expected $expected")
            }
        }
        assertTrue(failures.isEmpty(), "disjoint holes lose their parent under rotation:$failures")
    }

    /**
     * One FLine, two disjoint filled subpaths, wound *against each other*.
     *
     * Coplanar is not the same as co-oriented. A single FLine can perfectly well hold one contour
     * authored counter-clockwise and another authored clockwise — a glyph pair where one letter came
     * from a mirrored source, two faces of the same solid flattened into one line, anything built by
     * `plusAssign`. They are separate polygons with separate normals, so each has to be filled facing
     * its own way. A single hemisphere decision taken from whichever ring in the whole FLine happens
     * to be biggest gets the smaller one backwards.
     */
    @Test
    fun eachDisjointSubpathFacesItsOwnWinding() {
        val clockwise = square(200f, 0f, 60f).reversed() // normal -z, deliberately the smaller
        val counterClockwise = square(0f, 0f, 100f)      // normal +z, the stronger ring
        val rings = listOf(counterClockwise, clockwise)

        val failures = StringBuilder()
        orientations().forEach { xf ->
            val line = lineOf(rings, xf)
            val tris = triangulate(line)
            // split the triangles back out by which subpath they came from, on the x extent
            rings.forEachIndexed { index, ring ->
                val world = ring.map { xf(it) }
                val normal = newell(world)
                val centre = Vec3(
                    world.map { it.x }.average().toFloat(),
                    world.map { it.y }.average().toFloat(),
                    world.map { it.z }.average().toFloat()
                )
                val mine = tris.filter { t ->
                    val other = rings[1 - index].map { xf(it) }
                    val otherCentre = Vec3(
                        other.map { it.x }.average().toFloat(),
                        other.map { it.y }.average().toFloat(),
                        other.map { it.z }.average().toFloat()
                    )
                    val g = Vec3((t.a.x + t.b.x + t.c.x) / 3f, (t.a.y + t.b.y + t.c.y) / 3f, (t.a.z + t.b.z + t.c.z) / 3f)
                    val dm = length(Vec3(g.x - centre.x, g.y - centre.y, g.z - centre.z))
                    val doo = length(Vec3(g.x - otherCentre.x, g.y - otherCentre.y, g.z - otherCentre.z))
                    dm < doo
                }
                if (mine.isEmpty()) {
                    failures.append("\n  ${xf.name}: subpath $index produced no triangles")
                    return@forEachIndexed
                }
                val m = measure(mine, normal)
                if (m.signedAlongNormal < 0f) {
                    failures.append(
                        "\n  ${xf.name}: subpath $index faces backwards " +
                                "(signed ${m.signedAlongNormal}, unsigned ${m.area})"
                    )
                }
            }
        }
        assertTrue(failures.isEmpty(), "oppositely wound subpaths do not each face their own way:$failures")
    }

    /** A concave ring, whose bounding box lies about containment, in every plane. */
    @Test
    fun aConcaveRingTessellatesToItsOwnAreaInEveryPlane() {
        // an L, 100x100 with a 60x60 bite out of the top right
        val rings = listOf(
            listOf(
                Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(100f, 40f, 0f),
                Vec3(40f, 40f, 0f), Vec3(40f, 100f, 0f), Vec3(0f, 100f, 0f)
            )
        )
        val expected = 10000f - 3600f
        val failures = StringBuilder()
        orientations().forEach { xf ->
            val m = measureRotated(rings, xf)
            if (abs(m.area - expected) > 1f) {
                failures.append("\n  ${xf.name}: area ${m.area}, expected $expected")
            }
        }
        assertTrue(failures.isEmpty(), "a concave ring is not rotation invariant:$failures")
    }
}
