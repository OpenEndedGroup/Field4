@file:afield.runtime.completion.CompletionCategory("Patterns")
package afield.audio.synth

import afield.audio.engine.WavFloatArrayWriter
import afield.document.graph.Box
import afield.math.linalg.Vec2
import afield.math.linalg.vec
import afield.ui.notation.LfoDestination
import afield.ui.notation.MeetAction
import afield.ui.notation.MeetingRule
import afield.ui.notation.NotationBuilder
import afield.ui.notation.PlayheadLfo
import afield.ui.notation.PlayheadTrack
import afield.ui.notation.StrokePlayheads
import afield.ui.notation.notation
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
FIELD_TEST
id: synth.stroke.playheads
runner: jvm-main
entrypoint: afield.audio.synth.StrokePlayheadsDocs
tags: [smoke, synth, pattern, notation, audio, wav, analysis, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "stroke playheads heard"
*/

/**
 * Playheads on drawn strokes, heard — the harness behind the "Playheads along strokes" section
 * of `public/docs/chain_drawn_score.md`, after anima (https://llllllll.co/t/anima/75862).
 *
 * [threeStrokes] draws three crossing strokes into a document's drawing. [tracks] reads them back
 * from `notation.strokes` as [PlayheadTrack]s — a wave that transposes when it meets another
 * playhead, a rising line that changes rate, an arc that turns round — and [StrokePlayheads] runs
 * them, with meetings at stroke crossings. The notes play through [patTimed] and
 * [patRenderStereo] into a `.wav`.
 *
 * [listen] then renders each track alone and checks, from the audio, that every note struck where
 * the simulation put it and at its pitch, and that the wave's transposition — read from the heard
 * pitches — changes only between two notes a meeting falls between. [renderWav] needs no Field
 * window: it draws into an ephemeral document, as [DrawnScoreDocs.headless] does.
 */
object StrokePlayheadsDocs {

    const val SR: Int = PatSoundDocs.SR
    const val BEATS: Double = 32.0
    /** Eight beats every four seconds: 120 beats a minute, so 32 beats is 16 seconds. */
    const val CPS: Double = 1.0 / 16.0
    const val SEED: Long = 4

    /** The frame the strokes are drawn in, in world units. */
    const val LEFT: Double = 260.0
    const val TOP: Double = 40.0
    const val WIDTH: Double = 800.0
    const val HEIGHT: Double = 400.0

    /** Two playheads meet when both pass a crossing of their strokes within this many beats. */
    const val WINDOW: Double = 1.0

    /** Detached, as on the drawn-score page, so every strike is a separate onset. */
    val VOICE: PatVoiceSpec = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)

    /** The three strokes, as drawn: a wave across, a line rising, and an arc down through the middle. */
    fun strokes(): Map<String, List<Vec2>> = linkedMapOf(
        "wave" to (0..80).map { k -> val u = k / 80.0; vec(LEFT + 40 + u * (WIDTH - 80), TOP + HEIGHT / 2 + 90 * sin(2 * PI * 1.5 * u)) },
        "rise" to (0..40).map { k -> val u = k / 40.0; vec(LEFT + 80 + u * (WIDTH - 160), TOP + HEIGHT - 40 - u * (HEIGHT - 80)) },
        "arc" to (0..60).map { k -> val u = k / 60.0; vec(LEFT + WIDTH / 2 + 180 * sin(PI * u) - 60, TOP + 20 + u * (HEIGHT - 40)) },
    )

    /** The tracks the page plays, on [strokes] (or on strokes read back from a drawing). */
    fun tracks(strokes: Map<String, List<Vec2>>): List<PlayheadTrack> = listOf(
        PlayheadTrack(
            "wave", strokes.getValue("wave"), passBeats = 8.0, stepBeats = 0.5, degrees = listOf(0, 2, 4, 2),
            root = 67.0, onMeet = MeetAction.TRANSPOSE, transposeStep = 5.0,
            lfos = listOf(PlayheadLfo(LfoDestination.GAIN, periodBeats = 8.0, depth = 0.15)),
        ),
        PlayheadTrack(
            "rise", strokes.getValue("rise"), passBeats = 6.0, stepBeats = 1.0, degrees = listOf(0, 4, 3),
            root = 60.0, pingPong = true, onMeet = MeetAction.RATE, rateFactor = 2.0, probability = 0.9,
            lfos = listOf(PlayheadLfo(LfoDestination.LENGTH, periodBeats = 6.0, depth = 0.15, freezeAt = 16.0)),
        ),
        PlayheadTrack(
            "arc", strokes.getValue("arc"), passBeats = 5.0, stepBeats = 2.0, degrees = listOf(0, 3), ratchet = 2,
            root = 55.0, start = 0.5, onMeet = MeetAction.REVERSE,
        ),
    )

    /** The page's run: the three tracks, meetings at crossings within [WINDOW] beats. */
    fun run(strokes: Map<String, List<Vec2>> = strokes()): StrokePlayheads.Run =
        StrokePlayheads(tracks(strokes), MeetingRule.Crossing(WINDOW), seed = SEED).run(BEATS)

    /** The strokes a box drew, read back from its notation by name. */
    fun readStrokes(box: Box): Map<String, List<Vec2>> =
        box.notation.strokes.filter { it.name != null }.associate { it.name!! to it.points }

    /** [notes] as one pattern cycle of [BEATS], played through [VOICE]. */
    fun render(notes: List<TimedNote>): PatSoundDocs.Stereo =
        patRenderStereo(patTimed(notes, beats = BEATS), cycles = 1, cps = CPS, voice = VOICE)

    /** The published clip: the strokes drawn headlessly, read back, run and rendered. */
    fun renderWav(name: String, outPath: String) {
        require(name == "stroke-playheads") { "unknown StrokePlayheadsDocs scene '$name'; known: [stroke-playheads]" }
        val strokes = DrawnScoreDocs.headless { box ->
            box.notation { threeStrokes() }
            readStrokes(box)
        }
        val audio = render(run(strokes).timedNotes())
        require(audio.left.any { it != 0f }) { "the stroke playheads rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    /** One track heard alone: how many of its notes struck on time and at pitch, and its transpositions heard. */
    class TrackHeard(
        val track: String,
        val scheduled: Int,
        val onsetsDetected: Int,
        val matchedOnsets: Int,
        val maxOnsetErrorMs: Double,
        val pitched: Int,
        val maxPitchError: Double,
        /** (seconds, heard MIDI) per note. */
        val heard: List<Pair<Double, Double>>,
    ) {
        fun describe(): String =
            "$track: $scheduled notes, $onsetsDetected onsets, $matchedOnsets within 30 ms (worst %.1f ms), $pitched pitched (worst %.3f st)"
                .format(maxOnsetErrorMs, maxPitchError)
    }

    /** Renders each track of [run] alone and measures it against its notes. */
    fun listen(run: StrokePlayheads.Run): List<TrackHeard> {
        val secondsPerBeat = 1.0 / CPS / BEATS
        return run.notes.map { it.track }.distinct().map { name ->
            val notes = run.notes.filter { it.track == name }
            val mono = render(notes.map { TimedNote(it.start, it.end, it.note, it.gain) }).mono
            val onsets = PatSoundDocs.onsetPeaks(mono, threshold = 0.05).map { it.start.toDouble() / SR }
            val frames = patF0Frames(mono, SR, minHz = 150.0, maxHz = 1200.0)
            var matched = 0
            var worstMs = 0.0
            var pitched = 0
            var worstPitch = 0.0
            val heard = ArrayList<Pair<Double, Double>>()
            for (n in notes) {
                val start = n.start * secondsPerBeat
                val hold = (n.end - n.start) * secondsPerBeat * VOICE.sustain
                val onset = onsets.minByOrNull { abs(it - start) }?.takeIf { abs(it - start) <= 0.030 }
                if (onset != null) { matched++; worstMs = maxOf(worstMs, abs(onset - start) * 1000) }
                val inside = frames.filter { f ->
                    val a = f.startSample.toDouble() / SR
                    val b = (f.startSample + f.spanSamples).toDouble() / SR
                    f.voiced && a >= start + 0.004 && b <= start + hold - 0.002
                }.map { it.midi }.sorted()
                val midi = if (inside.isEmpty()) Double.NaN else inside[inside.size / 2]
                if (midi.isFinite()) { pitched++; worstPitch = maxOf(worstPitch, abs(midi - n.note)) }
                heard += start to midi
            }
            TrackHeard(name, notes.size, onsets.size, matched, worstMs, pitched, worstPitch, heard)
        }
    }

    /**
     * The wave's transposition as heard: each heard pitch minus the untransposed pitch of its
     * step, rounded to a semitone. Returns (note index, transposition) per note.
     */
    fun heardTranspositions(run: StrokePlayheads.Run, heard: TrackHeard): List<Int> {
        val wave = tracks(strokes()).first { it.name == "wave" }
        val notes = run.notes.filter { it.track == "wave" }
        return notes.indices.map { k -> Math.round(heard.heard[k].second - wave.pitchOf(k)).toInt() }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "stroke-playheads").path)
        dir.mkdirs()
        val drawn = DrawnScoreDocs.headless { box -> box.notation { threeStrokes() }; readStrokes(box) }
        check(drawn.keys == strokes().keys) { "read back ${drawn.keys}" }
        val run = run(drawn)
        check(run.notes == run(strokes()).notes) { "the drawing read back does not play as the strokes drawn" }
        println("${run.notes.size} notes, ${run.meetings.size} meetings: " +
            run.meetings.joinToString { "%.3f %s/%s".format(it.beat, it.a, it.b) })
        check(run.meetings.size >= 3) { "expected at least three meetings in $BEATS beats" }
        val meetBeats = run.meetings.map { it.beat }.toSet()
        check(run.changes.all { it.beat in meetBeats }) { "a change happened away from a meeting" }

        val wav = File(dir, "stroke-playheads.wav")
        renderWav("stroke-playheads", wav.path)
        println("  wrote ${wav.absolutePath} (${wav.length()} bytes)")

        val heard = listen(run)
        heard.forEach { println(it.describe()) }
        for (h in heard) {
            check(h.matchedOnsets == h.scheduled && h.onsetsDetected == h.scheduled) { h.describe() }
            check(h.pitched == h.scheduled && h.maxPitchError <= 0.25) { h.describe() }
        }
        // From the audio alone: the wave's transposition changes only across a meeting.
        val notes = run.notes.filter { it.track == "wave" }
        val trans = heardTranspositions(run, heard.first { it.track == "wave" })
        var heardChanges = 0
        for (k in 1 until notes.size) if (trans[k] != trans[k - 1]) {
            heardChanges++
            check(run.meetings.any { it.beat > notes[k - 1].start && it.beat <= notes[k].start + 1e-9 && "wave" in listOf(it.a, it.b) }) {
                "the wave's heard transposition changed between beats ${notes[k - 1].start} and ${notes[k].start} with no meeting"
            }
        }
        println("wave: heard transposition changed $heardChanges times, each across a meeting")
        check(heardChanges > 0) { "the wave never changed" }
        println("stroke playheads heard")
    }
}

/**
 * Draws the three crossing strokes [StrokePlayheadsDocs] plays, named `wave`, `rise` and `arc`, in
 * a frame named "anima". Call it inside `notation { }`.
 */
fun NotationBuilder.threeStrokes() {
    val d = StrokePlayheadsDocs
    stroke = "#868e96"
    frame("anima", d.LEFT, d.TOP, d.WIDTH, d.HEIGHT)
    val colours = listOf("#1971c2", "#e03131", "#2f9e44")
    d.strokes().entries.forEachIndexed { i, (name, points) -> stroke(points, name = name, stroke = colours[i]) }
}
