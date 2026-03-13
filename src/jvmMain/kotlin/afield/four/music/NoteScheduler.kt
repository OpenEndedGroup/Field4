package afield.four.music

import afield.four.synth.Buf
import afield.four.synth.Gate
import afield.four.synth.Param
import afield.four.synth.Signal
import afield.four.synth.SynthContext
import java.util.concurrent.atomic.AtomicReference

// ═══════════════════════════════════════════════════════════════════
//  Internal event types (audio-thread private)
// ═══════════════════════════════════════════════════════════════════

/** A scheduled note onset waiting in the lookahead buffer. */
private data class NoteEvent(
    val onsetBeat: Double,
    val offsetBeat: Double,
    val freq: Float,
    val velocity: Float
)

/** A pending note-off keyed by the token minted when its gate was opened. */
private data class PendingOff(
    val offsetBeat: Double,
    val openToken: Long
)

/** Cross-thread handoff: new pattern + the beat at which to apply it. */
private data class PatternSwap(
    val atBeat: Double,    // apply when nextScheduleBeat >= atBeat
    val seq: Sequence<Note>
)

// ═══════════════════════════════════════════════════════════════════
//  NoteScheduler
// ═══════════════════════════════════════════════════════════════════

/**
 * A [Signal] that continuously unrolls a [Sequence]<[Note]> pattern ahead of
 * the audio playhead and fires gate / frequency / velocity events at the right
 * beat boundary.
 *
 * **Rendering contract** – `NoteScheduler` itself outputs *silent zeros* but
 * drives three companion signals:
 *
 * | Property    | Type  | Updated on…     |
 * |-------------|-------|-----------------|
 * | [gate]      | Gate  | each note onset / offset |
 * | [freq]      | Param | each note onset  |
 * | [velocity]  | Param | each note onset  |
 *
 * Plug these into your synth graph and place the scheduler *before* the audio
 * signal in the graph so it fires events first:
 *
 * ```kotlin
 * val clk  = clock(120.0)
 * val sched = clk.scheduler { cycle(listOf(60.n, 64.n, 67.n)) }
 *
 * audio {
 *     // scheduler fires first (outputs 0); oscillator reads updated gate/freq
 *     sched + SineOsc(sched.freq) * AR(gate = sched.gate, release = 0.3.p) * 0.3f
 * }
 * ```
 *
 * **Pattern updates** – call [setPattern] from any thread.  By default the swap
 * is applied at the next measure boundary for glitch-free transitions.
 * Pass `immediate = true` to apply on the very next audio cycle.
 *
 * @param clock           Shared [BeatClock] that provides tempo and beat math.
 * @param lookaheadBeats  How many beats ahead to pre-schedule.  Larger values
 *                        add resilience against scheduling jitter at the cost of
 *                        slower pattern-change latency.  Default: 2 beats.
 * @param gate            Gate signal driven by this scheduler.
 * @param freq            Frequency [Param] driven by this scheduler.
 * @param velocity        Velocity [Param] driven by this scheduler.
 * @param initialPattern  Optional pattern to load immediately on construction.
 */
class NoteScheduler(
    val clock: BeatClock,
    val lookaheadBeats: Double = 2.0,
    val gate: Gate = Gate(),
    val freq: Param = Param(440f),
    val velocity: Param = Param(0.8f),
    initialPattern: Sequence<Note>? = null
) : Signal() {

    // ── cross-thread communication ───────────────────────────────────

    /** Pending pattern swap written by the live-coding thread. */
    private val pendingSwap = AtomicReference<PatternSwap?>(
        initialPattern?.let { PatternSwap(Double.NEGATIVE_INFINITY, it) }
    )

    // ── audio-thread-only state ─────────────────────────────────────

    private var patternIterator: Iterator<Note>? = null
    private var nextScheduleBeat: Double = 0.0
    private var initialized: Boolean = false

    /** Upcoming onsets, kept sorted by [NoteEvent.onsetBeat]. */
    private val onsets = ArrayList<NoteEvent>(32)

    /** Note-offs for currently sounding notes. */
    private val offsets = ArrayList<PendingOff>(16)

    /** Monotonic token; incremented each time the gate is opened.
     *  Used to prevent a stale note-off from closing a gate that a newer note
     *  has already opened. */
    private var gateOpenToken: Long = 0L

    // ── public API ──────────────────────────────────────────────────

    /**
     * Swap the current pattern.
     *
     * @param seq        The new [Sequence]<[Note]> to play (may be infinite via [cycle]).
     * @param immediate  If `true`, apply on the very next audio cycle and clear
     *                   all pre-scheduled future onsets.  If `false` (default),
     *                   apply at the start of the next measure for a seamless
     *                   transition.
     */
    fun setPattern(seq: Sequence<Note>, immediate: Boolean = false) {
        val atBeat = if (immediate) Double.NEGATIVE_INFINITY
        else clock.nextMeasureBeat(clock.currentBeat)
        pendingSwap.set(PatternSwap(atBeat, seq))
    }

    /** Convenience overload that accepts a [List] directly. */
    fun setPattern(notes: List<Note>, immediate: Boolean = false) =
        setPattern(notes.asSequence(), immediate)

    // ── Signal implementation ───────────────────────────────────────

    /**
     * Called by the audio thread once per buffer.
     * Outputs silent zeros while driving [gate], [freq], and [velocity].
     */
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        val currentBeat = clock.beatAt(ctx.time.toDouble())

        // First-call initialization
        if (!initialized) {
            nextScheduleBeat = currentBeat
            initialized = true
        }

        // Publish current beat for display / live-coding reads
        clock.currentBeat = currentBeat

        // ── Apply pending pattern swap ───────────────────────────────
        applyPendingSwap(currentBeat)

        // ── Unroll pattern into lookahead ────────────────────────────
        unroll(currentBeat + lookaheadBeats)

        // ── Beat range covered by this buffer ─────────────────────────
        val bufferEndBeat =
            clock.beatAt((ctx.time + numFrames * ctx.inverseSampleRate).toDouble())

        // ── Fire note onsets ─────────────────────────────────────────
        val onsetIter = onsets.iterator()
        while (onsetIter.hasNext()) {
            val event = onsetIter.next()
            if (event.onsetBeat >= bufferEndBeat) break   // list is sorted; safe to stop
            val token = ++gateOpenToken
            freq.base = event.freq
            velocity.base = event.velocity
            gate.open()
            offsets += PendingOff(event.offsetBeat, token)
            onsetIter.remove()
        }

        // ── Fire note offsets ────────────────────────────────────────
        val offsetIter = offsets.iterator()
        while (offsetIter.hasNext()) {
            val off = offsetIter.next()
            if (off.offsetBeat < bufferEndBeat) {
                // Only close gate if no newer onset has taken over
                if (off.openToken == gateOpenToken) gate.close()
                offsetIter.remove()
            }
        }

        // Output: silent zeros (we are a pure side-effect signal)
        Buf.clear(output, 0, numFrames)
    }

    // ── private helpers ─────────────────────────────────────────────

    private fun applyPendingSwap(currentBeat: Double) {
        val swap = pendingSwap.get() ?: return
        if (nextScheduleBeat >= swap.atBeat) {
            // CAS ensures we don't clobber a newer swap set between get() and CAS
            if (!pendingSwap.compareAndSet(swap, null)) return
            applySwap(swap, currentBeat)
        }
    }

    private fun applySwap(swap: PatternSwap, currentBeat: Double) {
        // Remove all pre-scheduled onsets from the swap point onward
        onsets.removeIf { it.onsetBeat >= swap.atBeat }
        // Start scheduling from the swap beat (but never in the past)
        nextScheduleBeat = maxOf(swap.atBeat, currentBeat)
        patternIterator = swap.seq.iterator()
    }

    private fun unroll(until: Double) {
        val it = patternIterator ?: return
        while (nextScheduleBeat < until) {
            if (!it.hasNext()) return   // finite sequence exhausted
            val note = it.next()
            val dur = note.duration.coerceAtLeast(1e-6)
            if (!note.isRest) {
                insertOnset(
                    NoteEvent(
                        onsetBeat  = nextScheduleBeat,
                        offsetBeat = nextScheduleBeat + dur * NOTE_LEGATO,
                        freq       = note.freq,
                        velocity   = note.velocity
                    )
                )
            }
            nextScheduleBeat += dur
        }
    }

    /** Insert into [onsets] maintaining ascending [NoteEvent.onsetBeat] order. */
    private fun insertOnset(event: NoteEvent) {
        var lo = 0; var hi = onsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (onsets[mid].onsetBeat <= event.onsetBeat) lo = mid + 1 else hi = mid
        }
        onsets.add(lo, event)
    }

    companion object {
        /**
         * Gate-on duration as a fraction of note duration (legato factor).
         * 0.9 leaves a slight gap between consecutive notes to avoid merged ADSRs.
         */
        const val NOTE_LEGATO: Double = 0.9
    }
}
