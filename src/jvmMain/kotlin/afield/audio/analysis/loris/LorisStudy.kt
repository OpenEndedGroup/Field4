@file:afield.runtime.completion.CompletionCategory("Audio")

package afield.audio.analysis.loris

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * One worked Loris gesture, written in the same breakpoint arithmetic that LorisTransforms.kt
 * applies one edit at a time.
 *
 * [spectralWhirlpool] reshapes a window of a [LorisModel] rather than the whole of it. A `sin²` bell
 * rises and falls across the window, so the edit begins and ends at zero and every breakpoint
 * outside the window is returned unchanged. Under that bell each partial sweeps four times through
 * ±18 semitones, its amplitude is shuttered at 12 Hz, and its noise bandwidth is pushed towards 0.85
 * at the peak: a vibrato that turns breathy in the middle and settles again. Pass the result to
 * [LorisClient.synthesize] to hear it.
 */

/** Example gesture: smooth local ±18-semitone sweeps, 12 Hz amplitude shutters and noisy peaks. */
fun LorisModel.spectralWhirlpool(startSeconds: Double = 6.0, durationSeconds: Double = 3.0): LorisModel {
    require(startSeconds.isFinite() && durationSeconds.isFinite() && startSeconds >= 0 && durationSeconds > 0)
    require(startSeconds + durationSeconds <= this.durationSeconds)
    return copy(partials = partials.map { partial -> partial.copy(points = partial.points.map { point ->
        val u = (point.timeSeconds - startSeconds) / durationSeconds
        if (u <= 0.0 || u >= 1.0) point else {
            val envelope = sin(PI * u).pow(2)
            val octaves = 1.5 * envelope * sin(2 * PI * 4 * u)
            val shutter = .25 + .75 * (.5 + .5 * sin(2 * PI * 12 * durationSeconds * u))
            point.copy(
                frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(sampleRate * .49),
                amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                bandwidth = point.bandwidth * (1 - envelope) + .85 * envelope,
            )
        }
    }) })
}
