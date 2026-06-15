package afield.examples.audio

import afield.four.audio.TrackingResonatorAnalysis
import afield.four.audio.TrackingResonatorBank
import afield.four.audio.WavFloatData
import afield.four.audio.WavFloatArrayLoader
import afield.four.audio.WavFloatArrayWriter
import afield.four.audio.trackingResonate
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val DEMO_SECONDS = 40.0f

/**
 * Example:
 *
 * ./gradlew runTrackingResonatorResynthesisExample --args="input.wav build/resynth balanced"
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: TrackingResonatorResynthesisExampleKt <input.wav> [output-dir] [balanced|high|extreme|superextreme]")
        return
    }

    val inputFile = File(args[0])
    val secondArg = args.getOrNull(1)
    val secondArgIsPreset = secondArg != null && AnalysisPreset.isPresetName(secondArg)
    val outputDir = File(
        if (secondArg == null || secondArgIsPreset) {
            inputFile.resolveSibling("${inputFile.nameWithoutExtension}-tracking-resynth").path
        } else {
            secondArg
        }
    )
    val preset = AnalysisPreset.named(
        if (secondArgIsPreset) secondArg else args.getOrNull(2) ?: "balanced"
    )
    outputDir.mkdirs()

    println("tracking resonator resynthesis")
    println("input : ${inputFile.absolutePath}")
    println("output: ${outputDir.absolutePath}")
    println("preset: ${preset.name} (${preset.description})")

    val loadedWav = timed("load wav") {
        WavFloatArrayLoader.loadWavToFloatArray(inputFile.absolutePath)
    }
    println(
        "loaded ${loadedWav.samples.size} samples at ${loadedWav.sampleRate} Hz " +
            "(${formatSeconds(loadedWav.samples.size.toDouble() / loadedWav.sampleRate)})"
    )

    val wav = firstSeconds(loadedWav, DEMO_SECONDS)
    println(
        "demo crop: using first ${formatSeconds(wav.samples.size.toDouble() / wav.sampleRate)} " +
            "(${wav.samples.size} samples)"
    )

    val frequencies = logFrequencies(
        count = preset.resonatorCount,
        minHz = preset.minHz,
        maxHz = min(preset.maxHz, wav.sampleRate * 0.47f)
    )
    val alphas = TrackingResonatorBank.alphasHeuristic(frequencies, wav.sampleRate.toFloat(), k = preset.alphaK)
    val betas = alphas.copyOf()
    val gammas = TrackingResonatorBank.gammasHeuristic(frequencies, wav.sampleRate.toFloat(), k = preset.gammaK)
    val hopLength = preset.hopLength

    println(
        "analysis setup: ${frequencies.size} log-spaced resonators, " +
            "${frequencies.first().roundToInt()}-${frequencies.last().roundToInt()} Hz, " +
            "hopLength=$hopLength, alphaK=${preset.alphaK}, gammaK=${preset.gammaK}"
    )
    val estimatedFrames = wav.samples.size / hopLength
    println(
        "analysis estimate: $estimatedFrames frames, " +
            "about ${formatBytes(estimatedTrackingAnalysisBytes(estimatedFrames, frequencies.size))} on disk"
    )
    val analysis = timed("analyze") {
        val bar = ConsoleProgressBar("analyze", "frames")
        trackingResonate(
            input = wav.samples,
            samplingRate = wav.sampleRate.toFloat(),
            naturalFrequencies = frequencies,
            alphas = alphas,
            betas = betas,
            gammas = gammas,
            hopLength = hopLength,
            powerTimeConstant = preset.powerTimeConstantSeconds,
            progress = bar::update
        ).also {
            bar.finish()
        }
    }

    val analysisFile = File(outputDir, "${inputFile.nameWithoutExtension}.f4track")
    timed("write analysis") {
        analysis.writeTo(analysisFile)
    }
    println("analysis file: ${analysisFile.absolutePath} (${formatBytes(analysisFile.length())})")
    printAnalysisSummary(analysis)

    val straightLength = analysis.transformedSampleCount(1.0f)
    println(
        "straight reconstruction: pitch=0.0 st, stretch=1.0, samples=$straightLength " +
            "(${formatSeconds(straightLength.toDouble() / wav.sampleRate)})"
    )
    val straight = timed("resynth straight-reconstruction") {
        val bar = ConsoleProgressBar("resynth straight", "samples")
        analysis.resynthesizeAdditive(
            length = straightLength,
            maxPartials = preset.reconstructionPartials,
            minMeanPower = preset.minMeanPower * 0.1f,
            gain = preset.reconstructionGain,
            normalizePeak = null,
            pitchShiftSemitones = 0.0f,
            timeStretch = 1.0f,
            amplitudeScale = 2.0f,
            localMaximaOnly = preset.reconstructionLocalMaximaOnly,
            minResonatorSpacing = preset.reconstructionMinPeakSpacing,
            selectionFrequencyTilt = preset.reconstructionSelectionFrequencyTilt,
            amplitudeFrequencyTilt = preset.reconstructionAmplitudeFrequencyTilt,
            progress = bar::update
        ).also {
            bar.finish()
        }
    }
    val straightFitGain = bestFitGain(model = straight, target = wav.samples)
    val fittedStraight = scaledCopy(straight, straightFitGain)
    val residual = residualCopy(target = wav.samples, model = fittedStraight)
    val straightPlusResidual = sumCopy(fittedStraight, residual)
    println(
        "straight fit: gain=${formatNumber(straightFitGain)}, " +
            "source=${signalSummary(wav.samples)}, additive=${signalSummary(fittedStraight)}, " +
            "residual=${signalSummary(residual)}"
    )

    val straightFile = File(outputDir, "${inputFile.nameWithoutExtension}-straight-reconstruction.wav")
    timed("write straight-reconstruction") {
        WavFloatArrayWriter.writeMono16(straightFile, fittedStraight, wav.sampleRate)
    }
    println("  wav: ${straightFile.absolutePath} (${formatBytes(straightFile.length())})")

    val residualFile = File(outputDir, "${inputFile.nameWithoutExtension}-residual.wav")
    timed("write residual") {
        WavFloatArrayWriter.writeMono16(residualFile, residual, wav.sampleRate)
    }
    println("  wav: ${residualFile.absolutePath} (${formatBytes(residualFile.length())})")

    val plusResidualFile = File(outputDir, "${inputFile.nameWithoutExtension}-straight-plus-residual.wav")
    timed("write straight-plus-residual") {
        WavFloatArrayWriter.writeMono16(plusResidualFile, straightPlusResidual, wav.sampleRate)
    }
    println("  wav: ${plusResidualFile.absolutePath} (${formatBytes(plusResidualFile.length())})")

    val variants = listOf(
        Variant("down-5-slow-125", pitchShiftSemitones = -5.0f, timeStretch = 1.25f),
        Variant("up-7-same-time", pitchShiftSemitones = 7.0f, timeStretch = 1.0f),
        Variant("up-12-fast-075", pitchShiftSemitones = 12.0f, timeStretch = 0.75f)
    )

    for (variant in variants) {
        val length = analysis.transformedSampleCount(variant.timeStretch)
        println(
            "variant ${variant.name}: pitch=${variant.pitchShiftSemitones} st, " +
                "stretch=${variant.timeStretch}, samples=$length " +
                "(${formatSeconds(length.toDouble() / wav.sampleRate)})"
        )
        val resynth = timed("resynth ${variant.name}") {
            val bar = ConsoleProgressBar("resynth ${variant.name}", "samples")
            analysis.resynthesizeAdditive(
                length = length,
                maxPartials = preset.maxPartials,
                minMeanPower = preset.minMeanPower,
                gain = preset.gain,
                normalizePeak = 0.92f,
                pitchShiftSemitones = variant.pitchShiftSemitones,
                timeStretch = variant.timeStretch,
                amplitudeScale = 2.0f,
                localMaximaOnly = true,
                minResonatorSpacing = preset.minPeakSpacing,
                selectionFrequencyTilt = 0.30f,
                amplitudeFrequencyTilt = 0.12f,
                progress = bar::update
            ).also {
                bar.finish()
            }
        }
        val outFile = File(outputDir, "${inputFile.nameWithoutExtension}-${variant.name}.wav")
        timed("write ${variant.name}") {
            WavFloatArrayWriter.writeMono16(outFile, resynth, wav.sampleRate)
        }
        println("  wav: ${outFile.absolutePath} (${formatBytes(outFile.length())})")
    }

    println("done")
}

private data class Variant(
    val name: String,
    val pitchShiftSemitones: Float,
    val timeStretch: Float
)

private data class AnalysisPreset(
    val name: String,
    val description: String,
    val resonatorCount: Int,
    val hopLength: Int,
    val maxPartials: Int,
    val reconstructionPartials: Int,
    val minHz: Float,
    val maxHz: Float,
    val alphaK: Float,
    val gammaK: Float,
    val minPeakSpacing: Int,
    val minMeanPower: Float,
    val gain: Float,
    val reconstructionGain: Float,
    val powerTimeConstantSeconds: Float,
    val reconstructionLocalMaximaOnly: Boolean = true,
    val reconstructionMinPeakSpacing: Int = 0,
    val reconstructionSelectionFrequencyTilt: Float = 0.55f,
    val reconstructionAmplitudeFrequencyTilt: Float = 0.20f
) {
    companion object {
        fun isPresetName(name: String): Boolean =
            name.lowercase(Locale.US) in setOf("balanced", "high", "extreme", "superextreme")

        fun named(name: String): AnalysisPreset =
            when (name.lowercase(Locale.US)) {
                "balanced" -> AnalysisPreset(
                    name = "balanced",
                    description = "large-file friendly: 2x temporal resolution, 2x bank density, cleaner peak tracks",
                    resonatorCount = 384,
                    hopLength = 256,
                    maxPartials = 128,
                    reconstructionPartials = 256,
                    minHz = 32.0f,
                    maxHz = 12_000.0f,
                    alphaK = 2.4f,
                    gammaK = 1.5f,
                    minPeakSpacing = 1,
                    minMeanPower = 3e-7f,
                    gain = 0.16f,
                    reconstructionGain = 0.10f,
                    powerTimeConstantSeconds = 0.025f
                )
                "high" -> AnalysisPreset(
                    name = "high",
                    description = "dense bank, short hops, local-peak additive tracks; best for moderate files",
                    resonatorCount = 768,
                    hopLength = 128,
                    maxPartials = 224,
                    reconstructionPartials = 512,
                    minHz = 28.0f,
                    maxHz = 16_000.0f,
                    alphaK = 3.0f,
                    gammaK = 1.25f,
                    minPeakSpacing = 2,
                    minMeanPower = 1e-7f,
                    gain = 0.12f,
                    reconstructionGain = 0.075f,
                    powerTimeConstantSeconds = 0.018f
                )
                "extreme" -> AnalysisPreset(
                    name = "extreme",
                    description = "slow but much denser; useful for short files",
                    resonatorCount = 1536,
                    hopLength = 64,
                    maxPartials = 384,
                    reconstructionPartials = 1024,
                    minHz = 24.0f,
                    maxHz = 18_000.0f,
                    alphaK = 3.5f,
                    gammaK = 1.1f,
                    minPeakSpacing = 2,
                    minMeanPower = 5e-8f,
                    gain = 0.08f,
                    reconstructionGain = 0.045f,
                    powerTimeConstantSeconds = 0.012f
                )
                "superextreme" -> AnalysisPreset(
                    name = "superextreme",
                    description = "very slow verification mode: dense, fast-tracking, near-Nyquist, minimal pruning",
                    resonatorCount = 3072,
                    hopLength = 32,
                    maxPartials = 1536,
                    reconstructionPartials = 3072,
                    minHz = 18.0f,
                    maxHz = 20_500.0f,
                    alphaK = 1.8f,
                    gammaK = 0.75f,
                    minPeakSpacing = 1,
                    minMeanPower = 1e-9f,
                    gain = 0.045f,
                    reconstructionGain = 0.025f,
                    powerTimeConstantSeconds = 0.006f,
                    reconstructionLocalMaximaOnly = false,
                    reconstructionMinPeakSpacing = 0,
                    reconstructionSelectionFrequencyTilt = 0.35f,
                    reconstructionAmplitudeFrequencyTilt = 0.12f
                )
                else -> error("unknown preset '$name'; expected balanced, high, extreme, or superextreme")
            }
    }
}

private fun logFrequencies(count: Int, minHz: Float, maxHz: Float): FloatArray {
    require(count > 0) { "count must be > 0" }
    require(minHz > 0.0f && maxHz > minHz) { "bad frequency range" }
    val logMin = ln(minHz.toDouble())
    val logMax = ln(maxHz.toDouble())
    return FloatArray(count) { i ->
        val t = if (count == 1) 0.0 else i.toDouble() / (count - 1)
        kotlin.math.exp(logMin + (logMax - logMin) * t).toFloat()
    }
}

private fun firstSeconds(wav: WavFloatData, seconds: Float): WavFloatData {
    require(seconds > 0.0f) { "seconds must be > 0" }
    val sampleCount = (seconds * wav.sampleRate).roundToInt().coerceAtMost(wav.samples.size)
    return if (sampleCount == wav.samples.size) {
        wav
    } else {
        WavFloatData(wav.samples.copyOf(sampleCount), wav.sampleRate)
    }
}

private fun printAnalysisSummary(analysis: TrackingResonatorAnalysis) {
    println(
        "analysis: frames=${analysis.numSlices}, resonators=${analysis.numResonators}, " +
            "duration=${(analysis.durationSeconds * 100.0f).roundToInt() / 100.0f}s"
    )
    println("dominant mean-power tracks:")
    val dominant = analysis.resonatorsByMeanPower(count = 8)
    for (resonator in dominant) {
        val track = analysis.track(resonator)
        val meanHz = track.frequencies.average().toFloat()
        println(
            "  #$resonator natural=${track.naturalFrequency.roundToInt()} Hz " +
                "meanTracked=${meanHz.roundToInt()} Hz meanPower=${analysis.meanPower(resonator)}"
        )
    }
}

private fun estimatedTrackingAnalysisBytes(frameCount: Int, resonatorCount: Int): Long {
    val headerBytes = 7L * Int.SIZE_BYTES
    val perResonatorBytes = 4L * resonatorCount * Float.SIZE_BYTES
    val perFrameBytes = frameCount.toLong() * resonatorCount * 4L * Float.SIZE_BYTES
    return headerBytes + perResonatorBytes + perFrameBytes
}

private fun bestFitGain(model: FloatArray, target: FloatArray): Float {
    val n = min(model.size, target.size)
    var numerator = 0.0
    var denominator = 0.0
    for (i in 0 until n) {
        numerator += model[i] * target[i]
        denominator += model[i] * model[i]
    }
    return if (denominator > 0.0) (numerator / denominator).toFloat() else 0.0f
}

private fun scaledCopy(values: FloatArray, gain: Float): FloatArray =
    FloatArray(values.size) { i -> values[i] * gain }

private fun residualCopy(target: FloatArray, model: FloatArray): FloatArray {
    val n = min(target.size, model.size)
    return FloatArray(n) { i -> target[i] - model[i] }
}

private fun sumCopy(left: FloatArray, right: FloatArray): FloatArray {
    val n = min(left.size, right.size)
    return FloatArray(n) { i -> left[i] + right[i] }
}

private fun signalSummary(samples: FloatArray): String =
    "rms=${formatNumber(rms(samples))}, peak=${formatNumber(peak(samples))}"

private fun rms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0.0f
    var sum = 0.0
    for (sample in samples) {
        sum += sample * sample
    }
    return sqrt(sum / samples.size).toFloat()
}

private fun peak(samples: FloatArray): Float {
    var peak = 0.0f
    for (sample in samples) {
        val value = abs(sample)
        if (value > peak) peak = value
    }
    return peak
}

private inline fun <T> timed(label: String, block: () -> T): T {
    println("$label...")
    val start = System.nanoTime()
    return try {
        block()
    } finally {
        val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0
        println("$label complete in ${formatSeconds(elapsedSeconds)}")
    }
}

private class ConsoleProgressBar(
    private val label: String,
    private val units: String,
    private val width: Int = 32
) {
    private var lastPercent = -1
    private var finished = false

    fun update(completed: Int, total: Int) {
        if (finished) return
        val safeTotal = total.coerceAtLeast(1)
        val safeCompleted = completed.coerceIn(0, safeTotal)
        val percent = ((safeCompleted.toDouble() / safeTotal) * 100.0).roundToInt().coerceIn(0, 100)
        if (percent == lastPercent && safeCompleted != safeTotal) return
        lastPercent = percent

        val filled = ((percent / 100.0) * width).roundToInt().coerceIn(0, width)
        val bar = "#".repeat(filled) + "-".repeat(width - filled)
        print("\r${label.take(28).padEnd(28)} [$bar] ${percent.toString().padStart(3)}% $safeCompleted/$total $units")
        System.out.flush()
    }

    fun finish() {
        if (!finished) {
            println()
            finished = true
        }
    }
}

private fun formatSeconds(seconds: Double): String =
    when {
        seconds < 60.0 -> String.format(Locale.US, "%.2fs", seconds)
        seconds < 3600.0 -> {
            val minutes = (seconds / 60.0).toInt()
            val remainder = seconds - minutes * 60.0
            String.format(Locale.US, "%dm %.1fs", minutes, remainder)
        }
        else -> {
            val hours = (seconds / 3600.0).toInt()
            val minutes = ((seconds - hours * 3600.0) / 60.0).toInt()
            val remainder = seconds - hours * 3600.0 - minutes * 60.0
            String.format(Locale.US, "%dh %dm %.0fs", hours, minutes, remainder)
        }
    }

private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "$bytes ${units[unit]}"
    } else {
        String.format(Locale.US, "%.2f %s", value, units[unit])
    }
}

private fun formatNumber(value: Float): String =
    String.format(Locale.US, "%.4g", value)
