package afield.graphics

import afield.stdlib.boxMesh
import org.graalvm.polyglot.Value
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A click on a solid names the element it hit — asserted against geometry, not against itself.
 *
 * ### What this is measuring
 *
 * `// [viewer-region surface-hit-picking]` is the mesh half of the viewer's hit record: given the
 * triangle three.js says a ray met and the point it met it at, decide whether the person meant a
 * **vertex**, an **edge** or the **triangle's interior**, and report barycentric coordinates and the
 * point on that element. It is lifted out of the shipped `threejs_viewer.html` by [ViewerJavaScript]
 * and run here on GraalJS, exactly as `ThreeInteractiveEntryDifferentialTest` and
 * `ThreeCameraPresetParityTest` run theirs.
 *
 * ### Every expectation below is worked out by hand
 *
 * Not one of them is "whatever the region returned last time". The right triangle
 * `(0,0,0) (10,0,0) (0,10,0)` was chosen because every quantity in it is a schoolbook one — the
 * barycentric weights of `(3, 3, 0)` are `x/10` and `y/10`, the distance to the hypotenuse is
 * `|x + y − 10| / √2` — so the arithmetic in the assertions can be read and checked without running
 * anything. A test whose expectations came out of the implementation would agree with the
 * implementation and prove nothing.
 *
 * ### The inputs that could make it fail, named
 *
 * A classifier tested only in the fat middle of a large triangle cannot fail. So the corpus contains,
 * deliberately:
 *
 *  * **a point nearer to two edges than to the corner it should snap to.** `(0.6, 0.6, 0)` is 0.6 from
 *    edge AB and 0.6 from edge CA, and 0.8485 from vertex A. Every edge-first implementation returns
 *    `edge` here; the correct answer is `vertex`.
 *    [aPointNearerAnEdgeThanTheCornerStillSnapsToTheCorner] asserts *both* the geometric fact and the
 *    classification, so the trap cannot quietly stop being a trap;
 *  * **a sliver**, where no interior hit is possible at all and saying otherwise would be a lie;
 *  * **a back face**, which a winding-blind implementation calls front-facing;
 *  * **a degenerate triangle**, where the honest answer is a refusal rather than `(⅓, ⅓, ⅓)`;
 *  * **a non-indexed geometry**, where reading the triangle wrong yields a plausible triangle from the
 *    wrong place — the failure that looks like a tolerance bug for a day.
 *
 * ### And the cross-type measurement
 *
 * [aSolidAndAnFLineAnnotationMeasureAcrossTheTwoRegions] is the capability the whole night was for. It
 * runs **two** regions — this one and `inspector-measure` — and measures from a triangle of a mesh to
 * an FLine annotation floating above it. The expected answer is 2.0 exactly, by construction, and the
 * test also computes what the answer would have been had the surface hit degraded to the raw click
 * point: 3.3826. The gap is what proves the polygon really travelled between the two regions.
 *
 * `a section through a solid reports the cross-section it actually cut` is the same discipline applied
 * to the other half of the night's work: it pins the exact number a real run of the demo prints.
 *
 * ### An audit of the driven headline, and what it did not cover
 *
 * The 2026-08-16 browser capture reported twenty-five real clicks reading `150 mm` across a mesh and an
 * FLine. On tracing it, that reading **could not have failed** for two whole classes of defect: the
 * measure elements are built from corner *indices* and never read `u`, `v` or `w`, and on that figure
 * `edge` and `triangle` both answer 150 anyway. The four tests under *what the headline did NOT test*
 * are the configurations where each of those can fail —
 * [the kind of element picked changes the measured distance] gives three distinct hand-derived
 * distances for the three kinds, [aWrongBarycentricWeightGivesAWrongPointOnTheElement] makes a swapped
 * weight produce a wrong distance and shows the centroid where it would not have, and
 * [a weight swap leaves every measured dimension untouched — which is why 150 mm could not have failed]
 * pins the boundary itself so it cannot silently move.
 */
class ThreeSurfaceHitDifferentialTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "surface-hit-picking",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("surface-hit-picking"),
            perturbation = perturbation,
        )

    private fun measureSession() =
        ViewerJavaScript.Session.open(
            regionName = "inspector-measure",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-measure"),
        )

    // ------------------------------------------------------------------ the figures

    /**
     * The right triangle every hand-derived number below refers to.
     *
     * Legs of 10 along `+x` and `+y`, hypotenuse `√200 = 14.142136`. Which makes the snap radius
     * `0.14 × 14.142136 = 1.979899` — a number this file writes out rather than asks the region for,
     * see [theSnapRadiusIsAFractionOfTheLongestEdge].
     */
    private val rightTriangle = "[[0,0,0],[10,0,0],[0,10,0]]"

    private val hypotenuse = sqrt(200.0)
    private val snapFraction = 0.14
    private val rightTolerance = hypotenuse * snapFraction

    /** A sliver: 10 long and 0.5 tall, so its snap radius (1.4) exceeds its own height. */
    private val sliver = "[[0,0,0],[10,0,0],[5,0.5,0]]"

    private fun Value.d(name: String) = getMember(name).asDouble()
    private fun Value.s(name: String) = getMember(name).asString()
    private fun Value.i(name: String) = getMember(name).asInt()
    private fun Value.point(name: String): Triple<Double, Double, Double> {
        val p = getMember(name)
        return Triple(p.getArrayElement(0).asDouble(), p.getArrayElement(1).asDouble(), p.getArrayElement(2).asDouble())
    }

    private fun ViewerJavaScript.Session.classify(
        triangle: String,
        point: String,
        tolerance: Double,
        ray: String? = null,
    ): Value = call("surfacePickClassify", fromJson(triangle), fromJson(point), tolerance, ray?.let { fromJson(it) })

    // ------------------------------------------------------------------ the tolerance

    /**
     * The snap radius is a fraction of the triangle's **longest edge**, and that choice is the design.
     *
     * A barycentric threshold would be scale-free too, and would be wrong: 0.05 of the way across a
     * triangle 0.01 units wide is 0.0005 units, which nobody can aim at. Asserted against `0.14 × √200`
     * written out longhand, so this fails if either the fraction or the "longest edge" half changes.
     */
    @Test
    fun theSnapRadiusIsAFractionOfTheLongestEdge() {
        session().use { js ->
            assertEquals(0.14, js.member("SURFACE_PICK_SNAP_FRACTION").asDouble(), 0.0)
            assertEquals(hypotenuse, js.call("surfacePickLongestEdge", js.fromJson(rightTriangle)).asDouble(), 1e-12)
            assertEquals(
                rightTolerance,
                js.call("surfacePickTolerance", js.fromJson(rightTriangle), snapFraction).asDouble(),
                1e-12,
            )
            // …and it scales with the figure rather than being a world constant: the same triangle at
            // a thousandth of the size gets a thousandth of the radius, so a millimetre model and a
            // metre model classify the same click identically.
            assertEquals(
                rightTolerance / 1000.0,
                js.call("surfacePickTolerance", js.fromJson("[[0,0,0],[0.01,0,0],[0,0.01,0]]"), snapFraction)
                    .asDouble(),
                1e-15,
            )
        }
    }

    // ------------------------------------------------------------------ the three kinds

    /**
     * The interior, with barycentric coordinates anyone can check.
     *
     * On this triangle the weight of `B` is `x/10` and the weight of `C` is `y/10`, so `(3, 3, 0)` is
     * `(0.4, 0.3, 0.3)`. And it really is an interior hit: its distance to the nearest edge is
     * `|3 + 3 − 10| / √2 = 2.828`, comfortably outside the 1.980 snap radius, which the test states as
     * an assertion rather than leaving to the reader.
     */
    @Test
    fun `the middle of a triangle is a triangle hit, with checkable barycentric coordinates`() {
        val distanceToHypotenuse = abs(3.0 + 3.0 - 10.0) / sqrt(2.0)
        assertTrue(
            distanceToHypotenuse > rightTolerance,
            "the fixture stopped being an interior point: $distanceToHypotenuse <= $rightTolerance",
        )
        session().use { js ->
            val hit = js.classify(rightTriangle, "[3,3,0]", rightTolerance)
            assertEquals("triangle", hit.s("kind"))
            assertEquals(0.4, hit.d("u"), 1e-12)
            assertEquals(0.3, hit.d("v"), 1e-12)
            assertEquals(0.3, hit.d("w"), 1e-12)
            assertEquals(-1, hit.i("corner"))
            assertEquals(-1, hit.i("edgeIndex"))
            assertEquals(0.0, hit.d("distance"), 0.0)
            assertEquals(Triple(3.0, 3.0, 0.0), hit.point("onElement"))
        }
    }

    /**
     * An edge hit, on the edge the point is actually nearest to.
     *
     * `(5, 0.9, 0)` is 0.9 from `AB`, 2.899 from the hypotenuse and 5.0 from `CA`; its nearest corner
     * is 5.08 away, which is outside the snap radius, so the vertex test correctly declines. The point
     * *on* the element is the perpendicular foot, `(5, 0, 0)`.
     */
    @Test
    fun `a point near one edge is an edge hit, and names the right edge`() {
        assertTrue(hypot(5.0, 0.9) > rightTolerance, "the fixture drifted into the corner snap radius")
        session().use { js ->
            val hit = js.classify(rightTriangle, "[5,0.9,0]", rightTolerance)
            assertEquals("edge", hit.s("kind"))
            assertEquals(0, hit.i("edgeIndex"), "edge 0 is (a, b), which is the one 0.9 away")
            assertEquals(-1, hit.i("corner"))
            assertEquals(0.9, hit.d("distance"), 1e-12)
            assertEquals(Triple(5.0, 0.0, 0.0), hit.point("onElement"))
            // The barycentric coordinates are still reported for an edge hit, and are still the hit's.
            assertEquals(0.5, hit.d("v"), 1e-12)
            assertEquals(0.09, hit.d("w"), 1e-12)
            assertEquals(0.41, hit.d("u"), 1e-12)
        }
    }

    /**
     * **The input a naive implementation gets wrong.**
     *
     * `(0.6, 0.6, 0)` sits 0.6 from edge `AB`, 0.6 from edge `CA`, and 0.8485 from vertex `A`. It is
     * *nearer to two edges than to the vertex* — necessarily so, by the triangle inequality, for every
     * point near every corner — so an implementation that asks "am I near an edge?" first can never
     * report a vertex at all, on any mesh, ever. The first assertion below pins that geometric fact, so
     * that the second one cannot quietly become vacuous if somebody moves the fixture.
     */
    @Test
    fun aPointNearerAnEdgeThanTheCornerStillSnapsToTheCorner() {
        val toCorner = hypot(0.6, 0.6)
        val toEdge = 0.6
        assertTrue(toEdge < toCorner, "the trap stopped being a trap: $toEdge is no longer under $toCorner")
        assertTrue(toCorner <= rightTolerance, "the fixture drifted outside the snap radius")

        session().use { js ->
            val hit = js.classify(rightTriangle, "[0.6,0.6,0]", rightTolerance)
            assertEquals("vertex", hit.s("kind"), "an edge-first classifier returns `edge` here")
            assertEquals(0, hit.i("corner"))
            assertEquals(-1, hit.i("edgeIndex"))
            assertEquals(toCorner, hit.d("distance"), 1e-12)
            assertEquals(Triple(0.0, 0.0, 0.0), hit.point("onElement"))
        }
    }

    /**
     * Counterfactual: put the edge test first and the trap springs.
     *
     * The perturbation is the smallest possible expression of the mistake — `<= tol` on the corner
     * branch becomes `< 0`, which is exactly "never snap to a corner" — and the point above then comes
     * back as an edge, which is the wrong answer this test exists to keep out.
     */
    @Test
    fun `counterfactual — a classifier that skips the corner test reports an edge`() {
        session { it.replace("if (cornerDistance <= tol) {", "if (cornerDistance < 0) {") }.use { js ->
            val hit = js.classify(rightTriangle, "[0.6,0.6,0]", rightTolerance)
            assertEquals("edge", hit.s("kind"), "the perturbation should have disabled corner snapping")
        }
    }

    // ------------------------------------------------------------------ the sliver

    /**
     * A sliver has no clickable interior, and the region says so rather than pretending.
     *
     * The fixture is 10 long and 0.5 tall, so its snap radius is `0.14 × 10 = 1.4` — nearly three times
     * its own height. Every point of it is therefore within snap distance of an edge or a corner, and
     * the honest classification of *every* point in it is `edge` or `vertex`. A barycentric-threshold
     * implementation would happily report `triangle` for the middle of it, at a place where the surface
     * is half a unit wide and no user could distinguish interior from edge.
     *
     * Swept over a grid rather than asserted at one point, because "no interior anywhere" is the claim.
     */
    @Test
    fun `a sliver has no interior a click can land in`() {
        val tolerance = 10.0 * snapFraction
        assertTrue(tolerance > 0.5, "the fixture stopped being a sliver relative to its own snap radius")
        session().use { js ->
            var interior = 0
            var sampled = 0
            for (i in 1..39) {
                val x = i * 0.25
                for (j in 1..9) {
                    val y = j * 0.05
                    // stay inside the triangle: it rises to (5, 0.5) and falls again
                    val ceiling = if (x <= 5.0) x * 0.1 else (10.0 - x) * 0.1
                    if (y >= ceiling) continue
                    sampled += 1
                    val kind = js.classify(sliver, "[$x,$y,0]", tolerance).s("kind")
                    if (kind == "triangle") interior += 1
                }
            }
            assertTrue(sampled > 100, "the sweep only found $sampled points inside the sliver")
            assertEquals(0, interior, "$interior of $sampled points in a sliver claimed to be interior hits")
        }
    }

    /** …and the same sweep on a well-conditioned triangle *does* find an interior, or it proves nothing. */
    @Test
    fun `a well conditioned triangle does have an interior, so the sliver result means something`() {
        // The interior region is bounded by the three snap bands: `x > 1.98`, `y > 1.98` and
        // `x + y < 10 − 1.98·√2 = 7.20`. That is a triangle of area 5.25, so a 0.25 grid should find
        // about 84 points in it — which is what the floor below is set just under.
        session().use { js ->
            var interior = 0
            for (i in 1..39) {
                for (j in 1..39) {
                    val x = i * 0.25
                    val y = j * 0.25
                    if (x + y >= 10.0) continue
                    if (js.classify(rightTriangle, "[$x,$y,0]", rightTolerance).s("kind") == "triangle") interior += 1
                }
            }
            assertTrue(interior > 60, "only $interior interior hits on a 10×10 right triangle")
        }
    }

    // ------------------------------------------------------------------ facing and refusal

    /**
     * Front or back is read off the **winding**, which is the question back-face culling asks.
     *
     * `(0,0,0) (10,0,0) (0,10,0)` winds counter-clockwise seen from `+z`, so its normal is `+z` and a
     * ray travelling along `+z` meets its back. Reversing two corners reverses the answer. Both facts
     * come from the right-hand rule and not from the code.
     */
    @Test
    fun `a ray that meets the back of a triangle is told so`() {
        session().use { js ->
            assertTrue(
                js.classify(rightTriangle, "[3,3,0]", rightTolerance, "[0,0,-1]").getMember("frontFacing").asBoolean(),
                "a ray along -z meets the front of a +z-facing triangle",
            )
            assertTrue(
                !js.classify(rightTriangle, "[3,3,0]", rightTolerance, "[0,0,1]").getMember("frontFacing").asBoolean(),
                "a ray along +z meets its back",
            )
            // Reverse the winding and the same ray meets the other face.
            val flipped = "[[0,0,0],[0,10,0],[10,0,0]]"
            assertTrue(
                js.classify(flipped, "[3,3,0]", rightTolerance, "[0,0,1]").getMember("frontFacing").asBoolean(),
                "reversing the winding must reverse the facing",
            )
            // With no ray to compare against, the neutral answer.
            assertTrue(js.classify(rightTriangle, "[3,3,0]", rightTolerance).getMember("frontFacing").asBoolean())
        }
    }

    /**
     * A degenerate triangle is a refusal, not a plausible number.
     *
     * Both shapes of degeneracy — collapsed to a point, and collinear — have no interior to be at a
     * position within, and `(⅓, ⅓, ⅓)` would be a confident answer to a question with none.
     */
    @Test
    fun `a degenerate triangle is refused rather than guessed at`() {
        session().use { js ->
            listOf(
                "[[1,2,3],[1,2,3],[1,2,3]]" to "collapsed to a point",
                "[[0,0,0],[1,0,0],[2,0,0]]" to "collinear",
                "[[0,0,0],[0,0,0],[5,0,0]]" to "two corners coincident",
            ).forEach { (triangle, why) ->
                val hit = js.classify(triangle, "[0.5,0,0]", 1.0)
                assertEquals("", hit.s("kind"), "a triangle $why should be refused")
                assertTrue(hit.d("u").isNaN(), "a refused hit must not carry barycentric coordinates")
                assertTrue(hit.getMember("onElement").isNull)
                assertTrue(js.call("surfacePickBarycentric", js.fromJson(triangle), js.fromJson("[0.5,0,0]")).isNull)
            }
        }
    }

    /** The degeneracy test is *scaled*, so a perfectly good millimetre-model triangle is not refused. */
    @Test
    fun `a tiny but honest triangle is not mistaken for a degenerate one`() {
        session().use { js ->
            // 1 µm on a side. Its d00*d11 is 1e-24, which any fixed epsilon of 1e-12 would reject.
            val tiny = "[[0,0,0],[1e-6,0,0],[0,1e-6,0]]"
            val hit = js.classify(tiny, "[3e-7,3e-7,0]", 1e-6 * sqrt(2.0) * snapFraction)
            assertEquals("triangle", hit.s("kind"), "a 1 µm triangle is a triangle")
            assertEquals(0.3, hit.d("v"), 1e-6)
            assertEquals(0.3, hit.d("w"), 1e-6)
        }
    }

    // ------------------------------------------------------------------ reading the triangle out

    /**
     * Indexed and non-indexed geometry both read out correctly.
     *
     * The non-indexed case is here because getting it wrong does not throw: it produces a plausible
     * triangle from somewhere else in the buffer, and the symptom is hits landing on the wrong part of
     * the model.
     */
    @Test
    fun `a triangle is read out of both indexed and non-indexed geometry`() {
        // A unit square as two triangles: 0-1-2 and 0-2-3.
        val positions = "[0,0,0, 1,0,0, 1,1,0, 0,1,0]"
        val index = "[0,1,2, 0,2,3]"
        session().use { js ->
            val second = js.call("surfacePickTriangle", js.fromJson(positions), js.fromJson(index), 1)
            assertEquals(Triple(0.0, 0.0, 0.0), second.getArrayElement(0).let {
                Triple(it.getArrayElement(0).asDouble(), it.getArrayElement(1).asDouble(), it.getArrayElement(2).asDouble())
            })
            assertEquals(1.0, second.getArrayElement(1).getArrayElement(0).asDouble(), 0.0)
            assertEquals(1.0, second.getArrayElement(1).getArrayElement(1).asDouble(), 0.0)
            assertEquals(0.0, second.getArrayElement(2).getArrayElement(0).asDouble(), 0.0)
            assertEquals(1.0, second.getArrayElement(2).getArrayElement(1).asDouble(), 0.0)

            val indices = js.call("surfacePickVertexIndices", js.fromJson(positions), js.fromJson(index), 1)
            assertEquals(0, indices.getArrayElement(0).asInt())
            assertEquals(2, indices.getArrayElement(1).asInt())
            assertEquals(3, indices.getArrayElement(2).asInt())

            // Non-indexed: six vertices, two triangles, consecutive.
            val flat = "[0,0,0, 1,0,0, 1,1,0, 0,0,0, 1,1,0, 0,1,0]"
            val nonIndexed = js.call("surfacePickTriangle", js.fromJson(flat), null, 1)
            assertEquals(0.0, nonIndexed.getArrayElement(0).getArrayElement(0).asDouble(), 0.0)
            assertEquals(1.0, nonIndexed.getArrayElement(1).getArrayElement(0).asDouble(), 0.0)
            assertEquals(0.0, nonIndexed.getArrayElement(2).getArrayElement(0).asDouble(), 0.0)
            assertEquals(1.0, nonIndexed.getArrayElement(2).getArrayElement(1).asDouble(), 0.0)

            // Out of range is null, not a triangle of zeros.
            assertTrue(js.call("surfacePickTriangle", js.fromJson(positions), js.fromJson(index), 7).isNull)
            assertTrue(js.call("surfacePickTriangle", js.fromJson(positions), js.fromJson(index), -1).isNull)
        }
    }

    /** The three kinds resolve to one, two or three points — the shapes `measureElement` already takes. */
    @Test
    fun `each kind of hit resolves to the measure shape it should`() {
        session().use { js ->
            val tri = js.fromJson(rightTriangle)
            fun points(point: String): Value =
                js.call("surfacePickElementPoints", tri, js.classify(rightTriangle, point, rightTolerance))

            assertEquals(1L, points("[0.6,0.6,0]").arraySize, "a vertex hit is one point")
            assertEquals(2L, points("[5,0.9,0]").arraySize, "an edge hit is a segment")
            assertEquals(3L, points("[3,3,0]").arraySize, "an interior hit is the whole triangle")

            // The vertex hit's one point is the corner itself, not the click.
            val corner = points("[0.6,0.6,0]").getArrayElement(0)
            assertEquals(0.0, corner.getArrayElement(0).asDouble(), 0.0)
            assertEquals(0.0, corner.getArrayElement(1).asDouble(), 0.0)

            // A refused hit yields no points at all, so the caller falls back rather than measuring to
            // the world origin.
            assertTrue(
                js.call(
                    "surfacePickElementPoints",
                    js.fromJson("[[1,1,1],[1,1,1],[1,1,1]]"),
                    js.classify("[[1,1,1],[1,1,1],[1,1,1]]", "[1,1,1]", 1.0),
                ).isNull,
            )
        }
    }

    // ------------------------------------------------------------------ the point of the whole thing

    /**
     * **A solid and an FLine annotation, measured across the two regions.**
     *
     * This is the capability `Three.kt`'s KDoc promised on 2026-08-15 and could not deliver: a mesh and
     * its annotations in one scene, and a distance between them.
     *
     * The figure: the triangle `(0,0,0) (10,0,0) (10,10,0)`, lying in `z = 0`; and an annotation
     * segment from `(9, 1, 2)` to `(9.5, 0.5, 2)`, floating two units above it. Both ends of the
     * annotation drop perpendicularly into the triangle's interior — each has `0 < y < x < 10` — so the
     * closest approach is **exactly 2.0**, by construction and with no arithmetic to get wrong.
     *
     * The click is placed at the triangle's **incentre**, `(7.0711, 2.9289)`. Not for elegance: the
     * incentre is the point furthest from every edge at once, `r = area / s = 50 / 17.071 = 2.9289`,
     * which is the only way to be sure a fixture is an interior hit rather than an edge hit that
     * happens to pass — the first version of this test used a point 0.05 from an edge and classified as
     * `edge`, correctly, and the assertion was wrong rather than the code.
     *
     * It is also a long way from the annotation, and that is deliberate: if the surface hit degraded to
     * the raw click point — which is what the pre-2026-08-16 viewer would have had to do, having no
     * surface element at all — the answer would be **3.3826**, the distance from the incentre to the
     * segment's near end. The test computes that number too and asserts the gap, so a regression that
     * quietly drops the polygon cannot pass by coincidence.
     *
     * Two regions, run in two sessions and joined in Kotlin, because `ViewerJavaScript` lifts one region
     * at a time — which is itself the invariant that keeps each of them testable alone.
     */
    @Test
    fun aSolidAndAnFLineAnnotationMeasureAcrossTheTwoRegions() {
        val triangle = "[[0,0,0],[10,0,0],[10,10,0]]"
        // The incentre: (a·A + b·B + c·C) / (a + b + c), with each side length opposite its vertex.
        val incircleRadius = 50.0 / ((10.0 + 10.0 + hypotenuse) / 2.0)
        val cx = (10.0 * 0.0 + hypotenuse * 10.0 + 10.0 * 10.0) / (20.0 + hypotenuse)
        val cy = (10.0 * 0.0 + hypotenuse * 0.0 + 10.0 * 10.0) / (20.0 + hypotenuse)
        val click = "[$cx,$cy,0]"
        assertTrue(
            incircleRadius > rightTolerance,
            "the fixture's incircle ($incircleRadius) no longer clears the snap radius ($rightTolerance)",
        )

        val surfacePoints = session().use { js ->
            val hit = js.classify(triangle, click, rightTolerance)
            assertEquals("triangle", hit.s("kind"), "the click was meant to land in the interior")
            // The incentre's barycentric weights are the side lengths, normalised — another number
            // that comes from the geometry rather than from the region.
            assertEquals(10.0 / (20.0 + hypotenuse), hit.d("u"), 1e-12)
            assertEquals(hypotenuse / (20.0 + hypotenuse), hit.d("v"), 1e-12)
            val points = js.call("surfacePickElementPoints", js.fromJson(triangle), hit)
            (0 until points.arraySize.toInt()).map { i ->
                val p = points.getArrayElement(i.toLong())
                listOf(p.getArrayElement(0).asDouble(), p.getArrayElement(1).asDouble(), p.getArrayElement(2).asDouble())
            }
        }
        assertEquals(3, surfacePoints.size, "an interior hit must hand the measure tool the triangle")

        val json = surfacePoints.joinToString(",", "[", "]") { it.joinToString(",", "[", "]") }
        val annotation = "[[9,1,2],[9.5,0.5,2]]"

        measureSession().use { js ->
            val solid = js.call("measureElement", "polygon", js.fromJson(json))
            val line = js.call("measureElement", "segment", js.fromJson(annotation))
            val answer = js.call("measureClosestApproach", solid, line)
            assertEquals(
                2.0, answer.d("distance"), 1e-12,
                "a segment 2 above a triangle it projects into is 2 away, exactly",
            )
            // `from` is on the solid and `to` on the annotation, in that order, so a caller can label
            // the ends — and the foot really is in the plane of the mesh.
            assertEquals(0.0, answer.point("from").third, 1e-12)
            assertEquals(2.0, answer.point("to").third, 1e-12)

            // The counterfactual, computed here rather than asserted as a hope: had the surface hit
            // degraded to the click point, the answer would have been 3.3826.
            val degraded = js.call("measureClosestApproach", js.call("measureElement", "point", js.fromJson("[$click]")), line)
            val naive = degraded.d("distance")
            assertEquals(sqrt((9.0 - cx) * (9.0 - cx) + (1.0 - cy) * (1.0 - cy) + 4.0), naive, 1e-12)
            assertTrue(
                naive - 2.0 > 1.0,
                "the fixture stopped distinguishing a polygon hit from a point hit (gap ${naive - 2.0})",
            )
        }
    }

    /** A vertex hit measures as a point — the other end of the same bridge. */
    @Test
    fun `a vertex hit on a solid measures as a point`() {
        val triangle = "[[0,0,0],[1,0,0],[1,1,0]]"
        val tolerance = sqrt(2.0) * snapFraction
        val points = session().use { js ->
            val hit = js.classify(triangle, "[0.05,0.02,0]", tolerance)
            assertEquals("vertex", hit.s("kind"))
            assertEquals(0, hit.i("corner"))
            val p = js.call("surfacePickElementPoints", js.fromJson(triangle), hit)
            assertEquals(1L, p.arraySize)
            p.getArrayElement(0).let {
                listOf(it.getArrayElement(0).asDouble(), it.getArrayElement(1).asDouble(), it.getArrayElement(2).asDouble())
            }
        }
        assertEquals(listOf(0.0, 0.0, 0.0), points, "the element is the corner, not the click")

        measureSession().use { js ->
            val corner = js.call("measureElement", "point", js.fromJson("[[0,0,0]]"))
            val node = js.call("measureElement", "point", js.fromJson("[[0,0,3]]"))
            assertEquals(3.0, js.call("measureClosestApproach", corner, node).d("distance"), 1e-12)
        }
    }

    // ------------------------------------------------------------------ what the headline did NOT test

    /**
     * **Where you click decides what you measure to** — three kinds, three different distances.
     *
     * ### Why this test exists: an audit of my own headline
     *
     * The driven capture on 2026-08-16 reported twenty-five clicks reading `150 mm` between a mesh and
     * an FLine, matching the figure's authored dimension. That number is true and it was oversold.
     * Tracing what it actually depends on:
     *
     *  * `surfacePickElementPoints` returns `tri[corner]`, or `tri[e]` and `tri[e+1]`, or all three
     *    corners. It **never reads `u`, `v` or `w`.** A wrong barycentric weight therefore cannot change
     *    a measured distance, at all, on any figure.
     *  * On that particular slab the conduit's leg projects into the hit triangle *and* crosses the
     *    diagonal edge that was picked, so `edge` and `triangle` both answer 150. The reading was
     *    insensitive to the vertex/edge/triangle decision as well.
     *
     * So the 150 exercised the raycast, the `faceIndex` → triangle readout and the hand-off into
     * `measureElement` — real things, and not the classifier's arithmetic. It could not have failed for
     * a wrong weight or a wrong kind. This test is the configuration where it can.
     *
     * ### The configuration
     *
     * The right triangle again, and one annotation point at `(5, 3, 4)`, chosen so that all three
     * answers differ and two of them are integers:
     *
     *  * **vertex** `A = (0,0,0)` → `√(25 + 9 + 16) = √50 = 7.0711`;
     *  * **edge** `AB`, whose nearest point to the annotation is `(5, 0, 0)` → `√(9 + 16) = 5`;
     *  * **triangle**, into which the annotation projects at `(5, 3, 0)` since `5 + 3 < 10` → `4`.
     *
     * A classifier that always answered `triangle` reads `4` three times, and
     * [counterfactualAClassifierThatNeverSnapsCollapsesTheThreeDistancesToOne] shows exactly that.
     */
    @Test
    fun `the kind of element picked changes the measured distance`() {
        val annotation = "[[5,3,4]]"
        val expected = mapOf(
            "[0.6,0.6,0]" to Triple("vertex", sqrt(50.0), "the corner itself"),
            "[5,0.9,0]" to Triple("edge", 5.0, "the foot on AB at (5, 0, 0)"),
            "[3,3,0]" to Triple("triangle", 4.0, "the perpendicular into the face"),
        )

        val resolved = session().use { js ->
            expected.keys.associateWith { click ->
                val hit = js.classify(rightTriangle, click, rightTolerance)
                val points = js.call("surfacePickElementPoints", js.fromJson(rightTriangle), hit)
                hit.s("kind") to (0 until points.arraySize.toInt()).map { i ->
                    val p = points.getArrayElement(i.toLong())
                    listOf(
                        p.getArrayElement(0).asDouble(),
                        p.getArrayElement(1).asDouble(),
                        p.getArrayElement(2).asDouble(),
                    )
                }
            }
        }

        val distances = measureSession().use { js ->
            val q = js.call("measureElement", "point", js.fromJson(annotation))
            resolved.mapValues { (_, kindAndPoints) ->
                val (kind, points) = kindAndPoints
                val json = points.joinToString(",", "[", "]") { it.joinToString(",", "[", "]") }
                val shape = if (points.size == 1) "point" else if (points.size == 2) "segment" else "polygon"
                kind to js.call("measureClosestApproach", js.call("measureElement", shape, js.fromJson(json)), q)
                    .d("distance")
            }
        }

        expected.forEach { (click, want) ->
            val (kind, distance) = distances.getValue(click)
            assertEquals(want.first, kind, "the click at $click should be a ${want.first} hit")
            assertEquals(
                want.second, distance, 1e-12,
                "a ${want.first} hit measures to ${want.third}, which is ${want.second} from (5, 3, 4)",
            )
        }

        // …and the three really are distinct, or the test above could not have failed. Asserted rather
        // than left to the reader, because "three numbers that happen to be equal" is exactly the shape
        // the 150 mm headline turned out to have.
        val values = distances.values.map { it.second }.sorted()
        assertEquals(3, values.toSet().size, "the three kinds no longer give three distinct distances")
        assertTrue(values.last() - values.first() > 3.0, "the spread collapsed to ${values.last() - values.first()}")
    }

    /** Red-first: disable snapping, and all three collapse to the interior answer. */
    @Test
    fun counterfactualAClassifierThatNeverSnapsCollapsesTheThreeDistancesToOne() {
        val clicks = listOf("[0.6,0.6,0]", "[5,0.9,0]", "[3,3,0]")
        val elements = session({
            it.replace("if (cornerDistance <= tol) {", "if (cornerDistance < 0) {")
                .replace("if (edgeDistance <= tol) {", "if (edgeDistance < 0) {")
        }).use { js ->
            clicks.map { click ->
                val hit = js.classify(rightTriangle, click, rightTolerance)
                assertEquals("triangle", hit.s("kind"), "the perturbation should have disabled snapping")
                val points = js.call("surfacePickElementPoints", js.fromJson(rightTriangle), hit)
                (0 until points.arraySize.toInt()).map { i ->
                    val p = points.getArrayElement(i.toLong())
                    listOf(
                        p.getArrayElement(0).asDouble(),
                        p.getArrayElement(1).asDouble(),
                        p.getArrayElement(2).asDouble(),
                    )
                }
            }
        }
        measureSession().use { js ->
            val q = js.call("measureElement", "point", js.fromJson("[[5,3,4]]"))
            val answers = elements.map { points ->
                val json = points.joinToString(",", "[", "]") { it.joinToString(",", "[", "]") }
                js.call("measureClosestApproach", js.call("measureElement", "polygon", js.fromJson(json)), q)
                    .d("distance")
            }
            assertEquals(
                listOf(4.0, 4.0, 4.0), answers.map { kotlin.math.round(it * 1e9) / 1e9 },
                "a classifier that never snaps gives one answer for all three clicks, and it did not",
            )
        }
    }

    /**
     * **The barycentric weights are load-bearing for exactly one thing, and here it is.**
     *
     * They do not reach a measured dimension — see the test above. What they *do* reach is
     * `record.onElement` on a `triangle` hit, rebuilt as `u·a + v·b + w·c`, which travels to Kotlin as
     * [ThreeSurfaceHit.onElement]. A caller measuring from that point — the obvious use, and the one
     * the FLine path has always supported — gets a wrong answer for a wrong weight.
     *
     * ### The input that makes this able to fail, and the control that proves it
     *
     * An equilateral triangle of side 60, clicked at `(40, 12, 0)`, whose weights are
     * `(0.2179, 0.5512, 0.2309)` — **pairwise distinct**, which is the whole point. Swapping `v` and `w`
     * moves `onElement` to `(30.39, 28.64, 0)`, and its distance to an annotation 25 straight above the
     * click goes from **25 exactly** to **31.531**.
     *
     * The control: the same triangle's **centroid**, where `u = v = w = ⅓`. There the identical
     * perturbation moves nothing and the distance stays 25. A test written at the centroid — the obvious
     * place to click, and the place I would have chosen — passes against a broken implementation. That
     * is the failure two agents withdrew a headline for on 2026-08-15, reproduced here on purpose so it
     * is visible rather than argued.
     */
    @Test
    fun aWrongBarycentricWeightGivesAWrongPointOnTheElement() {
        val side = 60.0
        val apex = 30.0 * sqrt(3.0)
        val triangle = "[[0,0,0],[$side,0,0],[30,$apex,0]]"
        val tolerance = side * snapFraction
        val click = "[40,12,0]"
        val annotation = "[[40,12,25]]"
        val centroid = "[${(0 + side + 30) / 3},${apex / 3},0]"

        fun onElementFor(perturbation: (String) -> String, point: String): List<Double> =
            session(perturbation).use { js ->
                val hit = js.classify(triangle, point, tolerance)
                assertEquals("triangle", hit.s("kind"), "the fixture must be an interior hit")
                val on = hit.point("onElement")
                listOf(on.first, on.second, on.third)
            }

        // The weights are distinct — the property that makes a swap detectable at all.
        session().use { js ->
            val hit = js.classify(triangle, click, tolerance)
            val u = hit.d("u"); val v = hit.d("v"); val w = hit.d("w")
            assertEquals(1.0, u + v + w, 1e-12, "barycentric weights must sum to 1")
            val gaps = listOf(abs(u - v), abs(v - w), abs(u - w))
            assertTrue(
                gaps.min() > 0.01,
                "the fixture's weights ($u, $v, $w) are too close together for a swap to be visible",
            )
        }

        val swapWeights: (String) -> String = {
            it.replace("return { u: 1 - v - w, v: v, w: w };", "return { u: 1 - v - w, v: w, w: v };")
        }
        val correct = onElementFor(ViewerJavaScript.UNPERTURBED, click)
        val broken = onElementFor(swapWeights, click)
        assertEquals(listOf(40.0, 12.0, 0.0), correct.map { kotlin.math.round(it * 1e9) / 1e9 })

        measureSession().use { js ->
            val q = js.call("measureElement", "point", js.fromJson(annotation))
            fun distanceFrom(p: List<Double>) = js.call(
                "measureClosestApproach",
                js.call("measureElement", "point", js.fromJson("[[${p.joinToString(",")}]]")),
                q,
            ).d("distance")

            assertEquals(25.0, distanceFrom(correct), 1e-9, "the annotation is 25 straight above the click")
            assertEquals(31.5314, distanceFrom(broken), 1e-4, "a swapped weight must give a wrong distance")
            assertTrue(
                distanceFrom(broken) - 25.0 > 6.0,
                "the perturbation moved the answer by only ${distanceFrom(broken) - 25.0}",
            )
        }

        // The control. At the centroid the swap is invisible, so a test written there proves nothing.
        val centroidCorrect = onElementFor(ViewerJavaScript.UNPERTURBED, centroid)
        val centroidBroken = onElementFor(swapWeights, centroid)
        centroidCorrect.zip(centroidBroken).forEach { (a, b) ->
            assertEquals(a, b, 1e-9, "the centroid should be a fixed point of a weight swap, and is not")
        }
    }

    /**
     * The limitation, as a tested fact rather than a sentence: a weight swap does **not** move a
     * measured dimension.
     *
     * This is the assertion that makes the audit above honest rather than merely stated. If somebody
     * later makes `surfacePickElementPoints` interpolate — reporting, say, the barycentric point rather
     * than the whole triangle for an interior hit — this test goes red, and the right response is to
     * delete it and rewrite the paragraph in [aWrongBarycentricWeightGivesAWrongPointOnTheElement],
     * because the scope of what a wrong weight can break will have changed.
     *
     * Written the awkward way round on purpose. A test asserting "these two are equal" normally means
     * "this is correct"; this one means "this is the *boundary* of what the driven 150 mm could have
     * caught", and the boundary is the thing that was missing from the first version of the headline.
     */
    @Test
    fun `a weight swap leaves every measured dimension untouched — which is why 150 mm could not have failed`() {
        val clicks = listOf("[0.6,0.6,0]", "[5,0.9,0]", "[3,3,0]")
        val swapWeights: (String) -> String = {
            it.replace("return { u: 1 - v - w, v: v, w: w };", "return { u: 1 - v - w, v: w, w: v };")
        }

        fun elementsUnder(perturbation: (String) -> String): List<Pair<String, List<List<Double>>>> =
            session(perturbation).use { js ->
                clicks.map { click ->
                    val hit = js.classify(rightTriangle, click, rightTolerance)
                    val points = js.call("surfacePickElementPoints", js.fromJson(rightTriangle), hit)
                    hit.s("kind") to (0 until points.arraySize.toInt()).map { i ->
                        val p = points.getArrayElement(i.toLong())
                        listOf(
                            p.getArrayElement(0).asDouble(),
                            p.getArrayElement(1).asDouble(),
                            p.getArrayElement(2).asDouble(),
                        )
                    }
                }
            }

        val correct = elementsUnder(ViewerJavaScript.UNPERTURBED)
        val swapped = elementsUnder(swapWeights)
        assertEquals(
            correct, swapped,
            "a barycentric swap changed a measure element, so the audit's claim that the weights do " +
                "not reach a dimension is out of date — rewrite it rather than deleting this test",
        )
        // And the kinds are the three distinct ones, so this is not vacuously comparing three
        // identical answers.
        assertEquals(listOf("vertex", "edge", "triangle"), correct.map { it.first })
    }

    /**
     * The headline number itself, asserted rather than merely printed.
     *
     * A real run of the demo measures **150 mm** between the slab and the conduit, twenty-five times
     * over a swept grid. Here is that measurement from the same geometry through the same two regions,
     * as an assertion — so the figure in bold has a floor under it.
     *
     * The slab is `boxMesh(1200, 800, 60)`, whose first quad is the `+z` face and whose triangle 0 is
     * `(4, 5, 7) = (−600,−400,30) (600,−400,30) (600,400,30)`. The conduit's vertical leg runs
     * `(300, −260, 180) → (300, 240, 180)`. The face's diagonal crosses `x = 300` at `y = 200`, inside
     * the leg's span, so the two segments cross in projection and their closest approach is exactly the
     * `180 − 30 = 150` between their planes.
     *
     * **And the honest part:** the answer is 150 whether the click resolved to the diagonal *edge* or to
     * the whole *triangle* — both asserted below — which is precisely why the driven reading could not
     * distinguish them, and why the three tests above had to be written.
     */
    @Test
    fun `the demo's 150 mm is reproducible from the geometry, by edge and by triangle alike`() {
        val slab = boxMesh(1200.0, 800.0, 60.0).toThreeDrawable()
        val positions = assertNotNull(slab.geometry.attributes["position"]).array
        val indices = assertNotNull(slab.geometry.index).array

        val corners = session().use { js ->
            val tri = js.call(
                "surfacePickTriangle",
                js.fromJson(positions.joinToString(",", "[", "]")),
                js.fromJson(indices.joinToString(",", "[", "]")),
                0,
            )
            assertTrue(!tri.isNull, "boxMesh's face 0 should be readable")
            (0 until 3).map { i ->
                val p = tri.getArrayElement(i.toLong())
                listOf(
                    p.getArrayElement(0).asDouble(),
                    p.getArrayElement(1).asDouble(),
                    p.getArrayElement(2).asDouble(),
                )
            }
        }
        // The `+z` face, at z = 30 — the plane the clearance is measured from.
        corners.forEach { assertEquals(30.0, it[2], 1e-9, "face 0 should be the +z face") }

        val leg = "[[300,-260,180],[300,240,180]]"
        measureSession().use { js ->
            val conduit = js.call("measureElement", "segment", js.fromJson(leg))

            val triangle = js.call(
                "measureElement", "polygon",
                js.fromJson(corners.joinToString(",", "[", "]") { it.joinToString(",", "[", "]") }),
            )
            assertEquals(
                150.0, js.call("measureClosestApproach", triangle, conduit).d("distance"), 1e-9,
                "the whole triangle to the conduit leg",
            )

            // Edge 2 of face 0 is (c, a) — the diagonal the driven click actually landed on.
            val diagonal = js.call(
                "measureElement", "segment",
                js.fromJson("[${corners[2].joinToString(",", "[", "]")},${corners[0].joinToString(",", "[", "]")}]"),
            )
            assertEquals(
                150.0, js.call("measureClosestApproach", diagonal, conduit).d("distance"), 1e-9,
                "the picked diagonal edge to the conduit leg",
            )
        }
    }

    // ------------------------------------------------------------------ the section, on a solid

    /**
     * The section tool cuts the solid, and the length it reports is checkable.
     *
     * Before 2026-08-16 `inspectorDrawCut` looked at `payloadGeometrySlots.fills` and at each
     * interactive object's fills, and at nothing else — so in a scene whose only geometry is a mesh
     * drawable there was no cut at all, and the readout said `cut 0 mm`. Drawables are in that loop
     * now.
     *
     * The figure is the demo's slab, `1200 × 800 × 60`, sectioned by the default `+x` plane at
     * `fraction = 0.62`. Both numbers the viewer prints follow from that and from nothing else:
     *
     *  * the plane sits at `−600 + 0.62 × 1200 = 144`;
     *  * the cut is the slab's `800 × 60` cross-section, whose perimeter is `2 × (800 + 60) = 1720`.
     *
     * A real run of the demo prints exactly `cut 1720 mm @ 144 mm`
     * (`dev_docs/agents/artifacts/mesh_inspector_parity/03_section_cuts_the_solid.png`). This is that
     * observation turned into an assertion, on the same geometry, through the same region.
     */
    @Test
    fun `a section through a solid reports the cross-section it actually cut`() {
        val slab = boxMesh(1200.0, 800.0, 60.0).toThreeDrawable()
        val positions = assertNotNull(slab.geometry.attributes["position"]).array
        val indices = assertNotNull(slab.geometry.index).array

        ViewerJavaScript.Session.open(
            regionName = "inspector-section",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-section"),
        ).use { js ->
            val bounds = """{"min":[-600,-400,-30],"max":[600,400,30]}"""
            val plane = js.call("sectionPlane", js.fromJson(bounds), js.fromJson("[1,0,0]"), 0.62, false)
            assertEquals(144.0, plane.getMember("at").asDouble(), 1e-9, "−600 + 0.62 × 1200")

            val cut = js.call(
                "sectionCutTriangles",
                js.fromJson(positions.joinToString(",", "[", "]")),
                js.fromJson(indices.joinToString(",", "[", "]")),
                plane,
            )
            val length = js.call("sectionCutLength", cut).asDouble()
            assertEquals(
                1720.0, length, 1e-6,
                "the cut of a 1200×800×60 slab at x = 144 is its 800×60 cross-section, perimeter 1720",
            )
            // …and the number is not zero for a trivial reason: the cut has real segments in it.
            assertTrue(cut.arraySize >= 6, "the cut came back with ${cut.arraySize} ordinates")
        }
    }

    // ------------------------------------------------------------------ the seam itself

    /** A perturbation that rewrites nothing is refused, so a counterfactual cannot measure nothing. */
    @Test
    fun `a perturbation that rewrites nothing is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            session { it.replace("a string this region does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `surface-hit-picking` region unchanged"), failure.message!!)
    }

    /**
     * The wiring the region cannot test for itself: a pickable drawable really does join the raycast.
     *
     * A source-shape assertion rather than a behavioural one, and honest about being one. The three
     * lines below are precisely what was missing on 2026-08-15 — the region could have been perfect and
     * the feature would still have been dead, because nothing put a drawable in `interactiveObjects`.
     * That is the same failure mode `ThreeInteractiveEntryDifferentialTest` was written for.
     */
    @Test
    fun `the viewer really does put a pickable drawable into the interactive set`() {
        val text = ViewerJavaScript.VIEWER.readText()
        assertTrue(
            Regex("""if \(slot && slot\.entry\) interactiveObjects\.push\(slot\.entry\);""").containsMatchIn(text),
            "renderPayloadGeometry no longer appends drawable entries to interactiveObjects",
        )
        assertTrue(
            Regex("""slot\.entry = surfaceEntry\(""").containsMatchIn(text),
            "addPayloadDrawables no longer builds an entry for a pickable surface",
        )
        // One entry constructor still — the 2026-08-11 invariant, now with a third source hanging off it.
        assertEquals(
            1, Regex("""const entry = interactiveEntry\(source, meshes, \[\]\);""").findAll(text).count(),
            "surfaceEntry should delegate to interactiveEntry exactly once, not retype it",
        )
    }
}
