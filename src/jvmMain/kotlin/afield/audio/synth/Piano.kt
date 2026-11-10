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
 * `Piano` is a [StereoNode], so `audio(Piano())` plays it, and it is played the way a piano is:
 *
 * ```kotlin
 * val piano = Piano()
 * piano.noteOn(60, 0.6)          // middle C, mezzo-forte
 * piano.noteOff(60)              // the damper comes down
 * piano.play(pat("c4 e4 g4 c5"), cycles = 2, cps = 0.5)
 * piano.play(readMidiClip(file))  // notes and the sustain pedal (CC 64)
 * audio(piano)
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
 *
 * The hammer only touches the string for one to three milliseconds, so [hardness] is read once, at
 * the strike's own sample, from whatever is driving it: an oscillator on `hardness` gives each note a
 * different hammer. An audio-rate signal on [inharmonicity] or [decay] is applied per sample: each
 * phasor's step is recomputed every 32 samples and corrected to second order in between, so an
 * oscillator at a few hundred hertz on `inharmonicity` puts sidebands on every partial.
 *
 * All synthesis state, the strings, dampers, resonators, board and room, belongs to the audio thread.
 * [noteOn], [noteOff] and [play] only enqueue frame-stamped events.
 *
 * # Against coopiano
 *
 * A4 at velocity 0.5, eight seconds, no room, rendered by this class and by coopiano's own C++:
 * the first eight partials agree within 0.3 cents and the fundamental and second partial's
 * 20–60 dB decay agree within a few percent. The numbers and the method are in `PianoTest` and
 * `dev_docs/audio/PIANO_PORT.md`. The port differs in three ways: the sample rate is a parameter,
 * the random draws are Java's, so a strike is the same instrument but not the same bits, and the
 * soundboard is coopiano's single shared stereo response rather than its four bridge regions.
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
    /** Sample rate the soundboard and resonators are built for; rebuilt if the engine's differs. */
    val sampleRate: Int = 48_000,
    val maxVoices: Int = 48,
) : StereoNode() {

    private class Event(val frame: Long, val kind: Int, val midi: Int, val value: Double, val order: Long)

    private val incoming = ConcurrentLinkedQueue<Event>()
    private val pending = PriorityQueue<Event>(64, compareBy<Event>({ it.frame }, { it.order }))
    @Volatile private var orderCounter = 0L

    /** Frames rendered so far; the clock [noteOn]'s `atFrame` is on. */
    @Volatile var frame: Long = 0L
        private set

    private var fs = 0.0
    private var resonance: PianoResonance? = null
    private var board: PianoBoardConvolver? = null
    private var roomFx: PianoRoom? = null
    private val voices = ArrayList<PianoVoice>()
    private val keyStrings = HashMap<Long, List<PianoString>>()
    private var ccPedal = 0.0
    private var strikes = 0L
    private var dcIn = doubleArrayOf(0.0, 0.0); private var dcOut = doubleArrayOf(0.0, 0.0)

    private var bufs = Array(10) { FloatArray(0) }
    private var force = DoubleArray(0)
    private var ownByKey = Array(88) { DoubleArray(0) }
    private val keyActive = BooleanArray(88)
    private val lr = DoubleArray(2)

    init {
        build(sampleRate.toDouble())
    }

    /** Strike [midi] (21..108) at [velocity] 0..1, at [atFrame] on [frame]'s clock or as soon as possible. */
    fun noteOn(midi: Int, velocity: Double = 0.6, atFrame: Long = -1L) = enqueue(atFrame, 0, midi, velocity)

    /** Release [midi]: its dampers come down unless the pedal holds them. */
    fun noteOff(midi: Int, atFrame: Long = -1L) = enqueue(atFrame, 1, midi, 0.0)

    /** Sustain pedal from a controller (MIDI CC 64), combined with [pedal] by taking the larger. */
    fun pedalEvent(value: Double, atFrame: Long = -1L) = enqueue(atFrame, 2, 0, value)

    private fun enqueue(atFrame: Long, kind: Int, midi: Int, value: Double) {
        incoming.add(Event(if (atFrame < 0) frame else atFrame, kind, midi, value, orderCounter++))
    }

    /**
     * Schedules [cycles] cycles of [pattern] from [startFrame] (default: 50 ms from now).
     *
     * Notes are named as in `pat` (`c4`, `e4`, `60`) and the `gain` annotation is the velocity,
     * scaled by [velocity]. A note holds its key for its `sustain` share of the event.
     */
    fun play(pattern: PatPattern<String>, cycles: Int = 1, cps: Double = 0.5, velocity: Double = 0.6, startFrame: Long = -1L) {
        val sr = (if (fs > 0) fs else sampleRate.toDouble()).toInt()
        val origin = if (startFrame >= 0) startFrame else frame + sr / 20
        for (c in 0 until cycles) {
            for (n in patSynthNotesForArc(pattern, PatArc(PatQ.of(c), PatQ.of(c + 1)), cps, sr, patVoice(sustain = 0.9), velocity, origin)) {
                val midi = (69.0 + 12.0 * log2(n.hz / 440.0)).roundToInt()
                if (midi !in 21..108) continue
                noteOn(midi, n.gain.coerceIn(0.02, 1.0), n.startFrame)
                noteOff(midi, n.startFrame + n.holdFrames.coerceAtLeast(1))
            }
        }
    }

    /** Schedules every note of [clip], and its sustain pedal, from [startFrame]. */
    fun play(clip: MidiClip, startFrame: Long = -1L, velocityScale: Double = 1.0) {
        val sr = if (fs > 0) fs else sampleRate.toDouble()
        val origin = if (startFrame >= 0) startFrame else frame + (sr / 20).toLong()
        val seq = clip.toSequence()
        for (n in seq.readTimedNotes()) {
            if (n.pitch !in 21..108) continue
            noteOn(n.pitch, (n.velocity / 127.0 * velocityScale).coerceIn(0.02, 1.0), origin + (n.startSeconds * sr).toLong())
            noteOff(n.pitch, origin + (n.endSeconds * sr).toLong())
        }
        val tempos = seq.tempoEvents()
        for (cc in seq.readControlChanges()) {
            if (cc.controller != 64) continue
            val ticks = (cc.beats * seq.resolution).toLong()
            pedalEvent(cc.value / 127.0, origin + (tickToSeconds(ticks, tempos, seq.resolution) * sr).toLong())
        }
    }

    /** Silences every voice and resonator. Call from the thread that renders, or before playing. */
    fun reset() {
        voices.clear(); pending.clear(); incoming.clear(); ccPedal = 0.0
        build(if (fs > 0) fs else sampleRate.toDouble())
    }

    /** How many voices are sounding, and their phasor count, for inspection. */
    val activeVoices: Int get() = voices.size
    val activePhasors: Int get() = voices.sumOf { it.data.count }

    private fun build(rate: Double) {
        fs = rate
        resonance = PianoResonance(voicing, rate)
        val (l, r) = PianoSoundboardIr.get(rate, voicing)
        board = PianoBoardConvolver(l, r)
        roomFx = PianoRoom(rate, voicing)
        keyStrings.clear()
    }

    private fun stringsFor(midi: Int, bridge: Double): List<PianoString> {
        val key = midi.toLong() * 1_000_003L + (bridge * 1000).roundToInt()
        return keyStrings.getOrPut(key) { PianoString.forKey(PianoKey(midi.toDouble(), voicing, 1.0, bridge)) }
    }

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (ctx.sampleRate.toDouble() != fs) build(ctx.sampleRate.toDouble())
        if (bufs[0].size < numFrames) { bufs = Array(10) { FloatArray(numFrames) }; force = DoubleArray(numFrames); ownByKey = Array(88) { DoubleArray(numFrames) } }
        val bPedal = bufs[0]; val bInh = bufs[1]; val bDecay = bufs[2]; val bCoup = bufs[3]; val bBoard = bufs[4]
        val bRoom = bufs[5]; val bHard = bufs[6]; val bBridge = bufs[7]; val bGain = bufs[8]
        pedal.render(bPedal, numFrames, ctx); inharmonicity.render(bInh, numFrames, ctx); decay.render(bDecay, numFrames, ctx)
        coupling.render(bCoup, numFrames, ctx); soundboard.render(bBoard, numFrames, ctx); room.render(bRoom, numFrames, ctx)
        hardness.render(bHard, numFrames, ctx); bridgeCoupling.render(bBridge, numFrames, ctx); gain.render(bGain, numFrames, ctx)

        while (true) pending.add(incoming.poll() ?: break)
        val start = frame
        java.util.Arrays.fill(force, 0, numFrames, 0.0)
        for (k in 0 until 88) java.util.Arrays.fill(ownByKey[k], 0, numFrames, 0.0)

        // Segments between events, so a note, a release or a pedal change lands on its own sample
        // and the output is the same at any block size.
        val res = resonance!!; val brd = board!!; val rm = roomFx!!
        val dcK = 1.0 - 2 * PI * 20.0 / fs
        var at = 0
        while (at < numFrames) {
            while (pending.isNotEmpty() && pending.peek().frame <= start + at) apply(pending.poll(), at, bHard, bBridge)
            var to = numFrames
            val next = pending.peek()
            if (next != null && next.frame < start + numFrames) to = (next.frame - start).toInt().coerceIn(at + 1, numFrames)
            val iter = voices.iterator()
            while (iter.hasNext()) {
                val v = iter.next()
                keyActive[v.key] = true
                v.render(force, ownByKey[v.key], at, to, bInh, bDecay, bPedal, ccPedal, fs)
                if (v.finished) iter.remove()
            }
            for (i in at until to) {
                val input = force[i]
                val pedalNow = max(bPedal[i].toDouble(), ccPedal).coerceIn(0.0, 1.0)
                val wet = res.process(input, pedalNow, ownByKey, keyActive, i)
                val bridge = input + voicing.resonanceGain * bCoup[i] * wet
                brd.process(bridge * bBoard[i], lr)
                for (c in 0..1) {
                    val o = lr[c] - dcIn[c] + dcK * dcOut[c]
                    dcIn[c] = lr[c]; dcOut[c] = o; lr[c] = o
                }
                rm.process(lr, bRoom[i].toDouble())
                val g = OUTPUT_GAIN * bGain[i]
                outL[i] = pianoLimitOutput(lr[0] * g).toFloat()
                outR[i] = pianoLimitOutput(lr[1] * g).toFloat()
            }
            at = to
        }
        for (k in 0 until 88) keyActive[k] = false
        frame = start + numFrames
    }

    private fun apply(e: Event, at: Int, bHard: FloatArray, bBridge: FloatArray) {
        when (e.kind) {
            0 -> {
                val midi = e.midi.coerceIn(21, 108)
                val hard = bHard[at].toDouble().coerceIn(0.01, 100.0)
                val bridge = bBridge[at].toDouble().coerceIn(0.0, 10.0)
                val key = PianoKey(midi.toDouble(), voicing, hard, bridge)
                val data = PianoStrike.prepare(key, stringsFor(midi, bridge), e.value, -0x61c8864680b583ebL xor (strikes++ * 0x100000001b3L) xor ((midi * 64L) shl 32), fs)
                if (voices.size >= maxVoices) voices.removeAt(0)
                voices.add(PianoVoice(midi - 21, data, key.hasDamper, fs, voicing.resonanceTransitionSeconds))
                resonance!!.held[midi - 21]++
            }
            1 -> {
                val k = e.midi.coerceIn(21, 108) - 21
                for (v in voices) if (v.key == k) v.keyDown = false
                val r = resonance!!
                if (r.held[k] > 0) r.held[k]--
            }
            2 -> ccPedal = e.value.coerceIn(0.0, 1.0)
        }
    }

    companion object {
        /** Bridge force to output: coopiano's instrument gain, then about 9 dB so mf peaks near -10 dBFS. */
        const val OUTPUT_GAIN = 0.0011 * 2.8
    }
}

/** One struck key: its phasor bank, dampers and attack buffer, rendered on the audio thread. */
internal class PianoVoice(val key: Int, val data: PianoNoteData, private val hasDamper: Boolean, fs: Double, transitionSeconds: Double) {
    var keyDown = true
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
    private var or_ = DoubleArray(data.count); private var oi = DoubleArray(data.count)
    private var dr = DoubleArray(data.count); private var di = DoubleArray(data.count)
    private var dw = DoubleArray(data.count)

    private fun recompute(s: Double, d: Double, fs: Double) {
        sBlock = s; dBlock = d
        val nyq = PI * 0.95
        for (p in 0 until data.count) {
            val w = data.omega(p, s) / fs
            val sig = data.sigma[p] * d
            val sigD = max(data.dampSig[p], sig)
            if (w >= nyq || w <= 0) { or_[p] = 0.0; oi[p] = 0.0; dr[p] = 0.0; di[p] = 0.0; dw[p] = 0.0; continue }
            val c = cos(w); val sn = sin(w)
            val mo = exp(-sig / fs); val md = exp(-sigD / fs)
            or_[p] = mo * c; oi[p] = mo * sn; dr[p] = md * c; di[p] = md * sn
            dw[p] = data.dOmegaDs(p, s) / fs
        }
    }

    fun render(force: DoubleArray, own: DoubleArray, from: Int, to: Int, inh: FloatArray, dec: FloatArray, pedal: FloatArray, ccPedal: Double, fs: Double) {
        val d = data
        for (i in from until to) {
            var out = 0.0
            if (age < d.attack.size) out += d.attack[age.toInt()]
            // damper target for this sample
            val pd = max(pedal[i].toDouble(), ccPedal).coerceIn(0.0, 1.0)
            val target = if (!hasDamper || keyDown) 0.0 else pianoDamperContact(pd)
            if (target > 0.0 && noisePos < 0 && contact == 0.0) noisePos = 0
            if (contact < target) contact = min(target, contact + ramp) else if (contact > target) contact = max(target, contact - ramp)
            if (noisePos >= 0 && noisePos < d.dampNoise.size) out += d.dampNoise[noisePos++]

            if (age >= d.coff) {
                val s = inh[i].toDouble(); val dd = dec[i].toDouble()
                if (blockLeft <= 0 || sBlock.isNaN()) { recompute(s, dd, fs); blockLeft = 32 }
                blockLeft--
                val ds = s - sBlock; val ddd = dd - dBlock
                val corrected = ds != 0.0 || ddd != 0.0
                val c = contact
                val pAge = age - d.coff
                val onset = if (pAge >= rise) 1.0 else { val t = pAge.toDouble() / rise; t * t * (3 - 2 * t) }
                val zr = d.zr; val zi = d.zi; val wo = d.wOut
                for (p in 0 until d.count) {
                    val re = zr[p]; val im = zi[p]
                    out += wo[p] * re * (if (d.longitudinal[p]) onset else 1.0)
                    var mr = or_[p] + c * (dr[p] - or_[p])
                    var mi = oi[p] + c * (di[p] - oi[p])
                    if (corrected) {
                        val x = dw[p] * ds
                        val y = d.sigma[p] * ddd / fs
                        val cr = (1 - y) * (1 - 0.5 * x * x); val ci = (1 - y) * x
                        val nr = mr * cr - mi * ci; mi = mr * ci + mi * cr; mr = nr
                    }
                    zr[p] = re * mr - im * mi
                    zi[p] = re * mi + im * mr
                }
                if (--untilCull <= 0) { untilCull = 4096; cull() }
            }
            force[i] += out; own[i] += out
            age++
        }
        if (age > d.coff && d.count == 0 && age >= d.attack.size && (noisePos < 0 || noisePos >= d.dampNoise.size)) finished = true
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
            }
            w++
        }
        d.count = w
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
    val one = FloatArray(n) { 1f }; val zero = FloatArray(n)
    voice.render(force, own, 0, n, one, one, zero, 0.0, fs)
    data.count = phasors.coerceAtMost(data.count)
    return force to data
}
