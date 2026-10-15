@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.audio.analysis.loris

import kotlin.math.*

// Kotlin-side partial edits. They change envelopes only; resynthesize with LorisClient.synthesize.
// Loris's own labelling, sifting, dilation and morphing run natively: see LorisClient.distill/sift/dilate/morph.

val LorisPartial.startSeconds: Double get() = points.first().timeSeconds
val LorisPartial.endSeconds: Double get() = points.last().timeSeconds
val LorisPartial.durationSeconds: Double get() = endSeconds - startSeconds
val LorisPartial.peakAmplitude: Double get() = points.maxOf { it.amplitude }
/** Amplitude-weighted mean frequency. */
val LorisPartial.meanFrequencyHz: Double get() {
    val w = points.sumOf { it.amplitude }
    return if (w <= 0) points.sumOf { it.frequencyHz } / points.size else points.sumOf { it.frequencyHz * it.amplitude } / w
}

/** Number of partials carrying each label; label 0 is "unlabeled". */
fun LorisModel.labelCounts(): Map<Int, Int> = partials.groupingBy { it.label }.eachCount().toSortedMap()

/** Keep partials matching [keep]; ids are preserved. */
fun LorisModel.filterPartials(keep: (LorisPartial) -> Boolean): LorisModel = copy(partials = partials.filter(keep))

fun LorisModel.keepLabels(labels: Iterable<Int>): LorisModel = labels.toSet().let { s -> filterPartials { it.label in s } }
fun LorisModel.keepLabels(range: IntRange): LorisModel = filterPartials { it.label in range }
fun LorisModel.dropUnlabeled(): LorisModel = filterPartials { it.label != 0 }
fun LorisModel.minimumDuration(seconds: Double): LorisModel = filterPartials { it.durationSeconds >= seconds }
/** Drop partials whose loudest breakpoint is below [db] dBFS (amplitude 1 = 0 dB). */
fun LorisModel.minimumPeak(db: Double): LorisModel = 10.0.pow(db / 20).let { a -> filterPartials { it.peakAmplitude >= a } }

/** Transpose by [cents] (may vary in time); breakpoints pushed past 0.49 × sampleRate are removed. */
fun LorisModel.shiftPitch(cents: LorisEnvelope): LorisModel = mapPoints { b ->
    b.copy(frequencyHz = b.frequencyHz * 2.0.pow(cents.valueAt(b.timeSeconds) / 1200))
}
fun LorisModel.shiftPitch(cents: Double): LorisModel = shiftPitch(LorisEnvelope.constant(cents))

/** Uniform time stretch: all breakpoint times and the timeline scale by [factor]. */
fun LorisModel.stretched(factor: Double): LorisModel = transformed(timeScale = factor)

/**
 * Spectral tilt: amplitude × (f / [pivotHz]) ^ ([dbPerOctave] / 6.02...), so +6 dB/oct brightens and
 * −6 dB/oct darkens, with partials at [pivotHz] unchanged.
 */
fun LorisModel.tilt(dbPerOctave: Double, pivotHz: Double = 1000.0): LorisModel {
    require(dbPerOctave.isFinite() && pivotHz.isFinite() && pivotHz > 0)
    return mapPoints { b -> b.copy(amplitude = b.amplitude * 10.0.pow(dbPerOctave * log2(b.frequencyHz / pivotHz) / 20)) }
}

/** Keep [startSeconds, endSeconds] and move it to time 0. Partials are cut at the boundaries' nearest breakpoints. */
fun LorisModel.crop(startSeconds: Double, endSeconds: Double): LorisModel {
    require(startSeconds >= 0 && endSeconds > startSeconds && endSeconds <= durationSeconds)
    return copy(frameCount = ceil((endSeconds - startSeconds) * sampleRate).toInt(), partials = partials.mapNotNull { p ->
        p.points.filter { it.timeSeconds in startSeconds..endSeconds }.map { it.copy(timeSeconds = it.timeSeconds - startSeconds) }
            .takeIf { it.isNotEmpty() }?.let { p.copy(points = it) }
    })
}

private fun LorisModel.mapPoints(f: (LorisBreakpoint) -> LorisBreakpoint): LorisModel = copy(partials = partials.mapNotNull { p ->
    p.points.map(f).filter { it.frequencyHz < sampleRate * .49 }.takeIf { it.isNotEmpty() }?.let { p.copy(points = it) }
})

/** Centre of the [binSeconds] bin with the largest summed breakpoint amplitude: a simple marker for dilate. */
fun LorisModel.loudestTimeSeconds(binSeconds: Double = .05): Double {
    require(binSeconds.isFinite() && binSeconds > 0)
    val bins = DoubleArray(ceil(durationSeconds / binSeconds).toInt().coerceAtLeast(1))
    partials.forEach { p -> p.points.forEach { b -> bins[(b.timeSeconds / binSeconds).toInt().coerceIn(bins.indices)] += b.amplitude } }
    return (bins.indices.maxBy { bins[it] } + .5) * binSeconds
}
