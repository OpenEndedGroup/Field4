package afield.graphics

import afield.stdlib.fline
import afield.stdlib.vec
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The one edit in the mesh-picking work that changes **existing** behaviour, held to the property that
 * makes it safe.
 *
 * ### What changed, and why it needed changing
 *
 * `raycaster.params.Line.threshold` was the constant `0.06` world units and
 * `raycaster.params.Points.threshold` was `1.5`. A `Mesh` is picked exactly, so those two numbers only
 * ever governed FLine strokes and node sprites — and at model scale they are absurd. On the millimetre
 * panels the inspector was written for, 0.06 mm is sixty microns: an FLine annotation in such a figure
 * could not be clicked, at all, by anyone. Nobody noticed because the only clickable things in those
 * figures were the *fills*. Putting a solid and its annotations in one scene made it unignorable, since
 * the whole point of that scene is to measure from one to the other.
 *
 * They are now `max(0.06, diagonal × 0.005)` and `max(1.5, diagonal × 0.01)`.
 *
 * ### The claim, and why it is a test rather than a comment
 *
 * I shipped this in `1604d5eb4` with no test and named it in the worklog as the branch's one risk. The
 * defence offered was **monotonicity**, in three parts, and all three are asserted below:
 *
 *  1. **Never below the legacy constant**, at any bounds — so nothing can become harder to pick.
 *  2. **Bit-identical to the legacy constant below the crossover**, which is a bounding diagonal of
 *     `0.06 / 0.005 = 12` for lines and `1.5 / 0.01 = 150` for points. Every unit-scale FLine figure in
 *     the docs is far below both, so this cannot re-tune anything that already works.
 *  3. **Non-decreasing in the diagonal.**
 *
 * The crossovers are computed here from the exported constants rather than written as `12` and `150`,
 * so changing a fraction moves the tested crossover with it instead of silently invalidating the test.
 *
 * ### The finding
 *
 * Monotonicity holds — it is `max(c, kd)` with `k > 0`, and the sweep confirms it including exactly at
 * both crossovers. What the sweep also shows, and what the original comment did not say, is that the
 * *point* threshold's crossover is at a diagonal of **150**, so a figure between 12 and 150 units gets
 * a scaled line threshold and an unchanged point threshold. That is not a bug, but it does mean the two
 * do not move together, and the mid-scale figure is the case nobody has looked at.
 * [thePointThresholdCrossoverIsTwelveTimesFurtherOut] records it as an assertion so it stops being a
 * surprise.
 */
class ThreePickThresholdTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "pick-thresholds",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("pick-thresholds"),
            perturbation = perturbation,
        )

    /** Bounds with a given diagonal, laid along one axis so the arithmetic is inspectable. */
    private fun boundsOfDiagonal(diagonal: Double) =
        """{"min":[0,0,0],"max":[$diagonal,0,0]}"""

    /** The constant the viewer used before 2026-08-16, written out rather than read from the region. */
    private val legacyLine = 0.06
    private val legacyPoint = 1.5

    // ------------------------------------------------------------------ the constants are the old ones

    /**
     * The floors *are* the pre-change constants. If they are not, everything below tests a different
     * claim from the one the worklog made.
     */
    @Test
    fun `the floors are exactly the constants the viewer used before`() {
        session().use { js ->
            assertEquals(legacyLine, js.member("PICK_LINE_THRESHOLD_MIN").asDouble(), 0.0)
            assertEquals(legacyPoint, js.member("PICK_POINT_THRESHOLD_MIN").asDouble(), 0.0)
        }
    }

    // ------------------------------------------------------------------ (2) below the crossover, identical

    /**
     * Below the crossover the answer is the old constant **bit for bit**, not merely close to it.
     *
     * `assertEquals(…, 0.0)` on purpose: "within a tolerance of the old behaviour" is not the promise
     * that was made. The promise was that an existing figure picks *exactly* as it did.
     */
    @Test
    fun `every figure below the crossover keeps the exact threshold it had`() {
        session().use { js ->
            val fraction = js.member("PICK_LINE_THRESHOLD_FRACTION").asDouble()
            val crossover = legacyLine / fraction
            assertEquals(12.0, crossover, 1e-12, "the line crossover moved; the docs figures may not be under it")

            // Realistic unit-scale figures, plus the crossover itself and the value just under it.
            val below = listOf(0.0, 1e-9, 0.001, 1.0, 2.0, 3.5, 10.0, 11.999, crossover)
            below.forEach { d ->
                val got = js.call("pickLineThreshold", js.fromJson(boundsOfDiagonal(d))).asDouble()
                assertEquals(
                    legacyLine, got, 0.0,
                    "a figure of diagonal $d used to pick at $legacyLine and now picks at $got",
                )
            }
        }
    }

    /**
     * And the real docs figures are below it — checked against geometry the repo actually builds, not
     * against a number I chose.
     *
     * `buildThreeGeometryPayload` is the production path every `threeLines` figure takes, so its
     * `bounds` block is the exact input `applyPickThresholds` receives. A unit-scale FLine figure has a
     * diagonal of about 1.4; the threshold must come back untouched.
     */
    @Test
    fun `a real unit-scale FLine payload picks at the legacy threshold`() {
        val line = fline {
            moveTo(vec(0.0, 0.0, 0.0))
            lineTo(vec(1.0, 1.0, 0.0))
            lineTo(vec(0.0, 1.0, 0.5))
        }
        val payload = buildThreeGeometryPayload("t", listOf(line), 12, autoFrame = true, editable = false)
        val bounds = payload.bounds!!
        val diagonal = sqrt(
            (0..2).sumOf { i ->
                val d = (bounds.max[i] - bounds.min[i]).toDouble()
                d * d
            }
        )
        assertTrue(diagonal < 12.0, "the fixture is no longer a unit-scale figure: diagonal $diagonal")

        val json = """{"min":[${bounds.min.joinToString(",")}],"max":[${bounds.max.joinToString(",")}]}"""
        session().use { js ->
            assertEquals(
                legacyLine, js.call("pickLineThreshold", js.fromJson(json)).asDouble(), 0.0,
                "a unit-scale FLine figure's pick radius moved",
            )
            assertEquals(legacyPoint, js.call("pickPointThreshold", js.fromJson(json)).asDouble(), 0.0)
        }
    }

    /**
     * A payload with **no bounds** falls back to the legacy constants.
     *
     * The case that matters most and is easiest to forget: `ThreeGeometryPayload.bounds` is nullable and
     * a caller that sends none is exactly the shape that predates all of this. A `diagonal` default of
     * `0` would have given `max(0.06, 0) = 0.06` too, by luck; a default of `10000` would have silently
     * widened every such figure's pick radius to fifty units. The region returns `1`, and the three
     * shapes of unusable bounds are covered rather than the one.
     */
    @Test
    fun `bounds that say nothing usable fall back to the legacy constants`() {
        session().use { js ->
            listOf(
                "null" to "absent",
                """{"min":[0,0,0],"max":[0,0,0]}""" to "zero extent",
                """{"max":[1,1,1]}""" to "no min",
                """{"min":[0,0,0]}""" to "no max",
            ).forEach { (json, why) ->
                val bounds = if (json == "null") null else js.fromJson(json)
                assertEquals(
                    1.0, js.call("pickThresholdDiagonal", bounds).asDouble(), 0.0,
                    "bounds that are $why should scale by 1",
                )
                assertEquals(legacyLine, js.call("pickLineThreshold", bounds).asDouble(), 0.0)
                assertEquals(legacyPoint, js.call("pickPointThreshold", bounds).asDouble(), 0.0)
            }
        }
    }

    // ------------------------------------------------------------------ (1) and (3), over a sweep

    /**
     * Never below the floor, and never decreasing — over a sweep that straddles **both** crossovers.
     *
     * The sweep is geometric so it spends points where the interesting behaviour is, and it deliberately
     * includes the two crossover diagonals themselves, since `max` changing branch is the only place a
     * step could hide.
     */
    @Test
    fun `the thresholds are never below the legacy floor and never decrease`() {
        session().use { js ->
            val lineFraction = js.member("PICK_LINE_THRESHOLD_FRACTION").asDouble()
            val pointFraction = js.member("PICK_POINT_THRESHOLD_FRACTION").asDouble()
            val diagonals = buildList {
                var d = 1e-6
                while (d < 1e6) { add(d); d *= 1.2 }
                add(legacyLine / lineFraction)       // the line crossover, exactly
                add(legacyPoint / pointFraction)     // the point crossover, exactly
            }.sorted()

            var previousLine = -1.0
            var previousPoint = -1.0
            diagonals.forEach { d ->
                val bounds = js.fromJson(boundsOfDiagonal(d))
                val l = js.call("pickLineThreshold", bounds).asDouble()
                val p = js.call("pickPointThreshold", bounds).asDouble()
                assertTrue(l >= legacyLine, "line threshold fell to $l below the floor at diagonal $d")
                assertTrue(p >= legacyPoint, "point threshold fell to $p below the floor at diagonal $d")
                assertTrue(l >= previousLine, "line threshold decreased from $previousLine to $l at $d")
                assertTrue(p >= previousPoint, "point threshold decreased from $previousPoint to $p at $d")
                previousLine = l
                previousPoint = p
            }
            assertTrue(diagonals.size > 120, "the sweep only visited ${diagonals.size} diagonals")
        }
    }

    /**
     * Above the crossover it really does scale — the sweep above would pass for a function that never
     * changed at all.
     *
     * The demo figure is the fixture: a 1200 × 800 × 60 slab with a conduit at z = 180 has bounds
     * `[-600, -400, -30] … [600, 400, 180]`, a diagonal of `√(1200² + 800² + 210²) = 1457.4`, and a line
     * pick radius of `7.287 mm`. That is the number that makes the conduit clickable and the whole
     * mixed-scene measurement possible; without it the radius is 0.06 mm and the demo cannot be used.
     */
    @Test
    fun `above the crossover the threshold scales with the figure`() {
        val expectedDiagonal = sqrt(1200.0 * 1200.0 + 800.0 * 800.0 + 210.0 * 210.0)
        session().use { js ->
            val demo = js.fromJson("""{"min":[-600,-400,-30],"max":[600,400,180]}""")
            assertEquals(expectedDiagonal, js.call("pickThresholdDiagonal", demo).asDouble(), 1e-9)
            val threshold = js.call("pickLineThreshold", demo).asDouble()
            assertEquals(expectedDiagonal * 0.005, threshold, 1e-9)
            assertEquals(7.2872, threshold, 1e-4, "the demo's conduit pick radius, in millimetres")
            assertTrue(
                threshold > legacyLine * 100,
                "the demo would still be unusable at $threshold; the whole point is that 0.06 mm is not " +
                    "a radius anyone can click within",
            )
        }
    }

    /**
     * The finding the sweep turned up: the two crossovers are a factor of twelve apart.
     *
     * A figure between 12 and 150 units gets a scaled *line* threshold and an unchanged *point* one. The
     * original comment implied one crossover; there are two, and this is where a mid-scale figure's
     * node sprites stay at the legacy 1.5 while its strokes widen. Recorded as an assertion so that
     * anyone changing either fraction has to decide about it deliberately.
     */
    @Test
    fun thePointThresholdCrossoverIsTwelveTimesFurtherOut() {
        session().use { js ->
            val lineCrossover = legacyLine / js.member("PICK_LINE_THRESHOLD_FRACTION").asDouble()
            val pointCrossover = legacyPoint / js.member("PICK_POINT_THRESHOLD_FRACTION").asDouble()
            assertEquals(12.0, lineCrossover, 1e-12)
            assertEquals(150.0, pointCrossover, 1e-12)

            // A figure right in between: the line threshold has moved, the point one has not.
            val mid = js.fromJson(boundsOfDiagonal(60.0))
            assertEquals(0.3, js.call("pickLineThreshold", mid).asDouble(), 1e-12)
            assertEquals(legacyPoint, js.call("pickPointThreshold", mid).asDouble(), 0.0)
        }
    }

    // ------------------------------------------------------------------ counterfactual and wiring

    /**
     * Counterfactual: drop the `max` and a unit-scale figure's pick radius collapses.
     *
     * This is the mistake the `max` exists to prevent, and it is a *silent* one — a 1.4-unit figure
     * would go from a 0.06 radius to 0.007, and every FLine in the docs would become four times harder
     * to click with nothing failing anywhere.
     */
    @Test
    fun `counterfactual — without the floor, every unit-scale figure gets harder to pick`() {
        session({
            it.replace(
                "return Math.max(PICK_LINE_THRESHOLD_MIN, pickThresholdDiagonal(bounds) * PICK_LINE_THRESHOLD_FRACTION);",
                "return pickThresholdDiagonal(bounds) * PICK_LINE_THRESHOLD_FRACTION;",
            )
        }).use { js ->
            val got = js.call("pickLineThreshold", js.fromJson(boundsOfDiagonal(1.4))).asDouble()
            assertTrue(got < legacyLine, "the perturbation should have removed the floor; got $got")
            assertEquals(0.007, got, 1e-12)
        }
    }

    /** A perturbation that rewrites nothing is refused, so the counterfactual cannot measure nothing. */
    @Test
    fun `a perturbation that rewrites nothing is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            session { it.replace("a string this region does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `pick-thresholds` region unchanged"), failure.message!!)
    }

    /**
     * And the viewer still routes through it, on every payload.
     *
     * The region could be perfect and the feature dead if `applyGeometry` stopped calling it — which is
     * exactly the failure `ThreeInteractiveEntryDifferentialTest` was written for, one level up.
     */
    @Test
    fun `the viewer applies the thresholds on every payload`() {
        val text = ViewerJavaScript.VIEWER.readText()
        assertTrue(
            Regex("""applyPickThresholds\(payload\.bounds\);""").containsMatchIn(text),
            "applyGeometry no longer applies the pick thresholds",
        )
        assertTrue(
            Regex("""raycaster\.params\.Line = \{ threshold: pickLineThreshold\(bounds\) \};""")
                .containsMatchIn(text),
            "the raycaster is no longer configured from the region",
        )
        // …and the startup literals are gone, or there are two copies of the constant again — one
        // tested and one not, which is exactly the arrangement that produced the 2026-08-11 bug. This
        // assertion is the reason the literals were removed: it failed first, and the viewer changed.
        assertTrue(
            !Regex("""raycaster\.params\.(Line|Points) = \{ threshold: [0-9.]+ \};""").containsMatchIn(text),
            "a hard-coded pick threshold is back alongside the region that is supposed to own it",
        )
        assertTrue(
            Regex("""^applyPickThresholds\(null\);""", RegexOption.MULTILINE).containsMatchIn(text),
            "the startup defaults no longer come from the region",
        )
    }
}
