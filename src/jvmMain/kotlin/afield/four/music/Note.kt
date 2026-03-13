package afield.four.music

import kotlin.math.pow

// ═══════════════════════════════════════════════════════════════════
//  MIDI utilities
// ═══════════════════════════════════════════════════════════════════

/** Convert a MIDI note number to frequency in Hz.  A4 = note 69 = 440 Hz. */
fun midiToHz(note: Int): Float =
    (440.0 * 2.0.pow((note - 69) / 12.0)).toFloat()

/** Nearest MIDI note number for the given frequency in Hz. */
fun hzToMidi(freq: Float): Int =
    (12.0 * kotlin.math.log2(freq / 440.0) + 69).toInt()

// ═══════════════════════════════════════════════════════════════════
//  Note – a single pitched event in beat time
// ═══════════════════════════════════════════════════════════════════

/**
 * A single musical note (or rest) expressed in beat time.
 *
 * @param pitch     MIDI note number 0–127.  Use [Note.REST] (or the top-level
 *                  [rest] / [rest(duration)] helpers) for a rest.
 * @param velocity  Amplitude 0.0–1.0.  Available via [NoteScheduler.velocity].
 * @param duration  Duration in **beats**.  1.0 = one beat; 0.5 = an eighth
 *                  note in 4/4; 0.25 = a sixteenth note.
 * @param channel   Voice/channel index for polyphonic routing.  Ignored by the
 *                  monophonic [NoteScheduler] but forwarded for custom handlers.
 */
data class Note(
    val pitch: Int,
    val velocity: Float = 0.8f,
    val duration: Double = 1.0,
    val channel: Int = 0
) {
    /** `true` when this note is a rest ([pitch] < 0). */
    val isRest: Boolean get() = pitch < 0

    /** Frequency in Hz, derived from [pitch].  Returns 0 for rests. */
    val freq: Float get() = if (isRest) 0f else midiToHz(pitch)

    companion object {
        /** Sentinel pitch value used for rests. */
        const val REST = -1

        /** Create a rest of the given [duration] (in beats). */
        fun rest(duration: Double = 1.0): Note = Note(REST, 0f, duration)

        /**
         * Create a note from a letter name and octave.
         *
         * Examples:
         * ```
         * Note.fromName("C", 4)   // MIDI 60 – middle C
         * Note.fromName("A", 4)   // MIDI 69 – A440
         * Note.fromName("F#", 3)  // MIDI 54
         * Note.fromName("Eb", 5)  // MIDI 75
         * ```
         * Accidentals: use `#` for sharp, `b` for flat (`"C#"`, `"Db"`, etc.).
         */
        fun fromName(
            name: String,
            octave: Int = 4,
            velocity: Float = 0.8f,
            duration: Double = 1.0
        ): Note {
            val u = name.uppercase().trim()
            val base = when {
                u.startsWith("C#") || u.startsWith("DB") -> 1
                u.startsWith("C")                         -> 0
                u.startsWith("D#") || u.startsWith("EB") -> 3
                u.startsWith("D")                         -> 2
                u.startsWith("E")                         -> 4
                u.startsWith("F#") || u.startsWith("GB") -> 6
                u.startsWith("F")                         -> 5
                u.startsWith("G#") || u.startsWith("AB") -> 8
                u.startsWith("G")                         -> 7
                u.startsWith("A#") || u.startsWith("BB") -> 10
                u.startsWith("A")                         -> 9
                u.startsWith("B")                         -> 11
                else -> error("Unknown note name: $name")
            }
            return Note(base + (octave + 1) * 12, velocity, duration)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Terse extension properties
// ═══════════════════════════════════════════════════════════════════

/**
 * Quick [Note] from a MIDI pitch with default velocity (0.8) and duration (1 beat).
 *
 * ```kotlin
 * 60.n          // middle C
 * listOf(60.n, 62.n, 64.n, 65.n, 67.n)
 * ```
 */
val Int.n: Note get() = Note(this)

/**
 * Quick [Note] from a MIDI pitch with explicit beat duration.
 *
 * ```kotlin
 * 60.n(0.5)     // middle C, half-beat (eighth note in 4/4)
 * 67.n(2.0)     // G4, two beats
 * ```
 */
fun Int.n(duration: Double): Note = Note(this, duration = duration)

/** Frequency in Hz for this MIDI note number. */
val Int.hz: Float get() = midiToHz(this)

// ═══════════════════════════════════════════════════════════════════
//  Top-level rest helpers
// ═══════════════════════════════════════════════════════════════════

/** A rest of 1 beat (the most common default). */
val rest: Note get() = Note.rest()

/** A rest of the given [duration] in beats. */
fun rest(duration: Double): Note = Note.rest(duration)
