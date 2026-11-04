package afield.examples.sonicpi

import afield.audio.engine.WavFloatArrayLoader
import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.SonicPi
import java.io.File
import java.nio.file.Files

/**
 * The sound on the [Sonic Pi](../sonic_pi.md) page, recorded from a real Sonic Pi.
 *
 * Unlike the other `*SoundDocs` renderers this is not an offline render: it boots (or reuses) the
 * shared [SonicPi], runs the page's program, and records it through Sonic Pi's own recorder — the
 * same `startRecording` / `run` / `saveRecording` path [SonicPiPatternProbe] measures. The clip is
 * therefore what Sonic Pi actually played, `choose` and `rrand` included, and differs run to run.
 *
 * Scenes: `bikes`, the page's first `live_loop`, recorded for two of its eight-second passes.
 */
object SonicPiSoundDocs {

    /** The first box on the page, as the box sends it. */
    val BIKES = """
        #sonicpi
        live_loop :bikes do
          with_synth :dsaw do
            with_fx :reverb, room: 0.5, mix: 0.3 do
              start_note = chord([:b1, :e2, :b3].choose, :minor).choose
              play start_note, release: 8, cutoff: 90, detune: rrand(0, 0.2)
            end
          end
          sleep 8
        end
    """.trimIndent()

    /** Post-fader, so not zero; the WAV is peak-normalised afterwards. */
    private const val RECORD_VOLUME = 0.3

    fun renderWav(name: String, outPath: String) {
        val (source, seconds) = when (name) {
            "bikes" -> BIKES to 16.0
            else -> throw IllegalArgumentException("No Sonic Pi scene '$name'; there is 'bikes'")
        }
        val sonicPi = SonicPi.shared()
        check(sonicPi.awaitReady(120_000L)) { "Sonic Pi never announced /spider/ready" }
        val raw = File(Files.createTempDirectory("sonicpi-docs-").toFile(), "$name.wav")
        try {
            sonicPi.volume(RECORD_VOLUME, silent = true)
            Thread.sleep(300)
            sonicPi.startRecording()
            Thread.sleep(300)
            sonicPi.run(source, workspace = "docs_$name", silent = true)
            Thread.sleep((seconds * 1000).toLong())
            sonicPi.stopAll()
            Thread.sleep(1_200)
            sonicPi.stopRecording()
            Thread.sleep(400)
            sonicPi.saveRecording(raw.absolutePath)
            check(awaitStableFile(raw, 30_000)) { "Sonic Pi never wrote a recording to $raw" }
        } finally {
            runCatching { sonicPi.stopAll() }
        }
        val audio = WavFloatArrayLoader.loadWavToFloatArray(raw.absolutePath)
        require(audio.samples.any { it != 0f }) { "Sonic Pi recorded silence for '$name'" }
        WavFloatArrayWriter.writeMono16(outPath, audio.samples, audio.sampleRate, normalizePeak = 0.9f)
        raw.delete()
    }

    private fun awaitStableFile(file: File, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var lastSize = -1L
        var stableFor = 0
        while (System.nanoTime() < deadline) {
            val size = if (file.isFile) file.length() else -1L
            if (size > 0 && size == lastSize) {
                if (++stableFor >= 3) return true
            } else stableFor = 0
            lastSize = size
            Thread.sleep(300)
        }
        return file.isFile && file.length() > 0
    }
}
