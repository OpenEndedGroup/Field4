package afield.four.synth

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.Closeable
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A Faust program played polyphonically, by a pattern.
 *
 * [FaustDsp] is one instance of one program: a drone with knobs. This is the other thing a Faust program
 * can be — an *instrument*, N copies of the same compiled DSP allocated to notes, with the notes coming
 * from Field's own pattern language. Nothing in Faust is recompiled to make that happen; a Faust factory
 * built with internal memory keeps its whole state at offset 0 of its own WebAssembly memory, so N
 * `WebAssembly.Instance`s of one module are N independent voices for free.
 *
 * ## One context, N instances — and the measurement that decided it
 *
 * Each [FaustDsp] builds **its own GraalVM [Context]**, and its KDoc records that the remaining 60.4µs of a
 * 128-frame block (against 7.6µs inside `compute`) is dominated by the per-block [Context.enter]. So N
 * voices as N [FaustDsp]s pays that entry N times per block, and the cost of polyphony would be a boundary
 * cost rather than a DSP cost. This class instead hosts every voice in **one** context — one `enter` per
 * block regardless of N — which required a new shim, because [FaustDsp]'s keeps its instance in
 * module-level JS globals and can therefore only ever hold one.
 *
 * The two arrangements are measured against each other by `synth.faust.poly`, which prints the µs-per-block
 * curve for both and asserts a realtime floor on the one shipped here. See `public/docs/faust.md`.
 *
 * ## The notes land on the sample, not on the block
 *
 * A 128-frame block at 48kHz is 2.67ms. Quantising a note onset to the block boundary is audible — it is the
 * difference between a groove and a grid — so [render] does not write parameters once per block. It enters
 * the context once, then walks the block in **chunks that end where the next event begins**: apply the
 * writes due at this exact frame, `compute` up to the next event, repeat. A note therefore begins on the
 * sample it was scheduled for, and `synth.faust.poly` asserts that in samples rather than in milliseconds.
 *
 * This is also why [FaustDsp.setParam] is not used: that queue is drained once per `render` call, which is a
 * block-granularity path by construction and exactly the thing being got around here.
 *
 * ## The controls are the program's own, and it will say so if they are missing
 *
 * Faust's polyphonic convention is `freq` / `gate` / `gain` — the same three names `-nvoices` looks for —
 * and they are discovered out of the program's declared UI by label, never by index. A program that does not
 * declare them is refused by [FaustPolyControls.of] with a message naming what it *did* declare, because the
 * alternative (writing to control 0 and hoping) produces silence, and silence is the hardest bug to read.
 */
class FaustVoiceBank internal constructor(
    val program: FaustProgram,
    val sampleRate: Int,
    val voices: Int,
    /** Discovered `freq`/`gate`/`gain`. */
    val controls: FaustPolyControls,
    /** Every voice's output is summed and multiplied by this on the way out. */
    val mixGain: Double = 1.0,
    /**
     * Below this, for a whole chunk and with its gate down, a voice counts as finished and can be
     * reallocated without cutting a tail.
     *
     * This is the Faust architecture layer's own answer to "is this voice free" — watch the output — rather
     * than a guess about the length of an envelope the host cannot see. Faust does not report its release
     * time, so guessing it would be wrong for every program but the one it was tuned on.
     */
    val idleThreshold: Float = 1e-5f,
    /**
     * How long a stolen voice holds its gate **down** before raising it for the incoming note, in samples.
     *
     * The one place in this class where there is a real trade and no right answer, so it is a knob with both
     * ends measured by `synth.faust.poly`:
     *
     *  * **1 sample (the default).** The stolen note starts essentially on time. It also clicks, and by a
     *     measured amount: Faust's `en.adsr` *resets to zero* on a trigger rather than continuing from the
     *     level it was at, so the outgoing note's amplitude appears as a one-sample step. Measured, the
     *     largest sample-to-sample delta across a steal is **0.99 of the local peak** against 0.36 in the
     *     same note once settled — 2.7×. That is audible, and it is what nearly every hardware polysynth
     *     does on voice overload.
     *  * **longer than the program's release** — `(0.03 * sampleRate)` for a 30ms release. The envelope
     *     reaches zero before the new note begins, so there is no step at all: measured **0.04×**, which is
     *     to say the largest jump across the steal is smaller than the sawtooth's own step in the same
     *     passage. The cost is that the stolen note is that late.
     *
     * A steal is an overload condition either way; the default prefers the note being on time. Set it to the
     * program's release length if you would rather never hear it.
     */
    val stealReleaseFrames: Long = RETRIGGER_FRAMES
) : Closeable {

    companion object {
        /**
         * Largest `compute` this bank will ask for in one call, per voice.
         *
         * Smaller than [FaustDsp.MAX_FRAMES] because the scratch is reserved *per voice* inside that voice's
         * own WebAssembly memory, and because chunks here are usually short anyway — they end at the next
         * note.
         */
        const val MAX_FRAMES: Int = 2048

        /** Voices beyond this are refused: it is a hundredfold past anything measured. */
        const val MAX_VOICES: Int = 128

        /**
         * Default [stealReleaseFrames]: one sample.
         *
         * Enough for a Faust envelope to see a rising edge, since `1, 0, 1` over three samples is one — and
         * the smallest value that makes a stolen note actually attack. It is not enough to avoid the click;
         * see [stealReleaseFrames] for what is, and what that costs.
         */
        const val RETRIGGER_FRAMES: Long = 1L
    }

    /**
     * Build a bank for [program]. Throws with a readable message if the program is not playable
     * polyphonically — see [FaustPolyControls.of].
     */
    constructor(
        program: FaustProgram,
        sampleRate: Int,
        voices: Int = 8,
        mixGain: Double = 1.0,
        stealReleaseFrames: Long = RETRIGGER_FRAMES
    ) : this(
        program, sampleRate, voices, FaustPolyControls.of(program), mixGain,
        stealReleaseFrames = stealReleaseFrames
    )

    private val closed = AtomicBoolean(false)

    private val context: Context = Context.newBuilder("js", "wasm")
        .engine(FaustEngine.sharedEngine)
        .allowExperimentalOptions(true)
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { true }
        .allowAllAccess(true)
        .allowPolyglotAccess(PolyglotAccess.ALL)
        .option("js.webassembly", "true")
        .option("wasm.UseUnsafeMemory", "true")
        .build()

    /** Channel counts as the compiled module reports them. */
    val numInputs: Int
    val numOutputs: Int

    private val fnCompute: Value
    private val fnSetParam: Value
    private val fnGetParam: Value

    // One entry per voice: its memory as a host-readable buffer, and where its output scratch starts.
    private val voiceBuffers: Array<Value>
    private val voiceOutBase: LongArray

    private val scratch = ByteArray(MAX_FRAMES * 4)
    private val scratchFloats = java.nio.ByteBuffer.wrap(scratch)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .asFloatBuffer()
    private val voiceL = FloatArray(MAX_FRAMES)
    private val voiceR = FloatArray(MAX_FRAMES)

    // ── the schedule ───────────────────────────────────────────────────────────────────────────

    /** Notes not yet started, earliest first. Guarded by [scheduleLock]; fed from any thread. */
    private val pending = PriorityQueue<FaustNote>(64, compareBy({ it.startFrame }, { it.hz }))

    /** Shared-parameter changes not yet applied, earliest first. Guarded by [scheduleLock]. */
    private val pendingAutomation = PriorityQueue<FaustParamChange>(32, compareBy { it.frame })
    private val scheduleLock = Any()

    private val frameCounter = AtomicLong(0)

    /** Absolute frames rendered so far — the bank's clock, and the only clock a note is timed against. */
    val frame: Long get() = frameCounter.get()

    private class VoiceState {
        var gateOn = false
        var noteOnFrame = Long.MIN_VALUE
        var endFrame = Long.MAX_VALUE
        var hz = 0.0
        /** True once the voice's own output has fallen below the idle threshold with its gate down. */
        var silent = true
        var note: FaustNote? = null

        /** Frame at which a stolen voice raises its gate again; [Long.MAX_VALUE] when it is not stealing. */
        var retriggerAt = Long.MAX_VALUE
        var pendingHz = 0.0
        var pendingGain = 0.0
        var pendingEnd = Long.MAX_VALUE
    }

    private val state = Array(voices) { VoiceState() }

    /** How many gate-on voices were cut short because every voice was busy. Ideally zero. */
    @Volatile
    var steals: Long = 0L
        private set

    /** How many *released but still ringing* voices were reused. Audible as a shortened tail, not a cut note. */
    @Volatile
    var tailCuts: Long = 0L
        private set

    /** Notes that arrived after the frame they were meant to start on, and were played immediately. */
    @Volatile
    var lateNotes: Long = 0L
        private set

    /** Notes started. */
    @Volatile
    var notesPlayed: Long = 0L
        private set

    /** The greatest number of voices sounding at once, so far. The honest measure of polyphony reached. */
    @Volatile
    var peakConcurrency: Int = 0
        private set

    init {
        require(voices in 1..MAX_VOICES) { "FaustVoiceBank: voices must be in 1..$MAX_VOICES, got $voices" }
        context.enter()
        try {
            context.eval(source(FAUST_POLY_SHIM))
            val bindings = context.getBindings("js")
            bindings.putMember("__polyWasmBytes", program.wasm)

            val setup = context.eval(
                source(
                    "__faustPolySetup($voices, $sampleRate, ${program.ui.inputs}, " +
                        "${program.ui.outputs}, $MAX_FRAMES)"
                )
            )
            val error = setup.getMember("error")
            if (!error.isNull) {
                error("Faust: could not instantiate ${voices} voices of '${program.name}': ${error.asString()}")
            }
            numInputs = setup.getMember("inputs").asInt()
            numOutputs = setup.getMember("outputs").asInt()
            if (numOutputs == 0) {
                error("Faust: '${program.name}' has no audio outputs — `process` must produce at least one signal")
            }
            if (numInputs > 0) {
                // An effect has nothing to be polyphonic about, and silently feeding it silence would look
                // like a broken instrument rather than a misuse.
                error(
                    "Faust: '${program.name}' declares $numInputs audio input(s), so it is an effect rather " +
                        "than an instrument — play it with faust(source, input = …) and put the polyphonic " +
                        "instrument on its input"
                )
            }

            fnCompute = bindings.getMember("__faustPolyCompute")
            fnSetParam = bindings.getMember("__faustPolySetParam")
            fnGetParam = bindings.getMember("__faustPolyGetParam")

            voiceOutBase = LongArray(voices)
            voiceBuffers = Array(voices) { v ->
                val offsets = context.eval(source("__faustPolyOffsets($v)"))
                voiceOutBase[v] = offsets.getMember("outBase").asLong()
                val buffer = context.eval(source("__faustPolyBuffer($v)"))
                // The polyphonic path needs the bulk read; the per-sample JS fallback that FaustDsp keeps
                // would cost N times as much and there is no reason to ship a version of this that is
                // twenty times slower without saying so.
                check(buffer.hasBufferElements()) {
                    "Faust: this GraalVM runtime does not expose WebAssembly memory as a host buffer, which " +
                        "the polyphonic path needs; a single voice still works through faust(...)"
                }
                buffer.readBuffer(voiceOutBase[v], scratch, 0, 4)
                buffer
            }

            // Every voice starts silent: gate down, and the declared gain applied so a note-on only has to
            // write freq and gate.
            for (v in 0 until voices) {
                fnSetParam.execute(v, controls.gate.index, 0f)
            }
        } catch (t: Throwable) {
            runCatching { context.leave() }
            runCatching { context.close(true) }
            throw t
        }
        runCatching { context.leave() }
    }

    // ── scheduling ─────────────────────────────────────────────────────────────────────────────

    /**
     * Schedule [notes] for their own frames on this bank's clock.
     *
     * Safe from any thread and at any time: a note whose frame has already passed is played at the next
     * opportunity and counted in [lateNotes], so a feeder that falls behind loses time rather than notes.
     */
    fun schedule(notes: Collection<FaustNote>) {
        if (notes.isEmpty() || closed.get()) return
        synchronized(scheduleLock) { pending.addAll(notes) }
    }

    /** Schedule one note. */
    fun schedule(note: FaustNote) = schedule(listOf(note))

    /**
     * Schedule a note starting [afterSeconds] from now, for [seconds].
     *
     * The convenience form, for playing an instrument from code rather than from a pattern.
     */
    fun play(hz: Double, seconds: Double = 0.25, gain: Double = 1.0, afterSeconds: Double = 0.0) {
        val start = frame + (afterSeconds * sampleRate).roundToLong()
        schedule(FaustNote(start, start + (seconds * sampleRate).roundToLong().coerceAtLeast(1L), hz, gain))
    }

    /**
     * Schedule shared-parameter changes for their own frames — a cutoff sweep, a resonance, a detune.
     *
     * The other half of "a pattern plays it". Notes are one kind of scheduled event and a parameter change
     * is another; both go through the same timeline, so a filter that opens on the beat opens on the *sample*
     * of the beat rather than up to 2.67ms after it. `automationApplied` counts what landed.
     */
    fun automate(changes: Collection<FaustParamChange>) {
        if (changes.isEmpty() || closed.get()) return
        synchronized(scheduleLock) { pendingAutomation.addAll(changes) }
    }

    /** Shared-parameter changes applied so far. */
    @Volatile
    var automationApplied: Long = 0L
        private set

    /** Notes scheduled but not yet started. */
    fun pendingCount(): Int = synchronized(scheduleLock) { pending.size }

    /** Drop every note and parameter change that has not happened yet. Sounding notes finish. */
    fun clearSchedule() {
        synchronized(scheduleLock) {
            pending.clear()
            pendingAutomation.clear()
        }
    }

    /** Gate every voice down now, and forget the schedule. */
    fun allNotesOff() {
        clearSchedule()
        if (closed.get()) return
        context.enter()
        try {
            for (v in 0 until voices) {
                val s = state[v]
                s.retriggerAt = Long.MAX_VALUE
                if (s.gateOn) {
                    fnSetParam.execute(v, controls.gate.index, 0f)
                    s.gateOn = false
                    s.endFrame = Long.MAX_VALUE
                }
            }
        } finally {
            runCatching { context.leave() }
        }
    }

    /**
     * Set a control on **every** voice — a cutoff, a resonance, anything that is not per-note.
     *
     * This is the parameter half of "a pattern plays it": [FaustPatternVoice] patterns notes, and a
     * [PatPattern] of numbers can pattern this.
     */
    fun setAll(control: FaustControl, value: Double) {
        if (closed.get()) return
        val clamped = value.coerceIn(control.min, control.max).toFloat()
        context.enter()
        try {
            for (v in 0 until voices) fnSetParam.execute(v, control.index, clamped)
        } finally {
            runCatching { context.leave() }
        }
    }

    /** Set a control by label on every voice. Silently ignores an unknown name. */
    operator fun set(label: String, value: Double) {
        program.ui.writable.firstOrNull {
            it.label == label || it.qualifiedLabel == label || it.address == label
        }?.let { setAll(it, value) }
    }

    // ── rendering ──────────────────────────────────────────────────────────────────────────────

    /**
     * Render `numFrames` of the mix, advancing the bank's clock by exactly that many frames.
     *
     * One [Context.enter] for the whole call however many voices sound and however many notes start inside
     * it; the block is split at event frames, not at parameter-queue boundaries.
     */
    fun render(outL: FloatArray, outR: FloatArray, numFrames: Int) {
        java.util.Arrays.fill(outL, 0, numFrames, 0f)
        java.util.Arrays.fill(outR, 0, numFrames, 0f)
        if (closed.get() || numFrames <= 0) return

        context.enter()
        try {
            var done = 0
            while (done < numFrames) {
                val now = frameCounter.get() + done
                applyDue(now)

                var chunk = min(MAX_FRAMES, numFrames - done)
                val next = nextEventFrame(now)
                if (next != Long.MAX_VALUE) {
                    // Clamped before narrowing: a note with no end in sight is a legitimate schedule, and
                    // `(next - now).toInt()` on a far-future frame would overflow into a negative chunk.
                    chunk = min(chunk, (next - now).coerceAtMost(MAX_FRAMES.toLong()).toInt())
                }
                chunk = max(1, chunk)

                mixChunk(outL, outR, done, chunk)
                done += chunk
            }
            frameCounter.addAndGet(numFrames.toLong())
        } catch (t: Throwable) {
            java.util.Arrays.fill(outL, 0, numFrames, 0f)
            java.util.Arrays.fill(outR, 0, numFrames, 0f)
            if (closed.compareAndSet(false, true)) {
                println("Faust: '${program.name}' failed while rendering polyphonically and was stopped: $t")
            }
        } finally {
            // try/catch rather than `runCatching`, which builds a `Result` — this is the audio thread, and
            // an allocation here is a GC pause in the audio path rather than a performance nit.
            try {
                context.leave()
            } catch (ignored: Throwable) {
                // Leaving a context that was never entered is not worth taking the audio thread down for.
            }
        }
    }

    /**
     * Start every note due at [now] and end every note whose time is up. Caller has entered the context.
     *
     * Both halves happen at the same instant, which is what makes a legato pattern work: the note-off of the
     * outgoing voice and the note-on of the incoming one are written in the same chunk boundary, so nothing
     * is quantised relative to anything else.
     */
    private fun applyDue(now: Long) {
        // Stolen voices raise their gate again first: their rising edge is due at this frame and the
        // envelope has to see it before anything else writes to the voice.
        for (v in 0 until voices) {
            val s = state[v]
            if (s.retriggerAt <= now) {
                fnSetParam.execute(v, controls.freq.index, clamp(controls.freq, s.pendingHz))
                val gainControl = controls.gain
                if (gainControl != null) fnSetParam.execute(v, gainControl.index, clamp(gainControl, s.pendingGain))
                fnSetParam.execute(v, controls.gate.index, 1f)
                s.gateOn = true
                s.noteOnFrame = s.retriggerAt
                s.endFrame = max(now + 1, s.pendingEnd)
                s.hz = s.pendingHz
                s.silent = false
                s.retriggerAt = Long.MAX_VALUE
            }
        }
        while (true) {
            val change = synchronized(scheduleLock) {
                val head = pendingAutomation.peek() ?: return@synchronized null
                if (head.frame > now) null else pendingAutomation.poll()
            } ?: break
            val clamped = change.value.coerceIn(change.control.min, change.control.max).toFloat()
            for (v in 0 until voices) fnSetParam.execute(v, change.control.index, clamped)
            automationApplied++
        }
        for (v in 0 until voices) {
            val s = state[v]
            if (s.gateOn && s.endFrame <= now) {
                fnSetParam.execute(v, controls.gate.index, 0f)
                s.gateOn = false
            }
        }
        while (true) {
            val note = synchronized(scheduleLock) {
                val head = pending.peek() ?: return@synchronized null
                if (head.startFrame > now) null else pending.poll()
            } ?: break
            if (note.startFrame < now) lateNotes++
            startNote(note, now)
        }
        var sounding = 0
        for (v in 0 until voices) if (state[v].gateOn) sounding++
        if (sounding > peakConcurrency) peakConcurrency = sounding
    }

    /**
     * Allocate a voice for [note] and gate it on. Caller has entered the context.
     *
     * The policy, stated because a voice allocator without a stated policy is a guess:
     *
     *  1. a voice that is **finished** — gate down and its own output below [idleThreshold] — preferred
     *     always, and picked round-robin-ish (lowest index) so a repeated single note reuses one voice and
     *     the rest stay cold;
     *  2. else a voice that is **released but still ringing** — gate down, tail audible. Reusing it cuts
     *     that tail, counted as [tailCuts]; the one whose note started longest ago is chosen, which for
     *     released voices is the quietest tail;
     *  3. else **steal the oldest sounding voice** and count it in [steals]. A note is never dropped: an
     *     instrument that silently ignores a note is worse than one that runs out of voices audibly.
     *
     * ## Stealing drops the gate for [stealReleaseFrames] samples, and that is not cosmetic
     *
     * Writing `freq` to a voice whose gate is already up does **not** restart its envelope: Faust's
     * `en.adsr` and friends trigger on a *rising edge*, so `gate = 1` over an already-high gate is a no-op.
     * The first version of this did exactly that, and it was measured: no click at all — the phasor and the
     * envelope both stay continuous, so `max |x[i] - x[i-1]|` across a steal was **1.03×** the same
     * statistic in a steal-free passage — but also **no attack**. The stolen note simply glided in at the
     * old envelope level, which is not a note.
     *
     * So a steal writes `gate = 0` now and raises it [stealReleaseFrames] samples later, giving the
     * envelope a real rising edge — and at the default of one sample it *does* click, because Faust's
     * `en.adsr` resets to zero on a trigger rather than continuing from where it was. That click is measured
     * (0.99 of the local peak against 0.36 settled, 2.7×) and [stealReleaseFrames] is the knob that removes
     * it at the cost of latency. See its KDoc for both numbers.
     */
    private fun startNote(note: FaustNote, now: Long) {
        // 1. a finished voice: gate down and no longer moving air.
        var chosen = -1
        for (v in 0 until voices) {
            val s = state[v]
            if (!s.gateOn && s.silent && s.retriggerAt == Long.MAX_VALUE) { chosen = v; break }
        }
        // 2. else a released voice whose tail is still audible.
        if (chosen < 0) {
            var oldest = Long.MAX_VALUE
            for (v in 0 until voices) {
                val s = state[v]
                if (!s.gateOn && s.retriggerAt == Long.MAX_VALUE && s.noteOnFrame < oldest) {
                    oldest = s.noteOnFrame
                    chosen = v
                }
            }
            if (chosen >= 0) tailCuts++
        }
        if (chosen >= 0) {
            val s = state[chosen]
            fnSetParam.execute(chosen, controls.freq.index, clamp(controls.freq, note.hz))
            val gainControl = controls.gain
            if (gainControl != null) fnSetParam.execute(chosen, gainControl.index, clamp(gainControl, note.gain))
            // Gate last: freq and gain must be in place on the sample the envelope opens.
            fnSetParam.execute(chosen, controls.gate.index, 1f)
            s.gateOn = true
            s.noteOnFrame = now
            s.endFrame = max(now + 1, note.endFrame)
            s.hz = note.hz
            s.silent = false
            s.note = note
            notesPlayed++
            return
        }

        // 3. else steal the oldest sounding voice, gate down now and up again a sample later.
        var oldest = Long.MAX_VALUE
        for (v in 0 until voices) {
            val s = state[v]
            if (s.gateOn && s.noteOnFrame < oldest) {
                oldest = s.noteOnFrame
                chosen = v
            }
        }
        if (chosen >= 0) {
            steals++
            val s = state[chosen]
            fnSetParam.execute(chosen, controls.gate.index, 0f)
            s.gateOn = false
            s.retriggerAt = now + stealReleaseFrames.coerceAtLeast(1L)
            s.pendingHz = note.hz
            s.pendingGain = note.gain
            s.pendingEnd = note.endFrame
            s.silent = false
            s.note = note
            notesPlayed++
            return
        }

        // 4. every voice is already mid-steal — which happens when a chord larger than the bank arrives on
        //    one frame. Supersede the pending note on whichever retriggers soonest, rather than dropping
        //    this one: the last note of an over-large chord is the one a person hears as "the note".
        var soonest = Long.MAX_VALUE
        for (v in 0 until voices) {
            if (state[v].retriggerAt < soonest) {
                soonest = state[v].retriggerAt
                chosen = v
            }
        }
        steals++
        val s = state[chosen]
        s.pendingHz = note.hz
        s.pendingGain = note.gain
        s.pendingEnd = note.endFrame
        s.note = note
        notesPlayed++
    }

    private fun clamp(control: FaustControl, value: Double): Float =
        value.coerceIn(control.min, control.max).toFloat()

    /**
     * The next frame at which anything must happen, strictly after [now]; [Long.MAX_VALUE] when nothing
     * does.
     *
     * A sentinel rather than a `Long?` on purpose: this runs once per chunk on the audio thread, and a
     * nullable `Long` boxes. Measured — see `synth.faust.poly`'s allocation check, which asserts
     * `render` allocates nothing at all per block.
     */
    private fun nextEventFrame(now: Long): Long {
        var next = Long.MAX_VALUE
        // Two peeks in one critical section, assigned to locals rather than returned as a Pair, which is
        // the other thing that used to allocate here.
        synchronized(scheduleLock) {
            val note = pending.peek()
            if (note != null && note.startFrame > now) next = note.startFrame
            val change = pendingAutomation.peek()
            if (change != null && change.frame > now && change.frame < next) next = change.frame
        }
        for (v in 0 until voices) {
            val s = state[v]
            if (s.retriggerAt > now && s.retriggerAt < next) next = s.retriggerAt
            if (s.gateOn && s.endFrame > now && s.endFrame < next) next = s.endFrame
        }
        return next
    }

    /** Compute and sum every audible voice for `count` frames. Caller has entered the context. */
    private fun mixChunk(outL: FloatArray, outR: FloatArray, offset: Int, count: Int) {
        val gain = mixGain.toFloat()
        for (v in 0 until voices) {
            val s = state[v]
            if (!s.gateOn && s.silent) continue

            fnCompute.execute(v, count)
            readChannel(v, 0, voiceL, count)
            if (numOutputs > 1) readChannel(v, 1, voiceR, count) else System.arraycopy(voiceL, 0, voiceR, 0, count)

            var peak = 0f
            for (i in 0 until count) {
                val l = voiceL[i]
                val r = voiceR[i]
                outL[offset + i] += l * gain
                outR[offset + i] += r * gain
                val a = max(abs(l), abs(r))
                if (a > peak) peak = a
            }
            // A voice is finished only when its gate is down *and* it has actually stopped moving air.
            s.silent = !s.gateOn && peak < idleThreshold
        }
    }

    private fun readChannel(voice: Int, channel: Int, out: FloatArray, count: Int) {
        val byteOffset = voiceOutBase[voice] + channel.toLong() * MAX_FRAMES * 4L
        voiceBuffers[voice].readBuffer(byteOffset, scratch, 0, count * 4)
        scratchFloats.position(0)
        scratchFloats.get(out, 0, count)
    }

    /** How many voices are sounding right now (gate up, or still ringing). */
    fun activeVoices(): Int = (0 until voices).count { state[it].gateOn || !state[it].silent }

    /** Which pitch a voice was last given, in Hz, or 0 if it has never played. */
    fun voiceHz(voice: Int): Double = state[voice].hz

    /**
     * Read a control back out of one voice's WebAssembly memory.
     *
     * The only way to check that a write landed where it was meant to rather than on whichever control
     * happened to sort first — which is what `synth.faust.poly` uses it for.
     */
    fun paramValue(voice: Int, control: FaustControl): Float {
        require(voice in 0 until voices) { "voice must be in 0..${voices - 1}" }
        if (closed.get()) return 0f
        context.enter()
        try {
            return fnGetParam.execute(voice, control.index).asFloat()
        } finally {
            runCatching { context.leave() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { context.close(true) }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "program" to program.name,
        "voices" to voices,
        "sampleRate" to sampleRate,
        "notesPlayed" to notesPlayed,
        "steals" to steals,
        "peakConcurrency" to peakConcurrency
    )

    private fun source(code: String): Source =
        Source.newBuilder("js", code, "faust-poly.js").buildLiteral()
}

/**
 * One note for a [FaustVoiceBank]: when, for how long, at what pitch.
 *
 * Times are **absolute frames on the bank's own clock**, not seconds, because that is the only clock in the
 * system that cannot drift against the audio. A pattern's rational cycle time is converted to a frame once,
 * by [faustPatternNotes], and everything downstream is integer arithmetic.
 */
data class FaustNote(
    val startFrame: Long,
    val endFrame: Long,
    /** Pitch in Hz, written to the program's declared `freq` control (clamped to its declared range). */
    val hz: Double,
    /** Written to the program's declared `gain` control, if it declares one. */
    val gain: Double = 1.0,
    /** What the pattern atom said, carried through for debugging and for plots. */
    val label: String = ""
) {
    init {
        require(endFrame > startFrame) { "FaustNote: endFrame must be > startFrame" }
        require(hz.isFinite() && hz > 0.0) { "FaustNote: hz must be finite and > 0, got $hz" }
    }

    val lengthFrames: Long get() = endFrame - startFrame

    override fun toString(): String =
        "FaustNote(${startFrame}..$endFrame, ${"%.2f".format(hz)}Hz, gain=${"%.2f".format(gain)}" +
            (if (label.isBlank()) "" else ", '$label'") + ")"
}

/**
 * One shared-parameter change, on a frame of the bank's clock.
 *
 * Written to **every** voice, because these are the front-panel controls rather than the per-note ones — a
 * cutoff, a resonance, a reverb mix. Same timeline as the notes, so the same sample accuracy.
 */
data class FaustParamChange(val frame: Long, val control: FaustControl, val value: Double) {
    override fun toString(): String = "FaustParamChange(@$frame ${control.label}=${"%.3f".format(value)})"
}

/**
 * The three controls a polyphonic Faust program has to declare, found by **label**.
 *
 * `freq` / `gate` / `gain` is Faust's own convention — the same three names its `-nvoices` architecture
 * looks for — so a program written for `faust2jaqt -nvoices 8` plays here unchanged.
 *
 * Two things make finding them by label rather than by position mandatory rather than tidy. Faust lists
 * controls **alphabetically within each group**, so `cutoff` comes before `freq` however the source was
 * written and index 0 is whatever happens to sort first. And Faust wraps every program in an implicit
 * `vgroup` named after the module — which here is the box id — so a qualified label would read
 * `box_a1b2c3/freq`; [FaustUi.parse] already drops that wrapper, which is why the bare label works.
 */
data class FaustPolyControls(
    val freq: FaustControl,
    val gate: FaustControl,
    /** Optional: a program may bake its own level in. */
    val gain: FaustControl?
) {
    companion object {
        /**
         * Discover the three controls, or refuse with a message a person can act on.
         *
         * @throws IllegalStateException naming what is missing, what the program *did* declare, and the one
         *   line of Faust that would fix it. The alternative — writing to control index 0 — produces
         *   silence, and there is nothing in silence to read.
         */
        fun of(program: FaustProgram): FaustPolyControls {
            val writable = program.ui.writable
            val freq = find(writable, "freq")
            val gate = find(writable, "gate")
            if (freq != null && gate != null) return FaustPolyControls(freq, gate, find(writable, "gain"))

            val missing = listOfNotNull(
                if (freq == null) "freq" else null,
                if (gate == null) "gate" else null
            )
            val declared = if (writable.isEmpty()) "nothing" else writable.joinToString { "${it.label} (${it.type.faustName})" }
            val fix = buildString {
                if (freq == null) append("\n  freq = hslider(\"freq [unit:Hz]\", 220, 40, 4000, 0.001);")
                if (gate == null) {
                    append("\n  gate = button(\"gate\");")
                    append("\n  … and gate the envelope with it, e.g. en.adsr(0.005, 0.08, 0.6, 0.05, gate)")
                }
            }
            error(
                "Faust: '${program.name}' cannot be played polyphonically — a polyphonic program must " +
                    "declare ${missing.joinToString(" and ") { "`$it`" }}, which is Faust's own convention " +
                    "(the same names `-nvoices` looks for). It declares: $declared.$fix"
            )
        }

        /**
         * Faust lists controls alphabetically, so this matches on the name and never on the position. The
         * address is checked too, so a program that puts its voice controls in a group still works.
         */
        private fun find(controls: List<FaustControl>, name: String): FaustControl? =
            controls.firstOrNull { it.label.equals(name, ignoreCase = true) }
                ?: controls.firstOrNull { it.qualifiedLabel.equals(name, ignoreCase = true) }
                ?: controls.firstOrNull { it.address.substringAfterLast('/').equals(name, ignoreCase = true) }
    }
}

/**
 * A [FaustVoiceBank] as a node in Field's audio graph.
 *
 * [StereoNode] rather than [StereoRenderable] for the same reason [FaustNode] is: the bank is stateful and
 * advances its own clock, so rendering it twice in one block must not advance it twice.
 */
class FaustPolyNode(val bank: FaustVoiceBank) : StereoNode() {
    override fun generateStereo(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        bank.render(outL, outR, numFrames)
    }
}

// The polyphonic shim. Deliberately a separate program from FAUST_DSP_SHIM rather than a generalisation of
// it: that one is the measured single-voice path and there is no reason to put an array index in front of
// every access on it.
//
// The important line is `new WebAssembly.Module(bytes)` *once*, outside the loop, and
// `new WebAssembly.Instance(module, env)` N times inside it. Compiling the module is the expensive half and
// it is shared; the instances are independent DSP states because Faust's wasm factory is built with
// internal memory, so `compute(0, …)` addresses a struct at offset 0 of each instance's own memory.
private const val FAUST_POLY_SHIM: String = """
var __polyVoices = [];
var __polyMaxFrames = 0, __polyInputs = 0, __polyOutputs = 0;

// Identical table to FAUST_DSP_SHIM's: Faust's wasm backend imports scalar math from `env`, and which
// entries a program needs depends on the program.
function __faustPolyEnv() {
  var rem = function (a, b) { return a - Math.round(a / b) * b; };
  var copysign = function (x, y) { return (y < 0) !== (x < 0) ? -x : x; };
  var env = {
    _abs: Math.abs, _acosf: Math.acos, _asinf: Math.asin, _atanf: Math.atan, _atan2f: Math.atan2,
    _ceilf: Math.ceil, _cosf: Math.cos, _expf: Math.exp, _exp2f: function (x) { return Math.pow(2, x); },
    _exp10f: function (x) { return Math.pow(10, x); }, _floorf: Math.floor,
    _fmodf: function (a, b) { return a % b; }, _logf: Math.log, _log2f: Math.log2, _log10f: Math.log10,
    _max_f: Math.max, _min_f: Math.min, _powf: Math.pow, _remainderf: rem, _rintf: Math.round,
    _roundf: Math.round, _sinf: Math.sin, _sqrtf: Math.sqrt, _tanf: Math.tan, _truncf: Math.trunc,
    _acoshf: Math.acosh, _asinhf: Math.asinh, _atanhf: Math.atanh,
    _coshf: Math.cosh, _sinhf: Math.sinh, _tanhf: Math.tanh,
    _isnanf: function (x) { return isNaN(x) ? 1 : 0; },
    _isinff: function (x) { return !isFinite(x) && !isNaN(x) ? 1 : 0; },
    _copysignf: copysign
  };
  var single = Object.keys(env);
  for (var i = 0; i < single.length; i++) {
    var name = single[i];
    if (name.charAt(name.length - 1) === "f") env[name.substring(0, name.length - 1)] = env[name];
  }
  env._max_ = Math.max;
  env._min_ = Math.min;
  env.table = new WebAssembly.Table({ initial: 0, element: "anyfunc" });
  return env;
}

function __faustPolySetup(voices, sampleRate, declaredInputs, declaredOutputs, maxFrames) {
  try {
    var bytes = new Uint8Array(Java.from(__polyWasmBytes));
    var module = new WebAssembly.Module(bytes);
    __polyMaxFrames = maxFrames;
    __polyVoices = [];
    for (var v = 0; v < voices; v++) {
      var instance = new WebAssembly.Instance(module, { env: __faustPolyEnv() });
      var ex = instance.exports;
      var mem = ex.memory;
      var ins = ex.getNumInputs ? ex.getNumInputs(0) : declaredInputs;
      var outs = ex.getNumOutputs ? ex.getNumOutputs(0) : declaredOutputs;
      var channels = Math.max(1, Math.max(ins, outs));
      var ptrBytes = 2 * channels * 4;
      var audioBytes = 2 * channels * maxFrames * 4;
      // memory.grow detaches every existing view, so it happens before any view is made — once, here.
      if (((mem.buffer.byteLength - (ptrBytes + audioBytes + 64)) | 0) < 0) {
        mem.grow(Math.ceil((ptrBytes + audioBytes + 65536) / 65536));
      }
      var total = mem.buffer.byteLength;
      var scratch = (total - (ptrBytes + audioBytes) - 16) & ~15;
      var outPtrArray = scratch;
      var inPtrArray = scratch + channels * 4;
      var outBase = (inPtrArray + channels * 4 + 15) & ~15;
      var inBase = outBase + channels * maxFrames * 4;
      var ptrs = new Int32Array(mem.buffer, outPtrArray, 2 * channels);
      for (var c = 0; c < channels; c++) {
        ptrs[c] = outBase + c * maxFrames * 4;
        ptrs[channels + c] = inBase + c * maxFrames * 4;
      }
      ex.init(0, sampleRate);
      __polyVoices.push({
        exports: ex, memory: mem,
        outPtrArray: outPtrArray, inPtrArray: inPtrArray,
        outBase: outBase, inBase: inBase
      });
      __polyInputs = ins;
      __polyOutputs = outs;
    }
    return { error: null, inputs: __polyInputs, outputs: __polyOutputs, voices: __polyVoices.length };
  } catch (e) {
    return { error: String(e) + (e && e.stack ? " | " + e.stack : ""), inputs: 0, outputs: 0, voices: 0 };
  }
}

function __faustPolyCompute(v, count) {
  var i = __polyVoices[v];
  i.exports.compute(0, count, i.inPtrArray, i.outPtrArray);
}

function __faustPolySetParam(v, index, value) { __polyVoices[v].exports.setParamValue(0, index, value); }
function __faustPolyGetParam(v, index) { return __polyVoices[v].exports.getParamValue(0, index); }
function __faustPolyBuffer(v) { return __polyVoices[v].memory.buffer; }
function __faustPolyOffsets(v) { return { outBase: __polyVoices[v].outBase, inBase: __polyVoices[v].inBase }; }
"""
