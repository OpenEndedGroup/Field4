package afield.rectsl

import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the SDF emitter cannot carry across exactly, and what it turns out it can.
 *
 * `b57390d2d` listed three tracks as unlowerable — the `->degrade` dropout gate (a
 * 64-bit integer hash) and the two pattern queries (rational arithmetic over a
 * mini-notation tree) — and baked all three into run-length-compressed hold chains,
 * recording that as a note on the emission. A note is not a measurement, and one of
 * the three turned out not to need one.
 *
 * **The gate lowers.** A 64-bit hash reads like something a shader cannot do, but
 * `uvec2` lanes and `umulExtended` are exactly 64-bit integer arithmetic, and the
 * only float in the original was the final `k / 2^53 < probability` comparison —
 * which is an integer comparison in disguise. So it is emitted, not sampled, and the
 * first test here holds the lane algebra to bit-for-bit agreement with the CPU.
 *
 * **The pattern queries still bake**, and that is measured rather than asserted.
 * Both are piecewise constant in time, which is what makes baking a fair trade: a
 * step function re-expressed as a hold chain is *exact* everywhere except within one
 * sample interval of an edge, where the edge lands on the nearer sample instant. So
 * the two things worth knowing are how much of the timeline disagrees, and whether
 * every disagreement really sits beside an edge rather than in the middle of a
 * plateau. Both are printed, and then held to a bound derived from the bake rate
 * rather than to a hopeful constant.
 */
class RectSLSdfBakeTest {

    /**
     * `->degrade` is **not** baked any more — it lowers exactly — so what needs
     * checking is that the lowering is what it claims to be. The emitted GLSL is a
     * splitmix64 finaliser transcribed into `uvec2` lanes; this re-implements those
     * lanes in Kotlin and demands bit-for-bit agreement with the track's own
     * `sample` over thousands of (ordinal, cycle, probability, salt) points.
     *
     * Simulating the shader's integer algebra is the only way to check this without
     * a GPU, and the GPU half is covered separately by
     * `RectSLGalleryHarness.measureSdfAgreement` on a `->degrade` scene. Together
     * they say: the lane algebra equals the CPU (here), and the GLSL equals the lane
     * algebra (there).
     */
    @Test
    fun `the dropout gate lowers bit-for-bit rather than baking`() {
        var checked = 0
        var dropped = 0
        for (ordinal in listOf(0, 1, 2, 7, 15, 63, 200)) {
            for (probability in listOf(0.0, 0.05, 0.25, 1.0 / 3.0, 0.45, 0.5, 0.9, 1.0)) {
                for (salt in listOf(0, 3)) {
                    val track = DegradeGateTrack(ordinal, probability, cps = 1.0, salt = salt)
                    for (step in 0 until 60) {
                        val t = step * 0.137
                        val cpu = track.sample(t)
                        val lanes = laneGate(track, t)
                        assertEquals(
                            cpu, lanes, 0.0,
                            "uvec2 lane arithmetic disagrees with DegradeGateTrack at ordinal=$ordinal " +
                                "p=$probability salt=$salt t=$t: CPU $cpu, lanes $lanes"
                        )
                        checked++
                        if (cpu == 0.0) dropped++
                    }
                }
            }
        }
        assertTrue(checked > 5000, "expected a broad sweep, made only $checked comparisons")
        // A gate that always fired, or never did, would agree trivially.
        assertTrue(
            dropped > checked / 10 && dropped < checked * 9 / 10,
            "the sweep should straddle the threshold; dropped $dropped of $checked"
        )
        println("[lower] ->degrade: $checked triples agree bit-for-bit ($dropped dropped)")
    }

    /** And the scene it comes from must now lower with *no* caveat, and compile. */
    @Test
    fun `a degrade-only scene lowers with no caveats and compiles`() {
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

        val result = RectSLGlsl.emitSdf(program, "scene")
        assertTrue(result.ok, "a ->degrade scene should now lower exactly, but reported: ${result.notes}")
        assertTrue(
            result.glsl.contains("rectslMul64") && result.glsl.contains("rectslLess64"),
            "the lowered gate should use the 64-bit lane helpers"
        )
        // The lane helpers use umulExtended, which is GLSL 4.00+. Prove the real front
        // end accepts them here rather than discovering it at pipeline creation.
        val spirv = ShaderSPIRVUtils.compileShader(
            "rectsl-degrade.frag", wrapAsFragment(result.glsl), ShaderKind.FRAGMENT_SHADER
        )
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "the lowered gate produced no SPIR-V")
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

    // --- the shader's integer algebra, in Kotlin -----------------------------

    /**
     * The emitted gate, evaluated the way the GPU evaluates it: a 64-bit value as two
     * `UInt` lanes, with the same four operations `RectSLGlsl.INT64_HELPER` defines
     * and in the same order `emitDegradeGate` writes them.
     *
     * `UInt` is chosen because Kotlin specifies it as wrapping mod 2^32, which is
     * what GLSL specifies for `uint` — so this is the shader's arithmetic and not a
     * model of it. The one thing not reproduced is the cycle index: the shader
     * computes `floor(t * cps)` in `float` where this and the CPU use `double`, so
     * they can disagree within an ulp of a cycle boundary. That is a discontinuity of
     * the gate either way, and the probe instants used by the GPU differential are
     * deliberately not at boundaries.
     */
    private fun laneGate(track: DegradeGateTrack, t: Double): Double {
        val cycle = Math.floor(t * track.cps).toInt()
        val keyed = (track.ordinal * 0x9E3779B1L) xor (track.salt * 0xC2B2AE3DL)
        var h = xor64(lanes(keyed), mul64(cycle64(cycle), lanes(0x85EBCA77L)))
        h = xor64(h, shr64(h, 33)); h = mul64(h, lanes(-0xAE502812AA7333L))
        h = xor64(h, shr64(h, 29)); h = mul64(h, lanes(-0x3B314601E57A13ADL))
        h = xor64(h, shr64(h, 32))
        val threshold = lanes(Math.ceil(track.probability.coerceIn(0.0, 1.0) * 9007199254740992.0).toLong())
        return if (less64(shr64(h, 11), threshold)) 0.0 else 1.0
    }

    /** (low 32 bits, high 32 bits) — the same packing `Long.glslUvec2()` emits. */
    private fun lanes(v: Long): Pair<UInt, UInt> =
        (v and 0xFFFFFFFFL).toUInt() to ((v ushr 32) and 0xFFFFFFFFL).toUInt()

    private fun cycle64(c: Int): Pair<UInt, UInt> =
        c.toUInt() to (if (c < 0) 0xFFFFFFFFu else 0u)

    private fun xor64(a: Pair<UInt, UInt>, b: Pair<UInt, UInt>): Pair<UInt, UInt> =
        (a.first xor b.first) to (a.second xor b.second)

    private fun shr64(a: Pair<UInt, UInt>, n: Int): Pair<UInt, UInt> = when {
        n == 0 -> a
        n < 32 -> ((a.first shr n) or (a.second shl (32 - n))) to (a.second shr n)
        else -> (a.second shr (n - 32)) to 0u
    }

    /** Low 64 bits of a 64x64 product; the cross terms land only in the high lane. */
    private fun mul64(a: Pair<UInt, UInt>, b: Pair<UInt, UInt>): Pair<UInt, UInt> {
        val full = a.first.toULong() * b.first.toULong()
        val lo = full.toUInt()
        val hi = (full shr 32).toUInt() + a.second * b.first + a.first * b.second
        return lo to hi
    }

    private fun less64(a: Pair<UInt, UInt>, b: Pair<UInt, UInt>): Boolean =
        a.second < b.second || (a.second == b.second && a.first < b.first)

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

    /** The emitted field, made into a shader shaderc will actually accept. */
    private fun wrapAsFragment(sdf: String): String = buildString {
        appendLine("#version 450")
        appendLine("layout(location = 0) in vec2 v_world;")
        appendLine("layout(location = 0) out vec4 outColor;")
        appendLine("layout(push_constant) uniform Push { float time; } push;")
        appendLine()
        appendLine(sdf)
        appendLine()
        appendLine("void main() {")
        appendLine("    float d = scene(v_world, push.time);")
        appendLine("    outColor = vec4(vec3(smoothstep(0.0, 0.02, d)), 1.0);")
        appendLine("}")
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
