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
    val scenes = listOf("square-centre", "square-off", "kac-a", "kac-b", "polar")

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
        else -> throw IllegalArgumentException("unknown eigendrum scene '$name'; known: $scenes")
    }
}
