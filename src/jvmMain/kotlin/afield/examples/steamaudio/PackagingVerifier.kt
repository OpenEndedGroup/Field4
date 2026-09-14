package afield.examples.steamaudio

import afield.four.audio.steamaudio.AudioBuffer
import afield.four.audio.steamaudio.AudioSettings
import afield.four.audio.steamaudio.BinauralEffect
import afield.four.audio.steamaudio.BinauralEffectParams
import afield.four.audio.steamaudio.Hrtf
import afield.four.audio.steamaudio.SteamAudio
import afield.four.audio.steamaudio.SteamAudioContext
import afield.four.audio.steamaudio.SteamAudioNatives
import java.lang.foreign.Arena

/**
 * Verifies the published packaging from a consumer's position.
 *
 * Run with only the library jar and one natives jar on the classpath, and with **no**
 * `steamaudio.library.path` set, so the loader has to find and extract the library from
 * classpath resources. That is the path a real dependency takes, and it is the one that a
 * mismatch between the jar layout and the loader's resource path would break -- at the
 * consumer's runtime, not in our build.
 */
fun main() {
    check(System.getProperty("steamaudio.library.path") == null) {
        "steamaudio.library.path is set, so this would not test resource extraction"
    }
    check(System.getProperty("steamaudio.library.file") == null) {
        "steamaudio.library.file is set, so this would not test resource extraction"
    }

    println("Packaging verification -- loading from classpath resources only")
    println("  platform detected: ${SteamAudioNatives.platform}")

    SteamAudio.load()
    val loadedFrom = SteamAudioNatives.loadedFrom
    println("  extracted and loaded: $loadedFrom")
    checkNotNull(loadedFrom) { "the library reported no load path" }
    check(!loadedFrom.toString().contains("${java.io.File.separator}native${java.io.File.separator}")) {
        "loaded from the source tree ($loadedFrom) rather than from the extracted cache -- " +
            "this run did not exercise resource extraction"
    }

    // Prove it is a working library, not just a file that opened.
    val settings = AudioSettings(samplingRate = 48_000, frameSize = 512)
    SteamAudioContext().use { context ->
        Hrtf.default(context, settings).use { hrtf ->
            BinauralEffect(context, settings, hrtf).use { effect ->
                Arena.ofConfined().use { arena ->
                    val input = AudioBuffer(arena, 1, settings.frameSize)
                    val output = AudioBuffer(arena, 2, settings.frameSize)
                    val params = BinauralEffectParams(arena, hrtf)
                    params.setDirection(1f, 0f, 0f)
                    input[0, 0] = 1f
                    effect.apply(params, input, output)
                    val peak = output.peak()
                    check(peak > 0f) { "rendered silence -- the library loaded but did not work" }
                    println("  rendered an impulse: peak %.4f".format(peak))
                }
            }
        }
    }

    println("  Steam Audio ${SteamAudio.VERSION}, ${SteamAudio.NUM_BANDS} bands")
    println("  OK: the published artifacts work with nothing but a classpath")
}
