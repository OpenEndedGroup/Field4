package afield.math.distribution

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.GpuProbe

/**
 * Lowers a [Dist] graph to GLSL.
 *
 * The graph becomes one straight-line function, `float nlSample(inout uint s)`, with one local per
 * node in evaluation order — so a node that is used twice is computed once and read twice, exactly
 * as on the CPU, and `x - x` is still exactly 0. Leaves draw through a PCG-hash uniform `nlRnd`.
 *
 * A graph with evidence ([Dist.given]) lowers to `float nlSample(inout uint s, inout float lw)`
 * instead: each evidence node adds its log-likelihood to `lw`, and the caller weighs the sample by
 * `exp(lw)`. A rejected sample adds `-1e30`, which is finite so sums stay ordered, and whose `exp`
 * is 0.
 *
 * A [Dist.map] node is Kotlin and cannot be lowered; [toGlsl] throws and names it.
 */
fun Dist.toGlsl(functionName: String = "nlSample"): String {
    val g = lowerGraph(listOf(this), functionName)
    val signature = if (g.hasEvidence) "(inout uint s, inout float lw)" else "(inout uint s)"
    return buildString {
        append(GLSL_RNG)
        for (h in g.helpers) append(h)
        append(g.preludes)
        append("float ").append(functionName).append(signature).append(" {\n")
        append(g.body)
        append("    return ").append(g.roots[0]).append(";\n}\n")
    }
}

/**
 * A graph lowered to GLSL statements: one `float nK = …;` per node, in evaluation order, with
 * evidence nodes adding to `lw` once each. [roots] are the local names of the requested roots.
 * Prelude ids are prefixed with [prefix], so several lowered graphs can share one shader.
 */
internal class LoweredGraph(val helpers: Set<String>, val preludes: String, val body: String, val roots: List<String>, val hasEvidence: Boolean)

internal fun lowerGraph(rootDists: List<Dist>, prefix: String): LoweredGraph {
    val plan = Plan(rootDists)
    val names = HashMap<Int, String>()
    val body = StringBuilder()
    val helpers = LinkedHashSet<String>()
    val preludes = StringBuilder()
    val counted = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Dist, Boolean>())
    plan.order.forEachIndexed { k, d ->
        val g = d.glsl ?: throw UnsupportedOperationException(
            "node '${d.op}' is Kotlin (`map`) and cannot be lowered to GLSL; rewrite it with Dist operators"
        )
        val name = "n$k"
        names[k] = name
        helpers += d.glslHelpers
        d.glslPrelude?.let { preludes.append(it("${prefix}_$name")) }
        val ins = d.inputs.map { names[plan.indexOf(it)]!! }
        val expr = g(ins, "s").replace(GLSL_ID, "${prefix}_$name")
        body.append("    float ").append(name).append(" = ").append(expr).append(";\n")
        // each piece of evidence counts once, however many variables it conditions
        if (d.kind == Dist.Kind.Given && counted.add(d.inputs[1])) body.append("    lw += ").append(ins[1]).append(";\n")
    }
    return LoweredGraph(helpers, preludes.toString(), body.toString(), rootDists.map { names[plan.indexOf(it)]!! }, plan.hasEvidence)
}

/** The hash and uniform every lowered graph uses. 24 random bits per draw, in `[0, 1)`. */
const val GLSL_RNG = """
uint nlHash(uint x) {
    x ^= x >> 16; x *= 0x7feb352dU; x ^= x >> 15; x *= 0x846ca68bU; x ^= x >> 16;
    return x;
}
float $GLSL_RND(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    w = (w >> 22u) ^ w;
    return float(w >> 8u) * (1.0 / 16777216.0);
}
"""

/**
 * A whole compute shader that draws `perThread` samples per invocation and writes, per
 * invocation, `vec4(sum(v - f), sum((v - f)^2), count, f)` where `f` is that invocation's first
 * sample. The shift by `f` keeps fp32 accumulation from cancelling; the host undoes it in double
 * ([Moments.addBlock]).
 */
fun Dist.monteCarloComputeShader(): String = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer NlOut { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push {
    NlOut result;
    uint  seed;
    uint  perThread;
    uint  threads;
} pc;
${toGlsl()}
void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.threads) return;
    uint s = nlHash(id * 1664525u ^ nlHash(pc.seed + 0x9e3779b9u));
    float f = nlSample(s);
    float sum = 0.0;
    float sq = 0.0;
    for (uint i = 1u; i < pc.perThread; i++) {
        float d = nlSample(s) - f;
        sum += d;
        sq += d * d;
    }
    pc.result.v[id] = vec4(sum, sq, float(pc.perThread), f);
}
"""

/** A compute shader that writes raw samples, four per invocation, for histograms of GPU draws. */
fun Dist.rawSamplesComputeShader(): String = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer NlOut { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push {
    NlOut result;
    uint  seed;
    uint  perThread;
    uint  threads;
} pc;
${toGlsl()}
void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.threads) return;
    uint s = nlHash(id * 1664525u ^ nlHash(pc.seed + 0x9e3779b9u));
    float a = nlSample(s);
    float b = nlSample(s);
    float c = nlSample(s);
    float d = nlSample(s);
    pc.result.v[id] = vec4(a, b, c, d);
}
"""

/**
 * The weighted form of [monteCarloComputeShader], for a graph with evidence. Each invocation keeps
 * its weights relative to the largest log-weight it has seen, `m`, and its values relative to its
 * first sample, `f`, and writes two `vec4`s: `(m, Σw, Σw(v-f), Σw(v-f)²)` and
 * `(f, Σw², Σw²(v-f), Σw²(v-f)²)`. The host merges them in double ([WeightedMoments.addBlock]).
 */
fun Dist.weightedMonteCarloComputeShader(): String = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer NlOut { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push {
    NlOut result;
    uint  seed;
    uint  perThread;
    uint  threads;
} pc;
${toGlsl()}
void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.threads) return;
    uint s = nlHash(id * 1664525u ^ nlHash(pc.seed + 0x9e3779b9u));
    float lw = 0.0;
    float f = nlSample(s, lw);
    float m = lw;
    float sw = 1.0, swd = 0.0, swdd = 0.0, sw2 = 1.0, sw2d = 0.0, sw2dd = 0.0;
    for (uint i = 1u; i < pc.perThread; i++) {
        lw = 0.0;
        float d = nlSample(s, lw) - f;
        if (lw > m) {
            float k = exp(m - lw);
            sw *= k; swd *= k; swdd *= k;
            float k2 = k * k;
            sw2 *= k2; sw2d *= k2; sw2dd *= k2;
            m = lw;
        }
        float w = exp(lw - m);
        sw += w; swd += w * d; swdd += w * d * d;
        float w2 = w * w;
        sw2 += w2; sw2d += w2 * d; sw2dd += w2 * d * d;
    }
    pc.result.v[2u * id] = vec4(m, sw, swd, swdd);
    pc.result.v[2u * id + 1u] = vec4(f, sw2, sw2d, sw2dd);
}
"""

/** Raw samples of a graph with evidence: two per invocation, each with its log-weight, as `vec4(a, lwa, b, lwb)`. */
fun Dist.rawWeightedSamplesComputeShader(): String = """#version 450
#extension GL_EXT_buffer_reference : require
layout(std430, buffer_reference) buffer NlOut { vec4 v[]; };
layout(local_size_x = 64) in;
layout(push_constant) uniform Push {
    NlOut result;
    uint  seed;
    uint  perThread;
    uint  threads;
} pc;
${toGlsl()}
void main() {
    uint id = gl_GlobalInvocationID.x;
    if (id >= pc.threads) return;
    uint s = nlHash(id * 1664525u ^ nlHash(pc.seed + 0x9e3779b9u));
    float la = 0.0;
    float a = nlSample(s, la);
    float lb = 0.0;
    float b = nlSample(s, lb);
    pc.result.v[id] = vec4(a, la, b, lb);
}
"""

/** The GPU's stand-in for minus infinity, `-1e30` and anything summed from it, back to `-Infinity`. */
private fun gpuLogWeight(v: Float): Double = if (v <= -1e29f) Double.NEGATIVE_INFINITY else v.toDouble()

/**
 * Monte Carlo on the GPU: the same [Dist] graph, lowered by [toGlsl], sampled by
 * `threads x perThread` invocations of one compute shader.
 *
 * Built on [GpuProbe], so a shader that did not compile, or a dispatch that never wrote its
 * buffer, is an error rather than a silent zero.
 *
 * From `jvmMain`, pass a `pump` and call [run]. From a box beneath a renderer, leave `pump` null
 * and wait inside `frames { }`:
 *
 * ```kotlin
 * val gpu = GpuMonteCarlo(renderer, X + Y lt 10)
 * frames {
 *     try {
 *         gpu.arm()
 *         while (gpu.stillWaiting()) frame()
 *         println(gpu.result())
 *     } finally { gpu.close() }
 * }
 * ```
 *
 * The answer is `E(dist)`; give it a 0-1 variable for a probability. A graph with evidence
 * ([Dist.given]) is estimated as a posterior: each sample weighted by its likelihood, so
 * `GpuMonteCarlo(renderer, mu given observed(normal(mu, 1), data))` is the posterior mean of `mu`.
 */
class GpuMonteCarlo(
    val renderer: Renderer,
    val dist: Dist,
    val threads: Int = 16_384,
    val perThread: Int = 256,
    val seed: Int = 1,
    pump: (() -> Unit)? = null,
    /** Write raw samples (4 per invocation, or 2 with their log-weights for a posterior) instead of moment blocks. */
    val raw: Boolean = false,
) : AutoCloseable {
    /** True if [dist] carries evidence, so the GPU weighs its samples. */
    val weighted: Boolean = Plan(listOf(dist)).hasEvidence
    val glsl: String = when {
        raw && weighted -> dist.rawWeightedSamplesComputeShader()
        raw -> dist.rawSamplesComputeShader()
        weighted -> dist.weightedMonteCarloComputeShader()
        else -> dist.monteCarloComputeShader()
    }
    val probe = GpuProbe(renderer, "noiselang", pump)
    private val out = probe.fixture("noiselang-out", threads * if (weighted && !raw) 8 else 4)

    init {
        require(threads % 64 == 0) { "threads must be a multiple of 64 (the workgroup size)" }
        probe.stage("sample", glsl).dispatch(groups = threads / 64) {
            bind("result") { out }
            bind("seed") { seed }
            bind("perThread") { perThread }
            bind("threads") { threads }
        }
    }

    /** How many samples one dispatch draws. */
    val samplesPerDispatch: Long get() = if (raw) threads * (if (weighted) 2L else 4L) else threads.toLong() * perThread

    /** Prefill the output with the sentinel; then pump until [stillWaiting] is false. */
    fun arm() = probe.arm(out)

    /** True until the dispatch has overwritten every slot. Throws past the deadline. */
    fun stillWaiting(): Boolean = probe.stillWaiting()

    /** Arm, pump, and wait (needs a `pump`). */
    fun run(): Estimate { probe.settle(out); return result() }

    /** The estimate from the last completed dispatch. */
    fun result(): Estimate {
        val v = probe.read(out)
        if (raw && weighted) {
            val wm = WeightedMoments()
            for (i in 0 until v.size / 2) wm.add(v[2 * i].toDouble(), gpuLogWeight(v[2 * i + 1]))
            return wm.mean()
        }
        if (raw) return Moments().apply { for (x in v) add(x.toDouble()) }.mean()
        if (weighted) {
            val wm = WeightedMoments()
            for (i in 0 until threads) {
                val o = i * 8
                wm.addBlock(
                    perThread.toLong(), gpuLogWeight(v[o]), v[o + 4].toDouble(),
                    v[o + 1].toDouble(), v[o + 2].toDouble(), v[o + 3].toDouble(),
                    v[o + 5].toDouble(), v[o + 6].toDouble(), v[o + 7].toDouble(),
                )
            }
            return wm.mean()
        }
        val m = Moments()
        for (i in 0 until threads) {
            val sd = v[i * 4].toDouble(); val sqd = v[i * 4 + 1].toDouble()
            val c = v[i * 4 + 2].toLong(); val f = v[i * 4 + 3].toDouble()
            // undo the shift: the block holds c samples, the first of which is f itself
            val sum = sd + c * f
            val sumSq = sqd + 2 * f * sd + c * f * f
            m.addBlock(c, sum, sumSq)
        }
        return m.mean()
    }

    /** Raw GPU samples (only when [raw]). For a posterior these are the values; [logWeights] are their weights. */
    fun samples(): FloatArray {
        check(raw) { "construct with raw = true to read samples" }
        val v = probe.read(out)
        return if (weighted) FloatArray(v.size / 2) { v[2 * it] } else v
    }

    /** The log-weight of each of [samples] (only when [raw] and [weighted]); `-Infinity` for a rejected sample. */
    fun logWeights(): DoubleArray {
        check(raw && weighted) { "log-weights exist only for raw samples of a graph with evidence" }
        val v = probe.read(out)
        return DoubleArray(v.size / 2) { gpuLogWeight(v[2 * it + 1]) }
    }

    /** Failures recorded by the probe (shader compile errors and the like), as text. */
    fun verdictLines(): List<String> = probe.verdictLines("noiselang")

    override fun close() = probe.close()
}
