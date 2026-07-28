package afield.stdlib

import afield.stdlib.Functions.Domain
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds `afield.stdlib` to the claims its own KDoc makes.
 *
 * Every case here started life as a *documented* promise that the code did not keep — found by
 * reading the 1550 KDoc blocks the 2026-07-27 coverage campaign added and checking each against the
 * implementation. Where the prose and the code disagreed the call was made case by case, and the
 * ones below are the ones where the **code** was wrong: an inert `amplitude`, a zero crossing
 * reported on the wrong side of the step, a palindrome that never came home, and a "level
 * preserving" normalization that dropped 60-odd dB. A wrong claim on a published reference page is
 * worse than a missing one, so each is pinned here rather than being quietly reworded.
 *
 * Deliberately arithmetic and deterministic: no GPU, no window, no server, milliseconds to run.
 */
class DocumentedBehaviourTest {

    /**
     * `fbm`'s KDoc promises the result "stays within roughly ±amplitude" — and until 2026-07-28 the
     * parameter was inert, because it cancelled out of `Σvᵢgⁱ / Σgⁱ`.
     *
     * Asserts the two things the documentation actually claims: the output scales with [Functions.fbm]'s
     * `amplitude`, and at the default `amplitude = 1.0` it stays inside ±1.
     */
    @Test
    fun fbmAmplitudeScalesTheOutput() = with(Functions) {
        listOf<Pair<String, (Double) -> Functions.Fn>>(
            "fbm" to { amp -> fbm(seed = 7L, frequency = 6.0, amplitude = amp, octaves = 5) },
            "fbmPerlin" to { amp -> fbmPerlin(seed = 7L, frequency = 6.0, amplitude = amp, octaves = 5) },
        ).forEach { (name, build) ->
            val quiet = peakAbs(build(0.1))
            val loud = peakAbs(build(10.0))
            val unit = peakAbs(build(1.0))

            assertTrue(
                unit > 1e-3,
                "$name: the test is only meaningful if the generator actually moves, peak was $unit"
            )
            assertTrue(
                unit <= 1.0 + 1e-9,
                "$name(amplitude = 1.0) must stay inside ±1 as documented, peak was $unit"
            )
            val ratio = loud / quiet
            assertTrue(
                abs(ratio - 100.0) < 1e-6,
                "$name: amplitude 10.0 must be 100× amplitude 0.1, measured ${"%.6f".format(ratio)}× " +
                    "(quiet peak $quiet, loud peak $loud)"
            )
        }
    }

    /**
     * `sampleZeroCrossings` documents linear interpolation of the crossing between the two straddling
     * samples; it computed `x - step * t` where the interpolation weight `t` is measured from the
     * *left* sample, so a crossing a quarter of the way into a step was reported three quarters of
     * the way along.
     *
     * Two mirrored cases, because the old arithmetic was right at `t = 0.5` and a single asymmetric
     * case could be passed by simply flipping the sign of the same mistake.
     */
    @Test
    fun sampleZeroCrossingsLandsOnTheCrossing() = with(Functions) {
        listOf(0.25, 0.5, 0.75).forEach { root ->
            val f = fn(0.0..1.0, "line") { x -> x - root }
            val crossings = f.sampleZeroCrossings(Domain.of(0.0, 1.0), step = 1.0)
            assertEquals(1, crossings.size, "expected exactly one crossing for x - $root, got $crossings")
            assertTrue(
                abs(crossings[0].x - root) < 1e-12,
                "x - $root crosses zero at $root; sampleZeroCrossings said ${crossings[0].x}"
            )
            assertEquals(0.0, crossings[0].y, "every returned crossing has y == 0.0 by contract")
        }
    }

    /**
     * `palindrome`'s KDoc: "`[a, b, c]` plays `a b c b a`", with the turning point not repeated
     * because "playing `c` twice in a row at the top of the arc rarely reads as intended".
     *
     * The implementation dropped *both* ends of the return leg rather than only the turning point, so
     * an out-and-back never came home: `[a, b, c]` played `a b c b`. Checks the whole itinerary by
     * sampling the middle of each unit slot, and the `includeEndsTwice` variant beside it.
     */
    @Test
    fun palindromePlaysAllTheWayBack() = with(Functions) {
        val items = listOf(c(1.0), c(2.0), c(3.0))

        assertEquals(
            listOf(1.0, 2.0, 3.0, 2.0, 1.0),
            slotValues(items.palindrome(), slots = 5),
            "[a, b, c].palindrome() must play a b c b a"
        )
        assertEquals(
            listOf(1.0, 2.0, 3.0, 3.0, 2.0, 1.0),
            slotValues(items.palindrome(includeEndsTwice = true), slots = 6),
            "includeEndsTwice = true must play a b c c b a"
        )
        // "Lists of one or two items always double their ends, there being no interior to drop."
        assertEquals(
            listOf(1.0, 2.0, 2.0, 1.0),
            slotValues(listOf(c(1.0), c(2.0)).palindrome(), slots = 4),
            "a two-item palindrome must play a b b a"
        )
    }

    /**
     * `convolve(normalize = true)` documents that dividing by the kernel's summed weight "keeps the
     * level when the kernel is a blur rather than an impulse response".
     *
     * It divided the *scaled* sum (already multiplied by the sample step) by the *unscaled* weight, so
     * the result came out a factor of `step` too small — at the default `samples = 64` across a
     * unit-wide kernel, a 63× drop. The property that matters is the one asserted here: convolving a
     * constant with a normalized kernel returns that constant, whatever the kernel width or sample
     * count. [Functions.blur], which was already right, is the model.
     */
    @Test
    fun normalizedConvolutionKeepsTheLevel() = with(Functions) {
        val flat = c(2.0, Domain.of(-10.0, 10.0))
        listOf(4, 64, 512).forEach { samples ->
            listOf(0.05, 1.0, 4.0).forEach { width ->
                val span = Domain.of(-width / 2.0, width / 2.0)
                val kernel = gaussianWindow(span, sigma = 0.3)
                val level = flat.convolve(kernel, samples = samples, kernelDomain = span, normalize = true).eval(0.0)
                assertTrue(
                    abs(level - 2.0) < 1e-9,
                    "a normalized convolution of the constant 2.0 must stay 2.0 " +
                        "(samples = $samples, kernel width = $width): got $level"
                )
            }
        }

        // Un-normalized is an integral and is *meant* to scale with the kernel's area — the guard
        // against "fixing" normalization by rescaling both branches.
        val span = Domain.of(-0.5, 0.5)
        val area = flat.convolve(gaussianWindow(span, sigma = 0.3), samples = 512, kernelDomain = span).eval(0.0)
        assertTrue(area < 2.0, "an un-normalized convolution with a sub-unit-area kernel must not keep the level: $area")
    }

    /**
     * [mat3] accepts a translation and ignores it — a 3×3 matrix has nowhere to put one, and the
     * parameter exists so that a `mat4(...)` call can be edited into a `mat3(...)` call without
     * reshuffling arguments.
     *
     * That is what its KDoc says, so it is pinned: the day someone makes `t` do something, this test
     * fails and the documentation gets updated with it.
     */
    @Test
    fun mat3IgnoresItsTranslation() {
        val plain = mat3(s = Vector3f(2f, 3f, 4f))
        val translated = mat3(t = Vector3f(10f, 20f, 30f), s = Vector3f(2f, 3f, 4f))
        assertEquals(
            elements(plain),
            elements(translated),
            "mat3's t argument is documented as ignored"
        )
        assertEquals(
            listOf(2f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 4f),
            elements(translated),
            "mat3(s = …) is a plain scale"
        )
    }

    /**
     * The step-based `toDoubleArray` documents that it samples "every `step` units" and that `includeEnd`
     * "adds the sample landing on `max` **when the width divides evenly**".
     *
     * It handed its computed count to the *count*-based overload, which spaces its samples `len / (count - 1)`
     * apart so that the last one lands on `max` — silently re-scaling the step whenever it did not divide the
     * width: a step of 0.3 across `0.0..1.0` sampled at 0, 1/3, 2/3, 1. Sampling an identity function makes
     * the positions observable, which is the only way to see this from outside.
     */
    @Test
    fun stepSamplingReallyUsesTheStep() = with(Functions) {
        val identity = fn(Domain.ALL, "x") { x -> x }
        val unit = Domain.of(0.0, 1.0)

        assertPositions(
            listOf(0.0, 0.3, 0.6, 0.9),
            identity.toDoubleArray(0.3, unit, includeEnd = true),
            "a step of 0.3 across 0..1 must land on multiples of 0.3 and stop short of max"
        )
        assertPositions(
            listOf(0.0, 0.3, 0.6),
            identity.toDoubleArray(0.3, unit, includeEnd = false),
            "includeEnd = false drops the sample that would land on max, it does not re-space the rest"
        )
        assertPositions(
            listOf(0.0, 0.25, 0.5, 0.75, 1.0),
            identity.toDoubleArray(0.25, unit, includeEnd = true),
            "an evenly-dividing step must still land exactly on max"
        )
        assertEquals(
            11,
            identity.toDoubleArray(0.1, unit, includeEnd = true).size,
            "1.0 / 0.1 is 10.000000000000002 in binary floating point; the endpoint must not be lost to that"
        )
        assertPositions(
            listOf(0.0, 0.25, 0.5, 0.75, 1.0),
            identity.toDoubleArray(-0.25, unit, includeEnd = true),
            "the step's sign is documented as ignored"
        )
        assertEquals(0, identity.toDoubleArray(0.0, unit).size, "a step of zero is documented as empty")
    }

    /**
     * A peak-hold envelope is documented as "always non-negative and never below the current sample's
     * magnitude" — and `PeakDetector` decayed unconditionally whenever the sample was not a new high, so a
     * *constant* input sawtoothed between `A` and `A · decay` forever.
     */
    @Test
    fun peakDetectorNeverFallsBelowTheCurrentSample() {
        val detector = PeakDetector(decay = 0.99f)
        val outputs = (0 until 50).map { detector(0.75f) }
        assertTrue(
            outputs.all { abs(it - 0.75f) < 1e-6f },
            "a constant 0.75 must hold at 0.75, got ${outputs.distinct()}"
        )

        val impulse = PeakDetector(decay = 0.5f)
        assertEquals(1.0f, impulse(1.0f), "a new high latches immediately")
        assertEquals(0.5f, impulse(0.0f), "silence after a peak decays by the retention factor")
        assertEquals(0.4f, impulse(0.4f), "…but never below the sample in hand")
    }

    /**
     * `DynamicRangeCompressor`'s `kneeWidthDb` is documented as "0 gives a hard knee". With no knee the
     * quadratic knee branch evaluated `0 / (2 * 0)`, so a sample sitting exactly on the threshold — which
     * silence does, because the envelope floors at exactly -100 dB — came out **NaN**.
     */
    @Test
    fun hardKneeCompressorDoesNotProduceNaN() {
        val hard = DynamicRangeCompressor(thresholdDb = -100f, ratio = 4f, kneeWidthDb = 0f)
        val silent = hard(0f)
        assertTrue(silent.isFinite(), "a hard knee at the envelope floor must not be NaN, got $silent")

        // A hard knee is the un-smoothed straight line: no reduction below the threshold, the full ratio
        // above it.
        val quiet = DynamicRangeCompressor(thresholdDb = -6f, ratio = 4f, kneeWidthDb = 0f)
        repeat(20000) { quiet(0.1f) }
        val out = quiet(0.1f)
        assertTrue(out.isFinite() && out > 0f, "a sub-threshold signal must pass through, got $out")
        assertTrue(abs(out - 0.1f) < 1e-3f, "0.1 is below -6 dB, so it must be left alone: $out")
    }

    /**
     * `movingAverage` is documented as "the mean of the function over a [window]-wide span", and the mean of a
     * constant is that constant — at any sample count.
     *
     * It summed rectangles of width `window / (samples - 1)` over probes that include **both** endpoints and
     * then divided by the window, which answers `samples / (samples - 1)` times the mean: exactly 2× at
     * `samples = 2` and 1.6% high at the default 64. The visible consequence was that `f - f.movingAverage(w)`,
     * the high-pass, did not return zero on a flat signal. Now a trapezoid, which is exact for a constant.
     */
    @Test
    fun movingAverageOfAConstantIsThatConstant() = with(Functions) {
        val flat = c(2.5, Domain.of(-10.0, 10.0))
        listOf(1, 2, 3, 8, 64, 512).forEach { samples ->
            val level = flat.movingAverage(1.0, samples).eval(0.0)
            assertTrue(
                abs(level - 2.5) < 1e-12,
                "the mean of the constant 2.5 must be 2.5 at samples = $samples, got $level"
            )
        }
        assertTrue(
            abs(flat.eval(0.0) - flat.movingAverage(1.0, 64).eval(0.0)) < 1e-12,
            "a high pass must leave nothing of a flat signal"
        )
        // A centred mean of a straight line is the line's own value — the other case a biased estimator breaks.
        val ramp = fn(Domain.of(-5.0, 5.0), "ramp") { x -> x }
        assertTrue(
            abs(ramp.movingAverage(2.0, 64).eval(1.0) - 1.0) < 1e-9,
            "the centred mean of a ramp at x = 1 is 1: got ${ramp.movingAverage(2.0, 64).eval(1.0)}"
        )
    }

    /**
     * `tile` documents "[count] copies of f back to back" and zero outside that span. It declared the *source's*
     * domain rather than the span, so `f.tile(3)` claimed to be one copy long and plotting or sampling it showed
     * a third of the material it had just built.
     */
    @Test
    fun tileDeclaresTheSpanItFills() = with(Functions) {
        val source = sin(4.0, domain = Domain.of(0.0, 0.25))
        val tiled = source.tile(3)
        assertEquals(0.0, tiled.domain.min, "the span starts at the origin")
        assertTrue(abs(tiled.domain.max - 0.75) < 1e-12, "three 0.25-wide copies span 0..0.75, got ${tiled.domain}")
        assertTrue(abs(tiled.eval(0.6) - tiled.eval(0.1)) < 1e-12, "the third copy repeats the first")
        assertEquals(0.0, tiled.eval(-0.01), "zero before the span")
        assertEquals(0.0, tiled.eval(0.76), "zero after it")
        assertEquals(0.0, source.tile(0).eval(0.1), "a count of zero or less is the constant zero")
    }

    /**
     * `obbBounds` documents a box that is "`center + extents[i] * axes[i]` on each axis, with `extents` as
     * half-widths" — which is only a *bounding* box if the centre is the middle of the projections. It used
     * the cloud's **centroid**, so any skewed cloud got a box that did not contain its own input: for
     * x = 0, 0, 0, 10 the centroid is 2.5 and the half-width 5, leaving the point at 10 outside.
     */
    @Test
    fun orientedBoundsContainTheirPoints() {
        val skewed = listOf(
            Vec3(0f, 0f, 0f), Vec3(0f, 0.1f, 0f), Vec3(0f, -0.1f, 0f), Vec3(10f, 0f, 0f)
        )
        val box = skewed.obbBounds()
        skewed.forEach { p ->
            val rel = Vec3(p).sub(Vec3(box.center))
            (0..2).forEach { axis ->
                val along = box.axes[axis].dot(rel)
                assertTrue(
                    abs(along) <= box.extents[axis] + 1e-3f,
                    "point $p is ${abs(along)} along axis $axis, outside the half-width ${box.extents[axis]}"
                )
            }
        }
        // …and it must still be tight: the long axis is half of the 10-unit spread, not more.
        assertTrue(
            abs(box.extents[0] - 5f) < 1e-3f,
            "the dominant half-width of a 10-unit spread is 5, got ${box.extents[0]}"
        )
    }

    /**
     * `String.times` documents "zero or fewer gives the empty string". A negative count reached
     * `StringBuilder(this.length * ln)` first, which throws `NegativeArraySizeException` for any non-empty
     * receiver — so the documented case was the one case that crashed.
     */
    @Test
    fun stringTimesNonPositiveIsEmpty() {
        assertEquals("", "ab" * 0)
        assertEquals("", "ab" * -1)
        assertEquals("", "ab" * -1000)
        assertEquals("ababab", "ab" * 3)
    }

    /**
     * `PairContext.axis` is documented as falling back to `(1, 0)` "for a coincident pair rather than being
     * NaN". The guard read `if (max(delta.length(), 0.0001f) < 1e-4f)` — the floor compared against itself,
     * so the fallback was unreachable and a coincident pair normalized a zero vector into `(NaN, NaN)`,
     * which reached the impulse and then the positions permanently. Two nodes at the origin is not a corner
     * case: `NetBuilder.node`'s `at` defaults to `Vec2()`, so every `net { }` written without positions
     * starts stacked.
     */
    @Test
    fun coincidentPairDoesNotPoisonTheLayoutWithNaN() {
        // Lambda.duplicatedConstantDemo's wiring, with every position left at the default origin. The
        // duplication rule is one that throws its new nodes apart with ctx.branchImpulse, which is where a
        // NaN axis becomes a NaN velocity.
        val stacked = net {
            val rootL = node(Lambda.ROOT, id = "rootL")
            val rootR = node(Lambda.ROOT, id = "rootR")
            val dup = node(Lambda.DUP, id = "dup")
            val constant = node(Lambda.CONST, id = "k")
            dup.principal to constant.principal
            dup.aux(0) to rootL.principal
            dup.aux(1) to rootR.principal
        }
        val reducer = Reducer(Lambda.rules())
        assertTrue(reducer.step(stacked), "the stacked net must still have a reducible pair")
        stacked.nodes.forEach { node ->
            val v = stacked.velocityOf(node)
            assertTrue(
                v.x.isFinite() && v.y.isFinite(),
                "node ${node.id} came out of the rewrite with velocity ($v)"
            )
        }
        // The impulse only reaches the coordinates through the layout, which is where a NaN becomes permanent.
        stacked.relax(8)
        stacked.nodes.forEach { node ->
            val p = stacked.positionOf(node)
            assertTrue(
                p.x.isFinite() && p.y.isFinite(),
                "node ${node.id} was left at ($p) after relaxing a net built with no positions"
            )
        }
    }

    /** Asserts sampled positions match [expected] to within floating-point slack, reporting both lists. */
    private fun assertPositions(expected: List<Double>, actual: DoubleArray, message: String) {
        val ok = expected.size == actual.size && expected.indices.all { abs(expected[it] - actual[it]) < 1e-9 }
        assertTrue(ok, "$message — expected $expected, got ${actual.toList()}")
    }

    /**
     * The largest absolute value [fn] takes over [samples] evenly spaced points of its domain.
     *
     * Asserts the domain is finite rather than falling back to a window: an `fbm` whose domain had
     * silently become unbounded would make every amplitude assertion here meaningless.
     */
    private fun peakAbs(fn: Functions.Fn, samples: Int = 512): Double {
        assertTrue(fn.domain.isFinite, "peakAbs needs a finite domain, got ${fn.domain}")
        var peak = 0.0
        for (i in 0 until samples) {
            val x = fn.domain.min + fn.domain.length * i.toDouble() / (samples - 1).toDouble()
            peak = maxOf(peak, abs(fn.eval(x)))
        }
        return peak
    }

    /**
     * The value in the middle of each unit-wide slot of a sequenced [fn] — which is how a
     * `sequence`-family combinator's itinerary is read back, since each item of a `0.0..1.0` source
     * occupies exactly one unit of the result at zero overlap.
     */
    private fun slotValues(fn: Functions.Fn, slots: Int): List<Double> {
        assertEquals(
            slots.toDouble(),
            fn.domain.length,
            "expected $slots unit slots, domain was ${fn.domain}"
        )
        return (0 until slots).map { fn.eval(it + 0.5) }
    }

    private fun elements(m: Matrix3f): List<Float> =
        listOf(m.m00, m.m01, m.m02, m.m10, m.m11, m.m12, m.m20, m.m21, m.m22)
}
