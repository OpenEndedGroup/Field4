package afield.math.distribution

import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A parametric family: how to draw from it and how likely a value is, for any parameters.
 *
 * A leaf such as `normal(0, 1)` carries its family with constant parameters. A node such as
 * `normal(mu, 1)`, whose mean is itself random, carries the family and takes its parameters from its
 * inputs, sample by sample. The log density is what [observed] uses to weigh a sample by data.
 */
internal class Family(
    val name: String,
    val paramNames: List<String>,
    /** Log density (or log mass) at `x` for parameters `p`; `-inf` outside the support or for bad parameters. */
    val logDensity: (x: Double, p: DoubleArray) -> Double,
    /** The same in GLSL; `x` and `p` are GLSL expressions. */
    val glslLogDensity: (x: String, p: List<String>) -> String,
    val sample: (p: DoubleArray, rng: SplittableRandom) -> Double,
    val glslSample: (p: List<String>, rng: String) -> String,
    /** GLSL helper functions the two GLSL lambdas call, emitted once each, in this order. */
    val glslHelpers: List<String> = emptyList(),
)

private const val LN_SQRT_2PI = 0.9189385332046728

/** `ln Γ(x)` for `x > 0` (Lanczos, g = 7), with the reflection formula below 1/2. */
internal fun lgamma(x: Double): Double {
    if (x < 0.5) return ln(PI / abs(sin(PI * x))) - lgamma(1 - x)
    val c = LANCZOS
    val xx = x - 1
    var a = c[0]
    val t = xx + 7.5
    for (i in 1 until 9) a += c[i] / (xx + i)
    return LN_SQRT_2PI + (xx + 0.5) * ln(t) - t + ln(a)
}

private val LANCZOS = doubleArrayOf(
    0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
    -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
    1.5056327351493116e-7,
)

internal fun stdNormal(rng: SplittableRandom): Double =
    sqrt(-2.0 * ln(1.0 - rng.nextDouble())) * cos(2 * PI * rng.nextDouble())

/** Marsaglia and Tsang's method; shape below 1 is boosted by `u^(1/k)`. NaN for bad parameters. */
internal fun sampleGamma(k: Double, rate: Double, rng: SplittableRandom): Double {
    if (!(k > 0) || !(rate > 0)) return Double.NaN
    if (k < 1) {
        val u = rng.nextDouble()
        return sampleGamma(k + 1, 1.0, rng) * u.pow(1 / k) / rate
    }
    val d = k - 1.0 / 3
    val c = 1 / sqrt(9 * d)
    while (true) {
        var x: Double
        var v: Double
        do { x = stdNormal(rng); v = 1 + c * x } while (v <= 0)
        v = v * v * v
        val u = rng.nextDouble()
        if (u < 1 - 0.0331 * x * x * x * x) return d * v / rate
        if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v / rate
    }
}

/** Knuth's product method below rate 30, Hörmann's PTRS above it. NaN for a negative rate. */
internal fun samplePoisson(lambda: Double, rng: SplittableRandom): Double {
    if (!(lambda >= 0)) return Double.NaN
    if (lambda == 0.0) return 0.0
    if (lambda < 30) {
        val l = exp(-lambda)
        var k = 0
        var p = 1.0
        do { k++; p *= rng.nextDouble() } while (p > l)
        return (k - 1).toDouble()
    }
    val slam = sqrt(lambda)
    val loglam = ln(lambda)
    val b = 0.931 + 2.53 * slam
    val a = -0.059 + 0.02483 * b
    val invAlpha = 1.1239 + 1.1328 / (b - 3.4)
    val vr = 0.9277 - 3.6224 / (b - 2)
    while (true) {
        val u = rng.nextDouble() - 0.5
        val v = rng.nextDouble()
        val us = 0.5 - abs(u)
        val k = floor((2 * a / us + b) * u + lambda + 0.43)
        if (us >= 0.07 && v <= vr) return k
        if (k < 0 || (us < 0.013 && v > us)) continue
        if (ln(v) + ln(invAlpha) - ln(a / (us * us) + b) <= -lambda + k * loglam - lgamma(k + 1)) return k
    }
}

internal const val GLSL_STD_NORMAL = """
float nlStdNormal(inout uint s) { return sqrt(-2.0 * log(1.0 - nlRnd(s))) * cos(6.2831853 * nlRnd(s)); }
"""

internal const val GLSL_LGAMMA = """
float nlLgamma(float x) {
    float acc = 0.0;
    for (int i = 0; i < 8 && x < 8.0; i++) { acc -= log(x); x += 1.0; }
    float z = 1.0 / (x * x);
    return acc + (x - 0.5) * log(x) - x + 0.91893853 + (0.083333333 - z * (0.0027777778 - z * 0.00079365079)) / x;
}
"""

internal const val GLSL_GAMMA = """
float nlGamma(float k, float rate, inout uint s) {
    if (!(k > 0.0) || !(rate > 0.0)) return 0.0 / 0.0;
    float boost = 1.0;
    if (k < 1.0) { boost = pow(nlRnd(s), 1.0 / k); k += 1.0; }
    float d = k - 1.0 / 3.0;
    float c = 1.0 / sqrt(9.0 * d);
    for (int i = 0; i < 64; i++) {
        float x = nlStdNormal(s);
        float v = 1.0 + c * x;
        if (v <= 0.0) continue;
        v = v * v * v;
        float u = nlRnd(s);
        if (u < 1.0 - 0.0331 * x * x * x * x || log(u) < 0.5 * x * x + d * (1.0 - v + log(v))) return boost * d * v / rate;
    }
    return boost * d / rate;
}
"""

internal const val GLSL_POISSON = """
float nlPoisson(float lambda, inout uint s) {
    if (!(lambda >= 0.0)) return 0.0 / 0.0;
    if (lambda < 30.0) {
        float l = exp(-lambda);
        float p = 1.0;
        for (int k = 0; k < 160; k++) { p *= nlRnd(s); if (p <= l) return float(k); }
        return 160.0;
    }
    float slam = sqrt(lambda);
    float loglam = log(lambda);
    float b = 0.931 + 2.53 * slam;
    float a = -0.059 + 0.02483 * b;
    float invAlpha = 1.1239 + 1.1328 / (b - 3.4);
    float vr = 0.9277 - 3.6224 / (b - 2.0);
    for (int i = 0; i < 64; i++) {
        float u = nlRnd(s) - 0.5;
        float v = nlRnd(s);
        float us = 0.5 - abs(u);
        float k = floor((2.0 * a / us + b) * u + lambda + 0.43);
        if (us >= 0.07 && v <= vr) return k;
        if (k < 0.0 || (us < 0.013 && v > us)) continue;
        if (log(v) + log(invAlpha) - log(a / (us * us) + b) <= -lambda + k * loglam - nlLgamma(k + 1.0)) return k;
    }
    return floor(lambda);
}
"""

private const val NEG_INF = Double.NEGATIVE_INFINITY

/** The GLSL stand-in for minus infinity: finite, so sums of it stay ordered, and `exp` of it is 0. */
internal const val GLSL_NEG_INF = "-1e30"

internal object Families {
    val normal = Family(
        "normal", listOf("mu", "sigma"),
        { x, p -> val s = p[1]; if (!(s > 0)) NEG_INF else { val z = (x - p[0]) / s; -0.5 * z * z - ln(s) - LN_SQRT_2PI } },
        { x, p -> "(${p[1]} > 0.0 ? -0.5 * (($x - ${p[0]}) / ${p[1]}) * (($x - ${p[0]}) / ${p[1]}) - log(${p[1]}) - 0.91893853 : $GLSL_NEG_INF)" },
        { p, rng -> p[0] + p[1] * stdNormal(rng) },
        { p, r -> "(${p[0]} + ${p[1]} * nlStdNormal($r))" },
        listOf(GLSL_STD_NORMAL),
    )

    val uniform = Family(
        "uniform", listOf("a", "b"),
        { x, p -> if (p[1] > p[0] && x >= p[0] && x < p[1]) -ln(p[1] - p[0]) else NEG_INF },
        { x, p -> "(${p[1]} > ${p[0]} && $x >= ${p[0]} && $x < ${p[1]} ? -log(${p[1]} - ${p[0]}) : $GLSL_NEG_INF)" },
        { p, rng -> p[0] + (p[1] - p[0]) * rng.nextDouble() },
        { p, r -> "(${p[0]} + (${p[1]} - ${p[0]}) * nlRnd($r))" },
    )

    val exponential = Family(
        "exponential", listOf("rate"),
        { x, p -> if (p[0] > 0 && x >= 0) ln(p[0]) - p[0] * x else NEG_INF },
        { x, p -> "(${p[0]} > 0.0 && $x >= 0.0 ? log(${p[0]}) - ${p[0]} * $x : $GLSL_NEG_INF)" },
        { p, rng -> -ln(1.0 - rng.nextDouble()) / p[0] },
        { p, r -> "(-log(1.0 - nlRnd($r)) / ${p[0]})" },
    )

    val bernoulli = Family(
        "bernoulli", listOf("p"),
        { x, p -> when (x) { 1.0 -> if (p[0] > 0) ln(p[0]) else NEG_INF; 0.0 -> if (p[0] < 1) ln(1 - p[0]) else NEG_INF; else -> NEG_INF } },
        { x, p -> "($x == 1.0 ? (${p[0]} > 0.0 ? log(${p[0]}) : $GLSL_NEG_INF) : ($x == 0.0 ? (${p[0]} < 1.0 ? log(1.0 - ${p[0]}) : $GLSL_NEG_INF) : $GLSL_NEG_INF))" },
        { p, rng -> if (rng.nextDouble() < p[0]) 1.0 else 0.0 },
        { p, r -> "float(nlRnd($r) < ${p[0]})" },
    )

    val poisson = Family(
        "poisson", listOf("rate"),
        { x, p ->
            val l = p[0]
            when {
                x < 0 || x != floor(x) || !(l >= 0) -> NEG_INF
                l == 0.0 -> if (x == 0.0) 0.0 else NEG_INF
                else -> x * ln(l) - l - lgamma(x + 1)
            }
        },
        { x, p -> "($x >= 0.0 && $x == floor($x) && ${p[0]} > 0.0 ? $x * log(${p[0]}) - ${p[0]} - nlLgamma($x + 1.0) : $GLSL_NEG_INF)" },
        { p, rng -> samplePoisson(p[0], rng) },
        { p, r -> "nlPoisson(${p[0]}, $r)" },
        listOf(GLSL_LGAMMA, GLSL_POISSON),
    )

    val gamma = Family(
        "gamma", listOf("shape", "rate"),
        { x, p ->
            val k = p[0]; val r = p[1]
            if (!(k > 0) || !(r > 0) || x <= 0) NEG_INF else (k - 1) * ln(x) - r * x + k * ln(r) - lgamma(k)
        },
        { x, p -> "(${p[0]} > 0.0 && ${p[1]} > 0.0 && $x > 0.0 ? (${p[0]} - 1.0) * log($x) - ${p[1]} * $x + ${p[0]} * log(${p[1]}) - nlLgamma(${p[0]}) : $GLSL_NEG_INF)" },
        { p, rng -> sampleGamma(p[0], p[1], rng) },
        { p, r -> "nlGamma(${p[0]}, ${p[1]}, $r)" },
        listOf(GLSL_STD_NORMAL, GLSL_LGAMMA, GLSL_GAMMA),
    )

    val beta = Family(
        "beta", listOf("a", "b"),
        { x, p ->
            val a = p[0]; val b = p[1]
            if (!(a > 0) || !(b > 0) || x <= 0 || x >= 1) NEG_INF
            else (a - 1) * ln(x) + (b - 1) * ln(1 - x) - (lgamma(a) + lgamma(b) - lgamma(a + b))
        },
        { x, p -> "(${p[0]} > 0.0 && ${p[1]} > 0.0 && $x > 0.0 && $x < 1.0 ? (${p[0]} - 1.0) * log($x) + (${p[1]} - 1.0) * log(1.0 - $x) - (nlLgamma(${p[0]}) + nlLgamma(${p[1]}) - nlLgamma(${p[0]} + ${p[1]})) : $GLSL_NEG_INF)" },
        { p, rng -> val x = sampleGamma(p[0], 1.0, rng); val y = sampleGamma(p[1], 1.0, rng); x / (x + y) },
        { p, r -> "nlBeta(${p[0]}, ${p[1]}, $r)" },
        listOf(GLSL_STD_NORMAL, GLSL_LGAMMA, GLSL_GAMMA, "\nfloat nlBeta(float a, float b, inout uint s) { float x = nlGamma(a, 1.0, s); float y = nlGamma(b, 1.0, s); return x / (x + y); }\n"),
    )
}
