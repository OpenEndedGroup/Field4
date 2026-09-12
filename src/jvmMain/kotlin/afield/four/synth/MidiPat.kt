@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.Box
import afield.graphics.FLine
import afield.graphics.lines
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.rect
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The bridge between the pattern algebra and [MidiClip], plus a piano roll drawn as FLines.
 *
 * Two vocabularies have to be reconciled, and the pattern stack's spelling wins both times.
 *
 * **Cycles against beats.** A `PatPattern` is a function of *cycles*, measured in [PatQ]: a
 * rational over `BigInteger`, so a subdivision is exact however fine it gets. A MIDI file is
 * quarter notes and ticks. `beatsPerCycle` bridges the two, defaulting to 4 so that one cycle is
 * one bar of 4/4, which is what the mini-notation reads like. It is an `Int` because the import
 * direction divides by `ppq * beatsPerCycle`: an integer denominator is what lets [MidiClip.toPat]
 * rebuild every onset as an exact [PatQ] rather than a truncated one. Export does not keep that
 * guarantee and cannot — [MidiNote] holds its position as `Double` quarter notes.
 *
 * **Velocity and channel are not in the Pat vocabulary.** `PatAlgebra.kt` names the parameters the
 * rest of the stack reads — `gain` for amplitude, conventionally 0..1, and `note` for MIDI pitch —
 * and there is no `velocity` parameter and no `channel`. Rather than add two more that only MIDI
 * would ever set, export reads velocity from `gain` when it is present, and from an explicit
 * `velocity` annotation (0..127) when a caller wants wire-level control. Import writes both, so a
 * round trip through the algebra keeps its dynamics either way; `MidiRoundTripTest.a pattern
 * exports to MIDI and comes back as a pattern` is where that is pinned.
 *
 * A clip read back in becomes a *finite* pattern. Querying past the last note yields nothing
 * rather than looping, so reach for `slow`, `fast` or `cat` to place a clip inside a longer
 * structure.
 *
 * **The piano roll** is FLine geometry rather than an SVG string: one filled rectangle per note
 * over a beat-and-bar grid, with a horizontal rule at every C to give the eye an octave reference
 * the way a real roll's keyboard gutter does. Each note line carries its pitch, velocity, start
 * beat and track index in `properties`, so a caller can hit-test a click back to the note it
 * landed on. Field's y axis grows *downward*, so a higher pitch draws at a smaller y; reversing
 * that yields an upside-down roll that still looks plausible.
 */

/** Beats per cycle used by [MidiClip.toPat] and [PatPattern.toMidiClip] when none is given. */
const val MIDI_DEFAULT_BEATS_PER_CYCLE: Int = 4

/**
 * Render this pattern over [cycles] cycles into a [MidiClip].
 *
 * Only events with an onset in the window become notes: a `PatPattern` clips events to the arc it
 * was queried with, so an event whose `whole` began before the window is a *continuation* and
 * striking it again would double-trigger the note. That is why the filter is `hasOnset` and the
 * length comes from `extent`, not from `arc` — the same rule `FaustPattern.kt:90-93` follows.
 *
 * Pitch is resolved in the order every other consumer in this package uses: the `note` annotation
 * first, then the event's own value parsed as a note name or number by [patNoteNumber]. Note that
 * [patNoteNumber] uses Tidal's octave convention, where `c5` is MIDI 60 — not scientific pitch
 * notation. An event whose value names no pitch (a sample name like `bd`) is skipped.
 */
fun <A> PatPattern<A>.toMidiClip(
    cycles: Int = 1,
    bpm: Double = 120.0,
    beatsPerCycle: Int = MIDI_DEFAULT_BEATS_PER_CYCLE,
    velocity: Int = 100,
    channel: Int = 0,
    name: String? = null,
    ppq: Int = MIDI_DEFAULT_PPQ,
    program: Int? = null
): MidiClip {
    require(cycles > 0) { "cycles must be positive, was $cycles" }
    require(beatsPerCycle > 0) { "beatsPerCycle must be positive, was $beatsPerCycle" }

    val window = PatArc(PatQ.ZERO, PatQ.of(cycles.toLong()))
    val notes = events(window).mapNotNull { event -> event.toMidiNote(beatsPerCycle, velocity, channel) }

    return MidiClip(
        tracks = listOf(MidiTrackData(notes = notes, name = name, program = program)),
        tempoMap = listOf(0.0 to bpm),
        ppq = ppq,
        name = name,
        timeSignature = beatsPerCycle to 4
    )
}

/**
 * One pattern event as a [MidiNote], or null if the event names no pitch or occupies no time.
 *
 * Velocity precedence: an explicit `velocity` annotation (0..127) beats `gain` (0..1, scaled),
 * which beats [fallbackVelocity]. Duration is the event's `extent` scaled by its `sustain`
 * annotation, which the pattern stack defines as a *fraction of the arc* rather than an absolute
 * length (see `FaustPattern.kt:105-107`).
 */
private fun <A> PatEvent<A>.toMidiNote(
    beatsPerCycle: Int,
    fallbackVelocity: Int,
    fallbackChannel: Int
): MidiNote? {
    if (!hasOnset) return null

    val pitch = annotations["note"]?.trim()?.toDoubleOrNull()
        ?: patNoteNumber(value.toString())?.toDouble()
        ?: return null

    val span = extent
    val startBeats = span.start.toDouble() * beatsPerCycle
    val fullBeats = span.dur.toDouble() * beatsPerCycle
    if (fullBeats <= 0.0) return null

    val sustain = annotations["sustain"]?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
    val durationBeats = fullBeats * sustain
    if (durationBeats <= 0.0) return null

    val explicit = annotations["velocity"]?.trim()?.toDoubleOrNull()
    val gain = annotations["gain"]?.trim()?.toDoubleOrNull()
    val resolvedVelocity = when {
        explicit != null -> explicit.roundToInt()
        gain != null -> (gain.coerceIn(0.0, 1.0) * 127.0).roundToInt()
        else -> fallbackVelocity
    }

    val resolvedChannel = annotations["channel"]?.trim()?.toIntOrNull() ?: fallbackChannel

    return MidiNote(
        pitch = pitch.roundToInt().coerceIn(0, 127),
        startBeats = startBeats,
        durationBeats = durationBeats,
        velocity = resolvedVelocity.coerceIn(1, 127),
        channel = resolvedChannel.coerceIn(0, 15)
    )
}

/** Write this pattern to [file] as a Standard MIDI File. Returns [file]. */
fun <A> PatPattern<A>.writeMidi(
    file: File,
    cycles: Int = 1,
    bpm: Double = 120.0,
    beatsPerCycle: Int = MIDI_DEFAULT_BEATS_PER_CYCLE,
    name: String? = null
): File = toMidiClip(cycles = cycles, bpm = bpm, beatsPerCycle = beatsPerCycle, name = name).writeTo(file)

/**
 * This clip as a pattern, so a recorded or imported performance can be composed with the algebra.
 *
 * Each note becomes an event whose `whole` is its exact extent in cycles and whose value is its
 * pitch as a decimal string — the spelling [patNoteNumber] parses back, so `toPat().toMidiClip()`
 * is a round trip. `note`, `velocity`, `gain` and `channel` annotations are all set: `gain` because
 * that is the key the rest of the pattern stack reads for amplitude, `velocity` because it is
 * lossless.
 *
 * Times stay exact. The conversion is the rational `tick / (ppq * beatsPerCycle)`, built with
 * [PatQ.of] rather than [PatQ.fromDouble] — the latter truncates, which would drift a long clip.
 *
 * The pattern is *finite*: it renders events only where notes exist, so querying past the end of
 * the clip yields nothing rather than looping. Use `slow`/`fast`/`cat` to place it in a longer
 * structure.
 */
fun MidiClip.toPat(beatsPerCycle: Int = MIDI_DEFAULT_BEATS_PER_CYCLE): PatPattern<String> {
    require(beatsPerCycle > 0) { "beatsPerCycle must be positive, was $beatsPerCycle" }
    val denominator = ppq.toLong() * beatsPerCycle

    // Precompute each note's exact cycle-space extent once, outside the render lambda: the lambda
    // runs on every query, and a pattern is queried per cycle for as long as it plays.
    val prepared = notes.mapNotNull { note ->
        val startTick = (note.startBeats * ppq).roundToLong()
        val endTick = (note.endBeats * ppq).roundToLong()
        if (endTick <= startTick) return@mapNotNull null
        val whole = PatArc(PatQ.of(startTick, denominator), PatQ.of(endTick, denominator))
        whole to note
    }

    return PatPattern { absArc ->
        prepared.mapNotNull { (whole, note) ->
            val part = whole.intersect(absArc) ?: return@mapNotNull null
            PatEvent(
                arc = part,
                value = note.pitch.toString(),
                annotations = mapOf(
                    "note" to note.pitch.toString(),
                    "velocity" to note.velocity.toString(),
                    "gain" to patFormatNumber(note.velocity / 127.0),
                    "channel" to note.channel.toString()
                ),
                whole = whole
            )
        }
    }
}

/** Read [file] as a Standard MIDI File and return it as a pattern. */
fun readMidiPat(file: File, beatsPerCycle: Int = MIDI_DEFAULT_BEATS_PER_CYCLE): PatPattern<String> =
    readMidiClip(file).toPat(beatsPerCycle)

/**
 * The notes a transcription found, as a [MidiClip] — the bridge `PatTranscription` did not have.
 *
 * `PatTranscription` turns audio into `PatDetectedNote`s and its only existing export is
 * `miniNotation`, a quantized string that cannot express an off-grid onset or a fractional pitch
 * (`PatTranscription.kt:399-405` drops `centsOffset` outright). This route keeps the detected times
 * exactly as detected: it converts seconds to beats at [bpm] and never touches the grid.
 *
 * Velocity carries the detector's `confidence`, scaled to 1..127. That is not a dynamic marking —
 * nothing in the pitch tracker measures loudness — so it is documented here rather than silently
 * implied: a quiet, confidently-detected note comes out loud.
 */
fun PatTranscription.toMidiClip(
    bpm: Double = 120.0,
    name: String? = "transcription",
    ppq: Int = MIDI_DEFAULT_PPQ
): MidiClip {
    val beatsPerSecond = bpm / 60.0
    val midiNotes = notes.mapNotNull { detected ->
        val startBeats = detected.startSeconds * beatsPerSecond
        val durationBeats = (detected.endSeconds - detected.startSeconds) * beatsPerSecond
        if (durationBeats <= 0.0) return@mapNotNull null
        MidiNote(
            pitch = detected.midi.coerceIn(0, 127),
            startBeats = startBeats,
            durationBeats = durationBeats,
            velocity = (detected.confidence.coerceIn(0.0, 1.0) * 126.0 + 1.0).roundToInt().coerceIn(1, 127),
            channel = 0
        )
    }
    return MidiClip(
        tracks = listOf(MidiTrackData(notes = midiNotes, name = name)),
        tempoMap = listOf(0.0 to bpm),
        ppq = ppq,
        name = name
    )
}

/** Write this transcription straight to a Standard MIDI File another DAW can open. Returns [file]. */
fun PatTranscription.writeMidi(file: File, bpm: Double = 120.0): File =
    toMidiClip(bpm = bpm).writeTo(file)

// ---------------------------------------------------------------------------------------------
// Piano roll, drawn as FLines.
//
// The house rendering order is Plotly, then FLine, then raster. A piano roll is geometry, so it is
// FLines: real drawable lines with real colours, not an SVG string like
// `PatPianoRollGallery.svgForLanes` or `Iterable<ScheduledNote>.pianoRollSvg` produce.
//
// Note that Field's y axis grows *downward*, so a higher pitch gets a *smaller* y. Getting that
// backwards silently produces an upside-down roll that still looks plausible.
// ---------------------------------------------------------------------------------------------

/** Layout for [MidiClip.toFLines]. Sizes are in Field units, which for a box output read as pixels. */
data class MidiRollOptions(
    val pixelsPerBeat: Double = 44.0,
    val semitoneHeight: Double = 7.0,
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    /** Draw a vertical line per beat, and a brighter one per bar. */
    val showGrid: Boolean = true,
    val beatsPerBar: Int = MIDI_DEFAULT_BEATS_PER_CYCLE,
    /** Colour notes by velocity rather than by track. */
    val colorByVelocity: Boolean = true,
    val minimumNoteWidth: Double = 2.0
)

/**
 * This clip as a list of FLines: one filled rectangle per note, plus a beat/bar grid and a
 * horizontal rule at each C.
 *
 * Each note line carries `properties["midi.pitch"]`, `["midi.velocity"]`, `["midi.startBeats"]`
 * and `["midi.track"]`, following the identity-tagging pattern `AbletonSetDebugDrawing.kt:241`
 * established, so a caller can hit-test back to the note.
 */
fun MidiClip.toFLines(options: MidiRollOptions = MidiRollOptions()): List<FLine> {
    val all = notes
    if (all.isEmpty()) return emptyList()

    val lowest = all.minOf { it.pitch }
    val highest = all.maxOf { it.pitch }
    // Two semitones of air above and below, so the extreme notes are not flush against the edge.
    val top = highest + 2
    val bottom = lowest - 2
    val out = mutableListOf<FLine>()

    fun yOf(pitch: Int): Double = options.originY + (top - pitch) * options.semitoneHeight
    fun xOf(beats: Double): Double = options.originX + beats * options.pixelsPerBeat

    val totalBeats = durationBeats
    val rollHeight = (top - bottom) * options.semitoneHeight

    if (options.showGrid) {
        var beat = 0
        while (beat <= totalBeats.toInt() + 1) {
            val isBar = beat % options.beatsPerBar == 0
            out += tick(
                xOf(beat.toDouble()), options.originY,
                xOf(beat.toDouble()), options.originY + rollHeight,
                if (isBar) vec(0.62, 0.66, 0.72, 0.55) else vec(0.42, 0.45, 0.50, 0.28)
            )
            beat++
        }
        // A rule at every C gives the eye an octave reference, the way a real piano roll's
        // keyboard gutter does.
        for (pitch in bottom..top) {
            if (pitch % 12 != 0) continue
            val y = yOf(pitch) + options.semitoneHeight
            out += tick(xOf(0.0), y, xOf(totalBeats + 1.0), y, vec(0.55, 0.58, 0.64, 0.34))
        }
    }

    tracks.forEachIndexed { trackIndex, track ->
        track.notes.forEach { note ->
            val x = xOf(note.startBeats)
            val w = (note.durationBeats * options.pixelsPerBeat).coerceAtLeast(options.minimumNoteWidth)
            val y = yOf(note.pitch)
            val h = options.semitoneHeight - 1.0

            val color = if (options.colorByVelocity) {
                // Velocity to brightness: a soft note is dim blue, a hard one bright cyan-white.
                val t = (note.velocity / 127.0).coerceIn(0.0, 1.0)
                vec(0.25 + 0.55 * t, 0.55 + 0.40 * t, 0.85 + 0.15 * t, 0.92)
            } else {
                TRACK_COLORS[trackIndex % TRACK_COLORS.size]
            }

            out += fline {
                rect(x, y, w, h)
                this.color = color
                this.filled = true
                this.stroked = true
                thickness = 1.0
            }.also {
                it.properties["midi.pitch"] = note.pitch
                it.properties["midi.velocity"] = note.velocity
                it.properties["midi.startBeats"] = note.startBeats
                it.properties["midi.track"] = trackIndex
            }
        }
    }
    return out
}

private fun tick(x0: Double, y0: Double, x1: Double, y1: Double, lineColor: Vec4): FLine =
    fline {
        moveTo(x0, y0)
        lineTo(x1, y1)
        this.color = lineColor
        this.stroked = true
        this.filled = false
        thickness = 1.0
    }

private val TRACK_COLORS: List<Vec4> = listOf(
    vec(0.31, 0.76, 0.97, 0.92),
    vec(0.98, 0.68, 0.32, 0.92),
    vec(0.56, 0.89, 0.52, 0.92),
    vec(0.93, 0.51, 0.75, 0.92),
    vec(0.78, 0.70, 0.98, 0.92)
)

/**
 * Draw this clip's piano roll into [channel] of this box.
 *
 * [fitToSize] defaults to true because a roll's natural extent is "however many beats long the
 * clip is", which is almost never the panel's aspect ratio.
 */
fun Box.showMidiRoll(
    clip: MidiClip,
    channel: String = "lines",
    options: MidiRollOptions = MidiRollOptions(),
    w: Double = 900.0,
    h: Double = 320.0,
    fitToSize: Boolean = true
) {
    lines(clip.toFLines(options), channel = channel, w = w, h = h, fitToSize = fitToSize)
}
