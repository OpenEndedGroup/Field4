@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import kotlin.math.roundToLong

/**
 * The sound on the [ORCA](../orca.md) page, rendered offline: ORCA's own operators running in
 * [OrcaHeadless], frame by frame, voiced by the same [PatSynthBank] calls a box makes live.
 *
 * Two scenes. `hello` is the page's first box: one ORCA, a melody and a bass. `duet` is the
 * `orca_duet` starting point: two ORCAs, with the box's exchange between them run after every frame
 * instead of in a `frames { }` loop. Each note starts on the sample its ORCA frame falls on, so the
 * clip is the rhythm ORCA computed, with none of a live run's network jitter.
 *
 * The grids and the exchange are copies of `dev_docs/agents/transcripts/orca_duet.yaml` and of the
 * page's first box; `OrcaSoundDocsTest` fails if they drift apart.
 */
object OrcaSoundDocs {
    const val SR = 48_000

    val HELLO = """
        #.ORCA.IN.FIELD.#
        .
        .2C8
        D2.8TCDEGAGED
        .:04
        .
        D8
        .:12C
    """.trimIndent()

    val LEAD = """
        #.LEAD.#
        .
        .2C8
        D2.8TCDEGAGED
        .:04
        .
        D8
        .:12C
    """.trimIndent()

    val ECHO = """
        #.ECHO.#
        .
        .3C8
        D3.8TCCCCCCCC
        .:25
    """.trimIndent()

    /** A rendered scene: the notes ORCA played, and the audio they made. */
    class Rendered(val left: FloatArray, val right: FloatArray, val notes: List<OrcaNote>)

    fun render(name: String): Rendered = when (name) {
        "hello" -> hello(seconds = 8.0)
        "duet" -> duet(seconds = 16.0)
        else -> throw IllegalArgumentException("No ORCA scene '$name'; there are 'hello' and 'duet'")
    }

    fun renderWav(name: String, outPath: String) {
        val rendered = render(name)
        require(rendered.notes.isNotEmpty()) { "scene '$name' played no notes" }
        require(rendered.left.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, rendered.left, rendered.right, SR, normalizePeak = 0.9f)
    }

    private fun hello(seconds: Double): Rendered = OrcaHeadless(HELLO, rows = 12).use { o ->
        val voice = Voicing(o)
        o.orca.onNote { voice.play(it) }
        voice.run(seconds) { o.step() }
    }

    private fun duet(seconds: Double): Rendered = OrcaHeadless(LEAD, cols = 40, rows = 9).use { leadEngine ->
        OrcaHeadless(ECHO, cols = 40, rows = 6).use { echoEngine ->
            val lead = leadEngine.orca
            val echo = echoEngine.orca
            val voice = Voicing(leadEngine)

            // The starting point's box from here on, with `voice` for `synth` and no ledger display.
            val up = mapOf('C' to 'D', 'D' to 'E', 'E' to 'G', 'G' to 'A', 'A' to 'C')
            val heard = ArrayDeque("CCCCCCCC".toList())
            var echoes = 0

            lead.onNote {
                if (it.channel == 0) { heard.addLast(it.note[0]); heard.removeFirst() }
                voice.play(it, pan = 0.35, wave = if (it.channel == 1) PatWave.SINE else PatWave.TRIANGLE)
            }
            echo.onNote {
                echoes++
                voice.play(it, pan = 0.7, wave = PatWave.SQUARE, gain = 0.35, cutoffHz = 2400.0)
            }

            voice.run(seconds) {
                leadEngine.step()
                echoEngine.step()
                val seen = lead.frame
                heard.forEachIndexed { i, note -> echo[5 + i, 3] = note }
                if (echoes >= 8) {
                    echoes = 0
                    val x = 5 + seen % 8
                    lead[x, 3] = up[lead[x, 3]] ?: 'C'
                }
                lead[4, 7] = if (lead[4, 4] in "GA") 'F' else 'C'
            }
        }
    }

    /** One bank for a scene, with each note placed on the sample its ORCA frame starts. */
    private class Voicing(private val clock: OrcaHeadless) {
        val bank = PatSynthBank(voices = 32, sampleRate = SR)
        private val scheduled = mutableListOf<PatSynthNote>()
        val notes = mutableListOf<OrcaNote>()

        fun play(note: OrcaNote, pan: Double = 0.5, wave: PatWave = bank.voice.wave, gain: Double = 1.0,
                 cutoffHz: Double? = null) {
            notes += note
            val start = (note.frame * clock.frameSeconds * SR).roundToLong()
            scheduled += bank.orcaSynthNote(note, start, pan, wave, gain, cutoffHz)
        }

        fun run(seconds: Double, frame: () -> Unit): Rendered {
            repeat((seconds / clock.frameSeconds).toInt()) { frame() }
            bank.schedule(scheduled)
            val total = (seconds * SR).toInt()
            val left = FloatArray(total); val right = FloatArray(total)
            val block = 512
            val l = FloatArray(block); val r = FloatArray(block)
            val ctx = SynthContext(SR.toFloat(), block)
            var at = 0
            var cycle = 1L
            while (at < total) {
                val n = minOf(block, total - at)
                l.fill(0f); r.fill(0f)
                // A node renders once per cycle and hands the same block back for the rest of it.
                ctx.cycleId = cycle++
                ctx.time = at.toFloat() / SR
                bank.render(l, r, n, ctx)
                System.arraycopy(l, 0, left, at, n); System.arraycopy(r, 0, right, at, n)
                at += n
            }
            return Rendered(left, right, notes.toList())
        }
    }
}
