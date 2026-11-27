package afield.ui.notation

import afield.math.linalg.Vec2
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Playheads that travel along drawn strokes, and what happens when two of them meet — a sequencer
 * after the method of anima (https://llllllll.co/t/anima/75862), the norns/Grid sequencer that
 * grew out of Crim's Animator. This is built from that description, not from its code.
 *
 * Every stroke is a [PlayheadTrack]. Its playhead moves along the stroke at its own [rate][PlayheadTrack.rate]
 * and direction, wrapping or ping-ponging at the ends, and on its own step clock it strikes a note
 * from its [degrees][PlayheadTrack.degrees] with a [probability][PlayheadTrack.probability] and a
 * [ratchet][PlayheadTrack.ratchet]. Pitch, rate and direction change only when two playheads
 * *meet* ([MeetingRule]): then each of the two applies its own [MeetAction]. Two [PlayheadLfo]s per
 * track move gain, note length or probability between meetings, and either can be frozen.
 *
 * [StrokePlayheads] is a pure simulation in beats with a seeded random source: the same strokes
 * and settings give the same notes and meetings every time, so it is unit-testable and its output
 * is a list of `TimedNote`-shaped [PlayheadNote]s a pattern can play. Nothing here touches an audio
 * graph; play the notes through a pattern, whose scheduling is the audio thread's.
 * [restroke][StrokePlayheads.restroke] swaps a track's stroke and keeps its playhead's place, so a
 * stroke can be redrawn between runs while the sequence carries on.
 */

/** What a track does to itself when its playhead meets another. */
enum class MeetAction {
    /** Shift the track's pitch by its [PlayheadTrack.transposeStep] semitones, folding back past an octave. */
    TRANSPOSE,

    /** Multiply the track's rate by its [PlayheadTrack.rateFactor], alternating up and back down. */
    RATE,

    /** Turn the playhead round. */
    REVERSE,

    /** Nothing: the track only marks the meeting. */
    NONE,
}

/** What a [PlayheadLfo] moves. None of these is pitch, rate or direction: those change only at meetings. */
enum class LfoDestination { GAIN, LENGTH, PROBABILITY }

/**
 * A sine LFO on one destination of a track: `depth * sin(2π (beat / periodBeats + phase))`, added
 * to the destination's base value. From [freezeAt] on it holds the value it had then.
 *
 * @param destination what it moves
 * @param periodBeats one cycle, in beats
 * @param depth amplitude, in the destination's units (gain 0..1, length as a fraction of a step, probability 0..1)
 * @param phase starting phase, in cycles
 * @param freezeAt the beat from which the LFO holds still; never by default
 */
class PlayheadLfo(
    val destination: LfoDestination,
    val periodBeats: Double,
    val depth: Double,
    val phase: Double = 0.0,
    var freezeAt: Double = Double.POSITIVE_INFINITY,
) {
    init { require(periodBeats > 0) { "periodBeats must be > 0" } }

    /** The LFO's value at [beat], held from [freezeAt]. */
    fun value(beat: Double): Double = depth * sin(2 * PI * (minOf(beat, freezeAt) / periodBeats + phase))
}

/**
 * One stroke as a track. Times are beats; [rate] 1 crosses the whole stroke in [passBeats].
 *
 * @param name the track's name; also how [StrokePlayheads.restroke] finds it
 * @param points the stroke, in world units (at least two points)
 * @param passBeats beats for one pass end to end at rate 1
 * @param stepBeats beats between steps at rate 1; the step clock speeds up with the rate
 * @param degrees scale degrees struck in turn, one per step
 * @param root MIDI note of degree 0
 * @param scale semitones of each degree within an octave
 * @param start where the playhead starts, as a fraction of the stroke
 */
class PlayheadTrack(
    val name: String,
    points: List<Vec2>,
    val passBeats: Double = 8.0,
    val stepBeats: Double = 0.5,
    val degrees: List<Int> = listOf(0, 2, 4),
    val root: Double = 60.0,
    val scale: List<Int> = listOf(0, 2, 4, 7, 9),
    start: Double = 0.0,
    /** +1 runs from the stroke's first point to its last, -1 back. */
    var direction: Int = 1,
    /** Bounce at the ends instead of wrapping round. */
    val pingPong: Boolean = false,
    /** Chance each step strikes, 0..1, before the LFOs. */
    val probability: Double = 1.0,
    /** Strikes per step, evenly spaced. */
    val ratchet: Int = 1,
    /** Base gain, 0..1. */
    val gain: Double = 0.8,
    /** Base note length, as a fraction of the step. */
    val length: Double = 0.4,
    /** What this track does when it meets another. */
    val onMeet: MeetAction = MeetAction.TRANSPOSE,
    /** Semitones per [MeetAction.TRANSPOSE]. */
    val transposeStep: Double = 5.0,
    /** Factor per [MeetAction.RATE]. */
    val rateFactor: Double = 1.5,
    /** Up to two LFOs. */
    val lfos: List<PlayheadLfo> = emptyList(),
) {
    init {
        require(passBeats > 0 && stepBeats > 0) { "passBeats and stepBeats must be > 0" }
        require(degrees.isNotEmpty() && scale.isNotEmpty()) { "degrees and scale must not be empty" }
        require(ratchet >= 1) { "ratchet must be >= 1" }
        require(lfos.size <= 2) { "a track has at most two LFOs, got ${lfos.size}" }
        require(direction == 1 || direction == -1) { "direction is +1 or -1" }
    }

    internal var path = PolylinePath(points)
    /** Where the playhead is, as a fraction 0..1 of the stroke. */
    var at: Double = start.coerceIn(0.0, 1.0)
        internal set
    /** The rate now; [MeetAction.RATE] changes it. */
    var rate: Double = 1.0
        internal set
    /** Semitones added now; [MeetAction.TRANSPOSE] changes it. */
    var transpose: Double = 0.0
        internal set
    internal var rateUp = true
    internal var step = 0
    internal var nextStep = 0.0

    /** The playhead's position on the canvas. */
    val position: Vec2 get() = path.at(at)

    /** The stroke as it is now. */
    val points: List<Vec2> get() = path.points

    /** MIDI pitch of step [k] with the current transposition. */
    fun pitchOf(k: Int): Double {
        val d = degrees[Math.floorMod(k, degrees.size)]
        val octave = Math.floorDiv(d, scale.size)
        return root + 12.0 * octave + scale[Math.floorMod(d, scale.size)] + transpose
    }

    internal fun lfo(dest: LfoDestination, beat: Double): Double =
        lfos.filter { it.destination == dest }.sumOf { it.value(beat) }
}

/** How two playheads meet. */
sealed class MeetingRule {
    /** When they come within [radius] world units; it re-arms once they are [rearm] × radius apart. */
    class Radius(val radius: Double, val rearm: Double = 1.5) : MeetingRule()

    /** When both pass the same crossing of their strokes within [windowBeats] of each other. */
    class Crossing(val windowBeats: Double) : MeetingRule()
}

/** A struck note: [start] to [end] in beats, MIDI [note], [gain] 0..1, from [track]. */
data class PlayheadNote(val track: String, val start: Double, val end: Double, val note: Double, val gain: Double)

/** Two playheads met at [beat], at [where] on the canvas; [a] and [b] are track names. */
data class PlayheadMeeting(val beat: Double, val a: String, val b: String, val where: Vec2)

/** Track [track]'s [action] took effect at [beat], leaving its transpose, rate and direction as given. */
data class PlayheadChange(val beat: Double, val track: String, val action: MeetAction, val transpose: Double, val rate: Double, val direction: Int)

/** Where every playhead was at [beat]. */
class PlayheadFrame(val beat: Double, val positions: List<Vec2>)

/** A crossing of two tracks' strokes: track indices, each stroke's fraction there, and the point. */
class StrokeCrossing(val a: Int, val b: Int, val atA: Double, val atB: Double, val where: Vec2)

/**
 * The simulation. [tick] is the time step in beats; meetings are found at tick times, notes are
 * struck on each track's exact step clock.
 *
 * ```
 * val seq = StrokePlayheads(tracks, MeetingRule.Crossing(windowBeats = 0.75), seed = 4)
 * val run = seq.run(beats = 16.0)
 * showPatAudio(patTimed(run.timedNotes(), beats = 16.0), cps = 0.125)
 * ```
 */
class StrokePlayheads(
    val tracks: List<PlayheadTrack>,
    val rule: MeetingRule,
    seed: Long = 1,
    val tick: Double = 1.0 / 96,
) {
    private val random = java.util.Random(seed)
    private var beat = 0.0
    private val armed = HashMap<Pair<Int, Int>, Boolean>()
    private var crossingList: List<StrokeCrossing> = strokeCrossings(tracks)
    /** For each crossing and each of its two tracks, the last beat that track passed it. */
    private val passed = HashMap<Pair<Int, Int>, Double>()
    private var changes = ArrayList<PlayheadChange>()

    /** Every crossing of every pair of strokes, as they are now. */
    val crossings: List<StrokeCrossing> get() = crossingList

    /** Everything one [run] produced. */
    class Run(
        val notes: List<PlayheadNote>,
        val meetings: List<PlayheadMeeting>,
        /** Every change a meeting made, one per track it touched. */
        val changes: List<PlayheadChange>,
        val frames: List<PlayheadFrame>,
    ) {
        /** The notes as `afield.audio.synth.TimedNote`'s fields: start, end, note, gain. */
        fun timedNotes(): List<afield.audio.synth.TimedNote> =
            notes.map { afield.audio.synth.TimedNote(it.start, it.end, it.note, it.gain) }
    }

    /**
     * Replaces track [name]'s stroke with [points] and keeps its playhead at the same fraction of
     * the stroke, so a redrawn stroke carries the sequence on rather than restarting it.
     */
    fun restroke(name: String, points: List<Vec2>) {
        val t = tracks.first { it.name == name }
        t.path = PolylinePath(points)
        crossingList = strokeCrossings(tracks)
        passed.clear()
    }

    /** Runs for [beats] more beats, recording a frame every [frameEvery] beats. */
    fun run(beats: Double, frameEvery: Double = 0.25): Run {
        val notes = ArrayList<PlayheadNote>()
        val meetings = ArrayList<PlayheadMeeting>()
        val frames = ArrayList<PlayheadFrame>()
        changes = ArrayList()
        val end = beat + beats
        val ticks = Math.round(beats / tick)
        val frameTicks = maxOf(1L, Math.round(frameEvery / tick))
        val origin = beat
        for (k in 0 until ticks) {
            val now = origin + k * tick
            beat = now
            if (k % frameTicks == 0L) frames += PlayheadFrame(now, tracks.map { it.position })
            // Strike every step that falls in [now, now + tick).
            for (t in tracks) while (t.nextStep < now + tick - 1e-12 && t.nextStep < end - 1e-12) strike(t, notes)
            // Move.
            val before = tracks.map { it.at }
            for (t in tracks) advance(t)
            // Meet.
            findMeetings(now + tick, before, meetings)
        }
        beat = end
        return Run(notes, meetings, changes, frames)
    }

    private fun strike(t: PlayheadTrack, out: MutableList<PlayheadNote>) {
        val at = t.nextStep
        val stepLen = t.stepBeats / t.rate
        val p = (t.probability + t.lfo(LfoDestination.PROBABILITY, at)).coerceIn(0.0, 1.0)
        val roll = random.nextDouble()
        if (roll < p) {
            val gain = (t.gain + t.lfo(LfoDestination.GAIN, at)).coerceIn(0.0, 1.0)
            val len = (t.length + t.lfo(LfoDestination.LENGTH, at)).coerceIn(0.05, 1.0)
            val sub = stepLen / t.ratchet
            for (r in 0 until t.ratchet) {
                val s = at + r * sub
                out += PlayheadNote(t.name, s, s + sub * len, t.pitchOf(t.step), gain)
            }
        }
        t.step++
        t.nextStep = at + stepLen
    }

    private fun advance(t: PlayheadTrack) {
        var u = t.at + t.direction * t.rate * tick / t.passBeats
        if (t.pingPong) {
            if (u > 1.0) { u = 2.0 - u; t.direction = -1 }
            if (u < 0.0) { u = -u; t.direction = 1 }
        } else u -= floor(u)
        t.at = u.coerceIn(0.0, 1.0)
    }

    private fun meet(i: Int, j: Int, now: Double, where: Vec2, out: MutableList<PlayheadMeeting>) {
        out += PlayheadMeeting(now, tracks[i].name, tracks[j].name, where)
        apply(tracks[i], now); apply(tracks[j], now)
    }

    private fun apply(t: PlayheadTrack, now: Double) {
        when (t.onMeet) {
            MeetAction.TRANSPOSE -> {
                var x = t.transpose + t.transposeStep
                if (abs(x) > 12.0) x -= 24.0 * Math.signum(x)
                t.transpose = x
            }
            MeetAction.RATE -> {
                t.rate = if (t.rateUp) t.rate * t.rateFactor else t.rate / t.rateFactor
                t.rateUp = !t.rateUp
            }
            MeetAction.REVERSE -> t.direction = -t.direction
            MeetAction.NONE -> {}
        }
        changes += PlayheadChange(now, t.name, t.onMeet, t.transpose, t.rate, t.direction)
    }

    private fun findMeetings(now: Double, before: List<Double>, out: MutableList<PlayheadMeeting>) {
        when (val r = rule) {
            is MeetingRule.Radius -> {
                val pos = tracks.map { it.position }
                for (i in tracks.indices) for (j in i + 1 until tracks.size) {
                    val d = dist(pos[i], pos[j])
                    val key = i to j
                    val isArmed = armed[key] ?: (d > r.radius)
                    if (isArmed && d <= r.radius) {
                        armed[key] = false
                        meet(i, j, now, mid(pos[i], pos[j]), out)
                    } else if (!isArmed && d > r.radius * r.rearm) armed[key] = true
                    else armed[key] = isArmed
                }
            }
            is MeetingRule.Crossing -> {
                crossingList.forEachIndexed { c, x ->
                    val hitA = passedFraction(before[x.a], tracks[x.a].at, x.atA)
                    val hitB = passedFraction(before[x.b], tracks[x.b].at, x.atB)
                    if (hitA) passed[c to x.a] = now
                    if (hitB) passed[c to x.b] = now
                    if (hitA || hitB) {
                        val ta = passed[c to x.a]
                        val tb = passed[c to x.b]
                        if (ta != null && tb != null && abs(ta - tb) <= r.windowBeats + 1e-9) {
                            passed.remove(c to x.a); passed.remove(c to x.b)
                            meet(x.a, x.b, now, x.where, out)
                        }
                    }
                }
            }
        }
    }

    /** Did a playhead moving from [u0] to [u1] in one tick pass [c]? A wrap is a jump, not a pass. */
    private fun passedFraction(u0: Double, u1: Double, c: Double): Boolean {
        if (abs(u1 - u0) > 0.5) return (c >= maxOf(u0, u1) || c < minOf(u0, u1)) // wrapped round the end
        return (u0 < c && c <= u1) || (u1 <= c && c < u0)
    }

    companion object {
        private fun dist(a: Vec2, b: Vec2) = hypot(a.x.toDouble() - b.x, a.y.toDouble() - b.y)
        private fun mid(a: Vec2, b: Vec2) = vec((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

        /** Every crossing of every pair of the tracks' strokes, with each stroke's fraction there. */
        fun strokeCrossings(tracks: List<PlayheadTrack>): List<StrokeCrossing> {
            val out = ArrayList<StrokeCrossing>()
            for (i in tracks.indices) for (j in i + 1 until tracks.size) {
                val p = tracks[i].path
                val q = tracks[j].path
                for (s in 0 until p.points.size - 1) for (t in 0 until q.points.size - 1) {
                    val hit = segmentHit(p.points[s], p.points[s + 1], q.points[t], q.points[t + 1]) ?: continue
                    out += StrokeCrossing(i, j, p.fractionAt(s, hit.first), q.fractionAt(t, hit.second),
                        vec(p.points[s].x + (p.points[s + 1].x - p.points[s].x) * hit.first,
                            p.points[s].y + (p.points[s + 1].y - p.points[s].y) * hit.first))
                }
            }
            return out
        }

        /** Where segments ab and cd cross, as the parameter along each; null if they do not. */
        internal fun segmentHit(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Pair<Double, Double>? {
            val rx = b.x.toDouble() - a.x; val ry = b.y.toDouble() - a.y
            val sx = d.x.toDouble() - c.x; val sy = d.y.toDouble() - c.y
            val den = rx * sy - ry * sx
            if (abs(den) < 1e-12) return null
            val qx = c.x.toDouble() - a.x; val qy = c.y.toDouble() - a.y
            val t = (qx * sy - qy * sx) / den
            val u = (qx * ry - qy * rx) / den
            return if (t in 0.0..<1.0 && u in 0.0..<1.0) t to u else null
        }
    }
}

/** A polyline parametrised by arc length: fraction 0 is its first point, 1 its last. */
internal class PolylinePath(points: List<Vec2>) {
    val points: List<Vec2> = points.toList()
    private val cumulative: DoubleArray
    val length: Double

    init {
        require(points.size >= 2) { "a stroke needs at least two points" }
        cumulative = DoubleArray(points.size)
        for (i in 1 until points.size)
            cumulative[i] = cumulative[i - 1] + hypot(points[i].x.toDouble() - points[i - 1].x, points[i].y.toDouble() - points[i - 1].y)
        length = cumulative.last()
        require(length > 0) { "a stroke needs some length" }
    }

    /** The fraction of the way along at parameter [t] of segment [segment]. */
    fun fractionAt(segment: Int, t: Double): Double =
        (cumulative[segment] + t * (cumulative[segment + 1] - cumulative[segment])) / length

    /** The point at fraction [u]. */
    fun at(u: Double): Vec2 {
        val s = u.coerceIn(0.0, 1.0) * length
        var i = java.util.Arrays.binarySearch(cumulative, s)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, points.size - 2)
        val seg = cumulative[i + 1] - cumulative[i]
        val f = if (seg > 0) (s - cumulative[i]) / seg else 0.0
        val a = points[i]
        val b = points[i + 1]
        return vec(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }
}
