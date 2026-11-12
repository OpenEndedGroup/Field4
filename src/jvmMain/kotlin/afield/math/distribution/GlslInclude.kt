package afield.math.distribution

import afield.document.graph.Box
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------
// Distributions as shader includes: `#include "dist/<name>"`
// ---------------------------------------------------------------------------------------------

/** Seeds for the shader stages: one hash of the invocation or pixel and the frame, so neighbours are uncorrelated. */
internal const val GLSL_SEEDS = """
uint nlSeed(uint a, uint b) { return nlHash(a * 1664525u ^ nlHash(b + 0x9e3779b9u)); }
uint nlSeed(uvec2 p, uint frame) { return nlHash(p.x ^ nlHash(p.y * 2654435761u ^ nlHash(frame + 0x9e3779b9u))); }
uint nlSeed(vec2 fragCoord, uint frame) { return nlSeed(uvec2(fragCoord), frame); }
uint nlSeed(uvec3 id, uint frame) { return nlHash(id.x ^ nlHash(id.y * 2654435761u ^ nlHash(id.z * 40503u ^ nlHash(frame + 0x9e3779b9u)))); }
"""

/** The runtime every distribution include shares, guarded so a shader may include several. */
internal val GLSL_RUNTIME: String = "#ifndef NL_RUNTIME\n#define NL_RUNTIME\n$GLSL_RNG$GLSL_SEEDS#endif\n"

/** A helper function wrapped in its own guard, so two includes that both need it define it once. */
internal fun guardedHelper(h: String): String {
    val tag = "NL_H_" + Integer.toHexString(h.hashCode()).uppercase()
    return "#ifndef $tag\n#define $tag\n$h\n#endif\n"
}

private val GLSL_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")

internal fun requireGlslName(name: String) {
    require(GLSL_NAME.matches(name) && !name.startsWith("gl_") && !name.startsWith("nl")) {
        "'$name' cannot name a GLSL function: use letters, digits and _, starting with a letter (not gl_ or nl)"
    }
}

/**
 * What `#include "dist/<name>"` puts into a shader: its GLSL, and the functions that GLSL defines.
 *
 * For one distribution, `float name(inout uint s)` returns one draw and advances the state `s`.
 * For a list of `N`, `void name(inout uint s, out float v[N])` fills `v` with one joint draw, and
 * `name_SIZE` is `N`. Seed the state with `nlSeed(gl_FragCoord.xy, frame)` in a fragment shader or
 * `nlSeed(gl_GlobalInvocationID, frame)` in a compute shader.
 *
 * A posterior (one with [Dist.given]) also defines `name_weighted(…, inout float lw)`, one weighted
 * draw adding its log-weight to `lw`; and `name(…)` is then an approximate posterior draw, one of
 * [posteriorPool] weighted draws chosen by weight (weighted reservoir sampling, in log space).
 * `bool name_try(inout uint s, out …)` is the same draw, returning false when none of the pool was
 * consistent with the evidence (the value is then 0): skip those rather than plot them.
 */
class DistInclude internal constructor(
    /** The name the shader includes it by, and the name of its function. */
    val name: String,
    /** The GLSL `#include "dist/<name>"` stands for. */
    val source: String,
    /** How many values one draw makes: 1 for a distribution, `N` for a list. */
    val size: Int,
    /** True if the distribution carries evidence, so `name_weighted` exists. */
    val weighted: Boolean,
    /** Weighted draws per posterior draw. */
    val posteriorPool: Int,
) {
    /** The function signature, for messages and the docs. */
    val signature: String get() = if (size == 1) "float $name(inout uint s)" else "void $name(inout uint s, out float v[$size])"

    override fun toString(): String = "DistInclude($signature)"
}

/** This distribution as the GLSL of `#include "dist/<name>"`. See [DistInclude]. */
fun Dist.toGlslInclude(name: String, posteriorPool: Int = 32): DistInclude = buildInclude(name, listOf(this), vector = false, posteriorPool)

/** These distributions, drawn jointly, as the GLSL of `#include "dist/<name>"`. See [DistInclude]. */
fun List<Dist>.toGlslInclude(name: String, posteriorPool: Int = 32): DistInclude = buildInclude(name, this, vector = true, posteriorPool)

private fun buildInclude(name: String, roots: List<Dist>, vector: Boolean, posteriorPool: Int): DistInclude {
    requireGlslName(name)
    require(roots.isNotEmpty()) { "an include of no distributions" }
    require(posteriorPool in 1..4096) { "posteriorPool must be in 1..4096" }
    val g = lowerGraph(roots, name)
    val n = roots.size
    val src = buildString {
        append(GLSL_RUNTIME)
        for (h in g.helpers) append(guardedHelper(h))
        append("#ifndef NL_DIST_$name\n#define NL_DIST_$name\n")
        append(g.preludes)
        if (vector) append("const int ${name}_SIZE = $n;\n")
        val outs = if (vector) g.roots.indices.joinToString("") { "    v[$it] = ${g.roots[it]};\n" } else "    return ${g.roots[0]};\n"
        val ret = if (vector) "void" else "float"
        val vParam = if (vector) ", out float v[$n]" else ""
        if (!g.hasEvidence) {
            append("$ret $name(inout uint s$vParam) {\n").append(g.body).append(outs).append("}\n")
        } else {
            append("$ret ${name}_weighted(inout uint s, inout float lw$vParam) {\n").append(g.body).append(outs).append("}\n")
            // weighted reservoir sampling over posteriorPool draws, with weights kept relative to the
            // largest log-weight seen so far so a long run of tiny likelihoods does not underflow.
            // name_try reports whether any draw was accepted; name ignores that.
            val outParam = if (vector) "out float v[$n]" else "out float x"
            append("bool ${name}_try(inout uint s, $outParam) {\n")
            append("    float m = -1e30; float total = 0.0;\n")
            if (vector) append("    float c[$n];\n    for (int i = 0; i < $n; i++) v[i] = 0.0;\n") else append("    x = 0.0;\n")
            append("    for (int k = 0; k < $posteriorPool; k++) {\n")
            append("        float lw = 0.0;\n")
            if (vector) append("        ${name}_weighted(s, lw, c);\n") else append("        float y = ${name}_weighted(s, lw);\n")
            append("        if (lw <= -1e29) continue;\n")
            append("        if (lw > m) { total *= exp(m - lw); m = lw; }\n")
            append("        float w = exp(lw - m);\n")
            append("        total += w;\n")
            if (vector) append("        if (nlRnd(s) * total < w) { for (int i = 0; i < $n; i++) v[i] = c[i]; }\n")
            else append("        if (nlRnd(s) * total < w) x = y;\n")
            append("    }\n")
            append("    return total > 0.0;\n}\n")
            if (vector) append("void $name(inout uint s, out float v[$n]) { ${name}_try(s, v); }\n")
            else append("float $name(inout uint s) { float x; ${name}_try(s, x); return x; }\n")
        }
        append("#endif\n")
    }
    return DistInclude(name, src, n, g.hasEvidence, posteriorPool)
}

/**
 * Where `#include "dist/<name>"` finds its distribution: the including box first, then its
 * ancestors nearest first, then the process-wide [distInclude] registrations.
 */
internal object DistIncludes {
    private val byBox = WeakHashMap<Box, MutableMap<String, DistInclude>>()
    private val global = ConcurrentHashMap<String, DistInclude>()

    @Synchronized fun put(box: Box, inc: DistInclude) { byBox.getOrPut(box) { HashMap() }[inc.name] = inc }
    fun putGlobal(inc: DistInclude) { global[inc.name] = inc }

    @Synchronized fun find(box: Box?, name: String): DistInclude? {
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

    @Synchronized fun namesNear(box: Box?): List<String> = (byBox[box]?.keys.orEmpty() + global.keys).sorted()
}

/**
 * The text of `#include "dist/<name>"` for a shader of [inside]. Called by the shader include
 * resolver; throws, naming what is defined, when nothing called [name] is.
 */
fun distIncludeSource(inside: Box?, name: String): String {
    val key = name.removeSuffix(".glsl")
    val inc = DistIncludes.find(inside, key) ?: throw IllegalStateException(
        "#include \"dist/$key\": no distribution is called '$key' in this box or above it. " +
            "Call distHere(\"$key\", d) in this box or a parent before the shader compiles" +
            DistIncludes.namesNear(inside).let { if (it.isEmpty()) "." else "; defined here: ${it.joinToString()}." }
    )
    return inc.source
}

/**
 * Make [d] available to this box's shaders, and its descendants', as `#include "dist/<name>"`,
 * which defines `float name(inout uint s)`: one draw per call.
 *
 * ```glsl
 * #include "dist/grain"
 * void main() {
 *     uint s = nlSeed(gl_FragCoord.xy, pc.frame);
 *     float g = grain(s);
 *     …
 * }
 * ```
 *
 * The shader draws from the same graph `ᛟ.distribution(d)` draws on the CPU, so the box's
 * histogram describes what the GPU is drawing. Call it again with a new distribution and the next
 * shader compile picks it up. A posterior draws approximately; see [DistInclude].
 */
fun Box.distHere(name: String, d: Dist, posteriorPool: Int = 32): DistInclude =
    d.toGlslInclude(name, posteriorPool).also { DistIncludes.put(this, it) }

/**
 * Make these distributions, drawn jointly, available as `#include "dist/<name>"`, which defines
 * `void name(inout uint s, out float v[N])`. Use it for a point (`listOf(x, y)`), a colour, or a
 * curve from a Gaussian process.
 */
fun Box.distHere(name: String, ds: List<Dist>, posteriorPool: Int = 32): DistInclude =
    ds.toGlslInclude(name, posteriorPool).also { DistIncludes.put(this, it) }

/** Make [d] available to every shader in the process as `#include "dist/<name>"`, for code with no box. A box's own [distHere] wins. */
fun distInclude(name: String, d: Dist, posteriorPool: Int = 32): DistInclude =
    d.toGlslInclude(name, posteriorPool).also { DistIncludes.putGlobal(it) }

/** Make these distributions available to every shader in the process as `#include "dist/<name>"`. */
fun distInclude(name: String, ds: List<Dist>, posteriorPool: Int = 32): DistInclude =
    ds.toGlslInclude(name, posteriorPool).also { DistIncludes.putGlobal(it) }

internal fun registerGlobalInclude(inc: DistInclude) = DistIncludes.putGlobal(inc)
internal fun registerBoxInclude(box: Box, inc: DistInclude) = DistIncludes.put(box, inc)
