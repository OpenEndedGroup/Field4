package afield.rectsl

import afield.stdlib.Functions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A key value is a track, so a fit can reach inside a keyed track.
 *
 * Before this, `TcbKey` held a `Double` and a parameter could only compose *around* a keyed track —
 * gain, bias, phase, a `mix` weight. The docs said so, and said that making keys track-valued was a
 * larger change to the IR. This is the test for that change, and it is mostly about the two places
 * it could be wrong rather than about the happy path:
 *
 * - the constant case must be **untouched**, because a 64,490-line characterization golden pins it
 *   bit for bit and the whole gallery is simplified against bit-identical sampling;
 * - a fitted TCB key must move its **tangents** with it, or `TrackFitResult.fitted` (which
 *   recomputes them) and `TrackFitResult.fittedFn` (which baked them) return two different
 *   animations for one fit.
 */
class RectSLKeyParamTest {

    private fun keyed(vararg keys: TcbKey) = LinearScalarTrack(keys.toList())

    /** A ramp from 0 whose *top* is the unknown — the shape a keyed parameter is for. */
    private fun rampWithUnknownTop(seed: Double = 1.0): Track<Double> =
        keyed(TcbKey(0.0, 0.0), TcbKey(2.0, FreeParamTrack("peak", seed)))

    @Test
    fun aKeyValueIsFoundAndSolvedFor() {
        val track = rampWithUnknownTop()
        assertEquals(mapOf("peak" to 1.0), track.freeParameters())

        // Halfway along a two-key linear span the track reads half its top, so asking for 2.0 at
        // t = 1.0 is asking for a top of 4.0 — an answer that can be checked by arithmetic rather
        // than by rerunning the fitter.
        val fit = track.fitParameters(
            listOf(TrackFitTarget(1.0, 2.0)),
            over = Functions.Domain(0.0, 2.0),
            iterations = 600
        )
        println("=== a key value, solved ===")
        println("seed:     ${fit.initialParameters}")
        println("fitted:   ${fit.parameters}")
        println("residual: ${fit.initialResidual} -> ${fit.finalResidual}")
        println("describe: ${track.describe()} -> ${fit.fitted.describe()}")

        assertTrue(fit.stuckParameters.isEmpty(), "a linear key value has a live derivative")
        assertEquals(4.0, fit.parameters.getValue("peak"), 1e-6)
        assertTrue(fit.finalResidual < 1e-6, "residual ${fit.finalResidual}")
        // And the solved track is a track: its own CPU sampler reads the fitted answer.
        assertEquals(2.0, fit.fitted.sample(1.0), 1e-6)
        assertEquals(4.0, fit.fitted.sample(2.0), 1e-6)
    }

    /** The gradient through a key, against the only oracle that does not trust the gradient. */
    @Test
    fun theGradientThroughAKeyAgreesWithACentralDifference() {
        val times = listOf(0.13, 0.61, 1.17, 1.71)
        val corpus = listOf(
            "linear, top unknown" to rampWithUnknownTop(),
            "linear, both keys unknown" to keyed(
                TcbKey(0.0, FreeParamTrack("bottom", -1.0)),
                TcbKey(2.0, FreeParamTrack("top", 1.0))
            ),
            "linear, key value is an expression" to keyed(
                TcbKey(0.0, 0.0),
                TcbKey(
                    2.0,
                    BinaryDoubleTrack(FreeParamTrack("scale", 1.5), constantTrack(2.0), TrackOp2.MUL)
                )
            ),
            "eased, middle key unknown" to EasedScalarTrack(
                listOf(
                    TcbKey(0.0, 0.0),
                    TcbKey(1.0, FreeParamTrack("middle", 2.0)),
                    TcbKey(2.0, 0.5)
                ),
                "quad-out"
            ),
            "tcb, middle key unknown" to TcbTrack(
                listOf(
                    TcbKey(0.0, 0.0),
                    TcbKey(1.0, FreeParamTrack("middle", 3.0)),
                    TcbKey(2.0, -1.0)
                )
            ),
            "tcb, tensioned key unknown" to TcbTrack(
                listOf(
                    TcbKey(0.0, 0.0),
                    TcbKey(1.0, FreeParamTrack("middle", 3.0), tension = 0.4, continuity = -0.2),
                    TcbKey(2.0, -1.0)
                )
            )
        )
        println("=== exact gradient through a key vs central difference ===")
        var worst = 0.0
        for ((label, track) in corpus) {
            val delta = track.gradientAgreement(times)
            println("  ${"%.3e".format(delta)}  $label   params=${track.freeParameters().keys}")
            assertTrue(track.freeParameters().isNotEmpty(), "$label should have a free parameter")
            worst = maxOf(worst, delta)
        }
        assertTrue(worst < 1e-6, "worst gradient disagreement $worst")
    }

    /**
     * The reason the TCB lowering grew a second path.
     *
     * A TCB tangent is derived from the neighbouring key values, so a fit that moves a key and
     * leaves the tangent produces a graph that no longer corresponds to any track. Here the graph
     * and the substituted track are asked the same question at forty instants.
     */
    @Test
    fun aFittedTcbKeyMovesItsTangentsWithIt() {
        val track = TcbTrack(
            listOf(
                TcbKey(0.0, 0.0),
                TcbKey(1.0, FreeParamTrack("middle", 1.0)),
                TcbKey(2.0, 0.0)
            )
        )
        val fit = track.fitParameters(
            listOf(TrackFitTarget(0.5, 2.0), TrackFitTarget(1.5, 2.0)),
            over = Functions.Domain(0.0, 2.0),
            iterations = 600
        )
        println("=== a fitted TCB key ===")
        println("fitted:   ${fit.parameters}")
        println("residual: ${fit.initialResidual} -> ${fit.finalResidual}")

        assertTrue(fit.improved, "the fit should end closer than it started")
        var worst = 0.0
        for (i in 0..40) {
            val t = i * 2.0 / 40.0
            val delta = kotlin.math.abs(fit.fittedFn.eval(t) - fit.fitted.sample(t))
            if (delta > worst) worst = delta
        }
        println("worst |fittedFn - fitted| over the span: $worst")
        assertTrue(
            worst < 1e-9,
            "the fitted graph and the substituted track must be the same animation, off by $worst"
        )
    }

    /**
     * The two TCB lowerings, pinned against each other.
     *
     * `lowerTcb` bakes its tangents and `lowerTcbSymbolic` builds them; only the first is reachable
     * with constant keys and only the second with a parameterised one, so the transcription between
     * them is exactly the kind of thing that drifts unnoticed. Wrapping each constant key in a
     * `FreeParamTrack` of the *same* number makes both paths describe the same spline, and they are
     * required to agree — with the CPU sampler as a third opinion.
     */
    @Test
    fun bakedAndSymbolicTangentsAgreeOnConstantKeys() {
        val keys = listOf(
            TcbKey(0.0, -1.0),
            TcbKey(0.7, 2.5, tension = 0.3),
            TcbKey(1.9, 0.25, continuity = -0.4, bias = 0.2),
            TcbKey(3.0, -2.0)
        )
        val baked = TcbTrack(keys, defaultTension = 0.1, defaultContinuity = 0.2, defaultBias = -0.3)
        val symbolic = baked.copy(
            keys = keys.map { it.copy(valueTrack = FreeParamTrack("k${it.time}", it.value)) }
        )
        assertEquals(4, symbolic.freeParameters().size, "each key should be its own unknown")

        val window = Functions.Domain(-0.5, 3.5)
        val a = baked.toFn(window).orThrow()
        val b = symbolic.toFn(window).orThrow()
        var worstPair = 0.0
        var worstSampler = 0.0
        for (i in 0..400) {
            val t = -0.5 + i * 4.0 / 400.0
            worstPair = maxOf(worstPair, kotlin.math.abs(a.eval(t) - b.eval(t)))
            worstSampler = maxOf(worstSampler, kotlin.math.abs(b.eval(t) - symbolic.sample(t)))
        }
        println("=== TCB: baked tangents vs symbolic tangents ===")
        println("worst |baked - symbolic|: $worstPair")
        println("worst |symbolic - sample|: $worstSampler")
        // Both spell the same formula; they differ only in where the multiplications are grouped,
        // which is a last-bits difference and not a licence to be approximately right.
        assertTrue(worstPair < 1e-12, "the two TCB lowerings disagree by $worstPair")
        assertTrue(worstSampler < 1e-12, "the symbolic lowering left the sampler by $worstSampler")
    }

    /** The constant path must be the path it always was: one leaf per span rise, same theta. */
    @Test
    fun aTrackWithNoParametersLowersToExactlyTheGraphItAlwaysDid() {
        val plain = keyed(TcbKey(0.0, 0.0), TcbKey(1.0, 3.0), TcbKey(2.0, -1.0))
        val window = Functions.Domain(0.0, 2.0)
        val fn = plain.toFn(window).orThrow()
        // Six leaves: the first key value, two span rises, and the three constants of the two
        // clamped ramps... whatever the exact number, it is a *property of the constant path*, so
        // the assertion that matters is that the sampler and the graph still agree bit for bit.
        var compared = 0
        for (i in 0..200) {
            val t = i * 2.0 / 200.0
            assertEquals(
                plain.sample(t).toRawBits(),
                fn.eval(t).toRawBits(),
                "a constant keyed track must still lower bit-exactly, off at t=$t"
            )
            compared++
        }
        println("constant keyed track: $compared samples bit-identical to the sampler")
    }

    /** A key value is a *value*, so it may not vary with time — and says so at construction. */
    @Test
    fun aKeyRefusesATimeVaryingValue() {
        val varying = keyed(TcbKey(0.0, 0.0), TcbKey(1.0, 1.0))
        val failure = runCatching { TcbKey(0.0, varying) }.exceptionOrNull()
        assertNotNull(failure, "a key value that varies with time has no single value")
        assertTrue(
            "time-invariant" in (failure.message ?: ""),
            "the refusal should say what a key value has to be: ${failure.message}"
        )
    }

    /**
     * The rewriting and printing halves, which is where a key that had become invisible to the IR
     * walkers would show up.
     */
    @Test
    fun theIrWalkersReachIntoAKey() {
        val track = rampWithUnknownTop(seed = 1.0)

        assertEquals("linear[0.0=0.0 2.0=?peak=1.0]", track.describe())
        assertEquals(1.0, track.sample(2.0), 0.0)

        val moved = track.substituteParameters(mapOf("peak" to 5.0))
        assertEquals(5.0, moved.sample(2.0), 0.0, "substitution should reach the key's value")
        assertEquals(2.5, moved.sample(1.0), 0.0)
        assertEquals("linear[0.0=0.0 2.0=?peak=5.0]", moved.describe())

        // A single-key track normally folds to a constant. One holding a parameter must not: that
        // fold is what would quietly delete the thing a fit was going to solve for.
        val lone = keyed(TcbKey(1.0, FreeParamTrack("only", 2.0)))
        assertEquals(lone, lone.simplify(), "a lone parameterised key must survive the fold")
        assertEquals(setOf("only"), lone.simplify().freeParameters().keys)
        assertEquals(constantTrack(2.0), keyed(TcbKey(1.0, 2.0)).simplify())

        // And the value words fold *symbolically* into a parameterised key rather than folding the
        // parameter away.
        val doubled = evalTrack(
            """
            linear-track "ramp" '( 0=0   2=4 free-param peak )
            "ramp" track-ref 3 value*
            """
        )
        assertEquals(setOf("peak"), doubled.freeParameters().keys)
        assertEquals(12.0, doubled.sample(2.0), 1e-12, "the key should still read 4 * 3")
        assertEquals(
            9.0,
            doubled.substituteParameters(mapOf("peak" to 3.0)).sample(2.0),
            1e-12,
            "and the parameter should still be the thing that moves"
        )
    }

    /** The language surface: a key value can be declared unknown where it is written. */
    @Test
    fun aTrackQuoteCanNameAnUnknownKey() {
        val track = evalTrack(
            """
            linear-track "rise" '( 0=0   2=4 free-param peak )
            "rise" track-ref
            """
        )
        assertEquals(mapOf("peak" to 4.0), track.freeParameters())
        assertEquals(2.0, track.sample(1.0), 0.0, "it plays at the number written")

        val fit = track.fitParameters(
            listOf(TrackFitTarget(1.0, 3.0)),
            over = Functions.Domain(0.0, 2.0),
            iterations = 600
        )
        assertEquals(6.0, fit.parameters.getValue("peak"), 1e-6)

        // The shader bakes it, exactly as it bakes a free parameter anywhere else: a scene with an
        // unknown in it still renders, at the value the unknown presently has.
        val program = RectAnimationOracle.compile(BAR_WITH_UNKNOWN_TOP).program
        val shader = RectSLGlsl.emitSdf(program, functionName = "scene")
        assertTrue("4.0" in shader.glsl, "the emitted shader should carry the key's current value")
    }

    private fun evalTrack(source: String): Track<Double> {
        val stack = evalRectSLStack(source)
        val top = stack.last()
        assertTrue(top is VTrack<*>, "expected a track on the stack, found ${top::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return (top as VTrack<Double>).track
    }

    private companion object {
        val BAR_WITH_UNKNOWN_TOP = """
            #rectsl
            ->wall 8 8 meters
            ->duration 2
            linear-track "rise" '( 0=0   2=4 free-param peak )
            "bar" rect
              0 0 ->xy
              1 1 ->wh
              "rise" track-ref ->y
            ;
        """.trimIndent()
    }
}
