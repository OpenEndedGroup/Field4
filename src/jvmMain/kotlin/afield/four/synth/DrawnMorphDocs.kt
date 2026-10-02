@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.Box
import afield.NotationBuilder
import afield.Rect
import afield.SessionInformation
import afield.four.audio.WavFloatArrayWriter
import afield.notation
import afield.stdlib.Vec2
import afield.stdlib.matchPoints
import afield.stdlib.resampleByLength
import afield.stdlib.vec
import afield.thisSessionInfo
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.drawn.morph
runner: jvm-main
entrypoint: afield.four.synth.DrawnMorphDocs
tags: [smoke, synth, pattern, notation, audio, wav, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "drawn morph heard"
*/

/**
 * A drawn morph, heard — the harness behind `public/docs/chain_drawn_morph.md`.
 *
 * Two strokes in the document's drawing ([morphShapes]: a circle named "from" and a heart named
 * "to", drawn from a different place and the other way round) are read back with
 * `notation.named(...)`, resampled to [N] points each with [resampleByLength], and paired with
 * [matchPoints]. [morphBetween] is the in-between cloud and [spreadOf] its size; [morphNotes] plays
 * that size as pitch while the cloud goes out to the heart and back, once with the matched pairs
 * and once with the pairs given by list index. [renderWav] draws the shapes into an ephemeral
 * document of its own, so the published `.wav`s come from the same reading the page's boxes do.
 */
object DrawnMorphDocs {

    const val SR: Int = PatSoundDocs.SR

    /** Points per shape after resampling. */
    const val N: Int = 240

    /** Where both shapes are centred on the canvas, and their size, in world units. */
    const val CX: Double = 520.0
    const val CY: Double = 260.0
    const val R: Double = 160.0

    /** One cycle is eight beats; at a quarter cycle a second the morph out and back takes four seconds. */
    const val BEATS: Double = 8.0
    const val CPS: Double = 0.25
    const val CYCLES: Int = 2

    val VOICE: PatVoiceSpec = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.6)

    /** The circle, as a hand would draw it: from the right, anticlockwise on screen, bunched at the start. */
    fun circle(): List<Vec2> = (0..72).map { k ->
        val u = k / 72.0
        val a = -2 * PI * u * u * (3 - 2 * u)            // eased: slow off the start and into the end
        vec(CX + R * cos(a), CY + R * sin(a))
    }

    /** The heart, from its bottom point, clockwise on screen (y grows down). */
    fun heart(): List<Vec2> = (0..120).map { k ->
        val a = PI + 2 * PI * k / 120.0
        val x = 16 * sin(a).pow(3)
        val y = 13 * cos(a) - 5 * cos(2 * a) - 2 * cos(3 * a) - cos(4 * a)
        vec(CX + R / 17 * x, CY - R / 17 * y + 10)
    }

    /** The resampled pair the page's boxes read back from the drawing. */
    fun shapes(box: Box): Pair<List<Vec2>, List<Vec2>> {
        val from = box.notation.named("from")!!.points.resampleByLength(N, closed = true)
        val to = box.notation.named("to")!!.points.resampleByLength(N, closed = true)
        return from to to
    }

    /**
     * The morph played as pitch, word for word the loop on the page (`DrawnMorphPageTest` compares
     * them): 32 steps out to [partner] and back, each step a quarter beat at a pitch that is MIDI 72
     * when the cloud is the circle's size and falls an octave for every halving of it.
     */
    fun morphNotes(from: List<Vec2>, partner: List<Vec2>): List<TimedNote> {
        val notes = mutableListOf<TimedNote>()
        val spread0 = spreadOf(from)
        for (k in 0 until 32) {
            val t = if (k < 16) k / 15.0 else (31 - k) / 15.0   // out to the heart, then back
            val midi = 72 + 12 * log2(spreadOf(morphBetween(from, partner, t)) / spread0)
            notes += TimedNote(k * 0.25, k * 0.25 + 0.25, midi)
        }
        return notes
    }

    fun render(notes: List<TimedNote>): PatSoundDocs.Stereo =
        patRenderStereo(patTimed(notes, beats = BEATS), cycles = CYCLES, cps = CPS, voice = VOICE)

    /** Runs [block] with a box in an ephemeral document whose drawing holds [morphShapes]. */
    fun <T> headless(block: (Box) -> T): T {
        val previous = thisSessionInfo.get()
        val root = Box("root")
        val session = SessionInformation(filename = "<drawn-morph>", documentName = "drawn-morph", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        try {
            val box = Box("drawn-morph").also {
                it.inside = session
                session.boxes[it.id] = it
                it.frame = Rect(20.0, 40.0, 200.0, 60.0)
            }
            box.notation { morphShapes() }
            return block(box)
        } finally {
            if (previous == null) thisSessionInfo.remove() else thisSessionInfo.set(previous)
        }
    }

    /** The notes each published clip plays. */
    fun sceneNotes(name: String): List<TimedNote> = headless { box ->
        val (from, to) = shapes(box)
        when (name) {
            "drawn-morph-matched" -> morphNotes(from, matchPoints(from, to).reorder(to))
            "drawn-morph-index" -> morphNotes(from, to)
            else -> error("unknown DrawnMorphDocs scene '$name'; known: [drawn-morph-matched, drawn-morph-index]")
        }
    }

    fun renderWav(name: String, outPath: String) {
        val audio = render(sceneNotes(name))
        require(audio.left.any { it != 0f }) { "the drawn morph rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: File(System.getProperty("java.io.tmpdir"), "drawn-morph").path)
        dir.mkdirs()
        val (matchedCost, indexCost) = headless { box ->
            val (from, to) = shapes(box)
            check(from.size == N && to.size == N) { "resampled to ${from.size} and ${to.size}, not $N" }
            val m = matchPoints(from, to)
            val index = from.indices.sumOf { (from[it].x - to[it].x).toDouble().pow(2) + (from[it].y - to[it].y).toDouble().pow(2) }
            m.squaredCost to index
        }
        check(matchedCost < indexCost / 2) { "matching should at least halve the index-order travel: $matchedCost vs $indexCost" }
        val matched = sceneNotes("drawn-morph-matched")
        val index = sceneNotes("drawn-morph-index")
        val dipMatched = 72 - matched.minOf { it.note }
        val dipIndex = 72 - index.minOf { it.note }
        check(dipIndex > 2 * dipMatched) { "the index-order morph should collapse further: dips $dipIndex vs $dipMatched" }
        println("travel matched %.0f, by index %.0f; pitch dips %.2f vs %.2f semitones".format(matchedCost, indexCost, dipMatched, dipIndex))
        for (name in listOf("drawn-morph-matched", "drawn-morph-index")) {
            val wav = File(dir, "$name.wav")
            renderWav(name, wav.path)
            val bytes = wav.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && bytes.size > 44) { "bad WAV at $wav" }
            println("  wrote ${wav.absolutePath} (${bytes.size} bytes)")
        }
        println("drawn morph heard")
    }
}

/** The in-between cloud: point `i` a fraction [t] of the way from `from[i]` to `to[i]`. */
fun morphBetween(from: List<Vec2>, to: List<Vec2>, t: Double): List<Vec2> =
    List(from.size) { vec(from[it].x + (to[it].x - from[it].x) * t, from[it].y + (to[it].y - from[it].y) * t) }

/** How big a cloud is: the root-mean-square distance of its points from their centroid. */
fun spreadOf(points: List<Vec2>): Double {
    val cx = points.sumOf { it.x.toDouble() } / points.size
    val cy = points.sumOf { it.y.toDouble() } / points.size
    return sqrt(points.sumOf { (it.x - cx).pow(2) + (it.y - cy).pow(2) } / points.size)
}

/** The two strokes `chain_drawn_morph.md` reads: a circle named "from" and a heart named "to". Call it inside `notation { }`. */
fun NotationBuilder.morphShapes() {
    stroke(DrawnMorphDocs.circle(), name = "from", stroke = "#1971c2")
    stroke(DrawnMorphDocs.heart(), name = "to", stroke = "#e03131")
}
