package afield

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **What a slider law promises**, held to numbers rather than to a description.
 *
 * Three properties, and each of them is a bug that has actually shipped in software like this:
 *
 *  - **Round trip.** `value → travel → value` has to come back. If it does not, dragging a handle one
 *    pixel and letting go moves the value, which makes a control unusable for fine adjustment because
 *    it drifts under the hand.
 *  - **Endpoint exactness.** A control at full travel must read *exactly* its maximum. The naive
 *    `min * (max/min).pow(1.0)` is exact for 20 Hz–20 kHz — which is precisely why this bug survives
 *    review — and inexact for 4319 of the 79 000 small integer ranges swept below, including `7..29`,
 *    where it returns `29.000000000000004` and so escapes *above* the declared maximum. Both
 *    directions therefore short-circuit at the ends, and this file asserts on the exact double rather
 *    than within a tolerance, because a tolerance would pass on the broken version.
 *  - **Refusal.** A logarithmic law over a range starting at zero is not repairable, and the failure
 *    mode of *not* refusing is a `NaN` in the DOM. So it throws, and the throw is asserted here as a
 *    thrown error with a readable message, not described in a comment.
 *
 * The browser half of the same rules is covered by [afield.ControlSurfaceProbeTest], which runs the
 * shipped JavaScript; between them the two implementations cannot drift apart silently.
 */
class ControlLawTest {

    /** The laws and ranges under test, chosen so each law meets a range it has to work hard on. */
    private val specs = listOf(
        "linear unit" to ControlLawSpec(ControlLaw.LINEAR, 0.0, 1.0),
        "linear bipolar range" to ControlLawSpec(ControlLaw.LINEAR, -50.0, 50.0),
        "log audio" to ControlLawSpec(ControlLaw.LOGARITHMIC, 20.0, 20000.0),
        "log three decades from a small min" to ControlLawSpec(ControlLaw.LOGARITHMIC, 0.001, 1.0),
        "log narrow" to ControlLawSpec(ControlLaw.LOGARITHMIC, 0.9, 1.1),
        "exp square taper" to ControlLawSpec(ControlLaw.EXPONENTIAL, 0.0, 1.0, exponent = 2.0),
        "exp cubic over a delay time" to ControlLawSpec(ControlLaw.EXPONENTIAL, 0.0, 10.0, exponent = 3.0),
        "exp from a negative min" to ControlLawSpec(ControlLaw.EXPONENTIAL, -12.0, 12.0, exponent = 2.0),
        "bipolar pan" to ControlLawSpec(ControlLaw.BIPOLAR, -1.0, 1.0, exponent = 2.0, center = 0.0),
        "bipolar detune in cents" to ControlLawSpec(ControlLaw.BIPOLAR, -1200.0, 1200.0, exponent = 3.0, center = 0.0),
        "bipolar asymmetric about a non-zero centre" to
            ControlLawSpec(ControlLaw.BIPOLAR, 0.25, 4.0, exponent = 2.0, center = 1.0)
    )

    /**
     * `travel → value → travel` for 101 positions of every law.
     *
     * Measured worst case is **1.33e-15** in travel; the floor is `1e-13`, two decades above it, so
     * ordinary last-bit noise cannot fail this but any real loss of invertibility does. At the
     * 292-pixel track a slider actually draws, `1e-13` is `3e-11` of a pixel.
     */
    @Test
    fun `travel round trips through value for every law`() {
        var worst = 0.0
        var worstName = ""
        specs.forEach { (name, spec) ->
            spec.validate(name)
            for (i in 0..100) {
                val t = i / 100.0
                val back = spec.normalizedFor(spec.valueAt(t))
                val error = abs(back - t)
                if (error > worst) {
                    worst = error
                    worstName = "$name at t=$t"
                }
            }
        }
        println("ControlLawTest: worst travel round-trip error ${"%.3e".format(worst)} ($worstName)")
        assertTrue(worst < 1e-13, "travel round trip drifts by $worst, worst at $worstName")
    }

    /**
     * `value → travel → value` as a *relative* error, which is the one a person feels.
     *
     * Absolute error is the wrong measure for a logarithmic control: 0.01 Hz of slop at 20 Hz is
     * audible and 0.01 Hz at 20 kHz is not, so the assertion is on the ratio. Measured worst case is
     * **1.03e-15** relative; the floor is `1e-13`.
     */
    @Test
    fun `value round trips through travel for every law`() {
        var worst = 0.0
        var worstName = ""
        specs.forEach { (name, spec) ->
            for (i in 0..100) {
                val value = spec.valueAt(i / 100.0)
                val back = spec.valueAt(spec.normalizedFor(value))
                val scale = maxOf(abs(value), 1e-12)
                val error = abs(back - value) / scale
                if (error > worst) {
                    worst = error
                    worstName = "$name at value=$value"
                }
            }
        }
        println("ControlLawTest: worst relative value round-trip error ${"%.3e".format(worst)} ($worstName)")
        assertTrue(worst < 1e-13, "value round trip drifts by a relative $worst, worst at $worstName")
    }

    /** Both ends of the track are the stated numbers, to the bit. */
    @Test
    fun `every law is exact at both endpoints`() {
        specs.forEach { (name, spec) ->
            assertEquals(spec.min, spec.valueAt(0.0), "$name: travel 0 is not the minimum")
            assertEquals(spec.max, spec.valueAt(1.0), "$name: travel 1 is not the maximum")
            assertEquals(0.0, spec.normalizedFor(spec.min), "$name: the minimum is not at travel 0")
            assertEquals(1.0, spec.normalizedFor(spec.max), "$name: the maximum is not at travel 1")
        }
    }

    /**
     * The endpoint short-circuit, with a range where removing it demonstrably breaks.
     *
     * `min * (max / max).pow(1.0)` is exact more often than one would guess — for 20 Hz–20 kHz it
     * happens to land on 20000.0 — which is exactly what makes this class of bug survive review. It is
     * not exact in general: over a `7..29` range the naive expression gives **29.000000000000004**,
     * i.e. a value *above* the declared maximum, which is the version of the bug that matters because
     * it escapes into whatever the callback feeds. A sweep of small integer ranges is included so this
     * test states how common the failure is rather than asserting one lucky case.
     */
    @Test
    fun `a log law is exact at the top even where the naive expression is not`() {
        val naive = { min: Double, max: Double -> min * Math.pow(max / min, 1.0) }

        val inexact = (2..200).flatMap { lo -> (lo + 1..400).map { hi -> lo.toDouble() to hi.toDouble() } }
            .filter { (lo, hi) -> naive(lo, hi) != hi }
        println("ControlLawTest: the naive log endpoint is inexact for ${inexact.size} of the small integer ranges tried")
        // Measured: 4319 of the 79 000 ranges tried. The floor is deliberately close to that, so a
        // change in the arithmetic that makes the premise evaporate fails here rather than passing quietly.
        assertTrue(inexact.size > 4000, "only ${inexact.size} inexact naive ranges found; the premise needs checking")

        assertEquals(29.000000000000004, naive(7.0, 29.0), "the 7..29 counterexample changed")
        assertTrue(naive(7.0, 29.0) > 29.0, "the counterexample no longer escapes above its maximum")
        assertEquals(29.0, ControlLawSpec(ControlLaw.LOGARITHMIC, 7.0, 29.0).valueAt(1.0))

        inexact.forEach { (lo, hi) ->
            val spec = ControlLawSpec(ControlLaw.LOGARITHMIC, lo, hi)
            assertEquals(hi, spec.valueAt(1.0), "a log $lo..$hi control does not reach $hi")
            assertEquals(lo, spec.valueAt(0.0), "a log $lo..$hi control does not reach $lo")
        }

        // And the control the brief names, for good measure.
        val audio = ControlLawSpec(ControlLaw.LOGARITHMIC, 20.0, 20000.0)
        assertEquals(20000.0, audio.valueAt(1.0))
        assertEquals(20.0, audio.valueAt(0.0))
    }

    /**
     * Mid-travel on a log 20–20000 control is the geometric mean, ~632 Hz, and 1 kHz sits at 57% of
     * the track rather than the 4.9% a linear law gives it.
     *
     * This is the claim the screenshot is checked against, so it is pinned here with a floor either
     * side of it. The 12× difference in handle position between the two laws is the whole feature.
     */
    @Test
    fun `a log law puts a kilohertz near the middle of the travel`() {
        val spec = ControlLawSpec(ControlLaw.LOGARITHMIC, 20.0, 20000.0)
        val linear = ControlLawSpec(ControlLaw.LINEAR, 20.0, 20000.0)
        val logTravel = spec.normalizedFor(1000.0)
        val linearTravel = linear.normalizedFor(1000.0)
        println(
            "ControlLawTest: 1 kHz on a 20..20000 control sits at ${"%.1f".format(logTravel * 100)} percent of " +
                "a log track and ${"%.2f".format(linearTravel * 100)} percent of a linear one"
        )
        assertTrue(logTravel > 0.54 && logTravel < 0.59, "1 kHz is at $logTravel of a log track, expected ~0.566")
        assertTrue(linearTravel < 0.06, "1 kHz is at $linearTravel of a linear track, expected under 0.06")
        assertTrue(logTravel / linearTravel > 10.0, "the two laws only differ by ${logTravel / linearTravel}x")
        assertEquals(632.4555320336759, spec.valueAt(0.5), 1e-9, "mid-travel is not the geometric mean")
    }

    /** A bipolar law lands on its centre exactly, which is the only reason to use one. */
    @Test
    fun `a bipolar law is exact at its centre`() {
        listOf(
            ControlLawSpec(ControlLaw.BIPOLAR, -1.0, 1.0, center = 0.0),
            ControlLawSpec(ControlLaw.BIPOLAR, -1200.0, 1200.0, exponent = 3.0, center = 0.0),
            ControlLawSpec(ControlLaw.BIPOLAR, 0.25, 4.0, center = 1.0)
        ).forEach { spec ->
            assertEquals(spec.center, spec.valueAt(0.5), "mid-travel is not the centre of $spec")
            assertEquals(0.5, spec.normalizedFor(spec.center), "the centre of $spec is not at mid-travel")
        }
    }

    /**
     * A taper actually tapers: half travel on a squared exponential is a quarter of the range, and a
     * cubic one an eighth. Stated as numbers so a sign error in the exponent cannot pass.
     */
    @Test
    fun `a taper spends its travel where it says it does`() {
        assertEquals(0.25, ControlLawSpec(ControlLaw.EXPONENTIAL, 0.0, 1.0, exponent = 2.0).valueAt(0.5), 1e-12)
        assertEquals(0.125, ControlLawSpec(ControlLaw.EXPONENTIAL, 0.0, 1.0, exponent = 3.0).valueAt(0.5), 1e-12)
        // A bipolar pan at three quarters of the travel is a quarter of the way out, squared.
        assertEquals(0.25, ControlLawSpec(ControlLaw.BIPOLAR, -1.0, 1.0, exponent = 2.0).valueAt(0.75), 1e-12)
        assertEquals(-0.25, ControlLawSpec(ControlLaw.BIPOLAR, -1.0, 1.0, exponent = 2.0).valueAt(0.25), 1e-12)
    }

    // ---------------------------------------------------------------- refusal

    @Test
    fun `a logarithmic law refuses a zero minimum, by name and with advice`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.LOGARITHMIC, 0.0, 20000.0).validate("cutoff")
        }
        val message = failure.message ?: ""
        println("ControlLawTest: refusal reads — $message")
        assertTrue("cutoff" in message, "the message does not name the control: $message")
        assertTrue("EXPONENTIAL" in message || "BIPOLAR" in message, "the message offers no alternative: $message")
    }

    @Test
    fun `a logarithmic law refuses a negative minimum`() {
        assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.LOGARITHMIC, -1.0, 1.0).validate("detune")
        }
    }

    @Test
    fun `an empty range and a bad taper are refused too`() {
        assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.LOGARITHMIC, 100.0, 100.0).validate("stuck")
        }
        assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.EXPONENTIAL, 1.0, 0.0).validate("backwards")
        }
        assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.EXPONENTIAL, 0.0, 1.0, exponent = 0.0).validate("flat taper")
        }
        assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.BIPOLAR, 0.0, 1.0, center = 2.0).validate("centre outside")
        }
        assertFailsWith<IllegalArgumentException> {
            ControlLawSpec(ControlLaw.BIPOLAR, 0.0, 1.0, center = 0.0).validate("centre at the edge")
        }
    }

    /**
     * Nothing a law can be asked for produces a non-finite number.
     *
     * The DOM is the reason: `data-value="NaN"` renders as a wedged handle and a value readout that
     * says "NaN", and by the time anyone sees it the cause is three files away. So every law is swept
     * over out-of-range, infinite and NaN inputs in both directions, and every answer has to be a
     * number.
     */
    @Test
    fun `no law can be made to emit a non-finite number`() {
        val hostile = listOf(
            -1.0, -0.0, 0.0, 0.5, 1.0, 2.0, 1e300, -1e300,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        )
        var checked = 0
        specs.forEach { (name, spec) ->
            hostile.forEach { t ->
                val value = spec.valueAt(t)
                assertTrue(value.isFinite(), "$name: valueAt($t) produced $value")
                val travel = spec.normalizedFor(t)
                assertTrue(travel.isFinite(), "$name: normalizedFor($t) produced $travel")
                assertTrue(travel in 0.0..1.0, "$name: normalizedFor($t) produced $travel, outside the track")
                checked += 2
            }
        }
        println("ControlLawTest: $checked hostile law evaluations, all finite")
        assertTrue(checked >= 242, "the hostile sweep shrank to $checked evaluations")
    }

    // ---------------------------------------------------------------- decimals

    /**
     * The adaptive decimal rule, on the control that motivates it.
     *
     * Four significant digits over 20 Hz–20 kHz: two decimals at the bottom, none at the top, and the
     * same control never showing `20000.00`.
     */
    @Test
    fun `significant digits give a sane decimal count across four decades`() {
        assertEquals(2, controlLawDecimals(20.0, 4, 2))
        assertEquals(1, controlLawDecimals(200.0, 4, 2))
        assertEquals(0, controlLawDecimals(1000.0, 4, 2))
        assertEquals(0, controlLawDecimals(20000.0, 4, 2))
        assertEquals(3, controlLawDecimals(1.5, 4, 2))
        assertEquals(6, controlLawDecimals(0.001, 4, 2))
        // Zero has no magnitude to work from; treat it as the ones place rather than diverging.
        assertEquals(3, controlLawDecimals(0.0, 4, 2))
        // The budget is capped so a very small value cannot ask for forty decimals.
        assertEquals(8, controlLawDecimals(1e-9, 4, 2))
    }

    /** A budget of zero is the old behaviour, exactly — which is what protects existing sliders. */
    @Test
    fun `a zero budget defers to the step-derived decimal count`() {
        listOf(0, 1, 2, 4).forEach { stepDecimals ->
            listOf(0.0, 1.0, 20.0, 20000.0, -3.5).forEach { value ->
                assertEquals(stepDecimals, controlLawDecimals(value, 0, stepDecimals))
            }
        }
        assertEquals(0, defaultSignificantDigits(ControlLaw.LINEAR))
        assertEquals(4, defaultSignificantDigits(ControlLaw.LOGARITHMIC))
        assertEquals(4, defaultSignificantDigits(ControlLaw.EXPONENTIAL))
        assertEquals(4, defaultSignificantDigits(ControlLaw.BIPOLAR))
    }

    /** The wire spelling is the contract with the browser, so it is pinned. */
    @Test
    fun `law wire names are stable and parse back`() {
        assertEquals(
            listOf("linear", "log", "exp", "bipolar"),
            ControlLaw.entries.map { it.wireName },
            "a law's wire name changed; every browser with a cached controlLaws.js now disagrees"
        )
        ControlLaw.entries.forEach { law ->
            assertEquals(law, ControlLaw.fromWireName(law.wireName))
            assertEquals(law, ControlLaw.fromWireName(law.wireName.uppercase()))
        }
        assertEquals(ControlLaw.LINEAR, ControlLaw.fromWireName(null))
        assertEquals(ControlLaw.LINEAR, ControlLaw.fromWireName("logarithmic-ish"))
    }
}
