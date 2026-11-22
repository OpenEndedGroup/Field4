package afield.examples.steamaudio

import kotlin.math.abs
import kotlin.math.log10

/*
FIELD_TEST
id: steamaudio.screen-study
runner: jvm-main
entrypoint: afield.examples.steamaudio.SteamAudioScreenStudySmoke
tags: [smoke, audio, steamaudio, dirt-samples]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "SCREEN_STUDY_PASSED"
*/

/** Check the two real renders retain both the right-side placement and the change in level. */
object SteamAudioScreenStudySmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val clear = SteamAudioScreenStudy.render(SteamAudioScreenStudy.CLEAR_OFFSET)
        val blocked = SteamAudioScreenStudy.render(0f)
        fun energy(samples: FloatArray) = samples.sumOf { it.toDouble() * it }
        fun energy(take: SteamAudioScreenStudy.Take) = energy(take.left) + energy(take.right)

        check(clear.occlusion > 0.99f && blocked.occlusion < 0.01f)
        check(blocked.transmission.all { it in 0f..1f })
        check(energy(clear.right) > energy(clear.left))
        check(energy(blocked) > 0 && energy(blocked) < energy(clear) * 0.1)
        val peak = maxOf(clear.left.maxOf { abs(it) }, clear.right.maxOf { abs(it) })
        check(peak in 0.3f..0.7f) { "Unexpected clear-take peak: $peak" }
        val drop = 10 * log10(energy(clear) / energy(blocked))
        println("SCREEN_STUDY_PASSED — clear=${clear.occlusion}, blocked=${blocked.occlusion}, drop=$drop dB")
    }
}
