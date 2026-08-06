package afield.rectsl

import afield.stdlib.FnAutoDiff
import afield.stdlib.FnGradientMode
import afield.stdlib.Functions
import afield.stdlib.extractParameters
import afield.stdlib.fitGradient
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The derivative, and then the derivative doing work.
 *
 * `RectSLFnBackendTest` establishes that the lowered track computes the same *value* the sampler
 * does. That says nothing about the gradient: a second interpreter can agree on values everywhere
 * and still differentiate the wrong thing. So the gradient is checked against a central finite
 * difference — the only oracle for a derivative that does not involve trusting the derivative — and
 * the fit is reported as a residual before and a residual after, both measured.
 */
class RectSLFitTest {

    /** A clamped identity in time: what RectSL uses wherever a value has to follow the clock. */
    private fun ramp(duration: Double = 2.0): Track<Double> =
        LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(duration, duration)))

    private fun param(name: String, value: Double): Track<Double> = FreeParamTrack(name, value)

    private fun binary(a: Track<Double>, b: Track<Double>, op: TrackOp2) = BinaryDoubleTrack(a, b, op)

    /**
     * A spread of parameterised tracks covering every lowering shape that has one: keyed linear,
     * eased, TCB, the unary ops, the binary ops, a ternary, and an affine time warp.
     */
    private fun corpus(): List<Pair<String, Track<Double>>> = listOf(
        "gain * ramp + bias" to binary(
            binary(ramp(), param("gain", 1.3), TrackOp2.MUL),
            param("bias", -0.4),
            TrackOp2.ADD
        ),
        "amp * sin(freq * ramp)" to binary(
            MappedDoubleTrack(binary(ramp(), param("freq", 2.1), TrackOp2.MUL), TrackOp1.SIN),
            param("amp", 0.8),
            TrackOp2.MUL
        ),
        "amp * cos(freq * ramp + phase)" to binary(
            MappedDoubleTrack(
                binary(
                    binary(ramp(), param("freq", 1.7), TrackOp2.MUL),
                    param("phase", 0.3),
                    TrackOp2.ADD
                ),
                TrackOp1.COS
            ),
            param("amp", 1.9),
            TrackOp2.MUL
        ),
        "amp * quad-out keys" to binary(
            EasedScalarTrack(listOf(TcbKey(0.0, -1.0), TcbKey(1.0, 2.0), TcbKey(2.0, 0.5)), "quad-out"),
            param("amp", 0.7),
            TrackOp2.MUL
        ),
        "tcb + offset" to binary(
            TcbTrack(listOf(TcbKey(0.0, 0.0), TcbKey(1.0, 3.0), TcbKey(2.0, -1.0))),
            param("offset", 0.25),
            TrackOp2.ADD
        ),
        "abs(ramp - pivot)" to MappedDoubleTrack(
            binary(ramp(), param("pivot", 1.6), TrackOp2.SUB),
            TrackOp1.ABS
        ),
        "sqrt(radius * ramp)" to MappedDoubleTrack(
            binary(ramp(), param("radius", 2.4), TrackOp2.MUL),
            TrackOp1.SQRT
        ),
        "pow(ramp, exponent)" to binary(ramp(), param("exponent", 1.4), TrackOp2.POW),
        "max(floorValue, ramp)" to binary(param("floorValue", 0.9), ramp(), TrackOp2.MAX),
        "min(ceilValue, ramp)" to binary(param("ceilValue", 1.1), ramp(), TrackOp2.MIN),
        "clamp(ramp, lo, hi)" to TernaryDoubleTrack(
            ramp(), param("lo", 0.4), param("hi", 1.5), TrackOp3.CLAMP
        ),
        "mix(from, to, ramp/2)" to TernaryDoubleTrack(
            param("from", -1.0),
            param("to", 2.0),
            binary(ramp(), constantTrack(2.0), TrackOp2.DIV),
            TrackOp3.MIX
        ),
        "reverse-time(gain * ramp)" to TimeWarpTrack(
            binary(ramp(), param("gain", 1.1), TrackOp2.MUL),
            TimeWarpKind.REVERSE,
            2.0
        ),
        "time*(gain * ramp)" to TimeWarpTrack(
            binary(ramp(), param("gain", 0.6), TrackOp2.MUL),
            TimeWarpKind.SCALE,
            1.5
        ),
        "time+(gain * ramp)" to TimeWarpTrack(
            binary(ramp(), param("gain", 1.4), TrackOp2.MUL),
            TimeWarpKind.SHIFT,
            0.35
        ),
        "saturate(gain * ramp)" to MappedDoubleTrack(
            binary(ramp(), param("gain", 0.31), TrackOp2.MUL),
            TrackOp1.SATURATE
        ),
        // The shapes Fn.floor opened up on 2026-08-06. Their derivatives are genuinely zero almost
        // everywhere, so what the oracle checks here is that the exact path reports the *same* zero a
        // central difference does rather than something plausible-looking — a wrong non-zero
        // derivative through a staircase is exactly the failure that would be hardest to notice.
        "floor(gain * ramp)" to MappedDoubleTrack(
            binary(ramp(), param("gain", 1.7), TrackOp2.MUL),
            TrackOp1.FLOOR
        ),
        "fract(gain * ramp)" to MappedDoubleTrack(
            binary(ramp(), param("gain", 1.3), TrackOp2.MUL),
            TrackOp1.FRACT
        ),
        // And a looped clock, which is the case the floor was really added for: the wrapped time is a
        // perfectly good differentiable function of the gain on either side of a wrap.
        "loop-time(gain * ramp)" to TimeWarpTrack(
            binary(ramp(4.0), param("gain", 1.2), TrackOp2.MUL),
            TimeWarpKind.LOOP,
            0.7
        ),
        "pingpong-time(gain * ramp)" to TimeWarpTrack(
            binary(ramp(4.0), param("gain", 0.9), TrackOp2.MUL),
            TimeWarpKind.PINGPONG,
            0.6
        ),
        "gate * gain * ramp" to binary(
            GateTrack(0.4, 1.6),
            binary(ramp(), param("gain", 1.5), TrackOp2.MUL),
            TrackOp2.MUL
        )
    )

    @Test
    fun theExactGradientAgreesWithACentralDifference() {
        // Times chosen off the segment boundaries and off the kinks of abs/clamp/min/max, where a
        // central difference straddles the corner and neither answer is the derivative.
        val times = listOf(0.13, 0.37, 0.61, 0.88, 1.17, 1.43, 1.71, 1.94)
        var worstOverall = 0.0
        var worstAt = "(none)"
        println("=== exact gradient vs central difference ===")
        for ((label, track) in corpus()) {
            val worst = track.gradientAgreement(times)
            println("  ${"%.3e".format(worst)}  $label   params=${track.freeParameters().keys}")
            assertTrue(track.freeParameters().isNotEmpty(), "$label should have free parameters")
            if (worst > worstOverall) {
                worstOverall = worst
                worstAt = label
            }
        }
        println("worst relative disagreement: $worstOverall (at $worstAt)")
        // A central difference of a smooth function is O(h^2) accurate at h = 1e-6 relative, so
        // ~1e-8 is the finite difference's own error, not the gradient's.
        assertTrue(worstOverall < 1e-6, "worst gradient disagreement $worstOverall at $worstAt")
    }

    @Test
    fun aFitMeasurablyReducesItsResidual() {
        // The classic inverse-animation question: an orbiting rect must be at three places at three
        // times. Amplitude, phase and centre are unknown; the frequency is authored.
        val track = BinaryDoubleTrack(
            BinaryDoubleTrack(
                MappedDoubleTrack(
                    BinaryDoubleTrack(
                        BinaryDoubleTrack(ramp(4.0), constantTrack(1.4), TrackOp2.MUL),
                        FreeParamTrack("phase", 0.0),
                        TrackOp2.ADD
                    ),
                    TrackOp1.COS
                ),
                FreeParamTrack("amp", 1.0),
                TrackOp2.MUL
            ),
            FreeParamTrack("centre", 0.0),
            TrackOp2.ADD
        )
        val targets = listOf(
            TrackFitTarget(0.5, 3.0),
            TrackFitTarget(1.7, -1.0),
            TrackFitTarget(3.1, 2.0)
        )
        val fit = track.fitParameters(targets, iterations = 800, lr = 0.05)

        println("=== a fit, measured ===")
        println("seed:     ${fit.initialParameters}")
        println("fitted:   ${fit.parameters}")
        println("residual: ${fit.initialResidual} -> ${fit.finalResidual}")
        println("reduction: ${"%.1f".format(100.0 * fit.residualReduction)}%")
        for (target in targets) {
            println(
                "  t=${target.time} want=${target.value} " +
                    "seed=${track.sample(target.time)} fitted=${fit.fitted.sample(target.time)}"
            )
        }

        assertTrue(fit.improved, "the fit should end closer than it started")
        assertTrue(
            fit.residualReduction > 0.9,
            "expected the residual to fall by more than 90%, got " +
                "${"%.1f".format(100.0 * fit.residualReduction)}% " +
                "(${fit.initialResidual} -> ${fit.finalResidual})"
        )
        // The substituted track is a real track, so it agrees with the lowered graph it was solved
        // through. That closes the loop: the fit is not a number in a report, it is an animation.
        for (target in targets) {
            assertEquals(
                fit.fittedFn.eval(target.time),
                fit.fitted.sample(target.time),
                1e-9,
                "the substituted track should reproduce the fitted graph at t=${target.time}"
            )
        }
    }

    @Test
    fun aFitRefusesATrackWithNothingToSolveFor() {
        val plain = ramp()
        val failure = runCatching {
            plain.fitParameters(listOf(TrackFitTarget(0.5, 1.0)))
        }.exceptionOrNull()
        assertNotNull(failure, "a fit with no free parameters must refuse")
        assertTrue(
            "free-param" in (failure.message ?: ""),
            "the refusal should say how to declare one: ${failure.message}"
        )
    }

    @Test
    fun aFitRefusesATrackThatDoesNotLower() {
        // Division by a track rather than by a constant: the near-zero guard is a discontinuity, and
        // only a statically constant divisor folds it.
        val divided = BinaryDoubleTrack(
            BinaryDoubleTrack(ramp(), FreeParamTrack("gain", 1.0), TrackOp2.MUL),
            LinearScalarTrack(listOf(TcbKey(0.0, 1.0), TcbKey(2.0, 3.0))),
            TrackOp2.DIV
        )
        val failure = runCatching {
            divided.fitParameters(listOf(TrackFitTarget(0.5, 1.0), TrackFitTarget(1.5, 2.0)))
        }.exceptionOrNull()
        assertNotNull(failure, "a fit on a non-lowering track must refuse")
        assertTrue(
            "guard" in (failure.message ?: ""),
            "the refusal should carry the lowering's own reason: ${failure.message}"
        )
    }

    /**
     * The honesty follow-through for having lowered the staircases.
     *
     * `floor`, a gate and a hold track all lower faithfully now, and their derivative really is zero
     * almost everywhere. A parameter reachable only through one is therefore present in the graph and
     * unsolvable, and a descent on it would print a residual that never moved and call it
     * convergence. That has to be an error, not a result.
     */
    @Test
    fun aFitRefusesAParameterWithNothingButAStaircaseAboveIt() {
        val stuck = MappedDoubleTrack(
            BinaryDoubleTrack(ramp(4.0), FreeParamTrack("gain", 1.0), TrackOp2.MUL),
            TrackOp1.FLOOR
        )
        val failure = runCatching {
            stuck.fitParameters(listOf(TrackFitTarget(1.0, 7.0), TrackFitTarget(3.0, 9.0)))
        }.exceptionOrNull()
        assertNotNull(failure, "a fit whose only parameter is under a floor must refuse")
        println("staircase refusal: ${failure.message}")
        assertTrue("zero derivative" in (failure.message ?: ""), "${failure.message}")
        assertTrue("gain" in (failure.message ?: ""), "should name the stuck parameter")
        // But a parameter *beside* a staircase is still solvable: the gate multiplies in, and the
        // gain underneath it keeps a live gradient wherever the gate is open.
        val beside = BinaryDoubleTrack(
            GateTrack(0.0, 3.0),
            BinaryDoubleTrack(ramp(4.0), FreeParamTrack("gain", 1.0), TrackOp2.MUL),
            TrackOp2.MUL
        )
        val fit = beside.fitParameters(listOf(TrackFitTarget(2.0, 5.0)), iterations = 600)
        println("beside a gate: residual ${fit.initialResidual} -> ${fit.finalResidual}")
        assertTrue(fit.finalResidual < 1e-6, "residual ${fit.finalResidual}")
    }

    @Test
    fun aLoweredTrackIsAcceptableToTheStdlibFitterUnderEXACT() = with(Functions) {
        // The other half of the bridge: the graphs this backend builds are inside
        // FnAutoDiff.differentiableOps rather than merely near it, so the stdlib's own fitter
        // accepts one under EXACT instead of refusing it.
        //
        // Only one step is taken, and that is a finding rather than timidity. `fitGradient` moves
        // *every* numeric leaf, and a lowered track's leaves are mostly not parameters — they are
        // key times and clamp bounds. Left to descend freely, Adam walks the `0.0` and `1.0` of a
        // keyed track's clamp01 past each other, and `coerceIn` throws on the inverted range. That
        // is exactly why `fitParameters` masks θ down to the named parameters, and it is worth
        // knowing that the unmasked fitter is not a substitute.
        val seedTrack = BinaryDoubleTrack(
            MappedDoubleTrack(
                BinaryDoubleTrack(ramp(1.0), constantTrack(1.0), TrackOp2.MUL),
                TrackOp1.SIN
            ),
            constantTrack(0.6),
            TrackOp2.MUL
        )
        val seed = assertNotNull(seedTrack.toFn(Functions.Domain(0.0, 1.0)).valueOrNull())
        assertTrue(
            FnAutoDiff.supports(seed),
            "a lowered track should be fully differentiable, unsupported: " +
                FnAutoDiff.unsupportedOps(seed)
        )
        assertTrue(
            FnAutoDiff.agrees(seed, DoubleArray(65) { it / 64.0 }),
            "the AD interpreter should reproduce the lowered graph's own values"
        )
        val target = sin(1.0, amplitude = 0.9, offset = 0.1).withDomain(Functions.Domain(0.0, 1.0))
        val run = fitGradient(
            target = target,
            seed = seed,
            domain = Functions.Domain(0.0, 1.0),
            iterations = 1,
            gradient = FnGradientMode.EXACT
        )
        println("=== fitGradient(EXACT) on a lowered track ===")
        println("leaves: ${seed.extractParameters().size}, exact gradient used: ${run.usedExactGradient}")
        println("rmse after one step: ${run.initialError} -> ${run.finalError}")
        assertTrue(run.usedExactGradient, "EXACT should not have fallen back")
    }

    @Test
    fun theFreeParamWordReachesTheSceneAndSolves() {
        val source = """
            #rectsl
            ->wall 18 8 meters
            ->duration 4
            ->fps 30

            linear-track "sweep" '( 0=0   4=4 )

            "marker" rect
              0 0 ->xy
              1 1 ->wh
              "sweep" track-ref 1.0 free-param gain * 0.0 free-param bias + ->x
              0xeeeeee solid ->fill
            ;
        """.trimIndent()
        val result = RectAnimationOracle.compile(source)
        val program = assertNotNull(
            result.programOrNull,
            "the free-param word should compile: ${result.diagnostics.joinToString("; ")}"
        )
        val rect = program.nodes.filterIsInstance<RectNode>().single()
        val params = rect.x.freeParameters()
        assertEquals(setOf("gain", "bias"), params.keys)
        println("=== the free-param word, end to end ===")
        println("x track: ${rect.x.describe()}")

        // A free parameter is still just the number it is, so the scene plays as authored.
        assertEquals(2.0, rect.x.sample(2.0), 1e-12)

        // And it solves. Two targets, two unknowns, an exactly determined linear system.
        val fit = rect.x.fitParameters(
            listOf(TrackFitTarget(1.0, -4.0), TrackFitTarget(3.0, 4.0)),
            iterations = 1200,
            lr = 0.05
        )
        println("fitted: ${fit.parameters}, residual ${fit.initialResidual} -> ${fit.finalResidual}")
        assertTrue(fit.finalResidual < 1e-3, "residual should reach ~0, got ${fit.finalResidual}")
        // gain = 4, bias = -8 is the unique answer to 0.0 + 1*g + b = -4 and 3*g + b = 4.
        assertEquals(4.0, fit.parameters.getValue("gain"), 1e-2)
        assertEquals(-8.0, fit.parameters.getValue("bias"), 1e-2)
        assertTrue(abs(fit.fitted.sample(3.0) - 4.0) < 1e-2)
    }
}
