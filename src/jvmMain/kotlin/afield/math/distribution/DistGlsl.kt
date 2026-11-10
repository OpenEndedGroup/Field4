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
 * A [Dist.map] node is Kotlin and cannot be lowered; [toGlsl] throws and names it.
 */
fun Dist.toGlsl(functionName: String = "nlSample"): String {
    val plan = Plan(listOf(this))
    val names = HashMap<Int, String>()
    val body = StringBuilder()
    plan.order.forEachIndexed { k, d ->
        val g = d.glsl ?: throw UnsupportedOperationException(
            "node '${d.op}' is Kotlin (`map`) and cannot be lowered to GLSL; rewrite it with Dist operators"
        )
        val name = "n$k"
        names[k] = name
        val ins = d.inputs.map { names[plan.indexOf(it)]!! }
        body.append("    float ").append(name).append(" = ").append(g(ins, "s")).append(";\n")
    }
    return buildString {
        append(GLSL_RNG)
        append("float ").append(functionName).append("(inout uint s) {\n")
        append(body)
        append("    return n").append(plan.order.size - 1).append(";\n}\n")
    }
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
 * The answer is `E(dist)`; give it a 0-1 variable for a probability.
 */
class GpuMonteCarlo(
    val renderer: Renderer,
    val dist: Dist,
    val threads: Int = 16_384,
    val perThread: Int = 256,
    val seed: Int = 1,
    pump: (() -> Unit)? = null,
    /** Write raw samples (4 per invocation) instead of moment blocks. */
    val raw: Boolean = false,
) : AutoCloseable {
    val glsl: String = if (raw) dist.rawSamplesComputeShader() else dist.monteCarloComputeShader()
    val probe = GpuProbe(renderer, "noiselang", pump)
    private val out = probe.fixture("noiselang-out", threads * 4)

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
    val samplesPerDispatch: Long get() = if (raw) threads * 4L else threads.toLong() * perThread

    /** Prefill the output with the sentinel; then pump until [stillWaiting] is false. */
    fun arm() = probe.arm(out)

    /** True until the dispatch has overwritten every slot. Throws past the deadline. */
    fun stillWaiting(): Boolean = probe.stillWaiting()

    /** Arm, pump, and wait (needs a `pump`). */
    fun run(): Estimate { probe.settle(out); return result() }

    /** The estimate from the last completed dispatch. */
    fun result(): Estimate {
        val v = probe.read(out)
        if (raw) return Moments().apply { for (x in v) add(x.toDouble()) }.mean()
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

    /** Raw GPU samples (only when [raw]). */
    fun samples(): FloatArray {
        check(raw) { "construct with raw = true to read samples" }
        return probe.read(out)
    }

    /** Failures recorded by the probe (shader compile errors and the like), as text. */
    fun verdictLines(): List<String> = probe.verdictLines("noiselang")

    override fun close() = probe.close()
}
