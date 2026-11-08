/**
 * Modes of a mass-spring network built on a graph, such as a Penrose tiling's vertices and edges
 * ([TilingGraph]), for the same modal synthesis a [DrumModes] drum uses. [ModalDrum] plays either:
 * both are a [ModeSet].
 */
package afield.audio.modal

import afield.geometry.path.GraphSpectrum
import afield.geometry.path.TilingGraph
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What [ModalDrum] needs from a set of modes: their eigenvalues (frequency ∝ √λ) and how hard a
 * strike at a point drives each one.
 */
interface ModeSet {
    /** Eigenvalues, ascending; mode i sounds at `fundamental * sqrt(lambda[i] / lambda[0])`. */
    val lambda: DoubleArray

    /** Each mode's frequency as a ratio to the lowest. */
    fun ratios(): DoubleArray

    /** The amplitude each mode rings with after a unit strike at (px, py) by a mallet of [radius]. */
    fun strikeShape(px: Double, py: Double, radius: Double): DoubleArray
}

/**
 * The modes of a network of unit masses on a graph's vertices, joined by unit springs along its
 * edges, with a spring to ground at every vertex chosen so that each vertex's springs add up to
 * the same [stiffness]. The stiffness matrix is then `stiffness·I − A`, A the adjacency matrix, so
 * the vibration modes are exactly the graph's tight-binding states and mode i rings at
 * ω² = [stiffness] − Eᵢ. Every E = 0 state shares one pitch, ω = √stiffness, which is how a Penrose
 * tiling's confined states become audible. [stiffness] must exceed the largest degree for every
 * ground spring to be positive.
 *
 * Make one with [TilingGraph.springModes]. A strike is heard where it lands (the mallet is also
 * the pickup), so mode i's amplitude is ψᵢ(v)², which does not depend on how degenerate states
 * are mixed, and the share of the sound at the E = 0 pitch is [GraphSpectrum.zeroWeights] at v.
 */
class GraphModes(
    /** The tight-binding spectrum the modes come from (with vectors). */
    val spectrum: GraphSpectrum,
    /** Every vertex's total spring stiffness to its neighbours and ground. */
    val stiffness: Double,
) : ModeSet {
    private val vecs = spectrum.vectors ?: throw IllegalArgumentException("GraphModes needs a spectrum solved with vectors")
    private val graph: TilingGraph get() = spectrum.graph

    // Highest E is the lowest pitch: order modes by ascending lambda = stiffness - E.
    private val order = spectrum.energies.indices.sortedByDescending { spectrum.energies[it] }

    init {
        val maxDegree = (0 until graph.vertexCount).maxOf { graph.degree(it) }
        require(stiffness > maxDegree) { "stiffness $stiffness must exceed the largest degree $maxDegree so every ground spring is positive" }
    }

    override val lambda: DoubleArray = DoubleArray(order.size) { stiffness - spectrum.energies[order[it]] }

    /** The tight-binding energy of each mode, in the same (ascending-λ) order as [lambda]. */
    val energies: DoubleArray = DoubleArray(order.size) { spectrum.energies[order[it]] }

    /** Number of modes (one per vertex). */
    val count: Int get() = lambda.size

    override fun ratios(): DoubleArray = DoubleArray(count) { sqrt(lambda[it] / lambda[0]) }

    /** The ratio of the E = 0 pitch to the lowest mode: √(stiffness / λ₀). */
    val zeroRatio: Double get() = sqrt(stiffness / lambda[0])

    /** Frequencies in Hz when the lowest mode sounds at [fundamental]. */
    @JvmOverloads
    fun frequencies(fundamental: Double = 110.0): DoubleArray = DoubleArray(count) { fundamental * sqrt(lambda[it] / lambda[0]) }

    /** Mode amplitudes for a strike on vertex [v], heard at [v]: ψᵢ(v)². */
    fun strikeVertex(v: Int): DoubleArray = DoubleArray(count) { val a = vecs[order[it]][v]; a * a }

    /**
     * A strike at (px, py) by a Gaussian mallet of [radius] (in drawing units), heard by the same
     * mallet: each mode's amplitude is the square of its Gaussian-weighted average under it. With a
     * radius well under an edge it is [strikeVertex] at the nearest vertex.
     */
    override fun strikeShape(px: Double, py: Double, radius: Double): DoubleArray {
        val g = DoubleArray(graph.vertexCount) { exp(-(hypot(graph.x[it] - px, graph.y[it] - py) / radius).let { d -> d * d } / 2) }
        val total = g.sum()
        if (total < 1e-12) return strikeVertex(graph.nearest(px, py))
        return DoubleArray(count) { k ->
            val psi = vecs[order[k]]
            var s = 0.0
            for (v in g.indices) if (g[v] > 1e-9) s += g[v] * psi[v]
            (s / total) * (s / total)
        }
    }

    /** The share of a strike-and-listen at vertex [v] that rings at the E = 0 pitch. */
    @JvmOverloads
    fun zeroShare(v: Int, tolerance: Double = 1e-8): Double {
        val a = strikeVertex(v)
        var zero = 0.0
        for (k in 0 until count) if (kotlin.math.abs(energies[k]) < tolerance) zero += a[k]
        return zero / a.sum()
    }

    /**
     * One strike rendered offline, the same synthesis as [DrumModes.render]: mode i at
     * `fundamental·√(λᵢ/λ₀)` Hz, decaying at [alpha] + [beta]·ratio², peak-normalised to 0.9.
     */
    @JvmOverloads
    fun render(
        amplitudes: DoubleArray,
        fundamental: Double = 110.0,
        seconds: Double = 2.5,
        sampleRate: Int = 44100,
        alpha: Double = 1.5,
        beta: Double = 0.25,
    ): FloatArray = renderModalStrike(frequencies(fundamental), amplitudes, fundamental, seconds, sampleRate, alpha, beta, -1)
}

/**
 * The mass-spring modes of this graph ([GraphModes]): unit masses on vertices, unit springs on
 * edges, ground springs topping every vertex up to [stiffness] (default: one more than the
 * largest degree). Solves the full spectrum, so keep the graph to a couple of thousand vertices.
 */
@JvmOverloads
fun TilingGraph.springModes(stiffness: Double = (0 until vertexCount).maxOf { degree(it) } + 1.0): GraphModes =
    GraphModes(spectrum(true), stiffness)

/** The mass-spring modes of a spectrum already solved with vectors; see [TilingGraph.springModes]. */
@JvmOverloads
fun GraphSpectrum.springModes(stiffness: Double = (0 until graph.vertexCount).maxOf { graph.degree(it) } + 1.0): GraphModes =
    GraphModes(this, stiffness)
