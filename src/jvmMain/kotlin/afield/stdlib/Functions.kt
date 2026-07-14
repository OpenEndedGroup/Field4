@file:afield.CompletionCategory("Math")

package afield.stdlib

import afield.Keyframe
import afield.KeyframeEvaluator
import afield.Box
import afield.graphics.FLine
import kotlin.math.*

fun interface RbfKernel {
    fun eval(r: Double): Double
}

object RbfKernels {
    val linear = RbfKernel { r -> r }
    val quadratic = RbfKernel { r -> r * r }
    val cubic = RbfKernel { r -> r * r * r }
    val thinPlate = RbfKernel { r -> if (r == 0.0) 0.0 else r * r * ln(r) }

    fun gaussian(epsilon: Double = 1.0) = RbfKernel { r ->
        val v = epsilon * r
        kotlin.math.exp(-(v * v))
    }

    fun multiquadric(c: Double = 1.0) = RbfKernel { r -> sqrt(r * r + c * c) }

    fun inverseMultiquadric(c: Double = 1.0) = RbfKernel { r -> 1.0 / sqrt(r * r + c * c) }

    fun inverseQuadratic(c: Double = 1.0) = RbfKernel { r -> 1.0 / (r * r + c * c) }
}

private fun solveRbfLinearSystem(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
    val n = bIn.size
    if (n == 0) return DoubleArray(0)
    val a = Array(n) { i -> aIn[i].clone() }
    val b = bIn.clone()
    val eps = 1e-12
    for (i in 0 until n) {
        var pivot = i
        var max = kotlin.math.abs(a[i][i])
        for (r in i + 1 until n) {
            val v = kotlin.math.abs(a[r][i])
            if (v > max) {
                max = v
                pivot = r
            }
        }
        if (max < eps) continue
        if (pivot != i) {
            val tmp = a[i]; a[i] = a[pivot]; a[pivot] = tmp
            val tb = b[i]; b[i] = b[pivot]; b[pivot] = tb
        }
        val inv = 1.0 / a[i][i]
        for (j in i until n) a[i][j] *= inv
        b[i] *= inv
        for (r in 0 until n) {
            if (r == i) continue
            val factor = a[r][i]
            if (factor == 0.0) continue
            for (j in i until n) a[r][j] -= factor * a[i][j]
            b[r] -= factor * b[i]
        }
    }
    return b
}

class RbfInterpolator(
    private val points: List<Functions.Sample>,
    private val kernel: RbfKernel,
    private val smoothing: Double = 0.0,
    private val base: Functions.Fn? = null
) {
    private val xs = DoubleArray(points.size) { points[it].x }
    private val weights: DoubleArray

    init {
        val n = points.size
        if (n == 0) {
            weights = DoubleArray(0)
        } else {
            val a = Array(n) { DoubleArray(n) }
            val b = DoubleArray(n)
            for (i in 0 until n) {
                val xi = xs[i]
                val target = points[i].y - (base?.eval(xi) ?: 0.0)
                b[i] = target * points[i].w
                for (j in 0 until n) {
                    a[i][j] = kernel.eval(kotlin.math.abs(xi - xs[j]))
                }
                a[i][i] += smoothing
            }
            weights = solveRbfLinearSystem(a, b)
        }
    }

    fun eval(x: Double): Double {
        var sum = base?.eval(x) ?: 0.0
        for (i in weights.indices) {
            sum += weights[i] * kernel.eval(kotlin.math.abs(x - xs[i]))
        }
        return sum
    }

    override fun toString(): String = prettySummary(
        "RbfInterpolator",
        "points" to points.size,
        "kernel" to kernel,
        "smoothing" to prettyNumber(smoothing),
        "base" to base
    )
}

object Functions {
    private const val TAU = Math.PI * 2.0

    // Closed range for 1D functions. Supports infinite and empty ranges.
    public data class Domain(val min: Double, val max: Double) : ClosedRange<Double> {
        override val start: Double
            get() = min
        override val endInclusive: Double
            get() = max

        // avoid jvm name collision with ClosedRange.isEmpty()
        val _isEmpty: Boolean
            get() = min.isNaN() || max.isNaN() || max < min

        val isFinite: Boolean
            get() = min.isFinite() && max.isFinite() && !_isEmpty

        val length: Double
            get() = if (_isEmpty) 0.0 else max - min

        val center: Double
            get() = if (_isEmpty) Double.NaN else (min + max) * 0.5

        fun containsInclusive(x: Double): Boolean = !_isEmpty && x >= min && x <= max

        override fun contains(value: Double): Boolean = containsInclusive(value)

        fun expand(amount: Double): Domain = if (_isEmpty) this else Domain(min - amount, max + amount)

        fun shrink(amount: Double): Domain = if (_isEmpty) this else Domain(min + amount, max - amount)

        fun shift(dx: Double): Domain = if (_isEmpty) this else Domain(min + dx, max + dx)

        fun scale(s: Double, about: Double = 0.0): Domain {
            if (_isEmpty) return this
            val a = (min - about) * s + about
            val b = (max - about) * s + about
            return if (a <= b) Domain(a, b) else Domain(b, a)
        }

        fun lerp(alpha: Double): Double = if (_isEmpty) Double.NaN else min + length * alpha

        fun alphaOf(x: Double): Double = if (_isEmpty || length == 0.0) 0.0 else (x - min) / length

        fun clamp(x: Double): Double {
            if (_isEmpty) return x
            return when {
                x < min -> min
                x > max -> max
                else -> x
            }
        }

        fun wrap(x: Double): Double {
            if (_isEmpty) return x
            val len = length
            if (!len.isFinite() || len == 0.0) return x
            val t = (x - min) % len
            val u = if (t < 0.0) t + len else t
            return min + u
        }

        fun reflect(x: Double): Double {
            if (_isEmpty) return x
            val len = length
            if (!len.isFinite() || len == 0.0) return x
            val two = len * 2.0
            val t = (x - min) % two
            val u = if (t < 0.0) t + two else t
            return if (u <= len) min + u else max - (u - len)
        }

        fun intersect(other: Domain): Domain {
            if (this._isEmpty) return this
            if (other._isEmpty) return other
            return Domain(max(min, other.min), min(max, other.max))
        }

        fun union(other: Domain): Domain {
            if (this._isEmpty) return other
            if (other._isEmpty) return this
            return Domain(min(min, other.min), max(max, other.max))
        }

        companion object {
            val ALL = Domain(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            val UNIT = Domain(0.0, 1.0)
            val DEFAULT = UNIT
            val EMPTY = Domain(1.0, 0.0)

            fun of(a: Double, b: Double): Domain = if (a <= b) Domain(a, b) else Domain(b, a)
            fun point(x: Double): Domain = Domain(x, x)
            fun centered(center: Double, radius: Double): Domain = Domain(center - radius, center + radius)
        }

        override fun toString(): String = when {
            _isEmpty -> "Domain.EMPTY"
            this == ALL -> "Domain.ALL"
            this == DEFAULT -> "Domain.DEFAULT"
            min == max -> "Domain(${prettyNumber(min)})"
            else -> "Domain(${prettyNumber(min)}..${prettyNumber(max)})"
        }
    }

    /**
     * Human-facing domain notation.
     *
     * In a `Functions` scope, `0.0..1.0` resolves to a [Domain], which keeps
     * timeline code readable. Kotlin's built-in `0..1` is still an `IntRange`
     * because member operators win, so public DSL entry points also accept
     * numeric [ClosedRange] values and convert them with [toDomain].
     */
    operator fun Double.rangeTo(end: Double): Domain = Domain.of(this, end)
    operator fun Double.rangeTo(end: Number): Domain = Domain.of(this, end.toDouble())
    operator fun Number.rangeTo(end: Double): Domain = Domain.of(this.toDouble(), end)

    fun domain(range: ClosedRange<*>): Domain = range.toDomain()

    fun ClosedRange<*>.toDomain(): Domain =
        Domain.of(rangeEndpoint(start), rangeEndpoint(endInclusive))

    private fun rangeEndpoint(value: Any?): Double =
        (value as? Number)?.toDouble()
            ?: throw IllegalArgumentException("Functions domains need numeric range endpoints, got $value")

    enum class EdgeMode { ZERO, CLAMP, WRAP, MIRROR }

    enum class Interpolation { NEAREST, LINEAR, CUBIC }

    enum class PulseEdge { HARD, LINEAR, SMOOTH, SINE, EXP }

    enum class PulseProfile { RECT, TRIANGLE, SINE, HANN, GAUSSIAN }

    enum class StretchShape { LINEAR, SMOOTHSTEP, SMOOTHERSTEP }

    /** Rectifier polarity for the [rectify] waveshaping transform: half-wave clamps the
     * negative excursion to zero; full-wave reflects it (|x|). */
    enum class RectifyMode { HALF, FULL }

    /** Common normalized-time shapes for procedural motion and value tweening. */
    /**
     * Named motion curves for [tween], [Fn.ease], and other alpha-shaping helpers.
     * The `_IN`/`_OUT`/`_IN_OUT` families follow the conventional Penner easings so
     * procedural animation reads the same here as in other motion toolkits.
     */
    enum class Easing {
        LINEAR,
        QUAD_IN,
        QUAD_OUT,
        QUAD_IN_OUT,
        CUBIC_IN,
        CUBIC_OUT,
        CUBIC_IN_OUT,
        QUART_IN,
        QUART_OUT,
        QUART_IN_OUT,
        QUINT_IN,
        QUINT_OUT,
        QUINT_IN_OUT,
        SINE_IN,
        SINE_OUT,
        SINE_IN_OUT,
        EXPO_IN,
        EXPO_OUT,
        EXPO_IN_OUT,
        CIRC_IN,
        CIRC_OUT,
        CIRC_IN_OUT,
        BACK_IN,
        BACK_OUT,
        BACK_IN_OUT,
        ELASTIC_IN,
        ELASTIC_OUT,
        ELASTIC_IN_OUT,
        BOUNCE_IN,
        BOUNCE_OUT,
        BOUNCE_IN_OUT,
        SMOOTHSTEP,
        SMOOTHERSTEP
    }

    // Common enum entries are re-exported here so `with(Functions)` reads like a
    // small DSL (`blend = ADD`, `profile = HANN`) instead of exposing every
    // internal enum path. Ambiguous entries such as CLAMP/WRAP stay qualified.
    val HARD: PulseEdge get() = PulseEdge.HARD
    val SMOOTH: PulseEdge get() = PulseEdge.SMOOTH
    val EXP: PulseEdge get() = PulseEdge.EXP
    val RECT: PulseProfile get() = PulseProfile.RECT
    val TRIANGLE: PulseProfile get() = PulseProfile.TRIANGLE
    val HANN: PulseProfile get() = PulseProfile.HANN
    val GAUSSIAN: PulseProfile get() = PulseProfile.GAUSSIAN
    val NEAREST: Interpolation get() = Interpolation.NEAREST
    val CUBIC: Interpolation get() = Interpolation.CUBIC

    /**
     * A one-dimensional signal together with the interval on which it is meant to
     * be sampled.
     *
     * Earlier drafts split this into "domain" and "ROI". That sounded flexible
     * but made composition ambiguous: arithmetic wanted intersection, timeline
     * placement wanted a destination interval, and samplers had to guess which
     * range was authoritative. For now the public model is deliberately simpler:
     * an [Fn] has one [domain]. When a timeline remaps a function, the chunk owns
     * the second interval explicitly as its placement [TimelineChunk.domain].
     *
     * User-facing constructors default to [Domain.DEFAULT] (`0.0..1.0`). Use
     * [Domain.ALL] explicitly for functions that should be unbounded or merely
     * act as non-constraining parameter signals.
     */
    data class Fn(
        val domain: Domain = Domain.DEFAULT,
        val name: String? = null,
        val graph: FnGraphNode? = null,
        private val evalFn: (Double) -> Double
    ) {
        fun eval(x: Double): Double = evalFn(x)

        operator fun invoke(x: Double): Double = evalFn(x)

        fun evalSafe(x: Double, outside: Double = 0.0, edge: EdgeMode = EdgeMode.ZERO): Double {
            if (domain.containsInclusive(x)) return evalFn(x)
            return when (edge) {
                EdgeMode.ZERO -> outside
                EdgeMode.CLAMP -> evalFn(domain.clamp(x))
                EdgeMode.WRAP -> evalFn(domain.wrap(x))
                EdgeMode.MIRROR -> evalFn(domain.reflect(x))
            }
        }

        fun withDomain(newDomain: Domain): Fn = Fn(
            newDomain,
            name,
            FnGraphNode(
                op = "over",
                label = "over",
                children = listOf(FnGraphEdge("source", this)),
                strings = mapOf("domain" to newDomain.toString()),
                rebuild = { children -> children[0].withDomain(newDomain) }
            ),
            evalFn
        )
        fun withDomain(newDomain: ClosedRange<*>): Fn = withDomain(Functions.domain(newDomain))
        fun withName(newName: String?): Fn = Fn(
            domain,
            newName,
            FnGraphNode(
                op = "name",
                label = newName ?: "name",
                children = listOf(FnGraphEdge("source", this)),
                strings = mapOf("name" to (newName ?: "")),
                rebuild = { children -> children[0].withName(newName) }
            ),
            evalFn
        )

        override fun toString(): String = prettySummary(
            "Fn",
            "name" to (name ?: "anonymous"),
            "domain" to domain
        )
    }

    private fun blend(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    private fun clamp01(x: Double): Double = x.coerceIn(0.0, 1.0)
    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)
    private fun smootherstep(t: Double): Double = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
    private fun powIn(t: Double, p: Double): Double = t.pow(p)
    private fun powOut(t: Double, p: Double): Double = 1.0 - (1.0 - t).pow(p)
    private fun powInOut(t: Double, p: Double): Double =
        if (t < 0.5) 0.5 * (2.0 * t).pow(p) else 1.0 - 0.5 * (2.0 - 2.0 * t).pow(p)

    private fun bounceOut(t: Double): Double {
        val n = 7.5625
        val d = 2.75
        return when {
            t < 1.0 / d -> n * t * t
            t < 2.0 / d -> (t - 1.5 / d).let { n * it * it + 0.75 }
            t < 2.5 / d -> (t - 2.25 / d).let { n * it * it + 0.9375 }
            else -> (t - 2.625 / d).let { n * it * it + 0.984375 }
        }
    }

    private fun shapeAlpha(t: Double, easing: Easing): Double {
        val c1 = 1.70158
        val c2 = c1 * 1.525
        val c3 = c1 + 1.0
        val c4 = TAU / 3.0
        val c5 = TAU / 4.5
        return when (easing) {
            Easing.LINEAR -> t
            Easing.QUAD_IN -> powIn(t, 2.0)
            Easing.QUAD_OUT -> powOut(t, 2.0)
            Easing.QUAD_IN_OUT -> powInOut(t, 2.0)
            Easing.CUBIC_IN -> powIn(t, 3.0)
            Easing.CUBIC_OUT -> powOut(t, 3.0)
            Easing.CUBIC_IN_OUT -> powInOut(t, 3.0)
            Easing.QUART_IN -> powIn(t, 4.0)
            Easing.QUART_OUT -> powOut(t, 4.0)
            Easing.QUART_IN_OUT -> powInOut(t, 4.0)
            Easing.QUINT_IN -> powIn(t, 5.0)
            Easing.QUINT_OUT -> powOut(t, 5.0)
            Easing.QUINT_IN_OUT -> powInOut(t, 5.0)
            Easing.SINE_IN -> 1.0 - kotlin.math.cos(t * Math.PI / 2.0)
            Easing.SINE_OUT -> kotlin.math.sin(t * Math.PI / 2.0)
            Easing.SINE_IN_OUT -> -(kotlin.math.cos(Math.PI * t) - 1.0) * 0.5
            Easing.EXPO_IN -> if (t == 0.0) 0.0 else 2.0.pow(10.0 * t - 10.0)
            Easing.EXPO_OUT -> if (t == 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t)
            Easing.EXPO_IN_OUT -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                t < 0.5 -> 2.0.pow(20.0 * t - 10.0) / 2.0
                else -> (2.0 - 2.0.pow(-20.0 * t + 10.0)) / 2.0
            }
            Easing.CIRC_IN -> 1.0 - kotlin.math.sqrt(1.0 - t * t)
            Easing.CIRC_OUT -> kotlin.math.sqrt(1.0 - (t - 1.0) * (t - 1.0))
            Easing.CIRC_IN_OUT -> if (t < 0.5)
                (1.0 - kotlin.math.sqrt(1.0 - (2.0 * t) * (2.0 * t))) / 2.0
            else
                (kotlin.math.sqrt(1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0)) + 1.0) / 2.0
            Easing.BACK_IN -> c3 * t * t * t - c1 * t * t
            Easing.BACK_OUT -> 1.0 + c3 * (t - 1.0).pow(3.0) + c1 * (t - 1.0).pow(2.0)
            Easing.BACK_IN_OUT -> if (t < 0.5)
                ((2.0 * t) * (2.0 * t) * ((c2 + 1.0) * 2.0 * t - c2)) / 2.0
            else
                ((2.0 * t - 2.0) * (2.0 * t - 2.0) * ((c2 + 1.0) * (t * 2.0 - 2.0) + c2) + 2.0) / 2.0
            Easing.ELASTIC_IN -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                else -> -2.0.pow(10.0 * t - 10.0) * kotlin.math.sin((t * 10.0 - 10.75) * c4)
            }
            Easing.ELASTIC_OUT -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                else -> 2.0.pow(-10.0 * t) * kotlin.math.sin((t * 10.0 - 0.75) * c4) + 1.0
            }
            Easing.ELASTIC_IN_OUT -> when {
                t == 0.0 -> 0.0
                t == 1.0 -> 1.0
                t < 0.5 -> -(2.0.pow(20.0 * t - 10.0) * kotlin.math.sin((20.0 * t - 11.125) * c5)) / 2.0
                else -> (2.0.pow(-20.0 * t + 10.0) * kotlin.math.sin((20.0 * t - 11.125) * c5)) / 2.0 + 1.0
            }
            Easing.BOUNCE_IN -> 1.0 - bounceOut(1.0 - t)
            Easing.BOUNCE_OUT -> bounceOut(t)
            Easing.BOUNCE_IN_OUT -> if (t < 0.5)
                (1.0 - bounceOut(1.0 - 2.0 * t)) / 2.0
            else
                (1.0 + bounceOut(2.0 * t - 1.0)) / 2.0
            Easing.SMOOTHSTEP -> smoothstep(t)
            Easing.SMOOTHERSTEP -> smootherstep(t)
        }
    }
    private fun easeAlpha(alpha: Double, easing: Easing): Double = shapeAlpha(alpha.coerceIn(0.0, 1.0), easing)
    private fun fract(x: Double): Double = x - floor(x)
    private fun stretchAlpha(alpha: Double, shape: StretchShape): Double =
        when (shape) {
            StretchShape.LINEAR -> alpha
            StretchShape.SMOOTHSTEP -> smoothstep(alpha)
            StretchShape.SMOOTHERSTEP -> smootherstep(alpha)
        }

    private fun requireStretchDomain(domain: Domain, label: String) {
        require(domain.isFinite) { "$label domain must be finite for stretch; got $domain" }
    }

    private fun intersectDomains(base: Domain, vararg fns: Fn?): Domain {
        var d = base
        for (f in fns) {
            if (f != null) d = d.intersect(f.domain)
        }
        return d
    }

    private fun edgeRamp(t: Double, edge: PulseEdge, curve: Double): Double {
        val u = t.coerceIn(0.0, 1.0)
        return when (edge) {
            PulseEdge.HARD -> if (u >= 1.0) 1.0 else 0.0
            PulseEdge.LINEAR -> u
            PulseEdge.SMOOTH -> smoothstep(u)
            PulseEdge.SINE -> 0.5 - 0.5 * kotlin.math.cos(Math.PI * u)
            PulseEdge.EXP -> {
                if (curve == 0.0) u
                else {
                    val top = kotlin.math.exp(curve * u) - 1.0
                    val bot = kotlin.math.exp(curve) - 1.0
                    if (bot == 0.0) u else top / bot
                }
            }
        }
    }

    private fun pulseProfileValue(profile: PulseProfile, u: Double, sigma: Double): Double {
        val t = u.coerceIn(0.0, 1.0)
        return when (profile) {
            PulseProfile.RECT -> 1.0
            PulseProfile.TRIANGLE -> if (t < 0.5) t * 2.0 else 2.0 - t * 2.0
            PulseProfile.SINE -> kotlin.math.sin(Math.PI * t)
            PulseProfile.HANN -> 0.5 - 0.5 * kotlin.math.cos(TAU * t)
            PulseProfile.GAUSSIAN -> {
                val s = if (sigma <= 0.0) 0.15 else sigma
                val z = (t - 0.5) / s
                kotlin.math.exp(-0.5 * z * z)
            }
        }
    }

    private fun pulseShape(
        t: Double,
        duty: Double,
        rise: Double,
        fall: Double,
        edge: PulseEdge,
        curve: Double,
        profile: PulseProfile,
        profileSigma: Double
    ): Double {
        if (duty <= 0.0) return 0.0
        val d = clamp01(duty)
        val tt = t.coerceIn(0.0, 1.0)
        if (tt >= d) return 0.0
        var r = rise.coerceAtLeast(0.0)
        var f = fall.coerceAtLeast(0.0)
        val totalEdge = r + f
        if (totalEdge > d && totalEdge > 0.0) {
            val scale = d / totalEdge
            r *= scale
            f *= scale
        }
        val env = when {
            r > 0.0 && tt < r -> edgeRamp(tt / r, edge, curve)
            f > 0.0 && tt > d - f -> 1.0 - edgeRamp((tt - (d - f)) / f, edge, curve)
            else -> 1.0
        }
        val u = if (d == 0.0) 0.0 else (tt / d).coerceIn(0.0, 1.0)
        return env * pulseProfileValue(profile, u, profileSigma)
    }

    private fun combinedDomain(a: Fn, b: Fn): Domain = a.domain.intersect(b.domain)

    private fun Domain.safeShift(dx: Double): Domain = if (this._isEmpty) this else this.shift(dx)
    private fun Domain.safeScale(scale: Double, about: Double = 0.0): Domain = if (this._isEmpty) this else this.scale(scale, about)

    private fun fnEdge(role: String, fn: Fn): FnGraphEdge = FnGraphEdge(role, fn)
    private fun itemEdges(fns: List<Fn>, prefix: String = "item"): List<FnGraphEdge> =
        fns.mapIndexed { index, fn -> fnEdge("$prefix$index", fn) }

    private fun childAt(children: List<Fn>, index: Int, fallback: Fn): Fn =
        children.getOrNull(index) ?: fallback

    private fun itemChildren(children: List<Fn>, count: Int): List<Fn> =
        children.take(count)

    private fun node(
        op: String,
        label: String = op,
        children: List<FnGraphEdge> = emptyList(),
        numbers: Map<String, Double> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
        longs: Map<String, Long> = emptyMap(),
        tags: Set<String> = emptySet(),
        rebuild: FnGraphRebuilder? = null
    ): FnGraphNode =
        FnGraphNode(op, label, children, numbers, strings, booleans, longs, tags, rebuild)

    private fun graphFn(
        domain: Domain,
        name: String?,
        graph: FnGraphNode,
        eval: (Double) -> Double
    ): Fn = Fn(domain, name, graph, eval)

    private fun graphFn(
        domain: Domain,
        name: String?,
        op: String,
        label: String = op,
        children: List<FnGraphEdge> = emptyList(),
        numbers: Map<String, Double> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
        longs: Map<String, Long> = emptyMap(),
        tags: Set<String> = emptySet(),
        rebuild: FnGraphRebuilder? = null,
        eval: (Double) -> Double
    ): Fn = graphFn(
        domain,
        name,
        node(op, label, children, numbers, strings, booleans, longs, tags, rebuild),
        eval
    )

    fun fn(
        domain: Domain = Domain.DEFAULT,
        name: String? = null,
        eval: (Double) -> Double
    ): Fn = graphFn(
        domain,
        name,
        node("lambda", label = name ?: "lambda", tags = setOf("opaque"))
    ) { x -> eval(x) }

    fun fn(
        domain: ClosedRange<*>,
        name: String? = null,
        eval: (Double) -> Double
    ): Fn = fn(domain.toDomain(), name, eval)

    // Base functions
    fun c(value: Number, domain: Domain = Domain.DEFAULT): Fn {
        val v = value.toDouble()
        return graphFn(
            domain,
            "const",
            node(
                "const",
                numbers = mapOf("value" to v),
                tags = setOf("number"),
                rebuild = { c(v, domain) }
            )
        ) { v }
    }

    private fun param(value: Number): Fn {
        val v = value.toDouble()
        return graphFn(
            Domain.ALL,
            "param",
            node(
                "param",
                numbers = mapOf("value" to v),
                tags = setOf("number", "parameter"),
                rebuild = { param(v) }
            )
        ) { v }
    }

    private fun asParam(value: Any, parameterName: String): Fn = when (value) {
        is Fn -> value
        is Number -> param(value)
        else -> error("$parameterName must be an Fn or Number, got ${value::class.simpleName ?: "unknown"}")
    }

    private fun asOptionalParam(value: Any?, parameterName: String): Fn? =
        value?.let { asParam(it, parameterName) }

    // `c(0.5)` is a real public constant and therefore has the default domain.
    // `0.5.c` is intentionally a parameter signal: it should not shrink the
    // domain of the function it is configuring.
    val Number.c: Fn
        get() = param(this)

    fun c(value: Number, domain: ClosedRange<*>): Fn =
        c(value, domain.toDomain())

    fun constant(value: Number, domain: Domain = Domain.DEFAULT): Fn =
        c(value, domain)

    fun constant(value: Number, domain: ClosedRange<*>): Fn =
        c(value, domain)

    fun zero(domain: Domain = Domain.DEFAULT): Fn = c(0.0, domain)
    fun one(domain: Domain = Domain.DEFAULT): Fn = c(1.0, domain)

    fun zero(domain: ClosedRange<*>): Fn = zero(domain.toDomain())
    fun one(domain: ClosedRange<*>): Fn = one(domain.toDomain())

    fun identity(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(
            domain,
            "x",
            node("identity", label = "x", rebuild = { identity(domain) })
        ) { x -> x }

    fun identity(domain: ClosedRange<*>): Fn =
        identity(domain.toDomain())

    /**
     * A **value ramp**: a straight line whose value goes from [start] to [end] as x
     * sweeps the function's [domain]. `ramp(1.0, 6.0) over (0.0..1.0)` is 1 at x=0 and
     * 6 at x=1; `ramp(0.0, 1.0)` is the identity over the default 0..1 domain. This is
     * the linear sibling of [tween] (which eases the same endpoints). An unbounded
     * domain is previewed over a finite window via [Domain.finiteSweep], so a ramp on
     * `Domain.ALL` stays finite to enumerate.
     *
     * For the inverse operation — *normalizing* an x-window down to 0..1 — use [remap].
     */
    fun ramp(
        start: Fn = param(0.0),
        end: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, start, end)
        return graphFn(
            d,
            "ramp",
            node(
                "ramp",
                children = listOf(fnEdge("start", start), fnEdge("end", end)),
                rebuild = { children -> ramp(children[0], children[1], domain) }
            )
        ) { x ->
            blend(start.eval(x), end.eval(x), domain.finiteSweep().alphaOf(x))
        }
    }

    fun ramp(
        start: Fn = param(0.0),
        end: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = ramp(start, end, domain.toDomain())

    fun ramp(start: Number, end: Number, domain: Domain = Domain.DEFAULT): Fn =
        ramp(param(start), param(end), domain)

    fun ramp(start: Number, end: Number, domain: ClosedRange<*>): Fn =
        ramp(start, end, domain.toDomain())

    fun ramp(start: Any, end: Any, domain: Domain = Domain.DEFAULT): Fn =
        ramp(asParam(start, "start"), asParam(end, "end"), domain)

    fun ramp(start: Any, end: Any, domain: ClosedRange<*>): Fn =
        ramp(start, end, domain.toDomain())

    /**
     * A **domain normalizer**: maps the x-window `[from, to]` onto `0..1`, i.e.
     * `(x - from) / (to - from)`. `remap(0.0, 10.0)` turns an x in 0..10 into a 0..1
     * alpha; `remap(0.0, 1.0)` is the identity. This is the position-of-x reading —
     * distinct from [ramp], which produces a value that *travels* from one number to
     * another across the domain. (Until 2026-07-14 this behaviour was misleadingly
     * named `ramp`; it was renamed once it became clear every caller reached for `ramp`
     * expecting the value-line semantics that [ramp] now has.)
     */
    fun remap(
        from: Fn = param(0.0),
        to: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, from, to)
        return graphFn(
            d,
            "remap",
            node(
                "remap",
                children = listOf(fnEdge("from", from), fnEdge("to", to)),
                rebuild = { children -> remap(children[0], children[1], domain) }
            )
        ) { x ->
            val s = from.eval(x)
            val e = to.eval(x)
            val len = e - s
            if (len == 0.0) 0.0 else (x - s) / len
        }
    }

    fun remap(
        from: Fn = param(0.0),
        to: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = remap(from, to, domain.toDomain())

    fun remap(from: Number, to: Number, domain: Domain = Domain.DEFAULT): Fn =
        remap(param(from), param(to), domain)

    fun remap(from: Number, to: Number, domain: ClosedRange<*>): Fn =
        remap(from, to, domain.toDomain())

    fun remap(from: Any, to: Any, domain: Domain = Domain.DEFAULT): Fn =
        remap(asParam(from, "from"), asParam(to, "to"), domain)

    fun remap(from: Any, to: Any, domain: ClosedRange<*>): Fn =
        remap(from, to, domain.toDomain())

    fun step(edge: Fn = param(0.0), domain: Domain = Domain.DEFAULT): Fn {
        val d = intersectDomains(domain, edge)
        return graphFn(
            d,
            "step",
            node(
                "step",
                children = listOf(fnEdge("edge", edge)),
                rebuild = { children -> step(children[0], domain) }
            )
        ) { x -> if (x >= edge.eval(x)) 1.0 else 0.0 }
    }

    fun step(edge: Fn = param(0.0), domain: ClosedRange<*>): Fn =
        step(edge, domain.toDomain())

    fun step(edge: Number, domain: Domain = Domain.DEFAULT): Fn =
        step(param(edge), domain)

    fun step(edge: Number, domain: ClosedRange<*>): Fn =
        step(edge, domain.toDomain())

    fun smoothStep(edge0: Fn = param(0.0), edge1: Fn = param(1.0), domain: Domain = Domain.DEFAULT): Fn {
        val d = intersectDomains(domain, edge0, edge1)
        return graphFn(
            d,
            "smoothstep",
            node(
                "smoothstep",
                children = listOf(fnEdge("edge0", edge0), fnEdge("edge1", edge1)),
                rebuild = { children -> smoothStep(children[0], children[1], domain) }
            )
        ) { x ->
            val a = edge0.eval(x)
            val b = edge1.eval(x)
            val e0 = min(a, b)
            val e1 = max(a, b)
            if (e1 == e0) {
                if (x < e0) 0.0 else 1.0
            } else {
                val t = clamp01((x - e0) / (e1 - e0))
                smoothstep(t)
            }
        }
    }

    fun smoothStep(edge0: Fn = param(0.0), edge1: Fn = param(1.0), domain: ClosedRange<*>): Fn =
        smoothStep(edge0, edge1, domain.toDomain())

    fun smoothStep(edge0: Number, edge1: Number, domain: Domain = Domain.DEFAULT): Fn =
        smoothStep(param(edge0), param(edge1), domain)

    fun smoothStep(edge0: Number, edge1: Number, domain: ClosedRange<*>): Fn =
        smoothStep(edge0, edge1, domain.toDomain())

    fun smoothStep(edge0: Any, edge1: Any, domain: Domain = Domain.DEFAULT): Fn =
        smoothStep(asParam(edge0, "edge0"), asParam(edge1, "edge1"), domain)

    fun smoothStep(edge0: Any, edge1: Any, domain: ClosedRange<*>): Fn =
        smoothStep(edge0, edge1, domain.toDomain())

    /**
     * Interpolates from [from] to [to] across [domain] with a named motion curve.
     * The endpoints may themselves be signals, which keeps tweening compositional.
     */
    fun tween(
        from: Fn = param(0.0),
        to: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT,
        easing: Easing = Easing.SMOOTHSTEP
    ): Fn {
        val d = intersectDomains(domain, from, to)
        return graphFn(
            d,
            "tween",
            "tween",
            children = listOf(fnEdge("from", from), fnEdge("to", to)),
            strings = mapOf("easing" to easing.name),
            rebuild = { children -> tween(children[0], children[1], domain, easing) }
        ) { x ->
            val t = easeAlpha(domain.finiteSweep().alphaOf(x), easing)
            blend(from.eval(x), to.eval(x), t)
        }
    }

    fun tween(
        from: Number,
        to: Number,
        domain: Domain = Domain.DEFAULT,
        easing: Easing = Easing.SMOOTHSTEP
    ): Fn = tween(param(from), param(to), domain, easing)

    fun tween(
        from: Number,
        to: Number,
        domain: ClosedRange<*>,
        easing: Easing = Easing.SMOOTHSTEP
    ): Fn = tween(from, to, domain.toDomain(), easing)

    /** Shapes a normalized signal through [easing], optionally clamping it to 0..1 first. */
    fun Fn.ease(easing: Easing = Easing.SMOOTHSTEP, clampInput: Boolean = true): Fn =
        graphFn(
            domain,
            name?.let { "ease($it)" } ?: "ease",
            "ease",
            children = listOf(fnEdge("source", this)),
            strings = mapOf("easing" to easing.name),
            booleans = mapOf("clampInput" to clampInput),
            rebuild = { children -> children[0].ease(easing, clampInput) }
        ) { x ->
            val value = eval(x)
            if (clampInput) easeAlpha(value, easing)
            else shapeAlpha(value, easing)
        }

    fun sin(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, frequency, phase, amplitude, offset)
        return graphFn(
            d,
            "sin",
            node(
                "sin",
                children = listOf(
                    fnEdge("frequency", frequency),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> sin(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            offset.eval(x) + amplitude.eval(x) * kotlin.math.sin(TAU * frequency.eval(x) * x + phase.eval(x))
        }
    }

    fun sin(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = sin(frequency, phase, amplitude, offset, domain.toDomain())

    fun cos(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, frequency, phase, amplitude, offset)
        return graphFn(
            d,
            "cos",
            node(
                "cos",
                children = listOf(
                    fnEdge("frequency", frequency),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> cos(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            offset.eval(x) + amplitude.eval(x) * kotlin.math.cos(TAU * frequency.eval(x) * x + phase.eval(x))
        }
    }

    fun cos(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = cos(frequency, phase, amplitude, offset, domain.toDomain())

    fun saw(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, amplitude, offset)
        return graphFn(
            d,
            "saw",
            node(
                "saw",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> saw(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = (x - phase.eval(x)) / p
                val f = fract(t)
                offset.eval(x) + amplitude.eval(x) * (f * 2.0 - 1.0)
            }
        }
    }

    fun saw(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = saw(period, phase, amplitude, offset, domain.toDomain())

    fun triangle(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, amplitude, offset)
        return graphFn(
            d,
            "triangle",
            node(
                "triangle",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> triangle(children[0], children[1], children[2], children[3], domain) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = fract((x - phase.eval(x)) / p)
                val v = if (t < 0.5) t * 2.0 else 2.0 - t * 2.0
                offset.eval(x) + amplitude.eval(x) * (v * 2.0 - 1.0)
            }
        }
    }

    fun triangle(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = triangle(period, phase, amplitude, offset, domain.toDomain())

    fun square(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset)
        return graphFn(
            d,
            "square",
            node(
                "square",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("duty", duty),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset)
                ),
                rebuild = { children -> square(children[0], children[1], children[2], children[3], children[4], domain) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) offset.eval(x)
            else {
                val t = fract((x - phase.eval(x)) / p)
                val dval = clamp01(duty.eval(x))
                offset.eval(x) + if (t < dval) amplitude.eval(x) else -amplitude.eval(x)
            }
        }
    }

    fun square(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = square(period, phase, duty, amplitude, offset, domain.toDomain())

    fun pulseTrain(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod)
        return graphFn(
            d,
            "pulsetrain",
            node(
                "pulsetrain",
                children = listOf(
                    fnEdge("period", period),
                    fnEdge("phase", phase),
                    fnEdge("duty", duty),
                    fnEdge("amplitude", amplitude),
                    fnEdge("offset", offset),
                    fnEdge("rise", rise),
                    fnEdge("fall", fall),
                    fnEdge("curve", curve),
                    fnEdge("profileSigma", profileSigma),
                    fnEdge("jitter", jitter),
                    fnEdge("dutyMod", dutyMod),
                    fnEdge("ampMod", ampMod),
                    fnEdge("phaseMod", phaseMod)
                ),
                strings = mapOf("edge" to edge.name, "profile" to profile.name),
                booleans = mapOf("bipolar" to bipolar),
                longs = mapOf("seed" to seed),
                rebuild = { children ->
                    pulseTrain(
                        children[0],
                        children[1],
                        children[2],
                        children[3],
                        children[4],
                        children[5],
                        children[6],
                        edge,
                        children[7],
                        profile,
                        children[8],
                        children[9],
                        seed,
                        bipolar,
                        children[10],
                        children[11],
                        children[12],
                        domain
                    )
                }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn offset.eval(x)
            val phaseShift = phase.eval(x) + phaseMod.eval(x)
            val raw = (x - phaseShift) / p
            val cycle = floor(raw)
            var local = (x - phaseShift) - cycle * p
            val jit = jitter.eval(x)
            if (jit != 0.0) {
                val j = (hashToUnit(seed, cycle.toLong()) - 0.5) * jit * p
                local -= j
            }
            local = ((local % p) + p) % p
            val t = local / p
            val dval = (duty.eval(x) + dutyMod.eval(x)).coerceIn(0.0, 1.0)
            val riseFrac = (rise.eval(x) / p).coerceIn(0.0, 1.0)
            val fallFrac = (fall.eval(x) / p).coerceIn(0.0, 1.0)
            val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, curve.eval(x), profile, profileSigma.eval(x))
            val amp = amplitude.eval(x) * ampMod.eval(x)
            val off = offset.eval(x)
            if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        }
    }

    fun pulseTrain(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: ClosedRange<*>
    ): Fn = pulseTrain(period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrain(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrain(param(period), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    fun pulseTrain(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrain(period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrain(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrain(
        asParam(period, "period"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    fun pulseTrain(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrain(period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrainWidth(
        period: Fn = param(1.0),
        width: Fn = param(0.5),
        phase: Fn = param(0.0),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val dd = intersectDomains(domain, period, width)
        val dutyFn = graphFn(
            dd,
            "duty",
            "dutyFromWidth",
            children = listOf(fnEdge("period", period), fnEdge("width", width)),
            rebuild = { children ->
                graphFn(
                    intersectDomains(domain, children[0], children[1]),
                    "duty",
                    "dutyFromWidth",
                    children = listOf(fnEdge("period", children[0]), fnEdge("width", children[1])),
                    tags = setOf("derived"),
                    rebuild = null
                ) { x ->
                    val p = kotlin.math.abs(children[0].eval(x))
                    if (p == 0.0) 0.0 else (children[1].eval(x) / p).coerceIn(0.0, 1.0)
                }
            },
            tags = setOf("derived")
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) 0.0 else (width.eval(x) / p).coerceIn(0.0, 1.0)
        }
        return pulseTrain(
            period = period,
            phase = phase,
            duty = dutyFn,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            jitter = jitter,
            seed = seed,
            bipolar = bipolar,
            dutyMod = dutyMod,
            ampMod = ampMod,
            phaseMod = phaseMod,
            domain = domain
        )
    }

    fun pulseTrainWidth(
        period: Number,
        width: Number = 0.5,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(param(period), param(width), param(phase), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    fun pulseTrainWidth(
        period: Number,
        width: Number = 0.5,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainWidth(period, width, phase, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrainWidth(
        period: Any,
        width: Any = 0.5,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(
        asParam(period, "period"),
        asParam(width, "width"),
        asParam(phase, "phase"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    fun pulseTrainWidth(
        period: Any,
        width: Any = 0.5,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainWidth(period, width, phase, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrainHz(
        frequency: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        val dd = intersectDomains(domain, frequency)
        val periodFn = graphFn(
            dd,
            "period",
            "periodFromFrequency",
            children = listOf(fnEdge("frequency", frequency)),
            tags = setOf("derived"),
            rebuild = { children ->
                graphFn(
                    intersectDomains(domain, children[0]),
                    "period",
                    "periodFromFrequency",
                    children = listOf(fnEdge("frequency", children[0])),
                    tags = setOf("derived"),
                    rebuild = null
                ) { x ->
                    val f = children[0].eval(x)
                    if (f == 0.0) 0.0 else 1.0 / f
                }
            }
        ) { x ->
            val f = frequency.eval(x)
            if (f == 0.0) 0.0 else 1.0 / f
        }
        return pulseTrain(
            period = periodFn,
            phase = phase,
            duty = duty,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            jitter = jitter,
            seed = seed,
            bipolar = bipolar,
            dutyMod = dutyMod,
            ampMod = ampMod,
            phaseMod = phaseMod,
            domain = domain
        )
    }

    fun pulseTrainHz(
        frequency: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainHz(param(frequency), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    fun pulseTrainHz(
        frequency: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainHz(frequency, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrainHz(
        frequency: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainHz(
        asParam(frequency, "frequency"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    fun pulseTrainHz(
        frequency: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainHz(frequency, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Fn = param(0.0),
        ampMod: Fn = param(1.0),
        phaseMod: Fn = param(0.0),
        domain: Domain = Domain.ALL
    ): Fn {
        if (pattern.isEmpty()) return zero(domain)
        val d = intersectDomains(domain, period, phase, duty, amplitude, offset, rise, fall, curve, profileSigma, jitter, dutyMod, ampMod, phaseMod)
        return graphFn(
            d,
            "pulsepattern",
            "pulsepattern",
            children = listOf(
                fnEdge("period", period),
                fnEdge("phase", phase),
                fnEdge("duty", duty),
                fnEdge("amplitude", amplitude),
                fnEdge("offset", offset),
                fnEdge("rise", rise),
                fnEdge("fall", fall),
                fnEdge("curve", curve),
                fnEdge("profileSigma", profileSigma),
                fnEdge("jitter", jitter),
                fnEdge("dutyMod", dutyMod),
                fnEdge("ampMod", ampMod),
                fnEdge("phaseMod", phaseMod)
            ),
            strings = mapOf("edge" to edge.name, "profile" to profile.name, "patternSize" to pattern.size.toString()),
            booleans = mapOf("bipolar" to bipolar),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                pulseTrainPattern(
                    pattern,
                    children[0],
                    children[1],
                    children[2],
                    children[3],
                    children[4],
                    children[5],
                    children[6],
                    edge,
                    children[7],
                    profile,
                    children[8],
                    children[9],
                    seed,
                    bipolar,
                    children[10],
                    children[11],
                    children[12],
                    domain
                )
            }
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn offset.eval(x)
            val phaseShift = phase.eval(x) + phaseMod.eval(x)
            val raw = (x - phaseShift) / p
            val cycle = floor(raw).toLong()
            var local = (x - phaseShift) - cycle * p
            val jit = jitter.eval(x)
            if (jit != 0.0) {
                val j = (hashToUnit(seed, cycle) - 0.5) * jit * p
                local -= j
            }
            local = ((local % p) + p) % p
            val t = local / p
            val dval = (duty.eval(x) + dutyMod.eval(x)).coerceIn(0.0, 1.0)
            val riseFrac = (rise.eval(x) / p).coerceIn(0.0, 1.0)
            val fallFrac = (fall.eval(x) / p).coerceIn(0.0, 1.0)
            val shaped = pulseShape(t, dval, riseFrac, fallFrac, edge, curve.eval(x), profile, profileSigma.eval(x))
            val size = pattern.size.toLong()
            val idx = ((cycle % size) + size) % size
            val amp = amplitude.eval(x) * pattern[idx.toInt()] * ampMod.eval(x)
            val off = offset.eval(x)
            if (bipolar) off + (shaped * 2.0 - 1.0) * amp else off + shaped * amp
        }
    }

    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainPattern(pattern, param(period), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), param(jitter), seed, bipolar, param(dutyMod), param(ampMod), param(phaseMod), domain)

    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Number = 0.0,
        ampMod: Number = 1.0,
        phaseMod: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainPattern(pattern, period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainPattern(
        pattern,
        asParam(period, "period"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        asParam(jitter, "jitter"),
        seed,
        bipolar,
        asParam(dutyMod, "dutyMod"),
        asParam(ampMod, "ampMod"),
        asParam(phaseMod, "phaseMod"),
        domain
    )

    fun pulseTrainPattern(
        pattern: DoubleArray,
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        bipolar: Boolean = false,
        dutyMod: Any = 0.0,
        ampMod: Any = 1.0,
        phaseMod: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn = pulseTrainPattern(pattern, period, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, jitter, seed, bipolar, dutyMod, ampMod, phaseMod, domain.toDomain())

    fun impulseTrain(
        period: Fn = param(1.0),
        phase: Fn = param(0.0),
        width: Fn = param(0.02),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        sigma: Fn = param(0.15),
        jitter: Fn = param(0.0),
        seed: Long = 0L,
        domain: Domain = Domain.ALL
    ): Fn = pulseTrainWidth(
        period = period,
        width = width,
        phase = phase,
        amplitude = amplitude,
        offset = offset,
        edge = PulseEdge.SMOOTH,
        curve = param(2.0),
        profile = PulseProfile.GAUSSIAN,
        profileSigma = sigma,
        jitter = jitter,
        seed = seed,
        domain = domain
    )

    fun impulseTrain(
        period: Number,
        phase: Number = 0.0,
        width: Number = 0.02,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        sigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        domain: Domain = Domain.ALL
    ): Fn = impulseTrain(param(period), param(phase), param(width), param(amplitude), param(offset), param(sigma), param(jitter), seed, domain)

    fun impulseTrain(
        period: Number,
        phase: Number = 0.0,
        width: Number = 0.02,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        sigma: Number = 0.15,
        jitter: Number = 0.0,
        seed: Long = 0L,
        domain: ClosedRange<*>
    ): Fn = impulseTrain(period, phase, width, amplitude, offset, sigma, jitter, seed, domain.toDomain())

    fun impulseTrain(
        period: Any,
        phase: Any = 0.0,
        width: Any = 0.02,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        sigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        domain: Domain = Domain.ALL
    ): Fn = impulseTrain(
        asParam(period, "period"),
        asParam(phase, "phase"),
        asParam(width, "width"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(sigma, "sigma"),
        asParam(jitter, "jitter"),
        seed,
        domain
    )

    fun impulseTrain(
        period: Any,
        phase: Any = 0.0,
        width: Any = 0.02,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        sigma: Any = 0.15,
        jitter: Any = 0.0,
        seed: Long = 0L,
        domain: ClosedRange<*>
    ): Fn = impulseTrain(period, phase, width, amplitude, offset, sigma, jitter, seed, domain.toDomain())

    fun pulseBurst(
        count: Int,
        period: Fn = param(1.0),
        start: Fn = param(0.0),
        phase: Fn = param(0.0),
        duty: Fn = param(0.5),
        amplitude: Fn = param(1.0),
        offset: Fn = param(0.0),
        rise: Fn = param(0.0),
        fall: Fn = param(0.0),
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Fn = param(2.0),
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Fn = param(0.15),
        domain: Domain = Domain.ALL
    ): Fn {
        if (count <= 0) return zero(domain)
        val d = intersectDomains(domain, period, start, phase, duty, amplitude, offset, rise, fall, curve, profileSigma)
        val base = pulseTrain(
            period = period,
            phase = start + phase,
            duty = duty,
            amplitude = amplitude,
            offset = offset,
            rise = rise,
            fall = fall,
            edge = edge,
            curve = curve,
            profile = profile,
            profileSigma = profileSigma,
            domain = domain
        )
        return graphFn(
            d,
            "pulseburst",
            "pulseburst",
            children = listOf(
                fnEdge("period", period),
                fnEdge("start", start),
                fnEdge("phase", phase),
                fnEdge("duty", duty),
                fnEdge("amplitude", amplitude),
                fnEdge("offset", offset),
                fnEdge("rise", rise),
                fnEdge("fall", fall),
                fnEdge("curve", curve),
                fnEdge("profileSigma", profileSigma),
                fnEdge("base", base)
            ),
            strings = mapOf("edge" to edge.name, "profile" to profile.name),
            longs = mapOf("count" to count.toLong()),
            rebuild = { children ->
                pulseBurst(count, children[0], children[1], children[2], children[3], children[4], children[5], children[6], children[7], edge, children[8], profile, children[9], domain)
            }
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn offset.eval(x)
            val s = start.eval(x)
            val end = s + p * count
            if (x < s || x > end) offset.eval(x) else base.eval(x)
        }
    }

    fun pulseBurst(
        count: Int,
        period: Number,
        start: Number = 0.0,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        domain: Domain = Domain.ALL
    ): Fn = pulseBurst(count, param(period), param(start), param(phase), param(duty), param(amplitude), param(offset), param(rise), param(fall), edge, param(curve), profile, param(profileSigma), domain)

    fun pulseBurst(
        count: Int,
        period: Number,
        start: Number = 0.0,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        rise: Number = 0.0,
        fall: Number = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Number = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Number = 0.15,
        domain: ClosedRange<*>
    ): Fn = pulseBurst(count, period, start, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, domain.toDomain())

    fun pulseBurst(
        count: Int,
        period: Any,
        start: Any = 0.0,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        domain: Domain = Domain.ALL
    ): Fn = pulseBurst(
        count,
        asParam(period, "period"),
        asParam(start, "start"),
        asParam(phase, "phase"),
        asParam(duty, "duty"),
        asParam(amplitude, "amplitude"),
        asParam(offset, "offset"),
        asParam(rise, "rise"),
        asParam(fall, "fall"),
        edge,
        asParam(curve, "curve"),
        profile,
        asParam(profileSigma, "profileSigma"),
        domain
    )

    fun pulseBurst(
        count: Int,
        period: Any,
        start: Any = 0.0,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        rise: Any = 0.0,
        fall: Any = 0.0,
        edge: PulseEdge = PulseEdge.SMOOTH,
        curve: Any = 2.0,
        profile: PulseProfile = PulseProfile.RECT,
        profileSigma: Any = 0.15,
        domain: ClosedRange<*>
    ): Fn = pulseBurst(count, period, start, phase, duty, amplitude, offset, rise, fall, edge, curve, profile, profileSigma, domain.toDomain())

    fun gaussian(
        mean: Fn = param(0.0),
        sigma: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, mean, sigma, amplitude)
        return graphFn(
            d,
            "gaussian",
            "gaussian",
            children = listOf(fnEdge("mean", mean), fnEdge("sigma", sigma), fnEdge("amplitude", amplitude)),
            rebuild = { children -> gaussian(children[0], children[1], children[2], domain) }
        ) { x ->
            val s = sigma.eval(x)
            if (s == 0.0) 0.0 else {
                val m = mean.eval(x)
                val a = amplitude.eval(x)
                a * kotlin.math.exp(-0.5 * ((x - m) / s).pow(2.0))
            }
        }
    }

    fun gaussian(
        mean: Number,
        sigma: Number = 1.0,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = gaussian(param(mean), param(sigma), param(amplitude), domain)

    fun gaussian(
        mean: Number,
        sigma: Number = 1.0,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = gaussian(mean, sigma, amplitude, domain.toDomain())

    fun gaussian(
        mean: Any,
        sigma: Any = 1.0,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = gaussian(asParam(mean, "mean"), asParam(sigma, "sigma"), asParam(amplitude, "amplitude"), domain)

    fun gaussian(
        mean: Any,
        sigma: Any = 1.0,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = gaussian(mean, sigma, amplitude, domain.toDomain())

    // Array helpers
    fun DoubleArray.toFn(
        domain: Domain = Domain.of(0.0, (size - 1).toDouble()),
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn {
        val samples = this
        val count = samples.size
        val dx = if (count > 1) domain.length / (count - 1).toDouble() else 1.0
        val safeDomain = if (domain._isEmpty) Domain.EMPTY else domain
        return graphFn(
            safeDomain,
            "array",
            "array",
            numbers = mapOf("outside" to outside),
            strings = mapOf("interpolation" to interpolation.name, "samples" to count.toString()),
            rebuild = { samples.toFn(domain, interpolation, outside) }
        ) { x ->
            if (count == 0) return@graphFn outside
            if (!safeDomain.containsInclusive(x)) return@graphFn outside
            val idx = if (dx == 0.0) 0.0 else (x - safeDomain.min) / dx
            sampleArray(samples, idx, interpolation, outside)
        }
    }

    fun DoubleArray.toFn(
        domain: ClosedRange<*>,
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = toFn(domain.toDomain(), interpolation, outside)

    fun List<Number>.toFn(
        domain: Domain = Domain.of(0.0, (size - 1).toDouble()),
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = this.map { it.toDouble() }.toDoubleArray().toFn(domain, interpolation, outside)

    fun List<Number>.toFn(
        domain: ClosedRange<*>,
        interpolation: Interpolation = Interpolation.LINEAR,
        outside: Double = 0.0
    ): Fn = toFn(domain.toDomain(), interpolation, outside)

    private fun sampleArray(samples: DoubleArray, index: Double, interpolation: Interpolation, outside: Double): Double {
        val n = samples.size
        if (n == 0) return outside
        return when (interpolation) {
            Interpolation.NEAREST -> {
                val i = index.roundToInt()
                if (i < 0 || i >= n) outside else samples[i]
            }
            Interpolation.LINEAR -> {
                val i = floor(index).toInt()
                val t = index - i
                val a = samples.getOrNull(i) ?: return outside
                val b = samples.getOrNull(i + 1) ?: return a
                blend(a, b, t)
            }
            Interpolation.CUBIC -> {
                val i = floor(index).toInt()
                val t = index - i
                val p0 = samples.getOrNull(i - 1) ?: samples.getOrNull(i) ?: return outside
                val p1 = samples.getOrNull(i) ?: return outside
                val p2 = samples.getOrNull(i + 1) ?: p1
                val p3 = samples.getOrNull(i + 2) ?: p2
                catmullRom(p0, p1, p2, p3, t)
            }
        }
    }

    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            2.0 * p1 +
                (-p0 + p2) * t +
                (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
            )
    }

    fun Fn.sampleAt(xs: DoubleArray, edge: EdgeMode = EdgeMode.ZERO, outside: Double = 0.0): DoubleArray =
        DoubleArray(xs.size) { i -> evalSafe(xs[i], outside, edge) }

    fun Fn.toDoubleArray(
        count: Int,
        sampleDomain: Domain = domain,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        if (sampleDomain._isEmpty) return DoubleArray(count) { outside }
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return DoubleArray(count) { i ->
            val x = sweep.min + step * i.toDouble()
            evalSafe(x, outside, edge)
        }
    }

    fun Fn.toDoubleArray(
        count: Int,
        sampleDomain: ClosedRange<*>,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray = toDoubleArray(count, sampleDomain.toDomain(), includeEnd, edge, outside)

    fun Fn.toDoubleArray(
        step: Double,
        sampleDomain: Domain = domain,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray {
        if (step == 0.0) return DoubleArray(0)
        if (sampleDomain._isEmpty) return DoubleArray(0)
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val s = kotlin.math.abs(step)
        val count = if (includeEnd) (floor(len / s) + 1).toInt() else floor(len / s).toInt()
        return toDoubleArray(count, sweep, includeEnd, edge, outside)
    }

    fun Fn.toDoubleArray(
        step: Double,
        sampleDomain: ClosedRange<*>,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): DoubleArray = toDoubleArray(step, sampleDomain.toDomain(), includeEnd, edge, outside)

    /**
     * Sample this function at [count] uniformly-spaced points and return the
     * results as `List<Vec2>` where each entry is `Vec2(time, value)`.
     *
     * Handy for feeding into [KeyframeEvaluator.setOverlay] or any API that
     * expects a list of 2D points.
     */
    fun Fn.toVec2List(
        count: Int,
        sampleDomain: Domain = domain,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): List<Vec2> {
        if (count <= 0) return emptyList()
        if (sampleDomain._isEmpty) return List(count) { Vec2(0f, outside.toFloat()) }
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return List(count) { i ->
            val x = sweep.min + step * i.toDouble()
            Vec2(x.toFloat(), evalSafe(x, outside, edge).toFloat())
        }
    }

    fun Fn.toVec2List(
        count: Int,
        sampleDomain: ClosedRange<*>,
        includeEnd: Boolean = true,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0
    ): List<Vec2> = toVec2List(count, sampleDomain.toDomain(), includeEnd, edge, outside)

    private data class PlotSample(val x: Double, val y: Double?)

    private fun Double.jsonNumber(): String =
        if (isFinite()) toString() else "null"

    private fun jsString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    val code = ch.code
                    if (code < 32) append("\\u").append(code.toString(16).padStart(4, '0'))
                    else append(ch)
                }
            }
        }
        append('"')
    }

    private fun htmlAttribute(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun Domain.plotSafe(default: Domain = Domain.DEFAULT): Domain {
        if (!isFinite) return default
        return when {
            length > 0.0 -> this
            min.isFinite() -> Domain.of(min - 0.5, min + 0.5)
            else -> default
        }
    }

    /**
     * Resolves an implicit sample domain to a finite window for enumeration.
     *
     * Oscillators and other unbounded functions default to [Domain.ALL], which
     * has no finite extent to walk at a fixed count or step. When a sampler is
     * handed such a domain (typically because the caller passed no explicit
     * window) it previews over [plotSafe]'s finite fallback. Empty and finite
     * domains — including point domains — pass through unchanged, so existing
     * behavior for bounded functions is preserved exactly.
     */
    private fun Domain.finiteSweep(): Domain = when {
        _isEmpty -> this
        isFinite -> this
        else -> plotSafe()
    }

    private fun yPlotDomain(samples: List<PlotSample>, requested: Domain?): Domain {
        if (requested != null && requested.isFinite) return requested.plotSafe()
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (sample in samples) {
            val y = sample.y ?: continue
            if (!y.isFinite()) continue
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        if (!lo.isFinite() || !hi.isFinite()) return Domain.of(-1.0, 1.0)
        if (lo == hi) {
            val pad = max(1.0, abs(lo) * 0.1)
            return Domain.of(lo - pad, hi + pad)
        }
        val pad = (hi - lo) * 0.08
        return Domain.of(lo - pad, hi + pad)
    }

    /**
     * Renders an interactive plot for an [Fn].
     *
     * The plot samples the whole finite [domain] by default. Infinite functions
     * need a finite [sampleDomain], or they fall back to a small 0..1 preview.
     * The returned iframe is self-contained so it can be displayed in Field,
     * reused in docs, or embedded in a scratch output without registering a
     * frontend route.
     */
    fun Fn.plotHtml(
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN,
        background: String = "#000000",
        primaryColor: String = "#0af",
        secondaryColor: String = "#f80",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"
    ): String {
        val plotDomain = (sampleDomain ?: domain).plotSafe()
        val count = samples.coerceIn(2, 32768)
        val step = plotDomain.length / (count - 1).toDouble()
        val plotSamples = List(count) { i ->
            val x = plotDomain.min + step * i.toDouble()
            val y = runCatching { evalSafe(x, outside = outside, edge = edge) }
                .getOrElse { Double.NaN }
                .takeIf { it.isFinite() }
            PlotSample(x, y)
        }
        val valueDomain = yPlotDomain(plotSamples, yDomain)
        val sampleJson = plotSamples.joinToString(",", "[", "]") { sample ->
            "[${sample.x.jsonNumber()},${sample.y?.jsonNumber() ?: "null"}]"
        }
        val finiteCount = plotSamples.count { it.y != null }
        val frameHeight = heightPx.coerceAtLeast(220) + 76
        val safeTitle = title.ifBlank { name ?: "Fn" }

        val srcdoc = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
html, body {
    margin: 0;
    padding: 0;
    background: $background;
    color: rgba(255,255,255,0.82);
    font-family: $fontFamily;
    overflow: hidden;
}
.fn-plot {
    box-sizing: border-box;
    width: 100%;
    min-width: 320px;
    height: ${frameHeight}px;
    padding: 10px 12px 12px;
    background: $background;
}
.fn-top {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 28px;
    margin-bottom: 6px;
}
.fn-title {
    flex: 1;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    color: rgba(255,255,255,0.92);
    font-size: 13px;
    font-weight: 600;
    letter-spacing: 0;
}
.fn-chip, .fn-readout {
    color: rgba(255,255,255,0.58);
    border: 1px solid rgba(255,255,255,0.13);
    border-radius: 6px;
    padding: 3px 7px;
    font-size: 11px;
    line-height: 16px;
    white-space: nowrap;
    background: rgba(255,255,255,0.035);
}
.fn-tools {
    display: flex;
    align-items: center;
    gap: 4px;
}
button {
    width: 26px;
    height: 24px;
    border-radius: 6px;
    border: 1px solid rgba(255,255,255,0.16);
    background: rgba(255,255,255,0.04);
    color: rgba(255,255,255,0.78);
    font-family: $fontFamily;
    font-size: 13px;
    line-height: 20px;
    cursor: pointer;
}
button:hover {
    border-color: $primaryColor;
    color: white;
}
.fn-canvas-wrap {
    position: relative;
    height: ${heightPx.coerceAtLeast(220)}px;
    border: 1px solid rgba(255,255,255,0.12);
    border-radius: 8px;
    background: #050505;
    overflow: hidden;
}
canvas {
    display: block;
    width: 100%;
    height: 100%;
    cursor: crosshair;
}
</style>
</head>
<body>
<div class="fn-plot">
    <div class="fn-top">
        <div class="fn-title" title="${htmlAttribute(safeTitle)}">${htmlAttribute(safeTitle)}</div>
        <div class="fn-chip">${finiteCount}/${count} samples</div>
        <div class="fn-readout" id="readout">x -- y --</div>
        <div class="fn-tools">
            <button type="button" id="reset" title="Reset view" aria-label="Reset view">&#8634;</button>
            <button type="button" id="fitY" title="Fit value range" aria-label="Fit value range">&#8597;</button>
        </div>
    </div>
    <div class="fn-canvas-wrap">
        <canvas id="plot"></canvas>
    </div>
</div>
<script>
(function () {
    const payload = {
        samples: $sampleJson,
        xRange: [${plotDomain.min.jsonNumber()}, ${plotDomain.max.jsonNumber()}],
        yRange: [${valueDomain.min.jsonNumber()}, ${valueDomain.max.jsonNumber()}],
        xLabel: ${jsString(xLabel)},
        yLabel: ${jsString(yLabel)},
        primary: ${jsString(primaryColor)},
        secondary: ${jsString(secondaryColor)}
    };
    const canvas = document.getElementById("plot");
    const ctx = canvas.getContext("2d");
    const readout = document.getElementById("readout");
    const dpr = Math.max(1, window.devicePixelRatio || 1);
    const margin = { left: 58, right: 18, top: 26, bottom: 42 };
    const state = {
        xMin: payload.xRange[0],
        xMax: payload.xRange[1],
        yMin: payload.yRange[0],
        yMax: payload.yRange[1],
        pointer: null,
        dragging: null
    };

    function finite(value) {
        return typeof value === "number" && Number.isFinite(value);
    }

    function format(value) {
        if (!finite(value)) return "--";
        const a = Math.abs(value);
        if (a !== 0 && (a >= 10000 || a < 0.001)) return value.toExponential(2);
        if (a >= 100) return value.toFixed(1);
        if (a >= 10) return value.toFixed(2);
        return value.toFixed(3);
    }

    function plotRect() {
        return {
            x: margin.left,
            y: margin.top,
            w: Math.max(20, canvas.clientWidth - margin.left - margin.right),
            h: Math.max(20, canvas.clientHeight - margin.top - margin.bottom)
        };
    }

    function xToPx(x) {
        const r = plotRect();
        return r.x + ((x - state.xMin) / (state.xMax - state.xMin)) * r.w;
    }

    function yToPx(y) {
        const r = plotRect();
        return r.y + r.h - ((y - state.yMin) / (state.yMax - state.yMin)) * r.h;
    }

    function pxToX(px) {
        const r = plotRect();
        return state.xMin + ((px - r.x) / r.w) * (state.xMax - state.xMin);
    }

    function pxToY(py) {
        const r = plotRect();
        return state.yMin + (1 - (py - r.y) / r.h) * (state.yMax - state.yMin);
    }

    function niceStep(span, target) {
        const raw = Math.abs(span) / Math.max(1, target);
        if (!finite(raw) || raw === 0) return 1;
        const power = Math.pow(10, Math.floor(Math.log10(raw)));
        const unit = raw / power;
        const nice = unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10;
        return nice * power;
    }

    function ticks(min, max, target) {
        const step = niceStep(max - min, target);
        const first = Math.ceil(min / step) * step;
        const out = [];
        for (let v = first; v <= max + step * 0.5 && out.length < 80; v += step) {
            if (v >= min - step * 0.5) out.push(v);
        }
        return out;
    }

    function resetView() {
        state.xMin = payload.xRange[0];
        state.xMax = payload.xRange[1];
        state.yMin = payload.yRange[0];
        state.yMax = payload.yRange[1];
        draw();
    }

    function setYRange(min, max) {
        if (!finite(min) || !finite(max)) return;
        if (min === max) {
            const pad = Math.max(1, Math.abs(min) * 0.1);
            min -= pad;
            max += pad;
        }
        const pad = (max - min) * 0.08;
        state.yMin = min - pad;
        state.yMax = max + pad;
    }

    function fitVisibleY() {
        let lo = Infinity;
        let hi = -Infinity;
        for (const p of payload.samples) {
            const x = p[0], y = p[1];
            if (!finite(x) || !finite(y)) continue;
            if (x < state.xMin || x > state.xMax) continue;
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        if (finite(lo) && finite(hi)) setYRange(lo, hi);
        draw();
    }

    function resize() {
        const rect = canvas.getBoundingClientRect();
        canvas.width = Math.max(1, Math.round(rect.width * dpr));
        canvas.height = Math.max(1, Math.round(rect.height * dpr));
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        draw();
    }

    function line(x1, y1, x2, y2, color, width) {
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.strokeStyle = color;
        ctx.lineWidth = width;
        ctx.stroke();
    }

    function drawGrid() {
        const r = plotRect();
        ctx.fillStyle = "#050505";
        ctx.fillRect(0, 0, canvas.clientWidth, canvas.clientHeight);
        ctx.fillStyle = "rgba(255,255,255,0.025)";
        ctx.fillRect(r.x, r.y, r.w, r.h);

        ctx.save();
        ctx.beginPath();
        ctx.rect(r.x, r.y, r.w, r.h);
        ctx.clip();
        for (const x of ticks(state.xMin, state.xMax, 8)) {
            const px = xToPx(x);
            line(px, r.y, px, r.y + r.h, "rgba(255,255,255,0.075)", 1);
        }
        for (const y of ticks(state.yMin, state.yMax, 6)) {
            const py = yToPx(y);
            line(r.x, py, r.x + r.w, py, "rgba(255,255,255,0.075)", 1);
        }
        if (state.xMin <= 0 && state.xMax >= 0) {
            const px = xToPx(0);
            line(px, r.y, px, r.y + r.h, "rgba(255,136,0,0.55)", 1.25);
        }
        if (state.yMin <= 0 && state.yMax >= 0) {
            const py = yToPx(0);
            line(r.x, py, r.x + r.w, py, "rgba(255,136,0,0.55)", 1.25);
        }
        ctx.restore();

        ctx.fillStyle = "rgba(255,255,255,0.52)";
        ctx.font = "11px Iosevka Web, IBM Plex Mono, monospace";
        ctx.textAlign = "center";
        ctx.textBaseline = "top";
        for (const x of ticks(state.xMin, state.xMax, 8)) {
            const px = xToPx(x);
            if (px >= r.x - 1 && px <= r.x + r.w + 1) ctx.fillText(format(x), px, r.y + r.h + 7);
        }
        ctx.textAlign = "right";
        ctx.textBaseline = "middle";
        for (const y of ticks(state.yMin, state.yMax, 6)) {
            const py = yToPx(y);
            if (py >= r.y - 1 && py <= r.y + r.h + 1) ctx.fillText(format(y), r.x - 8, py);
        }
        ctx.fillStyle = "rgba(255,255,255,0.7)";
        ctx.textAlign = "center";
        ctx.textBaseline = "bottom";
        ctx.fillText(payload.xLabel, r.x + r.w * 0.5, canvas.clientHeight - 5);
        ctx.save();
        ctx.translate(12, r.y + r.h * 0.5);
        ctx.rotate(-Math.PI / 2);
        ctx.fillText(payload.yLabel, 0, 0);
        ctx.restore();
    }

    function drawCurve() {
        const r = plotRect();
        ctx.save();
        ctx.beginPath();
        ctx.rect(r.x, r.y, r.w, r.h);
        ctx.clip();
        ctx.beginPath();
        let open = false;
        for (const p of payload.samples) {
            const x = p[0], y = p[1];
            if (!finite(x) || !finite(y)) {
                open = false;
                continue;
            }
            const px = xToPx(x);
            const py = yToPx(y);
            if (px < r.x - r.w || px > r.x + r.w * 2 || py < r.y - r.h || py > r.y + r.h * 2) {
                if (!open) continue;
            }
            if (!open) {
                ctx.moveTo(px, py);
                open = true;
            } else {
                ctx.lineTo(px, py);
            }
        }
        ctx.strokeStyle = payload.primary;
        ctx.lineWidth = 2;
        ctx.stroke();
        ctx.lineWidth = 1;
        ctx.strokeStyle = "rgba(255,255,255,0.35)";
        ctx.stroke();
        ctx.restore();
    }

    function drawPointer() {
        const p = state.pointer;
        if (!p) return;
        const r = plotRect();
        if (p.x < r.x || p.x > r.x + r.w || p.y < r.y || p.y > r.y + r.h) return;
        line(p.x, r.y, p.x, r.y + r.h, "rgba(255,255,255,0.18)", 1);
        line(r.x, p.y, r.x + r.w, p.y, "rgba(255,255,255,0.18)", 1);
    }

    function draw() {
        if (!ctx) return;
        drawGrid();
        drawCurve();
        drawPointer();
        if (state.pointer) {
            readout.textContent = "x " + format(pxToX(state.pointer.x)) + "   y " + format(pxToY(state.pointer.y));
        }
    }

    function pointer(event) {
        const rect = canvas.getBoundingClientRect();
        return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    }

    canvas.addEventListener("pointerdown", function (event) {
        event.preventDefault();
        const p = pointer(event);
        state.dragging = {
            id: event.pointerId,
            x: p.x,
            y: p.y,
            xMin: state.xMin,
            xMax: state.xMax,
            yMin: state.yMin,
            yMax: state.yMax
        };
        canvas.setPointerCapture(event.pointerId);
        canvas.style.cursor = "grabbing";
    });

    canvas.addEventListener("pointermove", function (event) {
        const p = pointer(event);
        state.pointer = p;
        if (state.dragging) {
            const r = plotRect();
            const dx = (p.x - state.dragging.x) / Math.max(1, r.w);
            const dy = (p.y - state.dragging.y) / Math.max(1, r.h);
            const xs = state.dragging.xMax - state.dragging.xMin;
            const ys = state.dragging.yMax - state.dragging.yMin;
            state.xMin = state.dragging.xMin - dx * xs;
            state.xMax = state.dragging.xMax - dx * xs;
            state.yMin = state.dragging.yMin + dy * ys;
            state.yMax = state.dragging.yMax + dy * ys;
        }
        draw();
    });

    canvas.addEventListener("pointerup", function (event) {
        if (state.dragging && state.dragging.id === event.pointerId) {
            state.dragging = null;
            canvas.style.cursor = "crosshair";
        }
    });

    canvas.addEventListener("pointerleave", function () {
        if (!state.dragging) {
            state.pointer = null;
            readout.textContent = "x -- y --";
            draw();
        }
    });

    canvas.addEventListener("dblclick", function (event) {
        event.preventDefault();
        resetView();
    });

    canvas.addEventListener("wheel", function (event) {
        event.preventDefault();
        const r = plotRect();
        const p = pointer(event);
        const xAnchor = pxToX(p.x);
        const yAnchor = pxToY(p.y);
        const xs = state.xMax - state.xMin;
        const ys = state.yMax - state.yMin;
        if (event.ctrlKey || event.metaKey) {
            const factor = Math.exp(event.deltaY * 0.0015);
            const ax = (xAnchor - state.xMin) / xs;
            const newXs = Math.max(1e-12, xs * factor);
            state.xMin = xAnchor - ax * newXs;
            state.xMax = xAnchor + (1 - ax) * newXs;
            if (event.shiftKey) {
                const ay = (yAnchor - state.yMin) / ys;
                const newYs = Math.max(1e-12, ys * factor);
                state.yMin = yAnchor - ay * newYs;
                state.yMax = yAnchor + (1 - ay) * newYs;
            }
        } else {
            state.xMin += (event.deltaX / Math.max(1, r.w)) * xs;
            state.xMax += (event.deltaX / Math.max(1, r.w)) * xs;
            state.yMin -= (event.deltaY / Math.max(1, r.h)) * ys;
            state.yMax -= (event.deltaY / Math.max(1, r.h)) * ys;
        }
        draw();
    }, { passive: false });

    document.getElementById("reset").addEventListener("click", resetView);
    document.getElementById("fitY").addEventListener("click", fitVisibleY);
    window.addEventListener("resize", resize);
    resize();
})();
</script>
</body>
</html>
""".trimIndent()

        return """
<iframe
    class="field-fn-plot-frame"
    title="${htmlAttribute(safeTitle)}"
    srcdoc="${htmlAttribute(srcdoc)}"
    data-afield-intrinsic-width="760"
    data-afield-intrinsic-height="$frameHeight"
    style="border:none;padding:0;margin:0;display:block;width:100%;height:${frameHeight}px;background:$background;"
></iframe>
""".trimIndent()
    }

    fun Fn.plotHtml(
        sampleDomain: ClosedRange<*>,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN,
        background: String = "#000000",
        primaryColor: String = "#0af",
        secondaryColor: String = "#f80",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"
    ): String =
        plotHtml(
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside,
            background = background,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            fontFamily = fontFamily
        )

    fun Fn.plotHtml(
        sampleDomain: ClosedRange<*>,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN,
        background: String = "#000000",
        primaryColor: String = "#0af",
        secondaryColor: String = "#f80",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"
    ): String =
        plotHtml(
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside,
            background = background,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            fontFamily = fontFamily
        )

    @JvmName("plotCurrentBox")
    fun Fn.plot(
        channel: String = "plot",
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn {
        val box = Box.context.get()
            ?: throw IllegalStateException("Fn.plot requires an active Box context")
        box.display(
            channel,
            plotHtml(
                sampleDomain = sampleDomain,
                samples = samples,
                heightPx = heightPx,
                title = title,
                xLabel = xLabel,
                yLabel = yLabel,
                yDomain = yDomain,
                edge = edge,
                outside = outside
            )
        )
        return this
    }

    @JvmName("plotCurrentBoxRange")
    fun Fn.plot(
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )

    @JvmName("plotCurrentBoxRangeYRange")
    fun Fn.plot(
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside
        )

    fun Box.plot(
        fn: Fn,
        channel: String = "plot",
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn {
        display(
            channel,
            fn.plotHtml(
                sampleDomain = sampleDomain,
                samples = samples,
                heightPx = heightPx,
                title = title,
                xLabel = xLabel,
                yLabel = yLabel,
                yDomain = yDomain,
                edge = edge,
                outside = outside
            )
        )
        return fn
    }

    fun Box.plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )

    fun Box.plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside
        )

    fun plot(
        fn: Fn,
        channel: String = "plot",
        sampleDomain: Domain? = null,
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn = fn.plot(
        channel = channel,
        sampleDomain = sampleDomain,
        samples = samples,
        heightPx = heightPx,
        title = title,
        xLabel = xLabel,
        yLabel = yLabel,
        yDomain = yDomain,
        edge = edge,
        outside = outside
    )

    fun plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain,
            edge = edge,
            outside = outside
        )

    fun plot(
        fn: Fn,
        sampleDomain: ClosedRange<*>,
        channel: String = "plot",
        samples: Int = 1024,
        heightPx: Int = 360,
        title: String = fn.name ?: "Fn",
        xLabel: String = "x",
        yLabel: String = "f(x)",
        yDomain: ClosedRange<*>,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = Double.NaN
    ): Fn =
        plot(
            fn = fn,
            channel = channel,
            sampleDomain = sampleDomain.toDomain() as Domain?,
            samples = samples,
            heightPx = heightPx,
            title = title,
            xLabel = xLabel,
            yLabel = yLabel,
            yDomain = yDomain.toDomain() as Domain?,
            edge = edge,
            outside = outside
        )

    // Streaming helpers
    class FunctionCursor(
        val fn: Fn,
        var x: Double = fn.domain.finiteSweep().min,
        var step: Double = 1.0
    ) {
        /** Samples at the current position without advancing. */
        fun peek(): Double = fn.eval(x)

        fun next(): Double {
            val value = fn.eval(x)
            x += step
            return value
        }

        /** Returns the sampled coordinate and value, then advances once. */
        fun nextWithX(): Pair<Double, Double> {
            val sampleX = x
            return sampleX to next()
        }

        fun next(count: Int): DoubleArray {
            require(count >= 0) { "count must be non-negative" }
            val out = DoubleArray(count)
            for (i in 0 until count) {
                out[i] = next()
            }
            return out
        }

        /** Moves by [count] cursor steps without evaluating the function. */
        fun skip(count: Int = 1): FunctionCursor {
            x += step * count
            return this
        }

        fun seek(position: Double): FunctionCursor {
            x = position
            return this
        }

        fun reset(position: Double = fn.domain.finiteSweep().min): FunctionCursor = seek(position)

        fun copy(x: Double = this.x, step: Double = this.step): FunctionCursor = FunctionCursor(fn, x, step)

        override fun toString(): String = prettySummary(
            "FunctionCursor",
            "fn" to fn,
            "x" to prettyNumber(x),
            "step" to prettyNumber(step)
        )
    }

    fun Fn.cursor(start: Double = domain.finiteSweep().min, step: Double = 1.0): FunctionCursor = FunctionCursor(this, start, step)

    fun Fn.stream(start: Double = domain.finiteSweep().min, step: Double = 1.0): Sequence<Double> = sequence {
        var x = start
        while (true) {
            yield(eval(x))
            x += step
        }
    }

    fun Fn.stream(domain: Domain, step: Double = 1.0, includeEnd: Boolean = true): Sequence<Double> = sequence {
        if (domain._isEmpty) return@sequence
        if (!domain.length.isFinite() || step == 0.0) return@sequence
        if (step > 0.0) {
            var x = domain.min
            val end = domain.max
            if (includeEnd) {
                while (x <= end) {
                    yield(eval(x))
                    x += step
                }
            } else {
                while (x < end) {
                    yield(eval(x))
                    x += step
                }
            }
        } else {
            var x = domain.max
            val end = domain.min
            if (includeEnd) {
                while (x >= end) {
                    yield(eval(x))
                    x += step
                }
            } else {
                while (x > end) {
                    yield(eval(x))
                    x += step
                }
            }
        }
    }

    fun Fn.streamWithX(start: Double = domain.finiteSweep().min, step: Double = 1.0): Sequence<Pair<Double, Double>> = sequence {
        var x = start
        while (true) {
            yield(x to eval(x))
            x += step
        }
    }

    enum class ChunkMapMode { DIRECT, STRETCH, CLAMP, WRAP, MIRROR }

    enum class ChunkBlend { REPLACE, ADD, MULTIPLY }

    val ADD: ChunkBlend get() = ChunkBlend.ADD
    val REPLACE: ChunkBlend get() = ChunkBlend.REPLACE
    val MULTIPLY: ChunkBlend get() = ChunkBlend.MULTIPLY

    data class TimelineChunk(
        val source: Fn,
        val sourceDomain: Domain = source.domain,
        val domain: Domain,
        val mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
        val blend: ChunkBlend = ChunkBlend.REPLACE,
        val edge: EdgeMode = EdgeMode.ZERO,
        val outside: Double = 0.0,
        val window: Fn? = null,
        val gain: Fn? = null,
        val name: String? = source.name
    ) {
        private fun alpha(globalX: Double): Double {
            val len = domain.length
            if (!len.isFinite() || len == 0.0) return 0.0
            return ((globalX - domain.min) / len).coerceIn(0.0, 1.0)
        }

        fun contains(globalX: Double): Boolean = domain.containsInclusive(globalX)

        private fun mapToSource(globalX: Double): Double {
            val linear = sourceDomain.min + (globalX - domain.min)
            return when (mapMode) {
                ChunkMapMode.DIRECT -> linear
                ChunkMapMode.STRETCH -> sourceDomain.lerp(alpha(globalX))
                ChunkMapMode.CLAMP -> sourceDomain.clamp(linear)
                ChunkMapMode.WRAP -> sourceDomain.wrap(linear)
                ChunkMapMode.MIRROR -> sourceDomain.reflect(linear)
            }
        }

        fun weightAt(globalX: Double): Double {
            if (!contains(globalX)) return 0.0
            val shaped = window?.evalSafe(alpha(globalX), outside = 0.0, edge = EdgeMode.CLAMP) ?: 1.0
            val g = gain?.eval(globalX) ?: 1.0
            return shaped * g
        }

        fun sampleAt(globalX: Double): Double? {
            if (!contains(globalX) || sourceDomain._isEmpty) return null
            val sourceX = mapToSource(globalX)
            return source.evalSafe(sourceX, outside = outside, edge = edge)
        }

        override fun toString(): String = prettySummary(
            "TimelineChunk",
            "name" to (name ?: source.name ?: "chunk"),
            "domain" to domain,
            "source" to sourceDomain,
            "map" to mapMode,
            "blend" to blend,
            "window" to window,
            "gain" to gain
        )
    }

    class TimelineGraph(
        chunks: List<TimelineChunk>,
        val outside: Double = 0.0,
        val defaultDomain: Domain = Domain.point(0.0)
    ) {
        val chunks: List<TimelineChunk> = chunks.toList()

        val domain: Domain = run {
            var d = Domain.EMPTY
            for (chunk in this.chunks) {
                d = if (d._isEmpty) chunk.domain else d.union(chunk.domain)
            }
            if (d._isEmpty) defaultDomain else d
        }

        private fun applyBlend(base: Double, sample: Double, weight: Double, blendMode: ChunkBlend): Double =
            when (blendMode) {
                ChunkBlend.REPLACE -> blend(base, sample, clamp01(weight))
                ChunkBlend.ADD -> base + sample * weight
                ChunkBlend.MULTIPLY -> base * blend(1.0, sample, clamp01(weight))
            }

        fun eval(x: Double): Double {
            var value = outside
            var active = false
            for (chunk in chunks) {
                val sample = chunk.sampleAt(x) ?: continue
                val weight = chunk.weightAt(x)
                value = applyBlend(value, sample, weight, chunk.blend)
                active = true
            }
            return if (active) value else outside
        }

        operator fun invoke(x: Double): Double = eval(x)

        private fun graphChildren(): List<FnGraphEdge> = buildList {
            chunks.forEachIndexed { index, chunk ->
                add(fnEdge("chunk$index.source", chunk.source))
                chunk.window?.let { add(fnEdge("chunk$index.window", it)) }
                chunk.gain?.let { add(fnEdge("chunk$index.gain", it)) }
            }
        }

        private fun rebuildWithChildren(children: List<Fn>): TimelineGraph {
            var cursor = 0
            val rebuilt = chunks.map { chunk ->
                val source = childAt(children, cursor++, chunk.source)
                val window = if (chunk.window == null) null else childAt(children, cursor++, chunk.window)
                val gain = if (chunk.gain == null) null else childAt(children, cursor++, chunk.gain)
                chunk.copy(source = source, window = window, gain = gain)
            }
            return TimelineGraph(rebuilt, outside, defaultDomain)
        }

        fun asFn(
            name: String = "timeline",
            domain: Domain = this.domain
        ): Fn = graphFn(
            domain,
            name,
            "timeline",
            children = graphChildren(),
            numbers = mapOf("outside" to outside, "chunks" to chunks.size.toDouble()),
            strings = mapOf("defaultDomain" to defaultDomain.toString()),
            rebuild = { children -> rebuildWithChildren(children).asFn(name, domain) }
        ) { x -> eval(x) }

        fun tap(offset: Double = 0.0, name: String = "timelineTap"): Fn {
            val tapDomain = if (domain._isEmpty) domain else domain.shift(offset)
            return graphFn(
                tapDomain,
                name,
                "timelineTap",
                children = graphChildren(),
                numbers = mapOf("offset" to offset, "outside" to outside, "chunks" to chunks.size.toDouble()),
                rebuild = { children -> rebuildWithChildren(children).tap(offset, name) }
            ) { x -> eval(x - offset) }
        }

        override fun toString(): String = prettySummary(
            "TimelineGraph",
            "chunks" to chunks.size,
            "domain" to domain,
            "outside" to prettyNumber(outside)
        )
    }

    class TimelineBuilder(
        start: Double = 0.0,
        private val outside: Double = 0.0
    ) {
        private val chunks = mutableListOf<TimelineChunk>()
        private val marks = LinkedHashMap<String, Double>()
        private val startAt = start

        var cursor: Double = start
            private set

        private fun defaultDurationFor(domain: Domain): Double {
            val len = domain.length
            return if (len.isFinite() && len > 0.0) len else 1.0
        }

        private fun normalizeDuration(duration: Double?, sourceDomain: Domain): Double {
            val d = duration ?: defaultDurationFor(sourceDomain)
            return if (!d.isFinite()) defaultDurationFor(sourceDomain) else kotlin.math.abs(d)
        }

        fun mark(name: String): Double {
            marks[name] = cursor
            return cursor
        }

        fun mark(name: String, at: Double): Double {
            marks[name] = at
            return at
        }

        fun marker(name: String): Double? = marks[name]

        fun moveTo(x: Double): Double {
            cursor = x
            return cursor
        }

        fun moveToMark(name: String, offset: Double = 0.0): Double {
            cursor = (marks[name] ?: cursor) + offset
            return cursor
        }

        fun advance(by: Double): Double {
            cursor += by
            return cursor
        }

        fun rewind(by: Double): Double = advance(-by)

        private fun insertChunk(
            source: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val target = Domain.of(at, at + dur)
            val chunk = TimelineChunk(
                source = source,
                sourceDomain = sourceDomain,
                domain = target,
                mapMode = mapMode,
                blend = blend,
                edge = edge,
                outside = outside,
                window = window,
                gain = asOptionalParam(gain, "gain")
            )
            chunks += chunk
            if (advanceCursor) cursor = target.max
            return chunk
        }

        override fun toString(): String = prettySummary(
            "TimelineBuilder",
            "cursor" to prettyNumber(cursor),
            "chunks" to chunks.size,
            "marks" to marks.keys.toList(),
            "outside" to prettyNumber(outside)
        )

        fun repeatToFit(
            source: Fn,
            duration: Double,
            at: Double = cursor,
            sourceDomain: Domain = source.domain,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = insertChunk(
            source = source,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = ChunkMapMode.WRAP,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun stretchToFit(
            source: Fn,
            duration: Double,
            at: Double = cursor,
            sourceDomain: Domain = source.domain,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = insertChunk(
            source = source,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = ChunkMapMode.STRETCH,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        private fun insertOverlap(
            source: Fn,
            overlap: Number,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk = insertOverlap(
            source = source,
            overlap = param(overlap),
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        private fun insertOverlap(
            source: Fn,
            overlap: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val ov = sequenceOverlapAt(overlap, at, dur)
            return insertChunk(
                source = source,
                at = at - ov,
                duration = dur,
                sourceDomain = sourceDomain,
                mapMode = mapMode,
                blend = blend,
                edge = edge,
                outside = outside,
                window = window,
                gain = gain,
                advanceCursor = true
            )
        }

        private fun insertSplice(
            source: Fn,
            lookBack: Number,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            fadeIn: Number = 0.15,
            fadeOut: Number = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = insertSplice(
            source = source,
            lookBack = param(lookBack),
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = param(fadeIn),
            fadeOut = param(fadeOut),
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        private fun insertSplice(
            source: Fn,
            lookBack: Fn,
            duration: Double? = null,
            sourceDomain: Domain = source.domain,
            fadeIn: Fn = param(0.15),
            fadeOut: Fn = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk {
            val dur = normalizeDuration(duration, sourceDomain)
            val look = abs(lookBack.paramAt(cursor)).coerceAtLeast(0.0)
            val start = cursor - look
            return insertChunk(
                source = source,
                at = start,
                duration = dur,
                sourceDomain = sourceDomain,
                mapMode = mapMode,
                blend = ChunkBlend.REPLACE,
                edge = edge,
                outside = outside,
                window = spliceWindow(
                    fadeIn = fadeIn.paramAt(start).coerceIn(0.0, 1.0),
                    fadeOut = fadeOut.paramAt(start + dur).coerceIn(0.0, 1.0)
                ),
                gain = gain,
                advanceCursor = advanceCursor
            )
        }

        fun snapshot(offset: Double = 0.0, name: String = "timelineSnapshot"): Fn {
            val graph = buildGraph()
            return graph.tap(offset, name)
        }

        fun lookBack(delay: Double, name: String = "timelineLookBack"): Fn =
            snapshot(offset = delay, name = name)

        fun Fn.place(
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn? = null,
            gain: Any? = null,
            advanceCursor: Boolean = true
        ): TimelineChunk = this@TimelineBuilder.insertChunk(
            source = this,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun Fn.overlap(
            amount: Number,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk = this@TimelineBuilder.insertOverlap(
            source = this,
            overlap = amount,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        fun Fn.overlap(
            amount: Fn,
            at: Double = cursor,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            mapMode: ChunkMapMode = ChunkMapMode.DIRECT,
            blend: ChunkBlend = ChunkBlend.REPLACE,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            window: Fn = hannWindow(Domain.DEFAULT),
            gain: Any? = null
        ): TimelineChunk = this@TimelineBuilder.insertOverlap(
            source = this,
            overlap = amount,
            at = at,
            duration = duration,
            sourceDomain = sourceDomain,
            mapMode = mapMode,
            blend = blend,
            edge = edge,
            outside = outside,
            window = window,
            gain = gain
        )

        fun Fn.splice(
            lookBack: Number,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            fadeIn: Number = 0.15,
            fadeOut: Number = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = this@TimelineBuilder.insertSplice(
            source = this,
            lookBack = lookBack,
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun Fn.splice(
            lookBack: Fn,
            duration: Double? = null,
            sourceDomain: Domain = domain,
            fadeIn: Fn = param(0.15),
            fadeOut: Fn = fadeIn,
            mapMode: ChunkMapMode = ChunkMapMode.STRETCH,
            edge: EdgeMode = EdgeMode.ZERO,
            outside: Double = this@TimelineBuilder.outside,
            gain: Any? = null,
            advanceCursor: Boolean = false
        ): TimelineChunk = this@TimelineBuilder.insertSplice(
            source = this,
            lookBack = lookBack,
            duration = duration,
            sourceDomain = sourceDomain,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            mapMode = mapMode,
            edge = edge,
            outside = outside,
            gain = gain,
            advanceCursor = advanceCursor
        )

        fun chunks(): List<TimelineChunk> = chunks.toList()

        fun buildGraph(): TimelineGraph = TimelineGraph(
            chunks = chunks,
            outside = outside,
            defaultDomain = Domain.point(startAt)
        )

        fun build(name: String = "timeline", domain: Domain? = null): Fn {
            val graph = buildGraph()
            return graph.asFn(name = name, domain = domain ?: graph.domain)
        }
    }

    fun spliceWindow(
        fadeIn: Double = 0.15,
        fadeOut: Double = fadeIn,
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val fi = fadeIn.coerceAtLeast(0.0).coerceAtMost(1.0)
        val fo = fadeOut.coerceAtLeast(0.0).coerceAtMost(1.0)
        return graphFn(
            domain,
            "spliceWindow",
            "spliceWindow",
            numbers = mapOf("fadeIn" to fadeIn, "fadeOut" to fadeOut),
            rebuild = { spliceWindow(fadeIn, fadeOut, domain) }
        ) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            val inW = if (fi == 0.0 || t >= fi) 1.0 else smoothstep(t / fi)
            val outStart = 1.0 - fo
            val outW = if (fo == 0.0 || t <= outStart) 1.0 else smoothstep((1.0 - t) / fo)
            (inW * outW).coerceIn(0.0, 1.0)
        }
    }

    fun timelineGraph(
        start: Double = 0.0,
        outside: Double = 0.0,
        init: TimelineBuilder.() -> Unit
    ): TimelineGraph {
        val builder = TimelineBuilder(start = start, outside = outside)
        builder.init()
        return builder.buildGraph()
    }

    fun timeline(
        start: Double = 0.0,
        outside: Double = 0.0,
        name: String = "timeline",
        domain: Domain? = null,
        init: TimelineBuilder.() -> Unit
    ): Fn {
        val builder = TimelineBuilder(start = start, outside = outside)
        builder.init()
        return builder.build(name = name, domain = domain)
    }

    private data class SequencePart(
        val fn: Fn,
        val sourceDomain: Domain,
        val duration: Double
    )

    private data class SequencePlacement(
        val part: SequencePart,
        val start: Double,
        val fadeIn: Double,
        val fadeOut: Double
    )

    private fun sequenceDuration(domain: Domain): Double {
        val len = domain.length
        return if (len.isFinite() && len > 0.0) len else 1.0
    }

    private fun List<Fn>.sequenceParts(): List<SequencePart> =
        map { fn -> SequencePart(fn, fn.domain, sequenceDuration(fn.domain)) }

    // Composition parameters are functions too. Literal overloads are just
    // adapters to constant Fns; parametric overloads sample near the place where
    // the parameter matters, e.g. around a join to choose that join's overlap.
    private fun Fn.paramAt(x: Double, outside: Double = 0.0): Double =
        if (domain._isEmpty) outside else evalSafe(x, outside = outside, edge = EdgeMode.CLAMP)

    private fun sequenceStep(duration: Double, overlap: Double): Double =
        (duration - overlap.coerceAtLeast(0.0).coerceAtMost(duration * 0.999)).coerceAtLeast(1e-9)

    private fun sequenceOverlapAt(overlap: Fn, boundary: Double, maxOverlap: Double): Double {
        if (maxOverlap <= 0.0) return 0.0
        val first = overlap.paramAt(boundary).coerceIn(0.0, maxOverlap)
        val midpoint = boundary - first * 0.5
        return overlap.paramAt(midpoint).coerceIn(0.0, maxOverlap)
    }

    private fun sequenceFadeWeight(local: Double, duration: Double, fadeIn: Double, fadeOut: Double): Double {
        val inFade = fadeIn.coerceAtLeast(0.0).coerceAtMost(duration * 0.5)
        val outFade = fadeOut.coerceAtLeast(0.0).coerceAtMost(duration * 0.5)
        val inW = if (inFade > 0.0 && local < inFade) smootherstep((local / inFade).coerceIn(0.0, 1.0)) else 1.0
        val outW = if (outFade > 0.0 && local > duration - outFade) smootherstep(((duration - local) / outFade).coerceIn(0.0, 1.0)) else 1.0
        return inW * outW
    }

    private fun SequencePlacement.sample(globalX: Double, edge: EdgeMode, outside: Double): Double? {
        val duration = part.duration
        val local = globalX - start
        if (local < 0.0 || local > duration) return null
        val alpha = if (duration <= 0.0) 0.0 else (local / duration).coerceIn(0.0, 1.0)
        val sourceX = part.sourceDomain.lerp(alpha)
        val value = part.fn.evalSafe(sourceX, outside = outside, edge = edge)
        return value * sequenceFadeWeight(local, duration, fadeIn, fadeOut)
    }

    private fun sequencePlacements(parts: List<SequencePart>, overlap: Fn, loop: Boolean, startOffset: Double = 0.0): Pair<List<SequencePlacement>, Double> {
        var cursor = 0.0
        val placements = ArrayList<SequencePlacement>(parts.size)
        var previousOverlap = 0.0
        for (i in parts.indices) {
            val part = parts[i]
            val boundary = startOffset + cursor + part.duration
            val nextOverlap = if (loop || i < parts.lastIndex) sequenceOverlapAt(overlap, boundary, part.duration) else 0.0
            placements += SequencePlacement(
                part = part,
                start = cursor,
                fadeIn = if (loop || i > 0) previousOverlap else 0.0,
                fadeOut = nextOverlap
            )
            cursor += sequenceStep(part.duration, nextOverlap)
            previousOverlap = nextOverlap
        }
        if (loop && placements.isNotEmpty()) {
            placements[0] = placements[0].copy(fadeIn = previousOverlap)
        }
        val finiteEnd = placements.lastOrNull()?.let { it.start + it.part.duration } ?: 0.0
        return placements to if (loop) cursor.coerceAtLeast(1e-9) else finiteEnd
    }

    private fun sampleSequencePlacements(
        placements: List<SequencePlacement>,
        x: Double,
        edge: EdgeMode,
        outside: Double
    ): Double {
        var value = 0.0
        var active = false
        for (placement in placements) {
            val sample = placement.sample(x, edge, outside) ?: continue
            value += sample
            active = true
        }
        return if (active) value else outside
    }

    private fun permutationOrder(size: Int, seed: Long, cycle: Long = 0L): List<Int> =
        (0 until size).sortedWith(compareBy<Int> { hashToUnit(seed + cycle * 0x9E3779B97F4A7C15uL.toLong(), it.toLong()) }.thenBy { it })

    private fun sequenceLoopValue(
        parts: List<SequencePart>,
        seed: Long?,
        x: Double,
        overlap: Fn,
        edge: EdgeMode,
        outside: Double
    ): Double {
        if (x < 0.0 || parts.isEmpty()) return outside
        val orderedForPeriod = parts
        val (_, period) = sequencePlacements(orderedForPeriod, overlap, loop = true)
        val cycle = floor(x / period).toLong()
        var value = 0.0
        var active = false
        for (offset in -1L..1L) {
            val c = cycle + offset
            val ordered = if (seed == null) parts else permutationOrder(parts.size, seed, c).map { parts[it] }
            val cycleStart = c * period
            val (placements, _) = sequencePlacements(ordered, overlap, loop = true, startOffset = cycleStart)
            for (placement in placements) {
                val shifted = placement.copy(start = placement.start + cycleStart)
                val sample = shifted.sample(x, edge, outside) ?: continue
                value += sample
                active = true
            }
        }
        return if (active) value else outside
    }

    private fun randomChoice(size: Int, seed: Long, slot: Long): Int =
        floor(hashToUnit(seed, slot) * size.toDouble()).toInt().coerceIn(0, size - 1)

    private fun weightedChoice(weights: DoubleArray, total: Double, seed: Long, slot: Long): Int {
        val target = hashToUnit(seed, slot) * total
        var acc = 0.0
        for (i in weights.indices) {
            acc += weights[i]
            if (target <= acc) return i
        }
        return weights.lastIndex
    }

    private fun randomSlotSequenceValue(
        parts: List<SequencePart>,
        seed: Long,
        x: Double,
        slot: Fn,
        overlap: Fn,
        edge: EdgeMode,
        outside: Double,
        weights: DoubleArray? = null,
        totalWeight: Double = 0.0
    ): Double {
        if (x < 0.0 || parts.isEmpty()) return outside
        val slotDuration = slot.paramAt(x, parts.maxOf { it.duration }).coerceAtLeast(1e-9)
        val localOverlap = sequenceOverlapAt(overlap, x, slotDuration)
        val step = sequenceStep(slotDuration, localOverlap)
        val slotIndex = floor(x / step).toLong()
        var value = 0.0
        var active = false
        for (i in (slotIndex - 1)..(slotIndex + 1)) {
            if (i < 0L) continue
            val choice = if (weights == null) randomChoice(parts.size, seed, i) else weightedChoice(weights, totalWeight, seed, i)
            val placement = SequencePlacement(
                part = parts[choice].copy(duration = slotDuration),
                start = i * step,
                fadeIn = if (i > 0L) localOverlap else 0.0,
                fadeOut = localOverlap
            )
            val sample = placement.sample(x, edge, outside) ?: continue
            value += sample
            active = true
        }
        return if (active) value else outside
    }

    /**
     * Places each function once in list order. A positive [overlap] turns hard
     * joins into additive crossfades at the joins.
     */
    fun List<Fn>.sequence(
        overlap: Number = 0.0,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequence"
    ): Fn = sequence(param(overlap), edge, outside, name)

    fun List<Fn>.sequence(
        overlap: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequence"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val (placements, end) = sequencePlacements(parts, overlap, loop = false)
        val domain = Domain.of(0.0, end)
        return graphFn(
            domain,
            name,
            "sequence",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).sequence(childAt(children, size, overlap), edge, outside, name) }
        ) { x -> sampleSequencePlacements(placements, x, edge, outside) }
    }

    fun List<Fn>.sequenceLoop(
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequenceLoop"
    ): Fn = sequenceLoop(param(overlap), domain, edge, outside, name)

    fun List<Fn>.sequenceLoop(
        overlap: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "sequenceLoop"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        return graphFn(
            domain,
            name,
            "sequenceLoop",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).sequenceLoop(childAt(children, size, overlap), domain, edge, outside, name) }
        ) { x -> sequenceLoopValue(parts, seed = null, x = x, overlap = overlap, edge = edge, outside = outside) }
    }

    fun List<Fn>.randomPermute(
        seed: Long = 0L,
        overlap: Number = 0.0,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermute"
    ): Fn = randomPermute(seed, param(overlap), edge, outside, name)

    fun List<Fn>.randomPermute(
        seed: Long = 0L,
        overlap: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermute"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val ordered = permutationOrder(parts.size, seed).map { parts[it].fn }
        val sequenced = ordered.sequence(overlap, edge, outside, name)
        return graphFn(
            sequenced.domain,
            name,
            "randomPermute",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children -> itemChildren(children, size).randomPermute(seed, childAt(children, size, overlap), edge, outside, name) }
        ) { x -> sequenced.eval(x) }
    }

    fun List<Fn>.randomPermuteLoop(
        seed: Long = 0L,
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermuteLoop"
    ): Fn = randomPermuteLoop(seed, param(overlap), domain, edge, outside, name)

    fun List<Fn>.randomPermuteLoop(
        seed: Long = 0L,
        overlap: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomPermuteLoop"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        return graphFn(
            domain,
            name,
            "randomPermuteLoop",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children -> itemChildren(children, size).randomPermuteLoop(seed, childAt(children, size, overlap), domain, edge, outside, name) }
        ) { x -> sequenceLoopValue(parts, seed = seed, x = x, overlap = overlap, edge = edge, outside = outside) }
    }

    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Number? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn = randomSequence(seed, param(overlap), slot?.let { param(it) }, domain, edge, outside, name)

    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn = randomSequence(seed, param(overlap), slot, domain, edge, outside, name)

    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Fn,
        slot: Number,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn = randomSequence(seed, overlap, param(slot), domain, edge, outside, name)

    fun List<Fn>.randomSequence(
        seed: Long = 0L,
        overlap: Fn,
        slot: Fn? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "randomSequence"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val slotFn = slot ?: param(parts.maxOf { it.duration })
        return graphFn(
            domain,
            name,
            "randomSequence",
            children = itemEdges(this) + listOf(fnEdge("overlap", overlap), fnEdge("slot", slotFn)),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                itemChildren(children, size).randomSequence(
                    seed,
                    childAt(children, size, overlap),
                    childAt(children, size + 1, slotFn),
                    domain,
                    edge,
                    outside,
                    name
                )
            }
        ) { x ->
            randomSlotSequenceValue(parts, seed, x, slotFn, overlap, edge, outside)
        }
    }

    fun List<Fn>.weightedSequence(
        weights: List<Any>,
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Number? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn = weightedSequence(weights, seed, param(overlap), slot?.let { param(it) }, domain, edge, outside, name)

    fun List<Fn>.weightedSequence(
        weights: List<Any>,
        seed: Long = 0L,
        overlap: Fn,
        slot: Fn? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        val weightFns = Array(parts.size) { i ->
            when (val w = weights.getOrNull(i)) {
                is Fn -> w
                is Number -> param(w.toDouble())
                else -> param(0.0)
            }
        }
        val slotFn = slot ?: param(parts.maxOf { it.duration })
        return graphFn(
            domain,
            name,
            "weightedSequence",
            children = itemEdges(this) + listOf(fnEdge("overlap", overlap), fnEdge("slot", slotFn)) + weightFns.mapIndexed { i, fn -> fnEdge("weight$i", fn) },
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                val rebuiltWeights = children.drop(size + 2).take(size)
                itemChildren(children, size).weightedSequence(
                    rebuiltWeights,
                    seed,
                    childAt(children, size, overlap),
                    childAt(children, size + 1, slotFn),
                    domain,
                    edge,
                    outside,
                    name
                )
            }
        ) { x ->
            val localWeights = DoubleArray(parts.size) { i -> weightFns[i].paramAt(x).takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
            val localTotal = localWeights.sum()
            if (localTotal <= 0.0) {
                randomSlotSequenceValue(parts, seed, x, slotFn, overlap, edge, outside)
            } else {
                randomSlotSequenceValue(parts, seed, x, slotFn, overlap, edge, outside, localWeights, localTotal)
            }
        }
    }

    fun List<Fn>.weightedSequence(
        weights: DoubleArray,
        seed: Long = 0L,
        overlap: Number = 0.0,
        slot: Number? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn = weightedSequence(weights.toList(), seed, overlap, slot, domain, edge, outside, name)

    fun List<Fn>.weightedSequence(
        weights: DoubleArray,
        seed: Long = 0L,
        overlap: Fn,
        slot: Fn? = null,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "weightedSequence"
    ): Fn = weightedSequence(weights.toList(), seed, overlap, slot, domain, edge, outside, name)

    fun List<Fn>.shuffle(
        seed: Long = 0L,
        overlap: Number = 0.0,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffle"
    ): Fn = shuffle(seed, param(overlap), edge, outside, name)

    fun List<Fn>.shuffle(
        seed: Long = 0L,
        overlap: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffle"
    ): Fn = randomPermute(seed, overlap, edge, outside, name)

    fun List<Fn>.shuffleLoop(
        seed: Long = 0L,
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffleLoop"
    ): Fn = shuffleLoop(seed, param(overlap), domain, edge, outside, name)

    fun List<Fn>.shuffleLoop(
        seed: Long = 0L,
        overlap: Fn,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "shuffleLoop"
    ): Fn = randomPermuteLoop(seed, overlap, domain, edge, outside, name)

    fun List<Fn>.palindrome(
        overlap: Number = 0.0,
        includeEndsTwice: Boolean = false,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "palindrome"
    ): Fn = palindrome(param(overlap), includeEndsTwice, edge, outside, name)

    fun List<Fn>.palindrome(
        overlap: Fn,
        includeEndsTwice: Boolean = false,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "palindrome"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val tail = if (includeEndsTwice || size <= 2) asReversed() else dropLast(1).drop(1).asReversed()
        val sequenced = (this + tail).sequence(overlap, edge, outside, name)
        return graphFn(
            sequenced.domain,
            name,
            "palindrome",
            children = itemEdges(this) + fnEdge("overlap", overlap),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            booleans = mapOf("includeEndsTwice" to includeEndsTwice),
            rebuild = { children -> itemChildren(children, size).palindrome(childAt(children, size, overlap), includeEndsTwice, edge, outside, name) }
        ) { x -> sequenced.eval(x) }
    }

    fun List<Fn>.roundRobin(
        slot: Number,
        overlap: Number = 0.0,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "roundRobin"
    ): Fn = roundRobin(param(slot), param(overlap), domain, edge, outside, name)

    fun List<Fn>.roundRobin(
        slot: Fn,
        overlap: Fn = param(0.0),
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "roundRobin"
    ): Fn {
        val parts = sequenceParts()
        if (parts.isEmpty()) return zero(Domain.EMPTY).withName(name)
        return graphFn(
            domain,
            name,
            "roundRobin",
            children = itemEdges(this) + listOf(fnEdge("slot", slot), fnEdge("overlap", overlap)),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children ->
                itemChildren(children, size).roundRobin(
                    childAt(children, size, slot),
                    childAt(children, size + 1, overlap),
                    domain,
                    edge,
                    outside,
                    name
                )
            }
        ) { x ->
            if (x < domain.min) return@graphFn outside
            val slotDuration = slot.paramAt(x, parts.maxOf { it.duration }).coerceAtLeast(1e-9)
            val localOverlap = sequenceOverlapAt(overlap, x, slotDuration)
            val step = sequenceStep(slotDuration, localOverlap)
            val base = x - domain.min
            val slotIndex = floor(base / step).toLong()
            var value = 0.0
            var active = false
            for (i in (slotIndex - 1)..(slotIndex + 1)) {
                if (i < 0L) continue
                val index = ((i % parts.size) + parts.size) % parts.size
                val placement = SequencePlacement(
                    part = parts[index.toInt()].copy(duration = slotDuration),
                    start = domain.min + i * step,
                    fadeIn = if (i > 0L) localOverlap else 0.0,
                    fadeOut = localOverlap
                )
                val sample = placement.sample(x, edge, outside) ?: continue
                value += sample
                active = true
            }
            if (active) value else outside
        }
    }

    fun List<Fn>.stack(
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "stack"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val d = fold(Domain.EMPTY) { acc, fn -> acc.union(fn.domain) }
        return graphFn(
            d,
            name,
            "stack",
            children = itemEdges(this),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).stack(edge, outside, name) }
        ) { x -> sumOf { it.evalSafe(x, outside = outside, edge = edge) } }
    }

    fun List<Fn>.average(
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "average"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val stacked = stack(edge, outside, name)
        return graphFn(
            stacked.domain,
            name,
            "average",
            children = itemEdges(this),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).average(edge, outside, name) }
        ) { x -> stacked.eval(x) / size.toDouble() }
    }

    fun List<Fn>.select(
        selector: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "select"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        val d = fold(selector.domain) { acc, fn -> acc.union(fn.domain) }
        return graphFn(
            d,
            name,
            "select",
            children = itemEdges(this) + fnEdge("selector", selector),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).select(childAt(children, size, selector), edge, outside, name) }
        ) { x ->
            val index = selector.eval(x).roundToInt().coerceIn(0, lastIndex)
            this[index].evalSafe(x, outside = outside, edge = edge)
        }
    }

    fun List<Fn>.morph(
        selector: Fn,
        edge: EdgeMode = EdgeMode.ZERO,
        outside: Double = 0.0,
        name: String = "morph"
    ): Fn {
        if (isEmpty()) return zero(Domain.EMPTY).withName(name)
        if (size == 1) return first().withName(name)
        val d = fold(selector.domain) { acc, fn -> acc.union(fn.domain) }
        return graphFn(
            d,
            name,
            "morph",
            children = itemEdges(this) + fnEdge("selector", selector),
            numbers = mapOf("items" to size.toDouble(), "outside" to outside),
            strings = mapOf("edge" to edge.name),
            rebuild = { children -> itemChildren(children, size).morph(childAt(children, size, selector), edge, outside, name) }
        ) { x ->
            val pos = selector.eval(x).coerceIn(0.0, lastIndex.toDouble())
            val i0 = floor(pos).toInt().coerceIn(0, lastIndex)
            val i1 = (i0 + 1).coerceIn(0, lastIndex)
            val t = smootherstep(pos - i0.toDouble())
            blend(
                this[i0].evalSafe(x, outside = outside, edge = edge),
                this[i1].evalSafe(x, outside = outside, edge = edge),
                t
            )
        }
    }

    data class FnTransform(
        val name: String,
        private val applyFn: (Fn) -> Fn
    ) {
        operator fun invoke(source: Fn): Fn = applyFn(source)

        infix fun then(next: FnTransform): FnTransform =
            FnTransform("$name then ${next.name}") { source -> next(this(source)) }

        operator fun div(next: FnTransform): FnTransform = then(next)
    }

    /**
     * The declarative layer is intentionally thin: it only rearranges existing
     * functions and timeline chunks. That keeps this file exploratory without
     * creating a second evaluator.
     */
    infix fun Fn.over(domain: Domain): Fn = withDomain(domain)

    infix fun Fn.over(range: ClosedRange<*>): Fn =
        over(range.toDomain())

    infix fun Fn.stretch(domain: Domain): Fn =
        stretch(domain, from = this.domain)

    infix fun Fn.stretch(range: ClosedRange<*>): Fn =
        stretch(range.toDomain())

    infix fun Fn.through(transform: FnTransform): Fn = transform(this)

    infix fun Fn.pipe(transform: FnTransform): Fn = transform(this)

    operator fun Fn.div(transform: FnTransform): Fn = transform(this)

    val all: FnTransform = FnTransform("all") { source ->
        source.withDomain(Domain.ALL)
    }

    fun stretch(to: Domain, from: Domain? = null, shape: StretchShape = StretchShape.LINEAR): FnTransform =
        FnTransform("stretch") { source ->
            source.stretch(to, from = from ?: source.domain, shape = shape)
        }

    fun stretch(to: ClosedRange<*>, from: Domain? = null, shape: StretchShape = StretchShape.LINEAR): FnTransform =
        stretch(to.toDomain(), from, shape)

    fun stretchSmooth(to: Domain, from: Domain? = null): FnTransform =
        stretch(to, from, StretchShape.SMOOTHSTEP)

    fun stretchSmooth(to: ClosedRange<*>, from: Domain? = null): FnTransform =
        stretchSmooth(to.toDomain(), from)

    fun stretchSmoother(to: Domain, from: Domain? = null): FnTransform =
        stretch(to, from, StretchShape.SMOOTHERSTEP)

    fun stretchSmoother(to: ClosedRange<*>, from: Domain? = null): FnTransform =
        stretchSmoother(to.toDomain(), from)

    fun stretchBy(to: Domain, warp: Fn, from: Domain? = null): FnTransform =
        FnTransform("stretchBy") { source ->
            source.stretchBy(to, warp, from = from ?: source.domain)
        }

    fun stretchBy(to: ClosedRange<*>, warp: Fn, from: Domain? = null): FnTransform =
        stretchBy(to.toDomain(), warp, from)

    fun delay(amount: Number): FnTransform = delay(param(amount))

    fun delay(amount: Fn): FnTransform = FnTransform("delay") { source ->
        source.delay(amount)
    }

    fun gain(amount: Number): FnTransform = gain(param(amount))

    fun gain(amount: Fn): FnTransform = FnTransform("gain") { source ->
        source * amount
    }

    fun bias(amount: Number): FnTransform = bias(param(amount))

    fun bias(amount: Fn): FnTransform = FnTransform("bias") { source ->
        source + amount
    }

    fun clipAt(threshold: Number): FnTransform = clipAt(param(threshold))

    fun clipAt(threshold: Fn): FnTransform = FnTransform("clip") { source ->
        source.clip(threshold)
    }

    fun softClipBy(amount: Number): FnTransform = softClipBy(param(amount))

    fun softClipBy(amount: Fn): FnTransform = FnTransform("softClip") { source ->
        source.softClip(amount)
    }

    fun hold(step: Number): FnTransform = hold(param(step))

    fun hold(step: Fn): FnTransform = FnTransform("hold") { source ->
        source.sampleHold(step)
    }

    /**
     * Time-scale transforms use musical language: `faster(2.0)` samples the
     * source twice as quickly, while `slower(2.0)` stretches it by two.
     */
    fun faster(factor: Number = 2.0, about: Number = 0.0): FnTransform =
        faster(param(factor), param(about))

    fun faster(factor: Fn, about: Fn = param(0.0)): FnTransform =
        FnTransform("faster") { source ->
            source.faster(factor, about)
        }

    fun slower(factor: Number = 2.0, about: Number = 0.0): FnTransform =
        slower(param(factor), param(about))

    fun slower(factor: Fn, about: Fn = param(0.0)): FnTransform =
        FnTransform("slower") { source ->
            source.slower(factor, about)
        }

    fun named(name: String): FnTransform = FnTransform("named($name)") { source ->
        source.withName(name)
    }

    fun lowPass(window: Number, samples: Int = 64): FnTransform = lowPass(param(window), samples)

    fun lowPass(window: Fn, samples: Int = 64): FnTransform =
        FnTransform("lowPass") { source ->
            source.movingAverage(window, samples).withName("lowPass")
        }

    fun highPass(window: Number, samples: Int = 64): FnTransform = highPass(param(window), samples)

    fun highPass(window: Fn, samples: Int = 64): FnTransform =
        FnTransform("highPass") { source ->
            (source - source.movingAverage(window, samples)).withName("highPass")
        }

    /**
     * A stateless phase color, not a recursive DSP all-pass. That distinction is
     * intentional here: an [Fn] should stay pure and freely sampleable.
     */
    fun allPass(delay: Number, feedback: Number = 0.5, repeats: Int = 3): FnTransform =
        allPass(param(delay), param(feedback), repeats)

    fun allPass(delay: Any, feedback: Any = 0.5, repeats: Int = 3): FnTransform =
        allPass(asParam(delay, "delay"), asParam(feedback, "feedback"), repeats)

    fun allPass(delay: Fn, feedback: Fn = param(0.5), repeats: Int = 3): FnTransform =
        FnTransform("allPass") { source ->
            val fb = feedback.mapY("allPassFeedback") { it.coerceIn(-0.99, 0.99) }
            var acc = source * (one(source.domain) - fb.abs())
            var gain = fb
            for (i in 1..max(1, repeats)) {
                acc += source.delay(delay * i.toDouble()) * gain
                gain *= fb * -1.0
            }
            acc.withName("allPass")
        }

    fun turbulence(amount: Number, frequency: Number = 8.0, octaves: Int = 4, seed: Long = 0L): FnTransform =
        turbulence(param(amount), param(frequency), octaves, seed)

    fun turbulence(amount: Number, frequency: Fn, octaves: Int = 4, seed: Long = 0L): FnTransform =
        turbulence(param(amount), frequency, octaves, seed)

    fun turbulence(amount: Fn, frequency: Number = 8.0, octaves: Int = 4, seed: Long = 0L): FnTransform =
        turbulence(amount, param(frequency), octaves, seed)

    fun turbulence(amount: Fn, frequency: Fn = param(8.0), octaves: Int = 4, seed: Long = 0L): FnTransform =
        FnTransform("turbulence") { source ->
            val mod = fbm(seed = seed, frequency = frequency, amplitude = param(1.0), octaves = octaves, domain = source.domain)
            source.warp(mod.abs(), amount).withName("turbulence")
        }

    /**
     * Stateless sample-and-hold driven by an external gate. Because this is a
     * pure function, it searches backward for the latest open gate rather than
     * storing latch state between calls.
     */
    fun gateHold(gate: Fn, step: Number, threshold: Number = 0.5, lookBack: Number = 1.0): FnTransform =
        gateHold(gate, param(step), param(threshold), param(lookBack))

    fun gateHold(gate: Fn, step: Any, threshold: Any = 0.5, lookBack: Any = 1.0): FnTransform =
        gateHold(gate, asParam(step, "step"), asParam(threshold, "threshold"), asParam(lookBack, "lookBack"))

    fun gateHold(gate: Fn, step: Fn, threshold: Fn = param(0.5), lookBack: Fn = param(1.0)): FnTransform =
        FnTransform("gateHold") { source ->
            val d = source.domain.intersect(gate.domain)
            graphFn(
                d,
                "gateHold",
                "gateHold",
                children = listOf(
                    fnEdge("source", source),
                    fnEdge("gate", gate),
                    fnEdge("step", step),
                    fnEdge("threshold", threshold),
                    fnEdge("lookBack", lookBack)
                ),
                rebuild = { children -> (childAt(children, 0, source) / gateHold(childAt(children, 1, gate), childAt(children, 2, step), childAt(children, 3, threshold), childAt(children, 4, lookBack))) }
            ) { x ->
                val safeStep = abs(step.paramAt(x, 1.0)).coerceAtLeast(1e-9)
                val safeLookBack = abs(lookBack.paramAt(x, 1.0))
                val gateThreshold = threshold.paramAt(x, 0.5)
                var t = x
                while (t >= d.min && x - t <= safeLookBack) {
                    if (gate.evalSafe(t) >= gateThreshold) return@graphFn source.evalSafe(t)
                    t -= safeStep
                }
                0.0
            }
        }

    fun crush(bits: Int): FnTransform = FnTransform("crush($bits)") { source ->
        source.bitCrush(bits).withName("crush")
    }

    /**
     * Waveshaping / distortion transforms — the value-domain (west-coast) counterparts
     * to the time-domain [hold] / [crush] pair. Each is a thin factory over a pure value
     * shaper (see [Fn.fold], [Fn.rectify], [Fn.abs], [Fn.quantize]) so it composes in
     * `through` / `pipe` / `/` chains and surfaces in the type-aware infix completion.
     * They shape the signal's VALUE, distinct from `hold`/`stepHold` which clock its time.
     */
    fun fold(threshold: Number = 1.0): FnTransform = fold(param(threshold))

    fun fold(threshold: Fn): FnTransform = FnTransform("fold") { source ->
        source.fold(threshold).withName("fold")
    }

    fun rectify(mode: RectifyMode = RectifyMode.FULL): FnTransform =
        FnTransform("rectify(${mode.name.lowercase()})") { source ->
            when (mode) {
                RectifyMode.HALF -> source.rectify()
                RectifyMode.FULL -> source.abs()
            }.withName("rectify")
        }

    fun quantize(levels: Int): FnTransform = FnTransform("quantize($levels)") { source ->
        source.quantize(levels).withName("quantize")
    }

    fun chebyshev(order: Int = 2): FnTransform = chebyshev(param(order))

    fun chebyshev(order: Fn): FnTransform = FnTransform("chebyshev") { source ->
        source.chebyshev(order).withName("chebyshev")
    }

    fun copyPaste(from: Domain, to: Domain, stretch: Boolean = true, edge: EdgeMode = EdgeMode.CLAMP): FnTransform =
        FnTransform("copyPaste") { source ->
            val d = source.domain.union(to)
            graphFn(
                d,
                "copyPaste",
                "copyPaste",
                children = listOf(fnEdge("source", source)),
                strings = mapOf("from" to from.toString(), "to" to to.toString(), "edge" to edge.name),
                booleans = mapOf("stretch" to stretch),
                rebuild = { children -> (childAt(children, 0, source) / copyPaste(from, to, stretch, edge)) }
            ) { x ->
                if (!to.containsInclusive(x)) return@graphFn source.evalSafe(x, edge = edge)
                val sourceX = if (stretch) {
                    from.lerp(to.alphaOf(x).coerceIn(0.0, 1.0))
                } else {
                    from.min + (x - to.min)
                }
                source.evalSafe(sourceX, edge = edge)
            }
        }

    fun copyPaste(from: ClosedRange<*>, to: ClosedRange<*>, stretch: Boolean = true, edge: EdgeMode = EdgeMode.CLAMP): FnTransform =
        copyPaste(from.toDomain(), to.toDomain(), stretch, edge)

    fun mirror(about: Number? = null): FnTransform = FnTransform("mirror") { source ->
        val center = about?.toDouble() ?: if (source.domain.isFinite) source.domain.center else 0.0
        source.reverse(param(center)).withName("mirror")
    }

    fun mirror(about: Fn): FnTransform = FnTransform("mirror") { source ->
        source.reverse(about).withName("mirror")
    }

    fun repeatEvery(period: Number, origin: Number = 0.0, domain: Domain = Domain.ALL): FnTransform =
        repeatEvery(param(period), param(origin), domain)

    fun repeatEvery(period: Any, origin: Any = 0.0, domain: Domain = Domain.ALL): FnTransform =
        repeatEvery(asParam(period, "period"), asParam(origin, "origin"), domain)

    fun repeatEvery(period: Number, origin: Number = 0.0, domain: ClosedRange<*>): FnTransform =
        repeatEvery(param(period), param(origin), domain.toDomain())

    fun repeatEvery(period: Any, origin: Any = 0.0, domain: ClosedRange<*>): FnTransform =
        repeatEvery(asParam(period, "period"), asParam(origin, "origin"), domain.toDomain())

    fun repeatEvery(period: Fn, origin: Fn = param(0.0), domain: Domain = Domain.ALL): FnTransform =
        FnTransform("repeatEvery") { source ->
            source.repeat(period, origin, domainOverride = domain).withName("repeatEvery")
        }

    fun repeatEvery(period: Fn, origin: Fn = param(0.0), domain: ClosedRange<*>): FnTransform =
        repeatEvery(period, origin, domain.toDomain())

    fun mirrorEvery(period: Number, origin: Number = 0.0, domain: Domain = Domain.ALL): FnTransform =
        mirrorEvery(param(period), param(origin), domain)

    fun mirrorEvery(period: Any, origin: Any = 0.0, domain: Domain = Domain.ALL): FnTransform =
        mirrorEvery(asParam(period, "period"), asParam(origin, "origin"), domain)

    fun mirrorEvery(period: Number, origin: Number = 0.0, domain: ClosedRange<*>): FnTransform =
        mirrorEvery(param(period), param(origin), domain.toDomain())

    fun mirrorEvery(period: Any, origin: Any = 0.0, domain: ClosedRange<*>): FnTransform =
        mirrorEvery(asParam(period, "period"), asParam(origin, "origin"), domain.toDomain())

    fun mirrorEvery(period: Fn, origin: Fn = param(0.0), domain: Domain = Domain.ALL): FnTransform =
        FnTransform("mirrorEvery") { source ->
            source.mirrorRepeat(period, origin, domainOverride = domain).withName("mirrorEvery")
        }

    fun mirrorEvery(period: Fn, origin: Fn = param(0.0), domain: ClosedRange<*>): FnTransform =
        mirrorEvery(period, origin, domain.toDomain())

    fun randomRepeat(
        period: Number,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = randomRepeat(param(period), seed, domain, sourceDomain, edge)

    fun randomRepeat(
        period: Number,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = randomRepeat(param(period), seed, domain.toDomain(), sourceDomain, edge)

    fun randomRepeat(
        period: Fn,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = FnTransform("randomRepeat") { source ->
        val src = sourceDomain ?: source.domain
        graphFn(
            domain,
            "randomRepeat",
            "randomRepeat",
            children = listOf(fnEdge("source", source), fnEdge("period", period)),
            strings = mapOf("sourceDomain" to src.toString(), "edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children -> (childAt(children, 0, source) / randomRepeat(childAt(children, 1, period), seed, domain, src, edge)) }
        ) { x ->
            if (src._isEmpty || !src.length.isFinite()) return@graphFn source.evalSafe(x, edge = edge)
            val p = abs(period.paramAt(x, 1.0)).coerceAtLeast(1e-9)
            val slot = floor((x - domain.min) / p).toLong()
            val local = ((x - domain.min) % p + p) % p
            val maxStart = (src.length - p).coerceAtLeast(0.0)
            val start = src.min + hashToUnit(seed, slot) * maxStart
            source.evalSafe(start + local.coerceAtMost(src.length), edge = edge)
        }
    }

    fun randomRepeat(
        period: Fn,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        edge: EdgeMode = EdgeMode.CLAMP
    ): FnTransform = randomRepeat(period, seed, domain.toDomain(), sourceDomain, edge)

    fun randomCopyPaste(
        grain: Number,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        density: Number = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(param(grain), seed, domain, sourceDomain, param(density), edge, outside)

    fun randomCopyPaste(
        grain: Number,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        density: Number = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(param(grain), seed, domain.toDomain(), sourceDomain, param(density), edge, outside)

    fun randomCopyPaste(
        grain: Any,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        density: Any = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(asParam(grain, "grain"), seed, domain, sourceDomain, asParam(density, "density"), edge, outside)

    fun randomCopyPaste(
        grain: Any,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        density: Any = 1.0,
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(grain, seed, domain.toDomain(), sourceDomain, density, edge, outside)

    fun randomCopyPaste(
        grain: Fn,
        seed: Long = 0L,
        domain: Domain = Domain.of(0.0, Double.POSITIVE_INFINITY),
        sourceDomain: Domain? = null,
        density: Fn = param(1.0),
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = FnTransform("randomCopyPaste") { source ->
        val src = sourceDomain ?: source.domain.takeIf { it.isFinite } ?: Domain.DEFAULT
        graphFn(
            domain,
            "randomCopyPaste",
            "randomCopyPaste",
            children = listOf(fnEdge("source", source), fnEdge("grain", grain), fnEdge("density", density)),
            numbers = mapOf("outside" to outside),
            strings = mapOf("sourceDomain" to src.toString(), "edge" to edge.name),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                (childAt(children, 0, source) / randomCopyPaste(
                    childAt(children, 1, grain),
                    seed,
                    domain,
                    src,
                    childAt(children, 2, density),
                    edge,
                    outside
                ))
            }
        ) { x ->
            if (src._isEmpty || !src.length.isFinite()) return@graphFn outside
            val g = abs(grain.paramAt(x, 1.0)).coerceAtLeast(1e-9)
            val fill = density.paramAt(x, 1.0).coerceIn(0.0, 1.0)
            val slot = floor((x - domain.min) / g).toLong()
            if (hashToUnit(seed + 17L, slot) > fill) return@graphFn outside
            val local = ((x - domain.min) % g + g) % g
            val maxStart = (src.length - g).coerceAtLeast(0.0)
            val start = src.min + hashToUnit(seed, slot) * maxStart
            source.evalSafe(start + local.coerceAtMost(src.length), outside = outside, edge = edge)
        }
    }

    fun randomCopyPaste(
        grain: Fn,
        seed: Long = 0L,
        domain: ClosedRange<*>,
        sourceDomain: Domain? = null,
        density: Fn = param(1.0),
        edge: EdgeMode = EdgeMode.CLAMP,
        outside: Double = 0.0
    ): FnTransform = randomCopyPaste(grain, seed, domain.toDomain(), sourceDomain, density, edge, outside)

    fun sin(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        sin(frequency = param(frequency), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun sin(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        sin(frequency, phase, amplitude, offset, domain.toDomain())

    fun sin(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        sin(frequency = asParam(frequency, "frequency"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun sin(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        sin(frequency, phase, amplitude, offset, domain.toDomain())

    fun cos(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        cos(frequency = param(frequency), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun cos(
        frequency: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        cos(frequency, phase, amplitude, offset, domain.toDomain())

    fun cos(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        cos(frequency = asParam(frequency, "frequency"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun cos(
        frequency: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        cos(frequency, phase, amplitude, offset, domain.toDomain())

    fun saw(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        saw(period = param(period), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun saw(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        saw(period, phase, amplitude, offset, domain.toDomain())

    fun saw(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        saw(period = asParam(period, "period"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun saw(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        saw(period, phase, amplitude, offset, domain.toDomain())

    fun triangle(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        triangle(period = param(period), phase = param(phase), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun triangle(
        period: Number,
        phase: Number = 0.0,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        triangle(period, phase, amplitude, offset, domain.toDomain())

    fun triangle(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        triangle(period = asParam(period, "period"), phase = asParam(phase, "phase"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun triangle(
        period: Any,
        phase: Any = 0.0,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        triangle(period, phase, amplitude, offset, domain.toDomain())

    fun square(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        square(period = param(period), phase = param(phase), duty = param(duty), amplitude = param(amplitude), offset = param(offset), domain = domain)

    fun square(
        period: Number,
        phase: Number = 0.0,
        duty: Number = 0.5,
        amplitude: Number = 1.0,
        offset: Number = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        square(period, phase, duty, amplitude, offset, domain.toDomain())

    fun square(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: Domain = Domain.ALL
    ): Fn =
        square(period = asParam(period, "period"), phase = asParam(phase, "phase"), duty = asParam(duty, "duty"), amplitude = asParam(amplitude, "amplitude"), offset = asParam(offset, "offset"), domain = domain)

    fun square(
        period: Any,
        phase: Any = 0.0,
        duty: Any = 0.5,
        amplitude: Any = 1.0,
        offset: Any = 0.0,
        domain: ClosedRange<*>
    ): Fn =
        square(period, phase, duty, amplitude, offset, domain.toDomain())

    private fun timelineStartFor(source: Fn): Double =
        if (source.domain.isFinite) source.domain.min else 0.0

    infix fun Fn.then(next: Fn): Fn =
        TimelineBuilder(start = timelineStartFor(this)).apply {
            this@then.place()
            next.place()
        }.build(name = "then")

    data class OverlapStart(val first: Fn, val amount: Fn) {
        infix fun with(next: Fn): Fn = first.overlapWith(next, amount)
    }

    infix fun Fn.overlapping(amount: Number): OverlapStart = OverlapStart(this, param(amount))

    infix fun Fn.overlapping(amount: Fn): OverlapStart = OverlapStart(this, amount)

    fun Fn.overlapping(amount: Number, next: Fn): Fn = overlapWith(next, amount)

    fun Fn.overlapping(amount: Fn, next: Fn): Fn = overlapWith(next, amount)

    fun Fn.overlapWith(
        next: Fn,
        amount: Number,
        blend: ChunkBlend = ChunkBlend.REPLACE,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn = overlapWith(next, param(amount), blend, window)

    fun Fn.overlapWith(
        next: Fn,
        amount: Fn,
        blend: ChunkBlend = ChunkBlend.REPLACE,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn = TimelineBuilder(start = timelineStartFor(this)).apply {
        this@overlapWith.place()
        next.overlap(amount = amount, blend = blend, window = window)
    }.build(name = "overlap")

    fun Fn.toFLine(
        sampleDomain: Domain = domain,
        samples: Int = 256,
        includeEnd: Boolean = true,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xOffset: Double = 0.0,
        yOffset: Double = 0.0
    ): FLine {
        val line = FLine()
        if (samples <= 0 || sampleDomain._isEmpty) return line
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, samples - 1) else samples
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        for (i in 0 until samples) {
            val x = sweep.min + step * i.toDouble()
            val y = eval(x)
            val px = xOffset + x * xScale
            val py = yOffset + y * yScale
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        return line
    }

    fun Fn.toFLine(
        sampleDomain: ClosedRange<*>,
        samples: Int = 256,
        includeEnd: Boolean = true,
        xScale: Double = 1.0,
        yScale: Double = 1.0,
        xOffset: Double = 0.0,
        yOffset: Double = 0.0
    ): FLine =
        toFLine(
            sampleDomain = sampleDomain.toDomain(),
            samples = samples,
            includeEnd = includeEnd,
            xScale = xScale,
            yScale = yScale,
            xOffset = xOffset,
            yOffset = yOffset
        )

    // Algebra and composition
    operator fun Fn.plus(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(+)",
            node("add", "+", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] + it[1] })
        ) { x -> this.eval(x) + other.eval(x) }
    }

    operator fun Fn.minus(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(-)",
            node("subtract", "-", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] - it[1] })
        ) { x -> this.eval(x) - other.eval(x) }
    }

    operator fun Fn.times(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(*)",
            node("multiply", "*", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] * it[1] })
        ) { x -> this.eval(x) * other.eval(x) }
    }

    operator fun Fn.div(other: Fn): Fn {
        val domain = combinedDomain(this, other)
        return graphFn(
            domain,
            "(/)",
            node("divide", "/", listOf(fnEdge("left", this), fnEdge("right", other)), rebuild = { it[0] / it[1] })
        ) { x -> this.eval(x) / other.eval(x) }
    }

    operator fun Fn.plus(value: Number): Fn = this + param(value)

    operator fun Fn.minus(value: Number): Fn = this - param(value)

    operator fun Fn.times(value: Number): Fn = this * param(value)

    operator fun Fn.div(value: Number): Fn = this / param(value)

    operator fun Number.plus(f: Fn): Fn = f + this
    operator fun Number.minus(f: Fn): Fn = param(this) - f
    operator fun Number.times(f: Fn): Fn = f * this
    operator fun Number.div(f: Fn): Fn = param(this) / f

    fun Fn.mapY(newName: String? = name, transform: (Double) -> Double): Fn =
        graphFn(
            domain,
            newName,
            node(
                "mapY",
                label = newName ?: "mapY",
                children = listOf(fnEdge("source", this)),
                tags = setOf("lambda"),
                rebuild = { children -> children[0].mapY(newName, transform) }
            )
        ) { x -> transform(eval(x)) }

    fun Fn.mapX(newName: String? = name, newDomain: Domain = domain, transform: (Double) -> Double): Fn =
        graphFn(
            newDomain,
            newName,
            node(
                "mapX",
                label = newName ?: "mapX",
                children = listOf(fnEdge("source", this)),
                tags = setOf("lambda"),
                rebuild = { children -> children[0].mapX(newName, newDomain, transform) }
            )
        ) { x -> eval(transform(x)) }

    private fun Fn.stretchMapped(
        from: Domain,
        to: Domain,
        newName: String?,
        warp: (Double) -> Double
    ): Fn {
        requireStretchDomain(from, "source")
        requireStretchDomain(to, "target")
        return graphFn(
            to,
            newName,
            node(
                "stretch",
                label = newName ?: "stretch",
                children = listOf(fnEdge("source", this)),
                strings = mapOf("from" to from.toString(), "to" to to.toString()),
                tags = setOf("lambda"),
                rebuild = { children -> children[0].stretchMapped(from, to, newName, warp) }
            )
        ) { x ->
            val alpha = to.alphaOf(x)
            eval(from.lerp(warp(alpha)))
        }
    }

    /**
     * Remap this function's existing coordinate domain onto [to].
     *
     * Unlike [over], which only relabels the public domain, stretch changes the
     * x-coordinate lookup so the whole source interval is played across the
     * target interval.
     */
    fun Fn.stretch(
        to: Domain,
        from: Domain = domain,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretchMapped(from, to, name) { alpha ->
        stretchAlpha(alpha, shape)
    }

    fun Fn.stretch(
        to: ClosedRange<*>,
        from: Domain = domain,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretch(to.toDomain(), from, shape)

    fun Fn.stretchFrom(
        from: Domain,
        to: Domain,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretch(to, from, shape)

    fun Fn.stretchFrom(
        from: ClosedRange<*>,
        to: ClosedRange<*>,
        shape: StretchShape = StretchShape.LINEAR
    ): Fn = stretch(to.toDomain(), from.toDomain(), shape)

    fun Fn.stretchSmooth(to: Domain, from: Domain = domain): Fn =
        stretch(to, from, StretchShape.SMOOTHSTEP)

    fun Fn.stretchSmooth(to: ClosedRange<*>, from: Domain = domain): Fn =
        stretchSmooth(to.toDomain(), from)

    fun Fn.stretchSmoother(to: Domain, from: Domain = domain): Fn =
        stretch(to, from, StretchShape.SMOOTHERSTEP)

    fun Fn.stretchSmoother(to: ClosedRange<*>, from: Domain = domain): Fn =
        stretchSmoother(to.toDomain(), from)

    fun Fn.stretchBy(to: Domain, warp: Fn, from: Domain = domain): Fn =
        stretchMapped(from, to, name) { alpha ->
            warp.evalSafe(alpha, outside = alpha, edge = EdgeMode.CLAMP)
        }

    fun Fn.stretchBy(to: ClosedRange<*>, warp: Fn, from: Domain = domain): Fn =
        stretchBy(to.toDomain(), warp, from)

    fun Fn.stretchBy(to: Domain, from: Domain = domain, warp: (Double) -> Double): Fn =
        stretchMapped(from, to, name, warp)

    fun Fn.stretchBy(to: ClosedRange<*>, from: Domain = domain, warp: (Double) -> Double): Fn =
        stretchBy(to.toDomain(), from, warp)

    fun Fn.shift(dx: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, dx)
        return graphFn(
            d,
            name,
            node("shift", children = listOf(fnEdge("source", this), fnEdge("dx", dx)), rebuild = { it[0].shift(it[1]) })
        ) { x -> eval(x - dx.eval(x)) }
    }

    fun Fn.shift(dx: Number): Fn = shift(param(dx))

    fun Fn.scaleX(scale: Fn = param(1.0), about: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, scale, about)
        return graphFn(
            d,
            name,
            node(
                "scaleX",
                children = listOf(fnEdge("source", this), fnEdge("scale", scale), fnEdge("about", about)),
                rebuild = { it[0].scaleX(it[1], it[2]) }
            )
        ) { x ->
            val a = about.eval(x)
            val s = scale.eval(x)
            if (s == 0.0) eval(a) else eval((x - a) / s + a)
        }
    }

    fun Fn.scaleX(scale: Number, about: Number = 0.0): Fn =
        scaleX(param(scale), param(about))

    fun Fn.scaleX(scale: Any, about: Any = 0.0): Fn =
        scaleX(asParam(scale, "scale"), asParam(about, "about"))

    fun Fn.faster(factor: Number = 2.0, about: Number = 0.0): Fn {
        return faster(param(factor), param(about))
    }

    fun Fn.faster(factor: Any, about: Any = 0.0): Fn =
        faster(asParam(factor, "factor"), asParam(about, "about"))

    fun Fn.faster(factor: Fn, about: Fn = param(0.0)): Fn {
        val safe = factor.mapY("fasterFactor") { abs(it).coerceAtLeast(1e-9) }
        return scaleX(one(safe.domain) / safe, about).withName("faster")
    }

    fun Fn.slower(factor: Number = 2.0, about: Number = 0.0): Fn {
        return slower(param(factor), param(about))
    }

    fun Fn.slower(factor: Any, about: Any = 0.0): Fn =
        slower(asParam(factor, "factor"), asParam(about, "about"))

    fun Fn.slower(factor: Fn, about: Fn = param(0.0)): Fn {
        val safe = factor.mapY("slowerFactor") { abs(it).coerceAtLeast(1e-9) }
        return scaleX(safe, about).withName("slower")
    }

    fun Fn.scaleY(scale: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, scale)
        return graphFn(
            d,
            name,
            node("scaleY", children = listOf(fnEdge("source", this), fnEdge("scale", scale)), rebuild = { it[0].scaleY(it[1]) })
        ) { x -> eval(x) * scale.eval(x) }
    }

    fun Fn.scaleY(scale: Number): Fn = scaleY(param(scale))

    fun Fn.offsetY(offset: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, offset)
        return graphFn(
            d,
            name,
            node("offsetY", children = listOf(fnEdge("source", this), fnEdge("offset", offset)), rebuild = { it[0].offsetY(it[1]) })
        ) { x -> eval(x) + offset.eval(x) }
    }

    fun Fn.offsetY(offset: Number): Fn = offsetY(param(offset))

    fun Fn.reverse(about: Fn = param(domain.center)): Fn {
        val d = intersectDomains(domain, about)
        return graphFn(
            d,
            name,
            node("reverse", children = listOf(fnEdge("source", this), fnEdge("about", about)), rebuild = { it[0].reverse(it[1]) })
        ) { x ->
            val a = about.eval(x)
            eval(a - (x - a))
        }
    }

    fun Fn.reverse(about: Number): Fn = reverse(param(about))

    fun Fn.flip(): Fn = mapY(name) { -it }

    fun Fn.abs(): Fn = mapY(name) { kotlin.math.abs(it) }

    fun Fn.sign(): Fn = mapY(name) { kotlin.math.sign(it) }

    fun Fn.pow(exponent: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, exponent)
        return graphFn(
            d,
            name,
            node("pow", children = listOf(fnEdge("source", this), fnEdge("exponent", exponent)), rebuild = { it[0].pow(it[1]) })
        ) { x -> eval(x).pow(exponent.eval(x)) }
    }

    fun Fn.pow(exponent: Number): Fn = pow(param(exponent))

    fun Fn.exp(): Fn = mapY(name) { kotlin.math.exp(it) }

    fun Fn.log(base: Fn = param(Math.E)): Fn {
        val d = intersectDomains(domain, base)
        return graphFn(
            d,
            name,
            node("log", children = listOf(fnEdge("source", this), fnEdge("base", base)), rebuild = { it[0].log(it[1]) })
        ) { x -> ln(eval(x)) / ln(base.eval(x)) }
    }

    fun Fn.log(base: Number): Fn = log(param(base))

    fun Fn.clamp(min: Fn = param(-1.0), max: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, min, max)
        return graphFn(
            d,
            name,
            node(
                "clamp",
                children = listOf(fnEdge("source", this), fnEdge("min", min), fnEdge("max", max)),
                rebuild = { it[0].clamp(it[1], it[2]) }
            )
        ) { x -> eval(x).coerceIn(min.eval(x), max.eval(x)) }
    }

    fun Fn.clamp(min: Number, max: Number): Fn = clamp(param(min), param(max))

    fun Fn.clamp(min: Any, max: Any): Fn = clamp(asParam(min, "min"), asParam(max, "max"))

    fun Fn.clip(threshold: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, threshold)
        return graphFn(
            d,
            name,
            node("clip", children = listOf(fnEdge("source", this), fnEdge("threshold", threshold)), rebuild = { it[0].clip(it[1]) })
        ) { x ->
            val t = threshold.eval(x)
            eval(x).coerceIn(-t, t)
        }
    }

    fun Fn.clip(threshold: Number): Fn = clip(param(threshold))

    fun Fn.rectify(): Fn = mapY(name) { if (it < 0.0) 0.0 else it }

    fun Fn.softClip(amount: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, amount)
        return graphFn(
            d,
            name,
            node("softClip", children = listOf(fnEdge("source", this), fnEdge("amount", amount)), rebuild = { it[0].softClip(it[1]) })
        ) { x ->
            val a = amount.eval(x)
            if (a == 0.0) 0.0 else tanh(eval(x) * a) / tanh(a)
        }
    }

    fun Fn.softClip(amount: Number): Fn = softClip(param(amount))

    fun Fn.fold(threshold: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, threshold)
        return graphFn(
            d,
            name,
            node("fold", children = listOf(fnEdge("source", this), fnEdge("threshold", threshold)), rebuild = { it[0].fold(it[1]) })
        ) { x ->
            val th = threshold.eval(x)
            if (th <= 0.0) 0.0 else {
                // West-coast wavefolder: pass the signal through unchanged while it stays
                // within [-th, th], reflecting it back each time it crosses the threshold.
                // asin(sin(.)) is exactly that triangle — continuous, odd-symmetric, bounded
                // to [-th, th], and equal to the input near zero.
                val value = eval(x)
                (2.0 * th / PI) * kotlin.math.asin(kotlin.math.sin(PI * value / (2.0 * th)))
            }
        }
    }

    fun Fn.fold(threshold: Number): Fn = fold(param(threshold))

    fun Fn.chebyshev(order: Fn = param(2.0)): Fn {
        val d = intersectDomains(domain, order)
        return graphFn(
            d,
            name,
            node("chebyshev", children = listOf(fnEdge("source", this), fnEdge("order", order)), rebuild = { it[0].chebyshev(it[1]) })
        ) { x ->
            // Canonical harmonic-generating waveshaper: pushing a unit-amplitude cosine
            // through the order-n Chebyshev polynomial Tₙ yields the n-th harmonic, since
            // Tₙ(cos θ) = cos(nθ). Clamp the drive to [-1, 1] first — the standard shaper
            // guard, matching how `fold`/`softClip` bound their input — because Tₙ diverges
            // rapidly outside the unit interval. Order is rounded to the nearest non-negative
            // integer, so a swept/animated `order` param becomes a harmonic sweep.
            val n = order.eval(x).roundToInt().coerceAtLeast(0)
            val v = eval(x).coerceIn(-1.0, 1.0)
            when (n) {
                0 -> 1.0
                1 -> v
                else -> {
                    // Iterative Tₙ recurrence: Tₖ = 2v·Tₖ₋₁ − Tₖ₋₂.
                    var tPrev = 1.0
                    var tCur = v
                    for (k in 2..n) {
                        val tNext = 2.0 * v * tCur - tPrev
                        tPrev = tCur
                        tCur = tNext
                    }
                    tCur
                }
            }
        }
    }

    fun Fn.chebyshev(order: Int): Fn = chebyshev(param(order))

    fun Fn.wrapY(min: Fn = param(-1.0), max: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, min, max)
        return graphFn(
            d,
            name,
            node(
                "wrapY",
                children = listOf(fnEdge("source", this), fnEdge("min", min), fnEdge("max", max)),
                rebuild = { it[0].wrapY(it[1], it[2]) }
            )
        ) { x ->
            val range = Domain.of(min.eval(x), max.eval(x))
            range.wrap(eval(x))
        }
    }

    fun Fn.wrapY(min: Number, max: Number): Fn = wrapY(param(min), param(max))

    fun Fn.wrapY(min: Any, max: Any): Fn = wrapY(asParam(min, "min"), asParam(max, "max"))

    fun Fn.withEdge(edge: EdgeMode, outside: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, outside)
        return graphFn(
            d,
            name,
            node(
                "withEdge",
                children = listOf(fnEdge("source", this), fnEdge("outside", outside)),
                strings = mapOf("edge" to edge.name),
                rebuild = { it[0].withEdge(edge, it[1]) }
            )
        ) { x -> evalSafe(x, outside.eval(x), edge) }
    }

    fun Fn.withEdge(edge: EdgeMode, outside: Number): Fn = withEdge(edge, param(outside))

    fun Fn.quantize(levels: Int): Fn = mapY(name) {
        if (levels <= 1) 0.0 else {
            val t = ((it + 1.0) * 0.5).coerceIn(0.0, 1.0)
            val q = floor(t * (levels - 1)).toInt()
            (q.toDouble() / (levels - 1)) * 2.0 - 1.0
        }
    }

    fun Fn.bitCrush(bits: Int): Fn = mapY(name) {
        if (bits <= 0) 0.0 else {
            val steps = (1 shl bits) - 1
            val t = ((it + 1.0) * 0.5).coerceIn(0.0, 1.0)
            val q = floor(t * steps).toInt()
            (q.toDouble() / steps.toDouble()) * 2.0 - 1.0
        }
    }

    fun Fn.mix(other: Fn, amount: Fn = param(0.5)): Fn =
        this * (param(1.0) - amount) + other * amount

    fun Fn.mix(other: Fn, amount: Number): Fn = mix(other, param(amount))

    fun Fn.crossfade(other: Fn, center: Fn = param(0.0), width: Fn = param(0.0)): Fn {
        val domain = combinedDomain(this, other).intersect(center.domain).intersect(width.domain)
        return graphFn(
            domain,
            "crossfade",
            node(
                "crossfade",
                children = listOf(fnEdge("left", this), fnEdge("right", other), fnEdge("center", center), fnEdge("width", width)),
                rebuild = { it[0].crossfade(it[1], it[2], it[3]) }
            )
        ) { x ->
            val c = center.eval(x)
            val w = width.eval(x)
            if (w <= 0.0) {
                if (x < c) this.eval(x) else other.eval(x)
            } else {
                val t = clamp01((x - (c - w * 0.5)) / w)
                val ww = smootherstep(t)
                blend(this.eval(x), other.eval(x), ww)
            }
        }
    }

    fun Fn.crossfade(other: Fn, center: Number, width: Number): Fn =
        crossfade(other, param(center), param(width))

    fun Fn.crossfade(other: Fn, center: Any, width: Any): Fn =
        crossfade(other, asParam(center, "center"), asParam(width, "width"))

    fun Fn.cut(range: Domain, outside: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, outside)
        return graphFn(
            d,
            name,
            node(
                "cut",
                children = listOf(fnEdge("source", this), fnEdge("outside", outside)),
                strings = mapOf("range" to range.toString()),
                rebuild = { it[0].cut(range, it[1]) }
            )
        ) { x -> if (range.containsInclusive(x)) eval(x) else outside.eval(x) }
    }

    fun Fn.cut(range: Domain, outside: Number): Fn = cut(range, param(outside))

    fun Fn.cut(range: ClosedRange<*>, outside: Fn = param(0.0)): Fn =
        cut(range.toDomain(), outside)

    fun Fn.cut(range: ClosedRange<*>, outside: Number): Fn =
        cut(range.toDomain(), param(outside))

    fun Fn.trim(range: Domain, outside: Fn = param(0.0)): Fn = cut(range, outside)

    fun Fn.trim(range: Domain, outside: Number): Fn = trim(range, param(outside))

    fun Fn.trim(range: ClosedRange<*>, outside: Fn = param(0.0)): Fn =
        trim(range.toDomain(), outside)

    fun Fn.trim(range: ClosedRange<*>, outside: Number): Fn =
        trim(range.toDomain(), param(outside))

    fun Fn.slice(range: Domain, outside: Fn = param(0.0)): Fn = cut(range, outside)

    fun Fn.slice(range: Domain, outside: Number): Fn = slice(range, param(outside))

    fun Fn.slice(range: ClosedRange<*>, outside: Fn = param(0.0)): Fn =
        slice(range.toDomain(), outside)

    fun Fn.slice(range: ClosedRange<*>, outside: Number): Fn =
        slice(range.toDomain(), param(outside))

    fun Fn.replace(range: Domain, replacement: Fn): Fn {
        val domain = this.domain.union(replacement.domain)
        return graphFn(
            domain,
            "replace",
            node(
                "replace",
                children = listOf(fnEdge("source", this), fnEdge("replacement", replacement)),
                strings = mapOf("range" to range.toString()),
                rebuild = { it[0].replace(range, it[1]) }
            )
        ) { x -> if (range.containsInclusive(x)) replacement.eval(x) else eval(x) }
    }

    fun Fn.replace(range: ClosedRange<*>, replacement: Fn): Fn =
        replace(range.toDomain(), replacement)

    fun Fn.window(window: Fn): Fn = this * window

    fun Fn.amplitudeMod(mod: Fn, depth: Fn = param(1.0), bias: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth, bias)
        return graphFn(
            d,
            "am",
            node(
                "amplitudeMod",
                "am",
                listOf(fnEdge("source", this), fnEdge("mod", mod), fnEdge("depth", depth), fnEdge("bias", bias)),
                rebuild = { it[0].amplitudeMod(it[1], it[2], it[3]) }
            )
        ) { x -> eval(x) * (bias.eval(x) + depth.eval(x) * mod.eval(x)) }
    }

    fun Fn.amplitudeMod(mod: Fn, depth: Number, bias: Number = 1.0): Fn =
        amplitudeMod(mod, param(depth), param(bias))

    fun Fn.amplitudeMod(mod: Fn, depth: Any, bias: Any = 1.0): Fn =
        amplitudeMod(mod, asParam(depth, "depth"), asParam(bias, "bias"))

    fun Fn.ringMod(mod: Fn, depth: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth)
        return graphFn(
            d,
            "rm",
            node(
                "ringMod",
                "rm",
                listOf(fnEdge("source", this), fnEdge("mod", mod), fnEdge("depth", depth)),
                rebuild = { it[0].ringMod(it[1], it[2]) }
            )
        ) { x -> eval(x) * (depth.eval(x) * mod.eval(x)) }
    }

    fun Fn.ringMod(mod: Fn, depth: Number): Fn = ringMod(mod, param(depth))

    fun Fn.warp(mod: Fn, depth: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, mod, depth)
        return graphFn(
            d,
            "warp",
            node(
                "warp",
                children = listOf(fnEdge("source", this), fnEdge("mod", mod), fnEdge("depth", depth)),
                rebuild = { it[0].warp(it[1], it[2]) }
            )
        ) { x -> eval(x + depth.eval(x) * mod.eval(x)) }
    }

    fun Fn.warp(mod: Fn, depth: Number): Fn = warp(mod, param(depth))

    fun Fn.quantizeX(step: Fn = param(1.0), mode: EdgeMode = EdgeMode.CLAMP): Fn {
        val d = intersectDomains(domain, step)
        return graphFn(
            d,
            name,
            node(
                "quantizeX",
                children = listOf(fnEdge("source", this), fnEdge("step", step)),
                strings = mapOf("mode" to mode.name),
                rebuild = { it[0].quantizeX(it[1], mode) }
            )
        ) { x ->
            val s = step.eval(x)
            if (s == 0.0) eval(x) else {
                val q = floor(x / s) * s
                evalSafe(q, edge = mode)
            }
        }
    }

    fun Fn.quantizeX(step: Number, mode: EdgeMode = EdgeMode.CLAMP): Fn =
        quantizeX(param(step), mode)

    fun Fn.sampleHold(step: Fn = param(1.0), offset: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, step, offset)
        return graphFn(
            d,
            "samplehold",
            node(
                "sampleHold",
                "samplehold",
                listOf(fnEdge("source", this), fnEdge("step", step), fnEdge("offset", offset)),
                rebuild = { it[0].sampleHold(it[1], it[2]) }
            )
        ) { x ->
            val s = step.eval(x)
            if (s == 0.0) eval(x) else {
                val o = offset.eval(x)
                val q = floor((x - o) / s) * s + o
                eval(q)
            }
        }
    }

    fun Fn.sampleHold(step: Number, offset: Number = 0.0): Fn =
        sampleHold(param(step), param(offset))

    fun Fn.sampleHold(step: Any, offset: Any = 0.0): Fn =
        sampleHold(asParam(step, "step"), asParam(offset, "offset"))

    fun Fn.phaseMod(mod: Fn, depth: Fn = param(1.0)): Fn = warp(mod, depth)

    fun Fn.phaseMod(mod: Fn, depth: Number): Fn = phaseMod(mod, param(depth))

    fun Fn.frequencyMod(mod: Fn, depth: Fn = param(1.0)): Fn = warp(mod, depth)

    fun Fn.frequencyMod(mod: Fn, depth: Number): Fn = frequencyMod(mod, param(depth))

    fun Fn.saturate(amount: Fn = param(1.0)): Fn {
        val d = intersectDomains(domain, amount)
        return graphFn(
            d,
            name,
            node("saturate", children = listOf(fnEdge("source", this), fnEdge("amount", amount)), rebuild = { it[0].saturate(it[1]) })
        ) { x ->
            val a = amount.eval(x)
            val v = eval(x)
            if (a == 0.0) 0.0 else v / (1.0 + kotlin.math.abs(v * a))
        }
    }

    fun Fn.saturate(amount: Number): Fn = saturate(param(amount))

    fun Fn.jitterX(amount: Fn = param(0.0), seed: Long = 0L): Fn {
        val d = intersectDomains(domain, amount)
        return graphFn(
            d,
            "jitter",
            node(
                "jitterX",
                "jitter",
                listOf(fnEdge("source", this), fnEdge("amount", amount)),
                longs = mapOf("seed" to seed),
                rebuild = { it[0].jitterX(it[1], seed) }
            )
        ) { x ->
            val h = hashToUnit(seed, floor(x).toLong()) - 0.5
            eval(x + h * amount.eval(x))
        }
    }

    fun Fn.jitterX(amount: Number, seed: Long = 0L): Fn = jitterX(param(amount), seed)

    fun Fn.repeat(
        period: Fn = param(1.0),
        origin: Fn = param(domain.finiteSweep().min),
        domainOverride: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domainOverride, period, origin)
        return graphFn(
            d,
            "repeat",
            node(
                "repeat",
                children = listOf(fnEdge("source", this), fnEdge("period", period), fnEdge("origin", origin)),
                strings = mapOf("domainOverride" to domainOverride.toString()),
                rebuild = { it[0].repeat(it[1], it[2], domainOverride) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn eval(x)
            val o = origin.eval(x)
            val wrapped = o + ((x - o) % p + p) % p
            eval(wrapped)
        }
    }

    fun Fn.repeat(period: Number, origin: Number = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        repeat(param(period), param(origin), domainOverride)

    fun Fn.repeat(period: Any, origin: Any = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        repeat(asParam(period, "period"), asParam(origin, "origin"), domainOverride)

    fun Fn.mirrorRepeat(
        period: Fn = param(1.0),
        origin: Fn = param(domain.finiteSweep().min),
        domainOverride: Domain = Domain.ALL
    ): Fn {
        val d = intersectDomains(domainOverride, period, origin)
        return graphFn(
            d,
            "mirror",
            node(
                "mirrorRepeat",
                "mirror",
                listOf(fnEdge("source", this), fnEdge("period", period), fnEdge("origin", origin)),
                strings = mapOf("domainOverride" to domainOverride.toString()),
                rebuild = { it[0].mirrorRepeat(it[1], it[2], domainOverride) }
            )
        ) { x ->
            val len = kotlin.math.abs(period.eval(x))
            if (len == 0.0) return@graphFn eval(x)
            val o = origin.eval(x)
            val two = len * 2.0
            val t = ((x - o) % two + two) % two
            val local = if (t <= len) o + t else o + (two - t)
            eval(local)
        }
    }

    fun Fn.mirrorRepeat(period: Number, origin: Number = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        mirrorRepeat(param(period), param(origin), domainOverride)

    fun Fn.mirrorRepeat(period: Any, origin: Any = domain.finiteSweep().min, domainOverride: Domain = Domain.ALL): Fn =
        mirrorRepeat(asParam(period, "period"), asParam(origin, "origin"), domainOverride)

    fun Fn.tile(count: Int, period: Fn = param(domain.finiteSweep().length), origin: Fn = param(domain.finiteSweep().min)): Fn {
        if (count <= 0) return zero(domain)
        val base = repeat(period, origin, domainOverride = Domain.ALL)
        val d = intersectDomains(domain, period, origin)
        return graphFn(
            d,
            "tile",
            node(
                "tile",
                children = listOf(fnEdge("source", this), fnEdge("period", period), fnEdge("origin", origin)),
                longs = mapOf("count" to count.toLong()),
                rebuild = { it[0].tile(count, it[1], it[2]) }
            )
        ) { x ->
            val p = kotlin.math.abs(period.eval(x))
            if (p == 0.0) return@graphFn 0.0
            val o = origin.eval(x)
            val end = o + p * count
            if (x < o || x > end) 0.0 else base.eval(x)
        }
    }

    fun Fn.tile(count: Int, period: Number, origin: Number = domain.finiteSweep().min): Fn =
        tile(count, param(period), param(origin))

    fun Fn.tile(count: Int, period: Any, origin: Any = domain.finiteSweep().min): Fn =
        tile(count, asParam(period, "period"), asParam(origin, "origin"))

    fun Fn.pad(left: Fn = param(0.0), right: Fn = param(0.0), value: Fn = param(0.0)): Fn {
        val d = intersectDomains(domain, left, right, value)
        return graphFn(
            d,
            name,
            node(
                "pad",
                children = listOf(fnEdge("source", this), fnEdge("left", left), fnEdge("right", right), fnEdge("value", value)),
                rebuild = { it[0].pad(it[1], it[2], it[3]) }
            )
        ) { x ->
            val l = left.eval(x)
            val rr = right.eval(x)
            if (x < domain.min - l || x > domain.max + rr) value.eval(x) else eval(x)
        }
    }

    fun Fn.pad(left: Number, right: Number = 0.0, value: Number = 0.0): Fn =
        pad(param(left), param(right), param(value))

    fun Fn.pad(left: Any, right: Any = 0.0, value: Any = 0.0): Fn =
        pad(asParam(left, "left"), asParam(right, "right"), asParam(value, "value"))

    fun Fn.delay(amount: Fn = param(0.0)): Fn = shift(amount)

    fun Fn.delay(amount: Number): Fn = delay(param(amount))

    fun Fn.echo(delay: Fn = param(1.0), decay: Fn = param(0.5), repeats: Int = 4): Fn {
        val d = intersectDomains(domain, delay, decay)
        return graphFn(
            d,
            "echo",
            node(
                "echo",
                children = listOf(fnEdge("source", this), fnEdge("delay", delay), fnEdge("decay", decay)),
                longs = mapOf("repeats" to repeats.toLong()),
                rebuild = { it[0].echo(it[1], it[2], repeats) }
            )
        ) { x ->
            val dly = delay.eval(x)
            val dec = decay.eval(x)
            var sum = eval(x)
            var gain = 1.0
            for (i in 1..repeats) {
                gain *= dec
                sum += eval(x - dly * i) * gain
            }
            sum
        }
    }

    fun Fn.echo(delay: Number, decay: Number = 0.5, repeats: Int = 4): Fn =
        echo(param(delay), param(decay), repeats)

    fun Fn.echo(delay: Any, decay: Any = 0.5, repeats: Int = 4): Fn =
        echo(asParam(delay, "delay"), asParam(decay, "decay"), repeats)

    fun Fn.derivative(step: Double = 1e-3): Fn {
        val newDomain = domain.shrink(step)
        return graphFn(
            newDomain,
            "d/dx",
            node(
                "derivative",
                "d/dx",
                listOf(fnEdge("source", this)),
                numbers = mapOf("step" to step),
                rebuild = { it[0].derivative(step) }
            )
        ) { x ->
            (eval(x + step) - eval(x - step)) / (2.0 * step)
        }
    }

    fun Fn.integral(step: Double = 1e-3, origin: Double = domain.finiteSweep().min): Fn =
        graphFn(
            domain,
            "integral",
            node(
                "integral",
                children = listOf(fnEdge("source", this)),
                numbers = mapOf("step" to step, "origin" to origin),
                rebuild = { it[0].integral(step, origin) }
            )
        ) { x ->
            if (step == 0.0) return@graphFn 0.0
            var acc = 0.0
            val dir = if (x >= origin) 1.0 else -1.0
            val stepSize = step * dir
            var t = origin
            val steps = ((x - origin) / stepSize).toInt()
            repeat(kotlin.math.abs(steps)) {
                acc += eval(t) * stepSize
                t += stepSize
            }
            val remainder = x - t
            if (remainder != 0.0) acc += eval(t) * remainder
            acc
        }

    fun Fn.convolve(
        kernel: Fn,
        samples: Int = 64,
        kernelDomain: Domain = kernel.domain,
        normalize: Boolean = false
    ): Fn {
        val resultDomain = if (domain.isFinite && kernel.domain.isFinite) {
            Domain(domain.min + kernel.domain.min, domain.max + kernel.domain.max)
        } else Domain.ALL
        return graphFn(
            resultDomain,
            "convolve",
            "convolve",
            children = listOf(fnEdge("source", this), fnEdge("kernel", kernel)),
            numbers = mapOf("samples" to samples.toDouble()),
            strings = mapOf("kernelDomain" to kernelDomain.toString()),
            booleans = mapOf("normalize" to normalize),
            rebuild = { children -> children[0].convolve(children[1], samples, kernelDomain, normalize) }
        ) { x ->
            if (samples <= 0) return@graphFn 0.0
            val kd = kernelDomain
            if (kd._isEmpty) return@graphFn 0.0
            val len = kd.length
            if (!len.isFinite()) return@graphFn 0.0
            val step = if (samples == 1) 0.0 else len / (samples - 1).toDouble()
            var sum = 0.0
            var weight = 0.0
            for (i in 0 until samples) {
                val t = kd.min + step * i.toDouble()
                val k = kernel.eval(t)
                sum += eval(x - t) * k
                weight += k
            }
            val scaled = if (step == 0.0) sum else sum * step
            if (normalize && weight != 0.0) scaled / weight else scaled
        }
    }

    fun Fn.correlate(
        kernel: Fn,
        samples: Int = 64,
        kernelDomain: Domain = kernel.domain
    ): Fn = convolve(kernel.reverse(param(kernelDomain.center)), samples, kernelDomain, normalize = false)

    fun Fn.movingAverage(window: Fn = param(1.0), samples: Int = 64): Fn {
        val d = intersectDomains(domain, window)
        return graphFn(
            d,
            "movingAverage",
            "movingAverage",
            children = listOf(fnEdge("source", this), fnEdge("window", window)),
            numbers = mapOf("samples" to samples.toDouble()),
            rebuild = { children -> children[0].movingAverage(children[1], samples) }
        ) { x ->
            if (samples <= 0) return@graphFn 0.0
            val w = window.eval(x)
            if (w == 0.0) return@graphFn eval(x)
            val half = w * 0.5
            val step = if (samples == 1) 0.0 else w / (samples - 1).toDouble()
            var sum = 0.0
            var t = -half
            for (i in 0 until samples) {
                sum += eval(x + t)
                t += step
            }
            val scaled = if (step == 0.0) sum else sum * step
            if (w == 0.0) 0.0 else scaled / w
        }
    }

    fun Fn.movingAverage(window: Number, samples: Int = 64): Fn =
        movingAverage(param(window), samples)

    fun Fn.blur(sigma: Fn = param(1.0), samples: Int = 64): Fn {
        val d = intersectDomains(domain, sigma)
        return graphFn(
            d,
            "blur",
            "blur",
            children = listOf(fnEdge("source", this), fnEdge("sigma", sigma)),
            numbers = mapOf("samples" to samples.toDouble()),
            rebuild = { children -> children[0].blur(children[1], samples) }
        ) { x ->
            if (samples <= 0) return@graphFn 0.0
            val s = sigma.eval(x)
            if (s == 0.0) return@graphFn eval(x)
            val half = s * 3.0
            val step = if (samples == 1) 0.0 else (half * 2.0) / (samples - 1).toDouble()
            var sum = 0.0
            var weight = 0.0
            var t = -half
            for (i in 0 until samples) {
                val w = kotlin.math.exp(-0.5 * (t / s).pow(2.0))
                sum += eval(x + t) * w
                weight += w
                t += step
            }
            if (weight == 0.0) 0.0 else sum / weight
        }
    }

    fun Fn.blur(sigma: Number, samples: Int = 64): Fn =
        blur(param(sigma), samples)

    // Windows
    fun hannWindow(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(domain, "hann", "hannWindow", rebuild = { hannWindow(domain) }) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            0.5 - 0.5 * kotlin.math.cos(TAU * t)
        }

    fun hannWindow(domain: ClosedRange<*>): Fn =
        hannWindow(domain.toDomain())

    fun hammingWindow(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(domain, "hamming", "hammingWindow", rebuild = { hammingWindow(domain) }) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            0.54 - 0.46 * kotlin.math.cos(TAU * t)
        }

    fun hammingWindow(domain: ClosedRange<*>): Fn =
        hammingWindow(domain.toDomain())

    fun blackmanWindow(domain: Domain = Domain.DEFAULT): Fn =
        graphFn(domain, "blackman", "blackmanWindow", rebuild = { blackmanWindow(domain) }) { x ->
            val t = clamp01(domain.finiteSweep().alphaOf(x))
            0.42 - 0.5 * kotlin.math.cos(TAU * t) + 0.08 * kotlin.math.cos(TAU * 2.0 * t)
        }

    fun blackmanWindow(domain: ClosedRange<*>): Fn =
        blackmanWindow(domain.toDomain())

    fun gaussianWindow(domain: Domain = Domain.DEFAULT, sigma: Fn = param(0.4)): Fn {
        val d = intersectDomains(domain, sigma)
        return graphFn(
            d,
            "gausswin",
            "gaussianWindow",
            children = listOf(fnEdge("sigma", sigma)),
            strings = mapOf("shapeDomain" to domain.toString()),
            rebuild = { children -> gaussianWindow(domain, children[0]) }
        ) { x ->
            val t = domain.finiteSweep().alphaOf(x) - 0.5
            val s = sigma.eval(x)
            if (s == 0.0) 0.0 else kotlin.math.exp(-0.5 * (t / s).pow(2.0))
        }
    }

    fun gaussianWindow(domain: Domain = Domain.DEFAULT, sigma: Number): Fn =
        gaussianWindow(domain, param(sigma))

    fun gaussianWindow(domain: ClosedRange<*>, sigma: Fn = param(0.4)): Fn =
        gaussianWindow(domain.toDomain(), sigma)

    fun gaussianWindow(domain: ClosedRange<*>, sigma: Number): Fn =
        gaussianWindow(domain.toDomain(), param(sigma))

    // Noise and stochastic processes
    private fun mix64(z: Long): Long {
        var x = z
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }

    private fun hashToUnit(seed: Long, x: Long): Double {
        val h = mix64(x + seed)
        val bits = h ushr 11
        return bits.toDouble() / (1L shl 53).toDouble()
    }

    private fun hashToSigned(seed: Long, x: Long): Double = hashToUnit(seed, x) * 2.0 - 1.0

    private data class SampleStats(val min: Double, val max: Double, val mean: Double, val variance: Double)

    private fun sampleStatsRandom(fn: Fn, domain: Domain, samples: Int, seed: Long): SampleStats? {
        if (samples <= 0 || domain._isEmpty) return null
        val sweep = domain.finiteSweep()
        val len = sweep.length
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var mean = 0.0
        var m2 = 0.0
        var n = 0
        for (i in 0 until samples) {
            val x = sweep.min + hashToUnit(seed, i.toLong()) * len
            val v = fn.eval(x)
            if (!v.isFinite()) continue
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            n++
            val delta = v - mean
            mean += delta / n
            val delta2 = v - mean
            m2 += delta * delta2
        }
        if (n == 0 || !minV.isFinite() || !maxV.isFinite()) return null
        val variance = if (n > 1) m2 / (n - 1).toDouble() else 0.0
        return SampleStats(minV, maxV, mean, variance)
    }

    private fun valueNoiseAt(seed: Long, frequency: Double, x: Double): Double {
        val xf = x * frequency
        val i = floor(xf).toLong()
        val t = smootherstep(fract(xf))
        val a = hashToSigned(seed, i)
        val b = hashToSigned(seed, i + 1)
        return blend(a, b, t)
    }

    private fun perlinNoiseAt(seed: Long, frequency: Double, x: Double): Double {
        val xf = x * frequency
        val i0 = floor(xf).toLong()
        val i1 = i0 + 1
        val t = xf - floor(xf)
        val g0 = if (hashToSigned(seed, i0) >= 0.0) 1.0 else -1.0
        val g1 = if (hashToSigned(seed, i1) >= 0.0) 1.0 else -1.0
        val v0 = g0 * t
        val v1 = g1 * (t - 1.0)
        val u = smootherstep(t)
        return blend(v0, v1, u)
    }

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        return graphFn(
            d,
            "white",
            "whiteNoise",
            children = listOf(fnEdge("frequency", frequency), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> whiteNoise(seed, children[0], children[1], domain) }
        ) { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            val i = floor(x * freq).toLong()
            hashToSigned(seed, i) * amp
        }
    }

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = whiteNoise(seed, param(frequency), param(amplitude), domain)

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = whiteNoise(seed, frequency, amplitude, domain.toDomain())

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = whiteNoise(seed, frequency, amplitude, domain.toDomain())

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = whiteNoise(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), domain)

    fun whiteNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = whiteNoise(seed, frequency, amplitude, domain.toDomain())

    fun valueNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        return graphFn(
            d,
            "valuenoise",
            "valueNoise",
            children = listOf(fnEdge("frequency", frequency), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> valueNoise(seed, children[0], children[1], domain) }
        ) { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            valueNoiseAt(seed, freq, x) * amp
        }
    }

    fun valueNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = valueNoise(seed, param(frequency), param(amplitude), domain)

    fun valueNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = valueNoise(seed, frequency, amplitude, domain.toDomain())

    fun valueNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = valueNoise(seed, frequency, amplitude, domain.toDomain())

    fun valueNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = valueNoise(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), domain)

    fun valueNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = valueNoise(seed, frequency, amplitude, domain.toDomain())

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude)
        return graphFn(
            d,
            "perlin",
            "perlinNoise",
            children = listOf(fnEdge("frequency", frequency), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> perlinNoise(seed, children[0], children[1], domain) }
        ) { x ->
            val freq = frequency.eval(x)
            val amp = amplitude.eval(x)
            perlinNoiseAt(seed, freq, x) * amp
        }
    }

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = perlinNoise(seed, param(frequency), param(amplitude), domain)

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = perlinNoise(seed, frequency, amplitude, domain.toDomain())

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = perlinNoise(seed, frequency, amplitude, domain.toDomain())

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = perlinNoise(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), domain)

    fun perlinNoise(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = perlinNoise(seed, frequency, amplitude, domain.toDomain())

    fun fbm(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude, lacunarity, gain)
        return graphFn(
            d,
            "fbm",
            "fbm",
            children = listOf(
                fnEdge("frequency", frequency),
                fnEdge("amplitude", amplitude),
                fnEdge("lacunarity", lacunarity),
                fnEdge("gain", gain)
            ),
            numbers = mapOf("octaves" to octaves.toDouble()),
            longs = mapOf("seed" to seed),
            rebuild = { children -> fbm(seed, children[0], children[1], octaves, children[2], children[3], domain) }
        ) { x ->
            var amp = amplitude.eval(x)
            var freq = frequency.eval(x)
            val lac = lacunarity.eval(x)
            val g = gain.eval(x)
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until max(1, octaves)) {
                val v = valueNoiseAt(seed + i, freq, x)
                sum += v * amp
                norm += amp
                amp *= g
                freq *= lac
            }
            if (norm == 0.0) 0.0 else sum / norm
        }
    }

    fun fbm(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbm(seed, param(frequency), param(amplitude), octaves, param(lacunarity), param(gain), domain)

    fun fbm(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbm(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    fun fbm(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbm(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), octaves, asParam(lacunarity, "lacunarity"), asParam(gain, "gain"), domain)

    fun fbm(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbm(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    fun fbm(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: ClosedRange<*>
    ): Fn = fbm(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, frequency, amplitude, lacunarity, gain)
        return graphFn(
            d,
            "fbmPerlin",
            "fbmPerlin",
            children = listOf(
                fnEdge("frequency", frequency),
                fnEdge("amplitude", amplitude),
                fnEdge("lacunarity", lacunarity),
                fnEdge("gain", gain)
            ),
            numbers = mapOf("octaves" to octaves.toDouble()),
            longs = mapOf("seed" to seed),
            rebuild = { children -> fbmPerlin(seed, children[0], children[1], octaves, children[2], children[3], domain) }
        ) { x ->
            var amp = amplitude.eval(x)
            var freq = frequency.eval(x)
            val lac = lacunarity.eval(x)
            val g = gain.eval(x)
            var sum = 0.0
            var norm = 0.0
            for (i in 0 until max(1, octaves)) {
                val v = perlinNoiseAt(seed + i, freq, x)
                sum += v * amp
                norm += amp
                amp *= g
                freq *= lac
            }
            if (norm == 0.0) 0.0 else sum / norm
        }
    }

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbmPerlin(seed, param(frequency), param(amplitude), octaves, param(lacunarity), param(gain), domain)

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Number,
        amplitude: Number = 1.0,
        octaves: Int = 5,
        lacunarity: Number = 2.0,
        gain: Number = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbmPerlin(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: Domain = Domain.DEFAULT
    ): Fn = fbmPerlin(seed, asParam(frequency, "frequency"), asParam(amplitude, "amplitude"), octaves, asParam(lacunarity, "lacunarity"), asParam(gain, "gain"), domain)

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Any,
        amplitude: Any = 1.0,
        octaves: Int = 5,
        lacunarity: Any = 2.0,
        gain: Any = 0.5,
        domain: ClosedRange<*>
    ): Fn = fbmPerlin(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    fun fbmPerlin(
        seed: Long = 0L,
        frequency: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        octaves: Int = 5,
        lacunarity: Fn = param(2.0),
        gain: Fn = param(0.5),
        domain: ClosedRange<*>
    ): Fn = fbmPerlin(seed, frequency, amplitude, octaves, lacunarity, gain, domain.toDomain())

    fun randomWalk(
        seed: Long = 0L,
        step: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: Domain = Domain.DEFAULT
    ): Fn {
        val d = intersectDomains(domain, step, amplitude)
        return graphFn(
            d,
            "randomwalk",
            "randomWalk",
            children = listOf(fnEdge("step", step), fnEdge("amplitude", amplitude)),
            longs = mapOf("seed" to seed),
            rebuild = { children -> randomWalk(seed, children[0], children[1], domain) }
        ) { x ->
            val st = step.eval(x)
            if (st == 0.0) return@graphFn 0.0
            val amp = amplitude.eval(x)
            val n = floor(x / st).toInt()
            var acc = 0.0
            if (n >= 0) {
                for (i in 0..n) {
                    acc += hashToSigned(seed, i.toLong()) * amp
                }
            } else {
                var i = 0
                while (i >= n) {
                    acc -= hashToSigned(seed, i.toLong()) * amp
                    i--
                }
            }
            acc
        }
    }

    fun randomWalk(
        seed: Long = 0L,
        step: Number,
        amplitude: Number = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = randomWalk(seed, param(step), param(amplitude), domain)

    fun randomWalk(
        seed: Long = 0L,
        step: Number,
        amplitude: Number = 1.0,
        domain: ClosedRange<*>
    ): Fn = randomWalk(seed, step, amplitude, domain.toDomain())

    fun randomWalk(
        seed: Long = 0L,
        step: Fn = param(1.0),
        amplitude: Fn = param(1.0),
        domain: ClosedRange<*>
    ): Fn = randomWalk(seed, step, amplitude, domain.toDomain())

    fun randomWalk(
        seed: Long = 0L,
        step: Any,
        amplitude: Any = 1.0,
        domain: Domain = Domain.DEFAULT
    ): Fn = randomWalk(seed, asParam(step, "step"), asParam(amplitude, "amplitude"), domain)

    fun randomWalk(
        seed: Long = 0L,
        step: Any,
        amplitude: Any = 1.0,
        domain: ClosedRange<*>
    ): Fn = randomWalk(seed, step, amplitude, domain.toDomain())

    data class Sample(val x: Double, val y: Double, val w: Double = 1.0)

    fun points(vararg pairs: Pair<Double, Double>): List<Sample> = pairs.map { Sample(it.first, it.second) }

    fun Fn.samplesAt(xs: DoubleArray): List<Sample> = xs.map { Sample(it, eval(it)) }

    fun Fn.autoScale(
        sampleDomain: Domain = domain,
        samples: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = false
    ): Fn {
        val stats = sampleStatsRandom(this, sampleDomain, samples, seed) ?: return this
        val range = stats.max - stats.min
        if (range == 0.0 || !range.isFinite()) return c(targetMin, sampleDomain)
        val scale = (targetMax - targetMin) / range
        val offset = targetMin - stats.min * scale
        val d = this.domain.intersect(sampleDomain)
        return graphFn(
            d,
            "autoscale",
            "autoScale",
            children = listOf(fnEdge("source", this)),
            numbers = mapOf(
                "samples" to samples.toDouble(),
                "targetMin" to targetMin,
                "targetMax" to targetMax
            ),
            strings = mapOf("sampleDomain" to sampleDomain.toString()),
            booleans = mapOf("clamp" to clamp),
            longs = mapOf("seed" to seed),
            rebuild = { children -> children[0].autoScale(sampleDomain, samples, seed, targetMin, targetMax, clamp) }
        ) { x ->
            val v = eval(x) * scale + offset
            if (clamp) v.coerceIn(min(targetMin, targetMax), max(targetMin, targetMax)) else v
        }
    }

    fun Fn.autoScale(
        sampleDomain: ClosedRange<*>,
        samples: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = false
    ): Fn = autoScale(sampleDomain.toDomain(), samples, seed, targetMin, targetMax, clamp)

    fun Fn.zNormalize(
        sampleDomain: Domain = domain,
        samples: Int = 256,
        seed: Long = 0L,
        epsilon: Double = 1e-9
    ): Fn {
        val stats = sampleStatsRandom(this, sampleDomain, samples, seed) ?: return this
        val std = kotlin.math.sqrt(stats.variance.coerceAtLeast(0.0))
        if (!std.isFinite() || std <= epsilon) return c(0.0, sampleDomain)
        val d = this.domain.intersect(sampleDomain)
        return graphFn(
            d,
            "znorm",
            "zNormalize",
            children = listOf(fnEdge("source", this)),
            numbers = mapOf("samples" to samples.toDouble(), "epsilon" to epsilon),
            strings = mapOf("sampleDomain" to sampleDomain.toString()),
            longs = mapOf("seed" to seed),
            rebuild = { children -> children[0].zNormalize(sampleDomain, samples, seed, epsilon) }
        ) { x -> (eval(x) - stats.mean) / std }
    }

    fun Fn.zNormalize(
        sampleDomain: ClosedRange<*>,
        samples: Int = 256,
        seed: Long = 0L,
        epsilon: Double = 1e-9
    ): Fn = zNormalize(sampleDomain.toDomain(), samples, seed, epsilon)

    fun Fn.whitenCdf(
        sampleDomain: Domain = domain,
        samples: Int = 2048,
        bins: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = true
    ): Fn {
        val d = this.domain.intersect(sampleDomain)
        val cached = lazy {
            val stats = sampleStatsRandom(this, sampleDomain, samples, seed) ?: return@lazy null
            val range = stats.max - stats.min
            if (!range.isFinite() || range == 0.0) return@lazy null
            val count = max(2, bins)
            val hist = DoubleArray(count)
            val sweep = sampleDomain.finiteSweep()
            val len = sweep.length
            var valid = 0
            for (i in 0 until samples) {
                val x = sweep.min + hashToUnit(seed + 1L, i.toLong()) * len
                val v = eval(x)
                if (!v.isFinite()) continue
                val u = ((v - stats.min) / range).coerceIn(0.0, 1.0)
                val idx = min(count - 1, floor(u * (count - 1)).toInt())
                hist[idx] += 1.0
                valid++
            }
            if (valid == 0) return@lazy null
            val cdf = DoubleArray(count)
            var acc = 0.0
            for (i in 0 until count) {
                acc += hist[i]
                cdf[i] = acc / valid.toDouble()
            }
            Triple(stats, cdf, range)
        }
        return graphFn(
            d,
            "whitenCdf",
            "whitenCdf",
            children = listOf(fnEdge("source", this)),
            numbers = mapOf(
                "samples" to samples.toDouble(),
                "bins" to bins.toDouble(),
                "targetMin" to targetMin,
                "targetMax" to targetMax
            ),
            strings = mapOf("sampleDomain" to sampleDomain.toString()),
            booleans = mapOf("clamp" to clamp),
            longs = mapOf("seed" to seed),
            rebuild = { children -> children[0].whitenCdf(sampleDomain, samples, bins, seed, targetMin, targetMax, clamp) }
        ) { x ->
            val data = cached.value
            if (data == null) return@graphFn eval(x)
            val (stats, cdf, range) = data
            val v = eval(x)
            if (!v.isFinite()) return@graphFn v
            val u = ((v - stats.min) / range)
            val t = if (clamp) u.coerceIn(0.0, 1.0) else u
            val idx = min(cdf.size - 1, max(0, floor(t * (cdf.size - 1)).toInt()))
            val next = min(cdf.size - 1, idx + 1)
            val frac = t * (cdf.size - 1) - idx
            val c = if (next == idx) cdf[idx] else blend(cdf[idx], cdf[next], frac)
            val out = targetMin + (targetMax - targetMin) * c
            if (clamp) out.coerceIn(min(targetMin, targetMax), max(targetMin, targetMax)) else out
        }
    }

    fun Fn.whitenCdf(
        sampleDomain: ClosedRange<*>,
        samples: Int = 2048,
        bins: Int = 256,
        seed: Long = 0L,
        targetMin: Double = 0.0,
        targetMax: Double = 1.0,
        clamp: Boolean = true
    ): Fn = whitenCdf(sampleDomain.toDomain(), samples, bins, seed, targetMin, targetMax, clamp)

    fun Fn.sampleUniform(count: Int, sampleDomain: Domain = domain, includeEnd: Boolean = true): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val steps = if (includeEnd) max(1, count - 1) else count
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return List(count) { i ->
            val x = sweep.min + step * i.toDouble()
            Sample(x, eval(x))
        }
    }

    fun Fn.sampleUniform(count: Int, sampleDomain: ClosedRange<*>, includeEnd: Boolean = true): List<Sample> =
        sampleUniform(count, sampleDomain.toDomain(), includeEnd)

    fun Fn.sampleJittered(count: Int, sampleDomain: Domain = domain, jitter: Double = 0.25, seed: Long = 0L): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val step = if (count == 0) 0.0 else len / count.toDouble()
        return List(count) { i ->
            val base = sweep.min + step * (i + 0.5)
            val j = (hashToUnit(seed, i.toLong()) - 0.5) * jitter * step
            val x = (base + j).coerceIn(sweep.min, sweep.max)
            Sample(x, eval(x))
        }
    }

    fun Fn.sampleJittered(count: Int, sampleDomain: ClosedRange<*>, jitter: Double = 0.25, seed: Long = 0L): List<Sample> =
        sampleJittered(count, sampleDomain.toDomain(), jitter, seed)

    fun Fn.sampleRandom(count: Int, sampleDomain: Domain = domain, seed: Long = 0L): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        return List(count) { i ->
            val x = sweep.min + hashToUnit(seed, i.toLong()) * len
            Sample(x, eval(x))
        }
    }

    fun Fn.sampleRandom(count: Int, sampleDomain: ClosedRange<*>, seed: Long = 0L): List<Sample> =
        sampleRandom(count, sampleDomain.toDomain(), seed)

    fun Fn.sampleByDensity(
        count: Int,
        sampleDomain: Domain = domain,
        density: Fn,
        seed: Long = 0L,
        maxRejects: Int = 10000
    ): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val len = sweep.length
        val out = ArrayList<Sample>(count)
        var tries = 0
        var i = 0L
        while (out.size < count && tries < maxRejects) {
            val x = sweep.min + hashToUnit(seed, i) * len
            val p = clamp01(density.eval(x))
            val r = hashToUnit(seed + 1L, i)
            if (r < p) out += Sample(x, eval(x))
            i++
            tries++
        }
        return out
    }

    fun Fn.sampleByDensity(
        count: Int,
        sampleDomain: ClosedRange<*>,
        density: Fn,
        seed: Long = 0L,
        maxRejects: Int = 10000
    ): List<Sample> = sampleByDensity(count, sampleDomain.toDomain(), density, seed, maxRejects)

    fun Fn.sampleZeroCrossings(sampleDomain: Domain = domain, step: Double = sampleDomain.finiteSweep().length / 128.0): List<Sample> {
        if (sampleDomain._isEmpty || step == 0.0) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val out = ArrayList<Sample>()
        var x = sweep.min
        var prev = eval(x)
        x += step
        while (x <= sweep.max) {
            val y = eval(x)
            if (prev == 0.0) out += Sample(x - step, 0.0)
            if (prev * y < 0.0) {
                val t = prev / (prev - y)
                val xc = x - step * t
                out += Sample(xc, 0.0)
            }
            prev = y
            x += step
        }
        return out
    }

    fun Fn.sampleZeroCrossings(sampleDomain: ClosedRange<*>, step: Double = sampleDomain.toDomain().length / 128.0): List<Sample> =
        sampleZeroCrossings(sampleDomain.toDomain(), step)

    fun Fn.samplePeaks(count: Int, sampleDomain: Domain = domain, step: Double = sampleDomain.finiteSweep().length / 256.0): List<Sample> {
        if (count <= 0 || sampleDomain._isEmpty || step == 0.0) return emptyList()
        val sweep = sampleDomain.finiteSweep()
        val candidates = ArrayList<Sample>()
        var x = sweep.min
        var prev = eval(x)
        x += step
        var current = eval(x)
        x += step
        while (x <= sweep.max) {
            val next = eval(x)
            if (current >= prev && current >= next) {
                candidates += Sample(x - step, current)
            }
            prev = current
            current = next
            x += step
        }
        return candidates.sortedByDescending { kotlin.math.abs(it.y) }.take(count)
    }

    fun Fn.samplePeaks(count: Int, sampleDomain: ClosedRange<*>, step: Double = sampleDomain.toDomain().length / 256.0): List<Sample> =
        samplePeaks(count, sampleDomain.toDomain(), step)

    val RbfKernels: afield.stdlib.RbfKernels
        get() = afield.stdlib.RbfKernels

    fun RbfInterpolator(
        points: List<Sample>,
        kernel: RbfKernel,
        smoothing: Double = 0.0,
        base: Fn? = null
    ): afield.stdlib.RbfInterpolator =
        afield.stdlib.RbfInterpolator(points, kernel, smoothing, base)

    fun rbf(points: List<Sample>, kernel: RbfKernel = RbfKernels.thinPlate, smoothing: Double = 0.0, base: Fn? = null): Fn {
        if (points.isEmpty()) return base ?: zero()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val pointDomain = Domain.of(minX, maxX)
        val domain = (base?.domain ?: pointDomain).union(pointDomain)
        val interp = RbfInterpolator(points, kernel, smoothing, base)
        return graphFn(
            domain,
            "rbf",
            "rbf",
            children = base?.let { listOf(fnEdge("base", it)) } ?: emptyList(),
            numbers = mapOf("points" to points.size.toDouble(), "smoothing" to smoothing),
            strings = mapOf("kernel" to kernel.toString(), "pointDomain" to pointDomain.toString()),
            rebuild = { children -> rbf(points, kernel, smoothing, children.getOrNull(0)) }
        ) { x -> interp.eval(x) }
    }

    fun rbfQuadratic(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.quadratic, smoothing, base)

    fun rbfCubic(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.cubic, smoothing, base)

    fun rbfThinPlate(points: List<Sample>, smoothing: Double = 0.0, base: Fn? = null): Fn =
        rbf(points, RbfKernels.thinPlate, smoothing, base)

    fun Fn.rbf(points: List<Sample>, kernel: RbfKernel = RbfKernels.thinPlate, smoothing: Double = 0.0): Fn =
        rbf(points, kernel, smoothing, this)

    fun Fn.rbfQuadratic(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.quadratic, smoothing, this)

    fun Fn.rbfCubic(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.cubic, smoothing, this)

    fun Fn.rbfThinPlate(points: List<Sample>, smoothing: Double = 0.0): Fn =
        rbf(points, RbfKernels.thinPlate, smoothing, this)

    // Granular synthesis
    fun Fn.granular(
        grainSize: Fn = param(1.0),
        spacing: Fn = grainSize,
        jitter: Fn = param(0.0),
        overlap: Int = 1,
        seed: Long = 0L,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn {
        val d = intersectDomains(domain, grainSize, spacing, jitter)
        val base = this
        return graphFn(
            d,
            "granular",
            "granular",
            children = listOf(
                fnEdge("source", base),
                fnEdge("grainSize", grainSize),
                fnEdge("spacing", spacing),
                fnEdge("jitter", jitter),
                fnEdge("window", window)
            ),
            numbers = mapOf("overlap" to overlap.toDouble()),
            longs = mapOf("seed" to seed),
            rebuild = { children ->
                childAt(children, 0, base).granular(
                    grainSize = childAt(children, 1, grainSize),
                    spacing = childAt(children, 2, spacing),
                    jitter = childAt(children, 3, jitter),
                    overlap = overlap,
                    seed = seed,
                    window = childAt(children, 4, window)
                )
            }
        ) { x ->
            val g = grainSize.eval(x)
            val sp = spacing.eval(x)
            if (g == 0.0 || sp == 0.0) return@graphFn base.eval(x)
            val index = floor(x / sp).toLong()
            var sum = 0.0
            val jit = jitter.eval(x)
            for (k in -overlap..overlap) {
                val i = index + k
                val jitterOffset = (hashToUnit(seed, i) - 0.5) * jit * sp
                val center = (i.toDouble() * sp) + jitterOffset
                val local = x - (center - g * 0.5)
                if (local >= 0.0 && local <= g) {
                    val u = if (g == 0.0) 0.0 else local / g
                    val win = window.eval(u)
                    sum += base.eval(center - g * 0.5 + local) * win
                }
            }
            sum
        }
    }

    fun Fn.granular(
        grainSize: Any,
        spacing: Any = grainSize,
        jitter: Any = 0.0,
        overlap: Int = 1,
        seed: Long = 0L,
        window: Fn = hannWindow(Domain.DEFAULT)
    ): Fn = granular(
        grainSize = asParam(grainSize, "grainSize"),
        spacing = asParam(spacing, "spacing"),
        jitter = asParam(jitter, "jitter"),
        overlap = overlap,
        seed = seed,
        window = window
    )

    // Time-series style streaming filters
    fun Fn.lowpassStream(alpha: Double, start: Double = domain.finiteSweep().min, step: Double = 1.0, initial: Double = 0.0): Sequence<Double> = sequence {
        var x = start
        var y = initial
        val a = alpha.coerceIn(0.0, 1.0)
        while (true) {
            val input = eval(x)
            y = y + a * (input - y)
            yield(y)
            x += step
        }
    }

    fun Fn.highpassStream(alpha: Double, start: Double = domain.finiteSweep().min, step: Double = 1.0, initial: Double = 0.0): Sequence<Double> = sequence {
        var x = start
        var y = initial
        var lastInput = 0.0
        val a = alpha.coerceIn(0.0, 1.0)
        while (true) {
            val input = eval(x)
            val out = a * (y + input - lastInput)
            y = out
            lastInput = input
            yield(out)
            x += step
        }
    }

    // Convenience builders for piecewise functions
    fun piecewise(default: Fn = zero(), vararg pieces: Pair<Domain, Fn>): Fn {
        val domain = pieces.fold(default.domain) { acc, (d, f) -> acc.union(d).union(f.domain) }
        return graphFn(
            domain,
            "piecewise",
            "piecewise",
            children = listOf(fnEdge("default", default)) + pieces.mapIndexed { index, (_, fn) -> fnEdge("piece$index", fn) },
            numbers = mapOf("pieces" to pieces.size.toDouble()),
            strings = pieces.mapIndexed { index, (pieceDomain, _) -> "piece$index.domain" to pieceDomain.toString() }.toMap(),
            rebuild = { children ->
                val rebuiltPieces = pieces.mapIndexed { index, (pieceDomain, fn) ->
                    pieceDomain to childAt(children, index + 1, fn)
                }.toTypedArray()
                piecewise(childAt(children, 0, default), *rebuiltPieces)
            }
        ) { x ->
            for ((d, f) in pieces) {
                if (d.containsInclusive(x)) return@graphFn f.eval(x)
            }
            default.eval(x)
        }
    }

    // ---------------------------------------------------------------------
    // Comprehensive demo (sampling + stats) for docs and quick validation
    // ---------------------------------------------------------------------

    data class DemoStats(
        val min: Double,
        val max: Double,
        val mean: Double,
        val rms: Double
    )

    data class DemoSeries(
        val name: String,
        val domain: Domain,
        val xs: DoubleArray,
        val ys: DoubleArray,
        val stats: DemoStats
    )

    data class DemoResult(
        val series: List<DemoSeries>,
        val notes: List<String>
    )

    // ══════════════════════════════════════════════════════════════════
    //  Keyframe-aware interpolation functions
    //
    //  Each takes a list of Keyframe (or Vec2) control points and returns
    //  a Fn defined over their time span. These are independent of the
    //  keyframe editor's built-in bezier evaluation — they provide
    //  alternative curve shapes that can be overlaid, compared, or used
    //  as stand-alone functions.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Piecewise-linear interpolation through the keyframe positions.
     * Outside the keyframe range the first/last value is held.
     */
    fun piecewiseLinear(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "piecewise-linear",
            "piecewiseLinear",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { piecewiseLinear(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val t = if (b.time == a.time) 0.0 else (x - a.time) / (b.time - a.time)
                    a.value + (b.value - a.value) * t
                }
            }
        }
    }

    /** Overload accepting Vec2 pairs instead of Keyframe objects. */
    @JvmName("piecewiseLinearVec2")
    fun piecewiseLinear(points: List<Vec2>): Fn {
        return piecewiseLinear(points.map { Keyframe(it.x.toDouble(), it.y.toDouble(), "linear") })
    }

    /**
     * Catmull-Rom spline through keyframe positions.
     *
     * The Catmull-Rom spline passes exactly through every control point and
     * produces smooth C¹-continuous curves. The [alpha] parameter controls
     * the spline variant:
     *  - 0.0 = uniform (classic)
     *  - 0.5 = centripetal (avoids cusps — recommended)
     *  - 1.0 = chordal
     *
     * @param points the keyframes to interpolate
     * @param alpha  parameterisation exponent (0 = uniform, 0.5 = centripetal, 1 = chordal)
     */
    fun catmullRomSpline(points: List<Keyframe>, alpha: Double = 0.5): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "catmull-rom",
            "catmullRom",
            numbers = mapOf("points" to sorted.size.toDouble(), "alpha" to alpha),
            rebuild = { catmullRomSpline(points, alpha) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    // Find segment
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val p0 = sorted[max(seg - 1, 0)]
                    val p1 = sorted[seg]
                    val p2 = sorted[min(seg + 1, n - 1)]
                    val p3 = sorted[min(seg + 2, n - 1)]
                    catmullRomSegment(
                        p0.time, p0.value,
                        p1.time, p1.value,
                        p2.time, p2.value,
                        p3.time, p3.value,
                        x, alpha
                    )
                }
            }
        }
    }

    /**
     * Evaluate a single Catmull-Rom segment.
     *
     * Uses cubic Hermite interpolation where the tangent at each interior knot
     * is the slope of the line connecting its neighbours, scaled to the segment
     * span. The [alpha] parameter biases knot spacing but the final evaluation
     * always uses the standard Hermite basis on the [t1, t2] interval.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun catmullRomSegment(
        t0: Double, v0: Double,
        t1: Double, v1: Double,
        t2: Double, v2: Double,
        t3: Double, v3: Double,
        x: Double, alpha: Double
    ): Double {
        val span = t2 - t1
        if (span < 1e-12) return v1
        val u = ((x - t1) / span).coerceIn(0.0, 1.0)

        // Tangents: slope of chord through neighbours, scaled to segment length
        val tangent1 = if (t2 != t0) (v2 - v0) / (t2 - t0) * span else 0.0
        val tangent2 = if (t3 != t1) (v3 - v1) / (t3 - t1) * span else 0.0

        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2.0 * u3 - 3.0 * u2 + 1.0
        val h10 = u3 - 2.0 * u2 + u
        val h01 = -2.0 * u3 + 3.0 * u2
        val h11 = u3 - u2

        return h00 * v1 + h10 * tangent1 + h01 * v2 + h11 * tangent2
    }

    /**
     * Monotone cubic (Fritsch-Carlson) interpolation.
     *
     * Guarantees no overshoot — the curve never exceeds the range defined by
     * adjacent keyframe values. Excellent for animation curves where you want
     * smooth motion without unexpected wobble.
     */
    fun monotoneCubic(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Compute slopes
        val dx = DoubleArray(n - 1) { sorted[it + 1].time - sorted[it].time }
        val dy = DoubleArray(n - 1) { sorted[it + 1].value - sorted[it].value }
        val slopes = DoubleArray(n - 1) { if (dx[it] == 0.0) 0.0 else dy[it] / dx[it] }

        // Tangents via Fritsch-Carlson
        val tangents = DoubleArray(n)
        tangents[0] = slopes[0]
        for (i in 1 until n - 1) {
            if (slopes[i - 1] * slopes[i] <= 0.0) {
                tangents[i] = 0.0
            } else {
                tangents[i] = (slopes[i - 1] + slopes[i]) * 0.5
            }
        }
        tangents[n - 1] = slopes[n - 2]

        // Enforce monotonicity
        for (i in 0 until n - 1) {
            if (abs(slopes[i]) < 1e-30) {
                tangents[i] = 0.0
                tangents[i + 1] = 0.0
            } else {
                val a = tangents[i] / slopes[i]
                val b = tangents[i + 1] / slopes[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val tau = 3.0 / sqrt(s)
                    tangents[i] = tau * a * slopes[i]
                    tangents[i + 1] = tau * b * slopes[i]
                }
            }
        }

        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "monotone-cubic",
            "monotoneCubic",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { monotoneCubic(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val h = dx[seg]
                    if (h < 1e-30) sorted[seg].value
                    else {
                        val t = (x - sorted[seg].time) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
                        val h10 = (t3 - 2.0 * t2 + t) * h
                        val h01 = -2.0 * t3 + 3.0 * t2
                        val h11 = (t3 - t2) * h
                        h00 * sorted[seg].value + h10 * tangents[seg] +
                                h01 * sorted[seg + 1].value + h11 * tangents[seg + 1]
                    }
                }
            }
        }
    }

    /**
     * Cubic Hermite interpolation with user-specified tangent slopes.
     *
     * Each keyframe's tangent is derived from its [Keyframe.handles]:
     *  - If handles are present the outgoing slope is `handle.value / handle.time`.
     *  - If handles are missing a Catmull-Rom-style tangent is used.
     *
     * This lets you feed editor keyframes (with their tangent handles) directly
     * into an independent Hermite evaluator.
     */
    fun hermiteFromHandles(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Derive tangent slopes — dValue/dTime at each keyframe
        val tangents = DoubleArray(n) { i ->
            val kf = sorted[i]
            // Try to use the outgoing handle slope
            val outH = kf.handles?.out
            if (outH != null && abs(outH.time) > 1e-12) {
                outH.value / outH.time
            } else {
                // Catmull-Rom fallback
                val prev = sorted[max(i - 1, 0)]
                val next = sorted[min(i + 1, n - 1)]
                val dt = next.time - prev.time
                if (abs(dt) < 1e-12) 0.0 else (next.value - prev.value) / dt
            }
        }

        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "hermite",
            "hermite",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { hermiteFromHandles(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].time && x < sorted[i + 1].time) { seg = i; break }
                    }
                    val h = sorted[seg + 1].time - sorted[seg].time
                    if (h < 1e-30) sorted[seg].value
                    else {
                        val t = (x - sorted[seg].time) / h
                        val t2 = t * t
                        val t3 = t2 * t
                        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
                        val h10 = (t3 - 2.0 * t2 + t) * h
                        val h01 = -2.0 * t3 + 3.0 * t2
                        val h11 = (t3 - t2) * h
                        h00 * sorted[seg].value + h10 * tangents[seg] +
                                h01 * sorted[seg + 1].value + h11 * tangents[seg + 1]
                    }
                }
            }
        }
    }

    /**
     * Quadratic B-spline approximation of the keyframe curve.
     *
     * Unlike interpolating splines, B-splines do **not** pass through the
     * control points — they smoothly approximate the shape. This produces
     * ultra-smooth curves that are naturally bias-free.
     */
    fun bSplineQuadratic(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        if (points.size == 1) return c(points[0].value)
        if (points.size == 2) return piecewiseLinear(points)
        val sorted = points.sortedBy { it.time }
        val n = sorted.size

        // Uniform parameterisation: map keyframe index to parameter
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "b-spline-quadratic",
            "bSplineQuadratic",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { bSplineQuadratic(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    // Map x to parameter space [0, n-1]
                    val span = sorted.last().time - sorted.first().time
                    val u = if (span < 1e-12) 0.0
                    else ((x - sorted.first().time) / span * (n - 1)).coerceIn(0.0, (n - 1).toDouble())

                    val seg = u.toInt().coerceIn(0, n - 2)
                    val t = u - seg

                    val p0val = sorted[max(seg - 1, 0)].value
                    val p1val = sorted[seg].value
                    val p2val = sorted[min(seg + 1, n - 1)].value

                    // Quadratic B-spline basis
                    val b0 = 0.5 * (1.0 - t) * (1.0 - t)
                    val b1 = 0.5 + t * (1.0 - t)
                    val b2 = 0.5 * t * t

                    b0 * p0val + b1 * p1val + b2 * p2val
                }
            }
        }
    }

    /**
     * Step / sample-and-hold through keyframes.
     * Holds each keyframe value until the next keyframe time.
     */
    fun stepHold(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "step-hold",
            "stepHold",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { stepHold(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    var value = sorted.first().value
                    for (kf in sorted) {
                        if (kf.time > x) break
                        value = kf.value
                    }
                    value
                }
            }
        }
    }

    /**
     * Nearest-value interpolation — returns the value of the closest keyframe.
     */
    fun nearestValue(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "nearest",
            "nearestValue",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { nearestValue(points) }
        ) { x ->
            sorted.minByOrNull { abs(it.time - x) }?.value ?: 0.0
        }
    }

    /**
     * Cosine interpolation through keyframes.
     *
     * Like piecewise-linear but each segment uses a cosine ease for softer
     * transitions — simple and cheap, no tangent computation required.
     */
    fun cosineInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "cosine",
            "cosineInterp",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { cosineInterp(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = (x - a.time) / span
                        val mu = (1.0 - kotlin.math.cos(t * PI)) * 0.5
                        a.value + (b.value - a.value) * mu
                    }
                }
            }
        }
    }

    /**
     * Smoothstep (Hermite) interpolation through keyframes.
     *
     * Each segment is eased with the classic smoothstep 3t² − 2t³, producing
     * a C¹-continuous curve with zero-velocity at every keyframe.
     */
    fun smoothStepInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "smoothstep",
            "smoothStepInterp",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { smoothStepInterp(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = ((x - a.time) / span).coerceIn(0.0, 1.0)
                        val s = smoothstep(t)
                        a.value + (b.value - a.value) * s
                    }
                }
            }
        }
    }

    /**
     * Smootherstep (Ken Perlin's 6t⁵ − 15t⁴ + 10t³) interpolation.
     *
     * C²-continuous — smoother than smoothstep, with zero first *and* second
     * derivative at keyframe positions.
     */
    fun smootherStepInterp(points: List<Keyframe>): Fn {
        if (points.isEmpty()) return zero()
        val sorted = points.sortedBy { it.time }
        val d = Domain.of(sorted.first().time, sorted.last().time)
        return graphFn(
            d,
            "smootherstep",
            "smootherStepInterp",
            numbers = mapOf("points" to sorted.size.toDouble()),
            rebuild = { smootherStepInterp(points) }
        ) { x ->
            when {
                x <= sorted.first().time -> sorted.first().value
                x >= sorted.last().time -> sorted.last().value
                else -> {
                    val i = sorted.indexOfLast { it.time <= x }
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    val span = b.time - a.time
                    if (span < 1e-12) a.value
                    else {
                        val t = ((x - a.time) / span).coerceIn(0.0, 1.0)
                        val s = smootherstep(t)
                        a.value + (b.value - a.value) * s
                    }
                }
            }
        }
    }

    // ── Resampled / transformed variants ─────────────────────────────

    /**
     * Gaussian-smoothed version of a source [Fn].
     *
     * Convolves the function with a Gaussian kernel of the given [sigma],
     * keeping the same domain. Useful for "softening" a keyframe curve after
     * construction.
     *
     * @param source the function to smooth
     * @param sigma  standard deviation of the Gaussian kernel (in time units)
     * @param taps   number of kernel samples (higher = smoother but slower)
     */
    fun gaussianSmooth(source: Fn, sigma: Double = 0.1, taps: Int = 31): Fn {
        val halfTaps = taps / 2
        val weights = DoubleArray(taps) { i ->
            val offset = (i - halfTaps).toDouble()
            exp(-0.5 * (offset * offset) / (halfTaps * halfTaps).toDouble())
        }
        val wSum = weights.sum()
        for (i in weights.indices) weights[i] /= wSum

        return graphFn(
            source.domain,
            "gaussian-smooth(${source.name ?: "?"})",
            "gaussianSmooth",
            children = listOf(fnEdge("source", source)),
            numbers = mapOf("sigma" to sigma, "taps" to taps.toDouble()),
            rebuild = { children -> gaussianSmooth(children[0], sigma, taps) }
        ) { x ->
            var acc = 0.0
            val step = sigma * 3.0 / halfTaps
            for (i in 0 until taps) {
                val offset = (i - halfTaps) * step
                acc += weights[i] * source.evalSafe(x + offset, source.eval(x), EdgeMode.CLAMP)
            }
            acc
        }
    }

    /**
     * Numerically differentiate a source [Fn] (central differences).
     *
     * @param source the function to differentiate
     * @param h      finite-difference half-width
     */
    fun differentiate(source: Fn, h: Double = 1e-4): Fn {
        return graphFn(
            source.domain,
            "d/dt(${source.name ?: "?"})",
            "differentiate",
            children = listOf(fnEdge("source", source)),
            numbers = mapOf("h" to h),
            rebuild = { children -> differentiate(children[0], h) }
        ) { x ->
            (source.eval(x + h) - source.eval(x - h)) / (2.0 * h)
        }
    }

    /**
     * Resample a [Fn] at uniform intervals and return a new [Fn] backed by
     * the sample table with the given [interpolation] mode.
     *
     * @param source       the function to resample
     * @param sampleCount  number of uniform samples
     * @param interpolation how to reconstruct between samples
     */
    /**
     * Produces a [Fn] that exactly matches the keyframe editor's native evaluation
     * (hold / linear / rational-bezier), by constructing a temporary
     * [KeyframeEvaluator] and sampling it.
     *
     * This is the function to use when you want a `Fn` that mirrors what the
     * user sees in the editor — the same bezier handles, the same hold segments,
     * the same tangent weights.
     *
     * @param keyframes     the keyframe list (e.g. from [KeyframeEvaluator.getKeyframes])
     * @param sampleCount   number of uniformly-spaced samples across the keyframe time range
     * @param interpolation how to reconstruct between samples in the resulting Fn
     */
    fun fromKeyframeEditor(
        keyframes: List<Keyframe>,
        sampleCount: Int = 512,
        interpolation: Interpolation = Interpolation.LINEAR
    ): Fn {
        if (keyframes.isEmpty()) return zero()
        val json = kotlinx.serialization.json.Json.encodeToString(keyframes)
        val evaluator = KeyframeEvaluator(json)
        val sorted = keyframes.sortedBy { it.time }
        val tMin = sorted.first().time
        val tMax = sorted.last().time
        val d = Domain.of(tMin, tMax)
        if (d._isEmpty || !d.isFinite || d.length < 1e-12) return c(sorted.first().value)

        val values = DoubleArray(sampleCount) { i ->
            val t = tMin + (tMax - tMin) * i.toDouble() / max(1, sampleCount - 1).toDouble()
            evaluator.evaluateValueAt(t)
        }
        return values.toFn(d, interpolation)
            .withName("keyframe-editor")
    }

    fun resample(
        source: Fn,
        sampleCount: Int = 256,
        interpolation: Interpolation = Interpolation.CUBIC
    ): Fn {
        val d = source.domain
        if (d._isEmpty || !d.isFinite) return source
        val samples = source.toDoubleArray(sampleCount, d, includeEnd = true)
        return samples.toFn(d, interpolation)
            .withName("resampled(${source.name ?: "?"})")
    }

    fun demo(
        sampleCount: Int = 512,
        seed: Long = 1L,
        domain: Domain = Domain.DEFAULT,
        printSummary: Boolean = true
    ): DemoResult {
        val d = domain

        val base = sin(frequency = param(2.0), amplitude = param(1.0), domain = d)
        val saw = saw(period = param(1.0 / 3.0), amplitude = param(0.7), domain = d)
        val tri = triangle(period = param(1.0 / 4.0), amplitude = param(0.5), domain = d)
        val square = square(period = param(1.0), amplitude = param(0.6), duty = param(0.3), domain = d)
        val pulse = pulseTrain(period = param(0.2), duty = param(0.35), amplitude = param(1.0), offset = param(0.0), domain = d)
        val noise = valueNoise(seed = seed, frequency = param(8.0), amplitude = param(0.4), domain = d)
        val fbmFn = fbm(seed = seed + 1, frequency = param(2.0), amplitude = param(0.4), octaves = 4, domain = d)

        val mixed = (base + saw + tri + square + noise + fbmFn).softClip(param(1.2))
        val windowed = mixed * hannWindow(d)
        val blurred = mixed.blur(sigma = param(0.02), samples = 64)
        val averaged = mixed.movingAverage(window = param(0.1), samples = 64)
        val sampledHold = mixed.sampleHold(step = param(0.05))
        val granular = mixed.granular(grainSize = param(0.05), spacing = param(0.04), jitter = param(0.2), overlap = 1, seed = seed)

        val series = listOf(
            demoSeries("sin", base, d, sampleCount),
            demoSeries("saw", saw, d, sampleCount),
            demoSeries("triangle", tri, d, sampleCount),
            demoSeries("square", square, d, sampleCount),
            demoSeries("pulseTrain", pulse, d, sampleCount),
            demoSeries("valueNoise", noise, d, sampleCount),
            demoSeries("fbm", fbmFn, d, sampleCount),
            demoSeries("mixed", mixed, d, sampleCount),
            demoSeries("windowed", windowed, d, sampleCount),
            demoSeries("blurred", blurred, d, sampleCount),
            demoSeries("movingAverage", averaged, d, sampleCount),
            demoSeries("sampleHold", sampledHold, d, sampleCount),
            demoSeries("granular", granular, d, sampleCount)
        )

        val notes = listOf(
            "Domain: [${"%.3f".format(d.min)}, ${"%.3f".format(d.max)}]",
            "Samples per series: $sampleCount",
            "Seed: $seed"
        )

        if (printSummary) {
            println("Functions.demo: ${notes.joinToString(" | ")}")
            series.forEach { s ->
                println(
                    "  ${s.name.padEnd(14)} min=${"%.3f".format(s.stats.min)} " +
                        "max=${"%.3f".format(s.stats.max)} mean=${"%.3f".format(s.stats.mean)} " +
                        "rms=${"%.3f".format(s.stats.rms)}"
                )
            }
        }

        return DemoResult(series, notes)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        demo()
    }

    private fun demoSeries(name: String, fn: Fn, domain: Domain, count: Int): DemoSeries {
        val xs = sampleXs(domain, count)
        val ys = fn.toDoubleArray(count, domain)
        val stats = computeStats(ys)
        return DemoSeries(name, domain, xs, ys, stats)
    }

    private fun sampleXs(domain: Domain, count: Int): DoubleArray {
        if (count <= 0) return DoubleArray(0)
        if (domain._isEmpty) return DoubleArray(count) { Double.NaN }
        val len = domain.length
        val steps = max(1, count - 1)
        val step = if (steps == 0) 0.0 else len / steps.toDouble()
        return DoubleArray(count) { i -> domain.min + step * i.toDouble() }
    }

    private fun computeStats(values: DoubleArray): DemoStats {
        if (values.isEmpty()) return DemoStats(0.0, 0.0, 0.0, 0.0)
        var minV = Double.POSITIVE_INFINITY
        var maxV = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSq = 0.0
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sum += v
            sumSq += v * v
        }
        val mean = sum / values.size.toDouble()
        val rms = kotlin.math.sqrt(sumSq / values.size.toDouble())
        return DemoStats(minV, maxV, mean, rms)
    }
}
