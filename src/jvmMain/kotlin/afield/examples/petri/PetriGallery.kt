package afield.examples.petri

import afield.four.synth.PatArc
import afield.four.synth.PatPattern
import afield.four.synth.PatQ
import afield.petri.PETRI_LABEL_LIMIT
import afield.petri.PETRI_STATE_DRAW_LIMIT
import afield.petri.PetriCombinators
import afield.petri.PetriConflict
import afield.petri.PetriHalt
import afield.petri.PetriNet
import afield.petri.PetriTiming
import afield.petri.cyc
import afield.petri.deadTransitions
import afield.petri.deadlockMarking
import afield.petri.emits
import afield.petri.frame
import afield.petri.frameAt
import afield.petri.pattern
import afield.petri.pInvariants
import afield.petri.period
import afield.petri.petri
import afield.petri.petriLabelsRenderable
import afield.petri.petriParse
import afield.petri.report
import afield.petri.settle
import afield.petri.stateSpace
import afield.petri.summaryHtml
import afield.petri.tInvariants
import afield.petri.toFLines
import afield.petri.toSvg
import afield.petri.tooDenseToDraw
import afield.petri.unfold
import afield.petri.willDeadlock
import java.io.File
import java.nio.file.Files

/*
FIELD_TEST
id: petri.gallery
runner: jvm-main
entrypoint: afield.examples.petri.PetriGallery
tags: [smoke, examples, petri, docs, notation]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "petri gallery passed"
*/

/**
 * The worked `#petri` programs behind the documentation, run for real, plus the guard that keeps
 * the cheatsheet honest.
 *
 * Two jobs:
 *
 * 1. **Every example actually works.** Each program here is parsed with no diagnostics, run, and
 *    checked against a claim about what it does — a period, a rhythm, a deadlock. A cheatsheet is
 *    only worth reading if the things in it have been tried.
 *
 * 2. **Every documented operator has been tried.** [checkCheatsheetCoverage] scrapes the operator
 *    tables out of `petri_cheatsheet.md` and fails if any of them appears in no worked example.
 *    Without that, adding a word to the notation and forgetting to demonstrate it is a silent
 *    documentation rot rather than a build failure. The RectSL gallery does the same thing, for the
 *    same reason.
 *
 * 3. **Every figure has words in it.** [writeFigure] fails on a figure that came out captionless.
 *    That is not a hypothetical: a `jvm-main` child JVM missing `-Djava.awt.headless=true` cannot
 *    read a glyph outline, and `PetriVisual.label` answers that by drawing the diagram without any
 *    names — so the gallery wrote twelve wordless, valid, plausible SVGs and exited 0.
 *
 * Writes nothing committed. Pass an output directory as `args[0]` to regenerate the SVG figures.
 */
object PetriGallery {

    /** One worked program: the source, and a claim about it that is checked rather than asserted in prose. */
    private class Example(
        val name: String,
        val blurb: String,
        val source: String,
        val check: (PetriNet) -> String,
    )

    /**
     * A worked program written in the **Kotlin DSL** rather than the notation, and deliberately not
     * about music.
     *
     * Both halves of that matter. A Petri net is a general model of concurrency, and a documentation
     * page made entirely of drum machines quietly suggests otherwise. And these are the cases the
     * notation cannot express: five philosophers built in a loop is a `for`, not a line of text —
     * which is the honest answer to "why are there two surfaces?".
     */
    private class DslExample(
        val name: String,
        val blurb: String,
        val build: () -> PetriNet,
        val check: (PetriNet) -> String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests(args.firstOrNull()?.let(::File))) { "petri gallery failed" }
    }

    /**
     * Every net the gallery builds, by name — the notation programs and the Kotlin-DSL ones.
     *
     * Public because it is the honest corpus for anything that wants to sweep "every net the
     * documentation actually shows a reader" rather than a handful chosen by whoever is writing the
     * sweep. `PetriDeadTransitionsSweepTest` uses it for exactly that: a semantic change to a public
     * analysis function has to be checked against the nets the callers really pass, and this is where
     * most of them live.
     */
    fun nets(): List<Pair<String, PetriNet>> =
        EXAMPLES.map { example ->
            example.name to (
                petriParse(example.source).net
                    ?: error("gallery example '${example.name}' does not parse")
                )
        } + DSL_EXAMPLES.map { it.name to it.build() }

    fun runTests(outputDir: File? = null): Boolean = try {
        val dir = outputDir ?: Files.createTempDirectory("petri-gallery-").toFile()
        dir.mkdirs()

        for (example in EXAMPLES) {
            val parsed = petriParse(example.source)
            check(parsed.errors.isEmpty()) {
                "example '${example.name}' does not parse: ${parsed.errors.joinToString("; ") { it.toString() }}"
            }
            check(parsed.warnings.isEmpty()) {
                "example '${example.name}' warns: ${parsed.warnings.joinToString("; ") { it.message }}"
            }
            val net = parsed.net!!
            val finding = example.check(net)
            writeFigure(dir, example.name, net)
            File(dir, "${example.name}.petri").writeText(example.source.trimIndent().trim() + "\n")
            println("  ${example.name.padEnd(22)} $finding")
        }

        for (example in DSL_EXAMPLES) {
            val net = example.build()
            val finding = example.check(net)
            writeFigure(dir, example.name, net)
            println("  ${example.name.padEnd(22)} $finding")
        }

        checkStateFigures(dir)
        checkCheatsheetCoverage()
        println("petri gallery passed (${EXAMPLES.size + DSL_EXAMPLES.size} worked examples)")
        if (outputDir != null) println("wrote figures to ${dir.absolutePath}")
        true
    } catch (t: Throwable) {
        t.printStackTrace()
        false
    }

    // ── the worked programs ───────────────────────────────────────────────────────────────

    private val EXAMPLES: List<Example> = listOf(
        Example(
            name = "two-place-loop",
            blurb = "The smallest net that plays anything: one token, two transitions, alternating.",
            source = """
                #petri
                !timing step
                !grid 1/8
                !conflict order

                kick : 1
                kick -[ hit  @1/8 : bd ]-> hats
                hats -[ back @1/8 : sd ]-> kick
            """,
        ) { net ->
            val sounds = net.emits().events(PatArc(PatQ.ZERO, PatQ.ONE))
                .filter { it.hasOnset }.sortedBy { it.arc.start }.map { it.value }
            check(sounds == List(4) { listOf("bd", "sd") }.flatten()) { "expected bd/sd alternating, got $sounds" }
            check(net.period() == cyc(1, 4)) { "expected a quarter-cycle period, got ${net.period()}" }
            "period ${net.period()} cycles, plays ${sounds.take(4).joinToString(" ")} …"
        },

        Example(
            name = "polyrhythm",
            blurb = "Five against three. Nothing in the source says fifteen; the net works it out.",
            source = """
                #petri
                !grid 1/15

                a0 : 1
                a0 -[ a0.go : x ]-> a1
                a1 -[ a1.go     ]-> a2
                a2 -[ a2.go : x ]-> a3
                a3 -[ a3.go     ]-> a4
                a4 -[ a4.go     ]-> a0

                b0 : 1
                b0 -[ b0.go : y ]-> b1
                b1 -[ b1.go     ]-> b2
                b2 -[ b2.go : y ]-> b0
            """,
        ) { net ->
            val lasso = net.settle()!!
            check(lasso.loopSteps == 15) { "five against three should settle in fifteen steps, not ${lasso.loopSteps}" }
            check(net.tInvariants().size == 2) { "two independent rings should be two separate T-invariants" }
            "settles in ${lasso.loopSteps} steps = ${lasso.duration} cycles, ${net.tInvariants().size} invariants"
        },

        Example(
            name = "weights-and-tests",
            blurb = "An arc weight, an inhibitor arc and a read arc, doing the three different things they do.",
            source = """
                #petri
                !grid 1/8
                !conflict priority

                clock : 1
                hats  : 0
                armed : 1
                fill  : 0 ^2

                -[ tick @1/8 pri=9 : hh ]-> hats
                2*hats + &clock + ~armed -[ roll @1/8 pri=1 w=2.5 : sd ]-> fill
                armed -[ disarm @1/8 pri=5 ]-> spent
            """,
        ) { net ->
            val run = net.run()
            run.steps(12)
            check(run.markingAt(cyc(2)).count("clock") == 1) { "a read arc must not consume the clock token" }
            val rolls = run.steps(12).flatMap { it.firings }.count { it.transition == "roll" }
            check(rolls > 0) { "'roll' never fired, so the inhibitor never released" }
            check(net.place("fill")!!.capacity == 2)
            "the clock token survives ${run.materialised} steps; roll fired $rolls times into a capacity-2 place"
        },

        Example(
            name = "sections",
            blurb = "Places holding patterns: a slow net whose tokens are rhythms is an arrangement.",
            source = """
                #petri
                !timing timed

                verse  : "bd ~ sd ~"
                chorus : 'placeholder'
                energy : #1, #2

                verse -[ play-verse  @2 => carry ]-> chorus
                chorus -[ play-chorus @2 => rev  ]-> verse
                energy -[ lift @1 => inc ]-> lifted
                lifted -[ tally @1 => count ]-> counted
            """,
        ) { net ->
            val notes = net.pattern().unfold<String>()
            val first = notes.events(PatArc(PatQ.ZERO, PatQ.ONE))
                .filter { it.hasOnset }.sortedBy { it.arc.start }.map { it.value }
            check(first == listOf("bd", "sd")) { "the verse should be sounding, not $first" }
            check(net.timing == PetriTiming.TIMED)
            "the verse sounds ${first.joinToString(" ")} while the section token travels"
        },

        Example(
            name = "colour-algebra",
            blurb = "Transitions that combine the rhythms they consume, rather than merely moving them.",
            source = """
                #petri
                !timing timed
                !conflict round-robin
                !seed 3

                left  : "bd bd"
                right : "sd ~ sd ~"
                slow  : "hh*4"
                notes : #60, #64

                left + right -[ merge   @1 => stack   ]-> both
                both -[ alternate @1 => cat      ]-> pair
                pair -[ squeeze   @1 => fastcat  ]-> tight
                slow -[ half      @1 => slow2    ]-> lazy
                lazy -[ double    @1 => fast2    ]-> busy
                busy -[ mirror    @1 => palindrome ]-> mirrored
                mirrored -[ thin  @1 => degrade  ]-> thinned
                thinned -[ forget @1 => black    ]-> plain
                notes -[ total    @1 => sum      ]-> totalled
                totalled -[ other @1 => swap     ]-> othered
            """,
        ) { net ->
            val run = net.run()
            val firings = run.steps(24).flatMap { it.firings }
            // Ask the firing what it produced rather than the place what it holds: under `timed`
            // the next transition consumes the token at the same instant it lands, so a
            // hand-off place is never observably occupied.
            val merged = firings.first { it.transition == "merge" }.produced.single().colour
            check(merged is PatPattern<*>) { "stack should have produced a pattern, got $merged" }
            check(run.halt != PetriHalt.DEADLOCK || run.materialised > 4) { "the chain stalled immediately" }
            val used = firings.map { it.transition }.distinct()
            "ran ${run.materialised} steps; ${used.size} of the ten colour transforms fired"
        },

        Example(
            name = "deadlock",
            blurb = "A net that wedges — found before it is heard, not during the set.",
            source = """
                #petri
                !grid 1/4
                !conflict random
                !seed 11

                armed : 1
                src   : 4
                ~armed + src -[ blocked @1/4 : bd ]-> dst
            """,
        ) { net ->
            check(net.willDeadlock()) { "this net should be reported as deadlocking" }
            check(net.deadTransitions() == listOf("blocked"))
            check(net.tInvariants().isEmpty())
            "willDeadlock() is true and 'blocked' never fires"
        },

        Example(
            name = "source-and-drain",
            blurb = "An empty side on either end: a transition can make tokens or eat them.",
            source = """
                #petri
                !timing event
                !grid 1/16

                pool : 0 ^3
                -[ spawn @1/16 : hh ]-> pool
                pool -[ sink @1/16 ]->
            """,
        ) { net ->
            val run = net.run()
            run.steps(8)
            check(net.transition("spawn")!!.inputs.isEmpty()) { "a source has no inputs" }
            check(net.transition("sink")!!.outputs.isEmpty()) { "a drain has no outputs" }
            check(run.materialised >= 4) { "the source/drain pair should keep going" }
            "a source and a drain kept a capacity-3 pool running for ${run.materialised} steps"
        },

        Example(
            name = "humanised",
            blurb = "delay= nudges a hit late without perturbing anything the net does.",
            source = """
                #petri
                !grid 1/8

                a : 1
                a -[ straight @1/8 : bd ]-> b
                b -[ late @1/8 delay=1/64 : sd ]-> a
            """,
        ) { net ->
            val late = net.run().steps(2).flatMap { it.firings }.first { it.transition == "late" }
            check(late.at == cyc(1, 8)) { "delay must not move when the net's logic fires" }
            check(late.arc.start == cyc(1, 8) + cyc(1, 64)) { "the event should start a sixty-fourth late" }
            "fires at ${late.at} but sounds at ${late.arc.start}"
        },
    )

    // ── the non-musical programs ──────────────────────────────────────────────────────────

    /**
     * The dining philosophers, both broken and fixed.
     *
     * [rightHandedLast] is the classic asymmetry fix: everyone reaches for their left fork first
     * except the last philosopher, who reaches right. Without it every philosopher grabs their left
     * fork in the same maximal step, every fork is gone, and nobody can ever pick up a second one.
     */
    private fun philosophers(
        count: Int = 5,
        rightHandedLast: Boolean = false,
        fair: Boolean = false,
    ): PetriNet = petri {
        conflict = if (fair) PetriConflict.ROUND_ROBIN else PetriConflict.ORDER
        grid = cyc(1, 4)
        for (index in 0 until count) place("fork.$index", tokens = 1)
        for (index in 0 until count) {
            val left = index
            val right = (index + 1) % count
            // The reversed philosopher grabs the fork on their right first, and waits for the left.
            val reversed = rightHandedLast && index == count - 1
            val first = if (reversed) right else left
            val second = if (reversed) left else right
            t("grab.$index") { from("fork.$first"); to("holding.$index") }
            t("eat.$index") { from("holding.$index"); from("fork.$second"); to("eating.$index") }
            t("done.$index") { from("eating.$index"); to("fork.$left"); to("fork.$right") }
        }
    }

    /** A producer and a consumer sharing a bounded buffer — the other textbook concurrency net. */
    private fun boundedBuffer(slots: Int = 4): PetriNet = petri {
        grid = cyc(1, 8)
        place("free", tokens = slots)
        place("items", capacity = slots)
        place("producer", tokens = 1)
        place("consumer", tokens = 1)
        // Each worker holds a token that it puts straight back: that is what makes it *one* worker
        // rather than an unbounded supply of them.
        t("produce") { from("producer"); from("free"); to("items"); to("producer") }
        t("consume") { from("consumer"); from("items"); to("free"); to("consumer") }
    }

    private val DSL_EXAMPLES: List<DslExample> = listOf(
        DslExample(
            name = "philosophers-deadlock",
            blurb = "Five philosophers, all left-handed. Everyone grabs a fork; nobody eats.",
            build = { philosophers(5) },
        ) { net ->
            check(net.willDeadlock()) { "the naive philosophers should deadlock" }
            val stuck = net.deadlockMarking()!!
            check((0 until 5).all { stuck.count("fork.$it") == 0 }) { "every fork should be held: $stuck" }
            check((0 until 5).all { stuck.count("holding.$it") == 1 }) { "everyone holds exactly one: $stuck" }
            check(net.deadTransitions().any { it.startsWith("eat.") }) { "nobody should get to eat" }
            "deadlocks with all 5 forks held and nobody eating"
        },

        DslExample(
            name = "philosophers-starved",
            blurb = "The asymmetry fixes the deadlock — and uncovers a starvation underneath it.",
            build = { philosophers(5, rightHandedLast = true) },
        ) { net ->
            check(!net.willDeadlock()) { "the asymmetric philosophers should not deadlock" }
            // But `!conflict order` means the first-declared claimant always wins the contested
            // fork, so the reversed philosopher never gets it. Not a deadlock — a starvation, and
            // the two need different fixes.
            val starved = net.deadTransitions()
            check(starved.isNotEmpty()) { "expected the reversed philosopher to starve under ORDER" }
            check(starved.all { it.endsWith(".4") }) { "only philosopher 4 should starve, got $starved" }
            "no deadlock, but philosopher 4 starves: ${starved.joinToString(", ")} never fire"
        },

        DslExample(
            name = "philosophers-fair",
            blurb = "Asymmetry stops the deadlock; a fair conflict policy stops the starvation.",
            build = { philosophers(5, rightHandedLast = true, fair = true) },
        ) { net ->
            check(!net.willDeadlock()) { "the fair table should not deadlock" }
            check(net.deadTransitions().isEmpty()) { "everyone should eventually eat: ${net.deadTransitions()}" }
            val period = net.period()
            "no deadlock, all ${net.transitions.size} transitions fire" +
                (period?.let { ", settles into a $it-cycle loop" } ?: "")
        },

        DslExample(
            name = "bounded-buffer",
            blurb = "Producer and consumer over a four-slot buffer; the invariants prove it cannot leak.",
            build = { boundedBuffer(4) },
        ) { net ->
            check(!net.willDeadlock()) { "producer/consumer should keep running" }
            val invariants = net.pInvariants()
            val conservation = invariants.firstOrNull { it.keys == setOf("items", "free") }
                ?: error("expected an items+free conservation law, got $invariants")
            check(conservation.values.all { it == 1 }) { "the buffer law should be items + free: $conservation" }
            check(invariants.any { it.keys == setOf("producer") }) { "the producer should be conserved: $invariants" }

            // And the law really holds along the trajectory, not just in the algebra.
            val run = net.run()
            run.steps(40)
            val totals = run.steps(40).map { it.after.count("items") + it.after.count("free") }.distinct()
            check(totals == listOf(4)) { "items + free drifted: $totals" }
            check(run.steps(40).all { it.after.count("items") <= 4 }) { "the buffer overflowed" }
            "${invariants.size} conservation laws; items + free stays ${totals.single()} across 40 steps"
        },
    )

    // ── the figures, and the guard that they have words in them ───────────────────────────

    private const val FIGURE_W = 900.0
    private const val FIGURE_H = 560.0

    /**
     * Write one figure, and refuse to have written a wordless one.
     *
     * **This is the guard, not the writing.** `PetriVisual.label` treats "no font in this JVM" as
     * "draw the diagram without names", on purpose — an anonymous diagram beats none. The cost is
     * that a JVM which cannot read glyph outlines writes twelve plausible, captionless, *wrong*
     * figures and exits 0, which is exactly what the `jvm-main` child did until
     * `requiredAgentJvmArgs` (agent_backdoor.kt) started passing `-Djava.awt.headless=true`:
     * `-XstartOnFirstThread` with non-headless AWT parks the macOS font manager on a run loop that
     * a windowless child never pumps. Nothing in the run said so — not the exit code, not stderr,
     * not the SVG, which is valid and pretty.
     *
     * So the figure is compared against the same drawing rendered with `labels = false`. Those two
     * differ by exactly the glyph geometry, which makes the check self-calibrating: no glyph counts,
     * no path-element names, nothing that depends on how Skiko chooses to batch paints. When the
     * fonts are gone they are byte-identical.
     */
    private fun writeFigure(dir: File, name: String, net: PetriNet) {
        val drawing = net.frame()
        val svg = drawing.toSvg(FIGURE_W, FIGURE_H)
        File(dir, "$name.svg").writeText(svg)

        // Big nets are anonymous by design — 30 names in one picture read as a grey haze, and
        // `toFLines` drops them whatever the caller asked for. Ask the same question it does.
        val nodes = net.places.size + net.transitions.size
        if (nodes > PETRI_LABEL_LIMIT) return

        val wordless = drawing.toSvg(FIGURE_W, FIGURE_H, labels = false)
        check(svg != wordless) {
            "'$name.svg' came out with no text in it: $nodes labelled nodes, and the figure is " +
                "byte-identical to the same drawing rendered with labels = false. " +
                (if (petriLabelsRenderable()) {
                    "This JVM can read glyph outlines, so the labels are being dropped somewhere in " +
                        "PetriDrawing.toFLines rather than by a missing font."
                } else {
                    "This JVM cannot read glyph outlines at all. If it was forked (the backdoor's " +
                        "run-jvm-main path), it needs -Djava.awt.headless=true on the command line: " +
                        "-XstartOnFirstThread plus non-headless AWT parks CFontManager.loadFonts on a " +
                        "run loop nothing here pumps. See Typefaces.warm and requiredAgentJvmArgs."
                })
        }
    }

    // ── the state-space figures, and the node bound ───────────────────────────────────────

    /**
     * One state-space figure: a net, the size it is drawn at, and whether the drawing is expected to
     * refuse it.
     *
     * The three of them are the three regimes, and they are here rather than in a unit test because
     * they go through the same call the boxes and the docs page do — [afield.petri.toSvg], which is
     * [afield.petri.toFLines] — so a change that only breaks the *drawing* rather than the numbers
     * still fails something.
     */
    private class StateFigure(
        val name: String,
        val blurb: String,
        val build: () -> PetriNet,
        val w: Double,
        val h: Double,
        val declined: Boolean,
    )

    /** Five philosophers is 82 markings; nine is 2 786. The same net at two sizes is the whole test. */
    private fun philosophers(n: Int) = petri {
        conflict = PetriConflict.ORDER
        grid = cyc(1, 4)
        for (i in 0 until n) place("fork.$i", tokens = 1)
        for (i in 0 until n) {
            val left = i
            val right = (i + 1) % n
            t("grab.$i") { from("fork.$left"); to("holding.$i") }
            t("eat.$i") { from("holding.$i"); from("fork.$right"); to("eating.$i") }
            t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
        }
    }

    private val STATE_FIGURES: List<StateFigure> = listOf(
        StateFigure(
            name = "state-graph-small",
            blurb = "the bounded buffer — small enough that every marking is a dot you can point at",
            build = {
                petri {
                    grid = cyc(1, 8)
                    place("free", tokens = 4)
                    place("items", capacity = 4)
                    place("producer", tokens = 1)
                    place("consumer", tokens = 1)
                    t("produce") { from("producer"); from("free"); to("items"); to("producer") }
                    t("consume") { from("consumer"); from("items"); to("free"); to("consumer") }
                }
            },
            w = 900.0,
            h = 320.0,
            declined = false,
        ),
        StateFigure(
            name = "state-graph-dense",
            blurb = "five philosophers, 82 markings — dense, and the case the limit is tuned to allow",
            build = { philosophers(5) },
            w = 1100.0,
            h = 640.0,
            declined = false,
        ),
        StateFigure(
            name = "state-graph-declined",
            blurb = "nine philosophers, 2 786 markings — declined, and it says so on the figure",
            build = { philosophers(9) },
            w = 1100.0,
            h = 640.0,
            declined = true,
        ),
    )

    /**
     * Draws all three and checks the node bound did what it claims, in both directions.
     *
     * The assertions are deliberately *counterfactual with a number on them* rather than "the two
     * differ": under the limit the drawing has to carry at least one FLine per marking, over it at
     * most a tenth of that — and, separately, **at least twenty FLines and one arrow per firing of
     * the counterexample**, because an empty picture would otherwise satisfy "far fewer than 2 786"
     * perfectly and a blank SVG is the failure mode a node bound is most likely to introduce.
     */
    private fun checkStateFigures(dir: File) {
        for (figure in STATE_FIGURES) {
            val net = figure.build()
            val graph = net.stateSpace()
            check(graph.complete) { "${figure.name}: the search did not finish, so the figure is of a prefix" }
            check(graph.tooDenseToDraw() == figure.declined) {
                "${figure.name}: ${graph.stateCount} markings against a limit of $PETRI_STATE_DRAW_LIMIT, " +
                    "expected declined=${figure.declined}"
            }

            val drawn = graph.toFLines(w = figure.w, h = figure.h)
            check(drawn.isNotEmpty()) { "${figure.name}: nothing was drawn at all" }
            if (figure.declined) {
                check(drawn.size >= 20) { "${figure.name}: only ${drawn.size} FLines — the summary is blank" }
                check(drawn.size * 10 < graph.stateCount) {
                    "${figure.name}: ${drawn.size} FLines for ${graph.stateCount} markings — it drew the hairball"
                }
                val trace = graph.deadlockTrace()
                check(trace != null && trace.length >= 9) { "${figure.name}: no counterexample to show" }
                check(graph.summaryHtml().contains("drawing declined")) {
                    "${figure.name}: the card does not admit the drawing was declined"
                }
            } else {
                check(drawn.size >= graph.stateCount) {
                    "${figure.name}: ${drawn.size} FLines for ${graph.stateCount} markings — a dot each is the minimum"
                }
                check(!graph.summaryHtml().contains("drawing declined")) {
                    "${figure.name}: the card claims a refusal that did not happen"
                }
            }

            File(dir, "${figure.name}.svg").writeText(graph.toSvg(w = figure.w, h = figure.h))
            println(
                "  ${figure.name.padEnd(22)} ${graph.stateCount} markings, ${drawn.size} FLines — ${figure.blurb}"
            )
        }
    }

    // ── cheatsheet coverage guard ─────────────────────────────────────────────────────────

    /**
     * Every operator documented in `petri_cheatsheet.md` must appear in at least one worked
     * program: an example above, or a `#petri` block on the examples page.
     *
     * The `Kotlin entry points` section is API surface rather than notation, so it is excluded —
     * those are covered by the unit tests and by `petri.smoke`. Silently skips when the docs are
     * absent, which is what a partial checkout looks like.
     */
    private fun checkCheatsheetCoverage() {
        val root = File(System.getProperty("user.dir"))
        val cheatsheet = File(root, "public/docs/petri_cheatsheet.md").takeIf { it.exists() } ?: return
        val examplesPage = File(root, "public/docs/petri_nets.md")

        val operators = cheatsheetOperators(cheatsheet.readText())
        check(operators.size > 40) { "cheatsheet operator scrape looks wrong: only ${operators.size} found" }

        val corpus = buildString {
            EXAMPLES.forEach { append(it.source).append('\n') }
            if (examplesPage.exists()) petriBlocks(examplesPage.readText()).forEach { append(it).append('\n') }
        }

        val missing = operators.filterNot { operator -> demonstrates(corpus, operator) }
        check(missing.isEmpty()) {
            "these cheatsheet operators have no worked example (add one to PetriGallery.EXAMPLES or to " +
                "petri_nets.md): ${missing.joinToString(", ")}"
        }

        // The combinator table and the Kotlin registry must also agree, in both directions: a word
        // in one and not the other is either an undocumented feature or a documented fiction.
        val documented = cheatsheetSection(cheatsheet.readText(), "Combinators")
        val inKotlin = PetriCombinators.names.toSet()
        check(documented == inKotlin) {
            "cheatsheet and PetriCombinators disagree: only documented ${documented - inKotlin}, " +
                "only implemented ${inKotlin - documented}"
        }
        println("  cheatsheet coverage: all ${operators.size} operators demonstrated, ${inKotlin.size} combinators documented")
    }

    /**
     * Whether [corpus] demonstrates [operator].
     *
     * Word-shaped operators must appear as whole words, so `cat` is not satisfied by `fastcat`.
     * Symbol-shaped ones are substrings, because `@` and `*` have no word boundaries to speak of.
     */
    private fun demonstrates(corpus: String, operator: String): Boolean {
        val wordShaped = operator.all { it.isLetterOrDigit() || it == '-' || it == '!' }
        if (!wordShaped) return operator in corpus
        return Regex("(?<![A-Za-z0-9_-])" + Regex.escape(operator) + "(?![A-Za-z0-9_-])").containsMatchIn(corpus)
    }

    /** First-column backticked tokens of every operator table, excluding the API section. */
    private fun cheatsheetOperators(text: String): List<String> {
        val skip = setOf("Kotlin entry points")
        val out = LinkedHashSet<String>()
        var section: String? = null
        for (line in text.lineSequence()) {
            Regex("^##+\\s+(.*)").find(line)?.let { section = it.groupValues[1].trim() }
            if (section == null || section in skip) continue
            val cell = Regex("^\\|\\s*`([^`]+)`\\s*\\|").find(line) ?: continue
            // `"…"` and `'…'` are shapes rather than tokens; demonstrate the quote character.
            out += when (val token = cell.groupValues[1]) {
                "\"…\"" -> "\""
                "'…'" -> "'"
                else -> token
            }
        }
        return out.toList()
    }

    /** The backticked first-column tokens of one named section. */
    private fun cheatsheetSection(text: String, wanted: String): Set<String> {
        val out = LinkedHashSet<String>()
        var section: String? = null
        for (line in text.lineSequence()) {
            Regex("^##+\\s+(.*)").find(line)?.let { section = it.groupValues[1].trim() }
            if (section != wanted) continue
            Regex("^\\|\\s*`([^`]+)`\\s*\\|").find(line)?.let { out += it.groupValues[1] }
        }
        return out
    }

    /** Every `#petri` program inside a fenced code block on a docs page. */
    private fun petriBlocks(markdown: String): List<String> {
        val out = mutableListOf<String>()
        var inFence = false
        var current = StringBuilder()
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) {
                if (inFence) {
                    if ("#petri" in current) out += current.toString()
                    current = StringBuilder()
                }
                inFence = !inFence
                continue
            }
            if (inFence) current.append(line).append('\n')
        }
        return out
    }
}
