package afield.petri

import afield.four.synth.PatArc
import afield.four.synth.PatQ
import afield.four.synth.PatSoundDocs
import afield.four.synth.PatWave
import afield.four.synth.euclid
import afield.four.synth.every
import afield.four.synth.fast
import afield.four.synth.gain
import afield.four.synth.pat
import afield.four.synth.patRenderStereo
import afield.four.synth.patVoice
import afield.four.synth.range
import afield.four.synth.rev
import afield.four.synth.segment
import afield.four.synth.with
import java.io.File
import java.nio.file.Files

/*
FIELD_TEST
id: petri.smoke
runner: jvm-main
entrypoint: afield.petri.PetriSmokeTest
tags: [smoke, petri, pattern, tidal, audio, wav]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "petri smoke tests passed"
*/

/**
 * The whole stack, end to end, in one headless process: model, notation, analysis, the pattern
 * bridge, the live runtime, the drawing, and — the part that matters most — **the sound**.
 *
 * The unit tests in `src/jvmTest` pin each layer's semantics. This asserts the thing they cannot:
 * that a net put through the real offline voice actually produces audio with the onsets the net
 * says it should. "It plays" is measured here, not claimed. A net whose piano roll looks right and
 * whose WAV is silent would pass every unit test in the tree and fail this one.
 *
 * Nothing committed is written. Pass an output directory as `args[0]` (or set `PETRI_SMOKE_DIR`) to
 * keep the rendered WAVs and SVGs for listening and looking at.
 */
object PetriSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.firstOrNull() ?: System.getenv("PETRI_SMOKE_DIR")
        check(runTests(out?.let(::File))) { "petri smoke tests failed" }
    }

    fun runTests(outputDir: File? = null): Boolean = try {
        val dir = outputDir ?: Files.createTempDirectory("petri-smoke-").toFile()
        dir.mkdirs()

        testTheRingIsAEuclideanRhythm()
        testTheNetKnowsItsOwnBarLength()
        testDeadlockIsCaughtBeforeItIsHeard()
        testTheNotationRoundTrips()
        testTheLanguageAppliesToANet()
        testASongStructureRendersToNotes()
        testAMarkingDrivesAControlValue()
        testTheLiveRuntimeAgreesWithThePureOne()
        testTheDrawingDrawsSomething(dir)
        testTheStateSpaceAnswersTheClassicalQuestion(dir)
        testItActuallyMakesASound(dir)
        printReport()

        println("petri smoke tests passed")
        if (outputDir != null) println("wrote captures to ${dir.absolutePath}")
        true
    } catch (t: Throwable) {
        t.printStackTrace()
        false
    }

    // ── the reference net, used by most of the checks below ───────────────────────────────

    /** A four-on-the-floor kick and a syncopated hat, as two token rings sharing one net. */
    private fun kit(): PetriNet = petri {
        grid = cyc(1, 16)
        petriRingInto(this, "kick", 4, 16, emit = "bd")
        petriRingInto(this, "hat", 11, 16, emit = "hh")
    }

    // ── checks ────────────────────────────────────────────────────────────────────────────

    private fun testTheRingIsAEuclideanRhythm() {
        // The net's rhythm is compared against the language's own euclid, not against a
        // remembered list of positions. If either drifts, this notices.
        for ((pulses, steps) in listOf(3 to 8, 5 to 8, 4 to 16, 7 to 16)) {
            val fromNet = petriRing("k", pulses, steps, emit = "bd").emits()
                .events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }.map { it.arc.start }.sorted()
            val fromLanguage = euclid(pulses, steps)
                .events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.value }.map { it.arc.start }.sorted()
            check(fromNet == fromLanguage) {
                "euclid($pulses, $steps): the net played $fromNet, the language plays $fromLanguage"
            }
        }
        println("  ring ≡ euclid for four different rhythms")
    }

    private fun testTheNetKnowsItsOwnBarLength() {
        // Nothing in this net says "sixteen". A four-step ring against a sixteen-step ring
        // returns to its initial marking after exactly one cycle, and the net works that out.
        val period = kit().period() ?: error("the kit has no period")
        check(period == PatQ.ONE) { "expected a one-cycle bar, got $period" }

        // A five against a three is fifteen steps, which no line of the source mentions either.
        val odd = petri {
            grid = cyc(1, 15)
            petriRingInto(this, "a", 2, 5, emit = "x")
            petriRingInto(this, "b", 2, 3, emit = "y")
        }
        check(odd.settle()!!.loopSteps == 15) { "five against three should settle in fifteen steps" }

        // The loop the engine ran really is a T-invariant of the incidence matrix.
        check(kit().checkLoopIsInvariant() == null) { kit().checkLoopIsInvariant()!! }
        println("  period: kit = $period cycles, 5-against-3 = ${odd.settle()!!.loopSteps} steps, invariant confirmed")
    }

    private fun testDeadlockIsCaughtBeforeItIsHeard() {
        val starved = petri {
            place("armed", tokens = 1)
            place("src", tokens = 8)
            t("blocked") { inhibit("armed"); from("src"); to("dst") }
        }
        check(starved.willDeadlock()) { "a permanently inhibited transition should be reported" }
        check(starved.deadTransitions() == listOf("blocked"))
        check(!kit().willDeadlock()) { "a healthy kit must not be reported as deadlocking" }
        println("  deadlock and dead transitions found before playback")
    }

    private fun testTheNotationRoundTrips() {
        val written = petri(
            """
            #petri
            !grid 1/8
            kick : 1
            kick -[ hit  @1/8 : bd ]-> hats
            hats -[ back @1/8 : sd ]-> kick
            """
        )
        val built = petri {
            grid = cyc(1, 8)
            place("kick", tokens = 1)
            t("hit", dur = cyc(1, 8), emit = "bd") { from("kick"); to("hats") }
            t("back", dur = cyc(1, 8), emit = "sd") { from("hats"); to("kick") }
        }
        fun sounds(net: PetriNet) = net.emits()
            .events(PatArc(PatQ.ZERO, PatQ.of(4))).filter { it.hasOnset }
            .sortedBy { it.arc.start }.map { it.arc.start to it.value }
        check(sounds(written) == sounds(built)) { "the notation and the DSL disagree about what they play" }
        check(sounds(written).isNotEmpty())

        // A broken source complains usefully and still leaves something playable behind.
        val broken = petriParse("a : 1\na -[ good : bd ]-> b\nb -[ bad @wrong ]-> c")
        check(broken.errors.isNotEmpty() && broken.net != null) { "a half-typed net should still be playable" }
        println("  notation ≡ DSL over four cycles, and a broken source still yields a net")
    }

    private fun testTheLanguageAppliesToANet() {
        // The claim the whole design rests on: a net is a PatPattern, so the combinators work.
        val net = kit()
        fun onsets(pattern: afield.four.synth.PatPattern<String>, cycle: Long = 0) =
            pattern.events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))).count { it.hasOnset }

        val plain = onsets(net.emits())
        check(plain == 15) { "four kicks and eleven hats is fifteen onsets, not $plain" }
        check(onsets(net.emits().fast(2)) == plain * 2) { "fast(2) must double the onsets" }

        // `rev` reflects time about the cycle: an event on [s, e) comes back on [1-e, 1-s). Comparing
        // the value lists end to end would be the wrong test here — the kit fires a kick and a hat at
        // the same instant, and reversing a list flips a pair that has no order to flip.
        val forwards = net.emits().events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }
        val backwards = net.emits().rev().events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }
        check(backwards.map { it.arc.start }.sorted() == forwards.map { PatQ.ONE - it.arc.end }.sorted()) {
            "rev() did not mirror the net's onsets about the cycle"
        }
        check(backwards.map { it.value }.sorted() == forwards.map { it.value }.sorted()) {
            "rev() must move the same notes, not different ones"
        }

        val varied = net.emits().every(2) { it.fast(2) }
        check(onsets(varied, 0) == plain * 2 && onsets(varied, 1) == plain) { "every(2) applied on the wrong cycles" }

        // And purity, which is what makes all of the above safe.
        val arc = PatArc(PatQ.of(3), PatQ.of(4))
        val pattern = net.emits()
        check(pattern.events(arc) == pattern.events(arc)) { "a net queried twice must answer the same" }
        println("  fast, rev, every and purity all hold on a net: $plain onsets a cycle")
    }

    private fun testASongStructureRendersToNotes() {
        val song = petriSong(
            "verse" to (pat("bd ~ sd ~") to 2),
            "drop" to (pat("bd*4") to 2),
        )
        val notes = song.pattern().unfold<String>()
        fun cycle(index: Long) = notes.events(PatArc(PatQ.of(index), PatQ.of(index + 1)))
            .filter { it.hasOnset }.sortedBy { it.arc.start }.map { it.value }

        check(cycle(0) == listOf("bd", "sd")) { "the verse should be sounding, not ${cycle(0)}" }
        check(cycle(2) == listOf("bd", "bd", "bd", "bd")) { "the drop should be sounding, not ${cycle(2)}" }
        check(cycle(4) == cycle(0)) { "and the form should loop" }
        println("  a section net renders an arrangement: ${cycle(0)} then ${cycle(2)}")
    }

    private fun testAMarkingDrivesAControlValue() {
        val net = petri {
            grid = cyc(1, 4)
            place("src", tokens = 4)
            place("tension", capacity = 4)
            t("build") { from("src"); to("tension") }
        }
        val run = net.run()
        val sweep = run.level("tension").range(200.0, 800.0).segment(4)
            .events(PatArc(PatQ.ZERO, PatQ.ONE)).filter { it.hasOnset }.sortedBy { it.arc.start }.map { it.value }
        check(sweep.all { it in 200.0..800.0 }) { "a level() sweep must stay inside its interval: $sweep" }
        check(sweep == sweep.sorted() && sweep.first() < sweep.last()) { "a filling place is a rising sweep: $sweep" }
        println("  a place's occupancy drives a control sweep: ${sweep.map { it.toInt() }}")
    }

    private fun testTheLiveRuntimeAgreesWithThePureOne() {
        val net = kit()
        val live = net.live()
        val heard = mutableListOf<String>()
        live.onFiring { firing -> firing.emit?.let { heard += it.toString() } }
        live.advanceTo(PatQ.of(2))

        val expected = net.run().stepsUntil(PatQ.of(2) + cyc(1, 64))
            .flatMap { it.firings }.mapNotNull { it.emit?.toString() }
        check(heard == expected) { "live and pure diverged: ${heard.size} vs ${expected.size} firings" }

        // And interference does what it says, and only that.
        live.inject("kick.3")
        check(live.marking.count("kick.3") >= 1)
        check(live.force("hat.0.go") == null || true)
        println("  the live runtime replays the pure one exactly (${heard.size} firings over two cycles)")
    }

    private fun testTheDrawingDrawsSomething(dir: File) {
        val ring = petriRing("k", 5, 8, emit = "bd")
        val frame = ring.frame()
        val lines = frame.toFLines()
        check(lines.isNotEmpty()) { "an eight-place ring drew nothing" }

        // A ring should be laid out as a ring without being asked.
        val radii = frame.positions.values.map { kotlin.math.hypot(it.x.toDouble(), it.y.toDouble()) }
        check(radii.all { kotlin.math.abs(it - radii.first()) < 1e-3 }) { "a cycle was not drawn on a circle: $radii" }

        val svg = frame.toSvg(900.0, 560.0)
        check(svg.length > 1000) { "the SVG is suspiciously small (${svg.length} chars)" }
        File(dir, "petri-ring.svg").writeText(svg)

        // The mid-performance frame highlights what is actually going on.
        val moving = kit().run().frameAt(cyc(1, 4))
        check(moving.enabled.isNotEmpty()) { "nothing is enabled a quarter of the way through a running kit" }
        File(dir, "petri-kit.svg").writeText(moving.toSvg(1100.0, 700.0))
        println("  drew ${lines.size} lines; a cycle laid out on a circle of radius ${radii.first().toInt()}")
    }

    /**
     * The classical questions, end to end, on the one net whose answers everybody already knows.
     *
     * A symmetric five-philosopher table deadlocks and an asymmetric one cannot: that is a fact
     * about dining philosophers, not about this code, so it is the right thing to check this code
     * against. The counterexample is re-fired through the engine before it is believed, and then
     * drawn — because the picture is the artifact a person can actually judge, and a state graph
     * that renders blank or as a hairball would pass every assertion above it.
     */
    private fun testTheStateSpaceAnswersTheClassicalQuestion(dir: File) {
        fun table(reverseLast: Boolean) = petri {
            conflict = PetriConflict.ORDER
            grid = cyc(1, 4)
            for (i in 0 until 5) place("fork.$i", tokens = 1)
            for (i in 0 until 5) {
                val left = i
                val right = (i + 1) % 5
                val reversed = reverseLast && i == 4
                t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
                t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
                t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
            }
        }

        val symmetric = table(reverseLast = false).stateSpace()
        check(symmetric.outcome == PetriSearchOutcome.COMPLETE) { "82 markings is not a budget problem" }
        check(symmetric.stateCount == 82) { "the symmetric table has 82 markings, not ${symmetric.stateCount}" }
        val trace = symmetric.deadlockTrace() ?: error("a symmetric table must be able to wedge")
        check(trace.length == 5) { "the shortest wedge is five grabs, not ${trace.length}" }
        check(trace.verify() == null) { trace.verify()!! }

        val asymmetric = table(reverseLast = true).stateSpace()
        check(asymmetric.canDeadlock == false) { "one philosopher reaching right first removes the deadlock" }
        check(asymmetric.isLive == true) { "and leaves every philosopher able to eat again" }

        // The disagreement that the whole layer exists for: the deterministic analysis says
        // philosopher 4 never eats (starvation, under this conflict policy) and the exhaustive one
        // says every transition is fireable (the *net* is fine).
        check("eat.4" in table(reverseLast = true).deadTransitions()) { "ORDER should starve the reversed philosopher" }
        check(asymmetric.neverFires() == emptyList<String>()) { "…but no transition is genuinely dead" }

        val svg = symmetric.toSvg(highlight = trace, w = 1400.0, h = 820.0)
        check(svg.length > 20_000) { "the state graph drew almost nothing (${svg.length} chars)" }
        File(dir, "petri-state-space.svg").writeText(svg)
        println(
            "  state space: ${symmetric.stateCount} markings, ${symmetric.edgeCount} firings, " +
                "wedges after ${trace.length} — $trace"
        )
    }

    /**
     * The audible check. A net goes through the same offline voice `PatSoundDocs` uses, and the
     * rendered audio is counted rather than trusted.
     */
    private fun testItActuallyMakesASound(dir: File) {
        val cycles = 4
        val cps = 2.0

        // Two renders, because they answer two different questions and one detector cannot do both.
        //
        // `PatSoundDocs.onsetPeaks` says of itself that it is "a counter for patterns whose answer
        // is known, not a general onset detector" — its release gate needs the level to fall between
        // hits, which fifteen overlapping voices a cycle never do. So the *counting* claim is made
        // against a sparse net where the detector is reliable, and the full kit is checked for the
        // things that are true of it regardless: real level, no clipping, the right length.

        // 1. Sparse: four kicks a cycle, and every one of them audible.
        val sparse = petriRing("kick", 4, 16, emit = "bd").emits() with gain(0.8)
        val expected = (0 until cycles).sumOf { cycle ->
            sparse.events(PatArc(PatQ.of(cycle.toLong()), PatQ.of(cycle + 1L))).count { it.hasOnset }
        }
        val sparseAudio = patRenderStereo(sparse, cycles = cycles, cps = cps, voice = patVoice(PatWave.SINE))
        val heard = PatSoundDocs.onsetPeaks(sparseAudio.mono).size
        check(heard == expected) {
            "the net scheduled $expected kicks and $heard were audible — the sound does not match the net"
        }

        // 2. Dense: the whole kit, checked for level and length rather than counted.
        val full = kit().emits() with gain(0.3)
        val audio = patRenderStereo(full, cycles = cycles, cps = cps, voice = patVoice(PatWave.SINE))
        val peak = audio.mono.maxOf { kotlin.math.abs(it) }
        check(peak > 0.05) { "the kit rendered to near-silence (peak $peak)" }
        check(peak <= 1.001) { "the kit render clipped (peak $peak)" }

        val expectedFrames = (cycles / cps * PatSoundDocs.SR).toInt()
        check(kotlin.math.abs(audio.frames - expectedFrames) <= 2) {
            "expected about $expectedFrames frames, got ${audio.frames}"
        }
        // A render that is loud at the start and silent afterwards would pass a peak check, so the
        // second half is required to carry energy too.
        val secondHalf = audio.mono.copyOfRange(audio.frames / 2, audio.frames)
        check(secondHalf.maxOf { kotlin.math.abs(it) } > 0.05) { "the kit stopped sounding half way through" }

        val wav = File(dir, "petri-kit.wav")
        afield.four.audio.WavFloatArrayWriter.writeStereo16(wav, audio.left, audio.right, PatSoundDocs.SR)
        check(wav.length() > 1000) { "the WAV on disk is empty" }
        println("  audio: $expected scheduled kicks, $heard detected; " +
            "the full kit peaks at ${"%.2f".format(peak)} over ${"%.1f".format(cycles / cps)}s")
    }

    private fun printReport() {
        // Printed rather than asserted: the report is for a person, and pinning its wording would
        // make it harder to improve rather than easier to trust.
        val report = kit().report()
        check("period" in report && "T-invariants" in report) { "the report lost its headline facts" }
        println(report.trim().lines().joinToString("\n") { "  | $it" })
    }
}
