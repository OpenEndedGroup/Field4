package afield.four.synth

import kotlin.math.abs

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
        val covers: Set<String>,
        val pattern: PatPattern<String>
    )

    private fun corpus(): List<Case> = listOf(
        Case("bd sd hh cp", 1, setOf("sequence")) { pat("bd sd hh cp") },
        Case("bd [hh hh] sd [cp cp cp]", 1, setOf("group")) { pat("bd [hh hh] sd [cp cp cp]") },
        Case("bd <sd cp>", 2, setOf("alternation")) { pat("bd <sd cp>") },
        Case("bd*4, [~ cp]*2", 1, setOf("stack", "rest", "density")) { pat("bd*4, [~ cp]*2") },
        Case("bd ~ sd ~", 1, setOf("rest")) { pat("bd ~ sd ~") },
        Case("bd!3 cp", 1, setOf("replicate")) { pat("bd!3 cp") },
        Case("bd@3 cp", 1, setOf("elongate")) { pat("bd@3 cp") },
        Case("bd(3,8)", 1, setOf("euclid")) { pat("bd(3,8)") },
        Case("[bd cp](3,8) hh!2", 1, setOf("euclid", "group", "replicate")) { pat("[bd cp](3,8) hh!2") },
        Case("{bd hh cp}%4", 3, setOf("polymeter")) { pat("{bd hh cp}%4") },
        Case("{bd sd, cp hh rim}", 2, setOf("polymeter", "stack")) { pat("{bd sd, cp hh rim}") },
        Case("bd:2 hh", 1, setOf("sampleIndex")) { pat("bd:2 hh") },
        Case("triplet bd bd bd", 1, setOf("sequence", "triplet")) { pat("bd bd bd") },
        Case("seven", 1, setOf("sequence", "triplet")) { pat("bd sd hh cp bd sd hh") },

        Case(".fast(3)", 1, setOf("fast")) { pat("bd sd").fast(3) },
        Case(".slow(2)", 2, setOf("slow")) { pat("bd sd hh").slow(2) },
        Case(".rev()", 1, setOf("rev")) { pat("bd sd hh cp").rev() },
        Case(".every(3){rev}", 3, setOf("every", "rev")) { pat("bd sd hh").every(3) { it.rev() } },
        Case(".iter(4)", 4, setOf("iter")) { pat("bd sd hh cp").iter(4) },
        Case(".iterBack(4)", 4, setOf("iterBack")) { pat("bd sd hh cp").iterBack(4) },
        Case(".palindrome()", 2, setOf("palindrome")) { pat("bd sd hh").palindrome() },
        Case(".linger(1/4)", 1, setOf("linger")) { pat("bd sd hh cp").linger(1, 4) },
        Case(".ply(2)", 1, setOf("ply", "stutter")) { pat("bd sd").ply(2) },
        Case(".stutter(3)", 1, setOf("stutter")) { pat("bd cp").stutter(3) },
        Case(".off(1/8)", 1, setOf("off")) { pat("bd sd").off(1, 8) { it } },
        Case(".superimpose{fast 2}", 1, setOf("superimpose")) { pat("bd sd").superimpose { it.fast(2) } },
        Case(".layer(rev, fast 2)", 1, setOf("layer")) { pat("bd sd").layer({ it.rev() }, { it.fast(2) }) },
        Case(".jux{rev}", 1, setOf("jux", "pan")) { pat("bd sd hh").jux { it.rev() } },
        Case(".chunk(4){fast 2}", 4, setOf("chunk")) { pat("bd sd hh cp").chunk(4) { it.fast(2) } },
        Case(".inside(2){rev}", 2, setOf("inside")) { pat("bd sd hh cp").inside(2) { it.rev() } },
        Case(".outside(2){rev}", 2, setOf("outside")) { pat("bd sd hh cp").outside(2) { it.rev() } },
        Case(".rot(1)", 1, setOf("rot")) { pat("bd sd hh cp").rot(1) },
        Case(".press()", 1, setOf("press")) { pat("bd sd").press() },
        Case(".swing(4)", 1, setOf("swing")) { pat("bd hh bd hh").swing(4) },
        Case(".zoom(1/4, 1/2)", 1, setOf("zoom")) { pat("bd sd hh cp").zoom(PatQ.of(1, 4), PatQ.of(1, 2)) },
        Case(".degradeBy(0)", 1, setOf("degradeBy")) { pat("bd*8").degradeBy(0.0) },
        Case(".degradeBy(0.5)", 4, setOf("degradeBy")) { pat("bd*8").degradeBy(0.5, salt = 7) },
        Case(".sometimesBy(0.5){fast}", 4, setOf("sometimesBy")) {
            pat("bd sd").sometimesBy(0.5, salt = 3) { it.fast(2) }
        },
        Case(".whenMod(0,4){rev}", 4, setOf("whenMod")) { pat("bd sd hh").whenMod(0, 4) { it.rev() } },
        Case(".switchEvery(2)", 4, setOf("switchEvery")) { pat("bd*4").switchEvery(2, pat("cp cp")) },
        Case(".shuffle(4)", 2, setOf("shuffle")) { pat("bd sd hh cp").shuffle(4, salt = 11) },
        Case(".chop(2)", 1, setOf("chop")) { pat("bd sd").chop(2) },
        Case(".striate(2)", 1, setOf("striate")) { pat("bd sd").striate(2) },
        Case(".segment(4)", 1, setOf("segment")) { pat("bd").segment(4) },
        Case(".arp(up)", 1, setOf("arp")) { pat("bass").arp("60", "64", "67", speed = 4) },
        Case(".someCyclesBy(0.5)", 4, setOf("someCyclesBy")) {
            pat("bd sd").someCyclesBy(0.5, salt = 5) { it.rev() }
        },
        Case(".playWhen(first half)", 2, setOf("playWhen")) {
            pat("bd sd hh cp").playWhen { it < PatQ.of(1, 2) }
        },
        Case("[bd*4|hh*2|cp]", 4, setOf("choice")) { pat("[bd*4|hh*2|cp]") },

        Case("notes c4 e4 g4", 1, setOf("notes")) { pat("c4 e4 g4 60") },
        Case("synth atoms", 1, setOf("synthAtoms")) { pat("tb303 prophet pluck") },
        Case("with gain const", 1, setOf("gain", "with")) { pat("bd sd hh cp") with gain(0.7) },
        Case("with gain signal", 1, setOf("gain", "with", "signal")) {
            pat("bd*4") with gain(sine().range(0.3, 0.9))
        },
        Case("with pan signal", 1, setOf("pan", "with", "signal")) {
            pat("bd sd hh cp") with pan(saw())
        },
        Case("with cutoff on tb303", 1, setOf("cutoff", "with")) {
            pat("tb303 tb303") with cutoff(880.0)
        },
        Case("with speed on sample", 1, setOf("speed", "with")) {
            pat("bd_haus loop_amen") with speed(1.5)
        }
    )

    private fun Case(
        label: String,
        cycles: Int,
        covers: Set<String>,
        build: () -> PatPattern<String>
    ) = Case(label, cycles, covers, build())

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
            if (failures.size == before) covered += case.covers
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
     * The part of the `Pat` surface that has a Sonic Pi meaning.
     *
     * Deliberately not "every public function in `Pat*.kt`": `asNotes`, `respond`, `patHighlight`
     * and the signal *constructors* are not things a Sonic Pi lowering can be right or wrong about.
     * These are, and each one is either exercised by the corpus above or is a known gap.
     */
    private val MEANINGFUL: Set<String> = setOf(
        // mini-notation
        "sequence", "group", "alternation", "stack", "rest", "replicate", "elongate", "density",
        "euclid", "polymeter", "sampleIndex", "triplet", "choice",
        // time and structure
        "fast", "slow", "rev", "every", "iter", "iterBack", "palindrome", "linger", "ply",
        "stutter", "off", "superimpose", "layer", "jux", "chunk", "inside", "outside", "rot",
        "press", "swing", "zoom", "degradeBy", "sometimesBy", "whenMod", "switchEvery", "shuffle",
        "chop", "striate", "segment", "arp", "someCyclesBy", "playWhen",
        // instruments and parameters
        "notes", "synthAtoms", "with", "gain", "pan", "cutoff", "speed", "signal"
    )

    private fun checkCoverage(covered: Set<String>, failures: MutableList<String>) {
        val stray = covered - MEANINGFUL
        if (stray.isNotEmpty()) failures += "the corpus claims to cover $stray, which is not in MEANINGFUL"

        val hit = covered intersect MEANINGFUL
        val fraction = hit.size.toDouble() / MEANINGFUL.size
        val missing = (MEANINGFUL - hit).sorted()
        println("  ${hit.size}/${MEANINGFUL.size} = ${"%.1f".format(fraction * 100)}% of the Sonic Pi-meaningful Pat surface")
        if (missing.isNotEmpty()) println("  not covered: $missing")

        // The floor. A printed number with no assertion under it is the weakest thing to ship, and
        // it is the number itself that regresses: a transform that starts disagreeing with the
        // pattern drops out of `covered` (the case fails first), so this catches that too.
        val floor = 0.96
        if (fraction < floor) {
            failures += "transform coverage ${"%.3f".format(fraction)} is below the floor $floor " +
                "(missing: $missing)"
        }
    }
}
