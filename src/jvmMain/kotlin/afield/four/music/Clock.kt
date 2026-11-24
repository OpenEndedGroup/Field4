package afield.four.music

import kotlin.math.floor

// ═══════════════════════════════════════════════════════════════════
//  BeatClock – musical time reference
// ═══════════════════════════════════════════════════════════════════

/**
 * Musical time source: converts between wall-clock seconds and beats.
 *
 * [BeatClock] is the shared timing reference for all [NoteScheduler] instances.
 * The audio thread writes [currentBeat] each cycle; the live-coding thread may
 * freely read it and update [bpm].
 *
 * @param bpm              Beats per minute (clamped to 1–999).
 * @param beatsPerMeasure  Beats per measure (bar).  Default 4 (common time).
 */
class BeatClock(
    bpm: Double = 120.0,
    val beatsPerMeasure: Int = 4
) {
    /** Tempo in beats per minute.  Thread-safe: write from any thread. */
    @Volatile
    var bpm: Double = bpm.coerceIn(1.0, 999.0)
        set(value) { field = value.coerceIn(1.0, 999.0) }

    /**
     * Current beat position as last reported by the scheduler running on the
     * audio thread.  Safe to read from any thread for display / live coding.
     */
    @Volatile
    var currentBeat: Double = 0.0
        internal set

    // ── conversions ─────────────────────────────────────────────────

    /** Beat position for a given time in seconds (relative to engine start). */
    fun beatAt(timeSec: Double): Double = timeSec * bpm / 60.0

    /** Seconds per beat at the current [bpm]. */
    val secsPerBeat: Double get() = 60.0 / bpm

    // ── structural helpers ───────────────────────────────────────────

    /** Zero-indexed measure number that contains [beat]. */
    fun measureOf(beat: Double): Long = floor(beat / beatsPerMeasure).toLong()

    /** Beat at the start of the **next** measure after [beat]. */
    fun nextMeasureBeat(beat: Double): Double =
        (measureOf(beat) + 1) * beatsPerMeasure.toDouble()

    /** Beat at the start of the **next** whole beat after [beat] (ceiling). */
    fun nextWholeBeat(beat: Double): Double = floor(beat) + 1.0

    /** Fractional position within the current beat in [0, 1). */
    fun beatPhase(beat: Double): Double = beat - floor(beat)

    /** Fractional position within the current measure in [0, 1). */
    fun measurePhase(beat: Double): Double =
        (beat % beatsPerMeasure) / beatsPerMeasure

    override fun toString(): String = "BeatClock(bpm=$bpm, beatsPerMeasure=$beatsPerMeasure)"
}
