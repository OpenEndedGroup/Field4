package afield.math.distribution

import afield.math.gp.GaussianProcess
import afield.math.gp.GpJoint
import afield.math.gp.GpPrediction
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Gaussian processes as distributions
// ---------------------------------------------------------------------------------------------
//
// A fitted Gaussian process answers "what is f at x, and how sure are you" in closed form. As
// distributions, its values can be asked everything else: the chance the curve ever exceeds a
// line, where its peak is, how far apart two of its points are, all by sampling one graph.

/** The GP's posterior at one point, as a normal distribution. */
fun GpPrediction.toDist(): Dist = if (variance <= 0.0) Dist.dirac(mean) else normal(mean, sqrt(variance))

/**
 * The joint posterior at every query point, as correlated distributions: one [Dist] per point.
 *
 * They share their randomness the way the curve does, so `jointSamples(*ds.toTypedArray())` draws
 * whole coherent curves and `maximum(ds)` is the distribution of the curve's peak. Each point is
 * `mean + Σ L[i][j] · z[j]` over independent standard normals `z`, with `L` the covariance factor
 * the joint already samples with ([GpJoint.samplingRank] normals). Built from ordinary nodes, so it
 * lowers to GLSL like anything else, and each point on its own has its exact normal density.
 *
 * The graph has `size × rank` nodes, so this is for tens or a few hundred points, not thousands.
 */
fun GpJoint.toDists(): List<Dist> {
    val mu = mean
    val r = samplingRank
    // the factor's columns, read through the public draw: column j = draw(e_j) - mean
    val cols = Array(r) { j ->
        val e = DoubleArray(r).also { it[j] = 1.0 }
        val x = fromStandardNormal(e)
        DoubleArray(size) { x[it] - mu[it] }
    }
    val z = List(r) { normal(0, 1) }
    return List(size) { i ->
        var d: Dist = Dist.dirac(mu[i])
        for (j in 0 until r) {
            val c = cols[j][i]
            if (c != 0.0) d += z[j] * c
        }
        d
    }
}

/** The posterior at each of [xs] (a process fitted in one dimension), as correlated distributions. */
fun GaussianProcess.dists(xs: DoubleArray): List<Dist> = posterior(xs).toDists()

/** The posterior at one point, as a normal distribution. */
fun GaussianProcess.dist(vararg point: Double): Dist = predict(*point).toDist()

/** The largest of [ds] on each sample: for a curve, its peak. CPU and GPU. */
fun maximum(ds: List<Dist>): Dist {
    require(ds.isNotEmpty()) { "maximum of no distributions" }
    return ds.reduce { a, b -> a.max(b) }
}

/** The smallest of [ds] on each sample. */
fun minimum(ds: List<Dist>): Dist {
    require(ds.isNotEmpty()) { "minimum of no distributions" }
    return ds.reduce { a, b -> a.min(b) }
}

/**
 * The index (0, 1, …) of the largest of [ds] on each sample, the first on a tie: for a curve
 * sampled at `xs`, `argmax(ds)` is which `x` the peak is at. CPU and GPU.
 */
fun argmax(ds: List<Dist>): Dist {
    require(ds.isNotEmpty()) { "argmax of no distributions" }
    return Dist(
        "argmax", ds,
        { ins, out, n, _ ->
            for (i in 0 until n) {
                var best = 0
                var v = ins[0][i]
                for (k in 1 until ins.size) if (ins[k][i] > v) { v = ins[k][i]; best = k }
                out[i] = best.toDouble()
            }
        },
        { ins, _ -> "nlArgmax$GLSL_ID(${ins.joinToString(", ")})" },
        glslPrelude = { id ->
            buildString {
                append("float nlArgmax$id(").append(ds.indices.joinToString(", ") { "float a$it" }).append(") {\n")
                append("    float b = a0; float k = 0.0;\n")
                for (k in 1 until ds.size) append("    if (a$k > b) { b = a$k; k = ${glslFloat(k.toDouble())}; }\n")
                append("    return k;\n}\n")
            }
        },
    )
}
