package afield.data.query.datalog

import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.DirtRender
import afield.audio.synth.PatPattern
import afield.audio.synth.PatSoundDocs
import afield.audio.synth.patRenderStereoDirt
import kotlin.math.pow

/**
 * The same kind of rules through the pinned Dirt-Samples, strictly (no synth fallback). Dirt names
 * its folders `bd`, `sn`, `hh`, `arpy`, so the instruments are those; Dirt pitches a sample by
 * `speed`, so each `arpy` note becomes `speed = 2^((note - 60) / 12)`.
 */
object DatalogRhythmDirtDocs {
    const val SONG = """
        event(bd, 36, 100, T)    :- euc(T, 4, 16, 0).
        event(sn, 38, 90, T)     :- within(T, 16, 64), every(T, 8, 4).
        event(hh, 42, 45, T)     :- within(T, 32, 64), every(T, 2, 1).
        event(arpy, N, 80, T)    :- euc(T, 5, 16, 2), D = (T div 3) mod 5,
                                    scale(48, minor_pent, D, 1, N).
    """

    fun pattern(): PatPattern<String> {
        val base = DatalogRhythmSoundDocs.pattern(SONG)
        return PatPattern { arc ->
            base.events(arc).map { e ->
                val note = e.annotations["note"]?.toDoubleOrNull() ?: return@map e
                e.copy(annotations = e.annotations + ("speed" to 2.0.pow((note - 60.0) / 12.0).toString()))
            }
        }
    }

    fun renderWav(name: String, outPath: String) {
        require(name == "song-dirt") { "unknown DatalogRhythmDirtDocs scene '$name'; known: [song-dirt]" }
        val result = patRenderStereoDirt(
            pattern(), cycles = DatalogRhythmSoundDocs.CYCLES, cps = DatalogRhythmSoundDocs.CPS,
            library = DirtRender.requirePinnedLibrary(), strict = true,
        )
        check(result.usedSamples && result.unmatched.isEmpty()) { "song-dirt: ${result.describe()}" }
        val audio = result.audio
        require(audio.left.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }
}
