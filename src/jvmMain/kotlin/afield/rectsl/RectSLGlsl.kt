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
 * to the ulp.
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
            notes = emitter.notes
        )
    }

    /** How many shapes one union helper folds together. See `unionTree`. */
    private const val UNION_FANIN = 32

    /** Ceiling on samples taken when baking a track that cannot be lowered. */
    private const val MAX_BAKE_SAMPLES = 4096

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
        var usesBounce = false
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
         */
        private fun constantValue(track: Track<Double>): Double? = when (track) {
            is ConstantTrack<*> -> track.value as Double
            is MappedDoubleTrack -> constantValue(track.source)?.let(track.op.eval)
            is BinaryDoubleTrack -> {
                val a = constantValue(track.a)
                val b = constantValue(track.b)
                if (a != null && b != null) track.op.eval(a, b) else null
            }
            is TernaryDoubleTrack -> {
                val a = constantValue(track.a)
                val b = constantValue(track.b)
                val c = constantValue(track.c)
                if (a != null && b != null && c != null) track.op.eval(a, b, c) else null
            }
            // Warping the clock of something that ignores the clock changes nothing.
            is TimeWarpTrack -> constantValue(track.source)
            is TcbTrack -> track.keys.singleOrNull()?.value
            is LinearScalarTrack -> track.keys.singleOrNull()?.value
            is HoldScalarTrack -> track.keys.singleOrNull()?.value
            is EasedScalarTrack -> track.keys.singleOrNull()?.value
            is GateTrack -> null
            is HeadingTrack -> null
            // Every pattern track is a function of the cycle, so none is constant.
            is CycleGateTrack, is CycleSwitchTrack, is DegradeGateTrack, is PatTrack, is PatHoldTrack -> null
        }

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
                // The rest are step functions no shader can evaluate: a 64-bit
                // integer hash, and rational-arithmetic pattern queries. Bake
                // them — being piecewise constant, they compress to a handful of
                // steps rather than to a table.
                is DegradeGateTrack -> bakeAsHold(name, track, "degrade gate")
                is PatTrack -> bakeAsHold(name, track, "pattern")
                is PatHoldTrack -> bakeAsHold(name, track, "pattern (sample-and-hold)")
            }
            return name
        }

        /** `1.0` on the cycles `->every k` fires on, `0.0` elsewhere. */
        private fun cycleGateExpr(k: Int, cps: Double, phase: Long): String {
            if (k <= 0) return "1.0"
            return "(mod(floor(t * ${cps.glslFloat()}) - ${phase.toDouble().glslFloat()}, " +
                "${k.toDouble().glslFloat()}) < 0.5 ? 1.0 : 0.0)"
        }

        /**
         * Sample a track that cannot be lowered symbolically and re-express it as
         * the hold chain its values already describe.
         *
         * Only reached for tracks that are piecewise constant in time, which is
         * what makes this a fair trade rather than a lossy one: consecutive equal
         * samples collapse, so a dropout gate over six seconds becomes a few
         * steps. The costs are real and recorded in [notes] rather than hidden —
         * the baked function is flat outside `[0, duration]`, and a step falling
         * between two samples lands on the nearer sample instant.
         */
        private fun bakeAsHold(name: String, track: Track<Double>, what: String): String {
            val duration = if (program.duration > 0.0) program.duration else 1.0
            val rate = maxOf(program.fps * 4.0, 120.0)
            val count = minOf((duration * rate).toInt() + 1, MAX_BAKE_SAMPLES)
            val steps = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until count) {
                val t = duration * i / (count - 1).coerceAtLeast(1)
                val v = track.sample(t)
                if (steps.isEmpty() || steps.last().second != v) steps += t to v
            }
            notes += "$what baked as ${steps.size} step(s) from $count samples over " +
                "[0, ${duration}s]; constant outside that range"
            return buildString {
                appendLine("float $name(float t) {")
                appendLine("    float v = ${steps.first().second.glslFloat()};")
                steps.drop(1).forEach { (time, value) ->
                    appendLine("    if (t >= ${time.glslFloat()}) v = ${value.glslFloat()};")
                }
                appendLine("    return v;")
                append("}")
            }
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
    val notes: List<String> = emptyList()
) {
    val ok: Boolean get() = notes.isEmpty()
}
