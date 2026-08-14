@file:afield.CompletionCategory("Patterns")
/*
 Where a rendered pattern's notes actually start, measured from the audio.

 This exists so that two different renderers can be measured with the *same* instrument. Field
 renders a pattern offline through `patRenderStereo`; Sonic Pi renders it through SuperCollider after
 `SonicPiPatterns` lowers it. Each can be checked against the pattern's exact rational schedule, and
 the comparison is only worth making if the measuring device is shared — two detectors tuned
 separately would agree with their own renderer and tell you nothing about the pair.

 Nothing here is novel signal processing. It is an energy envelope, half-wave rectified flux, a
 local-maximum test and a refractory gap, and it is adequate because the question is *when* rather
 than *what*: these are bare percussive trains with nothing else in the mix.

 Two details are load-bearing, and both were learned by getting them wrong in a way that looked like
 success. They are the reason this is a file rather than four lines at a call site.

 **Resolution.** A flux peak in a 256-sample window can only say which *frame* something happened
 in. A measurement whose resolution is coarser than the error it is looking for reports zero
 whatever the truth is — the first version of this reported `0.0 ms` at a resolution of 2.7 ms, and
 was believed. So every coarse hit is walked back to the first sample that crosses a fraction of the
 local peak, and [patDetectOnsetSamples] returns *samples*.

 **The refractory gap, applied after refinement.** `bd_haus` has a secondary energy rise 129 ms into
 its decay. A first-past-the-post refractory on the coarse stage reports that shoulder *and*
 suppresses the real attack that follows it — which reads as a timing failure rather than the
 detection failure it is, and which cost an hour. The gap is the pattern's own smallest interval,
 and deduplication happens after the onsets are exact.
 */
package afield.four.synth

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Analysis hop, in samples. 32 at 48 kHz is 0.67 ms — finer than anything being asserted. */
const val PAT_ONSET_HOP: Int = 32

/** Analysis window, in samples. */
const val PAT_ONSET_WINDOW: Int = 256

/** RMS per hop — a plain energy envelope, which is all a percussive train needs. */
fun patEnergyEnvelope(
    samples: FloatArray,
    hop: Int = PAT_ONSET_HOP,
    window: Int = PAT_ONSET_WINDOW
): DoubleArray {
    val frames = max(1, (samples.size - window) / hop)
    val env = DoubleArray(frames)
    for (f in 0 until frames) {
        val start = f * hop
        var sum = 0.0
        for (i in start until min(start + window, samples.size)) {
            sum += samples[i].toDouble() * samples[i]
        }
        env[f] = sqrt(sum / window)
    }
    return env
}

/**
 * Where each note starts, to the sample.
 *
 * [minGapSeconds] is the smallest interval the *pattern* can produce, not a tuning constant: the
 * detector is told what it is allowed to resolve and refuses to report anything closer. Deriving it
 * from the pattern rather than from taste is what keeps this a measurement.
 *
 * @param sensitivity where the adaptive threshold sits between the flux median and its 99.5th
 *   percentile. Nearer the floor admits a note struck during the tail of the previous one; nearer
 *   the top drops it, which reads as a timing failure rather than the detection failure it is.
 */
fun patDetectOnsetSamples(
    samples: FloatArray,
    sampleRate: Int,
    minGapSeconds: Double,
    envelope: DoubleArray = patEnergyEnvelope(samples),
    sensitivity: Double = 0.30
): List<Int> {
    if (envelope.size < 8) return emptyList()
    val flux = DoubleArray(envelope.size)
    for (i in 1 until envelope.size) flux[i] = max(0.0, envelope[i] - envelope[i - 1])

    val sorted = flux.sortedArray()
    val median = sorted[sorted.size / 2]
    val high = sorted[(sorted.size * 0.995).toInt().coerceAtMost(sorted.size - 1)]
    val threshold = median + sensitivity * (high - median)

    val refractoryFrames = (minGapSeconds * 0.45 * sampleRate / PAT_ONSET_HOP).toInt().coerceAtLeast(1)
    val coarse = mutableListOf<Int>()
    var lastFrame = -refractoryFrames * 2
    for (i in 3 until flux.size - 3) {
        if (flux[i] < threshold) continue
        if (flux[i] < flux[i - 1] || flux[i] < flux[i + 1]) continue
        if (i - lastFrame < refractoryFrames) continue
        coarse += i * PAT_ONSET_HOP
        lastFrame = i
    }

    // Walk each coarse hit back to the attack itself. Without this the reported error can never be
    // smaller than a hop, whatever the renderer actually did.
    val backS = (0.030 * sampleRate).toInt()
    val forwardS = (0.010 * sampleRate).toInt()
    val refined = coarse.map { at ->
        val lo = max(0, at - backS)
        val hi = min(samples.size, at + forwardS)
        var peak = 0.0f
        for (i in lo until hi) if (abs(samples[i]) > peak) peak = abs(samples[i])
        val trigger = 0.05f * peak
        var found = lo
        for (i in lo until hi) if (abs(samples[i]) > trigger) { found = i; break }
        found
    }.sorted()

    val gapSamples = (minGapSeconds * 0.5 * sampleRate).toInt()
    val out = mutableListOf<Int>()
    for (at in refined) if (out.isEmpty() || at - out.last() >= gapSamples) out += at
    return out
}

/** How an observed onset train compared with the schedule it was supposed to realise. */
data class PatOnsetMatch(
    val observed: Int,
    val scheduled: Int,
    val matched: Int,
    /** Worst |observed − scheduled| in seconds, after the constant offset is removed. */
    val worstError: Double,
    val meanError: Double,
    val recall: Double,
    /** The constant lag removed before measuring — reported, never judged. */
    val offset: Double
) {
    fun describe(label: String): String =
        "$label: $observed observed, $scheduled scheduled, $matched matched; " +
            "recall ${"%.3f".format(recall)}, mean |error| ${"%.3f".format(meanError * 1000)} ms, " +
            "worst ${"%.3f".format(worstError * 1000)} ms " +
            "(constant offset removed: ${"%+.1f".format(offset * 1000)} ms)"
}

/**
 * Compare an onset train against the schedule it should have realised.
 *
 * Two things are deliberately not judged. Neither renderer controls its own zero — a Sonic Pi
 * recording starts before the music and an offline render starts exactly at it — so the train is
 * anchored on its earliest onset and then shifted by the **median** of the matched residuals, which
 * stops the first onset being privileged into a free 0 ms. And the constant lag that shift removes
 * is reported rather than asserted: a sequencer whose events all arrive 40 ms late is still a
 * correct sequencer. What is measured is the *shape* — the intervals, and whether they drift.
 */
fun patMatchOnsets(
    observedSeconds: List<Double>,
    scheduledSeconds: List<Double>,
    tolerance: Double
): PatOnsetMatch {
    if (observedSeconds.isEmpty() || scheduledSeconds.isEmpty()) {
        return PatOnsetMatch(observedSeconds.size, scheduledSeconds.size, 0, Double.NaN, Double.NaN, 0.0, 0.0)
    }
    val anchored = observedSeconds.map { it - observedSeconds.first() }

    fun residuals(train: List<Double>): List<Double> = scheduledSeconds
        .filter { it <= train.last() + tolerance }
        .mapNotNull { want ->
            val nearest = train.minByOrNull { abs(it - want) } ?: return@mapNotNull null
            if (abs(nearest - want) <= tolerance) nearest - want else null
        }

    val coarse = residuals(anchored)
    val offset = if (coarse.isEmpty()) 0.0 else coarse.sorted()[coarse.size / 2]
    val shifted = anchored.map { it - offset }

    val errors = residuals(shifted).map { abs(it) }
    val considered = scheduledSeconds.count { it <= shifted.last() + tolerance }
    return PatOnsetMatch(
        observed = shifted.size,
        scheduled = considered,
        matched = errors.size,
        worstError = errors.maxOrNull() ?: Double.NaN,
        meanError = if (errors.isEmpty()) Double.NaN else errors.average(),
        recall = if (considered == 0) 0.0 else errors.size.toDouble() / considered,
        offset = offset
    )
}
