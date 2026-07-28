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
