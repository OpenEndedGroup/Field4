@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A physically modelled grand piano, ported from Peter Cooper's
 * [coopiano](https://github.com/peterc/coopiano) (MIT), with its controls opened up as signal inputs.
 *
 * Each key is one, two or three stiff strings. A felt hammer is thrown at them and the contact is
 * integrated, 32 steps per output sample, against every mode of every string, so a hard fast strike
 * leaves more energy in the upper partials than a soft slow one, for the reason a real hammer does.
 * When the hammer leaves, each mode of each string becomes two decaying phasors, a fast vertical one
 * and a slow horizontal one, and that two-stage decay is the piano's "prompt sound" and
 * "aftersound". Pairs of neighbouring modes add the string's longitudinal "phantom" partials at their
 * sum frequencies. The summed bridge force drives 88 keys' worth of sympathetic string resonators,
 * then a synthesised stereo soundboard response, then a small room.
 *
 * `Piano` is a [StereoNode], so `audio(Piano(), gain = 1f)` plays it, and it is played the way a piano is:
 *
 * ```kotlin
 * val piano = Piano()
 * piano.noteOn(60, 0.6)          // middle C, mezzo-forte
 * piano.noteOff(60)              // the damper comes down
 * piano.play(pat("c4 e4 g4 c5"), cycles = 2, cps = 0.5)
 * piano.play(readMidiClip(file))  // notes and the sustain pedal (CC 64)
 * audio(piano, gain = 1f)
 * ```
 *
 * # Inputs that move while it rings
 *
 * Every control is a [Param], so it takes a number, a slider or a whole signal graph:
 *
 * | input | what it moves | rate |
 * | --- | --- | --- |
 * | [pedal] | sustain pedal, 0 up to 1 down; half-pedal works | every sample, for every damper and resonator |
 * | [inharmonicity] | string stiffness, as a multiple of the voicing's | every sample, for every ringing mode |
 * | [decay] | how fast every string mode loses energy, as a multiple | every sample |
 * | [coupling] | how strongly the 88 sympathetic strings answer | every sample |
 * | [soundboard] | drive into the soundboard, against the dry bridge force | every sample |
 * | [room] | room reverb level | every sample |
 * | [hardness] | felt stiffness, as a multiple | read at the sample of each strike |
 * | [bridgeCoupling] | string–soundboard admittance (mode pulls and decays) | read at each strike |
 * | [unaCorda] | soft pedal: the hammer misses one string and meets softer felt | read at each strike |
 * | [sostenuto] | middle pedal: holds the dampers of the keys down when it is pressed | every 64 samples |
 * | [bridgeInput] | any signal, pushed into the bridge, for the board and the open strings | every sample |
 * | [dry], [micPosition], [mics] | close microphones over the strings, against the board | every sample; positions every 32 |
 *
 * [preparations] put bolts, rubber and loose screws on the strings ([PianoPreparation]), each one
 * a set of Params too, so a bolt can slide while the note rings.
 *
 * The hammer only touches the string for one to three milliseconds, so [hardness] is read once, at
 * the strike's own sample, from whatever is driving it: an oscillator on `hardness` gives each note a
 * different hammer. An audio-rate signal on [inharmonicity] or [decay] is applied per sample: each
 * phasor's step is recomputed every 32 samples and corrected to second order in between, so an
 * oscillator at a few hundred hertz on `inharmonicity` puts sidebands on every partial.
 *
 * All synthesis state, the strings, dampers, resonators, board and room, belongs to the audio thread.
 * [noteOn], [noteOff] and [play] only enqueue frame-stamped events. With [prepareAhead] the hammer
 * contact of each strike, the expensive part, is computed on worker threads up to a second before
 * its sample; the output is the same as computing it at the sample. [pianoRenderMidi] renders a
 * MIDI file to a WAV.
 *
 * # Against coopiano
 *
 * A4 at velocity 0.5, eight seconds, no room, rendered here and by coopiano's own C++ with its
 * single shared soundboard: the first eight partials land within 0.001 Hz of each other, their
 * levels and their early and late decay rates agree to 0.01 dB, and the waveforms differ by -75 dB
 * over the first half second. The random draws are coopiano's own (mt19937_64 and libc++'s
 * distributions, reproduced in Kotlin). Method and numbers are in `dev_docs/PIANO_PORT.md`; `PianoTest`
 * pins the hammer contact against the C++. The port differs in two ways: the sample rate is a
 * parameter, and the soundboard is coopiano's single shared stereo response rather than its four
 * bridge regions. Everything this class adds to coopiano (the moving controls, the close
 * microphones, preparations, the soft and sostenuto pedals, the bridge input) leaves that match
 * alone when it is at its default.
 */
class Piano(
    val voicing: PianoVoicing = PianoVoicing(),
    val pedal: Param = Param(0f),
    val inharmonicity: Param = Param(1f),
    val decay: Param = Param(1f),
    val coupling: Param = Param(1f),
    val soundboard: Param = Param(1f),
    val room: Param = Param(0.3f),
    val hardness: Param = Param(1f),
    val bridgeCoupling: Param = Param(voicing.bridgeCoupling.toFloat()),
    val gain: Param = Param(1f),
    /** 0 lets stiffness detune the note; 1 holds the fundamental where it is at inharmonicity 1. */
    val pinFundamental: Param = Param(0f),
    /** Close-mic position along the speaking length, 0 at the agraffe, 1 at the bridge (any value). */
    val micPosition: Param = Param(0.12f),
    /** 0 is the soundboard; 1 is the close microphones on the strings alone. */
    val dry: Param = Param(0f),
    /** Hammer thump, from the strike's own contact force. */
    val thump: Param = Param(0f),
    /** Damper felt landing on a ringing string (1 is the prepared level). */
    val damperNoise: Param = Param(1f),
    /** Key bottoming on the keybed and returning to the rail. */
    val keyNoise: Param = Param(0f),
    /**
     * Soft pedal, 0 up to 1 down, read at each strike: the action shifts so the hammer misses one
     * string of a three-string note (half of one of a two-string note) and meets it with softer felt.
     */
    val unaCorda: Param = Param(0f),
    /**
     * Sostenuto pedal: pressed (0.5 or more), it holds up the dampers of the keys that are down at
     * that moment, and only those, until it is let go. Read every 64 samples.
     */
    val sostenuto: Param = Param(0f),
    /**
     * Any signal, pushed into the bridge the way a transducer on the soundboard would push it: the
     * soundboard sounds it, and every string whose damper is up answers it. 1 is about the force of
     * a forte bass note.
     */
    val bridgeInput: Param = Param(0f),
    /** Bolts, rubber and loose screws on the strings; see [PianoPreparation]. */
    val preparations: List<PianoPreparation> = emptyList(),
    /**
     * Close microphones over the strings, each with its own position, pan and gain. Empty means one
     * centred microphone at [micPosition]. Heard through [dry].
     */
    val mics: List<PianoMic> = emptyList(),
    /** Sample rate the soundboard and resonators are built for; rebuilt if the engine's differs. */
    val sampleRate: Int = 48_000,
    val maxVoices: Int = 48,
    /**
     * Prepare each strike on a worker thread ahead of its sample (up to a second ahead), so bass
     * chords cost the audio thread nothing. The result is the same either way; false prepares every
     * strike on the audio thread at its sample.
     */
    val prepareAhead: Boolean = true,
) : StereoNode() {

    private class Event(
        val frame: Long, val kind: Int, val midi: Int, val value: Double, val order: Long, val strikeIndex: Long,
        /** Played "now" (no frame given), so it may wait a few milliseconds for its strike; [born] is when it was asked for. */
        val immediate: Boolean = false, val born: Long = frame,
    ) {
        var job: StrikeJob? = null
        var wasDeferred = false
        fun later(at: Long) = Event(at, kind, midi, value, order, strikeIndex, immediate, born).also { it.job = job; it.wasDeferred = wasDeferred }
    }

    /** One strike prepared on a worker, for the hammer, bridge and soft-pedal values it was asked for. */
    private inner class StrikeJob(
        val midi: Int, val velocity: Double, val seed: Long, val hard: Double, val bridge: Double, val una: Double, val fs: Double,
    ) : Runnable {
        val state = java.util.concurrent.atomic.AtomicInteger(0) // 0 queued, 1 running, 2 done, 3 abandoned
        @Volatile var data: PianoNoteData? = null
        private val latch = java.util.concurrent.CountDownLatch(1)

        override fun run() {
            if (!state.compareAndSet(0, 1)) return
            try { data = prepareStrike(midi, velocity, seed, hard, bridge, una, fs) } catch (_: Throwable) {
            } finally { state.set(2); latch.countDown() }
        }

        /** The prepared data; null if it had not started (and now never will). Waits if it is running. */
        fun take(): PianoNoteData? {
            if (state.compareAndSet(0, 3)) return null
            latch.await()
            return data
        }

        fun abandon() { state.compareAndSet(0, 3) }
    }

    private val incoming = ConcurrentLinkedQueue<Event>()
    private val pending = PriorityQueue<Event>(64, compareBy<Event>({ it.frame }, { it.order }))
    /** Strikes too far ahead to prepare yet, by frame. */
    private val far = PriorityQueue<Event>(64, compareBy<Event>({ it.frame }, { it.order }))
    private val orderCounter = java.util.concurrent.atomic.AtomicLong()
    private val strikeCounter = java.util.concurrent.atomic.AtomicLong()

    /** Frames rendered so far; the clock [noteOn]'s `atFrame` is on. */
    @Volatile var frame: Long = 0L
        private set

    @Volatile private var fs = 0.0
    private var resonance: PianoResonance? = null
    private var board: PianoBoardConvolver? = null
    private var roomFx: PianoRoom? = null
    private val voices = ArrayList<PianoVoice>()
    private val keyStrings = java.util.concurrent.ConcurrentHashMap<Long, List<PianoString>>()
    private var ccPedal = 0.0
    private var ccSoft = 0.0
    private var ccSost = 0.0
    private var sostDown = false
    private val sostLatched = BooleanArray(88)
    private var dcIn = doubleArrayOf(0.0, 0.0); private var dcOut = doubleArrayOf(0.0, 0.0)

    // The strike-time inputs as the audio thread last read them, for strikes prepared ahead.
    @Volatile private var lastHard = strikeHardness(hardness.base)
    @Volatile private var lastBridge = strikeBridge(bridgeCoupling.base, voicing.bridgeCoupling)
    @Volatile private var lastUna = strikeUna(unaCorda.base)

    private val micList: List<PianoMic> = mics.ifEmpty { listOf(PianoMic(position = micPosition)) }
    private val micStrings = IntArray(micList.size) { micList[it].string }
    private var bufs = Array(19) { FloatArray(0) }
    private var micBufs = Array(micList.size) { Array(3) { FloatArray(0) } }
    private var prepBufs = Array(preparations.size) { Array(PianoPreparation.INPUTS) { FloatArray(0) } }
    private var micOut = Array(micList.size) { DoubleArray(0) }
    private var force = DoubleArray(0)
    private var ownByKey = Array(88) { DoubleArray(0) }
    private val keyActive = BooleanArray(88)
    private val lr = DoubleArray(2)
    private val inputs = PianoBlockInputs()

    init {
        build(sampleRate.toDouble())
    }

    /** Strike [midi] (21..108) at [velocity] 0..1, at [atFrame] on [frame]'s clock or as soon as possible. */
    fun noteOn(midi: Int, velocity: Double = 0.6, atFrame: Long = -1L) = enqueue(atFrame, 0, midi, velocity)

    /** Release [midi]: its dampers come down unless the pedal holds them. */
    fun noteOff(midi: Int, atFrame: Long = -1L) = enqueue(atFrame, 1, midi, 0.0)

    /** Sustain pedal from a controller (MIDI CC 64), combined with [pedal] by taking the larger. */
    fun pedalEvent(value: Double, atFrame: Long = -1L) = enqueue(atFrame, 2, 0, value)

    /** Sostenuto pedal from a controller (MIDI CC 66), combined with [sostenuto] by taking the larger. */
    fun sostenutoEvent(value: Double, atFrame: Long = -1L) = enqueue(atFrame, 3, 0, value)

    /** Soft pedal from a controller (MIDI CC 67), combined with [unaCorda] by taking the larger. */
    fun unaCordaEvent(value: Double, atFrame: Long = -1L) = enqueue(atFrame, 4, 0, value)

    private fun enqueue(atFrame: Long, kind: Int, midi: Int, value: Double) {
        val e = Event(if (atFrame < 0) frame else atFrame, kind, midi, value, orderCounter.getAndIncrement(), if (kind == 0) strikeCounter.getAndIncrement() else -1L, atFrame < 0)
        if (kind == 0 && e.frame - frame <= aheadFrames()) submit(e)
        incoming.add(e)
    }

    private fun aheadFrames(): Long = (if (fs > 0) fs else sampleRate.toDouble()).toLong()

    private fun seedFor(midi: Int, strikeIndex: Long) = -0x61c8864680b583ebL xor (strikeIndex * 0x100000001b3L) xor ((midi * 64L) shl 32)

    private fun submit(e: Event) {
        if (!prepareAhead || e.job != null) return
        val midi = e.midi.coerceIn(21, 108)
        val job = StrikeJob(midi, e.value, seedFor(midi, e.strikeIndex), lastHard, lastBridge, lastUna, if (fs > 0) fs else sampleRate.toDouble())
        e.job = job
        PianoStrikePool.executor.execute(job)
    }

    private fun prepareStrike(midi: Int, velocity: Double, seed: Long, hard: Double, bridge: Double, una: Double, rate: Double): PianoNoteData {
        val key = PianoKey(midi.toDouble(), voicing, hard * (1.0 - 0.3 * una), bridge)
        return PianoStrike.prepare(key, stringsFor(midi, bridge), velocity, seed, rate, unaCordaHit(key.nStrings, una))
    }

    /**
     * Schedules [cycles] cycles of [pattern] from [startFrame] (default: 50 ms from now).
     *
     * An atom is a MIDI number (`60`) or a note name (`c4`, `eb3`, `f#5`, middle C is `c4`); a `note`
     * annotation wins over both. The `gain` annotation scales [velocity], and a note holds its key
     * for its `sustain` share of the event, or [legato] without one: 1 holds to the next event, 0.1 is
     * staccato.
     */
    fun play(pattern: PatPattern<String>, cycles: Int = 1, cps: Double = 0.5, velocity: Double = 0.6, legato: Double = 0.9, startFrame: Long = -1L) {
        val sr = if (fs > 0) fs else sampleRate.toDouble()
        val origin = if (startFrame >= 0) startFrame else frame + (sr / 20).toLong()
        for (c in 0 until cycles) {
            for (e in pattern.events(PatArc(PatQ.of(c), PatQ.of(c + 1)))) {
                if (!e.hasOnset) continue
                val midi = e.annotations["note"]?.toDoubleOrNull()?.roundToInt() ?: pianoNoteNumber(e.value) ?: continue
                if (midi !in 21..108) continue
                val startSec = e.extent.start.toDouble() / cps
                val lenSec = (e.extent.end - e.extent.start).toDouble() / cps
                val sustain = (e.annotations["sustain"]?.toDoubleOrNull() ?: legato).coerceIn(0.0, 1.0)
                val vel = (velocity * (e.annotations["gain"]?.toDoubleOrNull() ?: 1.0)).coerceIn(0.02, 1.0)
                val on = origin + Math.round(startSec * sr)
                noteOn(midi, vel, on)
                noteOff(midi, on + max(1L, Math.round(lenSec * sustain * sr)))
            }
        }
    }

    /** Schedules every note of [clip], and its sustain (CC 64), sostenuto (CC 66) and soft (CC 67) pedals, from [startFrame]. */
    fun play(clip: MidiClip, startFrame: Long = -1L, velocityScale: Double = 1.0) {
        val sr = if (fs > 0) fs else sampleRate.toDouble()
        val origin = if (startFrame >= 0) startFrame else frame + (sr / 20).toLong()
        val seq = clip.toSequence()
        for (n in seq.readTimedNotes().sortedBy { it.startSeconds }) {
            if (n.pitch !in 21..108) continue
            noteOn(n.pitch, (n.velocity / 127.0 * velocityScale).coerceIn(0.02, 1.0), origin + (n.startSeconds * sr).toLong())
            noteOff(n.pitch, origin + (n.endSeconds * sr).toLong())
        }
        val tempos = seq.tempoEvents()
        for (cc in seq.readControlChanges()) {
            val ticks = (cc.beats * seq.resolution).toLong()
            val at = origin + (tickToSeconds(ticks, tempos, seq.resolution) * sr).toLong()
            when (cc.controller) {
                64 -> pedalEvent(cc.value / 127.0, at)
                66 -> sostenutoEvent(cc.value / 127.0, at)
                67 -> unaCordaEvent(cc.value / 127.0, at)
            }
        }
    }

    /** Silences every voice and resonator. Call from the thread that renders, or before playing. */
    fun reset() {
        voices.clear(); pending.clear(); far.clear(); incoming.clear(); ccPedal = 0.0; ccSoft = 0.0; ccSost = 0.0
        sostDown = false; sostLatched.fill(false); deferred.clear(); deferredKeys.fill(0)
        build(if (fs > 0) fs else sampleRate.toDouble())
    }

    /** How many voices are sounding, and their phasor count, for inspection. */
    val activeVoices: Int get() = voices.size
    val activePhasors: Int get() = voices.sumOf { it.data.count }

    /**
     * Rendering offline (set by [pianoRender]): every strike lands on its own sample, waiting for its
     * worker if it must. Live, a note played "now" whose strike is still being prepared starts up to
     * [MAX_DEFER_SECONDS] later instead of stalling the audio thread.
     */
    internal var offline = false
    private val deferred = ArrayList<Event>()
    /** Per key, strikes waiting for their worker; that key's releases wait behind them. */
    private val deferredKeys = IntArray(88)

    private fun shouldDefer(e: Event, now: Long): Boolean {
        if (e.kind > 1) return false
        val k = e.midi.coerceIn(21, 108) - 21
        if (e.kind == 1) return deferredKeys[k] > 0
        val job = e.job
        if (offline || !e.immediate || job == null || job.state.get() >= 2 || now - e.born > MAX_DEFER_SECONDS * fs) return false
        if (!e.wasDeferred) { e.wasDeferred = true; deferredKeys[k]++ }
        return true
    }

    /** Strikes that had to be prepared on the audio thread because no worker had them ready. */
    var strikesPreparedInline: Long = 0L
        private set

    private fun build(rate: Double) {
        fs = rate
        resonance = PianoResonance(voicing, rate)
        val (l, r) = PianoSoundboardIr.get(rate, voicing)
        board = PianoBoardConvolver(l, r)
        roomFx = PianoRoom(rate, voicing)
    }

    private fun stringsFor(midi: Int, bridge: Double): List<PianoString> {
        val key = midi.toLong() * 1_000_003L + (bridge * 1000).roundToInt()
        return keyStrings.computeIfAbsent(key) { PianoString.forKey(PianoKey(midi.toDouble(), voicing, 1.0, bridge)) }
    }

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (ctx.sampleRate.toDouble() != fs) build(ctx.sampleRate.toDouble())
        if (bufs[0].size < numFrames) {
            bufs = Array(bufs.size) { FloatArray(numFrames) }; force = DoubleArray(numFrames); ownByKey = Array(88) { DoubleArray(numFrames) }
            micBufs = Array(micList.size) { Array(3) { FloatArray(numFrames) } }; micOut = Array(micList.size) { DoubleArray(numFrames) }
            prepBufs = Array(preparations.size) { Array(PianoPreparation.INPUTS) { FloatArray(numFrames) } }
        }
        val bPedal = bufs[0]; val bInh = bufs[1]; val bDecay = bufs[2]; val bCoup = bufs[3]; val bBoard = bufs[4]
        val bRoom = bufs[5]; val bHard = bufs[6]; val bBridge = bufs[7]; val bGain = bufs[8]
        pedal.render(bPedal, numFrames, ctx); inharmonicity.render(bInh, numFrames, ctx); decay.render(bDecay, numFrames, ctx)
        coupling.render(bCoup, numFrames, ctx); soundboard.render(bBoard, numFrames, ctx); room.render(bRoom, numFrames, ctx)
        hardness.render(bHard, numFrames, ctx); bridgeCoupling.render(bBridge, numFrames, ctx); gain.render(bGain, numFrames, ctx)
        val bPin = bufs[9]; val bDry = bufs[11]; val bThump = bufs[12]; val bDamp = bufs[13]; val bKey = bufs[14]
        val bUna = bufs[15]; val bSost = bufs[16]; val bIn = bufs[17]
        pinFundamental.render(bPin, numFrames, ctx); dry.render(bDry, numFrames, ctx)
        thump.render(bThump, numFrames, ctx); damperNoise.render(bDamp, numFrames, ctx); keyNoise.render(bKey, numFrames, ctx)
        unaCorda.render(bUna, numFrames, ctx); sostenuto.render(bSost, numFrames, ctx); bridgeInput.render(bIn, numFrames, ctx)
        // Inputs may be anything a signal graph produces; only NaN and infinities are refused.
        pianoFinite(bPedal, numFrames, 0f); pianoFinite(bInh, numFrames, 1f); pianoFinite(bDecay, numFrames, 1f)
        pianoFinite(bCoup, numFrames, 1f); pianoFinite(bBoard, numFrames, 1f); pianoFinite(bRoom, numFrames, 0f)
        pianoFinite(bHard, numFrames, 1f); pianoFinite(bBridge, numFrames, voicing.bridgeCoupling.toFloat()); pianoFinite(bGain, numFrames, 1f)
        pianoFinite(bPin, numFrames, 0f); pianoFinite(bDry, numFrames, 0f)
        pianoFinite(bThump, numFrames, 0f); pianoFinite(bDamp, numFrames, 1f); pianoFinite(bKey, numFrames, 0f)
        pianoFinite(bUna, numFrames, 0f); pianoFinite(bSost, numFrames, 0f); pianoFinite(bIn, numFrames, 0f, limit = 1e6f)
        for ((m, mic) in micList.withIndex()) {
            val b = micBufs[m]
            mic.position.render(b[0], numFrames, ctx); mic.pan.render(b[1], numFrames, ctx); mic.gain.render(b[2], numFrames, ctx)
            pianoFinite(b[0], numFrames, 0.12f); pianoFinite(b[1], numFrames, 0f); pianoFinite(b[2], numFrames, 1f)
            java.util.Arrays.fill(micOut[m], 0, numFrames, 0.0)
        }
        for ((j, prep) in preparations.withIndex()) prep.render(prepBufs[j], numFrames, ctx)

        while (true) {
            val e = incoming.poll() ?: break
            pending.add(e)
            if (e.kind == 0 && e.job == null && prepareAhead) far.add(e)
        }
        val start = frame
        while (far.isNotEmpty() && far.peek().frame - start <= aheadFrames() + numFrames) submit(far.poll())
        java.util.Arrays.fill(force, 0, numFrames, 0.0)
        for (k in 0 until 88) java.util.Arrays.fill(ownByKey[k], 0, numFrames, 0.0)

        val inp = inputs
        inp.inh = bInh; inp.dec = bDecay; inp.pedal = bPedal; inp.pin = bPin; inp.thump = bThump; inp.damper = bDamp; inp.key = bKey
        inp.micPos = Array(micList.size) { micBufs[it][0] }; inp.micString = micStrings; inp.micOut = micOut
        inp.sost = sostLatched; inp.preps = preparations; inp.prepBuf = prepBufs

        // Segments between events, and at every 64th frame for the sostenuto, so a note, a release or
        // a pedal change lands on its own sample and the output is the same at any block size.
        val res = resonance!!; val brd = board!!; val rm = roomFx!!
        val dcK = 1.0 - 2 * PI * 20.0 / fs
        val nm = micList.size
        var at = 0
        while (at < numFrames) {
            while (pending.isNotEmpty() && pending.peek().frame <= start + at) {
                val e = pending.poll()
                if (shouldDefer(e, start + at)) deferred.add(e) else apply(e, at, bHard, bBridge, bUna)
            }
            sostenutoAt(max(bSost[at].toDouble(), ccSost))
            var to = numFrames
            val next = pending.peek()
            if (next != null && next.frame < start + numFrames) to = (next.frame - start).toInt().coerceIn(at + 1, numFrames)
            to = min(to, (((start + at) / 64 + 1) * 64 - start).toInt())
            inp.ccPedal = ccPedal
            val iter = voices.iterator()
            while (iter.hasNext()) {
                val v = iter.next()
                keyActive[v.key] = true
                v.render(force, ownByKey[v.key], at, to, fs, inp)
                if (v.finished) iter.remove()
            }
            for (i in at until to) {
                val input = force[i] + INPUT_FORCE * bIn[i]
                val pedalNow = max(bPedal[i].toDouble(), ccPedal).coerceIn(0.0, 1.0)
                val wet = res.process(input, pedalNow, ownByKey, keyActive, i, sostLatched)
                val sympathetic = voicing.resonanceGain * bCoup[i] * wet
                brd.process((input + sympathetic) * bBoard[i], lr)
                for (c in 0..1) {
                    val o = lr[c] - dcIn[c] + dcK * dcOut[c]
                    dcIn[c] = lr[c]; dcOut[c] = o; lr[c] = o
                }
                val dr = bDry[i].toDouble()
                if (dr != 0.0) {
                    var ml = 0.0; var mr = 0.0
                    for (m in 0 until nm) {
                        val b = micBufs[m]
                        val heard = MIC_GAIN * b[2][i] * (micOut[m][i] + sympathetic)
                        val pan = b[1][i].toDouble()
                        ml += heard * min(1.0, 1.0 - pan); mr += heard * min(1.0, 1.0 + pan)
                    }
                    lr[0] = (1 - dr) * lr[0] + dr * ml; lr[1] = (1 - dr) * lr[1] + dr * mr
                }
                rm.process(lr, bRoom[i].toDouble())
                val g = OUTPUT_GAIN * bGain[i]
                var l = pianoLimitOutput(lr[0] * g); var r = pianoLimitOutput(lr[1] * g)
                if (!(l.isFinite() && r.isFinite())) {
                    // Only reachable through overflow inside a filter: start the board and room afresh.
                    l = 0.0; r = 0.0; brd.reset(); rm.reset(); dcIn[0] = 0.0; dcIn[1] = 0.0; dcOut[0] = 0.0; dcOut[1] = 0.0
                }
                outL[i] = l.toFloat()
                outR[i] = r.toFloat()
            }
            at = to
        }
        for (k in 0 until 88) keyActive[k] = false
        if (deferred.isNotEmpty()) {
            for (e in deferred) pending.add(e.later(start + numFrames))
            deferred.clear()
        }
        val last = numFrames - 1
        lastHard = strikeHardness(bHard[last]); lastBridge = strikeBridge(bBridge[last], voicing.bridgeCoupling)
        lastUna = strikeUna(max(bUna[last].toDouble(), ccSoft).toFloat())
        frame = start + numFrames
    }

    private fun sostenutoAt(v: Double) {
        val down = v >= 0.5
        if (down == sostDown) return
        sostDown = down
        val held = resonance!!.held
        for (k in 0 until 88) sostLatched[k] = down && held[k] > 0
    }

    private fun apply(e: Event, at: Int, bHard: FloatArray, bBridge: FloatArray, bUna: FloatArray) {
        when (e.kind) {
            0 -> {
                val midi = e.midi.coerceIn(21, 108)
                if (e.wasDeferred) deferredKeys[midi - 21]--
                val hard = strikeHardness(bHard[at])
                val bridge = strikeBridge(bBridge[at], voicing.bridgeCoupling)
                val una = strikeUna(max(bUna[at].toDouble(), ccSoft).toFloat())
                val job = e.job
                var data: PianoNoteData? = null
                if (job != null) {
                    if (job.hard == hard && job.bridge == bridge && job.una == una && job.fs == fs) data = job.take() else job.abandon()
                }
                if (data == null) {
                    strikesPreparedInline++
                    data = prepareStrike(midi, e.value, seedFor(midi, e.strikeIndex), hard, bridge, una, fs)
                }
                if (voices.size >= maxVoices) voices.removeAt(0)
                voices.add(PianoVoice(midi - 21, data, PianoMath.midiToFreq(midi.toDouble()) < 1400.0, fs, voicing.resonanceTransitionSeconds, prepsFor(midi)))
                resonance!!.held[midi - 21]++
            }
            1 -> {
                val k = e.midi.coerceIn(21, 108) - 21
                for (v in voices) if (v.key == k) v.release()
                val r = resonance!!
                if (r.held[k] > 0) r.held[k]--
            }
            2 -> ccPedal = e.value.coerceIn(0.0, 1.0)
            3 -> ccSost = e.value.coerceIn(0.0, 1.0)
            4 -> ccSoft = e.value.coerceIn(0.0, 1.0)
        }
    }

    private fun prepsFor(midi: Int): IntArray = preparations.indices.filter { midi in preparations[it].keys }.toIntArray()

    companion object {
        /** Bridge force to output: coopiano's instrument gain, then about 9 dB so mf peaks near -10 dBFS. */
        const val OUTPUT_GAIN = 0.0011 * 2.8
        /** Close-mic signal to the soundboard output's scale: A2 at velocity 0.7 measured 2.2 dB over the board at 1.0. */
        const val MIC_GAIN = 0.78
        /** Newtons on the bridge for [bridgeInput] at 1. */
        const val INPUT_FORCE = 25.0
        /** Longest a live note waits for its strike before it is prepared on the audio thread. */
        const val MAX_DEFER_SECONDS = 0.03
    }
}

private fun strikeHardness(v: Float): Double = (if (v.isNaN()) 1.0 else v.toDouble()).coerceIn(0.01, 100.0)
private fun strikeBridge(v: Float, default: Double): Double = (if (v.isNaN()) default else v.toDouble()).coerceIn(0.0, 10.0)
private fun strikeUna(v: Float): Double = (if (v.isNaN()) 0.0 else v.toDouble()).coerceIn(0.0, 1.0)

/** How much of the hammer each of a key's strings meets with the soft pedal at [una] (0 up .. 1 down). */
internal fun unaCordaHit(strings: Int, una: Double): DoubleArray? {
    if (una <= 0.0) return null
    return when (strings) {
        3 -> doubleArrayOf(1.0, 1.0, 1.0 - una)
        2 -> doubleArrayOf(1.0, 1.0 - 0.5 * una)
        else -> null
    }
}

/** Worker threads that prepare strikes ahead of their sample, shared by every [Piano]. */
internal object PianoStrikePool {
    val executor: java.util.concurrent.ExecutorService by lazy {
        val n = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 6)
        java.util.concurrent.Executors.newFixedThreadPool(n) { r -> Thread(r, "piano-strike").apply { isDaemon = true } }
    }
}

/** What a [PianoVoice] reads for one segment: the rendered control signals and where to write the microphones. */
internal class PianoBlockInputs {
    var inh = FloatArray(0); var dec = FloatArray(0); var pedal = FloatArray(0); var pin: FloatArray? = null
    var thump: FloatArray? = null; var damper: FloatArray? = null; var key: FloatArray? = null
    var ccPedal = 0.0
    var micPos: Array<FloatArray> = emptyArray()
    var micString = IntArray(0)
    var micOut: Array<DoubleArray> = emptyArray()
    var sost = BooleanArray(88)
    var preps: List<PianoPreparation> = emptyList()
    var prepBuf: Array<Array<FloatArray>> = emptyArray()
}

/** One struck key: its phasor bank, dampers, preparations and attack buffer, rendered on the audio thread. */
internal class PianoVoice(
    val key: Int, val data: PianoNoteData, private val hasDamper: Boolean, fs: Double, transitionSeconds: Double,
    /** Indices into [PianoBlockInputs.preps] of the preparations on this key's strings. */
    private val prepIdx: IntArray = IntArray(0),
) {
    var keyDown = true
        private set
    private var releasePos = -1
    private var micX = DoubleArray(0)
    private var micW = Array(0) { DoubleArray(0) }
    private var hm = DoubleArray(0)

    fun release() {
        if (keyDown) { keyDown = false; releasePos = 0 }
    }
    var finished = false
        private set
    private var age = 0L
    private var contact = 0.0
    private val ramp = 1.0 / max(1.0, transitionSeconds * fs)
    private var noisePos = -1
    private val rise = max(1, (0.002 * fs).toInt())
    private val killAbs = data.peakAmp * 3.1622776601683795e-5
    private var untilCull = 4096
    private var blockLeft = 0
    private var sBlock = Double.NaN
    private var dBlock = Double.NaN
    private var pinBlock = 0.0
    private var or_ = DoubleArray(data.count); private var oi = DoubleArray(data.count)
    private var dr = DoubleArray(data.count); private var di = DoubleArray(data.count)
    private var dw = DoubleArray(data.count)

    // Rattles: per preparation, the string's displacement weights at the object, and the object's state.
    private val np = prepIdx.size
    private val rw = Array(np) { DoubleArray(0) }
    private val rattleOn = BooleanArray(np)
    private val rattleY = DoubleArray(np); private val rattleObj = DoubleArray(np); private val rattlePrev = DoubleArray(np)
    private val rattleVel = DoubleArray(np)
    private val rattleFriction = exp(-RATTLE_FRICTION / fs)
    private val rattleGap = DoubleArray(np); private val rattleLevel = DoubleArray(np)
    private val rc1 = DoubleArray(np); private val rc2 = DoubleArray(np); private val rg = DoubleArray(np)
    private val ry1 = DoubleArray(np); private val ry2 = DoubleArray(np)
    private val rattleNorm = data.tonePeakAmp / max(1e-9, data.hammerVelocity) * RATTLE_GAIN

    private fun recompute(s: Double, d: Double, pin: Double, fs: Double, i: Int, inp: PianoBlockInputs) {
        sBlock = s; dBlock = d; pinBlock = pin
        val nyq = PI * 0.95
        // this voice's preparations, read at this sample
        var anyRattle = false
        val pos = DoubleArray(np); val massR = DoubleArray(np); val damp = DoubleArray(np); val str = IntArray(np)
        for (j in 0 until np) {
            val prep = inp.preps[prepIdx[j]]; val b = inp.prepBuf[prepIdx[j]]
            pos[j] = b[PianoPreparation.POSITION][i].toDouble()
            massR[j] = 2.0 * b[PianoPreparation.MASS][i] * 1e-3 / data.ms
            damp[j] = b[PianoPreparation.DAMPING][i].toDouble()
            str[j] = prep.string
            rattleLevel[j] = b[PianoPreparation.RATTLE][i].toDouble()
            rattleOn[j] = rattleLevel[j] != 0.0
            if (rattleOn[j]) {
                anyRattle = true
                if (rw[j].size < data.count) rw[j] = DoubleArray(data.count)
                rattleGap[j] = abs(b[PianoPreparation.GAP][i].toDouble()) * 1e-3
                val w = 2 * PI * b[PianoPreparation.TONE][i].toDouble().coerceIn(20.0, fs * 0.45) / fs
                val r = exp(-1.0 / (RATTLE_RING_SECONDS * fs))
                rc1[j] = 2 * r * cos(w); rc2[j] = -r * r; rg[j] = 1 - r
            }
        }
        for (p in 0 until data.count) {
            var fa = 1.0; var fb = 1.0; var add = 0.0
            for (j in 0 until np) {
                if (str[j] >= 0 && data.stringIndex[p] != str[j]) { if (rattleOn[j]) rw[j][p] = 0.0; continue }
                val sa = sin(data.na[p] * PI * pos[j])
                fa *= pianoMassFactor(massR[j] * sa * sa)
                add += damp[j] * sa * sa
                if (data.nb[p] > 0) {
                    val sb = sin(data.nb[p] * PI * pos[j])
                    fb *= pianoMassFactor(massR[j] * sb * sb)
                    add += damp[j] * sb * sb
                }
                if (rattleOn[j]) rw[j][p] = if (data.longitudinal[p]) 0.0 else sa
            }
            val w = data.omega(p, s, pin, fa, fb) / fs
            val sig = max(0.0, data.sigma[p] * d + add)
            val sigD = max(data.dampSig[p], sig)
            if (!(w < nyq) || !(w > 0)) { or_[p] = 0.0; oi[p] = 0.0; dr[p] = 0.0; di[p] = 0.0; dw[p] = 0.0; continue }
            val c = cos(w); val sn = sin(w)
            val mo = exp(-sig / fs); val md = exp(-sigD / fs)
            or_[p] = mo * c; oi[p] = mo * sn; dr[p] = md * c; di[p] = md * sn
            dw[p] = data.dOmegaDs(p, s, pin, fa, fb) / fs
        }
        if (!anyRattle) for (j in 0 until np) { ry1[j] = 0.0; ry2[j] = 0.0 }
    }

    private fun micWeights(m: Int, x: Double, string: Int) {
        micX[m] = x
        if (micW[m].size < data.count) micW[m] = DoubleArray(data.count)
        val w = micW[m]
        for (p in 0 until data.count) {
            if (string >= 0 && data.stringIndex[p] != string) { w[p] = 0.0; continue }
            val n = data.na[p] + data.nb[p]
            // A microphone this close hears the string's velocity at x, n·sin(nπx), where the bridge
            // feels its slope, ∝ n: so each mode is weighted by sin(nπx), scaled to the bridge near x_ref.
            w[p] = data.wOut[p] * sin(n * PI * x) * MIC_NORM
        }
    }

    fun render(force: DoubleArray, own: DoubleArray, from: Int, to: Int, fs: Double, inp: PianoBlockInputs) {
        val d = data
        val nm = inp.micOut.size
        if (micX.size != nm) { micX = DoubleArray(nm) { Double.NaN }; micW = Array(nm) { DoubleArray(0) } }
        val inh = inp.inh; val dec = inp.dec; val pedal = inp.pedal; val pin = inp.pin
        val thumpG = inp.thump; val keyG = inp.key; val damperG = inp.damper
        val held = inp.sost[key]
        for (i in from until to) {
            var out = 0.0
            var noise = 0.0
            val ia = age.toInt()
            if (age < d.attack.size) out += d.attack[ia]
            if (thumpG != null && ia < d.thump.size) noise += thumpG[i] * d.thump[ia]
            if (keyG != null) {
                if (ia < d.keyNoise.size) noise += keyG[i] * d.keyNoise[ia]
                if (releasePos >= 0 && releasePos < d.releaseNoise.size) noise += keyG[i] * d.releaseNoise[releasePos++]
            }
            // damper target for this sample
            val pd = max(pedal[i].toDouble(), inp.ccPedal).coerceIn(0.0, 1.0)
            val target = if (!hasDamper || keyDown || held) 0.0 else pianoDamperContact(pd)
            if (target > 0.0 && noisePos < 0 && contact == 0.0) noisePos = 0
            if (contact < target) contact = min(target, contact + ramp) else if (contact > target) contact = max(target, contact - ramp)
            if (noisePos >= 0 && noisePos < d.dampNoise.size) noise += (damperG?.get(i)?.toDouble() ?: 1.0) * d.dampNoise[noisePos++]
            out += noise
            var heard0 = 0.0
            var extra = noise

            if (age >= d.coff) {
                val s = inh[i].toDouble(); val dd = abs(dec[i].toDouble())
                val pn = pin?.get(i)?.toDouble() ?: 0.0
                if (blockLeft <= 0 || sBlock.isNaN()) {
                    recompute(s, dd, pn, fs, i, inp); blockLeft = 32
                    for (m in 0 until nm) { val x = inp.micPos[m][i].toDouble(); if (x != micX[m] || micW[m].size < d.count) micWeights(m, x, inp.micString[m]) }
                }
                blockLeft--
                val ds = s - sBlock; val ddd = dd - dBlock
                val corrected = ds != 0.0 || ddd != 0.0
                val c = contact
                val pAge = age - d.coff
                val onset = if (pAge >= rise) 1.0 else { val t = pAge.toDouble() / rise; t * t * (3 - 2 * t) }
                val zr = d.zr; val zi = d.zi; val wo = d.wOut
                val w0 = if (nm == 1) micW[0] else null
                if (nm > 1) { if (hm.size != nm) hm = DoubleArray(nm) else hm.fill(0.0) }
                var rattles = false
                for (j in 0 until np) if (rattleOn[j]) { rattles = true; rattleY[j] = 0.0 }
                for (p in 0 until d.count) {
                    val re = zr[p]; val im = zi[p]
                    val on = if (d.longitudinal[p]) onset else 1.0
                    out += wo[p] * re * on
                    if (w0 != null) heard0 += w0[p] * re * on
                    else if (nm > 1) { val v = re * on; for (m in 0 until nm) hm[m] += micW[m][p] * v }
                    if (rattles) for (j in 0 until np) if (rattleOn[j]) rattleY[j] += rw[j][p] * re
                    var mr = or_[p] + c * (dr[p] - or_[p])
                    var mi = oi[p] + c * (di[p] - oi[p])
                    if (corrected) {
                        // second-order rotation, renormalised and bounded so no modulation depth can grow a mode
                        val x = (dw[p] * ds).coerceIn(-0.5, 0.5)
                        val y = (d.sigma[p] * ddd / fs).coerceIn(-0.5, 1.0)
                        val nrm = (1 - y) * (1 - 0.125 * x * x * x * x)
                        val cr = nrm * (1 - 0.5 * x * x); val ci = nrm * x
                        val nr = mr * cr - mi * ci; mi = mr * ci + mi * cr; mr = nr
                    }
                    zr[p] = re * mr - im * mi
                    zi[p] = re * mi + im * mr
                }
                if (rattles) {
                    val rt = rattleSample(fs)
                    out += rt; extra += rt
                }
                if (--untilCull <= 0) { untilCull = 4096; cull() }
            }
            force[i] += out; own[i] += out
            if (nm == 1) inp.micOut[0][i] += heard0 + extra
            else for (m in 0 until nm) inp.micOut[m][i] += extra + (if (age >= d.coff && m < hm.size) hm[m] else 0.0)
            age++
        }
        val rattleQuiet = (0 until np).all { abs(ry1[it]) + abs(ry2[it]) < 1e-12 }
        if (age > d.coff && d.count == 0 && age >= d.attack.size && age >= d.thump.size && age >= d.keyNoise.size && rattleQuiet &&
            (noisePos < 0 || noisePos >= d.dampNoise.size) && (releasePos < 0 || releasePos >= d.releaseNoise.size)) finished = true
    }

    /**
     * One sample of every rattle on this voice. The object sits on the string with [rattleGap] of
     * play either side and coasts, slowed by friction, until the string meets it; each meeting rings
     * the object's own small resonance as hard as the two were moving against each other, and the
     * object leaves with the string's speed (part of it bounced back). A wide swing throws it about;
     * once the string swings less than the gap, friction settles it and the buzz stops.
     */
    private fun rattleSample(fs: Double): Double {
        var sum = 0.0
        for (j in 0 until np) {
            var impulse = 0.0
            if (rattleOn[j]) {
                val y = rattleY[j]; val g = rattleGap[j]
                val vs = (y - rattlePrev[j]) * fs
                rattleVel[j] *= rattleFriction
                rattleObj[j] += rattleVel[j] / fs
                val rel = y - rattleObj[j]
                if (rel > g || rel < -g) {
                    val vrel = vs - rattleVel[j]
                    if ((rel > g && vrel > 0) || (rel < -g && vrel < 0)) {
                        impulse = abs(vrel)
                        rattleVel[j] = vs + RATTLE_RESTITUTION * vrel
                    }
                    rattleObj[j] = if (rel > g) y - g else y + g
                }
                rattlePrev[j] = y
            }
            val yv = impulse * rg[j] + rc1[j] * ry1[j] + rc2[j] * ry2[j]
            ry2[j] = ry1[j]; ry1[j] = if (abs(yv) < 1e-30) 0.0 else yv
            sum += rattleLevel[j] * rattleNorm * yv
        }
        return sum
    }

    private fun cull() {
        val d = data
        var w = 0
        for (p in 0 until d.count) {
            if (Math.hypot(d.zr[p], d.zi[p]) * abs(d.wOut[p]) < killAbs) continue
            if (w != p) {
                d.zr[w] = d.zr[p]; d.zi[w] = d.zi[p]; d.wOut[w] = d.wOut[p]; d.sigma[w] = d.sigma[p]
                d.dampSig[w] = d.dampSig[p]; d.na[w] = d.na[p]; d.nb[w] = d.nb[p]; d.f0s[w] = d.f0s[p]
                d.shA[w] = d.shA[p]; d.shB[w] = d.shB[p]; d.pol[w] = d.pol[p]; d.longitudinal[w] = d.longitudinal[p]
                d.stringIndex[w] = d.stringIndex[p]; d.amp0[w] = d.amp0[p]
                or_[w] = or_[p]; oi[w] = oi[p]; dr[w] = dr[p]; di[w] = di[p]; dw[w] = dw[p]
                for (m in micW) if (p < m.size) m[w] = m[p]
                for (r in rw) if (p < r.size) r[w] = r[p]
            }
            w++
        }
        d.count = w
    }
}

/** A point mass's pull on a mode, for `t = 2·(m/M)·sin²(nπx)`: lower above zero, raised (mirrored) below; never NaN. */
internal fun pianoMassFactor(t: Double): Double = if (t >= 0.0) 1.0 / sqrt(1.0 + t) else sqrt(1.0 - t)

/** Rattle output against the voice's tone level, per m/s of string velocity at the catch over the hammer's. */
private const val RATTLE_GAIN = 14.0
private const val RATTLE_RING_SECONDS = 0.0025
/** How fast a coasting rattle slows (1/s), and how much of its speed it keeps bouncing off the string. */
private const val RATTLE_FRICTION = 60.0
private const val RATTLE_RESTITUTION = 0.3

private const val MIC_NORM = 1.0 / (PI * 0.12)

/** Replaces NaN with [default] and holds infinities at ±1e12, in place: the only clamp the inputs get. */
internal fun pianoFinite(b: FloatArray, n: Int, default: Float, limit: Float = 1e12f) {
    for (i in 0 until n) {
        val v = b[i]
        if (v.isNaN()) b[i] = default else if (v > limit) b[i] = limit else if (v < -limit) b[i] = -limit
    }
}

/** A stereo render of a [Piano], with its sample rate. */
class PianoRender(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    val mono: FloatArray get() = FloatArray(left.size) { 0.5f * (left[it] + right[it]) }
    val seconds: Double get() = left.size.toDouble() / sampleRate
    val peak: Float get() = max(left.maxOf { abs(it) }, right.maxOf { abs(it) })

    /** The `.wav` bytes, peak-normalised to [normalizePeak] when it is set. */
    fun wav(normalizePeak: Float? = null): ByteArray =
        afield.audio.engine.WavFloatArrayWriter.encodeStereo16(left, right, sampleRate, normalizePeak)

    fun writeWav(file: java.io.File, normalizePeak: Float? = null) =
        afield.audio.engine.WavFloatArrayWriter.writeStereo16(file, left, right, sampleRate, normalizePeak)
}

/**
 * Renders [seconds] of [piano] offline, through the same [Piano.render] the audio engine calls,
 * after running [setup] to schedule notes on its clock (frame 0 is the first rendered sample).
 */
fun pianoRender(seconds: Double, piano: Piano = Piano(sampleRate = 44_100), blockFrames: Int = 256, setup: Piano.() -> Unit = {}): PianoRender {
    val sr = piano.sampleRate
    piano.offline = true
    piano.setup()
    val frames = (seconds * sr).toInt()
    val left = FloatArray(frames); val right = FloatArray(frames)
    val ctx = SynthContext(sr.toFloat(), blockFrames)
    val bl = FloatArray(blockFrames); val br = FloatArray(blockFrames)
    var at = 0
    var cycle = 1L
    while (at < frames) {
        val n = min(blockFrames, frames - at)
        ctx.cycleId = cycle++
        ctx.time = at.toFloat() / sr
        piano.render(bl, br, n, ctx)
        System.arraycopy(bl, 0, left, at, n); System.arraycopy(br, 0, right, at, n)
        at += n
    }
    return PianoRender(left, right, sr)
}

/**
 * The shape of one string of [midi] struck at [velocity], [seconds] after the hammer left it, as
 * [points] displacements along its length (metres, bridge to agraffe).
 *
 * Read from the same phasors [Piano] plays: each mode's displacement is the real part of its two
 * polarizations, laid out as `sin(n pi x / L)`.
 */
fun pianoStringShape(midi: Int, velocity: Double, seconds: Double, points: Int = 200, voicing: PianoVoicing = PianoVoicing(), sampleRate: Int = 44_100): DoubleArray {
    val key = PianoKey(midi.toDouble(), voicing)
    val d = PianoStrike.prepare(key, PianoString.forKey(key), velocity, 1L, sampleRate.toDouble())
    val y = DoubleArray(points)
    for (p in 0 until d.count) {
        if (d.longitudinal[p] || d.stringIndex[p] != 0) continue
        val w = d.omega(p, 1.0)
        val a = exp(-d.sigma[p] * seconds)
        val re = a * (d.zr[p] * cos(w * seconds) - d.zi[p] * sin(w * seconds))
        val n = d.na[p]
        for (j in 0 until points) y[j] += re * sin(n * PI * j / (points - 1.0))
    }
    return y
}

/** An SVG line plot of [values] (equally spaced), for a box output beside the sound. */
fun pianoPlotSvg(values: DoubleArray, title: String, width: Int = 640, height: Int = 200, logX: Boolean = false, xLabel: String = "", yLabel: String = ""): String {
    val n = values.size
    val lo = values.minOrNull() ?: 0.0; val hi = values.maxOrNull() ?: 1.0
    val span = if (hi - lo == 0.0) 1.0 else hi - lo
    val sb = StringBuilder()
    sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='$height' style='background:#111;font:11px sans-serif'>")
    sb.append("<text x='8' y='14' fill='#ccc'>").append(title).append("</text>")
    sb.append("<text x='8' y='${height - 6}' fill='#888'>").append(xLabel).append("</text>")
    sb.append("<text x='${width - 8}' y='14' fill='#888' text-anchor='end'>").append(yLabel).append("</text>")
    sb.append("<polyline fill='none' stroke='#f0b040' stroke-width='1.2' points='")
    for (i in 0 until n) {
        val fx = if (logX) ln(1.0 + i) / ln(n.toDouble()) else i / (n - 1.0)
        val x = 8 + fx * (width - 16)
        val yy = height - 20 - (values[i] - lo) / span * (height - 44)
        sb.append(String.format("%.1f,%.1f ", x, yy))
    }
    sb.append("'/></svg>")
    return sb.toString()
}

/** Magnitude spectrum in dB of the first [fftSize] samples of [x] from [startSample], Hann-windowed. */
fun pianoSpectrumDb(x: FloatArray, startSample: Int = 0, fftSize: Int = 16384): DoubleArray {
    val re = DoubleArray(fftSize); val im = DoubleArray(fftSize)
    for (i in 0 until fftSize) {
        val j = startSample + i
        if (j < x.size) re[i] = x[j] * (0.5 - 0.5 * cos(2 * PI * i / (fftSize - 1)))
    }
    PianoMath.fft(re, im, false)
    return DoubleArray(fftSize / 2) { 20 * log10(sqrt(re[it] * re[it] + im[it] * im[it]) + 1e-9) }
}

/**
 * One held note's dry bridge force, before resonance, soundboard and room: coopiano's
 * `render_bridge_force`, for comparing the strings and hammer against the C++.
 */
internal fun pianoBridgeForce(midi: Int, velocity: Double, seconds: Double, seed: Long = 1L, sampleRate: Int = 44_100, voicing: PianoVoicing = PianoVoicing()): Pair<DoubleArray, PianoNoteData> {
    val fs = sampleRate.toDouble()
    val key = PianoKey(midi.toDouble(), voicing)
    val data = PianoStrike.prepare(key, PianoString.forKey(key), velocity, seed, fs)
    val phasors = data.count
    val voice = PianoVoice(midi - 21, data, key.hasDamper, fs, voicing.resonanceTransitionSeconds)
    val n = (seconds * fs).toInt()
    val force = DoubleArray(n); val own = DoubleArray(n)
    val inp = PianoBlockInputs().apply { inh = FloatArray(n) { 1f }; dec = FloatArray(n) { 1f }; pedal = FloatArray(n) }
    voice.render(force, own, 0, n, fs, inp)
    data.count = phasors.coerceAtMost(data.count)
    return force to data
}

/** `60` -> 60, `c4` -> 60, `eb3` -> 51, `f#5` -> 78; null when [atom] is neither. */
fun pianoNoteNumber(atom: String): Int? {
    val t = atom.trim().lowercase()
    t.toDoubleOrNull()?.let { return it.roundToInt() }
    val m = Regex("^([a-g])([#sb]?)(-?\\d)$").matchEntire(t) ?: return null
    val base = when (m.groupValues[1]) { "c" -> 0; "d" -> 2; "e" -> 4; "f" -> 5; "g" -> 7; "a" -> 9; else -> 11 }
    val acc = when (m.groupValues[2]) { "#", "s" -> 1; "b" -> -1; else -> 0 }
    return 12 * (m.groupValues[3].toInt() + 1) + base + acc
}

/** Puts [render] in this box's output as an audio player, peak-normalised to -1 dBFS. */
fun afield.document.graph.Box.showPianoAudio(render: PianoRender, channel: String = "main") {
    display(channel, render.wav(normalizePeak = 0.89f))
}
