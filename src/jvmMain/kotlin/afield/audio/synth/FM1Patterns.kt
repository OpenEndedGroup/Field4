@file:afield.runtime.completion.CompletionCategory("Synthesis")
package afield.audio.synth

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Field patterns into the FM-1 simulator, and the simulator's own sequence back out as a clip.
 *
 * In: a pattern event's rational arc becomes a whole simulator block (128 frames at 44,100 Hz) and
 * its atom a MIDI note on the track's channel, the way [faustNotesForArc] turns one into a frame and
 * a frequency for Faust. Out: the notes a track sends under [FM1Sim.midiOut] become a [MidiClip],
 * which `showMidiRoll` draws and `toPat` turns back into a pattern.
 */

/**
 * The MIDI notes the FM-1's drum tracks (808 and sampler) play, by the names Field's patterns use
 * for drums: `bd`, `sd`, `lt`, `mt`, `ht`, `rim`, `cp`, `cb`, `cr`, `oh` and `hh`, with a few
 * common spellings beside them.
 */
val FM1_DRUM_NOTES: Map<String, Int> = mapOf(
    "bd" to 36, "kick" to 36, "sd" to 38, "sn" to 38, "snare" to 38,
    "lt" to 41, "mt" to 45, "ht" to 48, "rim" to 37, "rs" to 37,
    "cp" to 39, "clap" to 39, "cb" to 56, "cr" to 49, "crash" to 49,
    "oh" to 46, "hh" to 42, "ch" to 42
)

/**
 * One note for an [FM1Sim] track: on at block [startBlock], off at [endBlock], both on
 * [FM1Sim.blocksRendered]'s clock.
 *
 * @param startBlock the block the note starts in
 * @param endBlock the block it is released in, after [startBlock]
 * @param note the MIDI note
 * @param velocity 1..127
 * @param label the pattern atom it came from
 */
data class FM1Note(val startBlock: Long, val endBlock: Long, val note: Int, val velocity: Int, val label: String)

/** The notes a pattern query produced, and how many events were skipped for having no pitch or a NaN gain. */
data class FM1PatternNotes(val notes: List<FM1Note>, val skipped: Int)

/**
 * Turn one query of [pattern] into [FM1Note]s, cycle 0 starting at block [originBlock].
 *
 * Pitch is the `note` parameter, then a drum name from [FM1_DRUM_NOTES], then the atom through
 * [patNoteNumber] (`c5` is middle C), rounded to a whole note; an event with none of those, or a
 * note outside MIDI's 0..127, is skipped and counted. Velocity is [velocity] times the event's
 * `gain`, which MIDI holds to at most 127; a gain of 0 or below is a rest, and a NaN gain skips the
 * event rather than sounding at some made-up level. `sustain` is the fraction of its arc a note holds, as for Faust.
 */
fun fm1NotesForArc(
    pattern: PatPattern<String>,
    arc: PatArc,
    cps: Double,
    sustain: Double = FAUST_PATTERN_SUSTAIN,
    velocity: Int = 100,
    originBlock: Long = 0L
): FM1PatternNotes {
    require(cps.isFinite() && cps > 0.0) { "cps must be finite and > 0, was $cps" }
    require(!sustain.isNaN()) { "sustain is NaN" }
    val blocksPerCycle = FM1Sim.BLOCKS_PER_SECOND / cps
    val notes = ArrayList<FM1Note>()
    var skipped = 0
    val events = pattern.events(arc).filter { it.hasOnset }.sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
    for (event in events) {
        val extent = event.extent
        val atom = event.value.trim()
        val midi = event.annotations["note"]?.trim()?.toDoubleOrNull()
            ?: FM1_DRUM_NOTES[atom.lowercase()]?.toDouble()
            ?: patNoteNumber(atom)?.toDouble()
        val gain = event.annotations["gain"]?.trim()?.toDoubleOrNull() ?: 1.0
        if (midi == null || !midi.isFinite() || gain.isNaN()) { skipped++; continue }
        val n = midi.roundToInt()
        if (n !in 0..127) { skipped++; continue }
        val held = event.annotations["sustain"]?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() } ?: sustain
        val start = originBlock + (extent.start.toDouble() * blocksPerCycle).roundToLong()
        val length = (extent.dur.toDouble() * blocksPerCycle * held.coerceAtLeast(0.0)).roundToLong().coerceAtLeast(1L)
        val scaled = velocity * gain
        if (!(scaled >= 0.5)) continue // a gain of 0 (or below) is a rest
        notes += FM1Note(start, start + length, n, scaled.coerceAtMost(127.0).roundToInt(), event.value)
    }
    return FM1PatternNotes(notes, skipped)
}

/**
 * Feeds a pattern to one track of an [FM1Sim], a cycle at a time, staying ahead of the audio.
 *
 * The pattern is queried on this feeder's own daemon thread, never the audio thread, and what it
 * produces are simulator blocks, so being early costs nothing. When nothing is pulling the
 * simulator (its box is stopped) the feeder waits; when it starts again the feeder skips the cycles
 * that went by rather than playing them all at once.
 */
class FM1PatternFeeder internal constructor(
    private val sim: FM1Sim,
    /** The pattern being played. */
    val pattern: PatPattern<String>,
    /** The track it plays. */
    val track: Int,
    /** Cycles a second. */
    val cps: Double,
    /** The fraction of its arc each note holds. */
    val sustain: Double,
    /** Velocity at a gain of 1. */
    val velocity: Int,
    /** How far ahead of the audio the feeder schedules. */
    val aheadSeconds: Double
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Cycles fed so far. */
    @Volatile var cyclesFed: Long = 0L
        private set

    /** Events skipped for having no pitch, as [fm1NotesForArc] counts them. */
    @Volatile var skipped: Long = 0L
        private set

    internal fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = kotlin.concurrent.thread(isDaemon = true, name = "fm1-pattern-feeder-$track") {
            val blocksPerCycle = FM1Sim.BLOCKS_PER_SECOND / cps
            val ahead = FM1Sim.blocks(aheadSeconds.coerceAtLeast(0.0))
            val origin = sim.blocksRendered + ahead
            var cycle = 0L
            while (running.get()) {
                val now = sim.blocksRendered
                // After a pause, rejoin at the cycle playing now instead of catching up on the gap.
                val behind = floor((now - origin) / blocksPerCycle).toLong()
                if (behind > cycle) cycle = behind
                while (running.get() && origin + cycle * blocksPerCycle <= now + ahead) {
                    val q = fm1NotesForArc(
                        pattern, PatArc(PatQ.of(cycle), PatQ.of(cycle + 1)), cps, sustain, velocity, origin
                    )
                    for (n in q.notes) sim.scheduleNote(track, n.note, n.velocity, n.startBlock, n.endBlock)
                    skipped += q.skipped
                    cycle++
                    cyclesFed++
                }
                runCatching { Thread.sleep(15L) }.onFailure { return@thread }
            }
        }
    }

    /** Stop feeding; notes already scheduled still play. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    /** Whether it is still feeding. */
    val isRunning: Boolean get() = running.get()

    override fun toString(): String = synthPrettyObject(
        this, "track" to track, "cps" to cps, "cyclesFed" to cyclesFed, "running" to isRunning
    )
}

/**
 * The notes in these MIDI events as a [MidiClip], one clip track per channel, at [bpm] (the
 * project's tempo, [FM1Sim.bpm]); time zero is the first event.
 *
 * A note still held at the end is closed at the last event. Clock and other system messages are
 * ignored.
 */
fun List<FM1MidiEvent>.toMidiClip(bpm: Double, name: String? = "FM-1"): MidiClip {
    require(bpm.isFinite() && bpm > 0.0) { "bpm must be finite and > 0, was $bpm" }
    if (isEmpty()) return MidiClip(name = name, tempoMap = listOf(0.0 to bpm))
    val t0 = first().frame
    val beatsPerFrame = bpm / 60.0 / FM1Sim.SAMPLE_RATE
    val open = HashMap<Pair<Int, Int>, Pair<Long, Int>>()
    val byChannel = sortedMapOf<Int, MutableList<MidiNote>>()
    fun close(ch: Int, pitch: Int, at: Long) {
        val (from, vel) = open.remove(ch to pitch) ?: return
        val start = (from - t0) * beatsPerFrame
        val dur = ((at - from) * beatsPerFrame).coerceAtLeast(1e-3)
        byChannel.getOrPut(ch) { mutableListOf() } += MidiNote(pitch, start, dur, vel, ch)
    }
    for (e in this) {
        val b = e.bytes
        if (b.size < 3 || b[0] >= 0xF0) continue
        val ch = b[0] and 15
        val on = (b[0] and 0xF0) == 0x90 && b[2] > 0
        val off = (b[0] and 0xF0) == 0x80 || ((b[0] and 0xF0) == 0x90 && b[2] == 0)
        if (on) { close(ch, b[1], e.frame); open[ch to b[1]] = e.frame to b[2] }
        else if (off) close(ch, b[1], e.frame)
    }
    val end = last().frame
    open.keys.toList().forEach { (ch, p) -> close(ch, p, end) }
    return MidiClip(
        tracks = byChannel.map { (ch, notes) -> MidiTrackData(notes.sortedBy { it.startBeats }, name = "channel ${ch + 1}") },
        tempoMap = listOf(0.0 to bpm),
        name = name
    )
}
