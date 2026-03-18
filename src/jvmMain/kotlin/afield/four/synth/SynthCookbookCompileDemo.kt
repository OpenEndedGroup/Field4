@file:Suppress("unused")

package afield.four.synth

/*
 Runnable cookbook coverage for afield.four.synth.

 The old monolithic compile-only object has been split into explicit snippet
 blocks so the FIELD_TEST catalog can discover and execute them through
 tools/field_backdoor.py.

 These snippets aim at stable, runtime-safe coverage of the cookbook surface.
 They intentionally skip examples that need machine-specific external state,
 such as an actual Dirt sample folder or explicit audio-engine registration.
 */

/*
FIELD_TEST
id: synth.cookbook.oscillators-and-patches
runner: backdoor-box
tags: [synth, cookbook, oscillators, audio]
timeoutSec: 45
expect:
  outputContains:
    - "synth oscillators ok"
  eventKinds:
    - output
*/
private val SYNTH_COOKBOOK_OSCILLATORS_AND_PATCHES = """
val basicOsc = sine
val s1 = SineOsc(220.p)
val s2 = SawOsc(55.p)
val s3 = PulseOsc(freq = 110.p, width = 0.2.p)
val layeredA = saw + saw
val layeredB = sine * 0.1f + square * 0.1f

val directSignal = saw * 0.05f
val monoPatch = audio { saw * 0.05f }
val stereoPatchA = audio { (saw * 0.1f).pan(-0.35f) }
val stereoPatchB = audio { Pan(sine * 0.08f, 0.4.p) }
val launchBundle = audio { saw * 0.05f } +
    pat("bd [hh hh] sd").respond(cps = 0.5) { event ->
        println("bundle " + event.value)
    }
val launchList = listOf(
    audio { tri * 0.04f },
    pat("bd [hh hh] sd").respond(cps = 0.5) { event ->
        println("list " + event.value)
    }
)

val p440 = 440.p
val p015 = 0.15.p
val p8 = 8.p
val cutoff = Param { 400f + 300f * kotlin.math.sin(time * 0.3f) }
val cutoffFiltered = saw / MoogLadder(cutoff = cutoff, resonance = 0.2.p)
val steppedParam = Param(sequenceOf(220f, 330f, 440f, 550f))
val steppedOsc = SineOsc(steppedParam)
val yieldedParam = Param {
    var t = 0
    while (true) {
        t++
        if (t > 10) t = 0
        yield(10f - t + 0.15f * kotlin.math.sin(time * 0.5f))
    }
}
val yieldedSuperSaw = SuperSaw() * yieldedParam
val modFreq = 220.p.modulate(sine * 20f)
val modulatedOsc = SineOsc(modFreq)

println("synth oscillators ok")
""".trimIndent()

/*
FIELD_TEST
id: synth.cookbook.effects-envelopes-and-input
runner: backdoor-box
tags: [synth, cookbook, effects, envelopes, input]
timeoutSec: 45
expect:
  outputContains:
    - "synth effects ok"
  eventKinds:
    - output
*/
private val SYNTH_COOKBOOK_EFFECTS_ENVELOPES_AND_INPUT = """
val delayed = saw / Delay()
val reverbed = saw / FreeVerb()
val bandPassed = white / SVF(mode = SVFMode.BP, cutoff = 1200.p, resonance = 0.6.p)
val chain = saw / MoogLadder(cutoff = 800.p) / Chorus() / FreeVerb()
val tunedDelay = saw / FractionalDelay(delayTime = 0.0125.p, feedback = 0.7.p, mix = 0.6.p)
val signalDivision = saw / SineOsc(2.p)

val percussiveGate = gate.also { it.open() }
val percussive = SawOsc(80.p) * AR(gate = percussiveGate, attack = 0.001.p, release = 0.15.p)

val sustainedGate = gate.also { it.open() }
val sustained = SineOsc(220.p) * ADSR(
    gate = sustainedGate,
    attack = 0.01.p,
    decay = 0.08.p,
    sustain = 0.6.p,
    release = 0.3.p
)

var heldGate = gate
heldGate.open()
val heldVoice = SineOsc(110.p) * ADSR(gate = heldGate, release = 0.5.p)
heldGate.close()

val mix = Mix(
    SawOsc(110.p),
    SawOsc(110.5.p),
    SineOsc(55.p) * 0.3f
) * 0.08f

val stereoManual = StereoSignal(
    left = saw * 0.08f,
    right = tri * 0.08f
)
val panned = Pan(white / FreeVerb(), Param(-0.2f))

val monitor = audioIn * 0.8f
val processedInput = audioIn / Delay(delayTime = 0.2.p, feedback = 0.35.p, mix = 0.4.p)
val stereoInputPatch = audio(gain = 0.9f) { audioInStereo }
val filteredInput = audio(gain = 0.7f) { audioIn / MoogLadder(cutoff = 1800.p) / FreeVerb() }

println("synth effects ok")
""".trimIndent()

/*
FIELD_TEST
id: synth.cookbook.sampling-fm-and-wavetables
runner: backdoor-box
tags: [synth, cookbook, sampling, fm, spectral]
timeoutSec: 45
expect:
  outputContains:
    - "synth sampling ok"
  eventKinds:
    - output
*/
private val SYNTH_COOKBOOK_SAMPLING_FM_AND_WAVETABLES = """
val kick = AudioSample.generate(0.25f) { t ->
    kotlin.math.sin((t * 55f * 2f * kotlin.math.PI.toFloat()).toDouble()).toFloat()
}
val kickPlayback = Sampler(kick) * 0.5f

val voice = AudioSample.generate(1.0f) { t ->
    kotlin.math.sin((t * 220f * 2f * kotlin.math.PI.toFloat()).toDouble()).toFloat()
}
val cloud = GrainCloud(voice, density = 18.p, position = 0.35.p, grainSize = 0.08.p)

val fieldRecording = AudioSample.generate(1.5f) { t ->
    kotlin.math.sin((t * 110f * 2f * kotlin.math.PI.toFloat()).toDouble()).toFloat()
}
val stereoCloud = StereoGrainCloud(fieldRecording, density = 12.p, spread = 0.7.p)

val fmOp = FMOperator(freq = 220.p, feedbackAmount = 0.2f)
val dexedish = FMPatch.twoOp(carrierFreq = 110f, index = 3.p) * 0.08f

val ksGate = gate.also { it.open() }
val burst = white * AR(gate = ksGate, attack = 0.0005.p, release = 0.008.p)
val stringPatch = audio { burst / KarplusStrong(freq = 110.p, decay = 0.992.p, brightness = 0.6.p) }

val hammerGate = gate.also { it.open() }
val hammer = impulse * AR(gate = hammerGate, attack = 0.0001.p, release = 0.002.p) * 0.8f
val hammeredString = audio {
    hammer / KarplusStrong(freq = 220.p, pickPosition = 0.08.p, brightness = 0.75.p)
}

val basicSuperSaw = supersaw
val tunedSuperSaw = SuperSaw(freq = 55.p, detuneCents = 18.p, blend = 0.9.p)
val filteredSuperSaw = audio {
    SuperSaw(freq = 110.p, detuneCents = 24.p, blend = 0.85.p) / MoogLadder(cutoff = 1400.p)
}
val basicSuperSquare = supersquare
val movingSuperSquare = audio {
    SuperSquare(freq = 110.p, voice = 0.42.p, lfo = 0.8.p, resonance = 0.25.p) * 0.08f
}
val pwmVoice = audio {
    SuperPWM(freq = 82.p, voice = 0.18.p, secondarySweep = 0.72.p) * 0.08f
}
val comparatorVoice = audio {
    SuperComparator(freq = 55.p, voice = 0.7.p, resonance = 0.3.p) * 0.06f
}
val chipVoice = audio {
    SuperChip(freq = 110.p, pitch2 = 2.p, pitch3 = 3.01.p, voice = 0.35.p) * 0.08f
}
val noiseVoice = audio {
    SuperNoise(freq = 90.p, voice = 0.4.p, resonance = 0.2.p) * 0.12f
}
val forkVoice = audio {
    SuperFork(freq = 440.p) * 0.08f
}

val rectified = audio { saw / Rectifier(mode = RectifierMode.FULL_WAVE, mix = 0.35.p) * 0.08f }
val clipped = audio {
    sine * 3f / Rectifier(RectifierMode.HALF_WAVE) /
        SVF(mode = SVFMode.LP, cutoff = 900.p) * 0.05f
}
val folded = audio {
    sine * 4f /
        StagedWaveFolder(stages = 4, symmetry = 0.18.p, stageGain = 1.5.p, makeup = 0.4.p) *
        0.08f
}

val wt = wavetable
wt.setFrame(0, fn = { phase: Float ->
    kotlin.math.sin((phase * kotlin.math.PI.toFloat() * 2f).toDouble()).toFloat()
})
wt.setFrame(1, fn = { phase: Float -> if (phase < 0.5f) 1f else -1f })
val wtOsc = audio {
    SpectralWavetableOsc(
        wt,
        freq = 110.p,
        position = Param { 0.5f + 0.45f * kotlin.math.sin(time * 0.18f) }
    ) * 0.08f
}
val wtRecorder = audio { audioIn / SpectralWavetableRecorder(wt, position = 0.2.p, captureRate = 6.p) }
val wtPlayback = audio {
    SpectralWavetableOsc(
        wt,
        freq = 55.p,
        position = Param { 0.2f + 0.6f * kotlin.math.sin(time * 0.09f) }
    ) / FreeVerb() * 0.08f
}
val explicitWt = SpectralWavetableOsc(wt, freq = 220.p, position = 0.5.p)

println("synth sampling ok")
""".trimIndent()

/*
FIELD_TEST
id: synth.cookbook.patterns-and-notes
runner: backdoor-box
tags: [synth, cookbook, patterns, notes, scheduling]
timeoutSec: 45
expect:
  outputContains:
    - "synth patterns ok"
  eventKinds:
    - output
*/
private val SYNTH_COOKBOOK_PATTERNS_AND_NOTES = """
val arp = noteProcess {
    val chord = listOf(48, 55, 60, 64)
    var step = 0
    while (step < 8) {
        note(
            pitch = chord[step % chord.size],
            duration = 0.18,
            velocity = 0.85,
            label = "n" + step
        )
        rest(0.25)
        step++
    }
}

val motif = notes {
    chord(60, 64, 67, durationSeconds = 0.35, velocity = 0.7)
    rest(0.5)
    note(pitch = 72, duration = 0.2, velocity = 1.0, label = "lead")
    rest(0.25)
    note(pitch = 67, duration = 0.45, start = 1.2)
}
val clip = listOf(
    note(60, duration = 0.25, start = 0.0),
    note(64, duration = 0.25, start = 0.25),
    note(67, duration = 0.5, start = 0.5)
)
val clipSvg = clip.pianoRollSvg(nowSeconds = 0.4, futureSeconds = 1.2)

val hats = pat("bd [hh hh] sd")
val postfixOperators = pat("bd(3,8) hh!2 cp*2 lead@3")
val polymeter = pat("{bd hh 808:4}%8")
val layeredPolymeter = pat("{bd sd stab, cp arpy cr arpy}")
val randomChoice = pat("[bd*4|hh*12|cp*2]")
val parameterizedAtom = pat("bd:(1,2,3) hh")
val scheduledAtoms = hats.scheduled(cps = 2.0, startAt = 0.25).take(4).toList()
val postfixSchedule = postfixOperators.scheduled(cps = 1.0).take(8).toList()
val polymeterSchedule = polymeter.scheduled(cps = 1.0).take(12).toList()
val layeredPolymeterSchedule = layeredPolymeter.scheduled(cps = 1.0).take(12).toList()
val randomChoiceSchedule = randomChoice.scheduled(cps = 1.0).take(12).toList()
val parameterizedAtomSchedule = parameterizedAtom.scheduled(cps = 1.0).take(4).toList()

val reversed = pat("a b c d").rev()
val varied = pat("a b c d")
    .every(4) { it.rev() }
    .sometimes { it.fast(2) }
    .degradeBy(0.15)

val layered = stack(
    pat("bd ~ sd"),
    pat("[hh hh hh hh]").degradeBy(0.2)
)
val euclidKick = euclidFill(3, 8, on = pat("kick")).scheduled(cps = 1.5).take(8).toList()
val held = pat("lead@3 tail").scheduled(cps = 1.0).take(3).toList()
val replicated = pat("bd!3 cp").scheduled(cps = 1.0).take(4).toList()
val densified = pat("bd*3 cp").scheduled(cps = 1.0).take(8).toList()

data class Cue(val color: String, val gain: Float)
val cues = pat("calm bright danger") { atom ->
    when (atom) {
        "calm" -> Cue("#0af", 0.15f)
        "bright" -> Cue("#fff", 0.4f)
        else -> Cue("#f80", 0.8f)
    }
}
val cueSchedule = cues.scheduled(cps = 0.5).take(3).toList()
val gaussianField = patFn(
    "g:(0,0.08) g:(1,0.05,0.7) g:(-0.5,0.12,0.4)",
    cps = 2.0,
    range = 0.5
)
val gaussianFieldDirect = pat("g:(0,0.08) g:(1,0.05,0.7)").asFn(
    cps = 2.0,
    range = 0.5
)
val shapedField = pat(
    "tent:(0.4,0.12) box:(1,0.04,0.5) wendland:(-0.6,0.18) spline:(0.25,0.1,0.8)"
).asFn(cps = 1.5, range = 0.6)
val envelopeField = pat(
    "env:(0.7,0.03,0.1) adsr:(0.4,0.01,0.08,0.5,0.2)"
).asFn(cps = 1.0, range = 0.5)
val dynamicField = pat("a b c") { atom ->
    when (atom) {
        "a" -> gaussianAtom(sigma = 0.08) { x -> kotlin.math.sin(local(x) * 12.0) }
        "b" -> gaussianAtom(sigma = 0.12) { x -> bipolarRandom() * (1.0 - normalized(x)) }
        else -> adsrAtom(attack = 0.01, decay = 0.08, sustain = 0.4, release = 0.2) { x ->
            0.5 + 0.5 * noise(x, frequency = 18.0)
        }
    }
}.asFn(cps = 2.0, maxLookAhead = 0.5)
val gaussianFieldSamples = listOf(
    gaussianField(0.0),
    gaussianField(0.25),
    gaussianField(0.5),
    gaussianField(1.0)
)
val shapedFieldSamples = listOf(
    shapedField(0.0),
    shapedField(0.25),
    envelopeField(0.5),
    envelopeField(1.0),
    dynamicField(0.33),
    dynamicField(0.9)
)

val degrees = pat("0 3 5 7")
val bassFromPattern = degrees.asNotes(cps = 2.0) { event ->
    val degree = event.value.toIntOrNull() ?: return@asNotes emptyList()
    listOf(
        note(
            pitch = 36 + degree,
            duration = event.duration * 0.95,
            start = event.start,
            label = event.value
        )
    )
}
println(
    "synth patterns ok " +
        scheduledAtoms.size + " " +
        postfixSchedule.size + " " +
        cueSchedule.size + " " +
        gaussianFieldSamples.size + " " +
        clipSvg.length + " " +
        bassFromPattern.size + " " +
        motif.size
)
""".trimIndent()

/*
FIELD_TEST
id: synth.cookbook.visualizers
runner: backdoor-box
tags: [synth, cookbook, visualizers, html]
timeoutSec: 45
expect:
  outputContains:
    - "synth visuals ok"
  eventKinds:
    - set-output
*/
private val SYNTH_COOKBOOK_VISUALIZERS = """
val patch = audio(gain = 0.1f) { saw / FreeVerb() }
val monoScope = (saw / MoogLadder(cutoff = 900.p)).scope()
val stereoScope = audioInStereo.scope()
val env = ADSR(gate = gate.also { it.open() }, release = 0.4.p)
val comp = Compressor(threshold = (-18).p, ratio = 6.p)

display("status", "<div style=\"padding:18px;background:#101820;color:#f2aa4c;font-family:monospace\">synth visuals ok</div>")
display("audio", patch.summaryHtml())
display("scope", monoScope.summaryHtml())
display("stereo", stereoScope.summaryHtml())
display("env", env.stateSvg())
display("comp", comp.stateSvg())
""".trimIndent()

/*
FIELD_TEST
id: synth.cookbook.terse-api
runner: backdoor-box
tags: [synth, cookbook, terse, api]
timeoutSec: 45
expect:
  outputContains:
    - "terse api ok"
  eventKinds:
    - output
*/
private val SYNTH_COOKBOOK_TERSE_API = """
// ── Oscillator factories ──────────────────────────────
val bass = saw(110)
val lead = sine(440)
val pad  = square(55)
val tri1 = tri(330)
val pwm  = pulse(220, width = 0.3)

// ── Envelope factories — compose with * ───────────────
val pluck     = saw(110) * ar()
val sustained = sine(220) * adsr(a = 0.01, d = 0.1, s = 0.7, r = 0.3)
val hit       = saw(80)  * perc(0.08)

// ── Filter/effect factories — compose with / ──────────
val darkPad    = saw(55) / lpf(800, 0.3) / reverb(0.7, 0.5, 0.3)
val brightLead = saw(440) / hpf(1200) / delay(0.25, feedback = 0.4)
val filtered   = square(110) / bpf(600) / chorus()
val dynCutoff  = saw(110) / lpf(Param { 400f + 200f * kotlin.math.sin(time * 0.3f) })

// ── FM ────────────────────────────────────────────────
val fmBass = fm(110, index = 3)
val fmBell = fm(880, ratio = 1.4, index = 6) / reverb(0.9) * perc(0.3)

// ── Double operators on Signal ────────────────────────
val mixed = saw(110) * 0.1 + sine(220) * 0.05
val doubl = 2 * saw(55)

// ── Patterns ──────────────────────────────────────────
val drums  = pat("bd ~ sd").stack(pat("[hh hh hh hh]"))
val melody = pat("60 64 67 72").notes(cps = 2.0)
val scale  = pat("0 2 4 5 7").degrees(root = 48, cps = 1.5)
val field  = pat("g:(0,0.08) tent:(1,0.05)").fn(cps = 2.0, range = 0.5)
val prev   = pat("bd [hh hh] sd").peek(4)

// ── Full terse patch ──────────────────────────────────
val fullPatch = audio {
    fm(110, index = 3) / lpf(600) / reverb() * ar() * 0.08
}

println("terse api ok " + prev.size + " " + field(0.5))
""".trimIndent()
