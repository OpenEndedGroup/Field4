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

 A Param can be modulated by another signal:

     val freq = 220.p.modulate(sine * 20f)
     SineOsc(freq)

 ---------------------------------------------------------------------------
 Chaining with `/`
 ---------------------------------------------------------------------------

 `/` keeps processing terse:

     saw / Delay()
     saw / FreeVerb()
     white / SVF(mode = SVFMode.BAND_PASS, cutoff = 1200.p, resonance = 0.6.p)

 Longer chains are fine:

     saw / MoogLadder(cutoff = 800.p) / Chorus() / FreeVerb()

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
 Granular and sampling examples
 ---------------------------------------------------------------------------

 Sample playback:

     val sample = AudioSample.fromFile("/tmp/kick.wav")
     Sampler(sample) * 0.5f

 Granular cloud:

     val sample = AudioSample.fromFile("/tmp/voice.wav")
     GrainCloud(sample, grainRate = 18.p, position = 0.35.p, grainDuration = 0.08.p)

 Stereo cloud:

     val sample = AudioSample.fromFile("/tmp/field-recording.wav")
     StereoGrainCloud(sample, grainRate = 12.p, spread = 0.7.p)

 ---------------------------------------------------------------------------
 FM examples
 ---------------------------------------------------------------------------

 A simple operator:

     FMOperator(freq = 220.p, index = 2.5.p, ratio = 2.p)

 Patch-style FM:

     FMPatch.dexedLike(
         carrierFreq = 110.p,
         modIndex = 3.p
     ) * 0.08f

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
     display("scope", sc.waveformSvg())
     audio { sc * 0.1f }

 Stereo graphs can be tapped too:

     val sc = audioInStereo.scope()
     display("stereo", sc.spectrumSvg())
     audio(gain = 0.8f) { sc }

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

fun audioEngine(
    sampleRate: Int = 48_000,
    bufferSize: Int = 512,
    queuedBufferCount: Int = 4,
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

inline fun audio(
    gain: Float = 1f,
    pan: Float = 0f,
    warmUpCycles: Int = 0,
    block: () -> Signal
): AudioPatchSpec = audio(block(), gain = gain, pan = pan, warmUpCycles = warmUpCycles)

inline fun audio(
    gain: Param = Param(1f),
    pan: Param = Param(0f),
    warmUpCycles: Int = 0,
    block: () -> Signal
): AudioPatchSpec = audio(block(), gain = gain, pan = pan, warmUpCycles = warmUpCycles)

fun audio(signal: StereoRenderable, gain: Float = 1f, warmUpCycles: Int = 0): AudioPatchSpec =
    AudioPatchSpec.from(signal, gain = Param(gain), warmUpCycles = warmUpCycles)

fun audio(signal: StereoRenderable, gain: Param = Param(1f), warmUpCycles: Int = 0): AudioPatchSpec =
    AudioPatchSpec.from(signal, gain = gain, warmUpCycles = warmUpCycles)

inline fun audio(
    gain: Float = 1f,
    warmUpCycles: Int = 0,
    block: () -> StereoRenderable
): AudioPatchSpec = audio(block(), gain = gain, warmUpCycles = warmUpCycles)

inline fun audio(
    gain: Param = Param(1f),
    warmUpCycles: Int = 0,
    block: () -> StereoRenderable
): AudioPatchSpec = audio(block(), gain = gain, warmUpCycles = warmUpCycles)

fun audioInput(): Signal = audioEngine().monoInput()

fun audioInputStereo(): StereoSignal = audioEngine().stereoInput()

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

val gate: Gate
    get() = Gate()

val audioIn: Signal
    get() = audioInput()

val audioInStereo: StereoSignal
    get() = audioInputStereo()
