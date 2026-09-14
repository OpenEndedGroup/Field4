@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.Box
import afield.RunsAt
import afield.ReturnValueRegistry
import afield.inMain
import afield.registerLaunchJob
import afield.returnValueRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A pattern on the audio seam: `pat(...)` driving Field's own voices in realtime.
 *
 * [Making a sound in Field](../sound_paths.md) divides everything that makes
 * samples by one signature, `render(outL, outR, numFrames, ctx)`, and a path on that seam composes
 * with every other path on it. [patRenderStereo] is not on it: it is a free function that returns a
 * whole buffer, because a pattern has to be *queried* over an arc and there is no "next 128 frames"
 * to ask it for without a scheduler. This file is that scheduler, for Field's own voices.
 *
 * Three parts, and the division between them is the whole design:
 *
 * | | where it runs | what it does |
 * | --- | --- | --- |
 * | [PatSynthFeeder] | a daemon thread | queries the pattern a cycle at a time and turns events into frame-stamped [PatSynthNote]s |
 * | [PatSynthBank] | the audio thread | starts a note on the frame it was given, and synthesises |
 * | [PatSynthLaunch] | neither | owns both, and closes both |
 *
 * Querying a pattern allocates, and a cycle of a large composed pattern is not a 2.67 ms job, so the
 * query cannot be on the audio thread. It does not have to be: what it produces is *frames*, and
 * being 400 ms early is the same sound as being exactly on time. The audio thread's remaining job is
 * an integer comparison. [FaustPatternFeeder] splits the work the same way for a Faust voice bank,
 * and the two are deliberate siblings.
 *
 * # The voice, and why the `.wav` is the same thing you hear
 *
 * One oscillator, one exponential-decay envelope, one one-pole lowpass, reading the same `gain`,
 * `pan`, `cutoff`, `sustain`, `note` and `speed` annotations off each event and using the same
 * [patDrumHz] map for unpitched atoms as [patRenderStereo] does.
 *
 * What makes a `.wav` on a documentation page a record of the realtime path rather than a second
 * implementation of it is [patSynthRenderStereo]: it drives *this* bank through a [SynthContext]
 * block by block, the way [FieldAudioEngine] does, and returns what came out. No audio device is
 * involved and nothing is re-derived.
 *
 * # Block size does not change the output
 *
 * A scheduler that rounded each note to a block boundary would sound fine and be wrong, and the
 * symptom is that its output changes when the engine's buffer size does. [patSynthRenderStereo] takes
 * `blockFrames`, and `PatSynthDocs` renders three bars at 64, 128, 512 and 1024 and asserts the four
 * buffers are bit-identical. That is the property that makes this a scheduler rather than a
 * quantiser.
 *
 * The mixing loop runs samples outside and voices inside, and that is what makes the property exact
 * rather than close. The other way round, every note due in a block starts before any voice in that
 * block has finished, so which slot a note lands in moves with the block boundaries and so does the
 * order its samples are summed in; float addition is not associative, and a sixteen-voice bar reads
 * one ULP apart at two buffer sizes. Deciding starts and ends per sample costs one branch per voice
 * per sample.
 */

/**
 * One note, stamped with the absolute frame it starts on.
 *
 * Every field here is resolved before the note reaches the audio thread, including the noise seed, so
 * that starting it costs no decisions. [seed] is derived from the event's own rational arc rather
 * than from a counter, which is what makes an unpitched voice sound the same in whichever order the
 * events happened to arrive.
 */
data class PatSynthNote(
    /** Absolute frame, on [PatSynthBank.frame]'s clock. */
    val startFrame: Long,
    /** Frames to hold at full before the decay starts. */
    val holdFrames: Int,
    /** Frames of decay tail to keep rendering after the hold. */
    val tailFrames: Int,
    val hz: Double,
    val wave: PatWave,
    val gain: Double,
    /** Zero is hard left, one is hard right, matching the `pan` annotation. */
    val pan: Double,
    /** One-pole lowpass corner, or null for no filter. */
    val cutoffHz: Double?,
    val seed: Long,
) {
    /** Total frames this note occupies, which is what a voice is reserved for. */
    val frames: Int get() = (holdFrames + tailFrames).coerceAtLeast(1)
}

/**
 * The events of [pattern] over [arc], as notes stamped on [originFrame]'s clock.
 *
 * Called off the audio thread. [arc] is in cycles, so `PatArc(PatQ.of(3), PatQ.of(4))` is the fourth
 * cycle, and a note's frame is `originFrame` plus its position in seconds times [sampleRate].
 */
fun patSynthNotesForArc(
    pattern: PatPattern<String>,
    arc: PatArc,
    cps: Double,
    sampleRate: Int,
    voice: PatVoiceSpec = patVoice(),
    gain: Double = 1.0,
    originFrame: Long = 0L,
): List<PatSynthNote> {
    require(cps > 0.0) { "cps must be > 0, got $cps" }
    val secondsPerCycle = 1.0 / cps
    return pattern.events(arc)
        .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
        .map { event ->
            val startSec = event.arc.start.toDouble() * secondsPerCycle
            val arcSec = (event.arc.end - event.arc.start).toDouble() * secondsPerCycle
            val sustainFrac = (event.annotations["sustain"]?.toDoubleOrNull() ?: voice.sustain).coerceIn(0.0, 1.0)
            val holdSec = arcSec * sustainFrac

            val explicitNote = event.annotations["note"]?.toDoubleOrNull()
            val atomNote = event.value.trim().toDoubleOrNull()
            val drum = patDrumHz(event.value.trim(), voice.baseHz)
            val hz = when {
                explicitNote != null -> patMidiToHz(explicitNote)
                atomNote != null -> patMidiToHz(atomNote)
                else -> drum.first
            }
            val wave = if (explicitNote == null && atomNote == null) (drum.second ?: voice.wave) else voice.wave
            val speed = event.annotations["speed"]?.toDoubleOrNull() ?: 1.0

            PatSynthNote(
                startFrame = originFrame + Math.round(startSec * sampleRate),
                holdFrames = Math.round(holdSec * sampleRate).toInt().coerceAtLeast(0),
                tailFrames = Math.round(voice.decay * 4.0 * sampleRate).toInt().coerceAtLeast(1),
                hz = hz * speed,
                wave = wave,
                gain = ((event.annotations["gain"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 4.0)) * gain,
                pan = (event.annotations["pan"]?.toDoubleOrNull() ?: 0.5).coerceIn(0.0, 1.0),
                cutoffHz = event.annotations["cutoff"]?.toDoubleOrNull(),
                seed = splitmix64(
                    (event.arc.start.n.toLong() * 1_000_003L) xor
                        (event.arc.start.d.toLong() * 7_919L) xor
                        event.value.hashCode().toLong(),
                ),
            )
        }
}

/**
 * A fixed bank of [patVoice] voices on the audio seam, played by frame-stamped [PatSynthNote]s.
 *
 * Returning one from a box plays it, because it is a [StereoRenderable] and
 * [registerAudioReturnValue] takes those. Usually you want [patSynth], which puts a
 * [PatSynthFeeder] in front of it so a pattern rather than a caller decides what plays.
 *
 * Nothing allocates in [generateStereo] and nothing locks: notes arrive on a
 * [ConcurrentLinkedQueue], get drained into a priority queue this thread owns, and start when the
 * frame counter reaches them. A note whose frame has already gone by starts at the top of the next
 * block, which is the right answer for a feeder that fell behind.
 *
 * @param voices how many notes can sound at once. A new note with none free steals the oldest.
 */
class PatSynthBank(
    val voice: PatVoiceSpec = patVoice(),
    val voices: Int = 16,
    val sampleRate: Int = 48_000,
    val masterGain: Double = 1.0,
) : StereoNode() {

    private class Live {
        var active = false
        var note: PatSynthNote? = null
        var i = 0
        var lowpass = 0.0
        var noiseState = 0L
        var phaseStep = 0.0
        var alpha = 1.0
        var leftGain = 0.0
        var rightGain = 0.0
        var holdSec = 0.0
        var startedAt = 0L
    }

    private val bank = Array(voices.coerceAtLeast(1)) { Live() }
    private val incoming = ConcurrentLinkedQueue<PatSynthNote>()
    private val pending = PriorityQueue<PatSynthNote>(64, compareBy({ it.startFrame }, { it.hz }))
    private val frameCounter = AtomicLong(0L)
    private val silenceRequested = AtomicBoolean(false)

    /** Frames rendered so far. The clock a [PatSynthNote]'s `startFrame` is on. */
    val frame: Long get() = frameCounter.get()

    /** Notes handed over but not yet started. Read it to see whether the feeder is keeping up. */
    @Volatile
    var queued: Int = 0
        private set

    /** Notes that started on a voice still sounding, because every voice was busy. */
    @Volatile
    var stolen: Long = 0L
        private set

    /** Hand [notes] to the audio thread. Safe from any thread; allocates nothing on the audio side. */
    fun schedule(notes: Collection<PatSynthNote>) {
        notes.forEach { incoming.add(it) }
    }

    fun schedule(note: PatSynthNote) {
        incoming.add(note)
    }

    /** Silence everything sounding and forget everything scheduled, at the top of the next block. */
    fun allNotesOff() {
        silenceRequested.set(true)
    }

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        while (true) {
            val next = incoming.poll() ?: break
            pending.add(next)
        }
        if (silenceRequested.compareAndSet(true, false)) {
            pending.clear()
            bank.forEach { it.active = false }
        }
        queued = pending.size

        val blockStart = frameCounter.get()

        // Sample-outer rather than voice-outer, and that choice is the whole reason the output does
        // not depend on the engine's buffer size. A voice-outer loop starts every note due in the
        // block before any voice in the block has finished, so which slot a note lands in, and
        // therefore the order its contribution is summed in, moves with the block boundaries. Float
        // addition is not associative, so that shows up as a one-ULP difference between buffer
        // sizes. Deciding both per sample removes the dependency rather than making it small.
        for (n in 0 until numFrames) {
            val now = blockStart + n
            while (pending.isNotEmpty() && pending.peek().startFrame <= now) {
                start(pending.poll(), now)
            }
            var l = 0.0
            var r = 0.0
            for (v in bank) {
                if (!v.active) continue
                val note = v.note ?: continue
                if (v.i >= note.frames) { v.active = false; continue }
                val t = v.i.toDouble() / sampleRate
                val env = patVoiceEnvelope(t, v.holdSec, voice.attack, voice.decay)
                if (env <= 1e-6 && t > v.holdSec) { v.active = false; continue }
                val phase = (v.phaseStep * v.i) % 1.0
                val raw = when (note.wave) {
                    PatWave.SINE -> sin(2.0 * PI * phase)
                    PatWave.SAW -> 2.0 * phase - 1.0
                    PatWave.SQUARE -> if (phase < 0.5) 1.0 else -1.0
                    PatWave.TRIANGLE -> if (phase < 0.5) 4.0 * phase - 1.0 else 3.0 - 4.0 * phase
                    PatWave.NOISE -> {
                        v.noiseState = splitmix64(v.noiseState)
                        toUnitDouble(v.noiseState) * 2.0 - 1.0
                    }
                }
                v.lowpass += v.alpha * (raw - v.lowpass)
                val sample = v.lowpass * env
                l += sample * v.leftGain
                r += sample * v.rightGain
                v.i++
            }
            outL[n] += l.toFloat()
            outR[n] += r.toFloat()
        }

        frameCounter.set(blockStart + numFrames)
    }

    private fun start(note: PatSynthNote, now: Long) {
        val free = bank.firstOrNull { !it.active }
        val target = free ?: bank.minByOrNull { it.startedAt }?.also { stolen++ } ?: return
        target.active = true
        target.note = note.copy(startFrame = now)
        target.i = 0
        target.lowpass = 0.0
        target.noiseState = note.seed
        target.phaseStep = note.hz / sampleRate
        target.holdSec = note.holdFrames.toDouble() / sampleRate
        target.alpha = onePoleAlpha(note.cutoffHz, sampleRate)
        val g = note.gain * masterGain
        target.leftGain = g * sqrt(1.0 - note.pan)
        target.rightGain = g * sqrt(note.pan)
        target.startedAt = now
    }

    override fun toString(): String = synthPrettyObject(
        this, "voices" to voices, "frame" to frame, "queued" to queued, "stolen" to stolen,
    )
}

/** The one-pole coefficient [patRenderStereo] uses, so the two paths filter identically. */
internal fun onePoleAlpha(cutoffHz: Double?, sampleRate: Int): Double {
    if (cutoffHz == null) return 1.0
    val rc = 1.0 / (2.0 * PI * cutoffHz.coerceIn(20.0, sampleRate / 2.2))
    val dt = 1.0 / sampleRate
    return dt / (rc + dt)
}

/**
 * Feeds [pattern] into [bank] a cycle at a time, [aheadSeconds] ahead of the audio thread.
 *
 * The counterpart of [FaustPatternFeeder], and the same argument applies: the query is off the audio
 * thread and what it produces is frames, so running early costs nothing.
 */
class PatSynthFeeder internal constructor(
    private val bank: PatSynthBank,
    val pattern: PatPattern<String>,
    val cps: Double,
    val voice: PatVoiceSpec,
    val gain: Double,
    val aheadSeconds: Double = 0.4,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Cycles queried so far. */
    @Volatile
    var cyclesFed: Long = 0L
        private set

    internal fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = kotlin.concurrent.thread(isDaemon = true, name = "pat-synth-feeder") {
            val origin = bank.frame
            val framesPerCycle = bank.sampleRate / cps
            val ahead = aheadSeconds * bank.sampleRate
            var cycle = 0L
            while (running.get()) {
                val horizon = bank.frame + ahead
                var fed = 0
                while (running.get() && origin + cycle * framesPerCycle <= horizon && fed < 64) {
                    bank.schedule(
                        patSynthNotesForArc(
                            pattern = pattern,
                            arc = PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)),
                            cps = cps,
                            sampleRate = bank.sampleRate,
                            voice = voice,
                            gain = gain,
                            originFrame = origin,
                        ),
                    )
                    cycle++
                    cyclesFed = cycle
                    fed++
                }
                runCatching { Thread.sleep(20L) }.onFailure { return@thread }
            }
        }
    }

    /** Stop querying. Notes already scheduled still play; [PatSynthBank.allNotesOff] silences those. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    val isRunning: Boolean get() = running.get()

    override fun toString(): String = synthPrettyObject(
        this, "cps" to cps, "cyclesFed" to cyclesFed, "running" to isRunning,
    )
}

/**
 * A pattern playing through a [PatSynthBank], as one thing a box can return.
 *
 * ```kotlin
 * patSynth(pat("bd ~ sd ~, hh*8"), cps = 2.0)
 * ```
 *
 * Returning it from a box ties the sound to the box: the box stays lit while it plays, and stopping
 * it stops the voices and the feeder thread. It is also a [StereoRenderable], so it can go into a
 * larger graph.
 *
 * [tap] is where analysis goes. It sits in the signal path and passes the audio through unchanged,
 * so `launch.tap.snapshot()` is a copy of what the audio thread last produced rather than a second
 * rendering of the graph. Rendering a stateful graph twice, once for the speakers and once for a
 * display value, is how a modulation oscillator ends up advancing at double speed.
 */
class PatSynthLaunch internal constructor(
    val bank: PatSynthBank,
    val feeder: PatSynthFeeder,
) : StereoNode(), AutoCloseable {

    /** A tap on this launch's own output, fed from the audio thread. */
    val tap: StereoScope = StereoScope(bank)

    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        tap.render(outL, outR, numFrames, ctx)
    }

    private val offlineContext = SynthContext(bank.sampleRate.toFloat(), OFFLINE_BLOCK)
    private val offlineL = FloatArray(OFFLINE_BLOCK)
    private val offlineR = FloatArray(OFFLINE_BLOCK)
    private var offlineCycle = 0L

    /**
     * Pull [frames] through the bank and into [tap], for when nothing else is pulling it.
     *
     * A machine with no output device gets no audio thread, so the engine never asks the bank for a
     * block and [tap] stays empty. Calling this once per rendered frame with a frame's worth of
     * samples advances the same bank through the same scheduler, and the tap fills with the same
     * audio; the only thing missing is a speaker. That is what a headless documentation build does,
     * and what a test does.
     *
     * Do not call it while the engine is also rendering this launch. Both would advance the voices
     * and the sound would run at double speed, which is the same hazard as sampling a synth graph
     * from the Runloop. [FieldAudioEngine.outputActive] is how a box decides which it is doing.
     */
    fun advanceOffline(frames: Int) {
        require(frames >= 0) { "frames must be >= 0, got $frames" }
        var left = frames
        while (left > 0) {
            val n = minOf(OFFLINE_BLOCK, left)
            java.util.Arrays.fill(offlineL, 0f)
            java.util.Arrays.fill(offlineR, 0f)
            offlineContext.cycleId = ++offlineCycle
            offlineContext.time = bank.frame.toFloat() / bank.sampleRate
            tap.render(offlineL, offlineR, n, offlineContext)
            left -= n
        }
    }

    override fun close() {
        feeder.stop()
        bank.allNotesOff()
    }

    override fun toString(): String = synthPrettyObject(this, "bank" to bank, "feeder" to feeder)

    private companion object {
        /** Chunk [advanceOffline] works in. Larger than any engine buffer, so one frame is one call. */
        const val OFFLINE_BLOCK = 2048
    }
}

/**
 * Play [pattern] through Field's own voices, in realtime.
 *
 * ```kotlin
 * val sc = patSynth(pat("bd ~ sd ~, hh*8"), cps = 2.0).scope()
 * sc.liveSpectrum("spectrum")
 * audio { sc }
 * ```
 *
 * @param cps cycles per second. Two is a comfortable drum tempo; a chord pattern wants less.
 * @param voice the oscillator, envelope and filter every event plays through. Unpitched atoms take
 *   their wave from [patDrumHz] instead.
 * @param voices how many events can sound at once.
 * @param gain a multiplier on top of each event's own `gain` annotation.
 * @param aheadSeconds how far in front of the audio thread the feeder queries.
 */
fun patSynth(
    pattern: PatPattern<String>,
    cps: Double = 1.0,
    voice: PatVoiceSpec = patVoice(),
    voices: Int = 16,
    gain: Double = 1.0,
    sampleRate: Int = 48_000,
    aheadSeconds: Double = 0.4,
): PatSynthLaunch {
    val bank = PatSynthBank(voice = voice, voices = voices, sampleRate = sampleRate)
    val feeder = PatSynthFeeder(bank, pattern, cps, voice, gain, aheadSeconds)
    feeder.start()
    return PatSynthLaunch(bank, feeder)
}

/**
 * Render [pattern] through a [PatSynthBank] offline, block by block, exactly as the engine would.
 *
 * The point of this rather than [patRenderStereo]: what comes back is the realtime path's own
 * output, so a `.wav` in the documentation is a record of what the speakers do. [blockFrames] is the
 * engine buffer size to pretend to have, and the output should not depend on it.
 */
fun patSynthRenderStereo(
    pattern: PatPattern<String>,
    cycles: Int,
    cps: Double = 1.0,
    voice: PatVoiceSpec = patVoice(),
    voices: Int = 16,
    gain: Double = 1.0,
    sampleRate: Int = PatSoundDocs.SR,
    blockFrames: Int = 128,
): PatSoundDocs.Stereo {
    require(cycles > 0) { "cycles must be > 0, got $cycles" }
    require(blockFrames in 1..8192) { "blockFrames must be in 1..8192, got $blockFrames" }
    val frames = Math.round(cycles / cps * sampleRate).toInt()
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    val bank = PatSynthBank(voice = voice, voices = voices, sampleRate = sampleRate)
    // Every cycle up front. The feeder's whole job is to be early, and being infinitely early is
    // the offline case of that.
    for (cycle in 0 until cycles) {
        bank.schedule(
            patSynthNotesForArc(
                pattern, PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)),
                cps, sampleRate, voice, gain, originFrame = 0L,
            ),
        )
    }

    val ctx = SynthContext(sampleRate.toFloat(), blockFrames)
    val blockL = FloatArray(blockFrames)
    val blockR = FloatArray(blockFrames)
    var at = 0
    var cycleId = 1L
    while (at < frames) {
        val n = minOf(blockFrames, frames - at)
        java.util.Arrays.fill(blockL, 0f)
        java.util.Arrays.fill(blockR, 0f)
        ctx.cycleId = cycleId++
        ctx.time = at.toFloat() / sampleRate
        bank.render(blockL, blockR, n, ctx)
        System.arraycopy(blockL, 0, left, at, n)
        System.arraycopy(blockR, 0, right, at, n)
        at += n
    }
    return PatSoundDocs.Stereo(left, right)
}

/**
 * Teach Field that a [PatSynthLaunch] is something a box can be running.
 *
 * [registerFaustLaunchReturnValue]'s shape: register the patch, hold the box open while the run
 * generation is current, and close the launch in a `finally` so the feeder thread goes with it.
 */
@RunsAt(returnValueRegistration, priority = 10)
fun registerPatSynthReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, launchEpoch, notify ->
        val launch = returnValue as? PatSynthLaunch ?: return@handler false

        val handle = FieldAudioEngine.shared().register(audio(launch, gain = 1f))

        inMain {
            val job = kotlin.coroutines.coroutineContext[Job]!!
            if (!inside.registerLaunchJob(job, launchEpoch)) {
                handle.close()
                launch.close()
                notify(false, strand, generation)
                return@inMain
            }
            notify(true, strand, generation)
            try {
                while (inside.runGeneration[strand] == generation) {
                    yield()
                }
            } finally {
                handle.close()
                launch.close()
                inside.jobs.remove(job)
                notify(false, strand, generation)
            }
        }
        true
    }
}

/**
 * Play [pattern] and put a live waveform and spectrum in this box's output.
 *
 * Return the result as the box's last expression, as with [patSynth]: the display is a side effect
 * and the launch is what ties the sound to the box.
 */
fun Box.showPatSynth(
    pattern: PatPattern<String>,
    cps: Double = 1.0,
    voice: PatVoiceSpec = patVoice(),
    channel: String = "main",
): PatSynthLaunch {
    val launch = patSynth(pattern, cps = cps, voice = voice)
    launch.tap.liveSummary(channel)
    return launch
}
