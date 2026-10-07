package afield.stdlib.loris

import afield.four.audio.WavFloatArrayLoader
import afield.four.audio.WavFloatArrayWriter
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.TimeViewerShell
import java.nio.file.Files
import java.nio.file.Path

/** Reproducible real-audio documentation fixture; never substitutes synthetic tracks for Loris. */
object LorisDocs {
    val sourcePath: Path get() = Path.of("public/docs/assets/subharmonic_sound/notld-barbara-source.wav")
    val folder: Path get() = Path.of("public/docs/assets/loris")
    val options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0, hopSeconds = .005,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0)

    /** Analyze committed public-domain film dialogue; render the unedited and transformed models. */
    fun render(): Pair<LorisResult, LorisResult> {
        val wav = WavFloatArrayLoader.loadWavToFloatArray(sourcePath.toString())
        val client = LorisClient()
        val result = client.analyze(wav.samples, wav.sampleRate, options)
        val edited = client.synthesize(result.model.transformed(frequencyScale = .75, bandwidthScale = 0.0, gain = .8))
        Files.createDirectories(folder)
        check(result.peak <= 1f && edited.peak <= 1f) { "Docs audio needs explicit attenuation" }
        WavFloatArrayWriter.writeMono16(folder.resolve("reconstruction.wav").toString(), result.samples, result.model.sampleRate)
        WavFloatArrayWriter.writeMono16(folder.resolve("three-quarter-tonal.wav").toString(), edited.samples, edited.model.sampleRate)
        Files.writeString(folder.resolve("provenance.json"), TimeViewerShell.scriptJson(mapOf(
            "source" to sourcePath.toString(), "sourceChannel" to "left (committed source is mono)",
            "sourceSha256" to java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sourcePath))
                .joinToString("") { "%02x".format(it) },
            "options" to options, "analysis" to result.provenance, "edit" to edited.provenance,
            "editParameters" to mapOf("frequencyScale" to .75, "bandwidthScale" to 0.0, "gain" to .8),
            "sampleRate" to wav.sampleRate, "frames" to wav.samples.size, "partials" to result.model.partials.size,
            "breakpoints" to result.model.partials.sumOf { it.points.size },
            "peak" to result.peak, "editedPeak" to edited.peak,
            "normalization" to "none", "resampling" to "none", "publishedEncoding" to "mono PCM16")))
        return result to edited
    }

    fun comparisonHtml(): String {
        val (result, _) = render()
        val wav = WavFloatArrayLoader.loadWavToFloatArray(sourcePath.toString())
        val sync = "loris-film-comparison"
        return AudioVisualizer.renderSamplesHtml(wav.samples, wav.sampleRate,
            AudioVisualizerOptions(width = 1000, waveformHeight = 95, syncGroup = sync),
            label = "Source · Night of the Living Dead (1968) · mono 48 kHz") +
            result.visualizationHtml(LorisViewOptions(height = 400,
                syncGroup = sync, label = "Film dialogue · irregular partial trajectories"))
    }

    fun transformedHtml(): String = render().second.visualizationHtml(LorisViewOptions(height = 340,
        axis = LorisFrequencyAxis.ERB,
        syncGroup = "loris-tonal-edit", label = "Frequency × 0.75 · noise bandwidth × 0 · gain × 0.8"))

    fun whirlpoolHtml(): String {
        val analyzed = render().first
        val edited = LorisClient().synthesize(analyzed.model.spectralWhirlpool(6.0, 3.0))
        check(edited.peak <= 1f)
        WavFloatArrayWriter.writeMono16(folder.resolve("spectral-whirlpool.wav").toString(), edited.samples, edited.model.sampleRate)
        Files.writeString(folder.resolve("whirlpool-provenance.json"), TimeViewerShell.scriptJson(mapOf(
            "parentAnalysis" to analyzed.provenance, "synthesis" to edited.provenance,
            "startSeconds" to 6.0, "durationSeconds" to 3.0, "gesture" to "afield.stdlib.loris.spectralWhirlpool",
            "octaveDepth" to 1.5, "sweepCycles" to 4, "shutterHz" to 12, "targetBandwidth" to .85,
            "nyquistLimit" to ".49 * sampleRate", "peak" to edited.peak, "normalization" to "none")))
        return edited.visualizationHtml(LorisViewOptions(height = 400, syncGroup = "loris-whirlpool",
            label = "6–9 seconds · ±18-semitone whirlpool / 12 Hz shutter / noisy core"))
    }
}
