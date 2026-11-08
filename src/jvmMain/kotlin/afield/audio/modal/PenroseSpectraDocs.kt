/**
 * Offline audio figures for `public/docs/penrose_spectra.md`: a Penrose patch and a square-lattice
 * control, each a mass-spring network ([GraphModes]), struck and heard at one vertex, through the
 * `renderWav(name, path)` contract the docs runner's `audio="…"` fences call.
 */
package afield.audio.modal

import afield.audio.engine.WavFloatArrayWriter
import afield.geometry.path.TilingGraph
import afield.geometry.path.deBruijnTiling
import afield.geometry.path.vertexGraph

/** The Penrose-spectra page's audio scenes. */
object PenroseSpectraDocs {
    private const val SR = 44100

    /** Scene names [renderWav] accepts. */
    val scenes = listOf("penrose-confined", "penrose-open", "square")

    /** Every scene uses the same stiffness, so the same tight-binding energy is the same pitch. */
    const val STIFFNESS = 8.0

    /** The page's Penrose patch: 5-fold, radius 6 line spacings. */
    fun penrose(): TilingGraph = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0).vertexGraph()

    /** The periodic control: a square-lattice disk with about as many vertices. */
    fun square(): TilingGraph = TilingGraph.squareLattice(radius = 19.0)

    /**
     * The vertex a scene strikes: in the inner half of the patch, the one with the most E = 0
     * weight (`confined`) or the one with none and the most edges (`open`); the square's centre.
     */
    fun strikeVertex(graph: TilingGraph, modes: GraphModes, confined: Boolean): Int {
        val inner = graph.interior(0.5 * graph.extent)
        val w = modes.spectrum.zeroWeights()
        return if (confined) inner.maxBy { w[it] } else inner.filter { w[it] < 1e-9 }.maxBy { graph.degree(it) * 1000 - it }
    }

    /** Render scene [name] (one of [scenes]) to a mono 16-bit WAV at [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val audio = render(name)
        require(audio.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, audio, SR, normalizePeak = 0.9f)
    }

    /** The samples of scene [name]. */
    fun render(name: String): FloatArray = when (name) {
        "penrose-confined", "penrose-open" -> {
            val g = penrose()
            val modes = g.springModes(STIFFNESS)
            val v = strikeVertex(g, modes, name == "penrose-confined")
            modes.render(modes.strikeVertex(v), zeroHz = 220.0, seconds = 3.0, sampleRate = SR, alpha = 1.0, beta = 0.2)
        }
        "square" -> {
            val g = square()
            val modes = g.springModes(STIFFNESS)
            val v = g.nearest(g.centre.first, g.centre.second)
            modes.render(modes.strikeVertex(v), zeroHz = 220.0, seconds = 3.0, sampleRate = SR, alpha = 1.0, beta = 0.2)
        }
        else -> throw IllegalArgumentException("unknown penrose-spectra scene '$name'; known: $scenes")
    }
}
