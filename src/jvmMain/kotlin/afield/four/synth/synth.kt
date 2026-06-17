/*
 Live-coding entrypoints for afield.four.synth.

 This file is intended to be the terse surface that boxes import. The examples
 below assume the default Field preamble, which now includes:

     import afield.four.synth.*

 Core ideas:

 - Plain numbers can become Params with `.p`
 - Lowercase vals such as `saw`, `sine`, `white`, `gate`, and `audioIn`
   return fresh instances each time they are referenced
 - `/` chains a Signal into a SignalProcessor
 - `audio { ... }` wraps a mono or stereo graph as something launchable
 - `.onBeat(...)` quantizes any launchable return value to the next musical boundary
 - Mono signals cache automatically for the duration of an audio block; built-in
   stereo nodes do the same, and `hold()` is available for arbitrary stereo renderables
 - Returning a Signal, Pan, StereoSignal, StereoGrainCloud, or AudioPatchSpec
   from a launched box inserts it into Field's shared audio engine

 ---------------------------------------------------------------------------
 Basic oscillators
 ---------------------------------------------------------------------------

 A bare oscillator:

     sine

 Set frequency with Params:

     SineOsc(220.p)
     SawOsc(55.p)
     PulseOsc(freq = 110.p, width = 0.2.p)

 Since the lowercase vals are getters, these are different oscillators:

     saw + saw
     sine * 0.1f + square * 0.1f

 ---------------------------------------------------------------------------
 Launchable patches
 ---------------------------------------------------------------------------

 Return a signal directly from a box:

     saw * 0.05f

 Or be explicit:

     audio { saw * 0.05f }

 Stereo output with panning:

     audio { (saw * 0.1f).pan(-0.35f) }
     audio { Pan(sine * 0.08f, 0.4.p) }

 Launch more than one thing from a box either with `+`:

     audio { saw * 0.05f } +
         pat("bd [hh hh] sd").respond(cps = 0.5) { event ->
             __m("pat").patHighlight(event)
         }

 Or by returning a list:

     listOf(
         audio { saw * 0.05f },
         pat("bd [hh hh] sd").respond(cps = 0.5) { event ->
             __m("pat").patHighlight(event)
         }
     )

 ---------------------------------------------------------------------------
 Quantized launch timing
 ---------------------------------------------------------------------------

 Delay any launchable return value until the next clock boundary:

     audio { saw * 0.05f }.onBeat(every = 4, preroll = 0.02)

 This also works for bundles:

     (audio { saw * 0.05f } +
         pat("bd [hh hh] sd").respond(cps = 0.5) { event ->
             __m("pat").patHighlight(event)
         }).onBeat(every = 4, preroll = 0.02)

 In `frames {}` / `loop {}` code you can suspend directly:

     frames {
         waitForBeat(every = 4, preroll = 0.02)
         println("downbeat")
     }

 `every` is measured in the chosen clock's units, so `every = 4` means every
 fourth rollover of that clock. Use a subdivided clock when you need finer
 boundaries, or a slower clock / larger `every` when you want measure-level
 quantization.

 ---------------------------------------------------------------------------
 Params and modulation
 ---------------------------------------------------------------------------

 `.p` is the quickest way to build Params:

     440.p
     0.15.p
     8.p

 Params can also be dynamic:

     val cutoff = Param { 400f + 300f * kotlin.math.sin(time * 0.3f) }
     saw / MoogLadder(cutoff = cutoff, resonance = 0.2.p)

 Or stepped from a sequence, one value per audio block:

     val seq = Param(sequenceOf(220f, 330f, 440f, 550f))
     SineOsc(seq)

 Or as a coroutine-style generator that can `yield(...)` values and see the
 current audio-block `time` directly:

     val seq = Param {
         var t = 0
         while (true) {
             t++
             if (t > 10) t = 0
             yield(10f - t + 0.15f * kotlin.math.sin(time * 0.5f))
         }
     }
     SuperSaw() * seq * 0.05f

 A Param can be modulated by another signal:

     val freq = 220.p.modulate(sine * 20f)
     SineOsc(freq)

 ---------------------------------------------------------------------------
 Chaining with `/`
 ---------------------------------------------------------------------------

 `/` keeps processing terse:

     saw / Delay()
     saw / FreeVerb()
     white / SVF(mode = SVFMode.BP, cutoff = 1200.p, resonance = 0.6.p)

 Longer chains are fine:

     saw / MoogLadder(cutoff = 800.p) / Chorus() / FreeVerb()

 Fractional delay is available when you need tuned delay times or resonant
 feedback structures:

     saw / FractionalDelay(delayTime = 0.0125.p, feedback = 0.7.p, mix = 0.6.p)

 Remember that `signal / signal` is still division. Chaining only happens when
 the right-hand side is a SignalProcessor.

 ---------------------------------------------------------------------------
 Envelopes and gates
 ---------------------------------------------------------------------------

 Quick percussive patch:

     val g = gate.also { it.open() }
     SawOsc(80.p) * AR(gate = g, attack = 0.001.p, release = 0.15.p)

 Sustained voice:

     val g = gate.also { it.open() }
     SineOsc(220.p) * ADSR(
         gate = g,
         attack = 0.01.p,
         decay = 0.08.p,
         sustain = 0.6.p,
         release = 0.3.p
     )

 Gate can be kept in a top-level var and opened/closed from later fragments:

     var g = gate
     g.open()
     SineOsc(110.p) * ADSR(gate = g, release = 0.5.p)

 Later:

     g.close()

 ---------------------------------------------------------------------------
 Mixing and stereo
 ---------------------------------------------------------------------------

 Layered voices:

     Mix(
         SawOsc(110.p),
         SawOsc(110.5.p),
         SineOsc(55.p) * 0.3f
     ) * 0.08f

 Manual left/right signal:

     StereoSignal(
         left = saw * 0.08f,
         right = tri * 0.08f
     )

 Mono source placed in the stereo field:

     Pan(white / FreeVerb(), Param(-0.2f))

 ---------------------------------------------------------------------------
 Audio input
 ---------------------------------------------------------------------------

 Monitor mono input:

     audioIn * 0.8f

 Process input with effects:

     audioIn / Delay(delayTime = 0.2.p, feedback = 0.35.p, mix = 0.4.p)

 Stereo input:

     audio(gain = 0.9f) { audioInStereo }

 Input into a filter and reverb:

     audio(gain = 0.7f) { audioIn / MoogLadder(cutoff = 1800.p) / FreeVerb() }

 ---------------------------------------------------------------------------
 Ableton Link
 ---------------------------------------------------------------------------

    val link = abletonLink()
    println(link.snapshot())

    val syncedWobble = Param { linkPhase.toFloat() }
    audio { saw(110) * (0.05f + syncedWobble * 0.05f) }

    linkAudioOut()
    audio { saw * 0.05f }

    audio(gain = 0.8f) { linkAudioInStereo() }

 ---------------------------------------------------------------------------
 Granular and sampling examples
 ---------------------------------------------------------------------------

 Sample playback:

     val sample = AudioSample.generate(0.25f) { t ->
         kotlin.math.sin((t * 55f * 2f * kotlin.math.PI.toFloat()).toDouble()).toFloat()
     }
     Sampler(sample) * 0.5f

 Granular cloud:

     val sample = AudioSample.generate(1f) { t ->
         kotlin.math.sin((t * 220f * 2f * kotlin.math.PI.toFloat()).toDouble()).toFloat()
     }
     GrainCloud(sample, density = 18.p, position = 0.35.p, grainSize = 0.08.p)

 Stereo cloud:

     val sample = AudioSample.generate(1.5f) { t ->
         kotlin.math.sin((t * 110f * 2f * kotlin.math.PI.toFloat()).toDouble()).toFloat()
     }
     StereoGrainCloud(sample, density = 12.p, spread = 0.7.p)

 Dirt sample library and polyphonic player:

     val lib = dirt("/path/to/Dirt-Samples")
     val kit = dirtPlayer(library = lib, voices = 24)
     audio { kit }
     kit.trigger("bd:3")

 Pattern glue for real Dirt tokens:

     val lib = dirt("/path/to/Dirt-Samples")
     val kit = dirtPlayer(library = lib)
     audio { kit }
     pat("bd:3 [hh*2|hh:3*2] sn:1").playDirt(kit, cps = 2.0)

 ---------------------------------------------------------------------------
 FM examples
 ---------------------------------------------------------------------------

 A simple operator:

     FMOperator(freq = 220.p, feedbackAmount = 0.2f)

 Patch-style FM:

     FMPatch.twoOp(
         carrierFreq = 110f,
         index = 3.p
     ) * 0.08f

 ---------------------------------------------------------------------------
 String and unison synthesis
 ---------------------------------------------------------------------------

 Tuned Karplus-Strong resonator driven by a short noise burst:

     val g = gate.also { it.open() }
     val burst = white * AR(gate = g, attack = 0.0005.p, release = 0.008.p)
     audio { burst / KarplusStrong(freq = 110.p, decay = 0.992.p, brightness = 0.6.p) }

 Hammer-like excitation from an oscillator click:

     val g = gate.also { it.open() }
     val hammer = impulse * AR(gate = g, attack = 0.0001.p, release = 0.002.p) * 0.8f
     audio { hammer / KarplusStrong(freq = 220.p, pickPosition = 0.08.p, brightness = 0.75.p) }

 SuperSaw:

     supersaw
     SuperSaw(freq = 55.p, detuneCents = 18.p, blend = 0.9.p)
     audio { SuperSaw(freq = 110.p, detuneCents = 24.p, blend = 0.85.p) / MoogLadder(cutoff = 1400.p) }

 Tidal-inspired super voices:

     supersquare
     SuperSquare(freq = 110.p, voice = 0.42.p, lfo = 0.8.p)
     audio { SuperPWM(freq = 82.p, voice = 0.18.p, secondarySweep = 0.72.p) * 0.08f }
     audio { SuperComparator(freq = 55.p, voice = 0.7.p, resonance = 0.3.p) * 0.06f }
     audio { SuperChip(freq = 110.p, pitch2 = 2.p, pitch3 = 3.01.p, voice = 0.35.p) * 0.08f }
     audio { SuperNoise(freq = 90.p, voice = 0.4.p, resonance = 0.2.p) * 0.12f }
     audio { SuperFork(freq = 440.p) * 0.08f }

 ---------------------------------------------------------------------------
 Distortion and folding
 ---------------------------------------------------------------------------

 Gentle full-wave rectification:

     audio { saw / Rectifier(mode = RectifierMode.FULL_WAVE, mix = 0.35.p) * 0.08f }

 Half-wave clipping into a filter:

     audio { sine * 3f / Rectifier(RectifierMode.HALF_WAVE) / SVF(mode = SVFMode.LP, cutoff = 900.p) * 0.05f }

 Multi-stage folding with asymmetry:

     audio {
         sine * 4f /
             StagedWaveFolder(stages = 4, symmetry = 0.18.p, stageGain = 1.5.p, makeup = 0.4.p) *
             0.08f
     }

 ---------------------------------------------------------------------------
 Spectral wavetable synthesis
 ---------------------------------------------------------------------------

 Make a wavetable and inspect it:

     val wt = wavetable
     display("wt", wt)
     wt

 Use it as a morphable oscillator:

     val wt = wavetable
     audio {
         SpectralWavetableOsc(
             wt,
             freq = 110.p,
             position = Param { 0.5f + 0.45f * kotlin.math.sin(time * 0.18f) }
         ) * 0.08f
     }

 Rewrite the table from a live source while listening to the result:

     val wt = wavetable
     audio { audioIn / SpectralWavetableRecorder(wt, position = 0.2.p, captureRate = 6.p) }
     audio {
         SpectralWavetableOsc(
             wt,
             freq = 55.p,
             position = Param { 0.2f + 0.6f * kotlin.math.sin(time * 0.09f) }
         ) / FreeVerb() * 0.08f
     }

 You can also write explicit frames:

     val wt = wavetable
     wt.setFrame(0, fn = { phase: Float ->
         kotlin.math.sin((phase * kotlin.math.PI.toFloat() * 2f).toDouble()).toFloat()
     })
     wt.setFrame(1, fn = { phase: Float -> if (phase < 0.5f) 1f else -1f })
     SpectralWavetableOsc(wt, freq = 220.p, position = 0.5.p)

 ---------------------------------------------------------------------------
 Scheduled notes and piano roll
 ---------------------------------------------------------------------------

 Build a memoized note process:

     val arp = noteProcess {
         val chord = listOf(48, 55, 60, 64)
         var step = 0
         while (true) {
             note(
                 pitch = chord[step % chord.size],
                 duration = 0.18,
                 velocity = 0.85,
                 label = "n$step"
             )
             rest(0.25)
             step++
         }
     }

 Move the playhead and inspect the roll in an output area:

     arp.at(3.5)
     display("roll", arp.pianoRollSvg())

 Inline in the editor, in the style of history / histogram:

     __m("arp").pianoRoll(arp.at(4.0))

 Chords and explicit timing are supported inside the process:

     val motif = notes {
         chord(60, 64, 67, durationSeconds = 0.35, velocity = 0.7)
         rest(0.5)
         note(pitch = 72, duration = 0.2, velocity = 1.0, label = "lead")
         rest(0.25)
         note(pitch = 67, duration = 0.45, start = 1.2)
     }

 You can also render plain note collections without a process:

     val clip = listOf(
         note(60, duration = 0.25, start = 0.0),
         note(64, duration = 0.25, start = 0.25),
         note(67, duration = 0.5, start = 0.5)
     )
     display("clip", clip.pianoRollSvg(nowSeconds = 0.4, futureSeconds = 1.2))

 Note processes are resettable and memoized, so the same future schedule can
 drive playback logic and visual inspection without re-running the generator
 for every query.

 ---------------------------------------------------------------------------
 Tidal-style patterns
 ---------------------------------------------------------------------------

 Parse a compact pattern language into timed atom events:

     val hats = pat("bd [hh hh] sd")
     hats.scheduled(cps = 2.0).take(4).toList()

 By default, pattern scheduling uses the ambient `PatClock` in box context if
 one exists, otherwise the shared `musicClock()`. Pass `clock = null` if you
 want a zero-based schedule instead.

 Sync multiple patterns against a shared musical clock:

     val master = musicClock()
     val sixteenth = master.subdivide(4.0)
     pat("bd [hh hh] sd").scheduled(cps = 0.5, clock = sixteenth).take(8).toList()

 The shared `musicClock()` runs at real time, so `cps = 1.0` means one cycle
 per second, which is 60 cycles per minute. If you treat one cycle as a beat,
 that is 60 BPM.

 Clocks can be paused and resumed. Already-dispatched future callbacks are not
 recalled when a clock pauses:

     val master = musicClock()
     master.pause()
     master.start()

 Map atoms into notes so they feed the existing piano-roll / note-process path:

     val degrees = pat("0 3 5 7")
     val bass = degrees.asNotes(cps = 2.0) { event ->
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

 Or respond directly to the atoms as a launchable event source:

     pat("kick <snare rim>").respond(cps = 2.0) { event ->
         box.display("atoms", event.value.toString())
     }

 Or run the callback slightly ahead of the target event time:

     pat("kick <snare rim>").respondAhead(ahead = 0.05, cps = 2.0, clock = musicClock()) { event ->
         box.display("atoms", "${event.value} in ${event.start - now}")
     }

 The responder callback gets the atom plus timing metadata (`start`,
 `duration`, `cycleIndex`) and a `box` reference, so it can be used for
 audio triggers, visuals, automation, or arbitrary live-coded side effects.

 Patterns can also become scalar functions over time. The first shape is a
 palette of finite-support atoms:

     val fn = patFn(
         "g:(0,0.12) tent:(1,0.08,0.8) wendland:(-0.5,0.2,0.4)",
         cps = 2.0,
         range = 0.9
     )

     fn(0.0)
     fn(0.5)

 `g:(value,sigma)` defaults the weight to `1.0`. The current shapes are:
 `g`, `tent`, `box`, `wendland`, `spline`, `env`, and `adsr`.

 Or directly from an already-parsed pattern:

     val field = pat("env:(0.7,0.03,0.12) adsr:(0.4,0.01,0.08,0.5,0.2)")
         .asFn(cps = 1.5, range = 0.4)

 Or map atoms into typed late-bound function specs:

     val animated = pat("a b c") { atom ->
         when (atom) {
             "a" -> gaussianAtom(sigma = 0.08) { x -> kotlin.math.sin(local(x) * 12.0) }
             "b" -> gaussianAtom(sigma = 0.12) { x -> bipolarRandom() * (1.0 - normalized(x)) }
             else -> adsrAtom(attack = 0.01, decay = 0.08, sustain = 0.4, release = 0.2) { x ->
                 0.5 + 0.5 * noise(x, frequency = 18.0)
             }
         }
     }.asFn(cps = 2.0, maxLookAhead = 0.5)

 ---------------------------------------------------------------------------
 Graph queries and ParamSet surgery
 ---------------------------------------------------------------------------

 Find params by path:

     val gains = someSignal matches ("**" + "/gain")
     gains

 Tweak a whole set together:

     (someSignal matches ("**" + "/gain")).set(0.15f)
     (someSignal matches ("**" + "/freq")).scale(0.5f)

 Randomize and shape a set:

     val cutoffs = someSignal matches ("**" + "/cutoff")
     cutoffs.random(200f..4000f)
     cutoffs.rewrite {
         normalize(300f..1800f)
         jitter(60f)
     }

 Add shared or per-parameter modulation:

     (someSignal matches ("**" + "/gain")).modulate(sine * 0.1f, mode = ParamModulationMode.ADD)

     (someSignal matches ("**" + "/freq")).modulate { entry, index ->
         SineOsc((0.08f + index * 0.03f).p) * (entry.base * 0.02f)
     }

 Drive a set from dynamic supplier expressions:

     val freqs = someSignal matches ("**" + "/freq")
     freqs.supply { entry, index, group ->
         group.mean + kotlin.math.sin(time * (0.2f + index * 0.05f)) * 12f
     }

 If you want the generic path wrapper instead of only Params:

     val paths = someSignal.graphPaths("**")
     paths
     paths.matching("**" + "/input").writable()

 ---------------------------------------------------------------------------
 Visualizers and scopes
 ---------------------------------------------------------------------------

 Audio patches retain a recent stereo output history, so once a patch is
 running you can inspect it directly:

     val patch = audio(gain = 0.1f) { saw / FreeVerb() }
     display("audio", patch.summaryHtml())
     patch

 If you want to tap an internal point in the graph, use a Scope:

     val sc = (saw / MoogLadder(cutoff = 900.p)).scope()
     audio { sc * 0.1f }
     sc.liveWaveform("scope")

 Stereo graphs can be tapped too:

     val sc = audioInStereo.scope()
     audio(gain = 0.8f) { sc }
     sc.liveSpectrum("stereo")

 `waveformSvg()` and `spectrumSvg()` are snapshots of the current trace. If you
 call them before the scoped signal has actually been rendered, they will say
 "Waiting for audio". Use `liveWaveform(...)`, `liveSpectrum(...)`, or
 `liveSummary(...)` when you want the output area to keep updating while audio runs.

 Stateful processors expose compact state views:

     val env = ADSR(gate = gate.also { it.open() }, release = 0.4.p)
     display("env", env.stateSvg())

     val comp = Compressor(threshold = (-18).p, ratio = 6.p)
     display("comp", comp.stateSvg())
     audio { audioIn / comp }

 ---------------------------------------------------------------------------
 Engine access
 ---------------------------------------------------------------------------

 If you need explicit engine access:

     val engine = audioEngine()
     val handle = engine.register(audio { saw * 0.05f })
     handle.close()

 Most live coding should just return the graph from the box and let Field's
 launch/stop manage registration automatically.
*/
package afield.four.synth

import afield.stdlib.Functions
import kotlin.OverloadResolutionByLambdaReturnType
import kotlin.experimental.ExperimentalTypeInference

fun audioEngine(
    sampleRate: Int = 48_000,
    bufferSize: Int = 512,
    queuedBufferCount: Int = 12,
    inputChannels: Int = 2
): FieldAudioEngine = FieldAudioEngine.shared(
    sampleRate = sampleRate,
    bufferSize = bufferSize,
    queuedBufferCount = queuedBufferCount,
    inputChannels = inputChannels
)

fun audio(signal: Signal, gain: Float = 1f, pan: Float = 0f, warmUpCycles: Int = 0): AudioPatchSpec =
    AudioPatchSpec.from(signal, gain = Param(gain), pan = Param(pan), warmUpCycles = warmUpCycles)

fun audio(signal: Signal, gain: Param = Param(1f), pan: Param = Param(0f), warmUpCycles: Int = 0): AudioPatchSpec =
    AudioPatchSpec.from(signal, gain = gain, pan = pan, warmUpCycles = warmUpCycles)

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
inline fun audio(
    gain: Float = 1f,
    pan: Float = 0f,
    warmUpCycles: Int = 0,
    block: () -> Signal
): AudioPatchSpec = audio(block(), gain = gain, pan = pan, warmUpCycles = warmUpCycles)

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
inline fun audio(
    gain: Param,
    pan: Param = Param(0f),
    warmUpCycles: Int = 0,
    block: () -> Signal
): AudioPatchSpec = audio(block(), gain = gain, pan = pan, warmUpCycles = warmUpCycles)

fun audio(signal: StereoRenderable, gain: Float = 1f, warmUpCycles: Int = 0): AudioPatchSpec =
    AudioPatchSpec.from(signal, gain = Param(gain), warmUpCycles = warmUpCycles)

fun audio(signal: StereoRenderable, gain: Param = Param(1f), warmUpCycles: Int = 0): AudioPatchSpec =
    AudioPatchSpec.from(signal, gain = gain, warmUpCycles = warmUpCycles)

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
inline fun audio(
    gain: Float = 1f,
    warmUpCycles: Int = 0,
    block: () -> StereoRenderable
): AudioPatchSpec = audio(block(), gain = gain, warmUpCycles = warmUpCycles)

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
inline fun audio(
    gain: Param,
    warmUpCycles: Int = 0,
    block: () -> StereoRenderable
): AudioPatchSpec = audio(block(), gain = gain, warmUpCycles = warmUpCycles)

fun audioInput(): Signal = audioEngine().monoInput()

fun audioInputStereo(): StereoSignal = audioEngine().stereoInput()

fun abletonLink(
    tempo: Double = 120.0,
    peerName: String = "Field4",
    quantum: Double = 4.0
): LinkAudioInterface = LinkAudioInterface.shared(
    tempo = tempo,
    peerName = peerName,
    quantum = quantum
)

fun linkTiming(
    tempo: Double = 120.0,
    peerName: String = "Field4",
    quantum: Double = 4.0
): LinkTimingSnapshot = abletonLink(
    tempo = tempo,
    peerName = peerName,
    quantum = quantum
).snapshot(quantum)

fun linkAudioOut(
    name: String = "Field4",
    tempo: Double = 120.0,
    peerName: String = "Field4",
    quantum: Double = 4.0,
    monitorLocal: Boolean = true,
    engine: FieldAudioEngine = audioEngine()
): LinkAudioInterface {
    val link = abletonLink(tempo = tempo, peerName = peerName, quantum = quantum)
    engine.enableLinkOutput(link, name = name, monitorLocal = monitorLocal)
    return link
}

fun linkAudioIn(channelName: String? = null): Signal = abletonLink().monoInput(channelName)

fun linkAudioInStereo(channelName: String? = null): StereoSignal = abletonLink().stereoInput(channelName)

fun linkAudioInput(channelName: String? = null): Signal = linkAudioIn(channelName)

fun linkAudioInputStereo(channelName: String? = null): StereoSignal = linkAudioInStereo(channelName)

fun Signal.pan(position: Float): Pan = Pan(this, Param(position))

fun Signal.pan(position: Param = Param(0f)): Pan = Pan(this, position)

val Number.p: Param
    get() = Param(toFloat())

val sine: SineOsc
    get() = SineOsc()

val saw: SawOsc
    get() = SawOsc()

val square: SquareOsc
    get() = SquareOsc()

val pulse: PulseOsc
    get() = PulseOsc()

val tri: TriOsc
    get() = TriOsc()

val phasor: Phasor
    get() = Phasor()

val impulse: Impulse
    get() = Impulse()

val white: WhiteNoise
    get() = WhiteNoise()

val pink: PinkNoise
    get() = PinkNoise()

val brown: BrownNoise
    get() = BrownNoise()

val supersaw: SuperSaw
    get() = SuperSaw()

val supersquare: SuperSquare
    get() = SuperSquare()

val superpwm: SuperPWM
    get() = SuperPWM()

val supercomparator: SuperComparator
    get() = SuperComparator()

val superchip: SuperChip
    get() = SuperChip()

val supernoise: SuperNoise
    get() = SuperNoise()

val superfork: SuperFork
    get() = SuperFork()

val wavetable: SpectralWavetable
    get() = SpectralWavetable()

val rectifier: Rectifier
    get() = Rectifier()

val fold: StagedWaveFolder
    get() = StagedWaveFolder()

val gate: Gate
    get() = Gate()

val audioIn: Signal
    get() = audioInput()

val audioInStereo: StereoSignal
    get() = audioInputStereo()

// ─── Frequency helpers ──────────────────────────────────────────

/** Self-documenting frequency param: `440.hz` */
val Number.hz: Param
    get() = Param(toFloat())

// ─── Gate shortcuts ─────────────────────────────────────────────

/** A gate that is immediately open */
fun openGate(): Gate = Gate().also { it.open() }

// ─── Oscillator factories ───────────────────────────────────────

fun sine(freq: Number): SineOsc = SineOsc(Param(freq.toFloat()))
fun saw(freq: Number): SawOsc = SawOsc(Param(freq.toFloat()))
fun square(freq: Number): SquareOsc = SquareOsc(Param(freq.toFloat()))
fun tri(freq: Number): TriOsc = TriOsc(Param(freq.toFloat()))
fun pulse(freq: Number, width: Number = 0.5): PulseOsc =
    PulseOsc(Param(freq.toFloat()), Param(width.toFloat()))

// ─── FM factory ─────────────────────────────────────────────────

/** `fm(110, index = 3)` — twoOp FM patch */
fun fm(freq: Number, ratio: Number = 2, index: Number = 2, feedback: Number = 0): Signal =
    FMPatch.twoOp(
        carrierFreq = freq.toFloat(),
        ratio = ratio.toFloat(),
        index = Param(index.toFloat()),
        feedback = feedback.toFloat()
    )

// ─── Envelope factories (compose with *) ────────────────────────
//   saw(110) * ar()           — quick pluck
//   sine(440) * adsr()        — sustained voice

/** AR envelope with auto-opened gate */
fun ar(attack: Number = 0.001, release: Number = 0.15, gate: Gate = openGate()): Signal =
    AR(gate = gate, attack = Param(attack.toFloat()), release = Param(release.toFloat()))

/** ADSR envelope with auto-opened gate */
fun adsr(a: Number = 0.01, d: Number = 0.1, s: Number = 0.7, r: Number = 0.3,
         gate: Gate = openGate()): Signal =
    ADSR(gate = gate, attack = Param(a.toFloat()), decay = Param(d.toFloat()),
        sustain = Param(s.toFloat()), release = Param(r.toFloat()))

/** Percussive hit — short attack, variable release */
fun perc(release: Number = 0.15, attack: Number = 0.001, gate: Gate = openGate()): Signal =
    ar(attack, release, gate)

// ─── Filter factories (compose with /) ──────────────────────────
//   saw(110) / lpf(800)       — lowpass
//   white / hpf(400, 0.6)     — highpass

fun lpf(cutoff: Number = 1000, resonance: Number = 0.2): MoogLadder =
    MoogLadder(cutoff = Param(cutoff.toFloat()), resonance = Param(resonance.toFloat()))
fun lpf(cutoff: Param, resonance: Param = Param(0.2f)): MoogLadder =
    MoogLadder(cutoff = cutoff, resonance = resonance)

fun hpf(cutoff: Number = 1000, resonance: Number = 0.5): SVF =
    SVF(mode = SVFMode.HP, cutoff = Param(cutoff.toFloat()), resonance = Param(resonance.toFloat()))

fun bpf(cutoff: Number = 1000, resonance: Number = 0.5): SVF =
    SVF(mode = SVFMode.BP, cutoff = Param(cutoff.toFloat()), resonance = Param(resonance.toFloat()))

// ─── Effect factories (compose with /) ──────────────────────────
//   saw(110) / lpf(800) / reverb()   — chain naturally

fun reverb(roomSize: Number = 0.8, damping: Number = 0.5, mix: Number = 0.33): FreeVerb =
    FreeVerb(roomSize = Param(roomSize.toFloat()), damping = Param(damping.toFloat()), mix = Param(mix.toFloat()))

fun springReverb(tension: Number = 0.55, decay: Number = 0.72, tone: Number = 0.65, mix: Number = 0.28): SpringReverb =
    SpringReverb(
        tension = Param(tension.toFloat()),
        decay = Param(decay.toFloat()),
        tone = Param(tone.toFloat()),
        mix = Param(mix.toFloat())
    )

fun spring(tension: Number = 0.55, decay: Number = 0.72, tone: Number = 0.65, mix: Number = 0.28): SpringReverb =
    springReverb(tension, decay, tone, mix)

fun convolutionReverb(
    impulse: AudioSample,
    mix: Number = 0.35,
    gain: Number = 1.0,
    normalize: Boolean = true,
    maxIrSamples: Int = 8192
): ConvolutionReverb =
    ConvolutionReverb(
        impulse = impulse,
        mix = Param(mix.toFloat()),
        gain = Param(gain.toFloat()),
        normalize = normalize,
        maxIrSamples = maxIrSamples
    )

fun reverbImpulse(
    lengthSeconds: Number = 0.45,
    decay: Number = 0.65,
    diffusion: Number = 0.8,
    sampleRate: Number = 44100,
    seed: Int = 0
): AudioSample =
    ConvolutionReverb.syntheticImpulse(
        lengthSeconds = lengthSeconds.toFloat(),
        decay = decay.toFloat(),
        diffusion = diffusion.toFloat(),
        sampleRate = sampleRate.toFloat(),
        seed = seed
    )

fun delay(time: Number = 0.3, feedback: Number = 0.5, mix: Number = 0.5): Delay =
    Delay(delayTime = Param(time.toFloat()), feedback = Param(feedback.toFloat()), mix = Param(mix.toFloat()))

fun chorus(rate: Number = 1.5, depth: Number = 0.003, mix: Number = 0.5): Chorus =
    Chorus(rate = Param(rate.toFloat()), depth = Param(depth.toFloat()), mix = Param(mix.toFloat()))

// ─── Pattern helpers ────────────────────────────────────────────

/** Layer two patterns: `pat("bd").stack(pat("hh"))` */
fun <A> PatPattern<A>.stack(other: PatPattern<A>): PatPattern<A> =
    afield.four.synth.stack(this, other)

/** Terse `.asFn()` alias: `pat("g:(0,0.08)").fn(cps = 2.0)` */
fun PatPattern<String>.fn(
    cps: Number = 1.0,
    range: Number = 1.0,
    outside: Number = 0.0
): Functions.Fn = asFn(cps = cps, range = range, outside = outside)
