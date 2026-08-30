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
 * What the bake costs turned out to depend on the track, though, and saying
 * "piecewise constant" about all of them was wrong the moment `0d875cc63`
 * (2026-08-22) put a `sine` on the wall. Three regimes, three currencies, one test
 * each:
 *
 * - a **rhythm** is piecewise constant, so the only question is *when* it steps.
 *   Since 2026-08-30 each edge is bisected rather than snapped to the sample grid,
 *   which took the displacement from 8.3ms to a microsecond; what is checked here is
 *   that the disagreement is confined to those edges and nowhere else.
 * - a **signal** has no plateaus, so there is nothing exact to check and the
 *   currency is value: how far the emitted ramp sits from the track, as a fraction of
 *   what the track does at all.
 * - **`rand`** is neither, and this is the test that says so out loud: baking it
 *   eight times finer does not help, because it hashes the rational instant it is
 *   handed and has no value between samples to converge on. An approximation that
 *   does not improve with resolution is not an approximation.
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
    fun `a rhythm bakes exactly except within a microsecond of each edge`() {
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
        report("pat", program, pats, RectSLSdfBakeFidelity.STEPPED)
    }

    /** The sample-and-hold reading, which holds a value across a rest. */
    @Test
    fun `a sample-and-hold rhythm bakes exactly except within a microsecond of each edge`() {
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
        report("hold-pat", program, pats, RectSLSdfBakeFidelity.STEPPED)
    }

    /**
     * A continuous signal is the case the old wording did not cover: there are no
     * plateaus, so "exact except at the edges" means nothing, and the bake is an
     * approximation in value from end to end. It is emitted as a ramp through the
     * samples rather than a staircase, which is what keeps it three orders inside the
     * bound this asserts — a hold chain of the same `sine` is off by 0.0086 of a
     * 0-to-1 range, and the ramp by 0.000024.
     */
    @Test
    fun `a signal bakes as a ramp within a thousandth of its own range`() {
        val program = compile(
            """
            #rectsl
            ->wall 8 8 abstract
            ->duration 4
            ->fps 30
            ->cps 1
            "r" rect  1 ->square  sine 6 * 3 -  ->x ;
            """
        )
        val pats = program.tracksOfKind<PatTrack>()
        assertTrue(pats.isNotEmpty(), "expected a PatTrack from the sine signal")
        val bakes = report("sine", program, pats, RectSLSdfBakeFidelity.SAMPLED)
        assertTrue(
            bakes.all { it.shape == RectSLSdfBakeShape.RAMP },
            "a smooth signal should be emitted as a ramp, got ${bakes.map { it.shape }}"
        )
    }

    /**
     * `rand` cannot be baked at any rate, and the emitter has to say so rather than
     * report a small-sounding sample interval.
     *
     * The evidence is that resolution does not help: `rand` hashes the *reduced
     * rational* instant it is handed, so 8x the samples is a different draw from the
     * same hash rather than a closer look at one signal. A test that only checked the
     * error would be checking a property of the grid; this checks that the error is
     * indifferent to it.
     */
    @Test
    fun `rand is reported as aliased because a finer bake does not help`() {
        val program = compile(
            """
            #rectsl
            ->wall 8 8 abstract
            ->duration 4
            ->fps 30
            ->cps 1
            "r" rect  1 ->square  3 rand 6 * 3 -  ->x ;
            """
        )
        val pats = program.tracksOfKind<PatTrack>()
        assertTrue(pats.isNotEmpty(), "expected a PatTrack from rand")
        val track = pats.first()

        val coarse = RectSLGlsl.bakeTrack(track, program.duration, program.fps)
        val fine = RectSLGlsl.bakeTrack(track, program.duration, program.fps * 8.0)
        println(
            "[bake] rand      %d samples -> worst %.4f, %d samples -> worst %.4f (range %.3f)"
                .format(coarse.sampleCount, coarse.worstError, fine.sampleCount, fine.worstError, coarse.valueRange)
        )
        assertEquals(
            RectSLSdfBakeFidelity.ALIASED, coarse.fidelity,
            "rand has no value between samples, so the bake must not claim to approximate it"
        )
        assertTrue(
            coarse.convergenceRatio < 1.5,
            "the aliasing verdict rests on the half-rate control, which came back at " +
                "${coarse.convergenceRatio}x"
        )
        assertTrue(
            fine.sampleCount > coarse.sampleCount * 4,
            "the control needs a genuinely finer bake, got ${fine.sampleCount} vs ${coarse.sampleCount}"
        )
        assertTrue(
            fine.worstError > coarse.worstError * 0.5,
            "8x the samples reduced the error from ${coarse.worstError} to ${fine.worstError}, so this " +
                "track is resolvable after all and should not be reported as aliased"
        )
    }

    /**
     * The emitter has to *say* it approximated, in both currencies: a sentence a
     * reader can act on, and the measurement it came from. This is the half that
     * keeps the story honest — if a future change lowered these symbolically the
     * notes would go away, and this test would demand the bake measurements go too.
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
        assertEquals(
            baked.notes.size, baked.bakes.size,
            "every caveat on a pattern scene should be a measured bake, was ${baked.notes}"
        )
        assertEquals(
            baked.bakes.map { it.note }, baked.notes,
            "the prose and the measurement must be the same list, not two lists"
        )
        assertTrue(
            baked.notes.any { it.contains("hold chain") && it.contains("edges placed to") },
            "the caveat should say what shape the bake took and how well placed it is, was ${baked.notes}"
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
     * Compare what the shader evaluates against the track's own sampler on a grid
     * 40x finer than the bake, and hold the result to what that *kind* of bake can
     * possibly cost — seconds for a rhythm, track units for a signal.
     */
    private fun report(
        label: String,
        program: SceneProgram,
        tracks: List<Track<Double>>,
        expected: RectSLSdfBakeFidelity
    ): List<RectSLGlsl.BakedTrack> {
        val duration = program.duration
        val bakes = tracks.map { RectSLGlsl.bakeTrack(it, duration, program.fps) }
        val probes = 40 * (bakes.first().sampleCount - 1)
        val probeStep = duration / probes

        var worstFraction = 0.0
        var worstValueError = 0.0
        var worstEdgeDistance = 0.0
        var worstRelativeError = 0.0
        var totalKnots = 0
        var mostDisagreements = 0
        var fewestEdges = Int.MAX_VALUE
        for ((index, track) in tracks.withIndex()) {
            val bake = bakes[index]
            assertEquals(
                expected, bake.fidelity,
                "$label: expected a $expected bake, the emitter classified it ${bake.fidelity}"
            )
            totalKnots += bake.knots.size
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
                    if (bake.valueRange > 0.0) {
                        worstRelativeError = maxOf(worstRelativeError, err / bake.valueRange)
                    }
                }
            }
            worstFraction = maxOf(worstFraction, disagreements.toDouble() / (probes + 1))
            mostDisagreements = maxOf(mostDisagreements, disagreements)
            fewestEdges = minOf(fewestEdges, edges.size)
            // Every disagreement must sit next to a real edge: that is the difference
            // between "the bake displaced an edge" and "the bake invented a value".
            for (t in mismatched) {
                val nearest = edges.minOfOrNull { abs(it - t) } ?: Double.MAX_VALUE
                worstEdgeDistance = maxOf(worstEdgeDistance, nearest)
            }
        }

        println(
            ("[bake] %-9s %d track(s), %d knot(s) total, %s, bake interval %.5fs: " +
                "disagrees on %.4f%% of the timeline, worst error %.3e (%.4f%% of range), " +
                "every disagreement within %.6fs of a real edge")
                .format(
                    label, tracks.size, totalKnots, bakes.first().shape, bakes.first().sampleInterval,
                    worstFraction * 100.0, worstValueError, worstRelativeError * 100.0, worstEdgeDistance
                )
        )

        when (expected) {
            RectSLSdfBakeFidelity.STEPPED -> {
                // The steps are bisected onto the track's own edges now, so the only
                // instants that can disagree are the sub-microsecond slivers beside
                // them: at most one probe per edge, and each one adjacent to an edge.
                assertTrue(
                    mostDisagreements <= fewestEdges,
                    "$label: $mostDisagreements probe(s) disagree but the track only has $fewestEdges " +
                        "edge(s) — that is more than displaced edges can explain"
                )
                assertTrue(
                    worstEdgeDistance <= probeStep,
                    "$label: a disagreement sat ${worstEdgeDistance}s from any real edge (probe step " +
                        "${probeStep}s) — the bake is wrong in the middle of a plateau, not at an edge"
                )
                assertTrue(
                    bakes.all { it.edgeTolerance <= RectSLGlsl.BAKE_EDGE_TOLERANCE },
                    "$label: the edges were left on the sample grid, not bisected onto the track's"
                )
            }
            RectSLSdfBakeFidelity.SAMPLED -> assertTrue(
                worstRelativeError < 1e-3,
                "$label: the emitted signal is ${worstRelativeError * 100}% of its own range away from " +
                    "the track, which is more than interpolating between samples should cost"
            )
            RectSLSdfBakeFidelity.ALIASED -> Unit
        }
        return bakes
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

    /**
     * Every distinct track anywhere in this program's channel DAG.
     *
     * The `when (node)` deliberately has no `else`: [Node] is sealed, and a third
     * kind that carried tracks would otherwise be walked past in silence, quietly
     * shrinking what this test claims to cover. Let it fail to compile instead.
     */
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
