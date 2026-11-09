/**
 * Offline audio figures for `public/docs/eigendrum.md`: each scene meshes a drum, solves its
 * modes and renders one strike to a WAV, through the `renderWav(name, path)` contract the docs
 * runner's `audio="…"` fences call.
 */
package afield.audio.modal

import afield.audio.engine.WavFloatArrayWriter

/** The eigendrum page's audio scenes. */
object EigendrumDocs {
    private val square = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
    private const val SR = 44100

    /** Scene names [renderWav] accepts. */
    val scenes = listOf("square-centre", "square-off", "kac-a", "kac-b", "polar", "disk-bare", "disk-raman", "disk-syahi", "triad")

    /** The heart-ish outline the page's "Your own drum" box falls back to, as x, y coordinates. */
    val heart: DoubleArray
        get() = polarPolygon(200) { t -> 1 + 0.35 * kotlin.math.sin(t) * kotlin.math.abs(kotlin.math.cos(t)) + 0.15 * kotlin.math.cos(3 * t) }

    // a tabla is struck between the edge of the syahi and the rim
    private fun strikeDisk(modes: DrumModes) = modes.render(modes.strike(0.62, 0.15, 0.04), 220.0, 2.5)

    /** Render scene [name] (one of [scenes]) to a mono 16-bit WAV at [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val audio = render(name)
        require(audio.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeMono16(outPath, audio, SR, normalizePeak = 0.9f)
    }

    /** The samples of scene [name]. */
    fun render(name: String): FloatArray = when (name) {
        "square-centre" -> drumMesh(square, 0.05).modes(16).let { it.render(it.strike(0.5, 0.5), 120.0) }
        "square-off" -> drumMesh(square, 0.05).modes(16).let { it.render(it.strike(0.31, 0.22), 120.0) }
        "kac-a" -> drumMesh(KacPair.drumA, 0.06).modes(16).let { it.render(it.strike(1.5, 1.5, 0.05), 140.0, 2.0) }
        "kac-b" -> drumMesh(KacPair.drumB, 0.06).modes(16).let { it.render(it.strike(1.5, 1.5, 0.05), 140.0, 2.0) }
        "polar" -> polarDrumMesh(0.06) { t -> 1 + 0.3 * kotlin.math.cos(5 * t) }.modes(16).let { it.render(it.strike(0.35, 0.1), 100.0) }
        "disk-bare" -> strikeDisk(polarDrumMesh(0.06) { 1.0 }.modes(15))
        "disk-raman" -> strikeDisk(loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.06).modes(15))
        "disk-syahi" -> strikeDisk(designSyahi(2, h = 0.08).modes)
        "triad" -> designShape(doubleArrayOf(4.0, 5.0, 6.0), start = PolarShape.fromPolygon(heart, 3), h = 0.08).shape
            .let { polarDrumMesh(0.06) { 1.0 }.polarMorphed(it).modes(12) }
            .let { it.render(it.strike(0.3, 0.25, 0.04), 220.0, 2.5) }
        else -> throw IllegalArgumentException("unknown eigendrum scene '$name'; known: $scenes")
    }
}
