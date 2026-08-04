package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [Coplanarity.sineOfAngle] had no tests at all: its default could have moved from `1e-3` to `0.5`
 * with every existing case still green, because nothing in the suite held two rings *near* enough to
 * one plane to care.
 *
 * So this file does two things. It pins the default **behaviourally** — a ring at half the tolerance
 * is a hole, the same ring at twice the tolerance is a polygon in its own right — and it *measures*
 * the property the tolerance does not have: "within `sineOfAngle` of" is not transitive, and greedy
 * seeding turns that into an order dependence strong enough to change whether a subpath is a hole.
 *
 * Everything is asserted on both implementations, because the viewer transliterates the tolerances
 * too (`threejs_viewer.html`, `COPLANAR_SINE_OF_ANGLE`).
 */
class CoplanarityToleranceTest {

    private val tolerance = 1e-3f

    /** A square in the `z = 0` plane, wound counter-clockwise unless [reversed]. */
    private fun square(cx: Float, cy: Float, half: Float, reversed: Boolean = false): List<Vec3> {
        val corners = listOf(
            Vec3(cx - half, cy - half, 0f),
            Vec3(cx + half, cy - half, 0f),
            Vec3(cx + half, cy + half, 0f),
            Vec3(cx - half, cy + half, 0f),
        )
        return if (reversed) corners.reversed() else corners
    }

    /**
     * Tilt a ring about the x axis **through its own centroid**, so the ring's normal turns by
     * [angle] while the ring itself stays centred on the plane it started in. That separates the two
     * tolerances: the angle changes, the offset barely does, so a test can put pressure on
     * [Coplanarity.sineOfAngle] alone.
     */
    private fun tilt(ring: List<Vec3>, angle: Float): List<Vec3> {
        val cy = ring.map { it.y }.average().toFloat()
        val c = cos(angle)
        val s = sin(angle)
        return ring.map { Vec3(it.x, cy + (it.y - cy) * c, (it.y - cy) * s) }
    }

    /** The sine of the angle between two rings' unit Newell normals, unsigned — the clustered quantity. */
    private fun sineBetween(a: List<Vec3>, b: List<Vec3>): Float {
        fun unit(ring: List<Vec3>): Vec3 {
            val n = PlanarTessellation.newellNormal(ring)
            val l = kotlin.math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
            return Vec3(n.x / l, n.y / l, n.z / l)
        }
        val (p, q) = unit(a) to unit(b)
        val cx = p.y * q.z - p.z * q.y
        val cy = p.z * q.x - p.x * q.z
        val cz = p.x * q.y - p.y * q.x
        return kotlin.math.sqrt(cx * cx + cy * cy + cz * cz)
    }

    /** A big square with a would-be hole tilted so its normal is `sine` away from the big one's. */
    private fun outerAndTiltedInner(sine: Float): List<List<Vec3>> {
        val outer = square(50f, 50f, 50f)
        val inner = tilt(square(50f, 50f, 20f, reversed = true), asin(sine))
        assertEquals(sine.toDouble(), sineBetween(outer, inner).toDouble(), 1e-6, "the fixture missed its angle")
        return listOf(outer, inner)
    }

    private fun holes(rings: List<List<Vec3>>, coplanarity: Coplanarity = Coplanarity.DEFAULT): String =
        PlanarTessellation.tessellate(rings, coplanarity)
            .joinToString(" ") { "${it.outer}<-${it.holes.joinToString(",", "[", "]")}" }

    // -------------------------------------------------------------------------- pinning the default

    /**
     * The characterization: `1e-3` is not decoration. Half of it clusters, twice it does not, and the
     * observable difference is whether a ring is subtracted from the one around it.
     */
    @Test
    fun theSineOfAngleDefaultDecidesWhetherANearCoplanarRingIsAHole() {
        assertEquals(1e-3f, Coplanarity().sineOfAngle, "the documented default moved")

        val inside = outerAndTiltedInner(tolerance * 0.5f)
        assertEquals("0<-[1]", holes(inside), "at half the tolerance the inner ring is a hole")
        assertEquals(8, PlanarTessellation.tessellate(inside).single().triangleCount)

        val outside = outerAndTiltedInner(tolerance * 2f)
        assertEquals("0<-[] 1<-[]", holes(outside), "at twice the tolerance it is a polygon of its own")

        // And the counterfactual, so the number itself is what this test is measuring: at 0.5 — the
        // value the review used as its example of a change nothing would notice — both cases cluster,
        // and the assertion above would have to be rewritten rather than merely re-run.
        assertEquals("0<-[1]", holes(outside, Coplanarity(sineOfAngle = 0.5f)))
    }

    /** The viewer's copy of the constant, and the same behavioural pair executed in its JavaScript. */
    @Test
    fun theViewerCarriesTheSameSineOfAngleAndTheSameBoundary() {
        val inside = outerAndTiltedInner(tolerance * 0.5f)
        val outside = outerAndTiltedInner(tolerance * 2f)

        ViewerJavaScript.open().use { js ->
            assertEquals(1e-3, js.member("COPLANAR_SINE_OF_ANGLE").asDouble(), 0.0)
            assertEquals(1e-3, js.member("COPLANAR_OFFSET_FRACTION").asDouble(), 0.0)
            assertEquals(holes(inside), jsHoles(js, inside))
            assertEquals(holes(outside), jsHoles(js, outside))
        }

        // The JavaScript's constant is load-bearing in the JavaScript, not merely equal to Kotlin's.
        ViewerJavaScript.perturbed { region ->
            region.replace("const COPLANAR_SINE_OF_ANGLE = 1e-3;", "const COPLANAR_SINE_OF_ANGLE = 0.5;")
        }.use { js ->
            assertNotEquals(holes(outside), jsHoles(js, outside), "the viewer's tolerance is unused")
            assertEquals("0<-[1]", jsHoles(js, outside))
        }
    }

    private fun jsHoles(js: ViewerJavaScript.Session, rings: List<List<Vec3>>): String {
        val json = rings.joinToString(",", "[", "]") { ring ->
            ring.joinToString(",", "[", "]") { "[${it.x.toDouble()},${it.y.toDouble()},${it.z.toDouble()}]" }
        }
        val polygons = js.call("tessellateCoplanarGroups", js.fromJson(json))
        return (0 until polygons.arraySize).joinToString(" ") { at ->
            val polygon = polygons.getArrayElement(at)
            val holes = polygon.getMember("holes")
            val list = (0 until holes.arraySize)
                .joinToString(",", "[", "]") { holes.getArrayElement(it).asInt().toString() }
            "${polygon.getMember("outer").asInt()}<-$list"
        }
    }

    // ------------------------------------------------------------------- what the tolerance is not

    /**
     * **`sineOfAngle` is not composition-invariant, and the clustering is therefore order-dependent
     * enough to change the answer.** Measured rather than argued, because the fix is not obvious and
     * the behaviour should be written down before anyone changes it.
     *
     * Three rings, pairwise angles `0`, `0.9 t` and `1.8 t` from a common reference:
     *
     *  * `bridge` is `0.9 t` from `outer` — inside the tolerance;
     *  * `inner` is `0.9 t` from `bridge` — inside the tolerance;
     *  * `inner` is `1.8 t` from `outer` — outside it.
     *
     * "Within `sineOfAngle` of" is not transitive, and a plane is represented by whichever ring seeded
     * it. So listing `outer` first gives three separate polygons and listing `bridge` first makes
     * `inner` a hole in `outer` — the same three rings, the same geometry, a different fill, decided by
     * subpath order.
     *
     * The greedy rule is a deliberate choice (`PlanarTessellation`'s "ambiguity resolves to the first
     * match" note) and the alternative — a global fit — has its own drift. What was missing is any
     * measurement of how large the consequence is. It is this large.
     */
    @Test
    fun theSineToleranceIsNotTransitiveSoRingOrderCanDecideWhetherARingIsAHole() {
        val outer = square(50f, 50f, 50f)
        val bridge = tilt(square(220f, 20f, 20f), asin(tolerance * 0.9f))
        val inner = tilt(square(50f, 50f, 10f, reversed = true), asin(tolerance * 1.8f))

        // The premise, asserted rather than assumed.
        assertTrue(sineBetween(outer, bridge) <= tolerance, "bridge should be inside the tolerance of outer")
        assertTrue(sineBetween(bridge, inner) <= tolerance, "inner should be inside the tolerance of bridge")
        assertTrue(sineBetween(outer, inner) > tolerance, "inner should be outside the tolerance of outer")

        val outerFirst = PlanarTessellation.tessellate(listOf(outer, bridge, inner))
        assertEquals(3, outerFirst.size, "outer seeds, so inner lands in a plane of its own and fills")
        assertTrue(outerFirst.all { it.holes.isEmpty() })

        val bridgeFirst = PlanarTessellation.tessellate(listOf(bridge, outer, inner))
        assertEquals(2, bridgeFirst.size, "bridge seeds, so all three share a plane and inner is a hole")
        assertEquals(listOf(2), bridgeFirst.single { it.holes.isNotEmpty() }.holes)

        // The viewer inherits the quirk, which is the right answer for a transliteration: agreeing
        // about the awkward case is the whole point of having a differential test.
        ViewerJavaScript.open().use { js ->
            assertEquals(holes(listOf(outer, bridge, inner)), jsHoles(js, listOf(outer, bridge, inner)))
            assertEquals(holes(listOf(bridge, outer, inner)), jsHoles(js, listOf(bridge, outer, inner)))
        }
    }

    /**
     * The other tolerance, for completeness: [Coplanarity.offsetFraction] is a fraction of the whole
     * input's diagonal, so it is the *offset* that a pure translation out of plane puts pressure on.
     */
    @Test
    fun theOffsetFractionDefaultDecidesWhetherAParallelRingIsAHole() {
        assertEquals(1e-3f, Coplanarity().offsetFraction, "the documented default moved")
        fun lifted(by: Float) = listOf(
            square(50f, 50f, 50f),
            square(50f, 50f, 20f, reversed = true).map { Vec3(it.x, it.y, by) },
        )
        // The diagonal is ~141 for a 100x100 square, so the tolerance is ~0.141.
        assertEquals("0<-[1]", holes(lifted(0.07f)))
        assertEquals("0<-[] 1<-[]", holes(lifted(0.28f)))

        // Dimensionless: scaling the whole drawing must not change the clustering.
        fun scaled(rings: List<List<Vec3>>, by: Float) = rings.map { ring ->
            ring.map { Vec3(it.x * by, it.y * by, it.z * by) }
        }
        assertEquals(holes(lifted(0.07f)), holes(scaled(lifted(0.07f), 1000f)))
        assertEquals(holes(lifted(0.28f)), holes(scaled(lifted(0.28f), 1000f)))
    }
}
