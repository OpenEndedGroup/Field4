package afield.stdlib

import afield.stdlib.Functions.Easing
import afield.stdlib.Functions.PulseEdge
import afield.stdlib.Functions.PulseProfile

/**
 * Single source of truth for the `Fn` graph op vocabulary and `op=` transmutation.
 *
 * The stdlib in [Functions] attaches a structural [FnGraphNode] to almost every
 * function it builds, each with a stable `op` string and role-tagged children.
 * Three places used to hardcode their own idea of "which ops exist": the
 * `rebuildAsOp` transmuter, the rewrite-DSL validator, and the Monaco language
 * extension (`fnrewrite.js`). They drifted. This registry is the one place that
 * knows the vocabulary; the others consult it (and a JVM test asserts the JS
 * mirror never drifts).
 *
 * Transmutation ("rebuild this node as a different op") is honest about what is
 * possible. Generators rebuild from role-addressed children plus scalar metadata
 * and always succeed, degrading missing roles to the factory defaults. Value
 * transforms rebuild only when the source node already has a `source` child, so
 * `clamp => op=softClip` works while `sin => op=clamp` is a reported no-op rather
 * than a lie. Data-carrying ops (piecewise, timeline, rbf, sequencing, …) are
 * *known* — selectors and validation recognise them — but are not transmutation
 * targets, because their identity lives in baked point/item data a foreign node
 * cannot supply.
 */
object OpRegistry {

    /** A transmutation target: how to rebuild [canonical] from an arbitrary matched node. */
    data class OpSpec(
        val canonical: String,
        val family: String,
        val aliases: Set<String> = emptySet(),
        val build: (OpSource) -> Functions.Fn?
    )

    /** Read-only view of the node being transmuted: children by role, plus scalar metadata. */
    class OpSource internal constructor(val self: Functions.Fn) {
        val node: FnGraphNode = self.graphNode()
        val domain: Functions.Domain get() = self.domain
        val numbers: Map<String, Double> get() = node.numbers
        val strings: Map<String, String> get() = node.strings
        val booleans: Map<String, Boolean> get() = node.booleans
        val longs: Map<String, Long> get() = node.longs

        private val byRole: Map<String, Functions.Fn> =
            node.children.associate { it.role to it.fn }

        fun child(vararg roles: String): Functions.Fn? = roles.firstNotNullOfOrNull { byRole[it] }
        fun childOr(default: Double, vararg roles: String): Functions.Fn = child(*roles) ?: p(default)
        fun source(): Functions.Fn? = child("source")

        fun long(key: String, default: Long = 0L): Long = longs[key] ?: default
        fun int(key: String, default: Int): Int = numbers[key]?.toInt() ?: default
        fun bool(key: String, default: Boolean): Boolean = booleans[key] ?: default
        fun str(key: String): String? = strings[key]

        /** frequency, or the reciprocal of period, or the default — bridges oscillator families. */
        fun frequencyLike(default: Double = 1.0): Functions.Fn =
            child("frequency") ?: child("period")?.let(::reciprocal) ?: p(default)

        /** period, or the reciprocal of frequency, or the default. */
        fun periodLike(default: Double = 1.0): Functions.Fn =
            child("period") ?: child("frequency")?.let(::reciprocal) ?: p(default)
    }

    // ---- transmutation builders (one per op; delegate to the real Functions factory) ----

    private fun buildSin(s: OpSource) = Functions.sin(s.frequencyLike(), s.childOr(0.0, "phase"), s.childOr(1.0, "amplitude"), s.childOr(0.0, "offset"), s.domain)
    private fun buildCos(s: OpSource) = Functions.cos(s.frequencyLike(), s.childOr(0.0, "phase"), s.childOr(1.0, "amplitude"), s.childOr(0.0, "offset"), s.domain)
    private fun buildSaw(s: OpSource) = Functions.saw(s.periodLike(), s.childOr(0.0, "phase"), s.childOr(1.0, "amplitude"), s.childOr(0.0, "offset"), s.domain)
    private fun buildTriangle(s: OpSource) = Functions.triangle(s.periodLike(), s.childOr(0.0, "phase"), s.childOr(1.0, "amplitude"), s.childOr(0.0, "offset"), s.domain)
    private fun buildSquare(s: OpSource) = Functions.square(s.periodLike(), s.childOr(0.0, "phase"), s.childOr(0.5, "duty"), s.childOr(1.0, "amplitude"), s.childOr(0.0, "offset"), s.domain)

    private fun buildPulseTrain(s: OpSource) = Functions.pulseTrain(
        period = s.periodLike(),
        phase = s.childOr(0.0, "phase"),
        duty = s.childOr(0.5, "duty"),
        amplitude = s.childOr(1.0, "amplitude"),
        offset = s.childOr(0.0, "offset"),
        edge = parseEnum(s.str("edge"), PulseEdge.SMOOTH),
        profile = parseEnum(s.str("profile"), PulseProfile.RECT),
        seed = s.long("seed"),
        bipolar = s.bool("bipolar", false),
        domain = s.domain
    )

    private fun buildGaussian(s: OpSource) = Functions.gaussian(s.childOr(0.0, "mean"), s.childOr(1.0, "sigma"), s.childOr(1.0, "amplitude"), s.domain)

    private fun buildWhiteNoise(s: OpSource) = Functions.whiteNoise(s.long("seed"), s.frequencyLike(), s.childOr(1.0, "amplitude"), s.domain)
    private fun buildValueNoise(s: OpSource) = Functions.valueNoise(s.long("seed"), s.frequencyLike(), s.childOr(1.0, "amplitude"), s.domain)
    private fun buildPerlinNoise(s: OpSource) = Functions.perlinNoise(s.long("seed"), s.frequencyLike(), s.childOr(1.0, "amplitude"), s.domain)
    private fun buildFbm(s: OpSource) = Functions.fbm(s.long("seed"), s.frequencyLike(), s.childOr(1.0, "amplitude"), s.int("octaves", 5), s.childOr(2.0, "lacunarity"), s.childOr(0.5, "gain"), s.domain)
    private fun buildFbmPerlin(s: OpSource) = Functions.fbmPerlin(s.long("seed"), s.frequencyLike(), s.childOr(1.0, "amplitude"), s.int("octaves", 5), s.childOr(2.0, "lacunarity"), s.childOr(0.5, "gain"), s.domain)
    private fun buildRandomWalk(s: OpSource) = Functions.randomWalk(s.long("seed"), s.childOr(1.0, "step"), s.childOr(1.0, "amplitude"), s.domain)

    private fun buildRamp(s: OpSource) = Functions.ramp(s.childOr(0.0, "start"), s.childOr(1.0, "end"), s.domain)
    private fun buildStep(s: OpSource) = Functions.step(s.childOr(0.0, "edge"), s.domain)
    private fun buildSmoothStep(s: OpSource) = Functions.smoothStep(s.childOr(0.0, "edge0"), s.childOr(1.0, "edge1"), s.domain)

    private fun buildHann(s: OpSource) = Functions.hannWindow(s.domain)
    private fun buildHamming(s: OpSource) = Functions.hammingWindow(s.domain)
    private fun buildBlackman(s: OpSource) = Functions.blackmanWindow(s.domain)
    private fun buildGaussianWindow(s: OpSource) = Functions.gaussianWindow(s.domain, s.childOr(0.4, "sigma"))

    private fun buildTween(s: OpSource) = Functions.tween(s.childOr(0.0, "from"), s.childOr(1.0, "to"), s.domain, parseEnum(s.str("easing"), Easing.SMOOTHSTEP))
    private fun buildEase(s: OpSource): Functions.Fn? {
        val src = s.source() ?: return null
        return with(Functions) { src.ease(parseEnum(s.str("easing"), Easing.SMOOTHSTEP), s.bool("clampInput", true)) }
    }

    // algebra — needs both operands
    private fun buildBinary(s: OpSource, combine: Functions.(Functions.Fn, Functions.Fn) -> Functions.Fn): Functions.Fn? {
        val left = s.child("left") ?: return null
        val right = s.child("right") ?: return null
        return combine(Functions, left, right)
    }

    // value transforms — need an existing source child (so transform => transform works, generator => transform is a no-op)
    private fun buildUnary(s: OpSource, wrap: Functions.(Functions.Fn) -> Functions.Fn): Functions.Fn? {
        val src = s.source() ?: return null
        return wrap(Functions, src)
    }

    private val specs: List<OpSpec> = listOf(
        OpSpec("sin", "oscillator", setOf("sine"), ::buildSin),
        OpSpec("cos", "oscillator", setOf("cosine"), ::buildCos),
        OpSpec("saw", "oscillator", setOf("sawtooth", "ramp-wave"), ::buildSaw),
        OpSpec("triangle", "oscillator", setOf("tri"), ::buildTriangle),
        OpSpec("square", "oscillator", setOf("rect"), ::buildSquare),
        OpSpec("pulsetrain", "pulse", setOf("pulseTrain"), ::buildPulseTrain),
        OpSpec("gaussian", "shape", emptySet(), ::buildGaussian),
        OpSpec("ramp", "shape", emptySet(), ::buildRamp),
        OpSpec("step", "shape", emptySet(), ::buildStep),
        OpSpec("smoothstep", "shape", setOf("smoothStep"), ::buildSmoothStep),
        OpSpec("tween", "motion", emptySet(), ::buildTween),
        OpSpec("ease", "motion", emptySet(), ::buildEase),
        OpSpec("whiteNoise", "noise", setOf("white", "whitenoise"), ::buildWhiteNoise),
        OpSpec("valueNoise", "noise", setOf("valuenoise", "noise"), ::buildValueNoise),
        OpSpec("perlinNoise", "noise", setOf("perlin", "perlinnoise"), ::buildPerlinNoise),
        OpSpec("fbm", "noise", emptySet(), ::buildFbm),
        OpSpec("fbmPerlin", "noise", setOf("fbmperlin"), ::buildFbmPerlin),
        OpSpec("randomWalk", "noise", setOf("randomwalk"), ::buildRandomWalk),
        OpSpec("hannWindow", "window", setOf("hann"), ::buildHann),
        OpSpec("hammingWindow", "window", setOf("hamming"), ::buildHamming),
        OpSpec("blackmanWindow", "window", setOf("blackman"), ::buildBlackman),
        OpSpec("gaussianWindow", "window", setOf("gausswin"), ::buildGaussianWindow),
        OpSpec("add", "algebra", setOf("+", "plus")) { buildBinary(it) { a, b -> a + b } },
        OpSpec("subtract", "algebra", setOf("-", "minus")) { buildBinary(it) { a, b -> a - b } },
        OpSpec("multiply", "algebra", setOf("*", "times")) { buildBinary(it) { a, b -> a * b } },
        OpSpec("divide", "algebra", setOf("/")) { buildBinary(it) { a, b -> a / b } },
        OpSpec("shift", "transform", setOf("delay")) { buildUnary(it) { f -> f.shift(it.childOr(0.0, "dx", "delay")) } },
        OpSpec("scaleX", "transform", emptySet()) { buildUnary(it) { f -> f.scaleX(it.childOr(1.0, "scale"), it.childOr(0.0, "about")) } },
        OpSpec("scaleY", "transform", setOf("gain")) { buildUnary(it) { f -> f.scaleY(it.childOr(1.0, "scale")) } },
        OpSpec("offsetY", "transform", setOf("bias")) { buildUnary(it) { f -> f.offsetY(it.childOr(0.0, "offset")) } },
        OpSpec("pow", "transform", emptySet()) { buildUnary(it) { f -> f.pow(it.childOr(1.0, "exponent")) } },
        OpSpec("log", "transform", emptySet()) { buildUnary(it) { f -> f.log(it.childOr(kotlin.math.E, "base")) } },
        OpSpec("clamp", "transform", emptySet()) { buildUnary(it) { f -> f.clamp(it.childOr(-1.0, "min"), it.childOr(1.0, "max")) } },
        OpSpec("clip", "transform", emptySet()) { buildUnary(it) { f -> f.clip(it.childOr(1.0, "threshold")) } },
        OpSpec("softClip", "transform", emptySet()) { buildUnary(it) { f -> f.softClip(it.childOr(1.0, "amount")) } },
        OpSpec("fold", "transform", emptySet()) { buildUnary(it) { f -> f.fold(it.childOr(1.0, "threshold")) } },
        OpSpec("chebyshev", "transform", setOf("cheby")) { buildUnary(it) { f -> f.chebyshev(it.childOr(2.0, "order")) } },
        OpSpec("wrapY", "transform", emptySet()) { buildUnary(it) { f -> f.wrapY(it.childOr(-1.0, "min"), it.childOr(1.0, "max")) } }
    )

    private val specByName: Map<String, OpSpec> = buildMap {
        for (spec in specs) {
            put(spec.canonical.lowercase(), spec)
            for (alias in spec.aliases) put(alias.lowercase(), spec)
        }
    }

    /**
     * Every op string the stdlib actually emits (from `node(...)`/`graphFn(...)`),
     * so validation and selectors never treat a real op as unknown even when it is
     * not a transmutation target. Kept in sync with `Functions.kt` by
     * `FunctionsGraphRewriteTest`.
     */
    val knownOps: Set<String> = (specByName.keys + setOf(
        "const", "param", "identity", "name", "over", "lambda", "opaque", "array",
        "amplitudeMod", "ringMod", "warp", "sampleHold", "quantizeX", "jitterX", "saturate",
        "mapX", "mapY", "stretch", "reverse", "crossfade", "cut", "replace", "withEdge",
        "repeat", "mirror", "mirrorRepeat", "tile", "pad", "echo", "copyPaste", "randomCopyPaste",
        "randomRepeat", "gateHold",
        "derivative", "integral", "differentiate", "convolve", "movingAverage", "blur",
        "gaussianSmooth", "autoScale", "zNormalize", "whitenCdf",
        "piecewise", "piecewiseLinear", "catmullRom", "monotoneCubic", "hermite",
        "bSplineQuadratic", "cosineInterp", "smoothStepInterp", "smootherStepInterp",
        "stepHold", "nearestValue", "rbf", "granular",
        "timeline", "timelineTap", "spliceWindow", "sequence", "sequenceLoop",
        "randomSequence", "weightedSequence", "randomPermute", "randomPermuteLoop",
        "palindrome", "roundRobin", "stack", "average", "select", "morph",
        "pulseburst", "pulsepattern", "dutyFromWidth", "periodFromFrequency", "period"
    ).map { it.lowercase() }).toSet()

    /** Canonical op names that `op=` can rebuild to, grouped by family, for docs and diagnostics. */
    val transmutableOps: List<String> get() = specs.map { it.canonical }

    fun knows(name: String): Boolean {
        val key = name.trim().trim('"', '\'').lowercase()
        return key in specByName || key in knownOps
    }

    fun isTransmutable(name: String): Boolean =
        name.trim().trim('"', '\'').lowercase() in specByName

    fun canonical(name: String): String? =
        specByName[name.trim().trim('"', '\'').lowercase()]?.canonical

    /** The family of a transmutable op (e.g. "oscillator", "noise", "transform"). */
    fun family(name: String): String? =
        specByName[name.trim().trim('"', '\'').lowercase()]?.family

    /** Canonical transmutable ops in [family], useful for within-family mutation. */
    fun opsInFamily(family: String): List<String> =
        specs.filter { it.family == family }.map { it.canonical }

    /** Rebuild [fn] as [targetOp], or null if the target is not a viable transmutation of this node. */
    fun rebuild(fn: Functions.Fn, targetOp: String): Functions.Fn? {
        val spec = specByName[targetOp.trim().trim('"', '\'').lowercase()] ?: return null
        return spec.build(OpSource(fn))
    }

    /** One-line description of the transmutation vocabulary, for validator hints. */
    fun transmutableHint(): String = "transmutable: " + transmutableOps.joinToString(", ")

    private fun p(v: Double): Functions.Fn = Functions.c(v, Functions.Domain.ALL)
    private fun reciprocal(f: Functions.Fn): Functions.Fn = with(Functions) { p(1.0) / f }

    private inline fun <reified E : Enum<E>> parseEnum(value: String?, default: E): E =
        value?.let { raw -> enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } } ?: default
}
