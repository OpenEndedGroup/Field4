package afield.math.distribution

import afield.audio.engine.WavFloatArrayWriter
import afield.audio.synth.ADSR
import afield.audio.synth.Param
import afield.audio.synth.PatPattern
import afield.audio.synth.PatSoundDocs
import afield.audio.synth.PatWave
import afield.audio.synth.PulseOsc
import afield.audio.synth.SineOsc
import afield.audio.synth.gain
import afield.audio.synth.note
import afield.audio.synth.pat
import afield.audio.synth.patRenderStereo
import afield.audio.synth.patVoice
import afield.audio.synth.renderSignalsOffline
import afield.audio.synth.segment
import afield.audio.synth.stack
import afield.audio.synth.texts
import afield.audio.synth.struct
import afield.audio.synth.with
import afield.math.gp.GpKernels
import afield.math.gp.gp1D
import java.io.File
import kotlin.math.ln

/*
FIELD_TEST
id: distribution.sound
runner: jvm-main
entrypoint: afield.math.distribution.DistributionSoundDocs
tags: [smoke, distribution, noiselang, pattern, synth, audio, wav, capture, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "distribution sound docs rendered"
*/

/**
 * The audio clips of `public/docs/distributions.md`, rendered offline for the docs runner's
 * `audio=` fences.
 *
 * Each scene repeats one of the page's boxes step for step, so the clip is the box's own sound: a
 * weighted scale as a pattern, a pitch and a loudness drawn together, a rhythm conditioned to have
 * five hits in sixteen, a melody from a Gaussian-process posterior, and a [DistParam] driving a
 * sine's pitch on the audio thread. Every draw is seeded (patterns by their exact position, the
 * parameter by its seed), so the renders are deterministic and the committed `.wav`s regenerate
 * byte for byte. As the `distribution.sound` FIELD_TEST each scene renders to a temp directory and
 * is checked; pass a directory as `args[0]` to regenerate the page's clips.
 */
object DistributionSoundDocs {
    /** One clip: what it renders, and a check on the render before it is allowed to be written. */
    class Scene(val name: String, val render: () -> PatSoundDocs.Stereo, val verify: (PatSoundDocs.Stereo) -> Unit = {})

    /** The page's weighted scale: six degrees of a minor pentatonic, the root and the fifth most likely. */
    val SCALE: Dist get() = categorical(listOf(0, 3, 5, 7, 10, 12), listOf(4, 1, 2, 3, 1, 2))

    /** Eight draws a cycle from [SCALE], above middle C. */
    fun weightedTune(): PatPattern<String> = (60 + SCALE).pat().segment(8).texts()

    /** A pitch and a loudness drawn together: the high notes loud, the low ones soft. */
    fun jointTune(): PatPattern<String> {
        val pitch = 55 + die(12)
        val loud = (pitch gt 61) * 0.6 + 0.25
        val (p, l) = jointPatterns(pitch, loud)
        return pat("x*8") with note(p.segment(8)) with gain(l.segment(8))
    }

    /** Sixteen steps with exactly five hits, any five equally likely, a new five each cycle; a kick on the one. */
    fun fiveInSixteen(): PatPattern<String> {
        val steps = List(16) { bernoulli(0.4) }
        val rhythm = steps.given(steps.sum() eq 5)
        val hats = struct(rhythm.pat().map { it != 0.0 }, pat("hh"))
        return stack(hats, pat("bd"))
    }

    /** The anchor notes the Gaussian-process melody passes near: (step, MIDI note). */
    val ANCHORS: List<Pair<Double, Double>> = listOf(0.0 to 62.0, 4.0 to 69.0, 8.0 to 65.0, 12.0 to 72.0, 15.0 to 64.0)

    /** Sixteen steps of a GP posterior through [ANCHORS], drawn as one curve per cycle and rounded to semitones. */
    fun gpMelody(): PatPattern<String> {
        val gp = gp1D(
            ANCHORS.map { it.first }.toDoubleArray(), ANCHORS.map { it.second }.toDoubleArray(),
            GpKernels.rbf(lengthScale = 2.0, variance = 16.0), noiseStd = 0.5, mean = { 67.0 },
        )
        val curve = gp.dists(DoubleArray(16) { it.toDouble() }).map { (it + 0.5).floor() }
        return curve.pat().texts()
    }

    /** A pitch drawn from [SCALE] (as Hz, from 220) at every rising edge of an 8 Hz clock that also gates the envelope. */
    fun heldPitch(seconds: Double = 4.0): Pair<FloatArray, DistParam> {
        val clock = PulseOsc(freq = Param(8f), width = Param(0.35f))
        val hz = 220 * (SCALE * (ln(2.0) / 12)).exp()
        val pitch = hz.param(trigger = clock)
        val env = ADSR(gate = clock, attack = Param(0.004f), decay = Param(0.06f), sustain = Param(0.35f), release = Param(0.08f))
        val voice = SineOsc(freq = pitch) * env * 0.5f
        return renderSignalsOffline(seconds, voice, sampleRate = PatSoundDocs.SR)[0] to pitch
    }

    /** The page's performance: 48 beats from 84 up to 132 bpm and back to 108, each about 12 ms early or late. */
    fun performanceBpm(k: Int): Double = when {
        k < 24 -> 84.0 + 48.0 * k / 24
        k < 36 -> 132.0 - 24.0 * (k - 24) / 12
        else -> 108.0
    }

    /**
     * The tempo-following box, step for step: render the performance, find its onsets in the sound,
     * follow the beat with a [Posterior], and play an accompaniment on the predicted beats.
     * Returns the mix and the predictions.
     */
    fun tempoFollow(): Pair<PatSoundDocs.Stereo, List<Double>> {
        val beats = 48
        val late = normal(0, 0.012).samples(beats, seed = 4)
        val beatTimes = DoubleArray(beats)
        var at = 0.5
        for (k in 0 until beats) { beatTimes[k] = at + late[k]; at += 60.0 / performanceBpm(k) }
        val length = beatTimes.last() + 1.0
        val performance = afield.audio.synth.patTimed(beatTimes.mapIndexed { k, t -> afield.audio.synth.TimedNote(t, t + 0.1, if (k % 4 == 0) 45.0 else 52.0) }, beats = length)
        val heard = patRenderStereo(performance, cycles = 1, cps = 1 / length, voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.3))
        val onsets = afield.audio.synth.patDetectOnsetSamples(heard.mono, PatSoundDocs.SR, minGapSeconds = 0.3).map { it.toDouble() / PatSoundDocs.SR }
        val period = uniform(0.3, 1.0)
        val beat = known(onsets[0])
        val post = Posterior(period, beat, particles = 3000)
        val predicted = ArrayList<Double>()
        for (t in onsets.drop(1)) {
            predicted += post.E(beat + period).value
            post.evolve(beat to beat + period + normal(0, 0.004), period to period * normal(0, 0.03).exp())
            post.observe(normal(beat, 0.015), t)
        }
        val accompaniment = afield.audio.synth.patTimed(predicted.map { p -> afield.audio.synth.TimedNote(p, p + 0.05, 76.0, gain = 0.45) }, beats = length)
        val reply = patRenderStereo(accompaniment, cycles = 1, cps = 1 / length, voice = patVoice(wave = PatWave.SINE, decay = 0.03, sustain = 0.2))
        val n = minOf(heard.frames, reply.frames)
        val mix = PatSoundDocs.Stereo(
            FloatArray(n) { heard.left[it] * 0.8f + reply.left[it] * 0.5f },
            FloatArray(n) { heard.right[it] * 0.8f + reply.right[it] * 0.5f },
        )
        return mix to predicted
    }

    private fun pattern(p: PatPattern<String>, cycles: Int, cps: Double, wave: PatWave = PatWave.TRIANGLE, decay: Double = 0.05, sustain: Double = 0.7) =
        patRenderStereo(p, cycles = cycles, cps = cps, voice = patVoice(wave = wave, decay = decay, sustain = sustain))

    val SCENES: List<Scene> = listOf(
        Scene("weighted-scale", { pattern(weightedTune(), 4, 0.5) }) { audio ->
            check(PatSoundDocs.onsetPeaks(audio.mono, threshold = 0.05).size >= 20) { "expected about 32 notes" }
        },
        Scene("joint-pitch-gain", { pattern(jointTune(), 4, 0.5) }),
        Scene("five-in-sixteen", { pattern(fiveInSixteen(), 4, 0.5, decay = 0.03, sustain = 0.3) }) {
            // check the rhythm itself: exactly five hats in every cycle
            val p = fiveInSixteen()
            for (c in 0 until 4) {
                val hats = p.events(afield.audio.synth.PatArc(afield.audio.synth.PatQ.of(c), afield.audio.synth.PatQ.of(c + 1))).count { it.value == "hh" }
                check(hats == 5) { "cycle $c has $hats hats, not 5" }
            }
        },
        Scene("gp-melody", { pattern(gpMelody(), 4, 0.35, decay = 0.04, sustain = 0.8) }),
        Scene("tempo-follow", { tempoFollow().first }) { audio ->
            check(audio.frames > PatSoundDocs.SR * 20) { "the performance is over twenty seconds long" }
            val (_, predicted) = tempoFollow()
            check(predicted.size == 47) { "48 onsets, so 47 predictions; got ${predicted.size}" }
        },
        Scene("held-pitch", {
            val (mono, _) = heldPitch()
            PatSoundDocs.Stereo(mono, mono.copyOf())
        }) { audio ->
            check(audio.left.any { it != 0f }) { "silence" }
        },
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    /** Offline-render scene [name] and write a stereo 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown DistributionSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val audio = scene.render()
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, PatSoundDocs.SR, normalizePeak = 0.9f)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "distribution-sound-docs").path)
        outDir.mkdirs()
        for (scene in SCENES) {
            val audio = scene.render()
            scene.verify(audio)
            val f = File(outDir, "${scene.name}.wav")
            renderWav(scene.name, f.path)
            println("  ${scene.name}: ${audio.frames} frames -> ${f.path}")
        }
        // the heard values of the parameter follow the scale's distribution
        val (_, param) = heldPitch()
        val heard = param.played()
        check(heard.size >= 30) { "the clock should have drawn about 32 pitches, drew ${heard.size}" }
        println("distribution sound docs rendered")
    }
}
