package afield.stdlib.loris

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

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
