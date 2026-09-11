package afield.four.synth

import afield.docs.CitationGuard
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exactness and honesty tests for `PatCondense.kt`.
 *
 * Two things have to be true at once and they pull against each other. The search has to **find**
 * structure — `x ~ ~ x ~ x ~ ~` is `x(3,8)` and a condenser that cannot say so is not worth having —
 * and it has to **not invent** it, because a search scored on description length will happily name a
 * figure that is one step away from the rhythm in front of it if nobody checks.
 *
 * The finding is checked against the corpus this repo already carries. The not-inventing is checked
 * two ways, because the obvious way does not work: see
 * [noEuclideanOrKolakoskiFigureIsNamedThatIsNotActuallyThere] for why a boolean step mask *always*
 * has structure to find, and [aStepSequenceWithNoStructureComesBackAsItself] for the counterfactual
 * that does hold.
 */
class PatCondenseTest {

    private companion object {
        /**
         * The two files whose `pat("…")` literals are the corpus.
         *
         * Scanned rather than re-listed. A corpus copied into this file would drift from the one the
         * contract tests actually exercise, and the whole point of reusing it is that it is the set
         * of mini-notation the language is already known to handle. Both files are on this test's
         * own compile classpath, so editing either recompiles and re-runs this class rather than
         * serving a cached verdict — which is the staleness hole `GateInputDeclarationTest` describes
         * for `citationGates`, and it does not apply here for that reason.
         */
        val CORPUS_SOURCES = listOf(
            "src/jvmTest/kotlin/afield/four/synth/PatContractTest.kt",
            "src/jvmMain/kotlin/afield/four/synth/SonicPiPatternsSmokeTest.kt"
        )

        /** `pat("…")` with no escapes — every mini-notation literal in the corpus is plain. */
        val PAT_LITERAL = Regex("""pat\("([^"\\]*)"\)""")

        /**
         * Floor on the corpus size. Measured 2026-09-10: **81** distinct literals across the two
         * files (123 occurrences in `PatContractTest`, 116 in `SonicPiPatternsSmokeTest`).
         *
         * Floored rather than pinned because adding a corpus case to either file is a normal thing to
         * do. Without it a regex that stopped matching would leave every assertion below quantified
         * over the empty set and passing.
         */
        const val MIN_CORPUS = 70

        /**
         * Floor on how many of the corpus condense exactly. Measured 2026-09-10: **78 of 81**.
         *
         * The three that do not are `[a|b|c] d`, `a*8?0.4` and `[bd*4|hh*2|cp]`, all randomised per
         * cycle, and all three are refused with a reason rather than fitted to their first bars.
         */
        const val MIN_EXACT = 76

        /**
         * Ceiling on the median `len(condensed)/len(flat)` **over the entries the search shortens**.
         *
         * Not over the whole corpus, where the median is exactly 1.000 and always will be: more than
         * half of these literals are two or three atoms and have nothing in them to find. Averaging
         * that in would make the headline number a statement about the corpus's length distribution
         * rather than about the search.
         *
         * Measured 2026-09-10: **0.429** over the 35 entries that shorten; best 0.127 (`a(k16) b`).
         */
        const val MAX_MEDIAN_RATIO = 0.50

        /** Floor on how many corpus entries the search shortens at all. Measured 2026-09-10: **35 of 78**. */
        const val MIN_SHORTENED = 32

        /** Seed for every generated population below, so a failure is reproducible from this line. */
        const val SEED = 20260910L
    }

    private fun corpus(): List<String> {
        val root = CitationGuard.repoRoot()
        val found = linkedSetOf<String>()
        for (relative in CORPUS_SOURCES) {
            val file = File(root, relative)
            assertTrue(file.isFile, "corpus source missing: $relative")
            for (match in PAT_LITERAL.findAll(file.readText())) found += match.groupValues[1]
        }
        return found.filter { it.isNotBlank() }.toList()
    }

    /** Independent of [PatCondensation.exact]: re-render both sides here rather than trusting the flag. */
    private fun rendersTheSame(left: String, right: String, cycles: Int): Boolean {
        val a = pat(left)
        val b = pat(right)
        return (0 until cycles).all { patCycleSignature(a, it.toLong()) == patCycleSignature(b, it.toLong()) }
    }

    private fun maskString(mask: List<Boolean>): String = mask.joinToString("") { if (it) "x" else "." }

    // ── 1. the round trip over the corpus that already exists ─────────────────────────────────

    @Test
    fun `every mini-notation the corpus already carries condenses to something that re-renders identically`() {
        val corpus = corpus()
        assertTrue(
            corpus.size >= MIN_CORPUS,
            "the corpus scan found only ${corpus.size} literals in ${CORPUS_SOURCES.size} files; " +
                "expected at least $MIN_CORPUS. Either the regex stopped matching or the corpus shrank"
        )

        val exact = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, String>>()
        for (source in corpus) {
            val result = patCondense(source)
            if (!result.exact) {
                assertTrue(
                    result.refusal != null && result.refusal.length > 40,
                    "\"$source\" was not condensed and gave no reason for it"
                )
                refused += source to result.refusal
                continue
            }
            assertTrue(
                rendersTheSame(source, result.notation, result.cycles),
                "\"$source\" condensed to \"${result.notation}\", which claims to be exact and is not"
            )
            assertTrue(
                result.notation.length <= result.flat.length,
                "\"$source\" condensed to \"${result.notation}\" (${result.notation.length} chars), " +
                    "which is longer than its own flat spelling \"${result.flat}\" " +
                    "(${result.flat.length}). The flat spelling is the floor and the search lost to it"
            )
            exact += source
        }

        println("  patCondense round trip: ${exact.size} of ${corpus.size} corpus literals exact")
        for ((source, why) in refused) println("    refused \"$source\" — ${why.substringBefore('—').trim()}")
        assertTrue(
            exact.size >= MIN_EXACT,
            "only ${exact.size} of ${corpus.size} corpus literals condensed exactly, expected at least " +
                "$MIN_EXACT. Refused: ${refused.map { it.first }}"
        )
    }

    // ── 2. named discoveries, asserted rather than printed ────────────────────────────────────

    @Test
    fun `the mask of a Euclidean rhythm condenses back to a Euclidean form`() {
        for ((pulses, steps) in listOf(3 to 8, 5 to 8, 5 to 16, 7 to 16, 2 to 5, 9 to 16)) {
            val condensed = patCondense(bjorklund(pulses, steps))
            assertTrue(condensed.exact, "euclid($pulses,$steps) did not condense exactly")
            assertEquals(
                "x($pulses,$steps)",
                condensed.notation,
                "the mask ${maskString(bjorklund(pulses, steps))} should read as its own Euclidean form"
            )
            assertTrue("euclid" in condensed.structure, "no euclid in ${condensed.structure}")
        }
    }

    @Test
    fun `a rotated Euclidean mask condenses to the same figure with the rotation named`() {
        // The offset sense is the parser's: `x(3,8,r)` compiles to `euclidFill(3, 8, rotation = -r)`.
        // Asserting the *round trip* rather than the sign keeps this test honest if that ever changes.
        val turned = List(8) { index -> bjorklund(3, 8)[((index - 3) % 8 + 8) % 8] }
        val condensed = patCondense(turned)
        assertTrue(condensed.exact, "the rotated mask ${maskString(turned)} did not condense")
        assertTrue(
            Regex("""^x\(3,8,[1-7]\)$""").matches(condensed.notation),
            "${maskString(turned)} should read as a turned x(3,8); got \"${condensed.notation}\""
        )
        val played = pat(condensed.notation).let { rhythm ->
            List(8) { index -> rhythm.events(PatArc(PatQ.of(index, 8), PatQ.of(index + 1, 8))).isNotEmpty() }
        }
        assertEquals(turned, played, "\"${condensed.notation}\" does not play ${maskString(turned)}")
    }

    @Test
    fun `the mask of a Kolakoski rhythm condenses back to a Kolakoski form`() {
        for (steps in listOf(8, 16, 32, 64)) {
            val runs = patCondense(kolakoskiMask(steps, KolakoskiReading.RUNS))
            assertTrue(runs.exact, "kolakoski($steps) did not condense exactly")
            assertEquals("x(k$steps)", runs.notation, "the runs reading at $steps steps")

            val gaps = patCondense(kolakoskiMask(steps, KolakoskiReading.GAPS))
            assertTrue(gaps.exact, "kolakoski($steps, GAPS) did not condense exactly")
            assertEquals("x(g$steps)", gaps.notation, "the gaps reading at $steps steps")
        }
    }

    @Test
    fun `a live pattern built without notation still condenses to notation`() {
        // The point of the oracle: these targets were never strings, and the answer is a string.
        assertEquals("bd(3,8)", euclidFill(3, 8, on = pat("bd")).condense().notation)
        assertEquals("a(k16)", mask(kolakoski(16), pat("a")).condense().notation)
        assertEquals("a(g16)", mask(kolakoski(16, KolakoskiReading.GAPS), pat("a")).condense().notation)
        assertEquals("cp hh sd bd", pat("bd sd hh cp").rev().condense().notation)
        assertEquals("a*3 b*3", pat("a b").ply(3).condense().notation)
    }

    // ── 3. the counterfactual: nothing is invented ────────────────────────────────────────────

    /**
     * **A boolean step mask always has structure, so "condenses to itself" cannot be the property.**
     *
     * For a 16-step mask to be un-shortened by run-length replication it must have no two adjacent
     * equal cells; over two symbols that forces strict alternation, which is `[x ~]*8`. Measured over
     * 400 masks at seed [SEED]: **0** came back byte-identical to their flat spelling. That is not the
     * search inventing things — every one of them re-renders exactly — it is the fact that a sparse
     * mask really does contain runs, and naming a run is a true statement.
     *
     * So the falsifiable claim is the sharper one: when the search *names a figure*, the figure is
     * there. Every whole-span `x(p,s)` / `x(kN)` the search emits over a random population is
     * recomputed here from [bjorklund] and [kolakoskiMask] and compared to the mask it claims.
     */
    @Test
    fun `no Euclidean or Kolakoski figure is named that is not actually there`() {
        val random = Random(SEED)
        val whole = Regex("""^x\((\d+),(\d+)(?:,(\d+))?\)$""")
        val kolakoski = Regex("""^x\(([kg])(\d+)(?:,(\d+))?\)$""")
        var named = 0
        var identicalToFlat = 0
        val ratios = mutableListOf<Double>()

        val population = 400
        for (trial in 0 until population) {
            val steps = 16
            val mask = List(steps) { random.nextInt(3) == 0 }
            val condensed = patCondense(mask)
            assertTrue(condensed.exact, "the mask ${maskString(mask)} did not condense exactly")
            assertTrue(
                rendersTheSame(condensed.flat, condensed.notation, 1),
                "the mask ${maskString(mask)} condensed to \"${condensed.notation}\", which is not " +
                    "the same rhythm as \"${condensed.flat}\""
            )
            if (condensed.notation == condensed.flat) identicalToFlat++
            ratios += condensed.compression

            whole.matchEntire(condensed.notation)?.let { match ->
                named++
                val pulses = match.groupValues[1].toInt()
                val figureSteps = match.groupValues[2].toInt()
                val offset = match.groupValues[3].ifEmpty { "0" }.toInt()
                val expected = List(figureSteps) { index ->
                    bjorklund(pulses, figureSteps)[((index + offset) % figureSteps + figureSteps) % figureSteps]
                }
                assertEquals(
                    mask, expected,
                    "the search named ${condensed.notation} for ${maskString(mask)}, which is not that figure"
                )
            }
            kolakoski.matchEntire(condensed.notation)?.let { match ->
                named++
                val reading = if (match.groupValues[1] == "k") KolakoskiReading.RUNS else KolakoskiReading.GAPS
                val figureSteps = match.groupValues[2].toInt()
                val offset = match.groupValues[3].ifEmpty { "0" }.toInt()
                assertEquals(
                    mask, kolakoskiMask(figureSteps, reading, rotation = -offset),
                    "the search named ${condensed.notation} for ${maskString(mask)}, which is not that figure"
                )
            }
        }

        val median = ratios.sorted()[ratios.size / 2]
        println(
            "  $population random 16-step masks (seed $SEED, density 1/3): $named named a whole-span " +
                "figure and every one of them checked out; $identicalToFlat came back as their own flat " +
                "spelling; median compression ${"%.3f".format(median)}"
        )
        assertEquals(
            0, identicalToFlat,
            "a 16-step boolean mask with no run to collapse would have to alternate strictly, and that " +
                "is periodic — so this being non-zero means the search stopped finding runs"
        )
        // The separation is the claim. A structured figure reaches 0.127 of its flat spelling and the
        // corpus's shortenable half reaches 0.429; random noise reached 0.613 on 2026-09-10. If noise
        // ever compressed as well as structure, the search would be finding figures in nothing.
        assertTrue(
            median > 0.55,
            "random masks compressed to a median of ${"%.3f".format(median)}, as well as the structured " +
                "corpus does, which means something is being named that is not there"
        )
        assertTrue(
            named in 1..(population / 10),
            "$named of $population random masks read as a single whole-span figure; measured 2026-09-10 " +
                "it was 6. Zero means the recognisers stopped firing and this test proves nothing"
        )
    }

    /**
     * **The counterfactual that does hold**, over a larger alphabet where "no structure" is reachable.
     *
     * A step sequence over five atoms with no two adjacent alike and no period cannot be shortened by
     * anything in the grammar: `!` needs a repeat, `*` needs a period, and the two rhythm figures need
     * a single voice. So it must come back exactly as it went in, character for character.
     */
    @Test
    fun `a step sequence with no structure comes back as itself, inventing none`() {
        val random = Random(SEED)
        val alphabet = listOf("a", "b", "c", "d", "e")
        var checked = 0
        var attempts = 0
        while (checked < 200 && attempts < 4000) {
            attempts++
            val length = 8
            val steps = List(length) { alphabet[random.nextInt(alphabet.size)] }
            // Nothing repeatable at *any* split the grammar can take: for each divisor of the length,
            // no two adjacent equal-width blocks may be equal. Filtering only on whole-sequence
            // periodicity is not enough — `c d c d c d a c` has no period and is still `[c d]!3 [a c]`.
            val repeatable = listOf(1, 2, 4).any { width ->
                (width until length step width).any { at ->
                    (0 until width).all { offset -> steps[at + offset] == steps[at - width + offset] }
                }
            }
            if (repeatable) continue
            val source = steps.joinToString(" ")
            val condensed = patCondense(source)
            assertTrue(condensed.exact, "\"$source\" did not condense exactly")
            assertEquals(
                source, condensed.notation,
                "\"$source\" has no repeat, no period and more than one voice, so there is nothing to " +
                    "find in it — the search returned \"${condensed.notation}\" anyway"
            )
            assertEquals(1.0, condensed.compression, 1e-9)
            checked++
        }
        println("  $checked structureless step sequences (seed $SEED, 5 atoms, 8 steps) came back unchanged")
        assertTrue(checked >= 200, "only $checked structureless sequences were generated in $attempts draws")
    }

    // ── 4. compression, with its population ───────────────────────────────────────────────────

    @Test
    fun `the compression the search achieves is measured over the corpus it was given`() {
        val corpus = corpus()
        val againstFlat = mutableListOf<Pair<String, Double>>()
        val againstSource = mutableListOf<Pair<String, Double>>()
        for (source in corpus) {
            val condensed = patCondense(source)
            if (!condensed.exact) continue
            againstFlat += source to condensed.compression
            againstSource += source to condensed.notation.length.toDouble() / source.length.toDouble()
        }
        assertTrue(againstFlat.size >= MIN_EXACT, "only ${againstFlat.size} exact condensations to measure")

        val flatRatios = againstFlat.map { it.second }.sorted()
        val medianFlat = flatRatios[flatRatios.size / 2]
        val worstFlat = flatRatios.last()
        val worstFlatCase = againstFlat.maxByOrNull { it.second }!!
        val bestFlatCase = againstFlat.minByOrNull { it.second }!!
        val sourceRatios = againstSource.map { it.second }.sorted()
        val medianSource = sourceRatios[sourceRatios.size / 2]
        val worstSourceCase = againstSource.maxByOrNull { it.second }!!

        val shortened = againstFlat.filter { it.second < 1.0 }.sortedBy { it.second }
        val medianShortened = shortened[shortened.size / 2].second

        println(
            "  compression over ${againstFlat.size} exact corpus condensations:\n" +
                "    against the flat spelling — median ${"%.3f".format(medianFlat)}, " +
                "worst ${"%.3f".format(worstFlat)} (\"${worstFlatCase.first}\"), " +
                "best ${"%.3f".format(bestFlatCase.second)} (\"${bestFlatCase.first}\")\n" +
                "    against the source string — median ${"%.3f".format(medianSource)}, " +
                "worst ${"%.3f".format(sourceRatios.last())} (\"${worstSourceCase.first}\")\n" +
                "    ${shortened.size} of ${againstFlat.size} were shortened at all; " +
                "median over those ${"%.3f".format(medianShortened)}"
        )

        // The median over the whole corpus is 1.000 and that is the right answer, not a weak one:
        // more than half of these literals are two or three atoms long and already minimal. The two
        // numbers that can regress are how many the search *can* shorten, and by how much when it does.
        assertTrue(
            worstFlat <= 1.0,
            "the worst case is ${"%.3f".format(worstFlat)} of flat, which means the search lost to " +
                "the floor it is supposed to never lose to"
        )
        assertTrue(
            medianSource <= 1.0,
            "the median condensation is ${"%.3f".format(medianSource)} of the string a person wrote; " +
                "above 1 means the search is making the corpus longer"
        )
        assertTrue(
            shortened.size >= MIN_SHORTENED,
            "only ${shortened.size} of ${againstFlat.size} corpus entries were shortened at all, " +
                "expected at least $MIN_SHORTENED"
        )
        assertTrue(
            medianShortened <= MAX_MEDIAN_RATIO,
            "when the search finds something it now only reaches ${"%.3f".format(medianShortened)} of " +
                "the flat spelling, past the ceiling of $MAX_MEDIAN_RATIO"
        )
    }

    // ── 5. what it refuses, and that it says so ───────────────────────────────────────────────

    @Test
    fun `a randomised target is refused rather than fitted to its first bars`() {
        for (source in listOf("[a|b|c] d", "a*8?0.4", "[bd*4|hh*2|cp]")) {
            val condensed = patCondense(source)
            assertTrue(
                !condensed.exact,
                "\"$source\" changes from cycle to cycle and was condensed anyway, to " +
                    "\"${condensed.notation}\""
            )
            assertTrue(
                condensed.refusal!!.contains("no period"),
                "\"$source\" was refused for the wrong reason: ${condensed.refusal}"
            )
            assertEquals(source, condensed.notation, "a refusal should hand back what it was given")
        }
    }

    @Test
    fun `a target carrying annotations no atom can spell is refused rather than approximated`() {
        // `jux` writes a `pan` onto every event and `chop` writes `begin`/`end`. Mini-notation has a
        // token for a sample index and none for either of those, so the flat spelling is already
        // lossy and the search says so instead of returning a rhythm that sounds different.
        for ((label, target) in listOf(
            "jux" to pat("a b c").jux { it.rev() },
            "chop" to pat("a b").chop(3)
        )) {
            val condensed = target.condense()
            assertTrue(!condensed.exact, "$label was condensed to \"${condensed.notation}\" and should not be")
            assertTrue(condensed.refusal != null, "$label was refused with no reason")
        }
    }

    @Test
    fun `an event that runs past the bar line is refused, because a slot cannot hold it`() {
        val condensed = pat("a b c").slow(2).condense()
        assertTrue(!condensed.exact, "a pattern slower than one cycle was condensed to \"${condensed.notation}\"")
        assertTrue(
            condensed.refusal!!.contains("step grid") || condensed.refusal.contains("no period"),
            "the wrong refusal: ${condensed.refusal}"
        )
    }

    // ── 6. the bounds are real and reported ───────────────────────────────────────────────────

    @Test
    fun `the search reports the bounds it ran under`() {
        val condensed = patCondense(kolakoskiMask(64))
        assertTrue(condensed.exact)
        assertTrue(condensed.oracleCalls in 1..PAT_CONDENSE_ORACLE_CALLS, "oracle calls ${condensed.oracleCalls}")
        assertTrue(condensed.spansExpanded > 0, "the DP reported expanding no spans at all")
        assertTrue(
            condensed.limits.contains("beam $PAT_CONDENSE_BEAM"),
            "the reported limits do not name the beam: ${condensed.limits}"
        )
        println("  kolakoskiMask(64) → \"${condensed.notation}\"; ${condensed.limits}")
    }

    @Test
    fun `a grid finer than the cap is refused rather than searched`() {
        // 257 steps is one past PAT_CONDENSE_MAX_SLOTS; 256 is inside it.
        val inside = patCondense(List(PAT_CONDENSE_MAX_SLOTS) { it % 7 == 0 })
        assertTrue(inside.exact, "a $PAT_CONDENSE_MAX_SLOTS-slot mask should be inside the cap")
        val outside = patCondense(List(PAT_CONDENSE_MAX_SLOTS + 1) { it % 7 == 0 })
        assertTrue(
            !outside.exact,
            "a ${PAT_CONDENSE_MAX_SLOTS + 1}-slot mask is past the cap and was searched anyway"
        )
        assertTrue(outside.refusal!!.contains("step grid"), "the wrong refusal: ${outside.refusal}")
    }

    // ── 7. the transcription loop, closed structurally ────────────────────────────────────────

    @Test
    fun `the flat notation a transcription emits condenses to the structure that made it`() {
        // patNotationOf writes one slot per step; that is as far as PatTranscription.kt can honestly
        // go from audio. Feeding its output straight back in is the payoff: the loop the transcription
        // header draws now ends in a figure rather than a slot list.
        val grid = PatStepGrid(stepSeconds = 0.125, worstResidual = 0.0, steps = 8)
        val notes = listOf(0, 3, 5).map { step ->
            PatDetectedNote(
                startSample = (step * 0.125 * 44100).toInt(),
                endSample = ((step + 1) * 0.125 * 44100).toInt(),
                sampleRate = 44100,
                midi = 60,
                centsOffset = 0.0,
                confidence = 1.0,
                voicedFrames = 8,
                harmonicResidual = 0.0
            )
        }
        val flat = patNotationOf(notes, grid)
        assertEquals("60 ~ ~ 60 ~ 60 ~ ~", flat, "the flat printer changed shape under us")
        val condensed = patCondense(flat)
        assertTrue(condensed.exact, "the flat transcription did not condense")
        assertEquals("60(3,8)", condensed.notation)
        assertTrue(rendersTheSame(flat, condensed.notation, 1))
    }

    /**
     * The same loop with the audio actually in it: render a bar to samples, listen to the samples,
     * condense what was heard. Nothing between the render and the transcription sees the pattern.
     *
     * This is the sentence `PatTranscription.kt`'s header is reaching for. Its arrow "→ revise the
     * generating code" landed on a slot list; it now lands on a figure.
     */
    @Test
    fun `a bar rendered to audio and heard back condenses to the figure that made it`() {
        for ((bar, figure) in listOf(
            "60 ~ ~ 60 ~ 60 ~ ~" to "60(3,8)",
            "60 ~ ~ 60 60 ~ 60 ~" to "60(k8)"
        )) {
            val audio = patRenderStereo(
                pat(bar),
                cycles = 1,
                cps = 0.5,
                voice = patVoice(decay = 0.06, sustain = 0.6)
            )
            val heard = patTranscribe(audio.mono, PatSoundDocs.SR, minHz = 180.0, maxHz = 420.0)
            assertEquals(
                bar, heard.miniNotation,
                "the transcriber did not recover the bar, so the condensation below would be about " +
                    "something else: grid ${heard.grid?.describe()}"
            )
            val condensed = patCondense(heard)
            assertTrue(condensed.exact, "\"$bar\" heard back did not condense: ${condensed.refusal}")
            assertEquals(figure, condensed.notation, "\"$bar\" through audio")
        }
    }

    // ── 8. the limits, pinned so the list in the docs stays true ──────────────────────────────

    /**
     * **What the search cannot find.** Each case is a *live pattern*, so the source string is not in
     * the candidate set and the search has to earn the answer on its own.
     *
     * These are pinned rather than left in prose because a limits list nobody runs goes stale, and
     * every one of them is a plausible next operator: whichever gets built should turn one of these
     * assertions red. That is how `[bd cp](3,8)` came off the list — it is still below, as the case
     * that used to fail, now asserting the answer instead of the absence of one.
     */
    @Test
    fun `the three things the search cannot find are the three things it says it cannot find`() {
        // 1. Alternation is proposed only at the top level, one whole cycle per alternative. A `<>`
        //    nested inside a sequence is out of reach, so `bd <sd cp>` costs 17 instead of 10.
        val alternating = patCondense(pat("bd <sd cp>"), cycles = 2)
        assertTrue(alternating.exact)
        assertEquals("<[bd sd] [bd cp]>", alternating.notation)
        assertTrue(
            alternating.notation.length > "bd <sd cp>".length,
            "the search now beats the hand-written alternation, so limit 1 is gone"
        )

        // 2. Polymeter is never proposed at all. `{a b c}%8` phases over three cycles and comes back
        //    as those three cycles written out, which is 54 characters against 9.
        val polymeter = patCondense(pat("{a b c}%8"), cycles = 3)
        assertTrue(polymeter.exact)
        assertTrue(
            "polymeter" !in polymeter.structure,
            "the search proposed a polymeter, so limit 2 is gone: ${polymeter.notation}"
        )
        assertTrue(polymeter.notation.length > 40, "unexpectedly short: ${polymeter.notation}")

        // 3. (was a limit until the block sweep landed) A rhythm figure is recognised over a repeated
        //    *term*, not only a repeated atom, because `mask` squeezes a whole cycle of its argument
        //    into each true step. Kept here as the case that used to fail.
        val grouped = patCondense(pat("[bd cp](3,8)"), cycles = 1)
        assertTrue(grouped.exact)
        assertEquals("[bd cp](3,8)", grouped.notation, "a figure over a two-atom group")

        // 3. The Kolakoski alphabet is a parameter of `kolakoskiMask` and not of the notation: there
        //    is no spelling for {2,3}, only `k` and `g` over {1,2}. So that figure decomposes.
        val alphabet = patCondense(kolakoskiMask(16, a = 2, b = 3))
        assertTrue(alphabet.exact)
        assertTrue(
            alphabet.notation.length > "x(k16)".length,
            "the notation grew a spelling for the alphabet, so limit 3 is gone: ${alphabet.notation}"
        )

        println(
            "  known limits, as they currently read:\n" +
                "    bd <sd cp>      → ${alternating.notation}\n" +
                "    {a b c}%8       → ${polymeter.notation}\n" +
                "    [bd cp](3,8)    → ${grouped.notation}\n" +
                "    kolakoski{2,3}  → ${alphabet.notation}"
        )
    }
}
