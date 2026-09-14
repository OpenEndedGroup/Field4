package afield.examples.steamaudio

import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.HrtfInterpolation
import afield.four.audio.steamaudio.SteamAudioContext
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures the binaural effect directly: feeds a single impulse from a series of azimuths and
 * reports the per-ear arrival delay and level.
 *
 * This is the diagnostic that actually pins down whether spatialisation is correct, because an
 * impulse is broadband and aperiodic -- unlike a tone, whose periodicity makes cross-correlation
 * ambiguous and whose narrow spectrum understates interaural level difference.
 *
 * What a correct HRTF should show:
 *
 *  - **Sign**: for a source on the right, the right ear leads (smaller delay) and is louder.
 *  - **ITD magnitude**: up to roughly 600-700 microseconds at full lateral, which is the time
 *    for sound to travel the extra distance around a head.
 *  - **Symmetry**: +90 and -90 should mirror each other.
 *  - **Median plane**: at 0 and 180 degrees, both ears should be near-identical.
 */
fun main() {
    val settings = AudioSettings(samplingRate = 48_000, frameSize = 1024)

    SteamAudioContext().use { context ->
        Arena.ofShared().use { arena ->
            Hrtf.default(context, settings).use { hrtf ->
                BinauralEffect(context, settings, hrtf).use { binaural ->

                    val input = AudioBuffer(arena, 1, settings.frameSize)
                    val output = AudioBuffer(arena, 2, settings.frameSize)
                    val params = BinauralEffectParams(
                        arena,
                        hrtf = hrtf,
                        // NEAREST avoids blending two measurements together, so the impulse
                        // response we measure is a single HRTF tap rather than a mixture.
                        interpolation = HrtfInterpolation.NEAREST,
                        trackPeakDelays = true,
                    )

                    val left = FloatArray(settings.frameSize)
                    val right = FloatArray(settings.frameSize)

                    println("Binaural effect probe -- impulse response per azimuth")
                    println("azimuth is measured clockwise from straight ahead; +90 is the right ear side")
                    println()
                    println(" azimuth   peak L   peak R    ILD dB   onset L   onset R   ITD us   leads")
                    println(" " + "-".repeat(78))

                    for (azimuthDegrees in intArrayOf(0, 30, 60, 90, 120, 150, 180, -150, -120, -90, -60, -30)) {
                        val radians = azimuthDegrees * PI / 180.0
                        // Unit direction in the listener's frame: +x right, -z ahead.
                        val dx = sin(radians).toFloat()
                        val dz = (-cos(radians)).toFloat()

                        binaural.reset()
                        params.setDirection(dx, 0f, dz)

                        // A single unit impulse at the start of an otherwise silent block.
                        input.clear()
                        input[0, 0] = 1.0f
                        output.clear()
                        binaural.apply(params, input, output)

                        output.readChannel(0, left)
                        output.readChannel(1, right)

                        val peakL = left.maxOf { abs(it) }
                        val peakR = right.maxOf { abs(it) }
                        val onsetL = onsetIndex(left)
                        val onsetR = onsetIndex(right)
                        val itdSamples = onsetL - onsetR          // positive => right ear leads
                        val itdMicros = itdSamples.toDouble() / settings.samplingRate * 1e6
                        val ild = 20.0 * log10((peakR + 1e-12) / (peakL + 1e-12))

                        val leads = when {
                            itdSamples > 0 -> "right"
                            itdSamples < 0 -> "left"
                            else -> "--"
                        }

                        println(
                            "%8d %8.4f %8.4f %+9.2f %9d %9d %+8.0f   %s".format(
                                azimuthDegrees, peakL, peakR, ild, onsetL, onsetR, itdMicros, leads,
                            ),
                        )
                    }

                    println()
                    println("peak delays reported by Steam Audio for the last direction: " +
                        "L=${params.leftPeakDelay}  R=${params.rightPeakDelay}")

                    // Energy check across the sphere: a well-formed HRTF should not change
                    // total loudness wildly with direction.
                    println()
                    var minEnergy = Double.MAX_VALUE
                    var maxEnergy = 0.0
                    for (azimuthDegrees in 0 until 360 step 15) {
                        val radians = azimuthDegrees * PI / 180.0
                        binaural.reset()
                        params.setDirection(sin(radians).toFloat(), 0f, (-cos(radians)).toFloat())
                        input.clear()
                        input[0, 0] = 1.0f
                        output.clear()
                        binaural.apply(params, input, output)
                        output.readChannel(0, left)
                        output.readChannel(1, right)
                        val energy = sqrt(left.sumOf { it.toDouble() * it } + right.sumOf { it.toDouble() * it })
                        minEnergy = minOf(minEnergy, energy)
                        maxEnergy = maxOf(maxEnergy, energy)
                    }
                    println(
                        "total energy across 24 azimuths: min %.4f  max %.4f  spread %.2f dB"
                            .format(minEnergy, maxEnergy, 20 * log10(maxEnergy / minEnergy)),
                    )
                }
            }
        }
    }
}

/**
 * Index of the first sample exceeding a fraction of the signal's peak.
 *
 * A fixed threshold relative to the peak is a reasonable onset estimator for an impulse
 * response, and is far more robust than cross-correlation on a periodic signal.
 */
private fun onsetIndex(samples: FloatArray, fraction: Float = 0.1f): Int {
    val peak = samples.maxOf { abs(it) }
    if (peak == 0f) return -1
    val threshold = peak * fraction
    for (index in samples.indices) {
        if (abs(samples[index]) >= threshold) return index
    }
    return -1
}
