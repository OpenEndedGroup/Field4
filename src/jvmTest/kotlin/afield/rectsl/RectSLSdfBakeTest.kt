package afield.rectsl

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Three of RectSL's tracks cannot be lowered to GLSL and are *baked* instead: the
 * dropout gate (a 64-bit integer hash) and the two pattern queries (rational
 * arithmetic over a mini-notation tree). `b57390d2d` recorded that as a note on
 * the emission and left it there.
 *
 * A note is not a measurement, so this suite measures it. All three are piecewise
 * constant in time, which is the whole reason baking them is a fair trade — a
 * step function re-expressed as a run-length-compressed hold chain is *exact*
 * everywhere except within one sample interval of an edge, where the edge lands
 * on the nearer sample instant. So the two things worth knowing are how much of
 * the timeline disagrees, and whether every disagreement really is next to an
 * edge rather than somewhere in the middle of a plateau.
 *
 * The answers, printed by each test so a reader gets the number rather than the
 * adjective, are then held to a bound derived from the bake rate rather than to a
 * hopeful constant.
 */
class RectSLSdfBakeTest {

    /** `->degrade` — the 64-bit hash. One gate track per copy, gating alpha. */
    @Test
    fun `the dropout gate bakes to within half a sample of its edges`() {
        val program = compile(
            """
            #rectsl
            ->wall 20 8 abstract
            ->duration 4
            ->fps 30
            ->cps 1
            "e" rect  0.5 ->square
              16 '( ic 1 * 0 ->offset ) array  0.5 ->degrade
            ;
            """
        )
        val gates = program.tracksOfKind<DegradeGateTrack>()
        assertTrue(gates.size >= 8, "expected a dropout gate per copy, found ${gates.size}")
        report("->degrade", program, gates)
    }

    /** A pattern read straight as a value: `"0 1 2 3" ->x`. */
    @Test
    fun `a pattern-driven track bakes to within half a sample of its edges`() {
        val program = compile(
            """
            #rectsl
            ->wall 8 8 abstract
            ->duration 4
            ->fps 30
            ->cps 1
            "r" rect  1 ->square  "0 1 2 3" ->x ;
            """
        )
        val pats = program.tracksOfKind<PatTrack>()
        assertTrue(pats.isNotEmpty(), "expected a PatTrack from a bare pattern string")
        report("pat", program, pats)
    }

    /** The sample-and-hold reading, which holds a value across a rest. */
    @Test
    fun `a sample-and-hold pattern bakes to within half a sample of its edges`() {
        val program = compile(
            """
            #rectsl
            ->wall 8 8 abstract
            ->duration 4
            ->fps 30
            ->cps 1
            "r" rect  1 ->square  "1 ~ 3" mini nums hold-pat ->x ;
            """
        )
        val pats = program.tracksOfKind<PatHoldTrack>()
        assertTrue(pats.isNotEmpty(), "expected a PatHoldTrack from hold-pat")
        report("hold-pat", program, pats)
    }

    /**
     * The emitter has to *say* it approximated. This is the half that keeps the
     * measurement honest: if a future change lowered these symbolically the notes
     * would go away, and this test would demand the bake measurements go with them.
     */
    @Test
    fun `a baked scene reports its caveats and an unbaked one does not`() {
        val baked = RectSLGlsl.emitSdf(
            compile(
                """
                #rectsl
                ->wall 8 8 abstract
                ->duration 4
                ->cps 1
                "r" rect  1 ->square  "0 1 2 3" ->x ;
                """
            )
        )
        assertTrue(!baked.ok, "a pattern-driven scene must not claim a clean lowering")
        assertTrue(
            baked.notes.any { it.contains("baked as") && it.contains("step(s)") },
            "the caveat should say how many steps the bake became, was ${baked.notes}"
        )

        val clean = RectSLGlsl.emitSdf(
            compile(
                """
                #rectsl
                ->wall 8 8 abstract
                ->duration 4
                "r" rect  1 ->square  '( 0=-2  4=2 ) ->x ;
                """
            )
        )
        assertTrue(clean.ok, "a keyed scene should lower with no caveats, got ${clean.notes}")
    }

    // --- the measurement ----------------------------------------------------

    /**
     * Compare the hold chain the shader evaluates against the track's own sampler
     * on a grid 40x finer than the bake, and hold the result to what a
     * piecewise-constant bake can possibly cost.
     */
    private fun report(label: String, program: SceneProgram, tracks: List<Track<Double>>) {
        val duration = program.duration
        // The emitter's own rate: max(fps * 4, 120) samples per second.
        val bakeInterval = duration / (RectSLGlsl.bakeHoldChain(tracks.first(), duration, program.fps).sampleCount - 1)
        val probes = 40 * (duration / bakeInterval).toInt()

        var worstFraction = 0.0
        var worstValueError = 0.0
        var worstEdgeDistance = 0.0
        var totalSteps = 0
        for (track in tracks) {
            val bake = RectSLGlsl.bakeHoldChain(track, duration, program.fps)
            totalSteps += bake.steps.size
            // Where the sampler's own value changes — the true edges.
            val edges = mutableListOf<Double>()
            var previous = track.sample(0.0)
            var disagreements = 0
            val mismatched = mutableListOf<Double>()
            for (i in 0..probes) {
                val t = duration * i / probes
                val truth = track.sample(t)
                if (truth != previous) edges += t
                previous = truth
                val err = abs(bake.valueAt(t) - truth)
                if (err > 1e-12) {
                    disagreements++
                    mismatched += t
                    worstValueError = maxOf(worstValueError, err)
                }
            }
            worstFraction = maxOf(worstFraction, disagreements.toDouble() / (probes + 1))
            // Every disagreement must sit next to a real edge: that is the difference
            // between "the bake displaced an edge" and "the bake invented a value".
            for (t in mismatched) {
                val nearest = edges.minOfOrNull { abs(it - t) } ?: Double.MAX_VALUE
                worstEdgeDistance = maxOf(worstEdgeDistance, nearest)
            }
        }

        println(
            ("[bake] %-9s %d track(s), %d step(s) total, bake interval %.5fs: " +
                "disagrees on %.4f%% of the timeline, worst value error %.3f, " +
                "every disagreement within %.5fs of a real edge")
                .format(
                    label, tracks.size, totalSteps, bakeInterval,
                    worstFraction * 100.0, worstValueError, worstEdgeDistance
                )
        )

        // A step landing on the nearer sample instant displaces an edge by at most
        // one bake interval, so at a 40x finer probe grid at most ~1/40 + a probe of
        // the timeline can disagree. The generous 3x covers the endpoints.
        assertTrue(
            worstFraction < 3.0 / 40.0,
            "$label: the bake disagrees on ${worstFraction * 100}% of the timeline, far more than " +
                "edge displacement can explain — it is losing whole plateaus, not just edges"
        )
        assertTrue(
            worstEdgeDistance <= bakeInterval * 1.5,
            "$label: a disagreement sat ${worstEdgeDistance}s from any real edge (bake interval " +
                "${bakeInterval}s) — the bake is inventing values in the middle of a plateau"
        )
    }

    private fun compile(source: String): SceneProgram {
        val result = RectAnimationOracle.compile(source.trimIndent())
        return result.programOrNull?.takeIf { result.ok }
            ?: error("scene did not compile:\n${result.diagnostics.joinToString("\n")}")
    }

    /**
     * Every distinct track of one kind anywhere in the program's channel DAG. The
     * baked kinds are never a rect's channel directly — `->degrade` multiplies into
     * alpha — so reaching them means walking.
     */
    private inline fun <reified T : Track<Double>> SceneProgram.tracksOfKind(): List<T> =
        allTracks().filterIsInstance<T>()

    /** Every distinct track anywhere in this program's channel DAG. */
    private fun SceneProgram.allTracks(): List<Track<Double>> {
        val seen = LinkedHashSet<Track<Double>>()
        val roots = ArrayDeque<Track<Double>>()
        nodes.forEach { node ->
            when (node) {
                is RectNode -> roots += listOf(
                    node.x, node.y, node.rotation, node.scaleX, node.scaleY, node.alpha, node.w, node.h
                )
                is GroupNode -> roots += listOf(
                    node.x, node.y, node.rotation, node.scaleX, node.scaleY, node.alpha
                )
                else -> Unit
            }
        }
        while (roots.isNotEmpty()) {
            val track = roots.removeFirst()
            if (!seen.add(track)) continue
            when (track) {
                is MappedDoubleTrack -> roots += track.source
                is BinaryDoubleTrack -> { roots += track.a; roots += track.b }
                is TernaryDoubleTrack -> { roots += track.a; roots += track.b; roots += track.c }
                is TimeWarpTrack -> roots += track.source
                is CycleSwitchTrack -> { roots += track.before; roots += track.after }
                is HeadingTrack -> { roots += track.x; roots += track.y }
                else -> Unit
            }
        }
        return seen.toList()
    }
}
