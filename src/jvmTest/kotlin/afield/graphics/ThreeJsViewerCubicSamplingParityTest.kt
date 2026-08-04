package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The second subsystem the viewer duplicates, and the first proof that [ViewerJavaScript] is a seam
 * rather than one test: the client's cubic Bézier sampler against `FLine.uniform_linearize_cubic`.
 *
 * `editable = true` re-samples curves in the browser, because a dragged control point has to move its
 * curve before the server hears about it. So `cubicPoint` / `sampleCubic` in `threejs_viewer.html` are
 * a second implementation of the same polynomial — and that is *deliberate*:
 * `dev_docs/fline_fill_orientation.md` records the standing decision not to unify the independent
 * flatteners, because they differ in tolerance model and in what they are allowed to allocate. The job
 * of a test here is therefore to pin that they agree, not to argue that one should go.
 *
 * Adding this cost two sentinel comments in the HTML and one file: no new harness, no new machinery.
 * That was the point of building the extractor generically.
 *
 * ### The one thing they do not agree about
 *
 * They sample the *same* polynomial at the *same* parameters, but at different **default densities**:
 * `EDGE_SAMPLES` is 24, and `Box.threeLines`'s `samplesPerCubic` default is 48. So a curved line's
 * stroke is drawn at half the resolution with `editable = true` as with `editable = false`. That is
 * characterised below rather than changed — it is a visible difference between two modes of the same
 * viewer, and worth knowing about, but a resolution default is not a thing to move without looking at
 * what it costs on a big drawing.
 */
class ThreeJsViewerCubicSamplingParityTest {

    private fun json(vararg points: Vec3): String =
        points.joinToString(",") { "[${it.x.toDouble()},${it.y.toDouble()},${it.z.toDouble()}]" }

    /** A cubic, named. Every one here is a shape a curve editor produces by accident or on purpose. */
    private data class Cubic(val name: String, val a: Vec3, val c1: Vec3, val c2: Vec3, val b: Vec3)

    private fun cubics(): List<Cubic> = listOf(
        Cubic("planarS", Vec3(0f, 0f, 0f), Vec3(30f, 90f, 0f), Vec3(70f, -90f, 0f), Vec3(100f, 0f, 0f)),
        Cubic("outOfPlane", Vec3(0f, 0f, 0f), Vec3(10f, 40f, 60f), Vec3(80f, -20f, -30f), Vec3(100f, 5f, 12f)),
        // Control points on the chord: the cubic degenerates to a straight line, evenly parameterised.
        Cubic("straight", Vec3(0f, 0f, 0f), Vec3(33f, 0f, 0f), Vec3(66f, 0f, 0f), Vec3(99f, 0f, 0f)),
        // A cusp: both handles on the far endpoint. The curve doubles back on itself.
        Cubic("cusp", Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f)),
        // Everything at one point, which a fresh node in an editor genuinely is.
        Cubic("allOnePoint", Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f), Vec3(7f, 7f, 7f)),
        // Large coordinates, where float32 and float64 have visibly different resolution.
        Cubic(
            "farFromTheOrigin",
            Vec3(1e5f, -1e5f, 1e5f), Vec3(1.0002e5f, -1e5f, 1e5f),
            Vec3(1.0004e5f, -0.9998e5f, 1e5f), Vec3(1.0006e5f, -0.9996e5f, 1e5f)
        ),
    )

    /** `uniform_linearize_cubic`'s own output: `samplesPerCubic` points, `t = 1/n … 1`, excluding `t = 0`. */
    private fun kotlinSamples(cubic: Cubic, samples: Int): List<Vec3> {
        val out = ArrayList<Vec3>()
        FLine().uniform_linearize_cubic(
            dest = { p, _ -> out.add(Vec3(p)) },
            a = cubic.a, c1 = cubic.c1, c2 = cubic.c2, b = cubic.b,
            samplesPerCubic = samples,
            storage = Vec3(0f, 0f, 0f),
            fromInstr = 0.0,
            toInstr = 1.0,
        )
        return out
    }

    private fun jsSamples(js: ViewerJavaScript.Session, cubic: Cubic, samples: Int?): List<Vec3> {
        val points = js.fromJson("[${json(cubic.a, cubic.c1, cubic.c2, cubic.b)}]")
        val result = if (samples == null) {
            js.call(
                "sampleCubic",
                points.getArrayElement(0), points.getArrayElement(1),
                points.getArrayElement(2), points.getArrayElement(3),
            )
        } else {
            js.call(
                "sampleCubic",
                points.getArrayElement(0), points.getArrayElement(1),
                points.getArrayElement(2), points.getArrayElement(3), samples,
            )
        }
        return (0 until result.arraySize).map { at ->
            val p = result.getArrayElement(at)
            Vec3(
                p.getArrayElement(0).asDouble().toFloat(),
                p.getArrayElement(1).asDouble().toFloat(),
                p.getArrayElement(2).asDouble().toFloat(),
            )
        }
    }

    private fun session() = ViewerJavaScript.Session.open(
        regionName = "cubic-sampling",
        exports = listOf("EDGE_SAMPLES", "cubicPoint", "sampleCubic"),
    )

    // -------------------------------------------------------------------------------- the parity

    /**
     * Same polynomial, same parameters, same points — to a tolerance set by the *scale of the curve*,
     * because the JavaScript evaluates in float64 and `uniform_linearize_cubic` rounds each coordinate
     * to float32. A fixed epsilon would pass the unit-sized cases and fail `farFromTheOrigin` for a
     * reason that is arithmetic, not disagreement.
     */
    @Test
    fun theClientSamplesTheSameCubicAsUniformLinearizeCubic() {
        session().use { js ->
            var compared = 0
            cubics().forEach { cubic ->
                listOf(1, 2, 3, 24, 48, 100).forEach { samples ->
                    val expected = kotlinSamples(cubic, samples)
                    // `sampleCubic` includes `t = 0`, which `uniform_linearize_cubic` leaves to the
                    // caller because the previous node already emitted it. Same polyline, different
                    // bookkeeping — so drop the first and the two must line up exactly.
                    val actual = jsSamples(js, cubic, samples).drop(1)
                    assertEquals(
                        expected.size, actual.size,
                        "`${cubic.name}` at $samples samples: ${expected.size} versus ${actual.size} points",
                    )
                    val scale = listOf(cubic.a, cubic.c1, cubic.c2, cubic.b)
                        .flatMap { listOf(abs(it.x), abs(it.y), abs(it.z)) }.max().coerceAtLeast(1f)
                    expected.zip(actual).forEachIndexed { at, (e, a) ->
                        listOf(e.x - a.x, e.y - a.y, e.z - a.z).forEach { delta ->
                            assertTrue(
                                abs(delta) <= 1e-5f * scale,
                                "`${cubic.name}` at $samples samples, point $at: $e versus $a",
                            )
                        }
                        compared++
                    }
                }
            }
            assertTrue(compared >= 1000, "only $compared sampled points were compared")
        }
    }

    /** And the endpoints exactly, because a curve that does not start and end where it is told is visible. */
    @Test
    fun theClientHitsBothEndpointsExactly() {
        session().use { js ->
            cubics().forEach { cubic ->
                val samples = jsSamples(js, cubic, 24)
                assertEquals(cubic.a.x, samples.first().x, 1e-4f * (abs(cubic.a.x) + 1f), cubic.name)
                assertEquals(cubic.a.y, samples.first().y, 1e-4f * (abs(cubic.a.y) + 1f), cubic.name)
                assertEquals(cubic.b.x, samples.last().x, 1e-4f * (abs(cubic.b.x) + 1f), cubic.name)
                assertEquals(cubic.b.y, samples.last().y, 1e-4f * (abs(cubic.b.y) + 1f), cubic.name)
            }
        }
    }

    /** `cubicPoint` on its own, at the three parameters whose answer is known without arithmetic. */
    @Test
    fun cubicPointAgreesWithTheClosedFormAtTheEndsAndTheMiddle() {
        session().use { js ->
            val cubic = cubics().first { it.name == "planarS" }
            val points = js.fromJson("[${json(cubic.a, cubic.c1, cubic.c2, cubic.b)}]")
            fun at(t: Double): List<Double> {
                val p = js.call(
                    "cubicPoint",
                    points.getArrayElement(0), points.getArrayElement(1),
                    points.getArrayElement(2), points.getArrayElement(3), t,
                )
                return (0..2).map { p.getArrayElement(it.toLong()).asDouble() }
            }
            assertEquals(listOf(0.0, 0.0, 0.0), at(0.0))
            assertEquals(listOf(100.0, 0.0, 0.0), at(1.0))
            // t = 1/2 is (a + 3 c1 + 3 c2 + b) / 8 — here (0 + 90 + 210 + 100)/8 = 50 in x, 0 in y.
            val middle = at(0.5)
            assertEquals(50.0, middle[0], 1e-9)
            assertEquals(0.0, middle[1], 1e-9)
        }
    }

    // ---------------------------------------------------------------- and the one disagreement

    /**
     * The characterization, so the mismatch is written down somewhere that fails when it changes:
     * **the client's default density is half the server's.**
     *
     * `EDGE_SAMPLES` is 24; `Box.threeLines(samplesPerCubic = 48)`. Both are defaults nobody passes, so
     * a curved line's stroke really is coarser with `editable = true`. Not changed here — a resolution
     * default is not a thing to move at the end of a night without measuring what it costs on a large
     * drawing — but it is now a number a test knows about.
     */
    @Test
    fun theClientsDefaultSampleCountIsHalfTheServersAndThatIsCharacterisedNotFixed() {
        session().use { js ->
            assertEquals(24, js.member("EDGE_SAMPLES").asInt(), "EDGE_SAMPLES moved")

            val cubic = cubics().first { it.name == "planarS" }
            assertEquals(25, jsSamples(js, cubic, null).size, "sampleCubic's default is EDGE_SAMPLES + 1")
            assertEquals(24, jsSamples(js, cubic, null).size - 1, "so 24 segments, client side")
            assertEquals(48, kotlinSamples(cubic, 48).size, "and 48, server side, from threeLines' default")

            // Which is a real difference in the picture, not only in a count: the client's polyline
            // misses the curve by more than the server's, and by a margin worth an order of magnitude.
            fun sagitta(points: List<Vec3>): Float {
                var worst = 0f
                for (i in 0 until points.size - 1) {
                    val mid = Vec3(
                        (points[i].x + points[i + 1].x) / 2f,
                        (points[i].y + points[i + 1].y) / 2f,
                        (points[i].z + points[i + 1].z) / 2f,
                    )
                    val t = (i + 0.5f) / (points.size - 1)
                    val exact = exactCubic(cubic, t.toDouble())
                    worst = maxOf(worst, abs(mid.y - exact[1].toFloat()))
                }
                return worst
            }
            val client = sagitta(jsSamples(js, cubic, null))
            // `listOf(a) + samples` would resolve to `plusVec3ListVec3List` — the stdlib's element-wise
            // vector addition — rather than to list concatenation. Build the list instead.
            val server = sagitta(buildList { add(cubic.a); addAll(kotlinSamples(cubic, 48)) })
            assertTrue(client > server, "the coarser polyline should miss the curve by more: $client vs $server")
            assertNotEquals(client, server)
        }
    }

    private fun exactCubic(cubic: Cubic, t: Double): DoubleArray {
        val mt = 1 - t
        fun one(a: Float, c1: Float, c2: Float, b: Float) =
            mt * mt * mt * a + 3 * mt * mt * t * c1 + 3 * mt * t * t * c2 + t * t * t * b
        return doubleArrayOf(
            one(cubic.a.x, cubic.c1.x, cubic.c2.x, cubic.b.x),
            one(cubic.a.y, cubic.c1.y, cubic.c2.y, cubic.b.y),
            one(cubic.a.z, cubic.c1.z, cubic.c2.z, cubic.b.z),
        )
    }

    // ---------------------------------------------------------------------- and it can go red

    /**
     * The counterfactual, because a parity test that cannot fail is what this branch exists to replace.
     * One character — `3 *` to `2 *` in the Bernstein basis — and the differential must notice.
     */
    @Test
    fun theHarnessCatchesAWrongBernsteinCoefficient() {
        val cubic = cubics().first { it.name == "planarS" }
        val expected = kotlinSamples(cubic, 24)

        ViewerJavaScript.Session.open(
            regionName = "cubic-sampling",
            exports = listOf("EDGE_SAMPLES", "cubicPoint", "sampleCubic"),
            perturbation = { it.replace("(3 * mt2 * t * c1[1])", "(2 * mt2 * t * c1[1])") },
        ).use { js ->
            val actual = jsSamples(js, cubic, 24).drop(1)
            assertTrue(
                expected.zip(actual).any { (e, a) -> abs(e.y - a.y) > 1e-3f },
                "a wrong Bernstein coefficient was not caught",
            )
        }
        session().use { js ->
            val actual = jsSamples(js, cubic, 24).drop(1)
            assertTrue(expected.zip(actual).all { (e, a) -> abs(e.y - a.y) <= 1e-3f }, "and green again")
        }
    }
}
