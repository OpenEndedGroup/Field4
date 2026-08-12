package afield.graphics

import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.label
import afield.stdlib.stroked
import org.graalvm.polyglot.Value
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * "How does it come apart" — **executing the viewer's own JavaScript against the real payload**.
 *
 * The reason this exists is one literal in the human's own 2026-08-09 document:
 *
 * ```kotlin
 * showConduitRelaxationThree(relaxed, channelPrefix = "cross-host-assembled", explosionMm = 0.0, height = 720)
 * ```
 *
 * An exploded view was already there, as a number you retype. Making it a *control* means the
 * displacement has to happen in the viewer on a slider drag, which means the arithmetic exists twice —
 * in `threejs_viewer.html`'s `inspector-explode` region and in Kotlin — and that is what
 * [ViewerJavaScript] is for.
 *
 * What makes this a differential rather than a self-consistency check is where the inputs come from:
 * **the real producer**. [buildThreeGeometryPayload] is called with real FLines and real group keys,
 * and the `position` and `group` attributes it emits are handed to the region verbatim. So the test
 * covers the seam as well as the arithmetic — a per-vertex group array that is one entry short of the
 * position array is the classic silent bug here (every vertex quietly picks up its neighbour's group,
 * and three.js renders a plausible wrong explosion), and it would fail here.
 *
 * The expectations are known by construction: four wall skins at x = 0, 12, 24, 36 have centroids at
 * exactly those coordinates, so every offset the region can produce is a number written down in
 * advance rather than one recomputed from the same code under test.
 */
class ThreeInspectorExplodeDifferentialTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "inspector-explode",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-explode"),
            perturbation = perturbation,
        )

    /** One skin of a wall: a 200 x 300 panel in the plane x = `x`, its centroid exactly at `(x, 100, 150)`. */
    private fun skin(name: String, x: Double) = fline {
        moveTo(x, 0.0, 0.0)
        lineTo(x, 200.0, 0.0)
        lineTo(x, 200.0, 300.0)
        lineTo(x, 0.0, 300.0)
        close()
        label = name
        filled = true
        stroked = false
    }

    private val skins = listOf(
        skin("sheathing", 0.0),
        skin("studs", 12.0),
        skin("services", 24.0),
        skin("lining", 36.0),
    )

    private fun payload(lines: List<FLine> = skins) = buildThreeGeometryPayload(
        uid = "test",
        lines = lines,
        samplesPerCubic = 12,
        autoFrame = true,
        editable = false,
        groupKeys = lines.map { it.label ?: "" },
        inspector = ThreeInspector(explode = ThreeExplode(amount = 0.0)),
    )

    private fun FloatArray.json() = joinToString(",", "[", "]") { it.toDouble().toString() }

    private fun Value.xyz() = Triple(
        getArrayElement(0).asDouble(), getArrayElement(1).asDouble(), getArrayElement(2).asDouble(),
    )

    private fun Value.triple(index: Int): Triple<Double, Double, Double> {
        val v = getArrayElement(index.toLong())
        if (v.isNull) return Triple(Double.NaN, Double.NaN, Double.NaN)
        return Triple(
            v.getArrayElement(0).asDouble(),
            v.getArrayElement(1).asDouble(),
            v.getArrayElement(2).asDouble(),
        )
    }

    // ---------------------------------------------------------------- the seam

    /**
     * The producer emits one group index per vertex, and they are the indices the names imply.
     *
     * This is the Kotlin half of the seam, asserted before anything is asked of the JavaScript half. If
     * it is wrong, every assertion below would still pass — on the wrong geometry.
     */
    @Test
    fun `the payload carries one assembly group per vertex, in first-appearance order`() {
        val p = payload()
        assertEquals(listOf("sheathing", "studs", "services", "lining"), p.inspector!!.groups)
        val fills = p.fills!!
        val positions = fills.attributes.getValue("position").array
        val groups = fills.attributes.getValue("group").array
        assertEquals(positions.size / 3, groups.size, "one group index per vertex")
        assertEquals(listOf(0f, 1f, 2f, 3f), groups.distinct().sorted())
        // and each vertex's group agrees with the x it was built at — the skins are one plane each
        positions.indices.step(3).forEach { i ->
            assertEquals(
                (positions[i] / 12f), groups[i / 3],
                "vertex at x=${positions[i]} carries group ${groups[i / 3]}",
            )
        }
    }

    // ---------------------------------------------------------------- centroids

    /** The region recovers each skin's centroid from the buffers, exactly where it was built. */
    @Test
    fun `group centroids come back at the coordinates the skins were built at`() {
        val fills = payload().fills!!
        session().use { js ->
            val result = js.call(
                "explodeGroupCentroids",
                js.fromJson(fills.attributes.getValue("position").array.json()),
                js.fromJson(fills.attributes.getValue("group").array.json()),
                4,
            )
            val centroids = result.getMember("centroids")
            listOf(0.0, 12.0, 24.0, 36.0).forEachIndexed { g, x ->
                assertTripleEquals(Triple(x, 100.0, 150.0), centroids.triple(g), "group $g")
            }
            (0 until 4).forEach { g ->
                assertEquals(4, result.getMember("counts").getArrayElement(g.toLong()).asInt())
            }
        }
    }

    /**
     * A group with no vertices reports `null`, not the origin — and is then left alone.
     *
     * An assembly usually does have empty groups: a line that is stroked but not filled contributes
     * nothing to the fill buffer, so *every* fill buffer of a mixed figure has them. Answering
     * `[0, 0, 0]` would give such a group a confident radial direction pointing away from the world
     * origin, and the empty group's *neighbours* would then be the only thing that moved correctly.
     */
    @Test
    fun `an empty group has no centroid and no displacement`() {
        val withGhost = skins + fline {
            moveTo(500.0, 500.0, 500.0)
            lineTo(600.0, 500.0, 500.0)
            label = "ghost"
            filled = false
            stroked = false
        }
        val p = payload(withGhost)
        assertEquals(5, p.inspector!!.groups.size)
        val fills = p.fills!!
        session().use { js ->
            val centroids = js.call(
                "explodeGroupCentroids",
                js.fromJson(fills.attributes.getValue("position").array.json()),
                js.fromJson(fills.attributes.getValue("group").array.json()),
                5,
            ).getMember("centroids")
            assertTrue(centroids.getArrayElement(4).isNull, "the ghost group should have no centroid")
            val offsets = js.call(
                "explodeOffsets", centroids, js.fromJson("[18, 100, 150]"),
                js.fromJson("""{"amount": 500, "mode": "radial"}"""),
            )
            assertTripleEquals(Triple(0.0, 0.0, 0.0), offsets.triple(4), "an absent group must not move")
        }
    }

    // ---------------------------------------------------------------- radial

    /**
     * Radial: the two outer skins go one way, the two inner the other, by exactly the amount.
     *
     * The skins share a y and a z, so every direction is exactly ±x and every offset is a number I can
     * write down: `(±5, 0, 0)`. Nothing here is a tolerance on a normalisation.
     */
    @Test
    fun `a radial explode moves every group exactly the amount, away from the centre`() {
        val bounds = payload().bounds!!
        session().use { js ->
            val centre = js.call("explodeBoundsCentre", js.fromJson("""{"min": ${bounds.min.json()}, "max": ${bounds.max.json()}}"""))
            assertTripleEquals(Triple(18.0, 100.0, 150.0), centre.xyz())
            val centroids = js.fromJson("[[0,100,150],[12,100,150],[24,100,150],[36,100,150]]")
            val offsets = js.call("explodeOffsets", centroids, centre, js.fromJson("""{"amount": 5, "mode": "radial"}"""))
            assertTripleEquals(Triple(-5.0, 0.0, 0.0), offsets.triple(0))
            assertTripleEquals(Triple(-5.0, 0.0, 0.0), offsets.triple(1))
            assertTripleEquals(Triple(5.0, 0.0, 0.0), offsets.triple(2))
            assertTripleEquals(Triple(5.0, 0.0, 0.0), offsets.triple(3))
        }
    }

    /** A group sitting exactly on the origin has no direction to go, and is not given one. */
    @Test
    fun `a group on the origin does not acquire a direction`() {
        session().use { js ->
            val offsets = js.call(
                "explodeOffsets",
                js.fromJson("[[10,0,0],[0,0,0]]"),
                js.fromJson("[0,0,0]"),
                js.fromJson("""{"amount": 7, "mode": "radial"}"""),
            )
            assertTripleEquals(Triple(7.0, 0.0, 0.0), offsets.triple(0))
            assertTripleEquals(Triple(0.0, 0.0, 0.0), offsets.triple(1))
        }
    }

    // ---------------------------------------------------------------- axis / layers

    /**
     * Axis mode fans four unevenly-spaced layers into an even, **centred** ladder.
     *
     * The wall's skins are 12 apart in reality. At `amount = 100` along +x they end up 100 apart, and
     * — this is the property with a usability consequence — the mean offset is exactly zero, so the
     * assembly stays where it was and the camera does not have to chase it as the slider moves.
     */
    @Test
    fun `an axis explode fans the layers evenly and keeps the assembly centred`() {
        session().use { js ->
            val centroids = js.fromJson("[[0,100,150],[12,100,150],[24,100,150],[36,100,150]]")
            val offsets = js.call(
                "explodeOffsets", centroids, js.fromJson("[18,100,150]"),
                js.fromJson("""{"amount": 100, "mode": "axis", "axis": [1, 0, 0]}"""),
            )
            assertTripleEquals(Triple(-150.0, 0.0, 0.0), offsets.triple(0))
            assertTripleEquals(Triple(-50.0, 0.0, 0.0), offsets.triple(1))
            assertTripleEquals(Triple(50.0, 0.0, 0.0), offsets.triple(2))
            assertTripleEquals(Triple(150.0, 0.0, 0.0), offsets.triple(3))
            val mean = (0 until 4).sumOf { offsets.triple(it).first } / 4.0
            assertEquals(0.0, mean, "the assembly drifted: mean offset $mean")
        }
    }

    /**
     * Rank, not distance — which is the whole reason axis mode exists.
     *
     * Three layers at 0, 1 and 1000: a distance-proportional explode leaves the first two on top of each
     * other and throws the third off-screen. Rank-spacing puts them at -a, 0, +a, evenly, which is what
     * a build-up drawing wants. The assertion is that the *gaps* are equal despite the input gaps not
     * being.
     */
    @Test
    fun `axis mode spaces by rank, so wildly uneven layers fan evenly`() {
        session().use { js ->
            val offsets = js.call(
                "explodeOffsets",
                js.fromJson("[[0,0,0],[1,0,0],[1000,0,0]]"),
                js.fromJson("[0,0,0]"),
                js.fromJson("""{"amount": 60, "mode": "axis", "axis": [1, 0, 0]}"""),
            )
            assertTripleEquals(Triple(-60.0, 0.0, 0.0), offsets.triple(0))
            assertTripleEquals(Triple(0.0, 0.0, 0.0), offsets.triple(1))
            assertTripleEquals(Triple(60.0, 0.0, 0.0), offsets.triple(2))
        }
    }

    /** An unnormalised axis is normalised, so `axis = [0, 0, 5]` is not five times the explode. */
    @Test
    fun `the axis is normalised`() {
        session().use { js ->
            val offsets = js.call(
                "explodeOffsets",
                js.fromJson("[[0,0,0],[0,0,10]]"),
                js.fromJson("[0,0,0]"),
                js.fromJson("""{"amount": 8, "mode": "axis", "axis": [0, 0, 5]}"""),
            )
            assertTripleEquals(Triple(0.0, 0.0, -4.0), offsets.triple(0))
            assertTripleEquals(Triple(0.0, 0.0, 4.0), offsets.triple(1))
        }
    }

    /** Ties break by group index, so a repeated centroid still fans in a defined order every frame. */
    @Test
    fun `groups sharing a centroid still order deterministically`() {
        session().use { js ->
            repeat(4) {
                val offsets = js.call(
                    "explodeOffsets",
                    js.fromJson("[[5,0,0],[5,0,0],[5,0,0]]"),
                    js.fromJson("[0,0,0]"),
                    js.fromJson("""{"amount": 10, "mode": "axis", "axis": [1, 0, 0]}"""),
                )
                assertTripleEquals(Triple(-10.0, 0.0, 0.0), offsets.triple(0))
                assertTripleEquals(Triple(0.0, 0.0, 0.0), offsets.triple(1))
                assertTripleEquals(Triple(10.0, 0.0, 0.0), offsets.triple(2))
            }
        }
    }

    // ---------------------------------------------------------------- applying it

    /**
     * The displaced buffer is `base + offset[group]`, vertex for vertex, against the real payload.
     *
     * Every one of the payload's fill vertices is checked, not a sample, and against an expectation
     * computed from the FLine's own coordinates rather than from the buffer — so this catches a
     * mis-indexed group as well as a mis-added offset.
     */
    @Test
    fun `applying the offsets displaces every vertex of the real payload`() {
        val fills = payload().fills!!
        val positions = fills.attributes.getValue("position").array
        val groups = fills.attributes.getValue("group").array
        session().use { js ->
            val offsets = js.fromJson("[[-150,0,0],[-50,0,0],[50,0,0],[150,0,0]]")
            val out = js.call(
                "explodeApply",
                js.fromJson(positions.json()),
                js.fromJson(groups.json()),
                offsets,
                null,
            )
            assertEquals(positions.size.toLong(), out.arraySize)
            val expected = doubleArrayOf(-150.0, -50.0, 50.0, 150.0)
            positions.indices.forEach { i ->
                val vertex = i / 3
                val axis = i % 3
                val shift = if (axis == 0) expected[groups[vertex].toInt()] else 0.0
                val got = out.getArrayElement(i.toLong()).asDouble()
                assertEquals(
                    positions[i].toDouble() + shift, got,
                    "vertex $vertex axis $axis: expected ${positions[i] + shift} got $got",
                )
            }
        }
    }

    /**
     * `amount = 0` is the assembled state, **bit for bit**.
     *
     * The document that motivated all this passes `explosionMm = 0.0`, so the common case is the one
     * where nothing should move. `explodeOffsets` short-circuits on a literal zero rather than scaling a
     * unit direction by it, because `direction * 0.0` is only exactly zero if the direction was finite,
     * and the whole point of the null-centroid handling is that sometimes it is not.
     */
    @Test
    fun `an amount of zero returns the assembled geometry unchanged`() {
        val fills = payload().fills!!
        val positions = fills.attributes.getValue("position").array
        val groups = fills.attributes.getValue("group").array
        session().use { js ->
            val centroids = js.call(
                "explodeGroupCentroids", js.fromJson(positions.json()), js.fromJson(groups.json()), 4,
            ).getMember("centroids")
            val offsets = js.call(
                "explodeOffsets", centroids, js.fromJson("[18,100,150]"),
                js.fromJson("""{"amount": 0, "mode": "radial"}"""),
            )
            val out = js.call(
                "explodeApply", js.fromJson(positions.json()), js.fromJson(groups.json()), offsets, null,
            )
            positions.indices.forEach { i ->
                assertEquals(
                    positions[i].toDouble(), out.getArrayElement(i.toLong()).asDouble(),
                    "the assembled state moved at index $i",
                )
            }
        }
    }

    /**
     * A vertex whose group is out of range is **copied**, not dropped.
     *
     * A geometry that loses vertices when a slider moves is worse than one that fails to explode: the
     * fill collapses into the origin and the figure looks broken rather than un-exploded. `-1` gets here
     * whenever a payload without an inspector block is fed to a viewer that has one — which is exactly
     * what a browser holding a cached `threejs_viewer.html` does.
     */
    @Test
    fun `a vertex with no group is copied rather than collapsed`() {
        session().use { js ->
            val out = js.call(
                "explodeApply",
                js.fromJson("[1,2,3, 4,5,6]"),
                js.fromJson("[-1, 0]"),
                js.fromJson("[[100,0,0]]"),
                null,
            )
            assertEquals(1.0, out.getArrayElement(0).asDouble())
            assertEquals(3.0, out.getArrayElement(2).asDouble())
            assertEquals(104.0, out.getArrayElement(3).asDouble())
        }
    }

    /** And with no group attribute at all, everything is copied — a `threeLines` payload is safe. */
    @Test
    fun `a payload with no group attribute passes through untouched`() {
        assertTrue(
            buildThreeGeometryPayload("t", skins, 12, true, false).fills!!.attributes["group"] == null,
            "an ordinary threeLines payload must not grow a group attribute",
        )
        session().use { js ->
            val out = js.call("explodeApply", js.fromJson("[7,8,9]"), null, js.fromJson("[[1,1,1]]"), null)
            assertEquals(7.0, out.getArrayElement(0).asDouble())
            assertEquals(9.0, out.getArrayElement(2).asDouble())
        }
    }

    // ---------------------------------------------------------------- counterfactuals

    /**
     * Remove the centring and the whole assembly walks off to one side — measurably.
     *
     * `middle` is what keeps the mean offset at zero. With it deleted the four skins fan out to
     * `0, 100, 200, 300` instead of `-150, -50, 50, 150`: the same spacing, and a mean of 150 rather
     * than 0, which on screen is the figure sliding out of frame as the slider is dragged. Asserting the
     * exact drifted numbers is what makes the healthy assertion load-bearing.
     */
    @Test
    fun `counterfactual — without the centring term the assembly drifts by a known amount`() {
        session { it.replace("const middle = (ordering.present - 1) * 0.5;", "const middle = 0;") }.use { js ->
            val offsets = js.call(
                "explodeOffsets",
                js.fromJson("[[0,100,150],[12,100,150],[24,100,150],[36,100,150]]"),
                js.fromJson("[18,100,150]"),
                js.fromJson("""{"amount": 100, "mode": "axis", "axis": [1, 0, 0]}"""),
            )
            assertTripleEquals(Triple(0.0, 0.0, 0.0), offsets.triple(0))
            assertTripleEquals(Triple(300.0, 0.0, 0.0), offsets.triple(3))
            val mean = (0 until 4).sumOf { offsets.triple(it).first } / 4.0
            assertEquals(150.0, mean, "the counterfactual should drift by exactly 150, got $mean")
        }
    }

    /**
     * Rank the groups by *distance* instead of by rank, and the uneven wall stops fanning evenly.
     *
     * This is the alternative design that looks reasonable until you try it on a real wall: the two
     * skins 1 unit apart stay 1 unit apart while the far one goes to 1000× the amount. The perturbation
     * makes that concrete.
     */
    @Test
    fun `counterfactual — spacing by distance instead of rank clumps the near layers`() {
        session { it.replace("const step = (ordering.ranks[g] - middle) * amount;", "const step = (c[0] * ordering.unit[0] + c[1] * ordering.unit[1] + c[2] * ordering.unit[2]) * amount;") }
            .use { js ->
                val offsets = js.call(
                    "explodeOffsets",
                    js.fromJson("[[0,0,0],[1,0,0],[1000,0,0]]"),
                    js.fromJson("[0,0,0]"),
                    js.fromJson("""{"amount": 60, "mode": "axis", "axis": [1, 0, 0]}"""),
                )
                assertTripleEquals(Triple(0.0, 0.0, 0.0), offsets.triple(0))
                assertTripleEquals(Triple(60.0, 0.0, 0.0), offsets.triple(1))
                assertTripleEquals(Triple(60000.0, 0.0, 0.0), offsets.triple(2))
            }
    }

    /** A perturbation that matched nothing is refused, so neither counterfactual can rot silently. */
    @Test
    fun `a perturbation that rewrites nothing is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            session { it.replace("a string this region does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `inspector-explode` region unchanged"), failure.message!!)
    }

    /**
     * And the Kotlin producer refuses a group array that does not match the vertex count.
     *
     * The check lives in `withGroups` rather than in a test-only assertion, because the failure it
     * catches is invisible: three.js renders a geometry whose vertices have picked up their neighbours'
     * groups, which looks like a slightly wrong explosion rather than like a bug.
     */
    @Test
    fun `a mismatched group array is refused by the producer`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            // one group key too few: the last line's vertices would silently inherit their neighbour's
            buildThreeGeometryPayload(
                uid = "t", lines = skins, samplesPerCubic = 12, autoFrame = true, editable = false,
                groupKeys = listOf("a", "b", "c"),
                inspector = ThreeInspector(),
            )
        }
        assertTrue(
            failure.message!!.contains("one assembly-group key per line"),
            "expected the producer's own refusal, got: ${failure.message}",
        )
    }

    private fun assertTripleEquals(
        expected: Triple<Double, Double, Double>,
        actual: Triple<Double, Double, Double>,
        message: String = "",
    ) {
        assertTrue(
            abs(expected.first - actual.first) < 1e-9 &&
                abs(expected.second - actual.second) < 1e-9 &&
                abs(expected.third - actual.third) < 1e-9,
            "$message expected $expected but was $actual",
        )
    }
}
