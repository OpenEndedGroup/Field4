package afield.data.query.datalog

import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.PatPattern
import afield.audio.synth.PatSoundDocs
import afield.audio.synth.patRenderStereo

/**
 * The audio for datalog.md's "Rules that decide when notes play": the page's own rules, played
 * through Pat's stand-in synth voice ([patRenderStereo], `patVoice`). The `renderWav(name, path)`
 * contract is the one the docs runner's `audio="…"` fence drives.
 */
object DatalogRhythmSoundDocs {
    /** The page's rules, word for word. */
    const val SONG = """
        event(kick, 36, 100, T)  :- euc(T, 4, 16, 0).
        event(snare, 38, 90, T)  :- within(T, 16, 64), every(T, 8, 4).
        event(hh, 42, 45, T)     :- within(T, 32, 64), every(T, 2, 1).
        event(lead, N, 80, T)    :- euc(T, 5, 16, 2), D = (T div 3) mod 5,
                                    scale(48, minor_pent, D, 1, N).
    """

    const val CYCLES = 4
    const val CPS = 0.5

    fun pattern(source: String = SONG): PatPattern<String> = Datalog().apply { add(source) }.rhythm().pattern()

    fun renderWav(name: String, outPath: String) {
        require(name == "song") { "unknown DatalogRhythmSoundDocs scene '$name'; known: [song]" }
        val audio = patRenderStereo(pattern(), cycles = CYCLES, cps = CPS)
        require(audio.left.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }
}
