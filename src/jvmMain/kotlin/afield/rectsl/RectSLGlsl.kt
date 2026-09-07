package afield.rectsl

import kotlin.math.abs

/**
 * RectSL scenes as GLSL signed distance fields.
 *
 * # Why this is a small file
 *
 * RectSL looks like a big language to port, and almost none of it needs
 * porting. The stack VM is a *build-time* machine: `array`, `grid`, `radial`,
 * every selection word, `each`/`filter`, the chart and plotting layers, the
 * `{{ }}` Kotlin escapes — all of it runs once and is gone, leaving a
 * [SceneProgram]: a flat list of nodes whose channels are [Track]s. Only that
 * residue has to reach the GPU, and it is eleven track shapes and a 2D
 * transform.
 *
 * # What the SDF is, and is not
 *
 * This emits shape, not picture. A RectSL scene is painter's-algorithm 2D —
 * z-sorted rects composited with per-rect alpha, fill, and material. An SDF is
 * a set, and `min` is union, which has no notion of layering. So the emitted
 * `float` field answers "how far is this point from the nearest visible
 * rectangle" and deliberately drops fill, material, and layer order. What that
 * buys is everything a distance field is good at and a rasterized quad is not:
 * outlines and glows at any width, `smin` blending *between* rectangles,
 * boolean ops, per-pixel normals, and sub-frame motion blur — because time is a
 * uniform and every track is closed-form, so a fragment can sample the scene at
 * as many instants as it likes.
 *
 * Visibility is not dropped. A rect whose alpha has been gated to zero by
 * `->appear` / `->vanish` / `->live` must not contribute a silhouette, so each
 * rect's alpha track is evaluated and tested before its distance is unioned in.
 *
 * None of that list is aspirational, and it is more useful to say where the
 * evidence is than to restate the claim. [RectSLSdfLook] is that vocabulary as
 * data with one preset per capability, [RectSLSdfShader] assembles a whole
 * fragment shader from it, and `RectSLGalleryHarness.renderSdf` renders each
 * preset — plus a side-by-side against the raster path — into
 * `public/docs/assets/rectsl/rectsl-sdf-*.png`. Two of them need the emitter's
 * cooperation rather than the look layer's, because they change the *set*: see
 * [RectSLSdfOptions]. To get it on screen and moving, replay
 * `dev_docs/agents/transcripts/rectsl_sdf_live.yaml`.
 *
 * # Fidelity
 *
 * Tracks are emitted symbolically, never sampled into tables: keyed tracks
 * become branch chains, TCB tracks become cubic Hermite spans with their
 * tangents precomputed on the CPU ([TcbTrack.hermiteSegments]), and the scalar
 * ops carry their own GLSL ([TrackOp1], [TrackOp2], [TimeWarpKind]). The
 * guards are reproduced rather than approximated — RectSL's divide-by-zero
 * returns 0, and its `mod` truncates where GLSL's floors.
 *
 * The one unavoidable difference is precision: the CPU samples in `double` and
 * a shader runs in `float`. Expect agreement to about 1e-6 of a wall unit, not
 * to the ulp — which is now measured rather than assumed:
 * `RectSLGalleryHarness.measureSdfAgreement` reads the *compiled shader's* own
 * answers back out of a framebuffer and compares them against [SceneProgram.sample]
 * across eleven programs, and the worst disagreement is 2.5e-06 wall units.
 *
 * What *is* approximated is now two tracks, not three. `->degrade`'s 64-bit
 * integer hash reads like something a shader cannot do and is not: it lowers
 * **exactly**, in `uvec2` lanes, with the threshold comparison done in integers so
 * no float enters the decision (see `emitDegradeGate` and [INT64_HELPER]). The two
 * that remain baked are the pattern queries, which are rational arithmetic over a
 * mini-notation tree — an interpreter, not a formula, and a different kind of
 * problem. There is nothing to lower symbolically even in principle: a
 * `PatPattern` is a `(PatArc) -> List<PatEvent>` lambda, so the emitter cannot tell
 * a `"bd*4"` from a `sine` from the outside, let alone find a closed form for it.
 * They go through [bakeTrack], and what that costs depends on the track:
 *
 * - a **rhythm** is piecewise constant, so the bake is exact except for where its
 *   edges are, and each edge is bisected to [BAKE_EDGE_TOLERANCE] — a microsecond,
 *   the same order as the millionth of a cycle [PatTrack] quantises its own queries
 *   to, which is the resolution the edge exists at in the first place;
 * - a **signal** — `sine`, `tri`, `perlin` — has no plateaus and no exactness to
 *   claim, so both a hold chain and a ramp through the samples are built and the one
 *   that measures closer wins. That is the ramp by 100x to 3000x, except on `saw`
 *   and `isaw`, whose once-a-cycle wrap a straight line would smear;
 * - `rand` is neither. It hashes the reduced rational instant it is handed, so it
 *   has no value *between* samples: a finer bake is a different signal, not a better
 *   approximation of this one. The emitter detects that by baking it twice — halving
 *   the rate leaves the error where it was — says so, and stops claiming otherwise.
 *
 * Which of the three, and what it measured, is [RectSLSdfResult.bakes]; the same
 * thing as a sentence is [RectSLSdfResult.notes]. `RectSLSdfBakeTest` measures each
 * regime on the timeline, and `RectSLGlslSdfTest` holds the whole gallery to the
 * bound its own classification implies.
 */
object RectSLGlsl {

    /**
     * Shared GLSL every emitted scene needs: an exact box field, and the
     * inverse-transform that carries a world point into a rect's local frame.
     */
    val PRELUDE: String = """
// --- RectSL SDF prelude -------------------------------------------------
// Exact signed distance to an axis-aligned box of half-extent b, centred at
// the origin: positive outside, negative inside, gradient of unit length
// everywhere outside.
float rectslBox(vec2 p, vec2 b) {
    vec2 d = abs(p) - b;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

// A rect in world space: undo the rotation about its centre, then measure.
float rectslRect(vec2 p, vec2 centre, vec2 halfExtent, float rot) {
    vec2 q = p - centre;
    float c = cos(rot);
    float s = sin(rot);
    return rectslBox(vec2(c * q.x + s * q.y, -s * q.x + c * q.y), halfExtent);
}
""".trimIndent()

    /**
     * The Penner family as GLSL, transcribed one-for-one from
     * `afield.stdlib.Functions.shapeAlpha` — same constants, same branch
     * structure, same magic numbers. `RectSLGlslEasingTest` holds this honest by
     * checking every name RectSL can produce has a body here.
     *
     * The exact-endpoint special cases in the Kotlin (`if (t == 0.0) 0.0`) are
     * kept: they matter for the expo and elastic curves, where the closed form
     * does not reach its endpoints on its own.
     */
    private val EASING_GLSL: Map<String, String> = buildMap {
        put("linear", "return t;")
        put("quad-in", "return t * t;")
        put("quad-out", "return 1.0 - pow(1.0 - t, 2.0);")
        put("quad-in-out", "return t < 0.5 ? 0.5 * pow(2.0 * t, 2.0) : 1.0 - 0.5 * pow(2.0 - 2.0 * t, 2.0);")
        put("cubic-in", "return pow(t, 3.0);")
        put("cubic-out", "return 1.0 - pow(1.0 - t, 3.0);")
        put("cubic-in-out", "return t < 0.5 ? 0.5 * pow(2.0 * t, 3.0) : 1.0 - 0.5 * pow(2.0 - 2.0 * t, 3.0);")
        put("quart-in", "return pow(t, 4.0);")
        put("quart-out", "return 1.0 - pow(1.0 - t, 4.0);")
        put("quart-in-out", "return t < 0.5 ? 0.5 * pow(2.0 * t, 4.0) : 1.0 - 0.5 * pow(2.0 - 2.0 * t, 4.0);")
        put("quint-in", "return pow(t, 5.0);")
        put("quint-out", "return 1.0 - pow(1.0 - t, 5.0);")
        put("quint-in-out", "return t < 0.5 ? 0.5 * pow(2.0 * t, 5.0) : 1.0 - 0.5 * pow(2.0 - 2.0 * t, 5.0);")
        put("sine-in", "return 1.0 - cos(t * 1.5707963267948966);")
        put("sine-out", "return sin(t * 1.5707963267948966);")
        put("sine-in-out", "return -(cos(3.141592653589793 * t) - 1.0) * 0.5;")
        put("expo-in", "return t == 0.0 ? 0.0 : pow(2.0, 10.0 * t - 10.0);")
        put("expo-out", "return t == 1.0 ? 1.0 : 1.0 - pow(2.0, -10.0 * t);")
        put(
            "expo-in-out",
            "if (t == 0.0) return 0.0;\n" +
                "    if (t == 1.0) return 1.0;\n" +
                "    return t < 0.5 ? pow(2.0, 20.0 * t - 10.0) / 2.0 : (2.0 - pow(2.0, -20.0 * t + 10.0)) / 2.0;"
        )
        put("circ-in", "return 1.0 - sqrt(max(1.0 - t * t, 0.0));")
        put("circ-out", "return sqrt(max(1.0 - (t - 1.0) * (t - 1.0), 0.0));")
        put(
            "circ-in-out",
            "return t < 0.5\n" +
                "        ? (1.0 - sqrt(max(1.0 - (2.0 * t) * (2.0 * t), 0.0))) / 2.0\n" +
                "        : (sqrt(max(1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0), 0.0)) + 1.0) / 2.0;"
        )
        put("back-in", "return 2.70158 * t * t * t - 1.70158 * t * t;")
        put("back-out", "return 1.0 + 2.70158 * pow(t - 1.0, 3.0) + 1.70158 * pow(t - 1.0, 2.0);")
        put(
            "back-in-out",
            "return t < 0.5\n" +
                "        ? ((2.0 * t) * (2.0 * t) * ((2.5949095 + 1.0) * 2.0 * t - 2.5949095)) / 2.0\n" +
                "        : ((2.0 * t - 2.0) * (2.0 * t - 2.0) * ((2.5949095 + 1.0) * (t * 2.0 - 2.0) + 2.5949095) + 2.0) / 2.0;"
        )
        put(
            "elastic-in",
            "if (t == 0.0) return 0.0;\n" +
                "    if (t == 1.0) return 1.0;\n" +
                "    return -pow(2.0, 10.0 * t - 10.0) * sin((t * 10.0 - 10.75) * 2.0943951023931953);"
        )
        put(
            "elastic-out",
            "if (t == 0.0) return 0.0;\n" +
                "    if (t == 1.0) return 1.0;\n" +
                "    return pow(2.0, -10.0 * t) * sin((t * 10.0 - 0.75) * 2.0943951023931953) + 1.0;"
        )
        put(
            "elastic-in-out",
            "if (t == 0.0) return 0.0;\n" +
                "    if (t == 1.0) return 1.0;\n" +
                "    return t < 0.5\n" +
                "        ? -(pow(2.0, 20.0 * t - 10.0) * sin((20.0 * t - 11.125) * 1.3962634015954636)) / 2.0\n" +
                "        : (pow(2.0, -20.0 * t + 10.0) * sin((20.0 * t - 11.125) * 1.3962634015954636)) / 2.0 + 1.0;"
        )
        put("bounce-in", "return 1.0 - rectslBounceOut(1.0 - t);")
        put("bounce-out", "return rectslBounceOut(t);")
        put(
            "bounce-in-out",
            "return t < 0.5\n" +
                "        ? (1.0 - rectslBounceOut(1.0 - 2.0 * t)) / 2.0\n" +
                "        : (1.0 + rectslBounceOut(2.0 * t - 1.0)) / 2.0;"
        )
        put("smoothstep", "return t * t * (3.0 - 2.0 * t);")
        put("smootherstep", "return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);")
        // The four shorthand words that predate the Penner family.
        put("ease-in", "return t * t;")
        put("ease-out", "return 1.0 - (1.0 - t) * (1.0 - t);")
        put("ease-in-out", "return t < 0.5 ? 2.0 * t * t : 1.0 - pow(-2.0 * t + 2.0, 2.0) * 0.5;")
        put("ease-smoothstep", "return t * t * (3.0 - 2.0 * t);")
        put("smooth", "return t * t * (3.0 - 2.0 * t);")
    }

    private val BOUNCE_HELPER: String = """
float rectslBounceOut(float t) {
    const float n = 7.5625;
    const float d = 2.75;
    if (t < 1.0 / d) return n * t * t;
    if (t < 2.0 / d) { float u = t - 1.5 / d; return n * u * u + 0.75; }
    if (t < 2.5 / d) { float u = t - 2.25 / d; return n * u * u + 0.9375; }
    float u = t - 2.625 / d;
    return n * u * u + 0.984375;
}
""".trimIndent()

    /** Every easing name this emitter can spell — the coverage guard's oracle. */
    val supportedEasings: Set<String> get() = EASING_GLSL.keys

    /**
     * 64-bit integer arithmetic in a shader that has none.
     *
     * GLSL 450 gets `uint64_t` only with `GL_ARB_gpu_shader_int64`, which needs the
     * `shaderInt64` device feature, so relying on it would make an emitted scene
     * refuse to run on hardware that draws every other scene fine. A 64-bit value
     * is therefore a `uvec2`: `x` the low 32 bits, `y` the high 32. Exactly the four
     * operations [DegradeGateTrack]'s hash performs are implemented, and no more.
     *
     * The multiply is the only interesting one. `umulExtended` (GLSL 4.00) returns
     * the exact 32x32 -> 64 product, and the two cross terms contribute only to the
     * high lane, so the low 64 bits of a 64x64 product are exact — GLSL specifies
     * `uint` arithmetic as wrapping mod 2^32, which is the same wrap Kotlin's `Long`
     * multiply performs on each lane. Nothing here is an approximation of the CPU;
     * it is the same arithmetic written out by hand.
     */
    private val INT64_HELPER: String = """
uvec2 rectslShr64(uvec2 a, int n) {
    if (n == 0) return a;
    if (n < 32) return uvec2((a.x >> uint(n)) | (a.y << uint(32 - n)), a.y >> uint(n));
    return uvec2(a.y >> uint(n - 32), 0u);
}

uvec2 rectslMul64(uvec2 a, uvec2 b) {
    uint hi;
    uint lo;
    umulExtended(a.x, b.x, hi, lo);
    return uvec2(lo, hi + a.y * b.x + a.x * b.y);
}

bool rectslLess64(uvec2 a, uvec2 b) {
    return (a.y < b.y) || (a.y == b.y && a.x < b.x);
}

// A cycle index as a two's-complement 64-bit value, sign-extended like Kotlin's
// Int-to-Long widening in `cycleOf(t, cps).toLong()`.
uvec2 rectslCycle64(int c) {
    return uvec2(uint(c), c < 0 ? 0xFFFFFFFFu : 0u);
}
""".trimIndent()

    /** The smooth minimum the emitter reaches for when [RectSLSdfOptions.blendRadius] is set. */
    private val SMIN_HELPER: String = """
// Polynomial smooth minimum (Quilez). At k = 0 this is exactly min(); above it
// the two fields fuse over a band of width k instead of creasing.
float rectslSmin(float a, float b, float k) {
    if (k <= 0.0) return min(a, b);
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}
""".trimIndent()

    /**
     * Lower a program to a `float <name>(vec2 p, float t)` distance field plus
     * everything it depends on. The result is self-contained GLSL: no uniforms,
     * no buffers, no samplers, so it can be pasted into any shader that has a
     * world-space point and a time.
     *
     * [options] covers the two things a *consumer* of the field cannot do for
     * itself, because they happen inside `scene`: how the rects are combined
     * ([RectSLSdfOptions.blendRadius]), and what the emitted symbols are called
     * ([RectSLSdfOptions.namePrefix]) so that two scenes can live in one shader.
     */
    @JvmOverloads
    fun emitSdf(
        program: SceneProgram,
        functionName: String = "rectslScene",
        options: RectSLSdfOptions = RectSLSdfOptions()
    ): RectSLSdfResult {
        val emitter = Emitter(program, options)
        val body = emitter.emitSceneFunction(functionName)
        // Globals are the functions whose bodies depend on nothing in this
        // program — the box field, the easing curves, the smooth minimum. They
        // keep their unprefixed names so that two scenes emitted into one shader
        // share them by definition rather than by luck; the assembler merges the
        // maps and writes each one once. See [RectSLSdfResult.globals].
        val globals = LinkedHashMap<String, String>()
        globals["rectslPrelude"] = PRELUDE
        if (emitter.usesBounce) globals["rectslBounceOut"] = BOUNCE_HELPER
        if (emitter.usesInt64) globals["rectslInt64"] = INT64_HELPER
        if (options.blendRadius > 0.0) globals["rectslSmin"] = SMIN_HELPER
        emitter.easingFunctions().forEach { (name, text) -> globals[name] = text }

        val scene = buildString {
            // Tracks form a DAG and are named on first reach, so a shared child
            // can end up defined after the parent that needs it. Prototypes make
            // definition order irrelevant instead of demanding a topological sort.
            val prototypes = emitter.trackFunctionNames()
            if (prototypes.isNotEmpty()) {
                appendLine("// forward declarations: the track graph is a DAG, not a tree")
                prototypes.forEach { appendLine("float $it(float t);") }
                appendLine()
            }
            emitter.trackFunctions().forEach { appendLine(it); appendLine() }
            append(body)
        }
        val glsl = buildString {
            globals.values.forEach { appendLine(it); appendLine() }
            append(scene)
        }
        return RectSLSdfResult(
            glsl = glsl,
            globals = globals,
            sceneGlsl = scene,
            sceneFunctionName = functionName,
            rectCount = emitter.rectCount,
            trackFunctionCount = emitter.trackFunctionCount,
            notes = emitter.notes,
            bakes = emitter.bakes
        )
    }

    /** How many shapes one union helper folds together. See `unionTree`. */
    private const val UNION_FANIN = 32

    /** Ceiling on samples taken when baking a track that cannot be lowered. */
    private const val MAX_BAKE_SAMPLES = 4096

    /** How much finer than its own bake grid [bakeTrack] measures the bake it produced. */
    private const val BAKE_PROBE_REFINEMENT = 4

    /**
     * How closely a stepped bake places its edges, in seconds.
     *
     * Nothing about a pattern edge requires it to land on a sample instant, and until
     * 2026-08-30 nothing looked for it off the grid either: the edge moved to the
     * nearest sample, up to 8.3ms out. Bisecting the interval it was found in costs
     * about thirteen more queries per edge and ends four orders of magnitude closer,
     * so the sample rate is no longer what limits *when* a step happens — only how
     * short an event can be before the bake steps over it entirely.
     *
     * There is little point going finer. [PatTrack.sample] quantises its own query to
     * a millionth of a cycle, so at one cycle per second this already sits on the grid
     * the edge itself sits on; faster than that and the sampler's own tread is the
     * limit rather than this constant.
     */
    internal const val BAKE_EDGE_TOLERANCE: Double = 1e-6

    /** Above this many edges, bisecting every one of them costs more than it buys. */
    private const val MAX_REFINED_EDGES = 2048

    /**
     * How much worse a half-rate bake has to be before sampling counts as converging
     * on the track at all.
     *
     * Halving the rate roughly doubles a hold chain's error and quadruples a ramp's,
     * so 1.5x is a wide margin for the real signals. What it separates them from is
     * `rand`, which hashes the reduced rational instant it is handed: half the samples
     * is a different draw from the same hash rather than a coarser look at one signal,
     * so the error does not move. An approximation that does not improve with
     * resolution is not an approximation, and that is the whole test.
     */
    private const val BAKE_CONVERGENCE_FACTOR = 1.5

    /**
     * How far off a bake has to be, as a fraction of what the track does at all,
     * before failing to converge is worth reporting as aliasing.
     *
     * The convergence test alone is not enough, because it catches something
     * harmless: a *piecewise linear* signal — `tri`, `saw` — is reproduced exactly by
     * a ramp everywhere except at its kinks, so halving the rate barely moves the
     * error. That is a bake at 0.0002% of the track's range, and calling it
     * "unresolved" would be alarming and wrong. Aliasing is the conjunction: the
     * deviation matters *and* resolution does not help it.
     */
    private const val BAKE_ALIAS_MATERIAL = 0.01

    /**
     * The sampled track the shader actually evaluates, and what that cost.
     *
     * Held separately from the emitted text so the loss can be *measured* rather than
     * described: `RectSLSdfBakeTest` evaluates [valueAt] against the track's own
     * `sample` on a grid far finer than [sampleCount], and [worstError] is the
     * emitter's own version of that check, taken at emit time.
     */
    internal data class BakedTrack(
        val fidelity: RectSLSdfBakeFidelity,
        val shape: RectSLSdfBakeShape,
        /** `HOLD`: the instant each step starts. `RAMP`: the uniform sample grid. */
        val knots: List<Pair<Double, Double>>,
        val sampleCount: Int,
        val duration: Double,
        /** How far an emitted edge can sit from the track's own, in seconds. */
        val edgeTolerance: Double,
        /** Worst |emitted - track| found on a grid [BAKE_PROBE_REFINEMENT]x finer than the bake. */
        val worstError: Double,
        /** The track's own spread over `[0, duration]`, so [worstError] can be read as a fraction. */
        val valueRange: Double,
        /** The same measurement at half the sample rate, or `NaN` for a stepped bake. */
        val halfRateError: Double = Double.NaN
    ) {
        /** Gap between bake samples. */
        val sampleInterval: Double get() = duration / (sampleCount - 1).coerceAtLeast(1)

        val sampleRate: Double get() = 1.0 / sampleInterval

        /** See [RectSLSdfBake.relativeError]. */
        val relativeError: Double get() = if (valueRange > 0.0) worstError / valueRange else worstError

        /** See [RectSLSdfBake.convergenceRatio]. */
        val convergenceRatio: Double
            get() = when {
                !halfRateError.isFinite() -> Double.NaN
                worstError > 0.0 -> halfRateError / worstError
                else -> Double.POSITIVE_INFINITY
            }

        /** What the emitted GLSL returns. */
        fun valueAt(t: Double): Double = when (shape) {
            RectSLSdfBakeShape.HOLD -> {
                var v = knots.first().second
                for ((time, value) in knots) if (t >= time) v = value else break
                v
            }
            RectSLSdfBakeShape.RAMP -> {
                val u = (t / sampleInterval).coerceIn(0.0, (knots.size - 1).toDouble())
                val i = minOf(u.toInt(), knots.size - 2)
                val f = u - i
                knots[i].second * (1.0 - f) + knots[i + 1].second * f
            }
        }
    }

    /**
     * Sample a track that cannot be lowered symbolically and re-express it as the
     * closest hold chain or ramp its own values admit.
     *
     * Three things can come back, and which one it is depends on the *track* rather
     * than on the bake — which is the distinction the emitter used to blur:
     *
     * - **stepped.** The track is piecewise constant, so the only question is where
     *   the edges are. They are found by bisection to [BAKE_EDGE_TOLERANCE], the
     *   resolution the sampler itself works at, so the bake is exact everywhere except
     *   inside those microsecond slivers — which no frame or probe instant lands in.
     *   A rhythm is this.
     * - **sampled.** The track is a signal, so there is no exactness to claim: what is
     *   emitted approximates it between samples, and the error is measured rather than
     *   bounded by hand. Both a hold chain and a ramp through the samples are built and
     *   the one that measures better wins — the ramp is 100x-3000x closer on `sine`,
     *   `tri` and `perlin`, and *worse* on `saw` and `isaw`, whose once-a-cycle wrap a
     *   straight line would smear across a whole interval.
     * - **aliased.** The deviation is material — past [BAKE_ALIAS_MATERIAL] of what the
     *   track does at all — *and* halving the sample rate does not make it worse,
     *   which means refining the rate would not make it better: sampling is not
     *   converging on anything. `rand` is the case, and it is reported rather than
     *   fixed, because no rate fixes it.
     */
    internal fun bakeTrack(track: Track<Double>, programDuration: Double, fps: Double): BakedTrack {
        val duration = if (programDuration > 0.0) programDuration else 1.0
        val rate = maxOf(fps * 4.0, 120.0)
        val count = minOf((duration * rate).toInt() + 1, MAX_BAKE_SAMPLES)

        // One truth grid, finer than the bake, shared by every candidate measured
        // below — including the half-rate control, which has to be judged against the
        // same instants or the comparison is between two different questions.
        val probes = BAKE_PROBE_REFINEMENT * (count - 1).coerceAtLeast(1)
        val truth = DoubleArray(probes + 1) { track.sample(duration * it / probes) }
        val range = (truth.max() - truth.min()).coerceAtLeast(0.0)

        val full = bakeAtRate(track, duration, count, truth, range)
        // A stepped bake is exact, so there is nothing for the control to tell us.
        if (full.fidelity == RectSLSdfBakeFidelity.STEPPED) return full
        val coarse = bakeAtRate(track, duration, (count + 1) / 2, truth, range)
        val converging = coarse.worstError >= full.worstError * BAKE_CONVERGENCE_FACTOR
        val material = full.relativeError > BAKE_ALIAS_MATERIAL
        return full.copy(
            fidelity = when {
                converging || !material -> RectSLSdfBakeFidelity.SAMPLED
                else -> RectSLSdfBakeFidelity.ALIASED
            },
            halfRateError = coarse.worstError
        )
    }

    /**
     * One bake at one rate, measured against [truth] — the grid the *caller* owns, so
     * that two rates can be compared.
     */
    private fun bakeAtRate(
        track: Track<Double>,
        duration: Double,
        count: Int,
        truth: DoubleArray,
        range: Double
    ): BakedTrack {
        val last = (count - 1).coerceAtLeast(1)
        fun instant(i: Int): Double = duration * i / last
        val samples = DoubleArray(count) { track.sample(instant(it)) }

        // Run-length compression, by index: where each run of equal samples starts.
        val runStarts = mutableListOf<Int>()
        for (i in 0 until count) if (i == 0 || samples[i] != samples[i - 1]) runStarts += i

        // Plateaus at all mean the track is stepped and a hold chain is its shape.
        val stepped = runStarts.size * 2 <= count
        val refine = stepped && runStarts.size <= MAX_REFINED_EDGES
        val holdKnots = if (refine) {
            runStarts.map { start ->
                val time = when (start) {
                    0 -> 0.0
                    else -> bisectEdge(track, instant(start - 1), instant(start), samples[start - 1])
                }
                time to samples[start]
            }
        } else {
            runStarts.map { instant(it) to samples[it] }
        }

        val probes = truth.size - 1
        fun measured(shape: RectSLSdfBakeShape, knots: List<Pair<Double, Double>>): BakedTrack {
            val bake = BakedTrack(
                fidelity = if (stepped) RectSLSdfBakeFidelity.STEPPED else RectSLSdfBakeFidelity.SAMPLED,
                shape = shape,
                knots = knots,
                sampleCount = count,
                duration = duration,
                edgeTolerance = if (shape == RectSLSdfBakeShape.HOLD && refine) {
                    BAKE_EDGE_TOLERANCE
                } else {
                    duration / last
                },
                worstError = 0.0,
                valueRange = range
            )
            var worst = 0.0
            for (i in 0..probes) worst = maxOf(worst, abs(bake.valueAt(duration * i / probes) - truth[i]))
            return bake.copy(worstError = worst)
        }

        val hold = measured(RectSLSdfBakeShape.HOLD, holdKnots)
        // A stepped track is already exact between its edges; there is nothing for a
        // ramp to win, and it would smear every edge across an interval.
        if (stepped) return hold
        val ramp = measured(RectSLSdfBakeShape.RAMP, (0 until count).map { instant(it) to samples[it] })
        return if (ramp.worstError < hold.worstError) ramp else hold
    }

    /**
     * The first instant in `(lo, hi]` at which the track has stopped returning
     * [heldValue], to within [BAKE_EDGE_TOLERANCE].
     *
     * Which side of the edge this lands on is not a rounding detail, and getting it
     * wrong costs a whole probe rather than a microsecond. [PatTrack.sample] quantises
     * its query to a millionth of a cycle, so the sampler's own edge sits at the first
     * microcycle *at or after* the pattern's exact rational onset — `"1 ~ 3"` steps at
     * 0.666667s, not at two thirds. Frame and probe instants, meanwhile, land on the
     * exact rational. Converging from below would therefore put the emitted step in
     * the sliver between the two and make every such frame show the next value early;
     * converging from above puts it where the sampler has it, which is the thing the
     * shader is supposed to agree with.
     *
     * The predicate is monotone across the interval for a stepped track, which is the
     * only kind this is called for. Where it is not — two changes inside one bake
     * interval — this finds the first of them, which is no worse than the grid instant
     * it replaces.
     */
    private fun bisectEdge(track: Track<Double>, lo: Double, hi: Double, heldValue: Double): Double {
        var low = lo
        var high = hi
        while (high - low > BAKE_EDGE_TOLERANCE) {
            val mid = 0.5 * (low + high)
            if (track.sample(mid) == heldValue) low = mid else high = mid
        }
        return high
    }

    private class Emitter(val program: SceneProgram, val options: RectSLSdfOptions = RectSLSdfOptions()) {
        private val prefix = options.namePrefix

        private val nodeMap: Map<String, Node> = program.nodes.associateBy { it.id }
        private val parentOf: Map<String, String> = buildMap {
            program.nodes.filterIsInstance<GroupNode>().forEach { group ->
                group.children.forEach { child -> put(child, group.id) }
            }
        }

        private val trackNames = LinkedHashMap<Track<Double>, String>()
        private val trackBodies = LinkedHashMap<String, String>()
        private val easings = LinkedHashSet<String>()
        val notes = mutableListOf<String>()

        /** Every bake this emission made, with what it measured. See [emitBaked]. */
        val bakes = mutableListOf<RectSLSdfBake>()
        var usesBounce = false
            private set

        /** True once a `->degrade` gate has been lowered and needs the uvec2 lane helpers. */
        var usesInt64 = false
            private set
        var rectCount = 0
            private set

        val trackFunctionCount: Int get() = trackBodies.size

        fun trackFunctions(): List<String> = trackBodies.values.toList()

        fun trackFunctionNames(): List<String> = trackBodies.keys.toList()

        /** Easing name -> (function name, body). Global: the curve does not depend on the scene. */
        fun easingFunctions(): List<Pair<String, String>> = easings.map { name ->
            easingFunctionName(name) to
                "float ${easingFunctionName(name)}(float t) {\n    ${EASING_GLSL.getValue(name)}\n}"
        }

        /**
         * Each rect gets its own function and the scene just unions them.
         * Emitting one giant function instead overflows glslang's parser stack
         * on the larger gallery scenes — `array-mandala` alone is 183 rects.
         */
        fun emitSceneFunction(functionName: String): String {
            val rects = program.nodes.filterIsInstance<RectNode>()
            val perRect = rects.mapIndexed { index, rect ->
                rectCount++
                "${prefix}Shape$index" to emitRect(rect, index)
            }
            if (rects.isEmpty()) notes += "scene has no rects; the field is empty everywhere"
            val out = StringBuilder()
            perRect.forEach { (_, body) ->
                out.appendLine(body)
                out.appendLine()
            }
            val top = unionTree(perRect.map { it.first }, out)
            val how = if (options.blendRadius > 0.0) {
                "smooth-unioned over ${options.blendRadius} wall unit(s)"
            } else {
                "unioned"
            }
            out.appendLine("// $rectCount rect(s), $how. Positive outside, negative inside.")
            out.appendLine("float $functionName(vec2 p, float t) {")
            out.appendLine("    return ${top?.let { "$it(p, t)" } ?: "1e9"};")
            out.append("}")
            return out.toString()
        }

        /**
         * Union the shapes through a tree of fan-in-[UNION_FANIN] helpers rather
         * than one flat chain.
         *
         * A flat union is what you would write by hand, and the in-process shader
         * compiler runs out of stack somewhere past ~120 calls in a single
         * function — measured, not guessed: 120 rects of `array-mandala` compile
         * and 150 do not. Chunking keeps every function small no matter how many
         * copies an `array` or `sunflower` produced.
         */
        private fun unionTree(names: List<String>, out: StringBuilder): String? {
            if (names.isEmpty()) return null
            if (names.size == 1) return names.single()
            var level = names
            var group = 0
            while (level.size > 1) {
                val next = mutableListOf<String>()
                for (chunk in level.chunked(UNION_FANIN)) {
                    if (chunk.size == 1) {
                        next += chunk.single()
                        continue
                    }
                    val name = "${prefix}Union${group++}"
                    val k = options.blendRadius
                    out.appendLine("float $name(vec2 p, float t) {")
                    out.appendLine("    float d = ${chunk.first()}(p, t);")
                    chunk.drop(1).forEach {
                        // A rect gated invisible returns the 1e9 sentinel, which a
                        // smooth minimum would drag the whole neighbourhood towards.
                        // Only blend where both operands are real distances.
                        if (k > 0.0) {
                            out.appendLine("    { float e = $it(p, t);")
                            out.appendLine(
                                "      d = (d > 1e8 || e > 1e8) ? min(d, e) : rectslSmin(d, e, ${k.glslFloat()}); }"
                            )
                        } else {
                            out.appendLine("    d = min(d, $it(p, t));")
                        }
                    }
                    out.appendLine("    return d;")
                    out.appendLine("}")
                    out.appendLine()
                    next += name
                }
                level = next
            }
            return level.single()
        }

        /**
         * One rect's world transform and box, with statically-identity work left
         * out.
         *
         * The composition is the CPU's, outward through the ancestor groups
         * exactly as [Transform2d.then] does it: a child's translation is scaled
         * and rotated by its parent before being added, rotations and alphas
         * accumulate, scales multiply.
         *
         * Most rects in a real scene never rotate, never scale, and never fade,
         * and most are not in a group — so writing the general composition for
         * every one of them buries the interesting scenes in dead arithmetic. On
         * `array-mandala` (183 rects) the unsimplified form is large enough that
         * the in-process shader compiler gives up. Whether a channel is the
         * identity is decided from the *folded* track, so `1 ->scale` and a
         * scale track that happens to be constant 1 are treated alike.
         */
        private fun emitRect(rect: RectNode, index: Int): String {
            val levels = ancestorChain(rect.id).map { Level(it.x, it.y, it.rotation, it.scaleX, it.scaleY, it.alpha) } +
                Level(rect.x, rect.y, rect.rotation, rect.scaleX, rect.scaleY, rect.alpha)

            val body = StringBuilder()
            var rotIsZero = true      // no rotation accumulated yet, so no cos/sin needed
            var scaleIsOne = true     // no scale accumulated yet, so offsets pass through
            var alphaIsOne = true
            var usesC = false

            for (level in levels) {
                val x = ref(level.x)
                val y = ref(level.y)
                if (!(isZero(level.x) && isZero(level.y))) {
                    usesC = true
                    val offset = if (scaleIsOne) "vec2($x, $y)" else "vec2($x, $y) * s"
                    if (rotIsZero) {
                        body.appendLine("    c += $offset;")
                    } else {
                        body.appendLine("    {")
                        body.appendLine("        vec2 o = $offset;")
                        body.appendLine("        float cs = cos(rot), sn = sin(rot);")
                        body.appendLine("        c += vec2(cs * o.x - sn * o.y, sn * o.x + cs * o.y);")
                        body.appendLine("    }")
                    }
                }
                if (!isZero(level.rot)) {
                    body.appendLine("    rot += ${ref(level.rot)};")
                    rotIsZero = false
                }
                if (!(isOne(level.scaleX) && isOne(level.scaleY))) {
                    body.appendLine("    s *= vec2(${ref(level.scaleX)}, ${ref(level.scaleY)});")
                    scaleIsOne = false
                }
                if (!isOne(level.alpha)) {
                    body.appendLine("    a *= ${ref(level.alpha)};")
                    alphaIsOne = false
                }
            }

            return buildString {
                appendLine("// ${rect.id}")
                appendLine("float ${prefix}Shape$index(vec2 p, float t) {")
                if (usesC) appendLine("    vec2 c = vec2(0.0);")
                if (!rotIsZero) appendLine("    float rot = 0.0;")
                if (!scaleIsOne) appendLine("    vec2 s = vec2(1.0);")
                if (!alphaIsOne) appendLine("    float a = 1.0;")
                append(body)
                // A rect gated invisible contributes no silhouette; matching the
                // CPU, alpha is clamped to [0,1] before the test.
                if (!alphaIsOne) appendLine("    if (clamp(a, 0.0, 1.0) <= 0.0) return 1e9;")
                val half = "abs(0.5 * vec2(${ref(rect.w)}, ${ref(rect.h)})${if (scaleIsOne) "" else " * s"})"
                val centre = if (usesC) "c" else "vec2(0.0)"
                if (rotIsZero) {
                    appendLine("    return rectslBox(p - $centre, $half);")
                } else {
                    appendLine("    return rectslRect(p, $centre, $half, rot);")
                }
                append("}")
            }
        }

        private class Level(
            val x: Track<Double>,
            val y: Track<Double>,
            val rot: Track<Double>,
            val scaleX: Track<Double>,
            val scaleY: Track<Double>,
            val alpha: Track<Double>
        )

        private fun isZero(track: Track<Double>): Boolean = constantValue(track) == 0.0

        private fun isOne(track: Track<Double>): Boolean = constantValue(track) == 1.0

        private fun ancestorChain(id: String): List<GroupNode> {
            val chain = mutableListOf<GroupNode>()
            var current = parentOf[id]
            val guard = HashSet<String>()
            while (current != null && guard.add(current)) {
                (nodeMap[current] as? GroupNode)?.let { chain += it }
                current = parentOf[current]
            }
            return chain.reversed()
        }

        /**
         * A track as a GLSL float expression evaluated at [timeExpr].
         *
         * Everything but a constant becomes a *call*, never an inlined
         * subexpression. Inlining looks tidier and is a trap: tracks form a DAG,
         * so a node reached by k paths would be written k times, and nested
         * `->face-travel` (which reads its x and y tracks at two times each)
         * turns that into exponential blow-up. One function per distinct track
         * keeps the emitted source linear in the size of the IR.
         */
        fun ref(track: Track<Double>, timeExpr: String = "t"): String {
            constantValue(track)?.let { return it.glslFloat() }
            return "${functionFor(track)}($timeExpr)"
        }

        /**
         * The track's value if it does not actually vary with time, else null.
         *
         * Worth doing rather than leaving to the shader compiler: RectSL's array
         * words build per-copy positions by *offsetting* a constant, so a
         * hundred-copy array arrives here as a hundred
         * `BinaryDoubleTrack(const, const, ADD)` nodes. Folding them turns a
         * hundred emitted functions into a hundred literals.
         *
         * The fold itself lives in [trackConstantValue], shared with the
         * differentiable backend, which needs exactly the same one. The shader
         * takes the default `foldFreeParameters = true`: a [FreeParamTrack] is
         * baked at its current value, because a shader has no notion of an
         * unknown to solve for.
         */
        private fun constantValue(track: Track<Double>): Double? = trackConstantValue(track)

        private fun gateExpr(track: GateTrack, timeExpr: String): String {
            val lo = if (track.lo == Double.NEGATIVE_INFINITY) null else track.lo
            val hi = if (track.hi == Double.POSITIVE_INFINITY) null else track.hi
            // `step(edge, x)` is 1 when x >= edge, which is exactly the CPU's
            // half-open [lo, hi) window.
            return when {
                lo == null && hi == null -> "1.0"
                lo == null -> "(1.0 - step(${hi!!.glslFloat()}, $timeExpr))"
                hi == null -> "step(${lo.glslFloat()}, $timeExpr)"
                else -> "(step(${lo.glslFloat()}, $timeExpr) * (1.0 - step(${hi.glslFloat()}, $timeExpr)))"
            }
        }

        /**
         * Keyed and derivative tracks become functions, deduplicated by value.
         * Making the track IR symbolic bought this for free: two rects built by
         * the same `array` body now carry *equal* tracks, so a hundred copies
         * share one emitted function instead of a hundred identical ones.
         */
        private fun functionFor(track: Track<Double>): String {
            trackNames[track]?.let { return it }
            val name = "${prefix}Track${trackNames.size}"
            // Claim the name before emitting the body: the body recurses into
            // children, and a shared child must resolve to this same map.
            trackNames[track] = name
            trackBodies[name] = "" // placeholder, keeps emission order stable
            trackBodies[name] = when (track) {
                is TcbTrack -> emitTcb(name, track)
                is LinearScalarTrack -> emitLinear(name, track)
                is HoldScalarTrack -> emitHold(name, track)
                is EasedScalarTrack -> emitEased(name, track)
                is HeadingTrack -> emitHeading(name, track)
                is MappedDoubleTrack -> wrapper(name, track.op.glsl(ref(track.source)))
                is BinaryDoubleTrack -> wrapper(name, track.op.glsl(ref(track.a), ref(track.b)))
                is TernaryDoubleTrack ->
                    wrapper(name, track.op.glsl(ref(track.a), ref(track.b), ref(track.c)))
                // A time warp rewrites the clock for its whole subtree, which is
                // a substitution at the call rather than anything in the body.
                is TimeWarpTrack -> wrapper(name, ref(track.source, track.warp.glsl("t", track.amount)))
                is GateTrack -> wrapper(name, gateExpr(track, "t"))
                is ConstantTrack<*> -> wrapper(name, (track.value as Double).glslFloat())
                // Unreachable in practice — constantValue folds a free parameter to its current
                // value before we ever get here — but the sealed `when` has to be total.
                is FreeParamTrack -> wrapper(name, track.value.glslFloat())

                // --- the pattern-language tracks ---------------------------
                // `->every k` fires on cycles 0, k, 2k, … Both of these lower
                // exactly: GLSL's mod already floors the way Math.floorMod does,
                // so the cycle predicate is the same predicate.
                is CycleGateTrack -> wrapper(name, cycleGateExpr(track.k, track.cps, track.phase))
                is CycleSwitchTrack -> wrapper(
                    name,
                    "mix(${ref(track.before)}, ${ref(track.after)}, " +
                        "${cycleGateExpr(track.k, track.cps, track.phase)})"
                )
                // `->degrade` used to be baked with the two pattern queries, on the
                // grounds that a 64-bit integer hash is not something a shader can
                // evaluate. It is: see [emitDegradeGate]. What remains baked is the
                // pair that really cannot be lowered — rational-arithmetic queries
                // over a mini-notation tree, which is an interpreter, not a formula.
                // A rhythm compresses to a handful of exactly-placed steps; a signal
                // does not compress at all. See [bakeTrack] for what each costs.
                is DegradeGateTrack -> emitDegradeGate(name, track)
                is PatTrack -> emitBaked(name, track, "pattern")
                is PatHoldTrack -> emitBaked(name, track, "pattern (sample-and-hold)")
            }
            return name
        }

        /**
         * `->degrade`, lowered **exactly** rather than baked.
         *
         * The gate is a splitmix64-style finaliser over
         * `(ordinal, cycle, salt)` compared against `probability`, and every part of
         * that survives the trip:
         *
         * - `ordinal` and `salt` are known at emit time, so
         *   `(ordinal * 0x9E3779B1) xor (salt * 0xC2B2AE3D)` folds to **one** 64-bit
         *   literal on the CPU. Only the cycle term varies with `t`.
         * - the finaliser's xors, logical right shifts and 64-bit multiplies are
         *   written out in `uvec2` lanes ([INT64_HELPER]), which is the same
         *   arithmetic rather than a model of it.
         * - the comparison is the part that would otherwise quietly reintroduce
         *   `float`. The CPU asks `k / 2^53 < probability` where `k` is a 53-bit
         *   integer and the division is exact, so the condition is exactly
         *   `k < probability * 2^53`. That threshold is computed once, on the CPU, as
         *   an exact `Long` — `ceil` because `k` is an integer and the bound is not —
         *   and compared with an unsigned 64-bit `<`. No float is involved in the
         *   decision at all, so there is no near-threshold ulp to lose.
         *
         * The one residual difference is the cycle index itself: `floor(t * cps)` is
         * evaluated in `float` here and `double` on the CPU, so the two can disagree
         * about which cycle `t` belongs to within about an ulp of a cycle boundary.
         * That is a discontinuity of the gate either way, and it is roughly five
         * orders of magnitude tighter than the 8.3ms edge displacement the bake had.
         */
        private fun emitDegradeGate(name: String, track: DegradeGateTrack): String {
            usesInt64 = true
            // Fold everything that does not depend on t. `ordinal` and `salt` are Ints
            // widened to Long exactly as the CPU widens them.
            val keyed = (track.ordinal * 0x9E3779B1L) xor (track.salt * 0xC2B2AE3DL)
            // `u < probability` with u = k / 2^53 exactly  <=>  k < probability * 2^53.
            // p in [0,1] so p * 2^53 <= 2^53 and the product is exact in double; ceil
            // turns "k < a real" into "k < an integer" without changing the predicate.
            val threshold = kotlin.math.ceil(track.probability.coerceIn(0.0, 1.0) * 9007199254740992.0).toLong()
            return buildString {
                appendLine("float $name(float t) {")
                appendLine("    // ->degrade ordinal=${track.ordinal} p=${track.probability} salt=${track.salt}")
                appendLine("    int cyc = int(floor(t * ${track.cps.glslFloat()}));")
                appendLine(
                    "    uvec2 h = ${keyed.glslUvec2()} ^ rectslMul64(rectslCycle64(cyc), ${0x85EBCA77L.glslUvec2()});"
                )
                appendLine("    h ^= rectslShr64(h, 33);")
                appendLine("    h = rectslMul64(h, ${(-0xAE502812AA7333L).glslUvec2()});")
                appendLine("    h ^= rectslShr64(h, 29);")
                appendLine("    h = rectslMul64(h, ${(-0x3B314601E57A13ADL).glslUvec2()});")
                appendLine("    h ^= rectslShr64(h, 32);")
                appendLine("    return rectslLess64(rectslShr64(h, 11), ${threshold.glslUvec2()}) ? 0.0 : 1.0;")
                append("}")
            }
        }

        /** `1.0` on the cycles `->every k` fires on, `0.0` elsewhere. */
        private fun cycleGateExpr(k: Int, cps: Double, phase: Long): String {
            if (k <= 0) return "1.0"
            return "(mod(floor(t * ${cps.glslFloat()}) - ${phase.toDouble().glslFloat()}, " +
                "${k.toDouble().glslFloat()}) < 0.5 ? 1.0 : 0.0)"
        }

        /**
         * Sample a track that cannot be lowered symbolically and emit whichever
         * shape [bakeTrack] found closest to it — a hold chain with bisected edges
         * for a rhythm, a ramp through the samples for a signal.
         *
         * The cost is recorded twice over: as a sentence in [notes], and as the
         * measurement it came from in [bakes], so a test can hold each kind of bake
         * to the bound that kind of bake can actually meet.
         */
        private fun emitBaked(name: String, track: Track<Double>, what: String): String {
            val bake = bakeTrack(track, program.duration, program.fps)
            val report = RectSLSdfBake(
                what = what,
                fidelity = bake.fidelity,
                shape = bake.shape,
                knotCount = bake.knots.size,
                sampleCount = bake.sampleCount,
                sampleRate = bake.sampleRate,
                duration = bake.duration,
                edgeTolerance = bake.edgeTolerance,
                worstError = bake.worstError,
                valueRange = bake.valueRange,
                halfRateError = bake.halfRateError
            )
            bakes += report
            notes += report.note
            return when (bake.shape) {
                RectSLSdfBakeShape.HOLD -> emitBakedHold(name, report, bake)
                RectSLSdfBakeShape.RAMP -> emitBakedRamp(name, report, bake)
            }
        }

        /** The steps as a chain of assignments: the last one whose instant has passed wins. */
        private fun emitBakedHold(name: String, report: RectSLSdfBake, bake: BakedTrack): String = buildString {
            appendLine("float $name(float t) {")
            appendLine("    // ${report.note}")
            appendLine("    float v = ${bake.knots.first().second.glslFloat()};")
            bake.knots.drop(1).forEach { (time, value) ->
                appendLine("    if (t >= ${time.glslFloat()}) v = ${value.glslFloat()};")
            }
            appendLine("    return v;")
            append("}")
        }

        /**
         * The samples as a `const` array read by arithmetic rather than by branching.
         *
         * A signal does not compress, so the chain a rhythm becomes would be one
         * comparison per sample here — 1281 of them for a four-cycle `sine`, which is
         * both the largest thing in the emitted shader and the slowest way to ask.
         * The grid is uniform, so the index is a multiply; and the clamp is what keeps
         * the promise that the bake is constant outside `[0, duration]`. Emitting the
         * musicality section this way is 55% less GLSL than the branch chain was.
         *
         * The cost moves rather than vanishing: a dynamically indexed `const` array
         * usually becomes private storage per invocation, so a 1281-knot ramp is a few
         * KB a fragment. If that ever matters the answer is to decimate the knots —
         * linear interpolation is accurate enough here to lose most of them — not to
         * go back to branching.
         */
        private fun emitBakedRamp(name: String, report: RectSLSdfBake, bake: BakedTrack): String = buildString {
            val n = bake.knots.size
            appendLine("float $name(float t) {")
            appendLine("    // ${report.note}")
            appendLine("    const float v[$n] = float[$n](")
            bake.knots.map { it.second }.chunked(8).forEachIndexed { index, chunk ->
                val comma = if ((index + 1) * 8 < n) "," else ""
                appendLine("        " + chunk.joinToString(", ") { it.glslFloat() } + comma)
            }
            appendLine("    );")
            appendLine("    float u = clamp(t * ${bake.sampleRate.glslFloat()}, 0.0, ${(n - 1).toDouble().glslFloat()});")
            appendLine("    int i = min(int(u), ${n - 2});")
            appendLine("    return mix(v[i], v[i + 1], u - float(i));")
            append("}")
        }

        private fun wrapper(name: String, expression: String): String =
            "float $name(float t) {\n    return $expression;\n}"

        private fun keysSorted(keys: List<TcbKey>) = keys.sortedBy { it.time }

        private fun emitTcb(name: String, track: TcbTrack): String {
            val keys = keysSorted(track.keys)
            if (keys.size == 1) return constantFunction(name, keys.first().value)
            return buildString {
                appendLine("float $name(float t) {")
                appendLine("    if (t <= ${keys.first().time.glslFloat()}) return ${keys.first().value.glslFloat()};")
                appendLine("    if (t >= ${keys.last().time.glslFloat()}) return ${keys.last().value.glslFloat()};")
                for (seg in track.hermiteSegments()) {
                    if (abs(seg.dt) <= 1e-12) continue
                    appendLine("    if (t < ${seg.t1.glslFloat()}) {")
                    appendLine("        float u = clamp((t - ${seg.t0.glslFloat()}) / ${seg.dt.glslFloat()}, 0.0, 1.0);")
                    appendLine("        float u2 = u * u;")
                    appendLine("        float u3 = u2 * u;")
                    appendLine(
                        "        return (2.0 * u3 - 3.0 * u2 + 1.0) * ${seg.p0.glslFloat()}" +
                            " + (u3 - 2.0 * u2 + u) * ${(seg.dt * seg.m0).glslFloat()}" +
                            " + (-2.0 * u3 + 3.0 * u2) * ${seg.p1.glslFloat()}" +
                            " + (u3 - u2) * ${(seg.dt * seg.m1).glslFloat()};"
                    )
                    appendLine("    }")
                }
                appendLine("    return ${keys.last().value.glslFloat()};")
                append("}")
            }
        }

        private fun emitLinear(name: String, track: LinearScalarTrack): String {
            val keys = keysSorted(track.keys)
            if (keys.size == 1) return constantFunction(name, keys.first().value)
            return buildString {
                appendLine("float $name(float t) {")
                appendLine("    if (t <= ${keys.first().time.glslFloat()}) return ${keys.first().value.glslFloat()};")
                appendLine("    if (t >= ${keys.last().time.glslFloat()}) return ${keys.last().value.glslFloat()};")
                for (i in 0 until keys.size - 1) {
                    val a = keys[i]
                    val b = keys[i + 1]
                    if (abs(b.time - a.time) <= 1e-12) continue
                    appendLine(
                        "    if (t < ${b.time.glslFloat()}) return mix(${a.value.glslFloat()}, ${b.value.glslFloat()}, " +
                            "clamp((t - ${a.time.glslFloat()}) / ${(b.time - a.time).glslFloat()}, 0.0, 1.0));"
                    )
                }
                appendLine("    return ${keys.last().value.glslFloat()};")
                append("}")
            }
        }

        private fun emitHold(name: String, track: HoldScalarTrack): String {
            val keys = keysSorted(track.keys)
            if (keys.size == 1) return constantFunction(name, keys.first().value)
            return buildString {
                appendLine("float $name(float t) {")
                // Last key with time <= t wins, matching `lastOrNull { it.time <= t }`;
                // before the first key the CPU falls back to the first value.
                appendLine("    float v = ${keys.first().value.glslFloat()};")
                for (key in keys) {
                    appendLine("    if (t >= ${key.time.glslFloat()}) v = ${key.value.glslFloat()};")
                }
                appendLine("    return v;")
                append("}")
            }
        }

        private fun emitEased(name: String, track: EasedScalarTrack): String {
            val keys = keysSorted(track.keys)
            if (keys.size == 1) return constantFunction(name, keys.first().value)
            val curve = track.easeName
            if (curve !in EASING_GLSL) {
                // Never silently substitute a different curve: say so, and fall
                // back to the linear interpolation the eased track shapes.
                notes += "easing '$curve' has no GLSL body; emitted linearly instead"
            } else {
                easings += curve
                if (curve.startsWith("bounce")) usesBounce = true
            }
            val shape: (String) -> String =
                if (curve in EASING_GLSL) { u -> "${easingFunctionName(curve)}($u)" } else { u -> u }
            return buildString {
                appendLine("float $name(float t) {")
                appendLine("    if (t <= ${keys.first().time.glslFloat()}) return ${keys.first().value.glslFloat()};")
                appendLine("    if (t >= ${keys.last().time.glslFloat()}) return ${keys.last().value.glslFloat()};")
                for (i in 0 until keys.size - 1) {
                    val a = keys[i]
                    val b = keys[i + 1]
                    if (abs(b.time - a.time) <= 1e-12) continue
                    appendLine("    if (t < ${b.time.glslFloat()}) {")
                    appendLine(
                        "        float u = clamp((t - ${a.time.glslFloat()}) / ${(b.time - a.time).glslFloat()}, 0.0, 1.0);"
                    )
                    appendLine("        return mix(${a.value.glslFloat()}, ${b.value.glslFloat()}, ${shape("u")});")
                    appendLine("    }")
                }
                appendLine("    return ${keys.last().value.glslFloat()};")
                append("}")
            }
        }

        /**
         * `->face-travel`: the heading of the point (x(t), y(t)), recovered by
         * central difference because the tracks are closed-form. The widening
         * search for real motion is kept — at a velocity zero-crossing the
         * heading is undefined, and popping to zero there is visible.
         */
        private fun emitHeading(name: String, track: HeadingTrack): String = buildString {
            appendLine("float $name(float t) {")
            appendLine("    float h = ${track.eps.glslFloat()};")
            appendLine("    for (int i = 0; i < 6; ++i) {")
            appendLine("        float dx = ${ref(track.x, "t + h")} - ${ref(track.x, "t - h")};")
            appendLine("        float dy = ${ref(track.y, "t + h")} - ${ref(track.y, "t - h")};")
            appendLine("        if (length(vec2(dx, dy)) > 1e-9) return atan(dy, dx) + ${track.offset.glslFloat()};")
            appendLine("        h *= 4.0;")
            appendLine("    }")
            appendLine("    return ${track.offset.glslFloat()};")
            append("}")
        }

        private fun constantFunction(name: String, value: Double): String =
            "float $name(float t) {\n    return ${value.glslFloat()};\n}"

        private fun easingFunctionName(name: String): String =
            "rectslEase_" + name.replace('-', '_')
    }
}

/**
 * A `Long` as the `uvec2` lane pair [RectSLGlsl.INT64_HELPER] operates on: `x` the
 * low 32 bits, `y` the high 32, both written as unsigned hex so no sign ever has to
 * survive a round trip through GLSL's `int` literal parsing.
 */
internal fun Long.glslUvec2(): String {
    val lo = (this and 0xFFFFFFFFL).toString(16)
    val hi = ((this ushr 32) and 0xFFFFFFFFL).toString(16)
    return "uvec2(0x${lo}u, 0x${hi}u)"
}

/**
 * How a scene is lowered — the choices a consumer of the field cannot make for
 * itself, because they happen *inside* `scene`.
 *
 * [blendRadius] replaces the `min` that unions the rects with a polynomial
 * smooth minimum of that radius, in wall units. This is the one advertised
 * capability that is not a look: it changes the set, so copies of an `array`
 * fuse into one body as they pass each other. Rects gated invisible are excluded
 * from the blend rather than dragging their 1e9 sentinel into it.
 *
 * [namePrefix] renames every symbol this emission owns (`<prefix>Shape*`,
 * `<prefix>Track*`, `<prefix>Union*`). The scene-independent helpers — the box
 * field, the easing curves, the smooth minimum — keep their `rectsl…` names on
 * purpose, so two scenes emitted into one shader *share* them; see
 * [RectSLSdfResult.globals].
 */
data class RectSLSdfOptions(
    val blendRadius: Double = 0.0,
    val namePrefix: String = "rectsl"
) {
    init {
        require(blendRadius >= 0.0) { "blendRadius cannot be negative" }
        require(namePrefix.isNotBlank() && namePrefix.all { it.isLetterOrDigit() || it == '_' }) {
            "namePrefix must be a GLSL identifier fragment, was '$namePrefix'"
        }
    }
}

/**
 * What a bake can claim, which is a property of the *track* rather than of the bake.
 *
 * @see RectSLGlsl.bakeTrack for how each is decided, and what each costs
 */
enum class RectSLSdfBakeFidelity {
    /**
     * Piecewise constant, plateaus genuinely flat, edges found by bisection. Exact
     * everywhere except within [RectSLGlsl.BAKE_EDGE_TOLERANCE] of an edge. A rhythm
     * — `"bd*4"`, `"0 3 -3 6"`, `segment`, `quantise`, `square` — is this.
     */
    STEPPED,

    /**
     * A signal with no plateaus, so nothing is exact and the deviation is measured
     * instead. `sine`, `cosine`, `tri`, `perlin`, `saw`, `isaw` are this.
     */
    SAMPLED,

    /**
     * The deviation matters and sampling is not converging on it: halving the rate
     * leaves the error where it was, so refining it would not move it either. `rand`
     * is the case — it hashes the reduced rational instant it is asked about, so a
     * finer grid is a *different* signal rather than a better approximation of this
     * one. Both halves are required: a piecewise-linear signal also fails to improve
     * with rate, but only by 0.0002% of its own range, which is not a caveat worth
     * frightening anyone with.
     */
    ALIASED
}

/** How a bake is written into GLSL. See [RectSLSdfBakeFidelity] for what it costs. */
enum class RectSLSdfBakeShape {
    /** A chain of `if (t >= edge) v = value;` — the shape of a rhythm. */
    HOLD,

    /** A `const` array of uniform samples, indexed by arithmetic and interpolated. */
    RAMP
}

/**
 * One track the SDF emitter had to sample, and what that measurably cost.
 *
 * This is the same information as the matching sentence in [RectSLSdfResult.notes]
 * — [note] *is* that sentence — as numbers rather than as prose, so a caller can
 * hold a rhythm to a bound in *seconds* and a signal to a bound in *track units*
 * instead of asserting that neither happened. `RectSLGlslSdfTest` does exactly that.
 */
data class RectSLSdfBake(
    /** Which query was baked: `"pattern"` or `"pattern (sample-and-hold)"`. */
    val what: String,
    /** What this bake can claim, and therefore which bound below applies. */
    val fidelity: RectSLSdfBakeFidelity,
    /** How it was written into GLSL, which follows from whichever measured closer. */
    val shape: RectSLSdfBakeShape,
    /** Steps in the emitted chain, or samples in the emitted ramp. */
    val knotCount: Int,
    /** Samples taken of the track, before compression. */
    val sampleCount: Int,
    val sampleRate: Double,
    /** The bake covers `[0, duration]`; outside it the emitted function is constant. */
    val duration: Double,
    /** How far an emitted edge can sit from the track's own, in seconds. */
    val edgeTolerance: Double,
    /** Worst deviation from the track, in the track's own units, measured at emit time. */
    val worstError: Double,
    /** The track's own spread over the bake, so [worstError] can be read as a fraction. */
    val valueRange: Double,
    /**
     * [worstError] again, from a bake at half the sample rate — the control that
     * separates a signal being approximated from a track that sampling cannot
     * converge on at all. `NaN` for a stepped bake, which is exact and has no need
     * of it.
     */
    val halfRateError: Double
) {
    /** [worstError] as a fraction of what the track does at all. */
    val relativeError: Double get() = if (valueRange > 0.0) worstError / valueRange else worstError

    /**
     * How much worse the same bake is at half the rate. Above ~1.5 the samples are
     * converging on something and [worstError] is a real approximation error; at ~1
     * they are not, and [RectSLSdfBakeFidelity.ALIASED] is what that means.
     */
    val convergenceRatio: Double
        get() = when {
            !halfRateError.isFinite() -> Double.NaN
            worstError > 0.0 -> halfRateError / worstError
            else -> Double.POSITIVE_INFINITY
        }

    /**
     * The sentence that appears in [RectSLSdfResult.notes] and above the emitted
     * function.
     *
     * Every number is formatted into a local before any of them is interpolated. A
     * `String.format` over a string that already contains a formatted percentage
     * reads the `%` in `100.000% of` as a conversion and throws, which is how the
     * first version of this failed the whole gallery.
     */
    val note: String
        get() {
            val span = "over [0, %.3fs]".format(duration)
            val shapeText = when (shape) {
                RectSLSdfBakeShape.HOLD -> "$knotCount-step hold chain"
                RectSLSdfBakeShape.RAMP -> "$knotCount-knot ramp"
            }
            val rate = "%.1f Hz".format(sampleRate)
            val deviation = "worst deviation %.2e, %.3f%% of the track's own %.3f range"
                .format(worstError, relativeError * 100.0, valueRange)
            val placement = "%.0e s".format(edgeTolerance)
            val control = "%.2fx".format(convergenceRatio)
            return when (fidelity) {
                RectSLSdfBakeFidelity.STEPPED ->
                    "$what lowered as a $shapeText $span; edges placed to $placement and exact " +
                        "between them; constant outside that range"
                RectSLSdfBakeFidelity.SAMPLED ->
                    "$what signal sampled at $rate $span as a $shapeText; $deviation; " +
                        "constant outside that range"
                RectSLSdfBakeFidelity.ALIASED ->
                    "$what signal is not resolved by sampling it at $rate: the emitted $shapeText " +
                        "$span has $deviation, and halving the rate leaves that at $control rather " +
                        "than worsening it, so no rate fixes this — a signal hashed from the query " +
                        "instant has no value between samples to converge on"
            }
        }
}

/** What [RectSLGlsl.emitSdf] produced, and anything it could not carry across. */
data class RectSLSdfResult(
    /** [globals] then [sceneGlsl]: the whole self-contained field, ready to paste. */
    val glsl: String,
    /**
     * Function name -> definition, for the helpers whose bodies depend on nothing
     * in this program. Merging the maps of two emissions and writing each entry
     * once is what lets two scenes share one shader; see [RectSLSdfOptions].
     */
    val globals: Map<String, String> = emptyMap(),
    /** Just this program's own functions — tracks, shapes, unions, and `scene`. */
    val sceneGlsl: String = glsl,
    /** The name the scene function was emitted under. */
    val sceneFunctionName: String = "rectslScene",
    val rectCount: Int,
    val trackFunctionCount: Int,
    /** Fidelity caveats — empty means nothing was approximated or dropped. */
    val notes: List<String> = emptyList(),
    /**
     * The measured half of [notes]: one entry per track the emitter had to sample.
     * `notes.size == bakes.size` means sampling a pattern was the *only* thing it
     * could not carry across.
     */
    val bakes: List<RectSLSdfBake> = emptyList()
) {
    val ok: Boolean get() = notes.isEmpty()
}
