package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clustering rule in [PlanarTessellation], tested where the decision is actually made.
 *
 * "Which rings are coplanar" is the design question that per-plane grouping cannot be done without,
 * and it is a question with a *tolerance* in it — which is precisely why the 2026-08-02 branch
 * stopped short of it and wrote the limit down instead. A tolerance that is not written down, not
 * scale free and not pinned by tests is a landmine, so each of the four decisions gets a test that
 * fails if the rule is quietly changed:
 *
 *  1. antiparallel counts as parallel — otherwise no hole in Field is a hole any more;
 *  2. the distance tolerance is a *fraction of the drawing*, never an absolute epsilon;
 *  3. an ambiguous ring joins the first plane it matches in ring order, not the nearest one, and a
 *     plane is represented by the ring that seeded it rather than by a running average;
 *  4. a ring with no normal cannot seed a plane.
 *
 * Nesting is asserted through [PlanarTessellation.Polygon.holes] rather than through total area,
 * because two different clusterings of the same rings can easily agree on the area while disagreeing
 * completely about what is a hole in what.
 */
class PlanarTessellationClusteringTest {

    // ----------------------------------------------------------------------------------- fixtures

    /** A counter-clockwise square of side [size] with its low corner at ([x], [y]), at height [z]. */
    private fun square(x: Float, y: Float, size: Float, z: Float = 0f): List<Vec3> = listOf(
        Vec3(x, y, z), Vec3(x + size, y, z), Vec3(x + size, y + size, z), Vec3(x, y + size, z)
    )

    private fun scaled(rings: List<List<Vec3>>, by: Float): List<List<Vec3>> =
        rings.map { ring -> ring.map { Vec3(it.x * by, it.y * by, it.z * by) } }

    /** A tessellation reduced to what the clustering decided: outer ring, and its holes. */
    private fun structure(rings: List<List<Vec3>>): List<Pair<Int, List<Int>>> =
        PlanarTessellation.tessellate(rings).map { it.outer to it.holes }

    private fun area(rings: List<List<Vec3>>): Float {
        var total = 0.0
        PlanarTessellation.tessellate(rings).forEach { polygon ->
            val t = polygon.triangles
            for (i in t.indices step 3) {
                val a = rings[t[i].ring][t[i].vertex]
                val b = rings[t[i + 1].ring][t[i + 1].vertex]
                val c = rings[t[i + 2].ring][t[i + 2].vertex]
                val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
                val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
                val cx = uy * vz - uz * vy
                val cy = uz * vx - ux * vz
                val cz = ux * vy - uy * vx
                total += 0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble())
            }
        }
        return total.toFloat()
    }

    /**
     * The distance tolerance the default [Coplanarity] implies for a set of rings, computed the same
     * way [PlanarTessellation] computes it, so a test can say "0.6 of a tolerance" and mean it.
     */
    private fun toleranceFor(rings: List<List<Vec3>>): Float {
        val points = rings.flatten()
        val dx = points.maxOf { it.x } - points.minOf { it.x }
        val dy = points.maxOf { it.y } - points.minOf { it.y }
        val dz = points.maxOf { it.z } - points.minOf { it.z }
        return Coplanarity.DEFAULT.offsetFraction * sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** A 100x100 figure in XY: the tolerance every test below measures its z offsets in. */
    private val unit = Coplanarity.DEFAULT.offsetFraction * sqrt(20000f)

    // ---------------------------------------------------------------------------- 1. antiparallel

    /**
     * A hole is almost always wound *against* the ring it is a hole in — that is how a hole is
     * authored, and it is what [FloatEarcut] expects. Its Newell normal therefore points the other
     * way. So the parallelism test has to be on the *unsigned* angle between normals; if it were not,
     * every hole in Field would stop being a hole at once.
     */
    @Test
    fun aHoleWoundAgainstItsOuterRingIsStillInTheSamePlane() {
        val rings = listOf(square(0f, 0f, 100f), square(30f, 30f, 40f).reversed())
        assertEquals(listOf(0 to listOf(1)), structure(rings), "the reversed ring is not a hole")
        assertTrue(abs(area(rings) - 8400f) < 1f, "area was ${area(rings)}")
    }

    /** And two coplanar rings wound against each other, side by side, each face their own way. */
    @Test
    fun twoDisjointCoplanarRingsWoundAgainstEachOtherEachFaceTheirOwnWay() {
        val rings = listOf(square(0f, 0f, 100f), square(200f, 0f, 60f).reversed())
        val polygons = PlanarTessellation.tessellate(rings)
        assertEquals(listOf(0 to emptyList<Int>(), 1 to emptyList()), polygons.map { it.outer to it.holes })
        assertTrue(polygons[0].normal.z > 0f, "ring 0 lost its winding: ${polygons[0].normal}")
        assertTrue(polygons[1].normal.z < 0f, "ring 1 lost its winding: ${polygons[1].normal}")
    }

    // ------------------------------------------------------------------------------- 2. scale free

    /**
     * The tolerance is a fraction of the input's diagonal, so *the same drawing at a different scale*
     * clusters identically. A glyph authored in screen pixels and the same glyph in unit-cube
     * coordinates are the same shape; an absolute epsilon would say they are not, and would silently
     * change its mind somewhere between the two.
     *
     * Both halves matter: an offset comfortably inside the tolerance must group, one comfortably
     * outside must not, and both answers must survive a scale of a thousand either way.
     */
    @Test
    fun theToleranceIsAFractionOfTheDrawingNotAnAbsoluteEpsilon() {
        val grouped = listOf(square(0f, 0f, 100f), square(30f, 30f, 40f, z = 0.5f * unit).reversed())
        val split = listOf(square(0f, 0f, 100f), square(30f, 30f, 40f, z = 5f * unit).reversed())

        assertEquals(
            listOf(0 to listOf(1)), structure(grouped),
            "half a tolerance of offset should still be one plane"
        )
        assertEquals(
            listOf(0 to emptyList<Int>(), 1 to emptyList()), structure(split),
            "five tolerances of offset is a different plane, so the inner ring fills instead"
        )

        listOf(0.001f, 1000f).forEach { by ->
            assertEquals(
                structure(grouped), structure(scaled(grouped, by)),
                "scaling the drawing by $by changed the clustering (the grouped case)"
            )
            assertEquals(
                structure(split), structure(scaled(split, by)),
                "scaling the drawing by $by changed the clustering (the split case)"
            )
        }
    }

    // -------------------------------------------------------------------------------- 3. ambiguity

    /**
     * The ambiguous case, and the answer this branch commits to.
     *
     * Three parallel planes: two far enough apart to stay separate, and a ring between them that is
     * within tolerance of *both*. Something has to break the tie, and the rule is **the first plane
     * in ring order**, not the nearest — because a nearest-plane rule needs a global pass over every
     * candidate, and because "first" is the only tie-break that stays stable as rings are appended.
     *
     * Written so the two rules disagree: the middle ring is nearer to `low` (0.6 of a tolerance) than
     * to `high` (0.95), so reordering the input changes which plane claims it. A nearest-plane
     * implementation would answer `low` both times and fail the second half.
     */
    @Test
    fun anAmbiguousRingJoinsTheFirstPlaneInRingOrderNotTheNearestOne() {
        val low = square(0f, 0f, 100f, z = 0f)
        val middle = square(30f, 30f, 40f, z = 0.6f * unit).reversed()
        val high = square(0f, 0f, 100f, z = 1.55f * unit)
        val tolerance = toleranceFor(listOf(low, middle, high))

        // the tie is real rather than an artefact of the numbers
        assertTrue(0.60f * unit < tolerance, "middle is not within tolerance of low")
        assertTrue(0.95f * unit < tolerance, "middle is not within tolerance of high")
        assertTrue(1.55f * unit > tolerance, "low and high would be one plane, so there is no tie")

        assertEquals(
            listOf(0 to listOf(1), 2 to emptyList()),
            structure(listOf(low, middle, high)),
            "listed first, `low` should claim the middle ring"
        )
        assertEquals(
            listOf(0 to listOf(2), 1 to emptyList()),
            structure(listOf(high, low, middle)),
            "listed first, `high` should claim the middle ring even though `low` is nearer"
        )
    }

    /**
     * A plane is represented by the ring that *seeded* it, never by a running average of its members,
     * so near-coplanarity does not accumulate: one ring 0.6 of a tolerance off joins, and a second
     * 1.2 off does not — where an averaging implementation would have drifted its plane up to 0.3 and
     * swallowed the second at 0.9.
     */
    @Test
    fun aPlaneIsTheRingThatSeededItNotARunningAverage() {
        val outer = square(0f, 0f, 100f, z = 0f)
        val near = square(20f, 20f, 20f, z = 0.6f * unit).reversed()
        val far = square(60f, 60f, 20f, z = 1.2f * unit).reversed()
        val rings = listOf(outer, near, far)

        assertEquals(
            listOf(0 to listOf(1), 2 to emptyList()),
            structure(rings),
            "`near` is a hole, `far` is its own surface"
        )
        // 10000 less the near hole, plus the far ring filling in its own plane, is 10000 again
        assertTrue(abs(area(rings) - 10000f) < 1f, "area was ${area(rings)}")
    }

    // ------------------------------------------------------------------------------- 4. degenerate

    /**
     * A ring whose Newell normal is zero has no plane to contribute and is not allowed to seed one.
     * Listed first — where a naive greedy pass would let it decide the frame — it must still leave the
     * real geometry that follows with its own. That is why rings without a normal are placed only
     * after every ring that has one has had its say.
     */
    @Test
    fun aRingWithoutANormalCannotSeedAPlane() {
        val collinear = listOf(Vec3(300f, 0f, 0f), Vec3(350f, 0f, 0f), Vec3(400f, 0f, 0f))
        val wall = listOf(
            Vec3(0f, 0f, 0f), Vec3(200f, 0f, 0f), Vec3(200f, 0f, 200f), Vec3(0f, 0f, 200f)
        )
        val rings = listOf(collinear, wall)
        assertEquals(listOf(1 to emptyList<Int>()), structure(rings), "the XZ wall should be the only fill")
        assertTrue(abs(area(rings) - 40000f) < 1f, "area was ${area(rings)}")
    }

    /** Every ring degenerate: no planes at all, no exception, and nothing filled. */
    @Test
    fun anInputOfNothingButDegenerateRingsFillsNothingAndDoesNotThrow() {
        val rings = listOf(
            listOf(Vec3(0f, 0f, 0f), Vec3(50f, 0f, 0f), Vec3(100f, 0f, 0f)),
            listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 50f), Vec3(0f, 0f, 100f))
        )
        assertTrue(PlanarTessellation.tessellate(rings).isEmpty())
    }

    // ---------------------------------------------------------------------------------- ordering

    /** Output order follows outer-ring index, not the order the planes happened to be discovered in. */
    @Test
    fun polygonsComeBackInRingOrderWhicheverOrderThePlanesWereFound() {
        val rings = listOf(
            square(0f, 0f, 100f),                                            // plane z = 0
            listOf(Vec3(0f, 0f, 0f), Vec3(50f, 0f, 0f), Vec3(50f, 0f, 50f)), // plane y = 0
            square(300f, 0f, 100f)                                           // plane z = 0 again
        )
        assertEquals(listOf(0, 1, 2), PlanarTessellation.tessellate(rings).map { it.outer })
    }
}
