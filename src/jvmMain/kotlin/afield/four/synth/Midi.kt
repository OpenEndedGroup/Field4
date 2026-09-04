@file:afield.CompletionCategory("Audio")
package afield.four.synth

import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Track
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// A Standard MIDI File surface for Field.
//
// Field has had exactly two `javax.sound.midi` call sites since basic-pitch integration landed:
// `MidiSystem.getSequence` in AbletonSet (reading an *external* transcriber's output) and
// `MidiSystem.write` in AbletonSetSmokeTest (a test fixture). Neither was reachable as a feature.
// This file is the product surface, and it owns the tick/tempo/note-pairing helpers that used to
// be private to AbletonSet.kt — AbletonSet is now a caller, not a second implementation.
//
// Time is carried in *beats* (quarter notes) in the Field-native model, because that is what the
// pattern stack speaks. Ticks and PPQ are a file-format detail and appear only at the edges, in
// [MidiTempoEvent] / [MidiTimedNote] / the reader and writer.

/** Default pulses-per-quarter-note for files Field writes. 480 divides 2, 3, 4, 5, 6 and 8 evenly. */
const val MIDI_DEFAULT_PPQ: Int = 480

/** The MIDI default tempo, used whenever a file states none: 500000 us/quarter == 120 BPM. */
const val MIDI_DEFAULT_MICROS_PER_QUARTER: Int = 500_000

/**
 * A tempo change at [tick], expressed the way a Standard MIDI File expresses it.
 *
 * Lifted verbatim (field names, types and the 500000 default) out of `AbletonSet.kt`, where it was
 * `private data class MidiTempoEvent`. Kept tick-based rather than beat-based on purpose: this is
 * the file-format edge, and `tickToSeconds` needs it in ticks.
 */
data class MidiTempoEvent(
    val tick: Long,
    val microsPerQuarter: Int
) {
    /** Beats per minute for this tempo. 500000 us/quarter -> 120.0. */
    val bpm: Double get() = if (microsPerQuarter <= 0) 0.0 else 60_000_000.0 / microsPerQuarter

    companion object {
        /** A tempo event at [tick] expressed in beats per minute. */
        fun ofBpm(tick: Long, bpm: Double): MidiTempoEvent =
            MidiTempoEvent(tick, (60_000_000.0 / bpm).roundToInt().coerceAtLeast(1))
    }
}

/**
 * A note-on/note-off pair recovered from a [Sequence], with both its tick span and its wall-clock
 * span already resolved through the file's tempo map.
 *
 * This is the reader's output type. It is deliberately *not* the Field-native note type: it still
 * carries ticks, so a caller that cares about the file's exact grid can see it.
 */
data class MidiTimedNote(
    val pitch: Int,
    val velocity: Int,
    val channel: Int,
    val startTick: Long,
    val endTick: Long,
    val startSeconds: Double,
    val endSeconds: Double,
    /** Index of the [Sequence] track this note was read from, 0-based. */
    val trackIndex: Int
) {
    val durationSeconds: Double get() = endSeconds - startSeconds
    val durationTicks: Long get() = endTick - startTick
}

/** A control-change (CC) event: [controller] set to [value] at [beats]. */
data class MidiControlChange(
    val controller: Int,
    val value: Int,
    val beats: Double,
    val channel: Int = 0
)

/**
 * One note in musical time. [startBeats] and [durationBeats] are quarter notes from the start of
 * the clip; [pitch] is a MIDI note number (60 == middle C) and [velocity] is 1..127.
 */
data class MidiNote(
    val pitch: Int,
    val startBeats: Double,
    val durationBeats: Double,
    val velocity: Int = 100,
    val channel: Int = 0
) {
    val endBeats: Double get() = startBeats + durationBeats

    /** Concert pitch of this note, via the same conversion the rest of the synth package uses. */
    val frequencyHz: Double get() = midiToFrequency(pitch.toDouble())

    /** This note moved by [beats]. */
    fun shifted(beats: Double): MidiNote = copy(startBeats = startBeats + beats)

    /** This note transposed by [semitones], clamped into the legal MIDI range. */
    fun transposed(semitones: Int): MidiNote = copy(pitch = (pitch + semitones).coerceIn(0, 127))
}

/** One named track of a [MidiClip]. A track maps to one `MTrk` chunk on disk. */
data class MidiTrackData(
    val notes: List<MidiNote> = emptyList(),
    val name: String? = null,
    val controlChanges: List<MidiControlChange> = emptyList(),
    /**
     * General MIDI program number (0-based, so 0 == Acoustic Grand Piano). Written as a
     * PROGRAM_CHANGE at tick 0 when non-null. See [GeneralMidi].
     */
    val program: Int? = null
) {
    val durationBeats: Double get() = notes.maxOfOrNull { it.endBeats } ?: 0.0
}

/**
 * A Field-native MIDI clip: some tracks of notes, a tempo map, a time signature and a PPQ.
 *
 * Write it with [writeTo] and read one back with [readMidiClip]. A clip written by [writeTo] is a
 * format-1 Standard MIDI File with a dedicated conductor track, which is what a DAW expects.
 */
data class MidiClip(
    val tracks: List<MidiTrackData> = emptyList(),
    /**
     * Tempo changes, in beats. The pair is (beats, BPM). An empty map means "120 BPM throughout",
     * and is written as an explicit 120 BPM event so that importers do not have to guess.
     */
    val tempoMap: List<Pair<Double, Double>> = emptyList(),
    val ppq: Int = MIDI_DEFAULT_PPQ,
    val name: String? = null,
    val timeSignature: Pair<Int, Int> = 4 to 4
) {
    init {
        require(ppq > 0) { "ppq must be positive, was $ppq" }
    }

    val notes: List<MidiNote> get() = tracks.flatMap { it.notes }

    val durationBeats: Double get() = tracks.maxOfOrNull { it.durationBeats } ?: 0.0

    /** BPM in force at [beats]. */
    fun bpmAt(beats: Double): Double =
        tempoMap.sortedBy { it.first }.lastOrNull { it.first <= beats }?.second ?: 120.0

    /** Seconds from the clip start to [beats], integrating the tempo map. */
    fun beatsToSeconds(beats: Double): Double {
        val tempos = effectiveTempoEvents()
        return tickToSeconds((beats * ppq).roundToLong(), tempos, ppq)
    }

    val durationSeconds: Double get() = beatsToSeconds(durationBeats)

    internal fun effectiveTempoEvents(): List<MidiTempoEvent> {
        if (tempoMap.isEmpty()) return listOf(MidiTempoEvent(0L, MIDI_DEFAULT_MICROS_PER_QUARTER))
        return tempoMap
            .map { (beats, bpm) -> MidiTempoEvent.ofBpm((beats * ppq).roundToLong(), bpm) }
            .sortedBy { it.tick }
    }

    /** This clip with every track's notes moved onto a grid of [gridBeats]. See [quantize]. */
    fun quantized(gridBeats: Double, strength: Double = 1.0, swing: Double = 0.0): MidiClip =
        copy(tracks = tracks.map { t -> t.copy(notes = quantize(t.notes, gridBeats, strength, swing)) })

    /** This clip transposed by [semitones]. */
    fun transposed(semitones: Int): MidiClip =
        copy(tracks = tracks.map { t -> t.copy(notes = t.notes.map { it.transposed(semitones) }) })

    /** Build a `javax.sound.midi.Sequence` for this clip. */
    fun toSequence(): Sequence {
        val sequence = Sequence(Sequence.PPQ, ppq)

        // Track 0 is the conductor track: tempo, time signature and the clip name. Format-1 files
        // put timing here and nowhere else, which is what makes them portable between DAWs.
        val conductor = sequence.createTrack()
        name?.let { conductor.addMetaText(0x03, it, 0L) }
        conductor.add(
            MidiEvent(
                MetaMessage(
                    0x58,
                    byteArrayOf(
                        timeSignature.first.toByte(),
                        // MIDI stores the denominator as its base-2 logarithm: 4 -> 2, 8 -> 3.
                        log2Denominator(timeSignature.second).toByte(),
                        24, 8
                    ),
                    4
                ),
                0L
            )
        )
        effectiveTempoEvents().forEach { tempo ->
            val micros = tempo.microsPerQuarter
            conductor.add(
                MidiEvent(
                    MetaMessage(
                        0x51,
                        byteArrayOf(
                            ((micros shr 16) and 0xff).toByte(),
                            ((micros shr 8) and 0xff).toByte(),
                            (micros and 0xff).toByte()
                        ),
                        3
                    ),
                    tempo.tick
                )
            )
        }

        tracks.forEach { data ->
            val track = sequence.createTrack()
            data.name?.let { track.addMetaText(0x03, it, 0L) }
            data.program?.let { program ->
                val channel = data.notes.firstOrNull()?.channel ?: 0
                track.add(
                    MidiEvent(
                        ShortMessage(ShortMessage.PROGRAM_CHANGE, channel, program.coerceIn(0, 127), 0),
                        0L
                    )
                )
            }
            data.controlChanges.forEach { cc ->
                track.add(
                    MidiEvent(
                        ShortMessage(
                            ShortMessage.CONTROL_CHANGE,
                            cc.channel.coerceIn(0, 15),
                            cc.controller.coerceIn(0, 127),
                            cc.value.coerceIn(0, 127)
                        ),
                        (cc.beats * ppq).roundToLong()
                    )
                )
            }
            data.notes.forEach { note ->
                val channel = note.channel.coerceIn(0, 15)
                val pitch = note.pitch.coerceIn(0, 127)
                val startTick = (note.startBeats * ppq).roundToLong()
                // A zero-length note is unrepresentable: a note-off at the same tick as its
                // note-on is dropped by most importers (ours included — see closeActiveNote's
                // `endSeconds <= startSeconds` guard). Give it one tick so it survives.
                val endTick = (note.endBeats * ppq).roundToLong().coerceAtLeast(startTick + 1)
                track.add(
                    MidiEvent(
                        ShortMessage(ShortMessage.NOTE_ON, channel, pitch, note.velocity.coerceIn(1, 127)),
                        startTick
                    )
                )
                // An explicit NOTE_OFF rather than a zero-velocity NOTE_ON: both are legal, and
                // both are read back by [readTimedNotes], but the explicit form is what a DAW
                // shows in its event list.
                track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, channel, pitch, 0), endTick))
            }
        }
        return sequence
    }

    /**
     * Write this clip to [file] as a format-1 Standard MIDI File. Returns [file].
     *
     * Format 1 (a conductor track plus one track per part) rather than format 0 (everything merged
     * onto one track): format 0 loses the track split, and a DAW import then shows one instrument.
     */
    fun writeTo(file: File): File {
        file.absoluteFile.parentFile?.mkdirs()
        MidiSystem.write(toSequence(), 1, file)
        return file
    }

    companion object {
        /** A single-track clip. */
        fun of(notes: List<MidiNote>, bpm: Double = 120.0, name: String? = null, ppq: Int = MIDI_DEFAULT_PPQ): MidiClip =
            MidiClip(
                tracks = listOf(MidiTrackData(notes = notes, name = name)),
                tempoMap = listOf(0.0 to bpm),
                ppq = ppq,
                name = name
            )
    }
}

private fun log2Denominator(denominator: Int): Int {
    var d = denominator.coerceAtLeast(1)
    var log = 0
    while (d > 1) {
        d = d shr 1
        log++
    }
    return log
}

private fun Track.addMetaText(type: Int, text: String, tick: Long) {
    val bytes = text.toByteArray(Charsets.US_ASCII)
    add(MidiEvent(MetaMessage(type, bytes, bytes.size), tick))
}

/**
 * Every tempo change in this sequence, in tick order, with the MIDI default (120 BPM) prepended so
 * the list is never empty.
 *
 * Lifted out of `AbletonSet.kt`, where it was `private fun javax.sound.midi.Sequence.tempoEvents()`.
 * Body unchanged, including the leading 500000 default and the scan across *all* tracks (a format-1
 * file puts tempo on track 0, but plenty of real files sprinkle it elsewhere).
 */
fun Sequence.tempoEvents(): List<MidiTempoEvent> {
    val tempos = mutableListOf(MidiTempoEvent(0L, MIDI_DEFAULT_MICROS_PER_QUARTER))
    tracks.forEach { track ->
        for (i in 0 until track.size()) {
            val event = track.get(i)
            val meta = event.message as? MetaMessage ?: continue
            if (meta.type == 0x51 && meta.data.size >= 3) {
                val data = meta.data
                val micros = ((data[0].toInt() and 0xff) shl 16) or
                    ((data[1].toInt() and 0xff) shl 8) or
                    (data[2].toInt() and 0xff)
                tempos += MidiTempoEvent(event.tick, micros)
            }
        }
    }
    return tempos.sortedBy { it.tick }
}

/** The track name (meta 0x03) of track [index], or null. */
fun Sequence.trackName(index: Int): String? {
    val track = tracks.getOrNull(index) ?: return null
    for (i in 0 until track.size()) {
        val meta = track.get(i).message as? MetaMessage ?: continue
        if (meta.type == 0x03) return String(meta.data, Charsets.US_ASCII).ifBlank { null }
    }
    return null
}

/**
 * Wall-clock seconds at [tick], integrating [tempos] at [resolution] ticks per quarter.
 *
 * Lifted out of `AbletonSet.kt` (`private fun tickToSeconds`) with its body unchanged, including
 * the `resolution <= 0` guard that returns 0.0 rather than dividing by zero — a real SMPTE-division
 * file reaches that guard, because `Sequence.resolution` is then frames-per-second, not PPQ.
 */
fun tickToSeconds(tick: Long, tempos: List<MidiTempoEvent>, resolution: Int): Double {
    if (resolution <= 0) return 0.0
    var seconds = 0.0
    var cursorTick = 0L
    var microsPerQuarter = MIDI_DEFAULT_MICROS_PER_QUARTER

    for (event in tempos.sortedBy { it.tick }) {
        if (event.tick > tick) break
        seconds += ticksToSeconds(event.tick - cursorTick, microsPerQuarter, resolution)
        cursorTick = event.tick
        microsPerQuarter = event.microsPerQuarter
    }
    seconds += ticksToSeconds(tick - cursorTick, microsPerQuarter, resolution)
    return seconds
}

/** Seconds spanned by [ticks] at a fixed tempo. Lifted unchanged from `AbletonSet.kt`. */
fun ticksToSeconds(ticks: Long, microsPerQuarter: Int, resolution: Int): Double =
    ticks.toDouble() * microsPerQuarter.toDouble() / 1_000_000.0 / resolution.toDouble()

/**
 * A note-on that has not yet been matched with its note-off.
 *
 * Lifted out of `AbletonSet.kt` (`private data class ActiveMidiNote`), with `trackIndex` added so
 * a multi-track read can attribute notes to tracks. AbletonSet never looked at the track index.
 */
internal data class ActiveMidiNote(
    val pitch: Int,
    val velocity: Int,
    val channel: Int,
    val startTick: Long,
    val trackIndex: Int
)

/**
 * Pair up the note-ons and note-offs in this sequence and return the resulting notes, in the order
 * their note-offs were seen.
 *
 * This is the generalisation of `AbletonSet.readPitchNotes`, which is now a caller. Behaviour that
 * callers depend on and that is therefore deliberate, not incidental:
 *
 *  - Notes are keyed on (channel, pitch) and paired FIFO, so overlapping same-pitch notes nest
 *    correctly rather than all collapsing onto the first note-off.
 *  - A NOTE_ON with velocity 0 is a note-off. That is not a nicety: it is how most sequencers
 *    encode note-offs, because running status then compresses the whole stream.
 *  - Notes still sounding at the end of the sequence are closed at `tickLength`, not dropped.
 *  - A note whose resolved duration is zero or negative is dropped. `MidiClip.toSequence` never
 *    writes one (it floors duration at one tick), but a foreign file can contain them.
 */
fun Sequence.readTimedNotes(): List<MidiTimedNote> {
    val tempos = tempoEvents()
    val active = mutableMapOf<Pair<Int, Int>, ArrayDeque<ActiveMidiNote>>()
    val out = mutableListOf<MidiTimedNote>()

    tracks.forEachIndexed { trackIndex, track ->
        for (i in 0 until track.size()) {
            val event = track.get(i)
            val message = event.message as? ShortMessage ?: continue
            when (message.command) {
                ShortMessage.NOTE_ON -> {
                    val pitch = message.data1
                    val velocity = message.data2
                    val key = message.channel to pitch
                    if (velocity > 0) {
                        active.getOrPut(key) { ArrayDeque() }.addLast(
                            ActiveMidiNote(pitch, velocity, message.channel, event.tick, trackIndex)
                        )
                    } else {
                        closeActiveNote(active, key, event.tick, tempos, resolution, out)
                    }
                }
                ShortMessage.NOTE_OFF -> {
                    val key = message.channel to message.data1
                    closeActiveNote(active, key, event.tick, tempos, resolution, out)
                }
            }
        }
    }

    val endTick = tickLength
    for ((key, queue) in active) {
        while (queue.isNotEmpty()) {
            closeActiveNote(active, key, endTick, tempos, resolution, out)
        }
    }
    return out
}

/** Every control-change event in this sequence, in tick order. */
fun Sequence.readControlChanges(): List<MidiControlChange> {
    val out = mutableListOf<Pair<Long, MidiControlChange>>()
    val ppq = resolution.coerceAtLeast(1)
    tracks.forEach { track ->
        for (i in 0 until track.size()) {
            val event = track.get(i)
            val message = event.message as? ShortMessage ?: continue
            if (message.command == ShortMessage.CONTROL_CHANGE) {
                out += event.tick to MidiControlChange(
                    controller = message.data1,
                    value = message.data2,
                    beats = event.tick.toDouble() / ppq,
                    channel = message.channel
                )
            }
        }
    }
    return out.sortedBy { it.first }.map { it.second }
}

/**
 * Close the oldest unmatched note for [key] at [endTick] and append it to [out].
 *
 * Lifted out of `AbletonSet.kt`, generalised to emit [MidiTimedNote] instead of
 * `AbletonPitchEstimate`. The `endSeconds <= startSeconds` drop is preserved exactly, because
 * AbletonSet's pitch analysis relies on it: a basic-pitch output does contain degenerate notes and
 * `dominantPitchAtSeconds` would otherwise return a note that occupies no time.
 */
private fun closeActiveNote(
    active: MutableMap<Pair<Int, Int>, ArrayDeque<ActiveMidiNote>>,
    key: Pair<Int, Int>,
    endTick: Long,
    tempos: List<MidiTempoEvent>,
    resolution: Int,
    out: MutableList<MidiTimedNote>
) {
    val note = active[key]?.removeFirstOrNull() ?: return
    val startSeconds = tickToSeconds(note.startTick, tempos, resolution)
    val endSeconds = tickToSeconds(endTick, tempos, resolution)
    if (endSeconds <= startSeconds) return
    out += MidiTimedNote(
        pitch = note.pitch,
        velocity = note.velocity,
        channel = note.channel,
        startTick = note.startTick,
        endTick = endTick,
        startSeconds = startSeconds,
        endSeconds = endSeconds,
        trackIndex = note.trackIndex
    )
}

/**
 * Read [file] as a Standard MIDI File and return it as a [MidiClip], preserving the file's PPQ,
 * tempo map, track split and track names.
 *
 * This survives files Field did not write: `AbletonSet` reads basic-pitch output through the same
 * note-pairing code, and [readMidiClip] is tested against hand-assembled SMF bytes that use
 * running status, zero-velocity note-offs and a mid-file tempo change.
 */
fun readMidiClip(file: File): MidiClip = MidiSystem.getSequence(file).toMidiClip(name = file.nameWithoutExtension)

/** Convert a `javax.sound.midi.Sequence` into a [MidiClip]. */
fun Sequence.toMidiClip(name: String? = null): MidiClip {
    val ppq = if (divisionType == Sequence.PPQ && resolution > 0) resolution else MIDI_DEFAULT_PPQ
    val timed = readTimedNotes()
    val ccs = readControlChanges().groupBy { (it.beats * ppq).roundToLong() }

    // Keep the file's own track split. Tracks that carry no notes (a conductor track, typically)
    // contribute nothing, but their tempo events are already in the tempo map.
    val byTrack = timed.groupBy { it.trackIndex }
    val trackData = byTrack.keys.sorted().map { index ->
        MidiTrackData(
            notes = byTrack.getValue(index)
                .map { n ->
                    MidiNote(
                        pitch = n.pitch,
                        startBeats = n.startTick.toDouble() / ppq,
                        durationBeats = n.durationTicks.toDouble() / ppq,
                        velocity = n.velocity,
                        channel = n.channel
                    )
                }
                .sortedWith(compareBy({ it.startBeats }, { it.pitch })),
            name = trackName(index),
            controlChanges = if (index == byTrack.keys.minOrNull()) ccs.values.flatten() else emptyList()
        )
    }

    return MidiClip(
        tracks = trackData,
        tempoMap = tempoEvents().map { it.tick.toDouble() / ppq to it.bpm },
        ppq = ppq,
        name = name ?: trackName(0),
        timeSignature = readTimeSignature() ?: (4 to 4)
    )
}

/** The time signature from meta 0x58, or null if the file states none. */
fun Sequence.readTimeSignature(): Pair<Int, Int>? {
    tracks.forEach { track ->
        for (i in 0 until track.size()) {
            val meta = track.get(i).message as? MetaMessage ?: continue
            if (meta.type == 0x58 && meta.data.size >= 2) {
                val numerator = meta.data[0].toInt() and 0xff
                val denominator = 1 shl (meta.data[1].toInt() and 0xff)
                if (numerator > 0) return numerator to denominator
            }
        }
    }
    return null
}

/**
 * Move [notes] onto a grid of [gridBeats].
 *
 * [strength] interpolates between untouched (0.0) and fully on the grid (1.0), which is what a DAW
 * calls "quantize amount" — a humanised feel needs a partial pull, not a hard snap.
 *
 * [swing] delays every *odd* grid position by that fraction of a grid step, so 0.0 is straight and
 * a shuffle is around 0.33. Applied after the snap, so swing is not itself quantized away.
 */
fun quantize(
    notes: List<MidiNote>,
    gridBeats: Double,
    strength: Double = 1.0,
    swing: Double = 0.0
): List<MidiNote> {
    require(gridBeats > 0.0) { "gridBeats must be positive, was $gridBeats" }
    val amount = strength.coerceIn(0.0, 1.0)
    return notes.map { note ->
        val gridPosition = (note.startBeats / gridBeats).roundToLong()
        val snapped = gridPosition * gridBeats
        val swung = if (gridPosition % 2L != 0L) snapped + swing * gridBeats else snapped
        note.copy(startBeats = note.startBeats + (swung - note.startBeats) * amount)
    }
}

/**
 * How far [notes] sit from a grid of [gridBeats], as a mean absolute error in beats. 0.0 means
 * perfectly on the grid. Useful for asserting that [quantize] did something.
 */
fun gridError(notes: List<MidiNote>, gridBeats: Double): Double {
    if (notes.isEmpty()) return 0.0
    return notes.sumOf { note ->
        val offset = note.startBeats / gridBeats
        abs(offset - floor(offset + 0.5))
    } / notes.size * gridBeats
}

/** General MIDI program numbers, 0-based as the wire format has them. */
object GeneralMidi {
    const val ACOUSTIC_GRAND_PIANO = 0
    const val ELECTRIC_PIANO = 4
    const val CELESTA = 8
    const val MARIMBA = 12
    const val DRAWBAR_ORGAN = 16
    const val ACOUSTIC_GUITAR_NYLON = 24
    const val ACOUSTIC_BASS = 32
    const val VIOLIN = 40
    const val STRING_ENSEMBLE = 48
    const val TRUMPET = 56
    const val SOPRANO_SAX = 64
    const val FLUTE = 73
    const val SQUARE_LEAD = 80
    const val WARM_PAD = 89

    /** The GM channel reserved for percussion, 0-based (so channel 10 in 1-based DAW displays). */
    const val PERCUSSION_CHANNEL = 9

    /** The GM name for program [program], or null if out of range. */
    fun name(program: Int): String? = NAMES.getOrNull(program)

    private val NAMES = listOf(
        "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
        "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
        "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
        "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
        "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
        "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)",
        "Electric Guitar (clean)", "Electric Guitar (muted)", "Overdriven Guitar",
        "Distortion Guitar", "Guitar Harmonics",
        "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
        "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
        "Violin", "Viola", "Cello", "Contrabass",
        "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
        "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
        "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
        "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
        "Oboe", "English Horn", "Bassoon", "Clarinet",
        "Piccolo", "Flute", "Recorder", "Pan Flute",
        "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
        "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
        "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
        "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
        "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
        "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
        "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
        "Sitar", "Banjo", "Shamisen", "Koto",
        "Kalimba", "Bag pipe", "Fiddle", "Shanai",
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
        "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
        "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
        "Telephone Ring", "Helicopter", "Applause", "Gunshot"
    )
}
