package afield.math.distribution

import java.util.SplittableRandom
import kotlin.math.exp

/**
 * Something observed, as a log-likelihood per sample: how plausible each sample makes it.
 *
 * Make one with [observed] (data drawn from a family whose parameters are random), with
 * [logLikelihood] (any variable you have written yourself), or implicitly with `x given condition`.
 * Join two with [and]; the likelihoods multiply, as they do for independent observations.
 */
class Evidence internal constructor(
    /** The log-likelihood of this evidence, as a random variable: one value per sample. */
    val logLikelihood: Dist,
) {
    /** Both pieces of evidence: their log-likelihoods add. */
    infix fun and(other: Evidence): Evidence = Evidence(logLikelihood + other.logLikelihood)

    override fun toString(): String = "Evidence(${logLikelihood.describe()})"
}

/**
 * Evidence that [data] were drawn, independently, from [model].
 *
 * [model] is a family whose parameters may be random: `normal(mu, 1)`, `poisson(rate)`,
 * `bernoulli(p)`, `exponential(rate)`, `gamma(k, rate)`, `uniform(a, b)`, and the constant-parameter
 * leaves `beta(a, b)`, `uniformInt`, `die`. Each sample is weighted by the probability (or density)
 * of the data given that sample's parameters, which is what turns a prior into a posterior:
 *
 * ```kotlin
 * val p = uniform(0, 1)                                  // a coin of unknown bias
 * val posterior = p given observed(bernoulli(p), 1, 1, 0, 1, 1, 1, 0, 1, 1, 0)
 * E(posterior)                                           // 8/12: Beta(8, 4), seven heads in ten
 * ```
 *
 * [model] itself is never drawn; only its parameters are read. A model that is arithmetic on a
 * family, such as `mu + normal(0, 1)`, has no density here: write it as `normal(mu, 1)`.
 */
fun observed(model: Dist, data: DoubleArray): Evidence {
    val f = model.family ?: throw IllegalArgumentException(
        "observed(${model.describe()}, …): the model must be a distribution family such as normal(mu, 1), " +
            "poisson(rate) or bernoulli(p), so its density is known; arithmetic on one, like mu + normal(0, 1), has none"
    )
    val params: List<Dist> = when (model.kind) {
        Dist.Kind.Leaf -> model.familyParams!!.map { Dist.dirac(it) }
        Dist.Kind.Param -> model.inputs
        else -> error("a family node is a leaf or a parameterised node")
    }
    val xs = data.copyOf()
    return Evidence(
        Dist(
            "observed ${f.name}×${xs.size}", params,
            { ins, out, n, _ ->
                val p = DoubleArray(ins.size)
                for (i in 0 until n) {
                    for (j in p.indices) p[j] = ins[j][i]
                    var acc = 0.0
                    for (x in xs) acc += f.logDensity(x, p)
                    out[i] = acc
                }
            },
            { ins, _ -> "nlObserved$GLSL_ID(${ins.joinToString(", ")})" },
            glslHelpers = f.glslHelpers,
            glslPrelude = { id ->
                val args = params.indices.joinToString(", ") { "float p$it" }
                val ps = params.indices.map { "p$it" }
                buildString {
                    append("const float nlData$id[${xs.size}] = float[](")
                    append(xs.joinToString(", ") { glslFloat(it) })
                    append(");\n")
                    append("float nlObserved$id($args) {\n")
                    append("    float acc = 0.0;\n")
                    append("    for (int i = 0; i < ${xs.size}; i++) { float x = nlData$id[i]; acc += ${f.glslLogDensity("x", ps)}; }\n")
                    append("    return acc;\n}\n")
                }
            },
        ),
    )
}

/** Evidence that [data] were drawn from [model]. */
fun observed(model: Dist, vararg data: Number): Evidence = observed(model, DoubleArray(data.size) { data[it].toDouble() })

/** Evidence that [data] were drawn from [model]. */
fun observed(model: Dist, data: List<Number>): Evidence = observed(model, DoubleArray(data.size) { data[it].toDouble() })

/**
 * Evidence from a log-likelihood you have written yourself, per sample: for a model that is not one
 * of the families, or a score that is not a probability (`logLikelihood(-distance.squared() / 2)`
 * prefers samples close to a target).
 */
fun logLikelihood(logLik: Dist): Evidence = Evidence(logLik)

/**
 * Every variable of a vector conditioned on the same [evidence], which counts once: for a rhythm,
 * a melody or a curve, `steps.given(steps.sum() eq 5)` is sixteen steps with exactly five hits.
 */
fun List<Dist>.given(evidence: Evidence): List<Dist> = map { it given evidence }

/** Every variable of a vector conditioned on the same [condition] (or weight), which counts once. */
fun List<Dist>.given(condition: Dist): List<Dist> {
    val e = Evidence(condition.asLogWeightNode())
    return map { it given e }
}

/** Thrown by a query when no sample is consistent with the evidence. */
class EvidenceImpossibleException(message: String) : IllegalStateException(message)

/**
 * Draws and their log-weights. [logWeights] is null when the graph had no evidence, and every
 * draw then counts equally.
 */
class WeightedSamples(
    /** `values[root][sample]`. */
    val values: Array<DoubleArray>,
    /** Per sample, the sum of every evidence's log-likelihood; `-Infinity` for a rejected sample. */
    val logWeights: DoubleArray?,
) {
    /** How many draws. */
    val size: Int get() = values.firstOrNull()?.size ?: 0

    /** Weights scaled so the largest is 1, or all 1 without evidence. Throws if every weight is 0. */
    fun normalizedWeights(): DoubleArray = logWeights?.let { normalizeLogWeights(it) } ?: DoubleArray(size) { 1.0 }

    /**
     * Effective sample size, `(Σw)² / Σw²`: how many unweighted draws these are worth. For a
     * condition by rejection it is the number accepted.
     */
    val effectiveSize: Double get() {
        val w = normalizedWeights()
        var s = 0.0; var s2 = 0.0
        for (v in w) { s += v; s2 += v * v }
        return s * s / s2
    }

    /** As many draws as there are, resampled by weight (systematic resampling): unweighted posterior draws, with repeats. */
    fun resampled(rng: SplittableRandom): Array<DoubleArray> {
        if (logWeights == null) return values
        val w = normalizedWeights()
        val n = size
        var total = 0.0
        for (v in w) total += v
        val out = Array(values.size) { DoubleArray(n) }
        val step = total / n
        var u = rng.nextDouble() * step
        var cum = w[0]
        var j = 0
        for (i in 0 until n) {
            while (cum < u && j < n - 1) { j++; cum += w[j] }
            for (r in values.indices) out[r][i] = values[r][j]
            u += step
        }
        return out
    }
}

/** `exp(lw - max)`, with errors for NaN (a negative weight), +∞, and evidence nothing satisfied. */
internal fun normalizeLogWeights(lw: DoubleArray): DoubleArray {
    var m = Double.NEGATIVE_INFINITY
    for (v in lw) {
        if (v.isNaN()) throw IllegalArgumentException("a weight in the evidence is negative or NaN; `x given w` needs w >= 0")
        if (v > m) m = v
    }
    if (m == Double.POSITIVE_INFINITY) throw IllegalArgumentException("a weight in the evidence is infinite")
    if (m == Double.NEGATIVE_INFINITY) throw noSampleSatisfied(lw.size.toLong())
    return DoubleArray(lw.size) { exp(lw[it] - m) }
}

internal fun noSampleSatisfied(n: Long) = EvidenceImpossibleException(
    "none of the $n samples is consistent with the evidence: every weight is 0. If the evidence is " +
        "possible at all, its probability is below about 1/$n; draw more samples or loosen the condition."
)
