package afield.audio.synth

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/*
FIELD_TEST
id: speech.tiny-voca.smoke
runner: jvm-main
entrypoint: afield.audio.synth.SpeechSmokeTest
tags: [smoke, onnx, audio, speech, cpu, network]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "speech smoke tests passed"
*/
/**
 * [speak] end to end on the CPU: phonemizer, tiny-voca, timing, the [Signal] path and a real-time
 * factor measured on this machine.
 *
 * What it pins: the phonemizer's tokens for a dictionary sentence (so a drift in the port shows up as
 * a token diff, not as a slightly odd voice); that the model's durations account for every sample it
 * returned (the property word timing rests on); that the words come out in order and do not overlap;
 * that [Speech.signal] rendered at 48 kHz is the 24 kHz audio, upsampled; and that the word pattern
 * has one onset per word. Pass a directory as the first argument to keep the `.wav`.
 */
object SpeechSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val tokens = SpeechPhonemizer.tokens("Hello! This is a box that speaks.")
        println("tokens: $tokens")
        check(tokens.toString() == "<bos> h ə l ˈoʊ ! ð ɪ s <sp> ɪ z <sp> ɐ <sp> b ˈɑː k s <sp> ð æ t <sp> s p ˈiː k s . <eos>") {
            "phonemizer drifted: $tokens"
        }
        val oov = SpeechPhonemizer.word("fieldwork-ish".replace("-", ""))
        println("unknown word: ${oov.text} -> ${oov.phones} (${oov.source})")

        // Warm the session and the phonemizer so the measured run is inference only.
        speak("Warming up.")
        val text = "The meeting moved to 3:30 on March 14th. Field speaks, listens, and draws what it heard."
        val runs = List(5) { speak(text) }
        val speech = runs.last()
        val rtf = runs.map { it.realTimeFactor }.sorted()[2]
        println("speech: $speech")
        println("real-time factor (median of 5): ${"%.1f".format(rtf)}x, ${"%.2f".format(speech.seconds)} s of audio")
        speech.words.forEach { println("  %-8s %6.3f .. %6.3f  %s %s".format(it.text, it.start, it.end, it.source, if (it.stressed) "*" else "")) }

        check(speech.words.size == 17) { "expected 17 words, got ${speech.words.size}: ${speech.words.map { it.text }}" }
        check(speech.words.zipWithNext().all { (a, b) -> a.end <= b.start + 1e-9 && a.start < a.end }) { "words overlap or are empty" }
        check(speech.words.last().end <= speech.seconds + 1e-6) { "last word ends after the audio" }
        val rms = sqrt(speech.samples.map { it * it.toDouble() }.average())
        check(rms > 0.02) { "audio is near silent: rms $rms" }

        val rendered = renderSignalsOffline(speech.seconds, speech.signal())[0]
        val expect = speech.resampled(48_000)
        val err = (0 until minOf(rendered.size, expect.size)).maxOf { abs(rendered[it] - expect[it]) }
        check(err < 1e-3) { "signal path differs from resampled audio by $err" }

        val onsets = speech.pattern().events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }
        check(onsets.map { it.value } == speech.words.map { it.text }) { "pattern events differ from words" }

        args.firstOrNull()?.let { dir -> File(dir).mkdirs(); File(dir, "speech_smoke.wav").writeBytes(speech.wav()) }
        println("speech smoke tests passed")
    }
}
