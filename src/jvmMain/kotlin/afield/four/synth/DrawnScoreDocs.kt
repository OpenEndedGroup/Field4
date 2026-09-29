@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.Box
import afield.MarkType
import afield.NotationBuilder
import afield.NotationSpace
import afield.Rect
import afield.SessionInformation
import afield.four.audio.WavFloatArrayWriter
import afield.notation
import afield.stdlib.Vec2
import afield.stdlib.vec
import afield.thisSessionInfo
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
FIELD_TEST
id: synth.drawn.score
runner: jvm-main
entrypoint: afield.four.synth.DrawnScoreDocs
tags: [smoke, synth, pattern, notation, audio, wav, analysis, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "drawn score heard"
*/

/**
 * A drawn score, heard — the harness behind `public/docs/chain_drawn_score.md`.
 *
 * The route is three parts of Field that were written without each other in mind. A box draws a
 * score into the document's drawing with `notation { drawnScore() }`: a frame, a piano roll of
 * rectangles, a glissando drawn as a pen stroke, a held note drawn as a level line, and a text
 * label. `notation.space("score", x = 0.0..8.0, y = 84.0..48.0)` reads that drawing back as eight
 * beats of MIDI pitch, [toPattern] turns its events into a [PatPattern], and [patRenderStereo]
 * plays the pattern offline into a `.wav`.
 *
 * Then the sound is taken back apart. [listen] finds the onsets in the rendered audio with
 * [PatSoundDocs.onsetPeaks] and the pitch of each note with YIN ([patF0Frames]), and holds both
 * against the notes read from the drawing — so the claim "the audio plays the score" is a
 * measurement with an error in milliseconds and in semitones, not a caption.
 *
 * [renderWav] needs no Field window and no browser: it opens an ephemeral document of its own,
 * draws the score into its drawing with the same `notation { }` code a box uses, and reads it
 * back through the same `NotationSpace`. As the `synth.drawn.score` FIELD_TEST everything renders
 * to a temporary directory and nothing committed is written.
 */
object DrawnScoreDocs {

    const val SR: Int = PatSoundDocs.SR

    /** The score's frame on the canvas, in world units, and what its axes mean. */
    const val LEFT: Double = 260.0
    const val TOP: Double = 40.0
    const val WIDTH: Double = 800.0
    const val HEIGHT: Double = 400.0
    const val BEATS: Double = 8.0
    const val HIGH: Double = 84.0
    const val LOW: Double = 48.0

    /** One cycle is the whole score, so 0.25 cycles a second is eight beats in four seconds. */
    const val CPS: Double = 0.25
    const val CYCLES: Int = 2

    /**
     * The voice. Detached on purpose: each note holds for 40% of its length and its tail is gone
     * 16 ms later, so even the glissando's 62 ms steps are separated by true silence. With a
     * longer tail [PatSoundDocs.onsetPeaks] loses steps — it re-arms against the level of the
     * window that triggered it, which on a steep attack is a point on the rising edge rather than
     * the note's peak, so a dip to a few percent between steps is not always deep enough.
     */
    val VOICE: PatVoiceSpec = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)

    /** The register YIN is told about. The score spans MIDI 60..79, about 262..784 Hz. */
    const val MIN_HZ: Double = 220.0
    const val MAX_HZ: Double = 900.0

    /** Where beat [beat] and pitch [midi] are on the canvas. */
    fun at(beat: Double, midi: Double): Vec2 =
        vec(LEFT + beat / BEATS * WIDTH, TOP + (HIGH - midi) / (HIGH - LOW) * HEIGHT)

    /** The piano-roll notes as drawn: start beat, end beat, MIDI pitch. */
    val ROLL: List<Triple<Double, Double, Double>> = listOf(
        Triple(0.0, 1.0, 67.0), Triple(1.0, 1.5, 71.0), Triple(1.5, 2.0, 74.0),
        Triple(2.0, 3.0, 72.0), Triple(3.0, 3.5, 71.0), Triple(3.5, 4.0, 67.0),
        Triple(7.5, 8.0, 72.0),
    )

    /** The glissando's ends: beat 4 at MIDI 60 up to beat 6 at MIDI 79, eased in and out. */
    const val GLISS_FROM: Double = 60.0
    const val GLISS_TO: Double = 79.0

    /** The held note: beats 6..7.5 at MIDI 76, drawn with a wobble a hand would give it. */
    const val HELD: Double = 76.0

    /** A space over the score frame, with the axes the page uses. */
    fun space(box: Box): NotationSpace = box.notation.space("score", x = 0.0..BEATS, y = HIGH..LOW)

    // ── rendering, from a drawing or headlessly ─────────────────────────────────────────────

    /** The score as heard: [space]'s notes, played through [VOICE]. */
    fun render(score: NotationSpace, cycles: Int = CYCLES, cps: Double = CPS): PatSoundDocs.Stereo =
        patRenderStereo(score.toPattern(), cycles = cycles, cps = cps, voice = VOICE)

    /**
     * Runs [block] with a box in an ephemeral document of its own, so a drawing can be written and
     * read with no Field window. The thread's current document is restored afterwards.
     */
    fun <T> headless(block: (Box) -> T): T {
        val previous = thisSessionInfo.get()
        val root = Box("root")
        val session = SessionInformation(filename = "<drawn-score>", documentName = "drawn-score", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        try {
            val box = Box("drawn-score").also {
                it.inside = session
                session.boxes[it.id] = it
                it.frame = Rect(20.0, 40.0, 200.0, 60.0)
            }
            box.notation { drawnScore() }
            return block(box)
        } finally {
            if (previous == null) thisSessionInfo.remove() else thisSessionInfo.set(previous)
        }
    }

    /** The published clip: the score drawn headlessly, read back, and rendered. */
    fun renderWav(name: String, outPath: String) {
        require(name == "drawn-score") { "unknown DrawnScoreDocs scene '$name'; known: [drawn-score]" }
        val audio = headless { render(space(it)) }
        require(audio.left.any { it != 0f }) { "the drawn score rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    // ── listening back ──────────────────────────────────────────────────────────────────────

    /** One note of the score against what the audio did there. */
    class Heard(
        val note: DrawnNote,
        /** When the note should strike, in seconds from the start of the render. */
        val scheduledSeconds: Double,
        /** The detected onset nearest to it, in seconds; NaN when none was within 30 ms. */
        val onsetSeconds: Double,
        /** Median YIN pitch over the note's held part, MIDI; NaN when no frame there was voiced. */
        val heardMidi: Double,
    ) {
        val onsetErrorMs: Double get() = (onsetSeconds - scheduledSeconds) * 1000.0
        val pitchError: Double get() = heardMidi - note.pitch
    }

    /** What [listen] measured: one [Heard] per scheduled note, plus the detector's own totals. */
    class Listening(
        val heard: List<Heard>,
        val onsetsDetected: Int,
        val frames: List<PatF0Frame>,
    ) {
        val scheduled: Int get() = heard.size
        val matchedOnsets: Int get() = heard.count { it.onsetSeconds.isFinite() }
        val maxOnsetErrorMs: Double get() = heard.filter { it.onsetSeconds.isFinite() }.maxOfOrNull { abs(it.onsetErrorMs) } ?: Double.NaN
        val pitched: Int get() = heard.count { it.heardMidi.isFinite() }
        val maxPitchError: Double get() = heard.filter { it.heardMidi.isFinite() }.maxOfOrNull { abs(it.pitchError) } ?: Double.NaN

        /** The voiced frames as (seconds, heard MIDI, drawn MIDI of the note sounding then). */
        fun trace(): List<Triple<Double, Double, Double>> = frames.filter { it.voiced }.mapNotNull { f ->
            val t = f.centerSample.toDouble() / SR
            val n = heard.lastOrNull { it.scheduledSeconds <= t } ?: return@mapNotNull null
            Triple(t, f.midi, n.note.pitch)
        }
    }

    /**
     * Measures [audio] against [notes]: an onset for every strike, and a pitch for every note.
     * [notes] are in beats of a [BEATS]-beat cycle played at [cps] for [cycles] cycles.
     */
    fun listen(audio: PatSoundDocs.Stereo, notes: List<DrawnNote>, cycles: Int = CYCLES, cps: Double = CPS): Listening {
        val mono = audio.mono
        val secondsPerBeat = 1.0 / cps / BEATS
        val onsets = PatSoundDocs.onsetPeaks(mono, threshold = 0.05).map { it.start.toDouble() / SR }
        val frames = patF0Frames(mono, SR, minHz = MIN_HZ, maxHz = MAX_HZ)
        val heard = (0 until cycles).flatMap { c ->
            notes.map { n ->
                val start = (c * BEATS + n.start) * secondsPerBeat
                val hold = n.duration * secondsPerBeat * VOICE.sustain
                val onset = onsets.minByOrNull { abs(it - start) }?.takeIf { abs(it - start) <= 0.030 } ?: Double.NaN
                // Frames wholly inside the held part, a little in from each end.
                val inside = frames.filter { f ->
                    val a = f.startSample.toDouble() / SR
                    val b = (f.startSample + f.spanSamples).toDouble() / SR
                    f.voiced && a >= start + 0.004 && b <= start + hold - 0.002
                }.map { it.midi }.sorted()
                val midi = if (inside.isEmpty()) Double.NaN else inside[inside.size / 2]
                Heard(n, start, onset, midi)
            }
        }
        return Listening(heard, onsets.size, frames)
    }

    // ── FIELD_TEST entry point ──────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "drawn-score").path)
        dir.mkdirs()

        val (notes, audio) = headless { box ->
            val score = space(box)
            // The reading, checked against the drawing before anything is played.
            val events = score.events
            check(events.count { it.mark.kind == MarkType.RECTANGLE } == ROLL.size) { "expected ${ROLL.size} rectangles, read $events" }
            check(events.none { it.mark.kind == MarkType.FRAME }) { "the frame that defines the space was read as an event" }
            score.notes() to render(score)
        }
        val rolled = notes.filter { it.event.mark.kind == MarkType.RECTANGLE }
        ROLL.zip(rolled).forEach { (drawn, read) ->
            check(abs(read.start - drawn.first) < 1e-6 && abs(read.end - drawn.second) < 1e-6 && abs(read.pitch - drawn.third) < 1e-6) {
                "drew $drawn, read $read"
            }
        }
        val gliss = notes.filter { it.event.mark.name == "gliss" }
        check(gliss.size == 16) { "expected the two-beat glissando as 16 eighth-beat steps, got ${gliss.size}" }
        check(gliss.zipWithNext().all { (a, b) -> b.pitch > a.pitch }) { "the glissando does not rise: $gliss" }
        check(abs(gliss.first().pitch - GLISS_FROM) < 0.05) { "the glissando starts at ${gliss.first().pitch}" }
        val held = notes.single { it.event.mark.name == "held" }
        check(abs(held.pitch - HELD) < 0.5) { "the held note reads ${held.pitch}" }
        check(notes.none { it.event.mark.kind == MarkType.TEXT }) { "the label was played" }
        println("read ${notes.size} notes: ${rolled.size} from rectangles, ${gliss.size} glissando steps, 1 held")

        val wav = File(dir, "drawn-score.wav")
        renderWav("drawn-score", wav.path)
        val bytes = wav.readBytes()
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && bytes.size == 44 + audio.frames * 4) { "bad WAV at $wav" }
        println("  wrote ${wav.absolutePath} (${bytes.size} bytes)")
        val heard = listen(audio, notes)
        println(
            "heard: ${heard.onsetsDetected} onsets detected for ${heard.scheduled} scheduled, " +
                "${heard.matchedOnsets} matched within 30 ms, worst ${"%.1f".format(heard.maxOnsetErrorMs)} ms; " +
                "${heard.pitched} notes pitched, worst ${"%.3f".format(heard.maxPitchError)} semitones",
        )
        check(heard.onsetsDetected == heard.scheduled) {
            "detected ${heard.onsetsDetected} onsets for ${heard.scheduled} notes; unmatched: " +
                heard.heard.filter { !it.onsetSeconds.isFinite() }.joinToString { "%.3fs %s".format(it.scheduledSeconds, it.note) }
        }
        check(heard.matchedOnsets == heard.scheduled) { "only ${heard.matchedOnsets} of ${heard.scheduled} onsets matched" }
        check(heard.maxOnsetErrorMs <= 12.0) { "an onset is ${heard.maxOnsetErrorMs} ms off" }
        check(heard.pitched == heard.scheduled) { "only ${heard.pitched} of ${heard.scheduled} notes had a voiced frame" }
        check(heard.maxPitchError <= 0.25) { "a note is ${heard.maxPitchError} semitones off" }

        println("drawn score heard")
    }
}

/**
 * Draws the score [DrawnScoreDocs] and `chain_drawn_score.md` read: a frame named "score", a piano
 * roll of rectangles, a glissando drawn as a pen stroke, a held note drawn as a level line with a
 * hand's wobble in it, and a text label. Call it inside `notation { }`.
 */
fun NotationBuilder.drawnScore() {
    val d = DrawnScoreDocs
    stroke = "#868e96"
    frame("score", d.LEFT, d.TOP, d.WIDTH, d.HEIGHT)
    fill = "#1971c2"
    stroke = "#1971c2"
    d.ROLL.forEachIndexed { i, (a, b, midi) ->
        val topLeft = d.at(a, midi + 0.5)
        val bottomRight = d.at(b, midi - 0.5)
        rect(topLeft.x, topLeft.y, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y, name = "note ${i + 1}")
    }
    fill = "transparent"
    // Eased, as a hand draws one: slow off the start, fast through the middle, slow into the top.
    val glide = (0..64).map { k ->
        val u = k / 64.0
        d.at(4.0 + 2.0 * u, d.GLISS_FROM + (d.GLISS_TO - d.GLISS_FROM) * (1 - cos(PI * u)) / 2)
    }
    stroke(glide, name = "gliss", stroke = "#e03131")
    val level = (0..30).map { k -> d.at(6.0 + 1.5 * k / 30.0, d.HELD + 0.2 * sin(k * 1.7)) }
    stroke(level, name = "held", stroke = "#2f9e44")
    val label = d.at(5.2, 66.0)
    text("gliss.", label.x, label.y, size = 18, color = "#e03131")
}
