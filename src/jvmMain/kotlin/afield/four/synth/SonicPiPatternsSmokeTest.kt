package afield.four.synth

import kotlin.math.abs
import kotlin.math.roundToInt

/*
FIELD_TEST
id: synth.sonic-pi.patterns
runner: jvm-main
entrypoint: afield.four.synth.SonicPiPatternsSmokeTest
tags: [smoke, synth, audio, sonic-pi, pattern]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "SonicPi pattern smoke tests passed"
*/
/**
 * Everything about lowering a Field pattern to Sonic Pi that can be checked with no Sonic Pi
 * running — which, it turns out, is nearly everything worth checking.
 *
 * The centre of this file is a **differential oracle**. For a corpus of patterns, the generated
 * Sonic Pi *text* is parsed back by [SonicPiProgramReader] and its event lattice is compared against
 * the pattern's own exact rational schedule, position by position, in [PatQ] — no doubles anywhere
 * in the comparison. That is deliberate: the tempting version of this test compares
 * [SonicPiProgram.events] against the pattern, which only proves the emitter can enumerate. Every
 * failure that can actually reach a listener lives in the gap between that list and the text: a
 * third rendered as `0.333333`, a `sleep` delta that does not accumulate back to the position it
 * came from, a missing pad at the end of the loop so the second bar arrives early, an opt attached
 * to the wrong line. So the oracle reads the text.
 *
 * The other half is the vocabulary. Sonic Pi answers a `sample :bd_hauz` by doing nothing at all —
 * no error, no sound, and a `live_loop` that plays three of its four voices is very hard to debug
 * from the sound. So every drum alias, every emitted instrument name and every emitted opt is
 * checked against `sonicpi-vocabulary.json`, which was extracted from the installed app.
 *
 * Needs no Sonic Pi install: the vocabulary is a committed resource. The parts that need a real
 * engine are in `afield.examples.sonicpi.SonicPiPatternProbe` (`sonicpi.pattern.timing`).
 */
object SonicPiPatternsSmokeTest {

    /**
     * Worst |rendered − scheduled| allowed for Field's own offline renderer, in seconds.
     *
     * Observed on this machine: 0.0227 ms, which is one sample at 44.1 kHz. 0.25 ms is ~11 samples
     * of slack, set there because the detector is the fragile part rather than the renderer.
     */
    private const val OFFLINE_TOLERANCE_S = 0.00025

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "SonicPi pattern smoke tests failed" }
    }

    fun runTests(): Boolean {
        val failures = mutableListOf<String>()

        println("vocabulary")
        checkVocabulary(failures)

        println("drum aliases resolve to real instruments")
        checkAliasTargets(failures)

        println("atom resolution order")
        checkResolution(failures)

        println("exact rational rendering")
        checkRationalRendering(failures)

        println("differential oracle: emitted text vs the pattern's own rational schedule")
        val coverage = checkDifferentialOracle(failures)

        println("parameter translation")
        checkOptTranslation(failures)

        println("the emitted source is complete")
        checkSourceIsRunnable(failures)

        println("a layer may name its own synth")
        checkPerLayerSynth(failures)

        println("with_fx chains")
        checkFxChains(failures)

        println("three-way: notation, Field's own renderer, and the emitted program")
        checkOfflineRendererOnsets(failures)

        println("unresolved atoms are reported, not dropped")
        checkUnresolvedReporting(failures)

        println("drive mode one-liners")
        checkOneLiners(failures)

        println("transform coverage")
        checkCoverage(coverage, failures)

        return if (failures.isEmpty()) {
            println("SonicPi pattern smoke tests passed")
            true
        } else {
            println("SonicPi pattern smoke tests FAILED:")
            failures.forEach { println("  - $it") }
            false
        }
    }

    // ── the vocabulary is real and complete ────────────────────────────────────────────────────

    private fun checkVocabulary(failures: MutableList<String>) {
        val v = SonicPiVocabulary
        println("  $v")
        // Floors rather than equalities: the committed vocabulary is regenerated from whatever
        // Sonic Pi is installed, so a newer release adding a synth must not fail this. A release
        // *removing* two thirds of the sample library is the thing worth catching.
        if (!v.version.startsWith("5.")) failures += "vocabulary is for Sonic Pi '${v.version}', expected a 5.x"
        if (v.synths.size < 60) failures += "only ${v.synths.size} synths in the vocabulary, expected >= 60"
        if (v.fx.size < 40) failures += "only ${v.fx.size} FX in the vocabulary, expected >= 40"
        if (v.samples.size < 200) failures += "only ${v.samples.size} samples in the vocabulary, expected >= 200"
        if (v.sampleGroups.size < 18) failures += "only ${v.sampleGroups.size} sample groups, expected >= 18"
        if (v.sampleOpts.size < 40) failures += "only ${v.sampleOpts.size} sample opts, expected >= 40"

        // The three opts the lowering converts into rather than passing through. If any of these
        // stops existing the conversion is silently pointless, which is worse than a crash.
        if ("amp" !in v.commonPlayOpts) failures += "'amp' is not an opt of play"
        if ("pan" !in v.commonPlayOpts) failures += "'pan' is not an opt of play"
        if ("lpf" !in v.sampleOpts) failures += "'lpf' is not an opt of sample"
        if ("rate" !in v.sampleOpts) failures += "'rate' is not an opt of sample"
        if ("cutoff" !in v.synthOpts("tb303")) failures += "'cutoff' is not an opt of synth :tb303"
        if ("cutoff" in v.synthOpts("beep")) failures += "'cutoff' unexpectedly IS an opt of :beep"
    }

    private fun checkAliasTargets(failures: MutableList<String>) {
        var samples = 0
        var notes = 0
        for ((alias, voice) in SONIC_PI_DRUM_ALIASES) {
            when (voice) {
                is SonicPiVoice.Sample -> {
                    samples++
                    if (!SonicPiVocabulary.isSample(voice.name)) {
                        failures += "alias '$alias' -> sample :${voice.name}, which Sonic Pi " +
                            "${SonicPiVocabulary.version} does not have"
                    }
                }
                is SonicPiVoice.Note -> {
                    notes++
                    val synth = voice.synth
                    if (synth != null && !SonicPiVocabulary.isSynth(synth)) {
                        failures += "alias '$alias' -> synth :$synth, which Sonic Pi does not have"
                    }
                    if (voice.note !in 0..127) failures += "alias '$alias' -> note ${voice.note}, out of MIDI range"
                }
                SonicPiVoice.Silent -> Unit
            }
        }
        println("  ${SONIC_PI_DRUM_ALIASES.size} aliases: $samples sample, $notes synth — all present")
        if (SONIC_PI_DRUM_ALIASES.size < 30) {
            failures += "only ${SONIC_PI_DRUM_ALIASES.size} drum aliases, expected >= 30"
        }
    }

    private fun checkResolution(failures: MutableList<String>) {
        val i = SonicPiInstruments()
        fun expect(atom: String, expected: SonicPiVoice?) {
            val got = i.resolve(atom)
            if (got != expected) failures += "resolve('$atom') = $got, expected $expected"
        }

        // A real sample name wins over everything.
        expect("bd_haus", SonicPiVoice.Sample("bd_haus"))
        expect("loop_amen", SonicPiVoice.Sample("loop_amen"))
        // A real synth name is next, played at the default note.
        expect("tb303", SonicPiVoice.Note(60, "tb303"))
        // `fm` is a synth, and would be read as a note if synths did not come first.
        expect("fm", SonicPiVoice.Note(60, "fm"))
        // Notes, on the ambient synth.
        expect("c4", SonicPiVoice.Note(patNoteNumber("c4")!!, null))
        expect("e2", SonicPiVoice.Note(patNoteNumber("e2")!!, null))
        expect("60", SonicPiVoice.Note(60, null))
        // Then the drum shorthand.
        expect("bd", SonicPiVoice.Sample("bd_haus"))
        expect("hh", SonicPiVoice.Sample("drum_cymbal_closed"))
        // The mini-notation's sample-index suffix selects nothing in Sonic Pi; the base name does.
        expect("bd:3", SonicPiVoice.Sample("bd_haus"))
        // And an atom that means nothing is null, not silence.
        expect("nonesuch_qq", null)

        val overridden = i.mapping("kick" to sonicPiSample("bd_tek"))
        if (overridden.resolve("kick") != SonicPiVoice.Sample("bd_tek")) {
            failures += "an override did not win: ${overridden.resolve("kick")}"
        }
        // The checked constructors are the whole point of having them.
        if (runCatching { sonicPiSample("bd_hauz") }.isSuccess) {
            failures += "sonicPiSample accepted a sample that does not exist"
        }
        if (runCatching { sonicPiNote(60, "tb304") }.isSuccess) {
            failures += "sonicPiNote accepted a synth that does not exist"
        }
        if (runCatching { SonicPiInstruments(defaultSynth = "nope") }.isSuccess) {
            failures += "SonicPiInstruments accepted a defaultSynth that does not exist"
        }
    }

    // ── a third stays a third ──────────────────────────────────────────────────────────────────

    private fun checkRationalRendering(failures: MutableList<String>) {
        val cases = listOf(
            PatQ.of(0) to "0",
            PatQ.of(1) to "1",
            PatQ.of(1, 2) to "0.5",
            PatQ.of(1, 4) to "0.25",
            PatQ.of(3, 8) to "0.375",
            PatQ.of(1, 16) to "0.0625",
            PatQ.of(1, 5) to "0.2",
            PatQ.of(1, 3) to "Rational(1, 3)",
            PatQ.of(2, 3) to "Rational(2, 3)",
            PatQ.of(1, 6) to "Rational(1, 6)",
            PatQ.of(3, 7) to "Rational(3, 7)",
            PatQ.of(5, 12) to "Rational(5, 12)"
        )
        for ((q, expected) in cases) {
            val rendered = patQToRuby(q)
            if (rendered != expected) failures += "patQToRuby($q) = '$rendered', expected '$expected'"
            // The round trip is the assertion that matters: whatever we wrote, Ruby and the oracle
            // must both read back the number we started with, exactly.
            val back = SonicPiProgramReader.parseRubyNumber(rendered)
            if (back == null || normalisePatQ(back) != normalisePatQ(q)) {
                failures += "round trip of $q via '$rendered' came back as $back"
            }
        }
        // A decimal that Ruby would accept but we never emit still has to read back exactly.
        val awkward = SonicPiProgramReader.parseRubyNumber("0.0625")
        if (awkward == null || normalisePatQ(awkward) != normalisePatQ(PatQ.of(1, 16))) {
            failures += "parseRubyNumber('0.0625') = $awkward, expected 1/16"
        }
        if (SonicPiProgramReader.parseRubyNumber("1r/3") != PatQ.of(1, 3)) {
            failures += "parseRubyNumber did not accept Ruby's own '1r/3' rational literal"
        }
        println("  12 rationals round-tripped exactly; triplets emit Rational(n, d), not a decimal")
    }

    // ── the differential oracle ────────────────────────────────────────────────────────────────

    private class Case(
        val label: String,
        val cycles: Int,
        /** Names from the reflected transform surface this case exercises. */
        val covers: Set<String>,
        /** Mini-notation features, which are not functions and so not in that surface. */
        val notation: Set<String>,
        val pattern: PatPattern<String>
    )

    private fun case(
        label: String,
        cycles: Int = 1,
        covers: Set<String> = emptySet(),
        notation: Set<String> = emptySet(),
        build: () -> PatPattern<String>
    ) = Case(label, cycles, covers, notation, build())

    /** Mini-notation features the corpus exercised, filled in by the oracle. */
    private val NOTATION_COVERED = mutableSetOf<String>()

    private fun q(n: Int, d: Int) = PatQ.of(n, d)

    private fun corpus(): List<Case> = listOf(
        // ── mini-notation ──────────────────────────────────────────────────────────────────────
        case("bd sd hh cp", notation = setOf("sequence")) { pat("bd sd hh cp") },
        case("bd [hh hh] sd [cp cp cp]", notation = setOf("group")) { pat("bd [hh hh] sd [cp cp cp]") },
        case("bd <sd cp>", 2, notation = setOf("alternation")) { pat("bd <sd cp>") },
        case("bd*4, [~ cp]*2", notation = setOf("stack", "rest", "density")) { pat("bd*4, [~ cp]*2") },
        case("bd ~ sd ~", notation = setOf("rest")) { pat("bd ~ sd ~") },
        case("bd!3 cp", notation = setOf("replicate")) { pat("bd!3 cp") },
        case("bd@3 cp", notation = setOf("elongate")) { pat("bd@3 cp") },
        case("bd(3,8)", notation = setOf("euclid")) { pat("bd(3,8)") },
        case("[bd cp](3,8) hh!2", notation = setOf("euclid", "group", "replicate")) { pat("[bd cp](3,8) hh!2") },
        case("bd(k16)", notation = setOf("kolakoski")) { pat("bd(k16)") },
        case("bd(g16,3)", notation = setOf("kolakoski", "kolakoskiGaps")) { pat("bd(g16,3)") },
        case("{bd hh cp}%4", 3, notation = setOf("polymeter")) { pat("{bd hh cp}%4") },
        case("{bd sd, cp hh rim}", 2, notation = setOf("polymeter", "stack")) { pat("{bd sd, cp hh rim}") },
        case("bd:2 hh", notation = setOf("sampleIndex")) { pat("bd:2 hh") },
        case("[bd*4|hh*2|cp]", 4, notation = setOf("choice")) { pat("[bd*4|hh*2|cp]") },
        case("bd bd bd", notation = setOf("sequence", "triplet")) { pat("bd bd bd") },
        case("seven", notation = setOf("sequence", "triplet")) { pat("bd sd hh cp bd sd hh") },

        // ── time and structure ─────────────────────────────────────────────────────────────────
        case(".fast(3)", covers = setOf("fast")) { pat("bd sd").fast(3) },
        case(".fastBy(3/2)", 2, setOf("fastBy")) { pat("bd sd hh").fastBy(q(3, 2)) },
        case(".slow(2)", 2, setOf("slow")) { pat("bd sd hh").slow(2) },
        case(".slowBy(2)", 2, setOf("slowBy")) { pat("bd sd hh").slowBy(q(2, 1)) },
        case(".stretch(2)", 2, setOf("stretch")) { pat("bd sd").stretch(q(2, 1)) },
        case(".timeScale(2)", 1, setOf("timeScale")) { pat("bd sd").timeScale(q(2, 1)) },
        case(".density(1.5)", 2, setOf("density")) { pat("bd sd").density(1.5) },
        case(".delay(1/8)", 1, setOf("delay")) { pat("bd sd").delay(q(1, 8)) },
        case(".subdivide(2)", 1, setOf("subdivide")) { pat("bd sd hh cp").subdivide(2) },
        case(".rev()", 1, setOf("rev")) { pat("bd sd hh cp").rev() },
        case(".every(3){rev}", 3, setOf("every", "rev")) { pat("bd sd hh").every(3) { it.rev() } },
        case(".iter(4)", 4, setOf("iter")) { pat("bd sd hh cp").iter(4) },
        case(".iterBack(4)", 4, setOf("iterBack")) { pat("bd sd hh cp").iterBack(4) },
        case(".palindrome()", 2, setOf("palindrome")) { pat("bd sd hh").palindrome() },
        case(".linger(1/4)", 1, setOf("linger")) { pat("bd sd hh cp").linger(1, 4) },
        case(".ply(2)", 1, setOf("ply", "stutter")) { pat("bd sd").ply(2) },
        case(".stutter(3)", 1, setOf("stutter")) { pat("bd cp").stutter(3) },
        case(".off(1/8)", 1, setOf("off")) { pat("bd sd").off(1, 8) { it } },
        case(".superimpose{fast 2}", 1, setOf("superimpose")) { pat("bd sd").superimpose { it.fast(2) } },
        case(".layer(rev, fast 2)", 1, setOf("layer")) { pat("bd sd").layer({ it.rev() }, { it.fast(2) }) },
        case(".jux{rev}", 1, setOf("jux"), setOf("pan")) { pat("bd sd hh").jux { it.rev() } },
        case(".juxBy(0.5){rev}", 1, setOf("juxBy"), setOf("pan")) { pat("bd sd hh").juxBy(0.5) { it.rev() } },
        case(".chunk(4){fast 2}", 4, setOf("chunk")) { pat("bd sd hh cp").chunk(4) { it.fast(2) } },
        case(".inside(2){rev}", 2, setOf("inside")) { pat("bd sd hh cp").inside(2) { it.rev() } },
        case(".outside(2){rev}", 2, setOf("outside")) { pat("bd sd hh cp").outside(2) { it.rev() } },
        case(".rot(1)", 1, setOf("rot")) { pat("bd sd hh cp").rot(1) },
        case(".press()", 1, setOf("press")) { pat("bd sd").press() },
        case(".pressBy(1/4)", 1, setOf("pressBy")) { pat("bd sd").pressBy(q(1, 4)) },
        case(".swing(4)", 1, setOf("swing")) { pat("bd hh bd hh").swing(4) },
        case(".swingBy(1/4,4)", 1, setOf("swingBy")) { pat("bd hh bd hh").swingBy(q(1, 4), 4) },
        case(".zoom(1/4,1/2)", 1, setOf("zoom")) { pat("bd sd hh cp").zoom(q(1, 4), q(1, 2)) },
        case(".withinCycle(0..1/2)", 1, setOf("withinCycle")) {
            pat("bd sd hh cp").withinCycle(PatArc(PatQ.ZERO, q(1, 2))) { it.fast(2) }
        },
        case(".whenCycle(even)", 2, setOf("whenCycle")) {
            pat("bd sd hh").whenCycle({ it % 2 == 0L }, { it.rev() })
        },
        case(".whenMod(0,4)", 4, setOf("whenMod")) { pat("bd sd hh").whenMod(0, 4) { it.rev() } },
        case(".switchEvery(2)", 4, setOf("switchEvery")) { pat("bd*4").switchEvery(2, pat("cp cp")) },
        case(".playWhen(first half)", 2, setOf("playWhen")) {
            pat("bd sd hh cp").playWhen { it < q(1, 2) }
        },

        // ── randomness, all seeded so the oracle stays deterministic ────────────────────────────
        case(".degrade()", 4, setOf("degrade", "degradeBy")) { pat("bd*8").degrade() },
        case(".degradeBy(0.5)", 4, setOf("degradeBy")) { pat("bd*8").degradeBy(0.5, salt = 7) },
        case(".unDegradeBy(0.5)", 4, setOf("unDegradeBy")) { pat("bd*8").unDegradeBy(0.5, salt = 7) },
        case(".sometimes{rev}", 4, setOf("sometimes", "sometimesBy")) { pat("bd sd").sometimes { it.rev() } },
        case(".sometimesBy(0.5)", 4, setOf("sometimesBy")) {
            pat("bd sd").sometimesBy(0.5, salt = 3) { it.fast(2) }
        },
        case(".someCyclesBy(0.5)", 4, setOf("someCyclesBy")) {
            pat("bd sd").someCyclesBy(0.5, salt = 5) { it.rev() }
        },
        case(".sometimesEvent{}", 2, setOf("sometimesEvent", "sometimesEventBy")) {
            pat("bd*4").sometimesEvent { it.fast(2) }
        },
        case(".sometimesEventBy(0.4)", 2, setOf("sometimesEventBy")) {
            pat("bd*4").sometimesEventBy(0.4, salt = 2) { it.fast(2) }
        },
        case(".shuffle(4)", 2, setOf("shuffle")) { pat("bd sd hh cp").shuffle(4, salt = 11) },

        // ── sample slicing ─────────────────────────────────────────────────────────────────────
        case(".chop(2)", 1, setOf("chop")) { pat("bd sd").chop(2) },
        case(".striate(2)", 1, setOf("striate")) { pat("bd sd").striate(2) },
        case(".striateAcross(2)", 1, setOf("striateAcross")) { pat("bd sd").striateAcross(2) },
        case(".segment(4)", 1, setOf("segment")) { pat("bd").segment(4) },
        case(".discretise(4)", 1, setOf("discretise", "segment")) { pat("bd").discretise(4) },
        case(".dropSampleIndex()", 1, setOf("dropSampleIndex")) { pat("bd:2 hh:1 sd:3").dropSampleIndex() },

        // ── combining two patterns ─────────────────────────────────────────────────────────────
        case("mask(euclid)", 1, setOf("mask")) { mask(euclid(3, 8), pat("bd*8")) },
        case("struct(euclid)", 1, setOf("struct")) { struct(euclid(3, 8), pat("bd")) },
        case("join", 2, setOf("join")) { join(pat("a b").map { pat("bd sd") }) },
        case("squeezeJoin", 1, setOf("squeezeJoin")) { squeezeJoin(pat("a b").map { pat("bd sd") }) },
        case(".squeeze{}", 1, setOf("squeeze")) { pat("a b").squeeze { pat("bd sd") } },
        case("`#`", 1, setOf("#", "|R", "appLeft")) { pat("bd sd") `#` pat("hh cp hh cp") },
        case("`R|`", 1, setOf("R|", "appRight")) { pat("bd sd") `R|` pat("hh cp hh cp") },
        case("`|L`", 1, setOf("|L", "appLeft")) { pat("bd sd") `|L` pat("hh cp hh cp") },
        case("`L|`", 1, setOf("L|", "appRight")) { pat("bd sd") `L|` pat("hh cp hh cp") },
        case("valuesFrom", 1, setOf("valuesFrom", "|R")) { pat("bd sd") valuesFrom pat("hh cp") },
        case("rhythmFrom", 1, setOf("rhythmFrom", "L|")) { pat("bd sd") rhythmFrom pat("hh cp hh") },
        case("appBoth", 1, setOf("appBoth")) {
            pat("bd sd").appBoth(pat("hh cp")) { a, _ -> a }
        },
        case(".zip(n)", 1, setOf("zip")) { pat("bd sd").zip("1 2", "n") },

        // ── pitch: these leave the String domain and come back through texts() ─────────────────
        case("nums/scale/texts", 1, setOf("nums", "scale", "texts")) {
            pat("0 2 4 5").nums().scale("minor", root = 48).texts()
        },
        case("toScale", 1, setOf("nums", "toScale", "texts")) {
            pat("0 2 4").nums().toScale(listOf(0, 3, 5, 7), root = 48).texts()
        },
        case("chord/notesOf", 1, setOf("nums", "chord", "notesOf", "texts")) {
            pat("0 3").nums().chord("minor").notesOf().texts()
        },
        case("chordsOf/voiceLead/arpeggiate", 1, setOf("chordsOf", "voiceLead", "arpeggiate", "texts")) {
            pat("c'major e'minor").chordsOf().voiceLead().arpeggiate().texts()
        },
        case(".arp(up)", 1, setOf("arp")) { pat("bass").arp("60", "64", "67", speed = 4) },

        // ── signals, discretised back into atoms ───────────────────────────────────────────────
        case("range/rounded", 1, setOf("segment", "range", "rounded")) {
            sine().segment(4).range(48.0, 60.0).rounded().map { it.toString() }
        },
        case("rangex", 1, setOf("segment", "rangex", "texts")) {
            sine().segment(4).rangex(1.0, 8.0).texts()
        },
        case("quantise", 1, setOf("segment", "quantise", "texts")) {
            saw().segment(4).range(48.0, 60.0).quantise(3.0).texts()
        },
        case("bipolar", 1, setOf("segment", "bipolar", "range", "texts")) {
            sine().segment(4).bipolar().range(48.0, 60.0).texts()
        },
        case("pick", 1, setOf("segment", "pick")) {
            sine().segment(4).pick("bd", "sd", "hh")
        },
        // One arithmetic operator taken all the way through the lowering, so the claim made about
        // the other twenty-three in EXCLUDED is demonstrated rather than asserted.
        case("`|+|` through texts", 1, setOf("|+|", "segment", "range", "rounded")) {
            (sine().segment(4).range(48.0, 54.0) `|+|` saw().segment(4).range(0.0, 6.0))
                .rounded().map { it.toString() }
        },

        // ── parameters ─────────────────────────────────────────────────────────────────────────
        case("notes c4 e4 g4", 1, notation = setOf("notes")) { pat("c4 e4 g4 60") },
        case("synth atoms", 1, notation = setOf("synthAtoms")) { pat("tb303 prophet pluck") },
        case("with gain const", 1, setOf("with"), setOf("gain")) { pat("bd sd hh cp") with gain(0.7) },
        case("with gain signal", 1, setOf("with", "range", "segment"), setOf("gain", "signal")) {
            pat("bd*4") with gain(sine().range(0.3, 0.9))
        },
        case("with pan signal", 1, setOf("with"), setOf("pan", "signal")) {
            pat("bd sd hh cp") with pan(saw())
        },
        case("with cutoff on tb303", 1, setOf("with"), setOf("cutoff")) {
            pat("tb303 tb303") with cutoff(880.0)
        },
        case("with speed on sample", 1, setOf("with"), setOf("speed")) {
            pat("bd_haus loop_amen") with speed(1.5)
        },
        case("without", 1, setOf("with", "without"), setOf("gain")) {
            (pat("bd sd") with gain(0.5)).without("gain")
        }
    )

    /** Returns the transform names the corpus actually exercised. */
    private fun checkDifferentialOracle(failures: MutableList<String>): Set<String> {
        val instruments = SonicPiInstruments()
        val covered = mutableSetOf<String>()
        var totalEvents = 0
        var checkedCases = 0

        for (case in corpus()) {
            val before = failures.size
            // Two cps values, because the emitted lattice must be tempo-independent: `use_bpm`
            // carries the tempo, so the same pattern at 0.5 and at 2.7 cps must emit identical
            // sleeps. A cps that leaked into a sleep would show up here and nowhere else.
            for (cps in listOf(1.0, 0.5, 2.7)) {
                val program = case.pattern.toSonicPiProgram(
                    cps = cps,
                    cycles = case.cycles,
                    name = "oracle",
                    instruments = instruments
                )
                val reading = SonicPiProgramReader.read(program.source)

                if (reading.unparsed.isNotEmpty()) {
                    failures += "${case.label}: ${reading.unparsed.size} line(s) the reader does " +
                        "not understand, first = '${reading.unparsed.first().trim()}'"
                }
                if (program.unresolved.isNotEmpty()) {
                    failures += "${case.label}: unresolved atoms ${program.unresolved.map { it.atom }}"
                }

                // The loop closes on the bar: the sleeps must sum to exactly `cycles`, or the
                // second time round the loop everything is early and stays early.
                val period = reading.loopPeriods["oracle"]
                if (period == null || normalisePatQ(period) != PatQ.of(case.cycles)) {
                    failures += "${case.label} @ $cps cps: loop period $period, expected ${case.cycles}"
                }

                // What the pattern says, straight from the public API.
                val expected = case.pattern
                    .scheduled(cps = cps, fromCycle = 0, startAt = 0.0, clock = null)
                    .takeWhile { it.arc.start < PatQ.of(case.cycles) }
                    .filter {
                        val v = instruments.resolve(it.value)
                        v != null && v != SonicPiVoice.Silent
                    }
                    .toList()

                if (reading.events.size != expected.size) {
                    failures += "${case.label} @ $cps cps: read ${reading.events.size} events " +
                        "from the program, the pattern schedules ${expected.size}"
                } else {
                    for ((index, pair) in reading.events.zip(expected).withIndex()) {
                        val (read, want) = pair
                        // Exact, in cycle space. No doubles.
                        if (normalisePatQ(read.at) != normalisePatQ(want.arc.start)) {
                            failures += "${case.label} @ $cps cps: event $index at ${read.at}, " +
                                "pattern says ${want.arc.start}"
                            break
                        }
                        // And the same answer at the seconds boundary the public API reports in.
                        val seconds = read.at.toDouble() / cps
                        if (abs(seconds - want.start) > 1e-9) {
                            failures += "${case.label} @ $cps cps: event $index at ${seconds}s, " +
                                "scheduled() says ${want.start}s"
                            break
                        }
                    }
                }
                if (cps == 1.0) totalEvents += reading.events.size
            }

            // Determinism: the same pattern must emit the same text. A generated program that
            // differs run to run cannot be diffed, and cannot be committed as a demo.
            val once = case.pattern.sonicPiSource(cps = 1.0, cycles = case.cycles)
            val twice = case.pattern.sonicPiSource(cps = 1.0, cycles = case.cycles)
            if (once != twice) failures += "${case.label}: emitted source is not deterministic"

            checkedCases++
            // A case only gets to claim its labels if every tempo of it agreed. A transform that
            // starts disagreeing therefore *drops out of the coverage number*, so the floor below
            // catches it twice: once as a failed comparison and once as a coverage regression.
            if (failures.size == before) {
                covered += case.covers
                NOTATION_COVERED += case.notation
            }
        }

        println("  $checkedCases patterns x 3 tempos, $totalEvents events, agreement exact in PatQ")
        return covered
    }

    // ── parameters ─────────────────────────────────────────────────────────────────────────────

    private fun checkOptTranslation(failures: MutableList<String>) {
        fun optsOf(source: String, vararg params: PatParam): Map<String, String> {
            val program = pat(source).with(*params).toSonicPiProgram(name = "opts")
            return SonicPiProgramReader.read(program.source).events.first().opts
        }

        // gain -> amp, unchanged.
        val amp = optsOf("bd", gain(0.75))
        if (amp["amp"] != "0.75") failures += "gain(0.75) became amp: ${amp["amp"]}, expected 0.75"

        // pan 0..1 -> -1..1. Field's centre is 0.5; Sonic Pi's is 0.
        val panCentre = optsOf("bd", pan(0.5))
        if (panCentre["pan"] != "0") failures += "pan(0.5) became pan: ${panCentre["pan"]}, expected 0"
        val panLeft = optsOf("bd", pan(0.0))
        if (panLeft["pan"] != "-1") failures += "pan(0.0) became pan: ${panLeft["pan"]}, expected -1"

        // cutoff Hz -> MIDI note. 440 Hz is A4, MIDI 69, and on a sample it is `lpf`, not `cutoff`.
        val sampleCut = optsOf("bd", cutoff(440.0))
        if (sampleCut["lpf"] != "69") failures += "cutoff(440) on a sample became lpf: ${sampleCut["lpf"]}, expected 69"
        if ("cutoff" in sampleCut) failures += "a sample was given a `cutoff:` opt, which it has no such thing as"

        val synthCut = optsOf("tb303", cutoff(880.0))
        if (synthCut["cutoff"] != "81") failures += "cutoff(880) on :tb303 became cutoff: ${synthCut["cutoff"]}, expected 81"

        // The same opt on a synth that has no such opt is dropped, and *recorded*.
        val onBeep = pat("beep").with(cutoff(880.0)).toSonicPiProgram(name = "opts")
        if (onBeep.droppedOpts.none { it.key == "cutoff" }) {
            failures += "cutoff on :beep should have been dropped and recorded, droppedOpts=${onBeep.droppedOpts}"
        }
        if ("cutoff:" in onBeep.source) failures += "cutoff was emitted for :beep, which does not accept it"
        if ("DROPPED OPTS" !in onBeep.source) failures += "a dropped opt is not mentioned in the emitted program"

        // speed -> rate, samples only.
        val rate = optsOf("bd", speed(1.5))
        if (rate["rate"] != "1.5") failures += "speed(1.5) became rate: ${rate["rate"]}, expected 1.5"

        println("  gain->amp, pan rescaled to -1..1, cutoff Hz->MIDI (440->69), speed->rate")
    }

    /**
     * [SonicPiProgram.source] is something a person pastes into a `#sonicpi` region, so whatever
     * comes out of it has to be Ruby all the way through.
     *
     * This exists because it was not. The tempo started life as a `__CPS__` placeholder substituted
     * on the way out of `Box.sonicPiPattern`, which meant the *public* `source` — the field the
     * whole API is named after — carried a token Ruby has never heard of. It only showed up when
     * the program was printed from a box, which is the first thing anyone does.
     */
    private fun checkSourceIsRunnable(failures: MutableList<String>) {
        for (cps in listOf(0.25, 0.5, 1.0, 2.0)) {
            val program = pat("bd sd hh").toSonicPiProgram(cps = cps, cycles = 2, name = "run")
            val source = program.source
            if ("__" in source) failures += "an unsubstituted placeholder survived into source at $cps cps"
            if ("use_bpm 60 * ${formatRubyNumber(cps)}" !in source) {
                failures += "the emitted tempo line is wrong at $cps cps: " +
                    source.lines().first { it.trim().startsWith("use_bpm") }.trim()
            }
            // Every route to the text has to give the same text; two of them once did not.
            if (pat("bd sd hh").sonicPiSource(cps = cps, cycles = 2, name = "run") != source) {
                failures += "sonicPiSource and SonicPiProgram.source disagree at $cps cps"
            }
            // Only Ruby the reader recognises, and a program that is nothing but a comment header
            // would satisfy that vacuously — so check it has a body too.
            val reading = SonicPiProgramReader.read(source)
            if (reading.unparsed.isNotEmpty()) failures += "unreadable line(s): ${reading.unparsed}"
            if (reading.events.size != 6) failures += "expected 6 events over 2 cycles, got ${reading.events.size}"
        }
        println("  no placeholders survive; source, sonicPiSource and the reader all agree")
    }

    /**
     * `"bass" to pat(...) on "tb303"` has to change two things at once, and the second is easy to
     * miss: the `use_synth` at the top of that `live_loop`, *and* which opts a bare note may carry.
     * A note with no synth of its own plays on the layer's synth, so it is that synth's opt list
     * that decides — asking `play`'s common opts instead drops `cutoff` from a `:tb303` layer,
     * which is correct for `:beep` and silently wrong for the case anyone would actually write.
     */
    private fun checkPerLayerSynth(failures: MutableList<String>) {
        val bass = pat("e1 ~ e2 ~") with cutoff(700.0)

        val onTb303 = sonicPiProgram(
            sonicPiLayer("drums", pat("bd sd")),
            sonicPiLayer("bass", bass, synth = "tb303"),
            cps = 1.0
        )
        if ("use_synth :tb303" !in onTb303.source) failures += "the bass layer did not get use_synth :tb303"
        if ("use_synth :beep" !in onTb303.source) failures += "the drums layer lost the default synth"
        if ("cutoff:" !in onTb303.source) failures += "cutoff did not reach a :tb303 layer: ${onTb303.droppedOpts}"
        if (onTb303.droppedOpts.isNotEmpty()) failures += "unexpected dropped opts: ${onTb303.droppedOpts}"

        // The same layer on the default synth: :beep has no cutoff, so it is dropped and recorded.
        val onBeep = sonicPiProgram("bass" to bass, cps = 1.0)
        if ("cutoff:" in onBeep.source) failures += "cutoff was emitted for a :beep layer"
        if (onBeep.droppedOpts.none { it.key == "cutoff" }) {
            failures += "cutoff on a :beep layer was neither emitted nor recorded as dropped"
        }

        // Per-layer instruments do not leak between layers.
        val kit = SonicPiInstruments().mapping("bd" to sonicPiSample("bd_tek"))
        val mixed = sonicPiProgram(
            sonicPiLayer("a", pat("bd"), instruments = kit),
            sonicPiLayer("b", pat("bd")),
            cps = 1.0
        )
        if ("sample :bd_tek" !in mixed.source) failures += "a per-layer override did not apply"
        if ("sample :bd_haus" !in mixed.source) failures += "a per-layer override leaked into the other layer"

        // And the emitted layers still agree with the patterns, exactly.
        val reading = SonicPiProgramReader.read(onTb303.source)
        if (reading.unparsed.isNotEmpty()) failures += "per-layer program has unreadable lines: ${reading.unparsed}"
        if (reading.loopPeriods.values.any { normalisePatQ(it) != PatQ.ONE }) {
            failures += "a per-layer loop does not close on the bar: ${reading.loopPeriods}"
        }
        println("  drums on :beep, bass on :tb303, cutoff reaches only the one that has it")
    }

    /**
     * `with_fx` wraps a layer's body, and the thing that must survive it is the timing.
     *
     * `with_fx :reverb do … end` adds a block — and an `end` — inside the `live_loop`. A reader that
     * counts the first `end` as the loop's would close the loop early and read a period of zero, and
     * every timing assertion downstream of that would be measuring a program that does not exist.
     * So the oracle is re-run here with two FX nested around a pattern that has a triplet in it: the
     * events, their exact `PatQ` positions and the loop's period must all be unchanged.
     */
    private fun checkFxChains(failures: MutableList<String>) {
        // The name and every opt are checked at construction, because Sonic Pi's answer to
        // `with_fx :revreb` is a runtime error on the loop's own thread, minutes in.
        if (runCatching { sonicPiFx("revreb") }.isSuccess) {
            failures += "sonicPiFx accepted an FX that does not exist"
        }
        if (runCatching { sonicPiFx("reverb", "roomm" to 0.5) }.isSuccess) {
            failures += "sonicPiFx accepted an opt :reverb does not have"
        }
        if (runCatching { sonicPiFx("reverb", "room" to 0.8, "mix" to 0.4) }.isFailure) {
            failures += "sonicPiFx rejected room and mix, which :reverb does have"
        }

        val pattern = pat("bd ~ [bd bd bd] bd")
        val plain = sonicPiLayer("dry", pattern)
        val wet = sonicPiLayer(
            "wet",
            pattern,
            fx = listOf(sonicPiFx("reverb", "room" to 0.8), sonicPiFx("echo", "phase" to 0.25))
        )
        val program = sonicPiProgram(plain, wet, cps = 0.5, cycles = 2)
        val reading = SonicPiProgramReader.read(program.source)

        if (reading.unparsed.isNotEmpty()) {
            failures += "with_fx made lines the reader cannot read: ${reading.unparsed.map { it.trim() }}"
        }
        if (reading.fx["wet"]?.map { it.name } != listOf("reverb", "echo")) {
            failures += "the wet layer's FX read back as ${reading.fx["wet"]}, expected reverb then echo"
        }
        if (reading.fx["dry"] != null) failures += "the dry layer picked up FX: ${reading.fx["dry"]}"
        if ("with_fx :reverb, room: 0.8" !in program.source) failures += "the reverb opt was not emitted"
        if ("with_fx :echo, phase: 0.25" !in program.source) failures += "the echo opt was not emitted"

        // The point of the whole check: the lattice is identical wet and dry.
        val dryEvents = reading.events.filter { it.layer == "dry" }
        val wetEvents = reading.events.filter { it.layer == "wet" }
        if (dryEvents.size != wetEvents.size) {
            failures += "with_fx changed the event count: ${dryEvents.size} dry vs ${wetEvents.size} wet"
        } else if (dryEvents.map { normalisePatQ(it.at) } != wetEvents.map { normalisePatQ(it.at) }) {
            failures += "with_fx moved the events"
        }
        for (layer in listOf("dry", "wet")) {
            val period = reading.loopPeriods[layer]
            if (period == null || normalisePatQ(period) != PatQ.of(2)) {
                failures += "loop :$layer closed at $period, expected 2 — an `end` was miscounted"
            }
        }
        // And against the pattern itself, exactly, the same way the oracle does.
        val expected = pattern.scheduled(cps = 0.5, fromCycle = 0, startAt = 0.0, clock = null)
            .takeWhile { it.arc.start < PatQ.of(2) }.toList()
        if (wetEvents.size != expected.size ||
            wetEvents.map { normalisePatQ(it.at) } != expected.map { normalisePatQ(it.arc.start) }
        ) {
            failures += "a layer inside two with_fx blocks no longer agrees with its pattern"
        }
        println("  2 nested FX, ${wetEvents.size} events, lattice identical to the dry layer")
    }

    /**
     * The same pattern, rendered by *Field*, measured by the *same detector* the Sonic Pi probe uses.
     *
     * `sonicpi.pattern.timing` proves the emitted program's onsets land where the pattern says, in a
     * real recording from a foreign engine — but it needs Sonic Pi installed, so on most machines
     * that claim is untested. Field can render the same pattern to PCM offline through
     * [patRenderStereo], with no assets, no audio device and no install. Measuring that with
     * [patDetectOnsetSamples] closes the triangle:
     *
     *   notation → emitted program   asserted exactly, in PatQ, by the differential oracle above
     *   emitted program → Field PCM  asserted here, offline and deterministic
     *   emitted program → Sonic Pi   asserted by `sonicpi.pattern.timing` on the real engine
     *
     * The middle edge is the one that runs everywhere, and it is what makes the outer two
     * comparable: if Field's renderer and Sonic Pi's ever disagree about the same schedule, one of
     * them has a bug, and until both edges were measured with one instrument nothing in the repo
     * could see it.
     *
     * The expected onsets are read back out of the *emitted Sonic Pi program*, not taken from the
     * pattern object, so this measures the lowering rather than the pattern algebra.
     */
    private fun checkOfflineRendererOnsets(failures: MutableList<String>) {
        val pattern = pat("bd ~ [bd bd bd] bd")
        val cps = 0.25
        val cycles = 2
        val sampleRate = PatSoundDocs.SR

        val program = pattern.toSonicPiProgram(cps = cps, cycles = cycles, name = "offline")
        val reading = SonicPiProgramReader.read(program.source)
        val expected = reading.events.map { it.at.toDouble() / cps }
        val minGap = expected.zipWithNext().minOfOrNull { (a, b) -> b - a } ?: 0.2

        // A percussive voice, and the reason is not cosmetic. `patVoice()` defaults to
        // `sustain = 1.0`, which holds each note for its whole arc — at 0.25 cps the first `bd` is a
        // continuous second of 62 Hz sine. That render has no attacks after the first, so an onset
        // detector finds only the envelope's ripple: measured, it reported 22 onsets for 10 notes.
        // An onset detector can only measure a signal that has onsets, so the render is asked for
        // the thing the pattern means — struck drums.
        val voice = patVoice(sustain = 0.05, decay = 0.03)
        val mono = patRenderStereo(pattern, cycles = cycles, cps = cps, voice = voice, sampleRate = sampleRate).mono
        var energy = 0.0
        for (v in mono) energy += v.toDouble() * v
        val rms = kotlin.math.sqrt(energy / maxOf(1, mono.size))
        if (rms < 1e-4) {
            failures += "Field's own render of the pattern is silent (RMS $rms) — nothing was measured"
            return
        }

        val onsets = patDetectOnsetSamples(mono, sampleRate, minGap)
        val match = patMatchOnsets(onsets.map { it.toDouble() / sampleRate }, expected, tolerance = OFFLINE_TOLERANCE_S)

        println("  Field render: ${mono.size} frames at $sampleRate Hz, RMS ${"%.4f".format(rms)}")
        println("  " + match.describe("offline"))
        println("  observed intervals (samples): ${onsets.zipWithNext().map { it.second - it.first }}")
        println("  program  intervals (samples): " +
            expected.zipWithNext().map { (a, b) -> ((b - a) * sampleRate).roundToInt() })

        // Observed: 10/10 matched, worst 0.0227 ms — **one sample** at 44.1 kHz. Not zero, unlike
        // Sonic Pi's: `patRenderStereo` converts each onset to a frame index through a `Double`, so
        // it rounds where SuperCollider's bundle scheduling does not. One sample is the honest
        // answer and worth keeping visible. The bound is 0.25 ms, ~11 samples of slack.
        if (match.recall < 1.0) {
            failures += "Field's own renderer realised only ${match.matched} of ${match.scheduled} " +
                "scheduled onsets"
        }
        if (match.worstError.isNaN() || match.worstError > OFFLINE_TOLERANCE_S) {
            failures += "Field's own renderer is ${"%.4f".format(match.worstError * 1000)} ms off " +
                "the program it was given, which exceeds the ${OFFLINE_TOLERANCE_S * 1000} ms bound"
        }
        if (match.observed > match.scheduled) {
            failures += "the detector found ${match.observed} onsets in a render of " +
                "${match.scheduled} — it is firing on something that is not the pattern"
        }
    }

    private fun checkUnresolvedReporting(failures: MutableList<String>) {
        val program = pat("bd nonesuch_qq sd nonesuch_qq").toSonicPiProgram(name = "missing")
        val missing = program.unresolved.singleOrNull()
        when {
            missing == null -> failures += "an unresolvable atom was not reported: ${program.unresolved}"
            missing.atom != "nonesuch_qq" -> failures += "reported '${missing.atom}', expected 'nonesuch_qq'"
            missing.count != 2 -> failures += "reported ${missing.count} occurrences, expected 2"
        }
        if ("NOT PLAYED" !in program.source) {
            failures += "the emitted program does not say that an atom will not play"
        }
        // Two events still play, and the loop still closes on the bar.
        val reading = SonicPiProgramReader.read(program.source)
        if (reading.events.size != 2) failures += "expected the 2 resolvable atoms to play, got ${reading.events.size}"
        if (normalisePatQ(reading.loopPeriods["missing"] ?: PatQ.ZERO) != PatQ.ONE) {
            failures += "an unresolved atom broke the loop period: ${reading.loopPeriods}"
        }

        // A rest is silence, and is not reported as a problem.
        val rests = pat("bd ~ ~ sd").toSonicPiProgram(name = "rests")
        if (rests.unresolved.isNotEmpty()) failures += "'~' was reported as unresolved: ${rests.unresolved}"
        println("  an unknown atom is named in the program text; '~' is not")
    }

    private fun checkOneLiners(failures: MutableList<String>) {
        val i = SonicPiInstruments()
        fun line(atom: String, annotations: Map<String, String> = emptyMap()) =
            sonicPiOneLiner(i.resolve(atom)!!, annotations, i.defaultSynth)

        val sample = line("bd", mapOf("gain" to "0.6"))
        if (sample != "sample :bd_haus, amp: 0.6") failures += "drive-mode sample line was '$sample'"

        // use_synth has to be on the same line: each /run-code is its own run on its own thread,
        // so a use_synth sent separately sets a thread-local on a thread that has already finished.
        val note = line("tb303")
        if (note != "use_synth :tb303; play 60") failures += "drive-mode synth line was '$note'"
        if (";" !in note) failures += "the drive-mode synth line split use_synth onto its own message"

        val bare = line("c4")
        if (bare != "use_synth :beep; play ${patNoteNumber("c4")}") failures += "drive-mode note line was '$bare'"
        println("  $sample / $note")
    }

    // ── coverage ───────────────────────────────────────────────────────────────────────────────

    /**
     * Every `PatPattern -> PatPattern` function in the `Pat*` files, found by reflection.
     *
     * **The denominator is discovered, not chosen.** The first version of this test scored
     * "53/53 = 100%" against a hand-written list, and a hand-written denominator is worth nothing:
     * it silently omits whatever was awkward, and it does not grow when someone adds a transform.
     * This walks the five file classes and takes every public static method whose first parameter
     * and return type are both `PatPattern`. That is 110 names today, and it is 111 the moment
     * anyone adds one.
     *
     * The corresponding rule is in [checkCoverage]: a name that is neither exercised, nor
     * explicitly excluded with a reason, nor on the counted debt list, **fails the test**.
     */
    private fun mechanicalTransformSurface(): Set<String> {
        val files = listOf(
            "TidalPatternsKt", "PatFunctionsKt", "PatCompositionKt", "PatAlgebraKt", "PatSignalsKt"
        )
        val names = sortedSetOf<String>()
        for (file in files) {
            val cls = Class.forName("afield.four.synth.$file")
            for (method in cls.methods) {
                if ('$' in method.name) continue
                if (!java.lang.reflect.Modifier.isPublic(method.modifiers)) continue
                val params = method.parameterTypes
                if (params.isEmpty() || params[0] != PatPattern::class.java) continue
                if (method.returnType != PatPattern::class.java) continue
                names += method.name
            }
        }
        return names
    }

    /**
     * Names deliberately not exercised as lowerings, each with the reason.
     *
     * "Deliberately" has to mean something, so every reason here is a property of the function
     * rather than of how much work it would be. The two that qualify: it operates in the signal
     * (`Double`) domain and so reaches the lowering only as a control parameter — which `with`,
     * `gain` and `range` do exercise — or it is not really public.
     */
    private val EXCLUDED: Map<String, String> = buildMap {
        // Every one of these is `|+|` with a different arithmetic function or a different structure
        // bias, and `|+|` *is* in the corpus, lowered end to end. Three facts make that enough, and
        // they are properties of the functions rather than of how much work a case would be:
        // they are all defined as `appBoth`/`appLeft`/`appRight` with a lambda, all three of which
        // the corpus exercises directly; they are `PatPattern<Double>` -> `PatPattern<Double>`, so
        // they reach the lowering only after `texts()` or as a control parameter; and **none of
        // them can move an event in time** — the structure comes from `app*`, and only the value
        // differs. A timing lowering cannot be wrong for `|-|` in a way it is right for `|+|`.
        val sibling = "arithmetic sibling of `|+|` (which the corpus lowers end to end): defined as " +
            "appBoth/appLeft/appRight with a different operator, so it cannot move an event in time"
        listOf(
            "|+", "+|", "|-|", "|-", "-|", "|*|", "|*", "*|",
            "|over|", "|over", "over|", "|%|", "|%", "%|",
            "addBoth", "addLeft", "addRight", "mulBoth", "mulLeft", "mulRight",
            "divBoth", "divLeft", "divRight"
        ).forEach { put(it, sibling) }
        put("mapEventsPerCycle", "internal; visible here only because Kotlin `internal` is public in bytecode")
    }

    /**
     * Transforms with a real Sonic Pi meaning that the corpus does not yet exercise. A debt list.
     *
     * Named and counted rather than omitted, because the whole failure mode this test exists to
     * prevent is a denominator that quietly shrinks to whatever was easy. [checkCoverage] asserts
     * this does not grow, and the honest coverage fraction below counts every one of these as a
     * miss.
     */
    private val KNOWN_GAPS: Set<String> = setOf()

    private fun checkCoverage(covered: Set<String>, failures: MutableList<String>) {
        val surface = mechanicalTransformSurface()
        println("  mechanical surface: ${surface.size} PatPattern -> PatPattern functions")

        // A corpus that claims to cover something the surface does not contain is a stale label.
        val phantom = covered - surface
        if (phantom.isNotEmpty()) {
            failures += "the corpus claims to cover ${phantom.sorted()}, which are not in the " +
                "reflected transform surface — a renamed or deleted function, or a typo"
        }
        // An exclusion for something that no longer exists is also drift.
        val staleExclusion = EXCLUDED.keys - surface
        if (staleExclusion.isNotEmpty()) failures += "EXCLUDED names no longer in the surface: ${staleExclusion.sorted()}"
        val staleGap = KNOWN_GAPS - surface
        if (staleGap.isNotEmpty()) failures += "KNOWN_GAPS names no longer in the surface: ${staleGap.sorted()}"

        // The rule that stops the denominator being curated.
        val unaccounted = surface - covered - EXCLUDED.keys - KNOWN_GAPS
        if (unaccounted.isNotEmpty()) {
            failures += "${unaccounted.size} transform(s) are neither exercised, excluded with a " +
                "reason, nor on the debt list: ${unaccounted.sorted()}. Add a corpus case, or add " +
                "the name to EXCLUDED with why it has no Sonic Pi meaning, or to KNOWN_GAPS."
        }

        val applicable = surface - EXCLUDED.keys
        val hit = covered intersect applicable
        val fraction = if (applicable.isEmpty()) 0.0 else hit.size.toDouble() / applicable.size
        val rawFraction = hit.size.toDouble() / surface.size

        println("  excluded with a reason: ${EXCLUDED.size} (${EXCLUDED.values.distinct().size} distinct reasons)")
        println("  debt (real meaning, no case yet): ${KNOWN_GAPS.size}${if (KNOWN_GAPS.isEmpty()) "" else " " + KNOWN_GAPS.sorted()}")
        println("  covered ${hit.size}/${applicable.size} of the applicable surface = " +
            "${"%.1f".format(fraction * 100)}%   (${"%.1f".format(rawFraction * 100)}% of all ${surface.size})")
        println("  mini-notation features exercised: ${NOTATION_COVERED.size} — ${NOTATION_COVERED.sorted()}")

        // Floors, set just under what was measured, so a regression fails and normal drift does not.
        val floor = 0.95
        if (fraction < floor) {
            failures += "applicable transform coverage ${"%.3f".format(fraction)} is below the " +
                "floor $floor (missing: ${(applicable - hit).sorted()})"
        }
        if (KNOWN_GAPS.size > 6) {
            failures += "the debt list has grown to ${KNOWN_GAPS.size}; it is a ratchet, not a bucket"
        }
        if (NOTATION_COVERED.size < 12) {
            failures += "only ${NOTATION_COVERED.size} mini-notation features exercised, expected >= 12"
        }
    }

}
