package afield.math.distribution

import afield.document.graph.Box
import afield.math.gp.GpFeatureModel
import afield.math.gp.GpNumerics
import afield.math.gp.GpRbfFeatures
import afield.math.gp.gpRandom
import java.util.SplittableRandom
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Gaussian-process draws in a shader: `#include "gp/<name>"`
// ---------------------------------------------------------------------------------------------

/**
 * A Gaussian process fitted for the GPU: a whole random function a shader can evaluate anywhere.
 *
 * It is the random-feature approximation of an RBF process ([GpRbfFeatures]: [frequencies] sine
 * and cosine pairs), fitted to [values] at [points]. A draw is a function chosen by one number,
 * `curve`: the shader evaluates `name(x, curve)` at any `x`, every pixel that passes the same
 * `curve` sees the same function, and a different `curve` is an independent draw from the
 * posterior. Conditioning is pathwise (Matheron's rule): a draw from the prior, corrected towards
 * the data through the fitted covariance, so a draw passes near the readings and spreads between
 * them exactly as the finite-feature posterior does.
 *
 * [cpu] is the same model on the CPU, a [GpFeatureModel], for histograms, means and checks.
 */
class GpShaderModel(
    /** Where the readings are, one array of [dimensions] coordinates each (1 to 3 dimensions). */
    points: Array<DoubleArray>,
    /** The reading at each point. */
    values: DoubleArray,
    /** The RBF length scale along each coordinate. */
    lengthScales: DoubleArray,
    /** The prior variance of the function (its spread, squared, far from the data). */
    val variance: Double = 1.0,
    /** The measurement noise of the readings. */
    val noiseStd: Double = 0.05,
    /** The prior mean: what the function returns to far from the data. */
    val mean: Double = 0.0,
    /** Sine/cosine pairs in the approximation; more is closer to the exact RBF and slower per pixel. */
    val frequencies: Int = 64,
    /** Seeds the frequencies: a different seed is a slightly different approximation, not a different draw. */
    val featureSeed: Long = 0L,
) {
    /** How many coordinates a point has. */
    val dimensions: Int = lengthScales.size
    private val xs = points.map { it.copyOf() }
    private val ys = values.copyOf()
    private val scales = lengthScales.copyOf()

    init {
        require(dimensions in 1..3) { "a shader GP takes 1, 2 or 3 coordinates" }
        require(xs.size == ys.size && xs.all { it.size == dimensions }) { "one value per point, each point of $dimensions coordinates" }
        require(xs.size <= 64) { "at most 64 readings: each one is a loop iteration in every shader call" }
        require(frequencies in 1..256) { "frequencies must be in 1..256" }
        require(variance > 0 && noiseStd >= 0) { "variance must be positive and noise non-negative" }
    }

    /** The same model on the CPU: the finite-feature posterior the shader draws from. */
    val cpu: GpFeatureModel = GpRbfFeatures(scales, frequencies, variance, featureSeed)
        .fit(xs.toTypedArray(), ys, DoubleArray(ys.size) { noiseStd }, mean = { mean }, numerics = GpNumerics())

    // the frequencies GpRbfFeatures drew: the same generator and order
    private val freq: Array<DoubleArray> = gpRandom(featureSeed).let { rng -> Array(frequencies) { DoubleArray(dimensions) { rng.nextGaussian() / scales[it] } } }
    private val amplitude = sqrt(variance / frequencies)
    private val featureCount = 2 * frequencies

    private fun features(p: DoubleArray): DoubleArray {
        val out = DoubleArray(featureCount)
        for (m in 0 until frequencies) {
            var ph = 0.0
            for (k in 0 until dimensions) ph += freq[m][k] * p[k]
            out[2 * m] = amplitude * cos(ph); out[2 * m + 1] = amplitude * sin(ph)
        }
        return out
    }

    private val n = ys.size
    private val phi = Array(n) { features(xs[it]) }
    private val centred = DoubleArray(n) { ys[it] - mean }
    /** `(K + Σ)^-1` by solving against unit vectors, row-major. */
    private val inverse: Array<DoubleArray> = Array(n) { i -> cpu.process.solveResidual(DoubleArray(n).also { it[i] = 1.0 }) }
    /** The noise a pseudo-observation carries, including the jitter the fit needed. */
    private val pseudoNoise = sqrt(noiseStd * noiseStd + cpu.process.jitterUsed)

    /**
     * One draw evaluated on the CPU the same way the shader does it, with this [rng] in place of the
     * shader's stream: for checks, and for a box that wants a few draws without a GPU.
     */
    fun drawCpu(rng: SplittableRandom): (DoubleArray) -> Double {
        val w = DoubleArray(featureCount) { stdNormal(rng) }
        val r = DoubleArray(n) { i ->
            var acc = centred[i]
            for (j in 0 until featureCount) acc -= phi[i][j] * w[j]
            acc - pseudoNoise * stdNormal(rng)
        }
        val alpha = DoubleArray(n) { i -> var a = 0.0; for (k in 0 until n) a += inverse[i][k] * r[k]; a }
        val weights = DoubleArray(featureCount) { j -> var c = w[j]; for (i in 0 until n) c += alpha[i] * phi[i][j]; c }
        return { p -> val f = features(p); var v = mean; for (j in f.indices) v += f[j] * weights[j]; v }
    }

    /** The GLSL of `#include "gp/<name>"`. See [Box.gpHere]. */
    fun toGlslInclude(name: String): String {
        requireGlslName(name)
        val xType = when (dimensions) { 1 -> "float"; 2 -> "vec2"; else -> "vec3" }
        fun arr(v: DoubleArray) = v.joinToString(", ") { glslFloat(it) }
        val F = featureCount
        return buildString {
            append(GLSL_RUNTIME)
            append(guardedHelper(GLSL_STD_NORMAL))
            append("#ifndef NL_GP_$name\n#define NL_GP_$name\n")
            append("const int ${name}_N = $n;\n")
            append("const int ${name}_M = $frequencies;\n")
            if (n > 0) {
                append("const float ${name}_POINTS[${n * dimensions}] = float[](${arr(DoubleArray(n * dimensions) { xs[it / dimensions][it % dimensions] })});\n")
                append("const float ${name}_VALUES[$n] = float[](${arr(ys)});\n")
            }
            append("const float ${name}_FREQ[${frequencies * dimensions}] = float[](${arr(DoubleArray(frequencies * dimensions) { freq[it / dimensions][it % dimensions] })});\n")
            if (n > 0) {
                append("const float ${name}_PHI[${n * F}] = float[](${arr(DoubleArray(n * F) { phi[it / F][it % F] })});\n")
                append("const float ${name}_INV[${n * n}] = float[](${arr(DoubleArray(n * n) { inverse[it / n][it % n] })});\n")
                append("const float ${name}_Y[$n] = float[](${arr(centred)});\n")
            }
            append("float ${name}_phase(int m, $xType x) {\n")
            append("    float ph = 0.0;\n")
            for (k in 0 until dimensions) {
                val comp = if (dimensions == 1) "x" else "x[$k]"
                append("    ph += ${name}_FREQ[m * $dimensions + $k] * $comp;\n")
            }
            append("    return ph;\n}\n")
            // a draw: prior weights, then the Matheron correction towards the data
            append("float $name($xType x, uint curve) {\n")
            append("    uint s = nlHash(curve * 747796405u + 0x632be5abu);\n")
            append("    float w[$F];\n")
            append("    for (int j = 0; j < $F; j++) w[j] = nlStdNormal(s);\n")
            if (n > 0) {
                append("    float r[$n];\n")
                append("    for (int i = 0; i < $n; i++) {\n")
                append("        float acc = ${name}_Y[i];\n")
                append("        for (int j = 0; j < $F; j++) acc -= ${name}_PHI[i * $F + j] * w[j];\n")
                append("        r[i] = acc - ${glslFloat(pseudoNoise)} * nlStdNormal(s);\n")
                append("    }\n")
                append("    float alpha[$n];\n")
                append("    for (int i = 0; i < $n; i++) { float a = 0.0; for (int k = 0; k < $n; k++) a += ${name}_INV[i * $n + k] * r[k]; alpha[i] = a; }\n")
            }
            append("    float v = ${glslFloat(mean)};\n")
            append("    for (int m = 0; m < $frequencies; m++) {\n")
            append("        float ph = ${name}_phase(m, x);\n")
            append("        float wc = w[2 * m];\n")
            append("        float ws = w[2 * m + 1];\n")
            if (n > 0) append("        for (int i = 0; i < $n; i++) { wc += alpha[i] * ${name}_PHI[i * $F + 2 * m]; ws += alpha[i] * ${name}_PHI[i * $F + 2 * m + 1]; }\n")
            append("        v += ${glslFloat(amplitude)} * (cos(ph) * wc + sin(ph) * ws);\n")
            append("    }\n")
            append("    return v;\n}\n")
            // the posterior mean and standard deviation, without drawing
            append("float ${name}_mean($xType x) {\n")
            append("    float v = ${glslFloat(mean)};\n")
            if (n > 0) {
                append("    float kx[$n];\n")
                append("    for (int i = 0; i < $n; i++) kx[i] = 0.0;\n")
                append("    for (int m = 0; m < $frequencies; m++) {\n")
                append("        float ph = ${name}_phase(m, x);\n")
                append("        float c = ${glslFloat(amplitude)} * cos(ph), sn = ${glslFloat(amplitude)} * sin(ph);\n")
                append("        for (int i = 0; i < $n; i++) kx[i] += c * ${name}_PHI[i * $F + 2 * m] + sn * ${name}_PHI[i * $F + 2 * m + 1];\n")
                append("    }\n")
                append("    for (int i = 0; i < $n; i++) { float a = 0.0; for (int k = 0; k < $n; k++) a += ${name}_INV[i * $n + k] * ${name}_Y[k]; v += kx[i] * a; }\n")
            }
            append("    return v;\n}\n")
            append("float ${name}_sd($xType x) {\n")
            if (n > 0) {
                append("    float kx[$n];\n")
                append("    for (int i = 0; i < $n; i++) kx[i] = 0.0;\n")
                append("    for (int m = 0; m < $frequencies; m++) {\n")
                append("        float ph = ${name}_phase(m, x);\n")
                append("        float c = ${glslFloat(amplitude)} * cos(ph), sn = ${glslFloat(amplitude)} * sin(ph);\n")
                append("        for (int i = 0; i < $n; i++) kx[i] += c * ${name}_PHI[i * $F + 2 * m] + sn * ${name}_PHI[i * $F + 2 * m + 1];\n")
                append("    }\n")
                append("    float q = 0.0;\n")
                append("    for (int i = 0; i < $n; i++) for (int k = 0; k < $n; k++) q += kx[i] * ${name}_INV[i * $n + k] * kx[k];\n")
                append("    return sqrt(max(${glslFloat(variance)} - q, 0.0));\n}\n")
            } else append("    return ${glslFloat(sqrt(variance))};\n}\n")
            append("#endif\n")
        }
    }

    companion object {
        /** A one-dimensional model through readings [ys] at [xs]. */
        fun of(xs: DoubleArray, ys: DoubleArray, lengthScale: Double = 1.0, variance: Double = 1.0, noiseStd: Double = 0.05,
               mean: Double = 0.0, frequencies: Int = 64, featureSeed: Long = 0L): GpShaderModel =
            GpShaderModel(Array(xs.size) { doubleArrayOf(xs[it]) }, ys, doubleArrayOf(lengthScale), variance, noiseStd, mean, frequencies, featureSeed)
    }
}

internal object GpIncludes {
    private val byBox = WeakHashMap<Box, MutableMap<String, String>>()
    private val global = ConcurrentHashMap<String, String>()
    @Synchronized fun put(box: Box, name: String, src: String) { byBox.getOrPut(box) { HashMap() }[name] = src }
    fun putGlobal(name: String, src: String) { global[name] = src }
    @Synchronized fun find(box: Box?, name: String): String? {
        if (box != null) {
            val seen = HashSet<Box>()
            var frontier = listOf(box)
            while (frontier.isNotEmpty()) {
                for (b in frontier) byBox[b]?.get(name)?.let { return it }
                seen += frontier
                frontier = frontier.flatMap { b -> b.parents.filter { it !in seen } }.distinct()
            }
        }
        return global[name]
    }
}

/** The text of `#include "gp/<name>"` for a shader of [inside]; called by the shader include resolver. */
fun gpIncludeSource(inside: Box?, name: String): String {
    val key = name.removeSuffix(".glsl")
    return GpIncludes.find(inside, key) ?: throw IllegalStateException(
        "#include \"gp/$key\": no Gaussian process is called '$key' in this box or above it. Call gpHere(\"$key\", model) before the shader compiles."
    )
}

/**
 * Make [model] available to this box's shaders, and its descendants', as `#include "gp/<name>"`.
 * The include defines, for `x` a `float`, `vec2` or `vec3` to match the model:
 *
 * - `float name(x, uint curve)`: the value at `x` of draw number `curve`. The same `curve` is the
 *   same function everywhere, so a fragment shader can draw a whole curve or field.
 * - `float name_mean(x)` and `float name_sd(x)`: the posterior mean and standard deviation.
 * - `name_N` readings at `name_POINTS` (`name_N × dimensions` floats) with values `name_VALUES`.
 *
 * Each call costs about `frequencies × readings` multiply-adds, plus `2 × frequencies` normal draws.
 */
fun Box.gpHere(name: String, model: GpShaderModel): GpShaderModel = model.also { GpIncludes.put(this, name, it.toGlslInclude(name)) }

/** Make [model] available to every shader in the process as `#include "gp/<name>"`, for code with no box. */
fun gpInclude(name: String, model: GpShaderModel): GpShaderModel = model.also { GpIncludes.putGlobal(name, it.toGlslInclude(name)) }

