package afield.graphics

import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.label
import afield.stdlib.stroked
import org.graalvm.polyglot.Value
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * "How far apart are these two things" — **executing the viewer's own JavaScript**.
 *
 * The three.js viewer could name the element under the pointer (`06352df08`) but could not say
 * anything about two of them at once, which is the first question a 3D preview of a *fabricated* thing
 * gets asked. The `inspector-measure` region answers it, and this test runs that region — the shipped
 * text, lifted out of `threejs_viewer.html` by [ViewerJavaScript] and evaluated on GraalJS — against
 * geometry whose answer is known by construction.
 *
 * The arrangement is the one `ThreeJsHitRecordDifferentialTest` established and for the same reason:
 * both sides of an assertion computed in Kotlin agree with themselves forever. Here the *answer* comes
 * from the viewer and the *expectation* comes from how the figure was built — two panels placed exactly
 * 2.5 apart have to come back as 2.5, to floating point, not to a tolerance chosen to make it pass.
 *
 * There are three families of test below, and they are deliberately different in kind:
 *
 *  * **constructed** — a figure whose closest approach is a number I wrote down when I built it;
 *  * **sampled** — a dense brute-force minimum computed in Kotlin over pseudo-random element pairs,
 *    which catches the case the constructed figures never happen to hit. The region is a closed form,
 *    so it must be *no worse* than the best sample, and it is: the measured gap is reported and
 *    floored;
 *  * **counterfactual** — the region's text rewritten so a branch that matters stops working, with an
 *    assertion that the answer then changes. Without one of these, a test proves only that the code
 *    runs.
 */
class ThreeInspectorMeasureDifferentialTest {

    private fun session(perturbation: (String) -> String = ViewerJavaScript.UNPERTURBED) =
        ViewerJavaScript.Session.open(
            regionName = "inspector-measure",
            exports = ViewerJavaScript.KNOWN_REGIONS.getValue("inspector-measure"),
            perturbation = perturbation,
        )

    private fun points(vararg p: Triple<Double, Double, Double>) =
        p.joinToString(",", "[", "]") { "[${it.first},${it.second},${it.third}]" }

    private fun ViewerJavaScript.Session.element(kind: String, json: String): Value =
        call("measureElement", kind, fromJson(json))

    private fun Value.xyz(): Triple<Double, Double, Double> = Triple(
        getArrayElement(0).asDouble(),
        getArrayElement(1).asDouble(),
        getArrayElement(2).asDouble(),
    )

    /** A quad in the z = `z` plane, corners `(x0,y0)`..`(x1,y1)`, as the viewer would resolve a face. */
    private fun quadJson(x0: Double, y0: Double, x1: Double, y1: Double, z: Double) =
        points(
            Triple(x0, y0, z), Triple(x1, y0, z), Triple(x1, y1, z), Triple(x0, y1, z),
        )

    // ---------------------------------------------------------------- constructed

    /**
     * Two panels, one directly above the other, built exactly 2.5 apart.
     *
     * The upper panel is **smaller and inset**, which is the point: a corner-to-corner answer would be
     * `sqrt(2.5² + 10² + 10²) ≈ 14.4`, and a centroid-to-centroid answer would also be 2.5 but for the
     * wrong reason. Only the true closest approach — a corner of the small panel dropped perpendicularly
     * onto the *interior* of the big one — gives 2.5, so this single assertion pins the interior branch
     * of `measureNearestOnElement` as well as the number.
     */
    @Test
    fun `two panels built two and a half apart measure two and a half`() {
        session().use { js ->
            val lower = js.element("polygon", quadJson(0.0, 0.0, 100.0, 100.0, 0.0))
            val upper = js.element("polygon", quadJson(10.0, 10.0, 90.0, 90.0, 2.5))
            val answer = js.call("measureClosestApproach", lower, upper)
            val distance = answer.getMember("distance").asDouble()
            assertTrue(abs(distance - 2.5) < 1e-12, "closest approach came back as $distance, not 2.5")
            // and `from` is on the lower panel, `to` on the upper — the order is part of the contract,
            // because the caller labels the ends with it.
            assertTrue(abs(answer.getMember("from").getArrayElement(2).asDouble()) < 1e-12)
            assertTrue(abs(answer.getMember("to").getArrayElement(2).asDouble() - 2.5) < 1e-12)
        }
    }

    /**
     * Two skew segments, an X-runner under a Y-runner — the conduit-crossing question, exactly.
     *
     * They cross in plan and clear each other by 2.5 in z, and the feet of the common perpendicular are
     * at the crossing point in plan. Every coordinate of the answer is therefore known, not just the
     * scalar, so this asserts all seven numbers.
     */
    @Test
    fun `two skew runners report the clearance and both feet`() {
        session().use { js ->
            val alongX = js.element("segment", points(Triple(-50.0, 30.0, 0.0), Triple(50.0, 30.0, 0.0)))
            val alongY = js.element("segment", points(Triple(12.0, -40.0, 2.5), Triple(12.0, 60.0, 2.5)))
            val answer = js.call("measureClosestApproach", alongX, alongY)
            assertTrue(abs(answer.getMember("distance").asDouble() - 2.5) < 1e-12)
            val from = answer.getMember("from").xyz()
            val to = answer.getMember("to").xyz()
            assertEquals(Triple(12.0, 30.0, 0.0), from.rounded())
            assertEquals(Triple(12.0, 30.0, 2.5), to.rounded())
        }
    }

    private fun Triple<Double, Double, Double>.rounded() = Triple(
        Math.round(first * 1e9) / 1e9,
        Math.round(second * 1e9) / 1e9,
        Math.round(third * 1e9) / 1e9,
    )

    /**
     * A point over a panel's interior, and the same point pushed off the edge.
     *
     * The pair is the assertion. Inside the footprint the answer is the perpendicular drop, 7.0. Ten
     * units outside it, the answer stops being 7.0 and becomes `hypot(10, 7)`, because the nearest thing
     * is now the panel's edge. A measure tool that always projects onto the plane would report 7.0 for
     * both and be wrong about the second in the direction that matters — it would claim the two things
     * are closer than they are.
     */
    @Test
    fun `a point over a panel drops perpendicular, and off the panel it does not`() {
        session().use { js ->
            val panel = js.element("polygon", quadJson(0.0, 0.0, 100.0, 100.0, 0.0))
            val over = js.element("point", points(Triple(40.0, 40.0, 7.0)))
            val past = js.element("point", points(Triple(110.0, 40.0, 7.0)))
            val inside = js.call("measureClosestApproach", panel, over).getMember("distance").asDouble()
            val outside = js.call("measureClosestApproach", panel, past).getMember("distance").asDouble()
            assertTrue(abs(inside - 7.0) < 1e-12, "over the panel: $inside")
            assertTrue(abs(outside - hypot(10.0, 7.0)) < 1e-12, "past the edge: $outside")
        }
    }

    /**
     * A conduit that actually passes through a panel measures **exactly zero**, at the hole.
     *
     * Zero is the one answer a nearest-feature enumeration cannot reach without the pierce test, because
     * the two elements share no vertex and no edge crossing — the minimum is interior to both. It is
     * also the answer that carries information: it says "these two things clash", which is what the
     * question is for on a fabrication drawing.
     */
    @Test
    fun `a conduit through a panel measures zero at the hole`() {
        session().use { js ->
            val panel = js.element("polygon", quadJson(0.0, 0.0, 100.0, 100.0, 0.0))
            val conduit = js.element("segment", points(Triple(37.0, 61.0, -20.0), Triple(37.0, 61.0, 20.0)))
            val answer = js.call("measureClosestApproach", panel, conduit)
            assertEquals(0.0, answer.getMember("distance").asDouble())
            val at = answer.getMember("to").xyz().rounded()
            assertEquals(Triple(37.0, 61.0, 0.0), at, "the clash should be located, not just detected")
        }
    }

    /**
     * An element with no geometry **refuses**, rather than measuring to the world origin.
     *
     * This is the case a cached viewer produces when it is handed a topology it cannot parse. The
     * tempting implementation returns `[0, 0, 0]` for "nearest point on nothing", and then a dimension
     * appears on screen reading the distance from the figure to the origin — a number that looks like a
     * measurement and is not one. `NaN` and a null pair is the honest answer, and `measureFormat` turns
     * it into an em dash.
     */
    @Test
    fun `an element with no geometry refuses rather than measuring to the origin`() {
        session().use { js ->
            val nothing = js.element("polygon", "[]")
            val point = js.element("point", points(Triple(1.0, 2.0, 3.0)))
            val answer = js.call("measureClosestApproach", nothing, point)
            assertTrue(answer.getMember("distance").asDouble().isNaN(), "expected a refusal, got ${answer.getMember("distance")}")
            assertTrue(answer.getMember("from").isNull && answer.getMember("to").isNull)
            assertEquals("—", js.call("measureFormat", answer.getMember("distance").asDouble(), "mm", 2).asString())
        }
    }

    // ---------------------------------------------------------------- the annotation

    /**
     * The dimension is drawn where it can be read, and the offset is exact.
     *
     * Four things have to hold for the annotation to be a dimension rather than a decoration: the
     * dimension line is the same length as the measurement, it is parallel to it, it is displaced by
     * exactly the requested offset, and the displacement is *across* the view rather than into it —
     * otherwise the annotation foreshortens to nothing at the one camera angle you were using.
     */
    @Test
    fun `the annotation is offset across the view by exactly the offset`() {
        session().use { js ->
            val from = js.fromJson("[0, 0, 0]")
            val to = js.fromJson("[30, 40, 0]")
            val view = js.fromJson("[0, 0, -1]")
            val ann = js.call("measureAnnotation", from, to, view, 6.0)
            assertTrue(abs(ann.getMember("distance").asDouble() - 50.0) < 1e-12)
            val side = ann.getMember("side").xyz()
            val direction = ann.getMember("direction").xyz()
            // unit, and perpendicular to both the measurement and the view
            assertTrue(abs(sqrt(side.dot(side)) - 1.0) < 1e-12, "side is not unit: $side")
            assertTrue(abs(side.dot(direction)) < 1e-12, "side is not perpendicular to the measurement")
            assertTrue(abs(side.third) < 1e-12, "side leans into the view direction: $side")
            val a = ann.getMember("a").xyz()
            val b = ann.getMember("b").xyz()
            // a - from == 6 * side, exactly
            assertTrue(abs(a.first - 6.0 * side.first) < 1e-12 && abs(a.second - 6.0 * side.second) < 1e-12)
            // and the dimension line is the same length as the thing it dimensions
            val length = sqrt((b.first - a.first).let { it * it } + (b.second - a.second).let { it * it })
            assertTrue(abs(length - 50.0) < 1e-12, "the dimension line is $length long but measures 50")
            val mid = ann.getMember("mid").xyz()
            assertTrue(abs(mid.first - (a.first + b.first) / 2) < 1e-12)
        }
    }

    /**
     * A measurement pointing straight at the camera still gets a readable offset.
     *
     * `direction × view` is the zero vector here, so the naive formula yields NaN and the dimension
     * vanishes — at precisely the camera angle a user reaches for when they want to read a depth. The
     * fallback has to be finite, unit, and perpendicular.
     */
    @Test
    fun `a measurement along the view direction still gets a finite offset`() {
        session().use { js ->
            val ann = js.call(
                "measureAnnotation",
                js.fromJson("[0, 0, 0]"), js.fromJson("[0, 0, 10]"), js.fromJson("[0, 0, 1]"), 4.0,
            )
            val side = ann.getMember("side").xyz()
            assertTrue(side.first.isFinite() && side.second.isFinite() && side.third.isFinite(), "side is $side")
            assertTrue(abs(sqrt(side.dot(side)) - 1.0) < 1e-12, "side is not unit: $side")
            assertTrue(abs(side.third) < 1e-12, "the fallback offset went into the view: $side")
        }
    }

    /** A zero-length measurement — two things that touch — does not produce a NaN annotation. */
    @Test
    fun `a zero length measurement annotates without NaN`() {
        session().use { js ->
            val ann = js.call(
                "measureAnnotation",
                js.fromJson("[5, 5, 5]"), js.fromJson("[5, 5, 5]"), js.fromJson("[0, 0, -1]"), 3.0,
            )
            assertEquals(0.0, ann.getMember("distance").asDouble())
            val side = ann.getMember("side").xyz()
            assertTrue(abs(sqrt(side.dot(side)) - 1.0) < 1e-12, "side is not unit: $side")
        }
    }

    /** The text on the dimension, exactly — including the trimming the KDoc promises. */
    @Test
    fun `the dimension text is trimmed the way the region says it is`() {
        session().use { js ->
            assertEquals("2.5 mm", js.call("measureFormat", 2.5, "mm", 2).asString())
            assertEquals("1200 mm", js.call("measureFormat", 1200.0, "mm", 2).asString())
            assertEquals("0.01 mm", js.call("measureFormat", 0.014, "mm", 2).asString())
            assertEquals("0", js.call("measureFormat", 0.0, "", 2).asString())
            assertEquals("—", js.call("measureFormat", Double.NaN, "mm", 2).asString())
            assertEquals("1200.5", js.call("measureFormat", 1200.5, "", 4).asString())
        }
    }

    // ---------------------------------------------------------------- sampled oracle

    /**
     * A dense brute-force minimum over 240 pseudo-random element pairs, plus a membership check.
     *
     * The constructed cases above are all cases I thought of. This one is not. It holds the region to
     * two independent oracles at once:
     *
     *  1. **the lattice.** Both elements are sampled on a 21-point (segment) or 21×21 (quad) lattice in
     *     Kotlin, and the smallest pairwise distance is taken. A closed form must be *no worse* than any
     *     sample — asserted at 1e-9, and that direction is exact.
     *  2. **membership.** Beating the lattice is only impressive if the answer is still *on* the
     *     elements, and "found a minimum just outside the patch" is the characteristic bug of a clamped
     *     solve. So the reported `from` is checked to lie on element A and `to` on element B, by an
     *     independent Kotlin routine — for a quad, by solving `q = o + α·e1 + β·e2` and requiring the
     *     residual to vanish and `α, β ∈ [0, 1]`. The random quads are built as parallelograms
     *     precisely so this check can be exact rather than another approximation.
     *
     * The lattice gap is then only a *sanity* number, and it is reported rather than trusted. Measured
     * 2026-08-11 over the seeded corpus: worst gap **3.1833**, mean **0.0435**, on elements whose edges
     * run to 90 units — so the 4.5-unit lattice spacing accounts for the worst case on its own. The
     * floors sit just outside both, so a regression that starts losing to the lattice, or that stops
     * tracking it on average, fails.
     *
     * The membership residual, which is the assertion that actually bites, measured **1.825e-12** at
     * its worst over the same corpus — i.e. floating-point noise on 200-unit coordinates. Its floor is
     * 1e-10, fifty-odd times that, rather than the 1e-6 it would be comfortable to write.
     */
    @Test
    fun `the closed form is never worse than a dense sampled minimum, and stays on the elements`() {
        val random = Random(20260811)
        fun coordinate() = (random.nextDouble() - 0.5) * 200.0
        fun p() = Triple(coordinate(), coordinate(), coordinate())

        var worstGap = 0.0
        var totalGap = 0.0
        var worstResidual = 0.0
        var pairs = 0

        session().use { js ->
            repeat(240) {
                val (kindA, ptsA) = randomElement(random, ::p)
                val (kindB, ptsB) = randomElement(random, ::p)
                val a = js.element(kindA, ptsA.toJson())
                val b = js.element(kindB, ptsB.toJson())
                val answer = js.call("measureClosestApproach", a, b)
                val closed = answer.getMember("distance").asDouble()
                val from = answer.getMember("from").xyz()
                val to = answer.getMember("to").xyz()

                // self-consistency: the scalar is the distance between the two reported points
                val span = sqrt(
                    (from.first - to.first).let { it * it } +
                        (from.second - to.second).let { it * it } +
                        (from.third - to.third).let { it * it }
                )
                assertTrue(abs(span - closed) < 1e-9, "distance $closed but the reported pair spans $span")

                // membership: each reported point is on the element it claims to be on
                val ra = residualOnElement(kindA, ptsA, from)
                val rb = residualOnElement(kindB, ptsB, to)
                assertTrue(ra < 1e-9, "`from` is $ra off the $kindA it claims to be on")
                assertTrue(rb < 1e-9, "`to` is $rb off the $kindB it claims to be on")
                worstResidual = maxOf(worstResidual, maxOf(ra, rb))

                val sampled = sampledMinimum(kindA, ptsA, kindB, ptsB)
                assertTrue(
                    closed <= sampled + 1e-9,
                    "the closed form ($closed) lost to a lattice sample ($sampled) on $kindA/$kindB",
                )
                val gap = sampled - closed
                worstGap = maxOf(worstGap, gap)
                totalGap += gap
                pairs += 1
            }
        }
        println(
            "measure vs 21x21 lattice over $pairs pairs: worst gap ${"%.4f".format(worstGap)}, " +
                "mean ${"%.4f".format(totalGap / pairs)}, worst membership residual ${"%.3e".format(worstResidual)}"
        )
        assertEquals(240, pairs)
        assertTrue(worstGap < 4.0, "the closed form beat the lattice by $worstGap — suspiciously far outside it")
        assertTrue(totalGap / pairs < 0.12, "the mean gap to the lattice grew to ${totalGap / pairs}")
        assertTrue(worstResidual < 1e-10, "a reported point drifted $worstResidual off its element")
    }

    /**
     * How far `q` is from being *on* the named element — an independent membership test.
     *
     * Deliberately not a call back into the region: a quad here is always a parallelogram
     * `o, o+e1, o+e1+e2, o+e2`, so `q = o + α·e1 + β·e2` has a two-by-two closed solution and
     * membership is the residual plus how far `α, β` stray outside `[0, 1]`. Nothing about it resembles
     * the code it is checking, which is the point.
     */
    private fun residualOnElement(
        kind: String,
        pts: List<Triple<Double, Double, Double>>,
        q: Triple<Double, Double, Double>,
    ): Double {
        fun sub(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>) =
            Triple(a.first - b.first, a.second - b.second, a.third - b.third)
        fun norm(a: Triple<Double, Double, Double>) = sqrt(a.dot(a))
        return when (kind) {
            "point" -> norm(sub(q, pts[0]))
            "segment" -> {
                val d = sub(pts[1], pts[0])
                val t = (sub(q, pts[0]).dot(d) / d.dot(d)).coerceIn(0.0, 1.0)
                norm(sub(q, Triple(pts[0].first + t * d.first, pts[0].second + t * d.second, pts[0].third + t * d.third)))
            }
            else -> {
                val o = pts[0]
                val e1 = sub(pts[1], pts[0])
                val e2 = sub(pts[3], pts[0])
                val d = sub(q, o)
                val a11 = e1.dot(e1)
                val a12 = e1.dot(e2)
                val a22 = e2.dot(e2)
                val det = a11 * a22 - a12 * a12
                val alpha = (d.dot(e1) * a22 - d.dot(e2) * a12) / det
                val beta = (d.dot(e2) * a11 - d.dot(e1) * a12) / det
                val foot = Triple(
                    o.first + alpha * e1.first + beta * e2.first,
                    o.second + alpha * e1.second + beta * e2.second,
                    o.third + alpha * e1.third + beta * e2.third,
                )
                val scale = norm(e1) + norm(e2)
                norm(sub(q, foot)) +
                    scale * maxOf(0.0, -alpha, alpha - 1.0) +
                    scale * maxOf(0.0, -beta, beta - 1.0)
            }
        }
    }

    private fun randomElement(random: Random, p: () -> Triple<Double, Double, Double>):
        Pair<String, List<Triple<Double, Double, Double>>> = when (random.nextInt(3)) {
        0 -> "point" to listOf(p())
        1 -> "segment" to listOf(p(), p())
        else -> {
            // A planar quad, built from an origin and two edge vectors, so the ring really is planar
            // and the interior branch is the one under test rather than a Newell average of noise.
            val o = p()
            val e1 = Triple(coordinateOf(random), coordinateOf(random), coordinateOf(random))
            val e2 = Triple(coordinateOf(random), coordinateOf(random), coordinateOf(random))
            "polygon" to listOf(
                o,
                Triple(o.first + e1.first, o.second + e1.second, o.third + e1.third),
                Triple(o.first + e1.first + e2.first, o.second + e1.second + e2.second, o.third + e1.third + e2.third),
                Triple(o.first + e2.first, o.second + e2.second, o.third + e2.third),
            )
        }
    }

    private fun coordinateOf(random: Random) = (random.nextDouble() - 0.5) * 90.0

    private fun List<Triple<Double, Double, Double>>.toJson() =
        joinToString(",", "[", "]") { "[${it.first},${it.second},${it.third}]" }

    /** Every element sampled on a lattice; the minimum pairwise distance is the oracle. */
    private fun sampledMinimum(
        kindA: String,
        a: List<Triple<Double, Double, Double>>,
        kindB: String,
        b: List<Triple<Double, Double, Double>>,
    ): Double {
        val sa = lattice(kindA, a)
        val sb = lattice(kindB, b)
        var best = Double.MAX_VALUE
        sa.forEach { pa ->
            sb.forEach { pb ->
                val d = sqrt(
                    (pa.first - pb.first).let { it * it } +
                        (pa.second - pb.second).let { it * it } +
                        (pa.third - pb.third).let { it * it }
                )
                if (d < best) best = d
            }
        }
        return best
    }

    private fun lattice(kind: String, pts: List<Triple<Double, Double, Double>>):
        List<Triple<Double, Double, Double>> = when (kind) {
        "point" -> pts
        "segment" -> (0..20).map { i ->
            val t = i / 20.0
            Triple(
                pts[0].first + t * (pts[1].first - pts[0].first),
                pts[0].second + t * (pts[1].second - pts[0].second),
                pts[0].third + t * (pts[1].third - pts[0].third),
            )
        }
        else -> buildList {
            for (i in 0..20) for (j in 0..20) {
                val u = i / 20.0
                val v = j / 20.0
                fun mix(f: (Triple<Double, Double, Double>) -> Double) =
                    (1 - u) * (1 - v) * f(pts[0]) + u * (1 - v) * f(pts[1]) +
                        u * v * f(pts[2]) + (1 - u) * v * f(pts[3])
                add(Triple(mix { it.first }, mix { it.second }, mix { it.third }))
            }
        }
    }

    // ---------------------------------------------------------------- counterfactuals

    /**
     * Delete the interior branch and the two-panel measurement gets it wrong — by a known amount.
     *
     * This is the perturbation `ViewerJavaScript` exists to make cheap. With
     * `measureNearestOnElement`'s plane-foot branch disabled, the small inset panel can only reach the
     * big one's *boundary*, so the answer stops being 2.5 and becomes the corner-to-edge distance
     * `hypot(10, 2.5)`. Asserting the perturbed number as well as the healthy one is what says the
     * healthy assertion is load-bearing rather than accidentally true.
     */
    @Test
    fun `counterfactual — without the interior branch the panels measure further apart`() {
        session { it.replace("if (measurePointInPolygon(frame.flat, at.s, at.t)) {", "if (false) {") }.use { js ->
            val lower = js.element("polygon", quadJson(0.0, 0.0, 100.0, 100.0, 0.0))
            val upper = js.element("polygon", quadJson(10.0, 10.0, 90.0, 90.0, 2.5))
            val distance = js.call("measureClosestApproach", lower, upper).getMember("distance").asDouble()
            assertTrue(
                abs(distance - hypot(10.0, 2.5)) < 1e-9,
                "the perturbation should force a corner-to-edge answer, got $distance",
            )
        }
    }

    /**
     * Delete the pierce test and a clash stops being a clash.
     *
     * The conduit still passes through the panel, but with no interior crossing the nearest features are
     * the conduit's ends and the panel's edges, so the reported distance jumps off zero. A measure tool
     * that reports a positive clearance for two things that intersect is worse than one that reports
     * nothing, which is why this branch gets a counterfactual of its own.
     */
    @Test
    fun `counterfactual — without the pierce test a clash reports a clearance`() {
        session { it.replace("    if (hit) consider(hit.slice(), hit.slice());", "    if (false) consider(hit, hit);") }
            .use { js ->
                val panel = js.element("polygon", quadJson(0.0, 0.0, 100.0, 100.0, 0.0))
                val conduit = js.element("segment", points(Triple(37.0, 61.0, -20.0), Triple(37.0, 61.0, 20.0)))
                val distance = js.call("measureClosestApproach", panel, conduit).getMember("distance").asDouble()
                // Measured: with the pierce test gone the nearest feature is the conduit's own *end*,
                // 20 above the panel's interior — so the clash reads as a comfortable 20 mm clearance.
                // Asserting the specific wrong number rather than "> something" is what makes this a
                // counterfactual instead of a shrug.
                assertTrue(
                    abs(distance - 20.0) < 1e-12,
                    "with no pierce test the clash should read as the conduit's end-to-panel 20, got $distance",
                )
            }
    }

    /** And a perturbation that matched nothing is refused, so neither of the above can rot silently. */
    @Test
    fun `a perturbation that rewrites nothing is refused`() {
        val failure = assertFailsWith<IllegalStateException> {
            session { it.replace("a string this region does not contain", "x") }.close()
        }
        assertTrue(failure.message!!.contains("left the `inspector-measure` region unchanged"), failure.message!!)
    }

    // ---------------------------------------------------------------- against a real FLine

    /**
     * The elements a *real* figure resolves to, not hand-written coordinate triples.
     *
     * Two panels built with `fline { }` the way the human's documents build them, measured between
     * corner nodes and between faces. The point is that the coordinates travelling into the region are
     * the ones an FLine actually carries — `Float`, via [FLine.nodes] — so a `Float`/`Double` narrowing
     * anywhere in the payload path would show up here and nowhere else in this file.
     */
    @Test
    fun `two FLine panels measure the gap they were built with`() {
        val gap = 2.5
        fun panel(z: Double, inset: Double) = fline {
            moveTo(inset, inset, z)
            lineTo(100.0 - inset, inset, z)
            lineTo(100.0 - inset, 100.0 - inset, z)
            lineTo(inset, 100.0 - inset, z)
            close()
            label = "panel@$z"
            filled = true
            stroked = true
        }

        val lower = panel(0.0, 0.0)
        val upper = panel(gap, 10.0)
        fun ring(line: FLine) = (0..3).map { line.nodes[it].to }
            .joinToString(",", "[", "]") { "[${it.x},${it.y},${it.z}]" }

        session().use { js ->
            val a = js.element("polygon", ring(lower))
            val b = js.element("polygon", ring(upper))
            val distance = js.call("measureClosestApproach", a, b).getMember("distance").asDouble()
            assertTrue(abs(distance - gap) < 1e-6, "two FLine panels $gap apart measured $distance")
            // And the annotation over that measurement reads as the number the panels were built with.
            val answer = js.call("measureClosestApproach", a, b)
            val ann = js.call(
                "measureAnnotation", answer.getMember("from"), answer.getMember("to"),
                js.fromJson("[0, 0, -1]"), 8.0,
            )
            assertEquals("2.5 mm", js.call("measureFormat", ann.getMember("distance").asDouble(), "mm", 2).asString())
        }
    }

    private fun Triple<Double, Double, Double>.dot(o: Triple<Double, Double, Double>) =
        first * o.first + second * o.second + third * o.third
}
