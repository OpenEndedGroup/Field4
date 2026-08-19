package afield.rectsl

import afield.stdlib.Functions
import afield.stdlib.gradientAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The differentiable heading, checked where a heading actually goes wrong.
 *
 * `RectSLFnBackendTest` already compares every gallery rotation against a pre-refactor golden and
 * finds them equal to 7.7e-14, which is the broad evidence. It is not the *sharp* evidence, because
 * a corpus of pretty animations does not deliberately visit the two places an `atan2` lowering can
 * be catastrophically rather than slightly wrong:
 *
 * 1. **The branch cut.** `atan2` jumps by a full turn across the negative x axis. A lowering that
 *    took the jump at a different sample, or in the wrong direction, would still agree to 1e-14 at
 *    every other time and would spin a rect through 180 degrees at one.
 * 2. **The signed zeros.** `atan2(+0.0, -1.0)` is `+pi` and `atan2(-0.0, -1.0)` is `-pi` — half a
 *    turn apart, from two inputs that compare equal. `RectSLSimplify`'s rule table is built around
 *    this fact, and it is the whole reason `FnExpr.Atan2` is a primitive rather than
 *    `atan(y/x)` with a quadrant fix-up. [theQuadrantFixUpIsHalfATurnOutAtANegativeZero] measures
 *    the failure of the form this file's refusal text used to predict, so the choice stays a
 *    measurement rather than an assertion of taste.
 *
 * On top of those: the widening search that [HeadingTrack.sample] runs when the velocity vanishes,
 * the gradient against a central difference, and a fit that solves for a heading — because a
 * derivative nobody descends is a derivative nobody has checked.
 */
class RectSLHeadingTest {

    private val window = Functions.Domain(0.0, 4.0)

    private fun lower(track: Track<Double>, over: Functions.Domain = window): Functions.Fn =
        when (val lowered = track.toFn(over)) {
            is RectSLFnLowering.Lowered -> lowered.value
            is RectSLFnLowering.Refused -> fail("expected a lowering, got a refusal: ${lowered.why}")
        }

    /** A clamped identity in time — RectSL's own spelling of "follow the clock". */
    private fun clock(duration: Double = 4.0): Track<Double> =
        LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(duration, duration)))

    private fun times(count: Int, from: Double = 0.0, to: Double = 4.0): List<Double> =
        (0..count).map { from + (to - from) * it / count.toDouble() }

    // ---------------------------------------------------------------------------------------------
    // Values
    // ---------------------------------------------------------------------------------------------

    /**
     * A rect on a circle winds through all four quadrants and therefore over the cut, twice in the
     * window. The lowered heading has to jump where the sampler jumps, at the same sample and by the
     * same amount.
     *
     * Measured: 4001 of 4001 samples **bit-identical**, worst absolute difference 0.0. Bit-equality
     * is nevertheless not what is asserted, because it is not what is *promised*: where the position
     * tracks are themselves nested re-associations the lowering is exact arithmetic without being a
     * bit-identical spelling, and a central difference over `1/240` of a second turns a one-ulp
     * position error into a ~1e-14 angle error (the gallery's worst, in a `cascade-split` rotation,
     * is 7.7e-14). The tolerance is set where rounding lives; a mistaken branch would be 6.28, five
     * orders of magnitude away.
     */
    @Test
    fun theLoweredHeadingFollowsTheSamplerAroundTheWholeCircle() {
        val radius = 3.0
        // Two full revolutions in the window, so the trajectory crosses the cut twice.
        val rate = 2.0 * PI / 2.0
        val phase = BinaryDoubleTrack(clock(), constantTrack(rate), TrackOp2.MUL)
        val x = BinaryDoubleTrack(
            MappedDoubleTrack(phase, TrackOp1.COS), constantTrack(radius), TrackOp2.MUL
        )
        val y = BinaryDoubleTrack(
            MappedDoubleTrack(phase, TrackOp1.SIN), constantTrack(radius), TrackOp2.MUL
        )
        val heading = HeadingTrack(x, y)
        val fn = lower(heading)

        var worst = 0.0
        var worstAt = -1.0
        var identical = 0
        var crossings = 0
        var previous = heading.sample(0.0)
        val grid = times(4000)
        for (t in grid) {
            val expected = heading.sample(t)
            val got = fn.eval(t)
            if (expected.toRawBits() == got.toRawBits()) identical++
            val delta = abs(expected - got)
            if (delta > worst) {
                worst = delta
                worstAt = t
            }
            // A cut crossing is a jump of about a full turn in the *sampler*; count them so a run
            // that never reached the cut cannot read as a pass.
            if (abs(expected - previous) > PI) crossings++
            previous = expected
        }
        println(
            "heading around the circle: worst |delta| $worst at t=$worstAt, " +
                "$identical/${grid.size} bit-identical, $crossings branch-cut crossings"
        )
        assertTrue(crossings >= 2, "the trajectory should have crossed the cut twice, saw $crossings")
        assertTrue(worst < 1e-12, "worst heading disagreement $worst at t=$worstAt")
        // The sampler and the lowering must jump at the *same* samples, not merely end up close on
        // average: compare the two difference sequences rather than the two value sequences.
        for (i in 1 until grid.size) {
            val samplerJump = heading.sample(grid[i]) - heading.sample(grid[i - 1])
            val loweredJump = fn.eval(grid[i]) - fn.eval(grid[i - 1])
            assertTrue(
                abs(samplerJump - loweredJump) < 1e-9,
                "the lowering jumped differently between t=${grid[i - 1]} and t=${grid[i]}: " +
                    "sampler $samplerJump, lowered $loweredJump"
            )
        }
    }

    /**
     * Travelling straight along **-x** is the branch cut itself, and it is not an exotic case: it is
     * every rect that walks left across the wall. `dy` is exactly `+0.0` there, so the answer is
     * exactly `+pi`, and this is asserted bit for bit.
     */
    @Test
    fun aRectTravellingLeftPointsAtExactlyPlusPiAndNotMinusPi() {
        val x = LinearScalarTrack(listOf(TcbKey(0.0, 5.0), TcbKey(4.0, -5.0)))
        val y = constantTrack(1.25)
        val heading = HeadingTrack(x, y)
        val fn = lower(heading)
        for (t in times(200, 0.5, 3.5)) {
            val expected = heading.sample(t)
            assertEquals(PI, expected, 0.0, "the sampler itself should read +pi at t=$t")
            assertEquals(
                expected.toRawBits(),
                fn.eval(t).toRawBits(),
                "heading at t=$t: sampler=$expected lowered=${fn.eval(t)}"
            )
        }
        // And the same trajectory mirrored: travelling right is exactly 0, not -0.0-flavoured noise.
        val rightwards = HeadingTrack(
            LinearScalarTrack(listOf(TcbKey(0.0, -5.0), TcbKey(4.0, 5.0))), y
        )
        val rightFn = lower(rightwards)
        for (t in times(50, 0.5, 3.5)) {
            assertEquals(
                rightwards.sample(t).toRawBits(),
                rightFn.eval(t).toRawBits(),
                "rightward heading at t=$t"
            )
        }
    }

    /**
     * The primitive, at the four corners the composed form cannot reach. Two inputs that compare
     * equal — `+0.0` and `-0.0` — have to produce answers half a turn apart.
     */
    @Test
    fun fnAtan2DistinguishesTheZerosThatAreHalfATurnApart() = with(Functions) {
        val corners = listOf(
            Triple(0.0, -1.0, PI),
            Triple(-0.0, -1.0, -PI),
            Triple(0.0, 1.0, 0.0),
            Triple(-0.0, 1.0, -0.0),
            Triple(1.0, 0.0, PI / 2.0),
            Triple(-1.0, 0.0, -PI / 2.0)
        )
        for ((y, x, expected) in corners) {
            val fn = fnLeaf(y).atan2(fnLeaf(x))
            assertEquals(
                expected.toRawBits(),
                fn.eval(0.3).toRawBits(),
                "Fn.atan2($y, $x) should be $expected, was ${fn.eval(0.3)}"
            )
            assertEquals(
                atan2(y, x).toRawBits(),
                fn.eval(0.3).toRawBits(),
                "Fn.atan2 should be kotlin.math.atan2 bit for bit at ($y, $x)"
            )
        }
    }

    /**
     * **The measured refusal.** `RectSLFn.kt` used to predict that closing this gap needed
     * "an FnExpr.Atan plus the quadrant fix-up atan2 needs, which the exact step from fnStepFrom can
     * now supply". This builds exactly that — `atan(y/x)` plus `pi` where `x < 0`, with the sign of
     * the correction chosen by the exact step — and shows it is a full half turn out at a negative
     * zero, which is the size of error `RectSLSimplify` says is a real bug here.
     *
     * The point is not that the prediction was careless. It is that the failure is invisible to any
     * test that does not deliberately feed it a `-0.0`: the composed form is correct at every other
     * double, including every `-0.0`-free neighbourhood of the cut. Keeping the measurement means
     * the choice of primitive stays justified rather than merely asserted.
     */
    @Test
    fun theQuadrantFixUpIsHalfATurnOutAtANegativeZero() = with(Functions) {
        /** `atan(y/x) + pi*sign-ish correction` — the form the old refusal text proposed. */
        fun composed(y: Functions.Fn, x: Functions.Fn): Functions.Fn {
            val negativeX = 1.0 - fnStepFrom(x)          // 1 where x < 0
            val negativeY = 1.0 - fnStepFrom(y)          // 1 where y < 0, by *value*
            val correction = negativeX * (fnLeaf(PI) - fnLeaf(2.0 * PI) * negativeY)
            return (y / x).atan() + correction
        }

        // Away from the zeros the composed form is right, which is exactly what makes it dangerous.
        var worstOrdinary = 0.0
        for (yv in listOf(-2.0, -0.5, 0.5, 2.0)) {
            for (xv in listOf(-3.0, -0.75, 0.75, 3.0)) {
                val got = composed(fnLeaf(yv), fnLeaf(xv)).eval(0.0)
                worstOrdinary = maxOf(worstOrdinary, abs(got - atan2(yv, xv)))
            }
        }
        assertTrue(
            worstOrdinary < 1e-12,
            "the composed form should be right away from the zeros, worst $worstOrdinary"
        )

        // At a negative zero it is a half turn out, and the primitive is not.
        val truth = atan2(-0.0, -1.0)
        val composedValue = composed(fnLeaf(-0.0), fnLeaf(-1.0)).eval(0.0)
        val primitiveValue = fnLeaf(-0.0).atan2(fnLeaf(-1.0)).eval(0.0)
        println(
            "atan2(-0.0, -1.0): truth $truth, FnExpr.Atan2 $primitiveValue, " +
                "atan(y/x)+quadrant $composedValue (out by ${abs(composedValue - truth)})"
        )
        assertEquals(-PI, truth, 0.0, "kotlin.math.atan2(-0.0, -1.0) is -pi")
        assertEquals(truth.toRawBits(), primitiveValue.toRawBits(), "the primitive is exact")
        assertTrue(
            abs(composedValue - truth) > 6.2,
            "the composed form should be a half turn (2pi in angle-difference terms) out, " +
                "was out by ${abs(composedValue - truth)}"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The widening search
    // ---------------------------------------------------------------------------------------------

    /**
     * A point that never moves has no heading, and the sampler falls through all six probes to the
     * bare offset. The lowering folds that to a literal, which has to be the same literal.
     */
    @Test
    fun aStationaryPointIsTheBareOffset() {
        val still = HeadingTrack(constantTrack(2.0), constantTrack(-1.0), offset = 0.75)
        val fn = lower(still)
        for (t in times(40)) {
            assertEquals(still.sample(t).toRawBits(), fn.eval(t).toRawBits(), "stationary at t=$t")
        }
        assertEquals(0.75, fn.eval(1.0), 0.0)
    }

    /**
     * A trajectory that is *locally* stationary and then moves — the case the widening search exists
     * for. `x` is flat over the first half of the window, so the first four probes see exactly zero
     * velocity and only the fifth, at `eps·4⁴ ≈ 1.07s`, reaches the moving part.
     *
     * Verified as bit-identical, which is the strong claim: the lowering has to take the same probe
     * the sampler takes, at every sample, or the two answers are different angles entirely.
     */
    @Test
    fun theWideningSearchTakesTheSameProbeTheSamplerTakes() {
        val x = LinearScalarTrack(
            listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 0.0), TcbKey(4.0, 6.0))
        )
        val y = LinearScalarTrack(
            listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 0.0), TcbKey(4.0, 2.0))
        )
        val heading = HeadingTrack(x, y)
        val fn = lower(heading)
        var widened = 0
        var identical = 0
        val grid = times(800)
        for (t in grid) {
            // Did the first probe fail? That is the definition of "the search widened".
            val eps = heading.eps
            val dx = x.sample(t + eps) - x.sample(t - eps)
            val dy = y.sample(t + eps) - y.sample(t - eps)
            if (kotlin.math.hypot(dx, dy) <= 1e-9) widened++
            if (heading.sample(t).toRawBits() == fn.eval(t).toRawBits()) identical++
        }
        println(
            "widening search: $widened/${grid.size} samples needed a wider probe, " +
                "$identical/${grid.size} bit-identical"
        )
        assertTrue(widened > 100, "the fixture should have exercised the widening, saw $widened")
        assertEquals(
            grid.size,
            identical,
            "every sample of a widening heading should be bit-identical to the sampler"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Gradients, and a gradient doing work
    // ---------------------------------------------------------------------------------------------

    /**
     * The exact gradient of a heading against a central finite difference of the same thing — the
     * only oracle for a derivative that does not involve trusting the derivative.
     *
     * Sampled away from the cut on purpose. *At* the cut the function really is discontinuous and a
     * finite difference of it is meaningless, so agreement there would be evidence of nothing.
     */
    @Test
    fun theHeadingGradientAgreesWithACentralDifference() {
        val rise = FreeParamTrack("rise", 0.6)
        val x = clock()
        val y = BinaryDoubleTrack(clock(), rise, TrackOp2.MUL)
        val heading = HeadingTrack(x, y)
        val worst = heading.gradientAgreement(times(20, 0.5, 3.5), over = window)
        println("heading gradient vs central difference: worst relative disagreement $worst")
        assertTrue(worst < 1e-6, "heading gradient disagrees with a central difference by $worst")

        // And the derivative is not merely *consistent*, it is non-zero — the distinction that
        // matters, since `floor` and `sign` are consistent and useless.
        val fn = lower(heading)
        val slots = freeParameterSlots(fn)
        assertTrue(slots.containsKey("rise"), "the parameter should have survived lowering")
        val gradient = fn.gradientAt(2.0).gradient
        val total = slots.getValue("rise").sumOf { gradient[it] }
        println("d(heading)/d(rise) at t=2 over ${slots.getValue("rise").size} theta slots: $total")
        assertTrue(abs(total) > 1e-3, "a heading's derivative should be real, was $total")
    }

    /**
     * A heading reads each of its position tracks twelve times — two probes, six widenings — so a
     * free parameter inside one occupies twelve θ slots. This is the regression guard for the fix
     * that made `fitParameters` treat them as one variable.
     */
    @Test
    fun aParameterUnderAHeadingOccupiesEveryProbesSlot() {
        val heading = HeadingTrack(
            BinaryDoubleTrack(clock(), FreeParamTrack("rise", 0.6), TrackOp2.MUL),
            clock()
        )
        val slots = freeParameterSlots(lower(heading))
        println("free-parameter slots under a heading: ${slots.mapValues { it.value.size }}")
        // Six widenings, two probes each, and each probe's difference is read three times — twice
        // by the `moving` indicator (which appears in both `m` and `1 - m`) and once by the
        // `atan2`. Thirty-six, and the number is asserted rather than bounded because it is the
        // graph-size claim `lowerHeading`'s KDoc makes: the nested form is 36 where the
        // masked-sum form it replaced measured 54.
        assertEquals(
            36,
            slots.getValue("rise").size,
            "six widenings x two probes x three reads of each difference"
        )
        assertEquals(
            slots.getValue("rise").first(),
            freeParameterIndices(lower(heading)).getValue("rise"),
            "the single-index spelling should be the first slot"
        )
    }

    /**
     * Inverse animation *through* a heading: how steep does the climb have to be for the dart to
     * point at 45 degrees?
     *
     * The answer is `rise = 1` and the fit has to find it from `0.15` by descending an `atan2`. This
     * is the deliverable the whole change is for — before it, `fitParameters` threw on any track
     * containing a `->face-travel`.
     */
    @Test
    fun aFitSolvesForTheAngleARectPointsAt() {
        val heading = HeadingTrack(
            clock(),
            BinaryDoubleTrack(clock(), FreeParamTrack("rise", 0.15), TrackOp2.MUL)
        )
        val result = heading.fitParameters(
            listOf(TrackFitTarget(1.0, PI / 4.0), TrackFitTarget(3.0, PI / 4.0)),
            over = window,
            iterations = 600
        )
        println(
            "heading fit: rise ${result.initialParameters["rise"]} -> ${result.parameters["rise"]}, " +
                "residual ${result.initialResidual} -> ${result.finalResidual}"
        )
        assertTrue(result.stuckParameters.isEmpty(), "nothing should be stuck: ${result.stuckParameters}")
        assertTrue(result.improved, "the fit should have moved closer")
        assertTrue(
            result.finalResidual < 1e-6,
            "the fit should reach the target angle, residual ${result.finalResidual}"
        )
        assertEquals(1.0, assertNotNull(result.parameters["rise"]), 1e-5)
        // The substituted *track* must agree with the fitted graph — the check that the twelve θ
        // slots really did move together. One slot left behind and these part company.
        for (t in times(20, 0.25, 3.75)) {
            assertEquals(
                result.fitted.sample(t),
                result.fittedFn.eval(t),
                1e-9,
                "the fitted track and the fitted graph disagree at t=$t"
            )
        }
    }

    /**
     * The production consumer, not the harness: a real `->face-travel` program compiled by
     * [RectAnimationOracle], whose rotation channel is then lowered and compared against the scene's
     * own sampler.
     */
    @Test
    fun faceTravelWrittenInRectSLLowersAndAgrees() {
        val source = """
            #rectsl
            ->wall 12 12 abstract
            ->duration 4
            ->fps 30
            "dart" rect
              1.1 0.26 ->wh
              0xffcc33 solid ->fill
              '( 0=-5  2=0  4=5 ) ->x
              '( 0=-4  1=3  3=-3  4=2 ) ->y
              ->face-travel
            ;
        """.trimIndent()
        val compiled = RectAnimationOracle.compile(source)
        assertTrue(compiled.ok, "the program should compile: ${compiled.diagnostics}")
        val rotation = assertNotNull(compiled.programOrNull)
            .nodes.filterIsInstance<RectNode>().single().rotation
        assertTrue(rotation is HeadingTrack, "->face-travel should build a HeadingTrack, got $rotation")
        val fn = lower(rotation)
        var worst = 0.0
        for (t in times(400)) worst = maxOf(worst, abs(rotation.sample(t) - fn.eval(t)))
        println("->face-travel from RectSL source: worst rotation disagreement $worst")
        assertTrue(worst < 1e-12, "the compiled program's heading disagreed by $worst")
    }
}
