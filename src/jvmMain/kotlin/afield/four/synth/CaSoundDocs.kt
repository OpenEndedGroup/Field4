@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import afield.stdlib.binomialParity
import afield.stdlib.centerColumn
import afield.stdlib.elementaryCa
import afield.stdlib.longestRunLength
import java.io.File
import kotlin.math.abs

/*
FIELD_TEST
id: synth.ca.sound
runner: jvm-main
entrypoint: afield.four.synth.CaSoundDocs
tags: [smoke, synth, pattern, alife, audio, wav, capture, cpu]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "ca sound docs rendered"
*/

/**
 * A cellular automaton as a rhythm, by way of the pattern language's own inverse parser.
 *
 * The route is four steps and each one belongs to a different part of Field: an elementary rule is
 * stepped by `alife_ca.kt`, one of its rows becomes a step mask, [patCondense] finds the shortest
 * mini-notation that plays that mask, and [patRenderStereo] plays the notation through [patVoice].
 * So the sound is not "a sonification of a CA" in the loose sense. It is the CA's row, spelled as
 * something a musician would have typed, and then played.
 *
 * The condenser earns its place in the middle of that: it is a **measurement**. A row with
 * structure gets a much shorter spelling than one without, so [CaBar.compression] separates rule
 * 90's self-similar rows from rule 30's centre column. Neither ratio can exceed one, because the
 * search is never allowed to lose to the flat spelling and a flat spelling carries overhead that
 * any grouping saves, so the `rule30-hats` scene asserts the *ratio* between the two -- rule 30's
 * at least 1.4 times rule 90's -- rather than a floor on either. That gap is the difference between
 * the two scenes here, and you can hear it.
 *
 * # Every scene has an oracle outside the CA
 *
 * Rule 90 from a single seed is Pascal's triangle mod 2, so [binomialParity] computes what the
 * stepper should have produced without stepping anything, and [gasketBar] checks the two against
 * each other before any sound is made. Row *n*'s population is exactly `2^popcount(n)`, which is
 * what [gasketScale] turns into pitch, so the melody's note sequence is checkable digit by digit.
 * [rule30Bar] has no such closed form, so what is asserted there is the negative: a population near
 * half, and a longest run short enough that the sequence has no long runs in it.
 *
 * # What it does not do
 *
 * No realtime engine, no audio device, no GPU, no Field window. The renders are deterministic, so a
 * committed `.wav` regenerates byte-stably, which is the contract `DOUGH_DSP_PLOT_GALLERY.md` sets
 * and [PatSoundDocs] follows. Run as the `synth.ca.sound` FIELD_TEST, every scene renders to a
 * temporary directory and runs its own checks, and nothing committed is written. Pass an output
 * directory as `args[0]` to regenerate the page's `.wav`s.
 */

/**
 * The three cellular-automaton scenes, their oracles, and the renderer that writes them out.
 *
 * [SCENES] is the gallery in order; [renderWav] writes one of them to a path.
 */
object CaSoundDocs {

    /** Shared with [PatSoundDocs], because the two galleries write the same kind of file. */
    const val SR: Int = PatSoundDocs.SR

    /**
     * One bar derived from a cellular automaton: where it came from, the steps it fires on, and the
     * shortest mini-notation that plays it.
     *
     * [condensation] is the whole reason this is a class rather than a `List<Boolean>`. It carries
     * the notation, the flat spelling it had to beat, whether the search signed for the result, and
     * the bounds it ran under.
     */
    class CaBar(
        /** How the mask was derived, in a form that fits a caption. */
        val label: String,
        /** One entry per step; `true` is an onset. */
        val mask: List<Boolean>,
        /** The atom each onset plays. */
        val on: String,
    ) {
        val condensation: PatCondensation = patCondense(mask, on)

        /** The condensed notation, parsed. Identical to the mask's own pattern when the search signed for it. */
        val pattern: PatPattern<String> get() = condensation.pattern

        val steps: Int get() = mask.size
        val onsets: Int get() = mask.count { it }

        /** Condensed length over flat length. Below one means the row had structure to find. */
        val compression: Double get() = condensation.compression

        override fun toString(): String =
            "$label: $onsets of $steps steps, ${condensation.describe()}"
    }

    // ── the three derivations ──────────────────────────────────────────────────────────────

    /**
     * Row [generation] of rule 90 from a single seed, read along the sublattice the seed can reach.
     *
     * Rule 90 is `left XOR right`, so from one live cell only every other column is ever touched,
     * and the reachable cells of row *g* are `C(g, k) mod 2` for `k` in `0..g`. This runs the real
     * stepper over a grid wide enough that the light cone has not met the border, pulls that row
     * out, and checks it against [binomialParity] cell by cell before returning.
     */
    fun gasketBar(generation: Int = 20, on: String = "x"): CaBar {
        require(generation >= 1) { "generation must be >= 1, got $generation" }
        // Odd width, and wide enough that 2*generations < width, which is what
        // afield.stdlib.sierpinskiReference requires of its own reference for the same reason.
        val width = 2 * (generation + 1) + 1
        val space = elementaryCa(90, width = width, generations = generation + 1, wrap = false)
        val centre = width / 2
        val mask = (0..generation).map { k ->
            val x = centre - generation + 2 * k
            val cell = space.at(x, generation)
            check(cell == binomialParity(generation, k)) {
                "rule 90 row $generation column $k came out $cell, and C($generation, $k) mod 2 is " +
                    binomialParity(generation, k)
            }
            cell == 1
        }
        return CaBar("rule 90, generation $generation", mask, on)
    }

    /**
     * The centre column of rule 30's space-time diagram, [generations] long, as a step mask.
     *
     * The classic example of an elementary rule with no visible structure. What is checked is the
     * negative: no long runs, and a population near half, both of which a structured rule fails.
     */
    fun rule30Bar(generations: Int = 32, on: String = "x"): CaBar {
        require(generations >= 4) { "generations must be >= 4, got $generations" }
        // Wide enough that the border never influences the centre column over this many steps.
        val width = 2 * generations + 3
        val column = elementaryCa(30, width = width, generations = generations, wrap = false).centerColumn()
        return CaBar("rule 30, centre column over $generations generations", column.map { it == 1 }, on)
    }

    /**
     * The population of each of rule 90's first [generations] rows, as pitch.
     *
     * Row *n* of Pascal's triangle mod 2 has exactly `2^popcount(n)` odd entries, so the population
     * sequence is the binary digit sum of the row index, which climbs and falls with the carries.
     * This counts the real CA's rows, checks each count against that formula, and maps the digit sum
     * to a degree of [scale]. So the tune is the binary expansion of 0, 1, 2, … read out loud, and
     * every note is checkable with `Integer.bitCount`.
     *
     * @param root MIDI note the zero degree sits on.
     * @param scale semitone offsets per digit-sum; the default is a minor pentatonic, extended so a
     *   digit sum of five still has a note.
     */
    fun gasketScale(
        generations: Int = 32,
        root: Int = 45,
        scale: List<Int> = listOf(0, 3, 5, 7, 10, 12),
    ): List<Int> = gasketPopulations(generations).mapIndexed { n, _ ->
        root + scale[Integer.bitCount(n).coerceAtMost(scale.lastIndex)]
    }

    /**
     * How many cells are alive in each of rule 90's first [generations] rows, counted off the real
     * space-time diagram.
     *
     * Checked against `2^popcount(n)` row by row on the way out, so a caller plotting this against
     * that formula is plotting two things rather than one thing twice.
     */
    fun gasketPopulations(generations: Int = 32): List<Int> {
        require(generations >= 2) { "generations must be >= 2, got $generations" }
        val width = 2 * generations + 3
        val space = elementaryCa(90, width = width, generations = generations, wrap = false)
        return (0 until generations).map { n ->
            var ones = 0
            for (x in 0 until width) if (space.at(x, n) == 1) ones++
            check(ones == 1 shl Integer.bitCount(n)) {
                "rule 90 row $n has $ones live cells; 2^popcount($n) is ${1 shl Integer.bitCount(n)}"
            }
            ones
        }
    }

    // ── scene model ────────────────────────────────────────────────────────────────────────

    /** Shares [PatSoundDocs.Scene]'s shape so the two galleries' harnesses stay comparable. */
    class Scene(
        val name: String,
        val title: String,
        /** One line describing what you hear. */
        val blurb: String,
        val cycles: Int,
        val cps: Double,
        val render: (Int, Double) -> PatSoundDocs.Stereo,
        val verify: Scene.(PatSoundDocs.Stereo) -> Unit = {},
    ) {
        val seconds: Double get() = cycles / cps
    }

    val SCENES: List<Scene> = listOf(

        // 1 ── a row with structure. The condenser finds it, and you hear the figure repeat.
        Scene(
            name = "gasket-bar",
            title = "rule 90's twentieth row, spelled as mini-notation and played",
            blurb = "Four onsets in a twenty-one step bar, at the steps where C(20, k) is odd. " +
                "The bar is symmetric because Pascal's triangle is, and the two pairs sit four " +
                "steps apart at each end because 20 is 10100 in binary.",
            cycles = 4,
            cps = 1.0,
            render = { cycles, cps ->
                patRenderStereo(
                    gasketBar(20).pattern,
                    cycles = cycles, cps = cps,
                    // A short decay on purpose: the bar's last onset is at step 20 of 21 and the
                    // next cycle's first is at step 0, so at 1 cps they are 48 ms apart. A tail
                    // longer than that runs the two together, which a listener hears as a flam and
                    // an onset detector reads as one strike.
                    voice = patVoice(wave = PatWave.SQUARE, baseHz = 784.0, decay = 0.008, sustain = 0.04),
                )
            },
            verify = { audio ->
                val bar = gasketBar(20)
                check(bar.steps == 21) { "expected a 21-step bar, got ${bar.steps}" }
                check(bar.onsets == 4) { "expected 4 onsets, got ${bar.onsets}" }
                // Symmetry, from Pascal's: C(n, k) = C(n, n - k).
                check(bar.mask == bar.mask.reversed()) { "the bar is not symmetric: ${bar.mask}" }
                val onsets = PatSoundDocs.onsetPeaks(audio.mono, threshold = 0.04)
                check(onsets.size == 4 * cycles) {
                    "expected ${4 * cycles} strikes, found ${onsets.size}"
                }
                println("    ${bar.condensation.describe()}")
            },
        ),

        // 2 ── a row without structure, played the same way, so the difference is the sound of it.
        Scene(
            name = "rule30-hats",
            title = "rule 30's centre column as thirty-two hats, and a notation that barely shortens",
            blurb = "The same route as the gasket bar, on the elementary rule with no visible " +
                "structure. The condenser still shortens it, because a flat spelling has overhead " +
                "any grouping saves, but it shortens it about half as far, and the rhythm never " +
                "settles into a figure the way the gasket bar does.",
            cycles = 3,
            cps = 0.5,
            render = { cycles, cps ->
                patRenderStereo(
                    rule30Bar(32).pattern,
                    cycles = cycles, cps = cps,
                    // `x` rather than `hh` as the atom, in both bars, because compression is a
                    // ratio of character counts and a two-character atom moves it. The voice
                    // decides what it sounds like; the atom only has to be the same one.
                    voice = patVoice(wave = PatWave.NOISE, baseHz = 5200.0, decay = 0.008, sustain = 0.05),
                )
            },
            verify = { audio ->
                val bar = rule30Bar(32)
                check(bar.steps == 32) { "expected 32 steps, got ${bar.steps}" }
                // The negative claims: a population near half, and no long run either way.
                check(bar.onsets in 10..22) { "rule 30's column is lopsided: ${bar.onsets} of 32" }
                val runs = longestRunLength(IntArray(32) { if (bar.mask[it]) 1 else 0 })
                check(runs <= 6) { "rule 30's column has a run of $runs, which is not noise-like" }
                // The measurement this scene exists for, as a comparison rather than an absolute.
                // Both rows condense: a flat spelling carries overhead that any grouping saves, so
                // an absolute floor here would be a claim about that overhead rather than about the
                // rule. What separates the two rules is the ratio between their compressions.
                val gasket = gasketBar(20).compression
                check(bar.compression > gasket * 1.4) {
                    "rule 30 condensed to ${bar.compression} against rule 90's $gasket, " +
                        "which is not the gap this scene is about"
                }
                val onsets = PatSoundDocs.onsetPeaks(audio.mono, threshold = 0.05)
                check(onsets.size >= bar.onsets * cycles - 2) {
                    "expected about ${bar.onsets * cycles} hats, found ${onsets.size}"
                }
                println(
                    "    ${bar.onsets} of 32 steps, longest run $runs, " +
                        "${"%.2f".format(bar.compression)} against the gasket bar's ${"%.2f".format(gasket)}",
                )
            },
        ),

        // 3 ── the same rule read as pitch rather than as rhythm.
        Scene(
            name = "gasket-scale",
            title = "each of rule 90's first thirty-two row populations, as a pentatonic degree",
            blurb = "Row n has 2^popcount(n) live cells, so the melody is the binary digit sum of " +
                "0, 1, 2, … played on a minor pentatonic. It falls back to the root at every power " +
                "of two and climbs through the carries in between.",
            cycles = 2,
            cps = 0.25,
            render = { cycles, cps ->
                val notes = gasketScale()
                patRenderStereo(
                    pat(notes.joinToString(" ")),
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.3),
                )
            },
            verify = { audio ->
                val notes = gasketScale()
                check(notes.size == 32) { "expected 32 notes, got ${notes.size}" }
                val scale = listOf(0, 3, 5, 7, 10, 12)
                // Note by note against the formula, with no CA in the loop at all.
                notes.forEachIndexed { n, note ->
                    check(note == 45 + scale[Integer.bitCount(n)]) {
                        "note $n is $note; popcount($n) is ${Integer.bitCount(n)}"
                    }
                }
                // Every power of two returns to the root, which is what a listener hears.
                listOf(1, 2, 4, 8, 16).forEach { check(notes[it] == 45 + scale[1]) }
                check(notes[0] == 45) { "row 0 has one live cell, so it is the root" }
                check(notes[31] == 45 + scale[5]) { "row 31 is 11111, the top of the range" }
                val onsets = PatSoundDocs.onsetPeaks(audio.mono, threshold = 0.05)
                check(onsets.size >= 50) { "expected most of 64 notes to be audible, found ${onsets.size}" }
                println("    ${notes.distinct().sorted()} are the MIDI notes it uses")
            },
        ),
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    /** Offline-render scene [name] and write a stereo 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown CaSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val audio = scene.render(scene.cycles, scene.cps)
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("CA_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "ca-sound-docs").path,
        )
        outDir.mkdirs()

        SCENES.forEach { scene ->
            val audio = scene.render(scene.cycles, scene.cps)
            check(audio.frames > 0) { "${scene.name}: rendered no samples" }
            check(audio.left.size == audio.right.size) { "${scene.name}: channel length mismatch" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "${scene.name}: non-finite samples"
            }
            val expectedFrames = (scene.seconds * SR).toInt()
            check(abs(audio.frames - expectedFrames) <= SR / 10) {
                "${scene.name}: expected ~$expectedFrames frames, got ${audio.frames}"
            }
            val monoPeak = PatSoundDocs.peak(audio.mono)
            val monoRms = PatSoundDocs.rms(audio.mono)
            check(monoPeak > 0.02) { "${scene.name}: effectively silent (peak=$monoPeak)" }
            check(monoPeak <= 4.0) { "${scene.name}: runaway level (peak=$monoPeak)" }
            check(monoRms > 0.002) { "${scene.name}: RMS too low to be music (rms=$monoRms)" }

            println(
                "scene ${scene.name}: ${"%.2f".format(audio.frames.toDouble() / SR)}s, " +
                    "peak=${"%.3f".format(monoPeak)}, rms=${"%.4f".format(monoRms)}",
            )
            scene.verify(scene, audio)

            val file = File(outDir, "ca-${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, SR, normalizePeak = 0.9f)

            val bytes = file.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${scene.name}: missing RIFF" }
            check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${scene.name}: missing WAVE" }
            val channels = (bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8)
            check(channels == 2) { "${scene.name}: WAV declares $channels channels, not 2" }
            val declaredSr = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
            check(declaredSr == SR) { "${scene.name}: WAV declares $declaredSr Hz" }
            check(bytes.size == 44 + audio.frames * 4) {
                "${scene.name}: WAV data size mismatch: ${bytes.size} vs ${44 + audio.frames * 4}"
            }
            println("  wrote ${file.absolutePath} (${bytes.size} bytes)")
        }

        // The measurement the page publishes: which elementary rules have a spelling shorter than
        // one slot per step, over the same 32-step centre column for each.
        println()
        println("centre-column condensation, 32 generations:")
        CONDENSATION_SURVEY.forEach { rule ->
            val bar = centreColumnBar(rule)
            println(
                "  rule ${rule.toString().padStart(3)}: ${bar.onsets.toString().padStart(2)} of 32 " +
                    "steps, ${"%.2f".format(bar.compression)} — ${bar.condensation.notation}",
            )
        }

        println()
        println("ca sound docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }

    /**
     * The rules the survey covers. 90 and 150 are the additive ones, 30 and 45 the two usually
     * called chaotic, 110 the universal one, and 250 is here because its centre column is periodic
     * with period two from a single seed, which is the shortest interesting answer there is.
     */
    val CONDENSATION_SURVEY: List<Int> = listOf(30, 45, 90, 110, 150, 250)

    /** The centre column of [rule] over [generations] steps, as a bar. Used by the survey and the docs page. */
    fun centreColumnBar(rule: Int, generations: Int = 32, on: String = "x"): CaBar {
        val width = 2 * generations + 3
        val column = elementaryCa(rule, width = width, generations = generations, wrap = false).centerColumn()
        return CaBar("rule $rule, centre column over $generations generations", column.map { it == 1 }, on)
    }
}
