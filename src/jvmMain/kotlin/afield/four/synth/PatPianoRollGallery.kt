@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import java.io.File
import java.util.Locale
import kotlin.math.max

/*
FIELD_TEST
id: synth.pat.gallery
runner: jvm-main
entrypoint: afield.four.synth.PatPianoRollGallery
tags: [smoke, synth, pattern, tidal, plot, svg, capture, cpu]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "pat piano-roll gallery rendered"
*/

/**
 * Headless **piano-roll gallery** for the pattern language, drawing the operators defined across
 * `TidalPatterns.kt`, `PatSignals.kt`, `PatAlgebra.kt` and `PatComposition.kt`.
 *
 * Output is **SVG**, not raster. A piano roll is line art (rectangles, gridlines, a parameter
 * polyline), so it is drawn as vector geometry, built here as plain strings. That also makes it
 * diffable: a changed operator shows up as a changed rectangle in the file rather than as a
 * changed PNG blob. There is no AWT, no browser, no GPU, no Field window.
 *
 * Every rectangle is a **scheduled event**. Each lane queries its pattern over the roll's cycles
 * through `PatPattern.events(...)`, and nothing is hand-placed; the only parts chosen by hand are
 * the colours and the mapping from cycle time to pixels.
 *
 * #### Lanes, and how a roll is read
 *
 * A roll is a stack of horizontal lanes drawn at one shared time scale, so reading down a column
 * compares the same instant across all of them. Most rolls are built as a source lane with its
 * transforms underneath: `pat("a b c d")` on top, then `rev()`, `euclid(3,8)`, `iter(4)` and the
 * rest below it. Where a form is shorthand for a longhand spelling, the two are lanes of the same
 * roll and the check compares them event for event.
 *
 * #### What each roll is checked against
 *
 * As a FIELD_TEST (`synth.pat.gallery`) every roll is rendered to a temp dir and asserted: each
 * lane draws at least one event, no event falls outside the window the roll plots, and the SVG
 * holds exactly one rectangle per event plus one backdrop per lane plus one page background, so
 * the figure can neither invent a note nor drop one. Each roll then runs its own `verify` over the
 * events it actually drew, which is where the musical claim lives: that `rev` reverses, that
 * `euclid(3,8)` puts three onsets on the eighth grid with gaps as even as 8/3 allows, that
 * `iter(4)` rotates by a step per cycle, that `shuffle(4)` is a permutation every cycle, and that
 * a continuous parameter takes the value of its curve at each note's own midpoint. It writes no
 * committed assets during the test. Pass an output dir as `args[0]` (or set `PAT_ROLL_DIR`) to
 * regenerate the committed captures under `dev_docs/assets/pat_gallery/`.
 *
 * Add a roll by appending one `Roll` to `ROLLS`.
 */

/**
 * The catalogue of piano rolls, the SVG renderer behind them, and the FIELD_TEST entry point.
 */
object PatPianoRollGallery {

    // ── the roll model ─────────────────────────────────────────────────────────────────────

    /** One horizontal band of a roll: a pattern, rendered over [PatRollSpec.cycles] cycles. */
    class Lane(
        val label: String,
        val pattern: PatPattern<String>,
        /** When set, each event's rectangle height and a polyline encode this parameter. */
        val paramKey: String? = null,
        /** Colour override; defaults to the lane's position in the roll. */
        val color: String? = null,
        /**
         * Label the value axis as MIDI pitch. Layout is chosen automatically: a lane whose atoms
         * all parse as numbers is drawn on a value axis, and anything else gets one row per
         * distinct atom.
         */
        val pitched: Boolean = false
    )

    class PatRollSpec(
        val title: String,
        val subtitle: String,
        val cycles: Int,
        val lanes: List<Lane>
    )

    /** A named roll, plus a check on the events it drew. */
    class Roll(
        val name: String,
        val blurb: String,
        val build: () -> PatRollSpec,
        val verify: (List<LaneEvents>) -> Unit = {}
    )

    /** What a lane actually resolved to — handed to [Roll.verify] so checks read real events. */
    class LaneEvents(val label: String, val events: List<PatEvent<String>>, val cycles: Int) {
        /** Events of cycle [index], sorted, with arcs made relative to that cycle's start. */
        fun cycle(index: Long): List<PatEvent<String>> {
            val base = PatQ.of(index)
            return events.filter { qFloor(it.arc.start) == index }
                .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
                .map { PatEvent(PatArc(it.arc.start - base, it.arc.end - base), it.value, it.sourceSpan, it.annotations) }
        }
    }

    // ── palette (shared with the rest of the dark-mode docs figures) ────────────────────────

    private const val BG = "#14161c"
    private const val GRID = "#2a2f3a"
    private const val GRID_STRONG = "#454d5e"
    private const val TEXT = "#c9d1e0"
    private const val TEXT_DIM = "#7d879b"
    private val LANE_COLORS = listOf("#4fc3f7", "#ffb74d", "#ba68c8", "#81c784", "#f06292", "#fff176")

    /**
     * Colour per **instrument**, rather than per lane.
     *
     * The rule is that colour tracks what you *hear*. These are exactly the tokens [patDrumHz]
     * gives a voice of its own, ordered coolest-for-lowest through brightest-for-highest, and the
     * aliases share a colour because they share a pitch. `synth.pat.mini` asserts that
     * correspondence against the drum map instead of trusting this paragraph, so a token that
     * stops being audibly distinct stops being visually distinct in the same commit.
     *
     * A token the drum map has no voice for has no instrument identity to encode, so it falls back
     * to its lane's colour and those figures read exactly as they did before. That is why this is a
     * map with five entries and not a hash of the atom string: a colour here is a claim about
     * sound, and `a`, `b`, `x` and `1 2 3` make no such claim.
     */
    val ATOM_COLORS: Map<String, String> = mapOf(
        "bd" to "#4fc3f7", "kick" to "#4fc3f7",   // 62 Hz sine — the lowest voice in the map
        "sd" to "#ffb74d", "snare" to "#ffb74d",  // 190 Hz noise
        "cp" to "#f06292", "clap" to "#f06292",   // 900 Hz noise
        "oh" to "#81c784",                        // 4200 Hz noise
        "hh" to "#fff176", "hat" to "#fff176",    // 6200 Hz noise — the brightest
    )

    /**
     * The colour for one event's atom, or null when the drum map has no voice of its own for it.
     *
     * The `:n` sample-index suffix is dropped first, for the same reason [patDrumHz] drops it:
     * `bd:3` is a kick, so it is kick-coloured.
     */
    fun atomColor(atom: String): String? = ATOM_COLORS[atom.substringBefore(':').lowercase()]

    /**
     * A lane's distinct atoms, ordered **high pitch at the top** — the axis a piano roll has.
     *
     * The first draft assigned rows in order of first appearance, which puts an atom's height at
     * the mercy of where it happens to be written: `bd sd . hh hh hh` drew the kick above the hats
     * and `hh hh hh . bd sd` would have drawn it below, for the same music. Sorting by the pitch
     * the voice will actually play — MIDI numbers through [patMidiToHz], named drums through
     * [patDrumHz] — means an instrument sits at the same height in every figure on the page, so
     * vertical position carries information instead of noise. Paired with [ATOM_COLORS] it also
     * gives each lane two independent cues for the same fact.
     *
     * The sort is stable, so atoms the drum map has no voice for keep their order of appearance
     * among themselves rather than being shuffled by an arbitrary tiebreak.
     */
    fun orderAtoms(atoms: List<String>): List<String> =
        atoms.sortedByDescending { atom ->
            val bare = atom.trim()
            bare.toDoubleOrNull()?.let { patMidiToHz(it) } ?: patDrumHz(bare, baseHz = 220.0).first
        }

    // ── the rolls ──────────────────────────────────────────────────────────────────────────

    val ROLLS: List<Roll> = listOf(

        // 1 ── continuous signals, and what segment does to them.
        Roll(
            name = "signals-segment",
            blurb = "The signal family discretised. Each lane is one continuous pattern read through " +
                "`segment(16)`; the polyline is the value it carried, the rectangle height the same " +
                "number. Sixteen events per lane, on the exact 1/16 grid.",
            build = {
                PatRollSpec(
                    title = "Continuous signals, sampled at each cell's centre",
                    subtitle = "sine() / tri() / saw() / square() / perlin() / rand(), each .segment(16)",
                    cycles = 2,
                    lanes = listOf(
                        Lane("sine()", sine().segment(16).texts(), paramKey = null),
                        Lane("tri()", tri().segment(16).texts()),
                        Lane("saw()", saw().segment(16).texts()),
                        Lane("square()", square().segment(16).texts()),
                        Lane("perlin()", perlin().segment(16).texts()),
                        Lane("rand()", rand().segment(16).texts()),
                    )
                )
            },
            verify = { lanes ->
                lanes.forEach { lane ->
                    check(lane.events.size == 16 * lane.cycles) {
                        "${lane.label}: expected ${16 * lane.cycles} events, drew ${lane.events.size}"
                    }
                    val first = lane.cycle(0)
                    check(first.size == 16) { "${lane.label}: cycle 0 has ${first.size} events" }
                    first.forEachIndexed { i, e ->
                        check(e.arc.start == PatQ.of(i, 16) && e.arc.end == PatQ.of(i + 1, 16)) {
                            "${lane.label}: event $i is not on the 1/16 grid: ${e.arc}"
                        }
                    }
                    check(first.all { it.value.toDoubleOrNull()?.let { v -> v in -0.001..1.001 } == true }) {
                        "${lane.label}: a sampled value left 0..1: ${first.map { it.value }}"
                    }
                }
                // sine must rise then fall; saw must rise monotonically; square must be two levels.
                fun values(label: String) = lanes.first { it.label == label }.cycle(0).map { it.value.toDouble() }
                val saws = values("saw()")
                check(saws.zipWithNext().all { (a, b) -> b > a }) { "saw() did not rise monotonically: $saws" }
                val squares = values("square()")
                check(squares.distinct().sorted() == listOf(0.0, 1.0)) { "square() is not two-valued: $squares" }
                check(squares.take(8).all { it == 0.0 } && squares.drop(8).all { it == 1.0 }) {
                    "square() did not switch at the half cycle: $squares"
                }
                val sines = values("sine()")
                check(sines.indexOf(sines.max()) in 3..4) { "sine() peaks in the wrong place: $sines" }
            }
        ),

        // 2 ── the counterfactual figure: structure choice.
        Roll(
            name = "structure-choice",
            blurb = "The same two patterns — `0 1` and `10 20 30` — combined three ways. Structure " +
                "from both gives the intersection grid (4 events); from the left, exactly the left's " +
                "2 events; from the right, exactly the right's 3. Before tonight only the first lane " +
                "of each pair was expressible, and only for string atoms via `zip`.",
            build = {
                val twos = pat("0 1").nums()
                val threes = pat("10 20 30").nums()
                PatRollSpec(
                    title = "Whose rhythm survives when two patterns meet",
                    subtitle = "pat(\"0 1\") and pat(\"10 20 30\") under |+| , |+ and +|",
                    cycles = 2,
                    lanes = listOf(
                        Lane("left: 0 1", twos.texts()),
                        Lane("right: 10 20 30", threes.texts()),
                        Lane("|+| both", twos.`|+|`(threes).texts()),
                        Lane("|+ left", twos.`|+`(threes).texts()),
                        Lane("+| right", twos.`+|`(threes).texts()),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label == label }
                check(lane("|+| both").cycle(0).size == 4) { "|+| should give 4 events" }
                check(lane("|+ left").cycle(0).size == 2) { "|+ should give 2 events" }
                check(lane("+| right").cycle(0).size == 3) { "+| should give 3 events" }
                check(lane("|+| both").cycle(0).map { it.arc.start } ==
                    listOf(PatQ.ZERO, PatQ.of(1, 3), PatQ.of(1, 2), PatQ.of(2, 3))) {
                    "|+| did not produce the intersection grid: ${lane("|+| both").cycle(0).map { it.arc }}"
                }
                check(lane("|+ left").cycle(0).map { it.value } == listOf("10", "21")) {
                    "|+ latched the wrong values: ${lane("|+ left").cycle(0).map { it.value }}"
                }
                check(lane("+| right").cycle(0).map { it.value } == listOf("10", "20", "31")) {
                    "+| latched the wrong values: ${lane("+| right").cycle(0).map { it.value }}"
                }
            }
        ),

        // 3 ── the parameter counterfactual: four kicks, a sixteen-step sweep.
        Roll(
            name = "continuous-parameter",
            blurb = "A filter curve and three note densities. Lane 1 is the whole of what the old " +
                "surface could do: a cutoff staircase typed out by hand, one literal per note, which " +
                "you must retype from scratch to change either the curve or the note count. Lane 2 " +
                "is `appBoth` against the same curve read at 16 steps — the wrong answer, because it " +
                "fragments four kicks into sixteen. Lanes 3 to 5 are `with(cutoff(curve))` over 4, 8 " +
                "and 16 notes: the SAME curve, sampled at whatever density the rhythm happens to be, " +
                "with the note count untouched. A sine on four quarter-cells is symmetric, so lane 3 " +
                "aliases down to two levels and the curve only resolves as the rhythm gets finer — " +
                "which is what sampling a function, rather than typing a staircase, means.",
            build = {
                // Non-monotone on purpose: a staircase of four hand-typed literals cannot be this.
                val curve = sine().rangex(200, 4000)
                PatRollSpec(
                    title = "A continuous parameter, without multiplying the notes",
                    subtitle = "sine().rangex(200, 4000) applied to bd*4, bd*8 and bd*16",
                    cycles = 2,
                    lanes = listOf(
                        Lane("old: zip(\"200 1000 2000 4000\")", pat("bd*4").zip(pat("200 1000 2000 4000"), "cutoff"), paramKey = "cutoff"),
                        Lane("appBoth: 16 fragments", pat("bd*4").appBoth(curve.segment(16)) { atom, _ -> atom }),
                        Lane("bd*4 with cutoff(curve)", pat("bd*4") with cutoff(curve), paramKey = "cutoff"),
                        Lane("bd*8 with cutoff(curve)", pat("bd*8") with cutoff(curve), paramKey = "cutoff"),
                        Lane("bd*16 with cutoff(curve)", pat("bd*16") with cutoff(curve), paramKey = "cutoff"),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label.startsWith(label) }
                check(lane("old").cycle(0).size == 4) { "the old zip surface should give 4 events" }
                check(lane("appBoth").cycle(0).size == 16) { "appBoth should fragment into 16" }

                val typed = setOf(200.0, 1000.0, 2000.0, 4000.0)
                // The claim being checked is exactly the documented one: each note's parameter is
                // the curve evaluated at that note's own midpoint. That is checkable analytically,
                // so it is — at all three densities, with no eyeballed shape and no remembered number.
                fun analyticCutoff(midpoint: Double): Double {
                    val unit = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * midpoint)
                    return 200.0 * Math.pow(4000.0 / 200.0, unit)
                }
                for (n in listOf(4, 8, 16)) {
                    val notes = lane("bd*$n with").cycle(0)
                    check(notes.size == n) { "bd*$n with cutoff must keep $n events, drew ${notes.size}" }
                    check(notes.map { it.arc.start } == (0 until n).map { PatQ.of(it, n) }) {
                        "bd*$n: the surviving events are not the kicks: ${notes.map { it.arc }}"
                    }
                    notes.forEachIndexed { i, note ->
                        val expected = analyticCutoff((2.0 * i + 1.0) / (2.0 * n))
                        val got = note.num("cutoff")
                        check(Math.abs(got - expected) < 1e-5) {
                            "bd*$n note $i: cutoff $got is not the curve at its midpoint ($expected)"
                        }
                    }
                    check(notes.all { it.num("cutoff") in 200.0..4000.0 }) { "bd*$n: cutoff escaped its range" }
                    check(notes.none { it.num("cutoff") in typed }) { "bd*$n: the curve hit the typed staircase" }
                }
                // A sine sampled on four quarter-cells is symmetric, so bd*4 legitimately sees only
                // two distinct values; the curve resolves as the rhythm gets finer. That is the
                // figure's point, so assert it rather than let a coarse lane read as a bug.
                fun distinct(n: Int) = lane("bd*$n with").cycle(0).map { it.num("cutoff") }.distinct().size
                check(distinct(4) == 2) { "bd*4 on a sine should alias to two values, saw ${distinct(4)}" }
                check(distinct(8) >= 4) { "bd*8 should resolve more of the curve, saw ${distinct(8)}" }
                check(distinct(16) >= 8) { "bd*16 should resolve the curve, saw ${distinct(16)}" }
                val fine = lane("bd*16 with").cycle(0).map { it.num("cutoff") }
                check(fine.indexOf(fine.max()) in 3..4) { "bd*16: the curve peaks in the wrong place: $fine" }
            }
        ),

        // 4 ── gain following an LFO, note by note.
        Roll(
            name = "gain-lfo",
            blurb = "Sixteen hats with the gain following a sine, and the same hats with the gain " +
                "following the same sine an eighth of a cycle later via `off`. Rectangle height is " +
                "the gain; the polyline is the value each note actually latched.",
            build = {
                val hats = pat("hh*16")
                PatRollSpec(
                    title = "Amplitude read from a signal, one value per note",
                    subtitle = "pat(\"hh*16\") with gain(sine().range(0.15, 1.0))",
                    cycles = 2,
                    lanes = listOf(
                        Lane("with gain(sine())", hats with gain(sine().range(0.15, 1.0)), paramKey = "gain"),
                        Lane("with gain(tri())", hats with gain(tri().range(0.15, 1.0)), paramKey = "gain"),
                        Lane("with gain(perlin())", hats with gain(perlin().range(0.15, 1.0)), paramKey = "gain"),
                    )
                )
            },
            verify = { lanes ->
                lanes.forEach { lane ->
                    val first = lane.cycle(0)
                    check(first.size == 16) { "${lane.label}: expected 16 hats, got ${first.size}" }
                    val gains = first.map { it.num("gain") }
                    check(gains.all { it in 0.149..1.001 }) { "${lane.label}: gain out of range: $gains" }
                    check(gains.distinct().size >= 8) { "${lane.label}: gain barely varies: $gains" }
                }
                val sines = lanes.first { it.label.contains("sine") }.cycle(0).map { it.num("gain") }
                // A sine over one cycle: rises through the first quarter, dips through the third.
                check(sines.take(4).zipWithNext().all { (a, b) -> b > a }) { "sine gain did not rise: $sines" }
                check(sines.drop(8).take(4).zipWithNext().all { (a, b) -> b < a }) { "sine gain did not fall: $sines" }
            }
        ),

        // 5 ── rhythm transforms, each next to the untransformed original.
        Roll(
            name = "rhythm-transforms",
            blurb = "One source pattern and five transforms of it, drawn over four cycles so the " +
                "cycle-to-cycle ones are visible: `rev` mirrors, `euclid(3,8)` spaces three onsets " +
                "over eight slots, `iter(4)` advances a quarter each cycle, `linger(1/4)` holds the " +
                "head, `shuffle(4)` permutes, and `slow(2)` / `fast(2)` really do span cycles — read " +
                "the `slow(2)` lane across the bar lines and it goes `a b` then `c d`, where before " +
                "this branch it played `a b` forever and the second half was never heard at all.",
            build = {
                val source = pat("a b c d")
                PatRollSpec(
                    title = "Rhythm transforms, against the source",
                    subtitle = "pat(\"a b c d\") — four cycles",
                    cycles = 4,
                    lanes = listOf(
                        Lane("source", source),
                        Lane("rev()", source.rev()),
                        Lane("euclid(3,8)", struct(euclid(3, 8), source)),
                        Lane("iter(4)", source.iter(4)),
                        Lane("linger(1/4)", source.linger(1, 4)),
                        Lane("shuffle(4)", source.shuffle(4)),
                        Lane("slow(2)", source.slow(2)),
                        Lane("fast(2)", source.fast(2)),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label == label }
                check(lane("source").cycle(0).map { it.value } == listOf("a", "b", "c", "d"))
                check(lane("rev()").cycle(0).map { it.value } == listOf("d", "c", "b", "a")) {
                    "rev did not reverse: ${lane("rev()").cycle(0).map { it.value }}"
                }
                // Euclidean: three onsets on the eighth grid, and the gaps are as even as 8/3 allows.
                val euclidStarts = lane("euclid(3,8)").cycle(0).map { it.arc.start }
                check(euclidStarts.size == 3) { "euclid(3,8) drew ${euclidStarts.size} onsets" }
                val slots = euclidStarts.map { qFloorDiv(it, PatQ.of(1, 8)) }
                val gaps = slots.zipWithNext { a, b -> b - a } + (8 - slots.last() + slots.first())
                check(gaps.all { it in 2..3 }) { "euclid(3,8) gaps are not Euclidean: $gaps (slots $slots)" }
                // iter really rotates, cycle by cycle.
                check(lane("iter(4)").cycle(1).map { it.value } == listOf("b", "c", "d", "a")) {
                    "iter(4) cycle 1: ${lane("iter(4)").cycle(1).map { it.value }}"
                }
                check(lane("iter(4)").cycle(2).map { it.value } == listOf("c", "d", "a", "b"))
                check(lane("linger(1/4)").cycle(0).map { it.value } == listOf("a", "a", "a", "a"))
                // shuffle is a permutation every cycle, and at least one cycle is reordered.
                var reordered = false
                for (c in 0L until 4L) {
                    val v = lane("shuffle(4)").cycle(c).map { it.value }
                    check(v.sorted() == listOf("a", "b", "c", "d")) { "shuffle cycle $c is not a permutation: $v" }
                    if (v != listOf("a", "b", "c", "d")) reordered = true
                }
                check(reordered) { "shuffle(4) never reordered over four cycles" }
                // slow(2) spans cycles: the first half in even cycles, the second in odd ones. Before
                // this branch it played `a b` in every cycle and `c d` was never heard at all.
                check(lane("slow(2)").cycle(0).map { it.value } == listOf("a", "b")) {
                    "slow(2) cycle 0: ${lane("slow(2)").cycle(0).map { it.value }}"
                }
                check(lane("slow(2)").cycle(1).map { it.value } == listOf("c", "d")) {
                    "slow(2) cycle 1: ${lane("slow(2)").cycle(1).map { it.value }} — the second half is lost"
                }
                check(lane("fast(2)").cycle(0).map { it.value } == listOf("a", "b", "c", "d", "a", "b", "c", "d")) {
                    "fast(2): ${lane("fast(2)").cycle(0).map { it.value }}"
                }
            }
        ),

        // 6 ── the pitch surface: a scale, a progression, and voice leading.
        Roll(
            name = "voice-leading",
            blurb = "A pitched roll. The scale lane is eight degrees of C minor. Then the same " +
                "four-chord progression twice: as written, spelled from each chord's own root, and " +
                "voice-led. The as-written lane leaps around the keyboard; the voice-led lane moves " +
                "9 semitones in total instead of 83, with every chord's harmony unchanged.",
            build = {
                val progression = chords("c'maj7 a'min7 d'min7 g'dom7")
                PatRollSpec(
                    title = "Scales, chords, and voice leading",
                    subtitle = "run(8).scale(\"minor\") — chords(\"c'maj7 a'min7 d'min7 g'dom7\") as written vs .voiceLead()",
                    cycles = 2,
                    lanes = listOf(
                        Lane("scale(\"minor\")", run(8).scale("minor", root = 60).texts(), pitched = true),
                        Lane("as written", progression.notesOf().texts(), pitched = true),
                        Lane("voiceLead()", progression.voiceLead(center = 60).notesOf().texts(), pitched = true),
                        Lane("arpeggiate()", progression.voiceLead(center = 60).arpeggiate().texts(), pitched = true),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label.startsWith(label) }
                check(lane("scale").cycle(0).map { it.value.toDouble().toInt() } ==
                    listOf(60, 62, 63, 65, 67, 68, 70, 72)) {
                    "the minor scale is wrong: ${lane("scale").cycle(0).map { it.value }}"
                }
                val written = lane("as written").cycle(0)
                val led = lane("voiceLead").cycle(0)
                check(written.size == 16 && led.size == 16) { "both chord lanes should draw 16 notes" }

                fun chordsOf(events: List<PatEvent<String>>) = events
                    .groupBy { it.arc.start }
                    .toSortedMap(compareBy { it })
                    .values.map { group -> group.map { it.value.toDouble().toInt() }.sorted() }

                val writtenChords = chordsOf(written)
                val ledChords = chordsOf(led)
                val movedWritten = patTotalVoiceMovement(writtenChords)
                val movedLed = patTotalVoiceMovement(ledChords)
                println("  voice-leading roll: as written moves $movedWritten semitones, voice-led $movedLed")
                check(movedLed < movedWritten) { "voiceLead did not reduce movement: $movedLed vs $movedWritten" }
                writtenChords.zip(ledChords).forEach { (a, b) ->
                    check(a.map { Math.floorMod(it, 12) }.toSortedSet() == b.map { Math.floorMod(it, 12) }.toSortedSet()) {
                        "voiceLead changed the harmony: $a became $b"
                    }
                }
                // arpeggiate spreads the same notes in time: 16 notes, all on the 1/16 grid.
                val arped = lane("arpeggiate").cycle(0)
                check(arped.size == 16) { "arpeggiate drew ${arped.size} notes" }
                check(arped.map { it.arc.start } == (0 until 16).map { PatQ.of(it, 16) }) {
                    "arpeggiate is not on the 1/16 grid: ${arped.map { it.arc }}"
                }
            }
        ),

        // 7 ── arrangement: off, jux, and the mini-notation additions.
        Roll(
            name = "arrangement",
            blurb = "Arrangement operators over four cycles. `off(1/8)` adds a ghost displaced by an " +
                "exact eighth — a genuine time shift, so an echo whose arc crosses the cycle boundary " +
                "wraps into the next cycle rather than being folded back. `jux` splits the pattern " +
                "across the stereo field (the rectangle's vertical position is `pan`: top is left, " +
                "bottom is right). `bd*8?0.4` degrades deterministically, and differently each cycle. " +
                "`chop(2)` slices in place; `striateAcross(2)` interleaves.",
            build = {
                val source = pat("bd ~ sd ~")
                PatRollSpec(
                    title = "Arrangement, and the new mini-notation modifiers",
                    subtitle = "off / jux / ?0.4 / chop / striateAcross — four cycles",
                    cycles = 4,
                    lanes = listOf(
                        Lane("source", source),
                        Lane("off(1/8) { gain 0.4 }", source.off(1, 8) { it with gain(0.4) }, paramKey = "gain"),
                        Lane("jux { rev() }", source.jux { it.rev() }, paramKey = "pan"),
                        Lane("bd*8?0.4", pat("bd*8?0.4")),
                        Lane("chop(2)", pat("a b").chop(2)),
                        Lane("striateAcross(2)", pat("a b").striateAcross(2)),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label.startsWith(label) }
                check(lane("source").cycle(0).size == 2) { "source should be two onsets" }
                val off = lane("off(1/8)").cycle(0)
                check(off.size == 4) { "off should draw the two onsets plus two echoes, drew ${off.size}" }
                val echoes = off.filter { it.annotations.containsKey("gain") }
                check(echoes.size == 2) { "two of the four should be echoes: ${off.map { it.annotations }}" }
                check(echoes.map { it.arc.start } == listOf(PatQ.of(1, 8), PatQ.of(5, 8))) {
                    "the echoes are not an exact eighth late: ${echoes.map { it.arc }}"
                }
                check(echoes.all { it.num("gain") == 0.4 }) { "the echoes are not quieter" }
                val jux = lane("jux").cycle(0)
                check(jux.size == 4) { "jux should double the events, drew ${jux.size}" }
                check(jux.map { it.num("pan") }.toSet() == setOf(0.0, 1.0)) {
                    "jux did not hard-pan: ${jux.map { it.num("pan") }}"
                }
                // `?` degrades: fewer than 8 per cycle, not the same set every cycle, deterministic.
                val degraded = (0L until 4L).map { lane("bd*8?0.4").cycle(it).map { e -> e.arc.start } }
                check(degraded.all { it.size < 8 }) { "?0.4 dropped nothing: ${degraded.map { it.size }}" }
                check(degraded.all { it.isNotEmpty() }) { "?0.4 dropped everything: ${degraded.map { it.size }}" }
                check(degraded.distinct().size > 1) { "?0.4 dropped the same slots every cycle" }
                check(lane("chop(2)").cycle(0).map { it.value } == listOf("a", "a", "b", "b")) {
                    "chop should slice in place: ${lane("chop(2)").cycle(0).map { it.value }}"
                }
                check(lane("striateAcross(2)").cycle(0).map { it.value } == listOf("a", "b", "a", "b")) {
                    "striateAcross should interleave: ${lane("striateAcross(2)").cycle(0).map { it.value }}"
                }
            }
        ),

        // 8 ── groove and substitution: the second operator batch.
        Roll(
            name = "groove",
            blurb = "Groove and substitution. `swing(4)` pushes every off-beat exactly a third of an " +
                "eighth late — still exactly quantised, just not straight. `press()` moves every " +
                "event onto the second half of its own slot. `squeeze` replaces each event with a " +
                "whole pattern compressed into that event's arc, which is mini-notation nesting from " +
                "Kotlin values. `ncat` splits the cycle by weight. `euclidFull` fills the off-beats " +
                "too. `sometimesEventBy` transforms a random subset of the NOTES, where the existing " +
                "`sometimesBy` transforms whole cycles. (Eighth-note hats are a full eighth long, so " +
                "once the off-beats move the events overlap their neighbours — that is why the swung " +
                "lane reads as wide blocks with a bright seam rather than as separated bars. The " +
                "figure's own check asserts each onset's exact rational position.)",
            build = {
                PatRollSpec(
                    title = "Groove, substitution, and per-event probability",
                    subtitle = "swing / press / squeeze / ncat / euclidFull / sometimesEventBy",
                    cycles = 2,
                    lanes = listOf(
                        Lane("hh*8 straight", pat("hh*8")),
                        Lane("swing(4)", pat("hh*8").swing(4)),
                        Lane("press()", pat("a b c d").press()),
                        Lane("squeeze", pat("a b").squeeze { if (it == "a") pat("1 2 3") else pat("4 5") }),
                        Lane("ncat(a:1, b:2)", ncat(pat("a") to 1, pat("b") to 2)),
                        Lane("euclidFull(3,8)", euclidFull(3, 8, pat("bd"), pat("hh"))),
                        Lane("sometimesEventBy(0.4)", pat("x*8").sometimesEventBy(0.4) { it.fast(2) }),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label.startsWith(label) }

                // Swing is exact: the off-beats are a third of an eighth (1/24) late, no more.
                val straight = lane("hh*8 straight").cycle(0).map { it.arc.start }
                val swung = lane("swing(4)").cycle(0).map { it.arc.start }
                check(straight == (0 until 8).map { PatQ.of(it, 8) }) { "the straight lane moved: $straight" }
                check(swung.size == 8) { "swing dropped notes: ${swung.size}" }
                swung.forEachIndexed { i, start ->
                    val expected = if (i % 2 == 0) PatQ.of(i, 8) else PatQ.of(i, 8) + PatQ.of(1, 24)
                    check(start == expected) { "swing note $i is at $start, not $expected" }
                }

                check(arcsOfLane(lane("press()")) == listOf(
                    PatQ.of(1, 8) to PatQ.of(1, 4), PatQ.of(3, 8) to PatQ.of(1, 2),
                    PatQ.of(5, 8) to PatQ.of(3, 4), PatQ.of(7, 8) to PatQ.ONE
                )) { "press: ${arcsOfLane(lane("press()"))}" }

                val squeezed = lane("squeeze").cycle(0)
                check(squeezed.map { it.value } == listOf("1", "2", "3", "4", "5")) {
                    "squeeze: ${squeezed.map { it.value }}"
                }
                check(squeezed.first().arc.end == PatQ.of(1, 6)) { "squeeze did not compress: ${squeezed.first().arc}" }

                check(arcsOfLane(lane("ncat")) == listOf(PatQ.ZERO to PatQ.of(1, 3), PatQ.of(1, 3) to PatQ.ONE)) {
                    "ncat split in the wrong place: ${arcsOfLane(lane("ncat"))}"
                }

                val full = lane("euclidFull").cycle(0)
                check(full.size == 8) { "euclidFull drew ${full.size} of 8 steps" }
                check(full.count { it.value == "bd" } == 3 && full.count { it.value == "hh" } == 5) {
                    "euclidFull split wrong: ${full.map { it.value }}"
                }

                // Per-event, not per-cycle: some cycle must be partially transformed.
                val counts = (0L until 2L).map { lane("sometimesEventBy").cycle(it).size }
                check(counts.any { it in 9..15 }) { "sometimesEventBy looks all-or-nothing: $counts" }
            }
        ),

        // 9 ── mini-notation: the standalone step markers, each against its bracketed equivalent.
        Roll(
            name = "mini-notation",
            blurb = "Mini-notation. Each of the three new markers is drawn immediately above the " +
                "bracketed spelling it is shorthand for, so the pair must line up exactly: `.` groups " +
                "(`bd sd . hh hh hh` is `[bd sd] [hh hh hh]`, two halves rather than five fifths), `_` " +
                "extends the previous step (`bd _ _ sd` is `bd@3 sd`), and a bare `!` repeats it " +
                "(`bd ! ! sd` is `bd!3 sd`). Then the forms that were already there: two polymeter " +
                "spellings, `?` degrade, and a sample-index suffix.",
            build = {
                PatRollSpec(
                    title = "Mini-notation: the step markers, against their bracketed equivalents",
                    subtitle = "bd sd . hh hh hh  —  bd _ _ sd  —  bd ! ! sd  —  {a, b}  —  {a}%n  —  ?  —  :n",
                    cycles = 2,
                    lanes = listOf(
                        Lane("bd sd . hh hh hh", pat("bd sd . hh hh hh")),
                        Lane("[bd sd] [hh hh hh]", pat("[bd sd] [hh hh hh]")),
                        Lane("bd _ _ sd", pat("bd _ _ sd")),
                        Lane("bd@3 sd", pat("bd@3 sd")),
                        Lane("bd ! ! sd", pat("bd ! ! sd")),
                        Lane("bd!3 sd", pat("bd!3 sd")),
                        Lane("{bd sd stab, cp arpy cr}", pat("{bd sd stab, cp arpy cr}")),
                        Lane("{bd hh 808:4}%8", pat("{bd hh 808:4}%8")),
                        Lane("bd*8?0.4", pat("bd*8?0.4")),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label == label }
                // Each shorthand must be arc-for-arc and value-for-value its bracketed equivalent.
                val pairs = listOf(
                    "bd sd . hh hh hh" to "[bd sd] [hh hh hh]",
                    "bd _ _ sd" to "bd@3 sd",
                    "bd ! ! sd" to "bd!3 sd"
                )
                for ((shorthand, bracketed) in pairs) {
                    val a = lane(shorthand).cycle(0)
                    val b = lane(bracketed).cycle(0)
                    check(a.map { it.arc.start to it.arc.end } == b.map { it.arc.start to it.arc.end }) {
                        "`$shorthand` does not match `$bracketed`: ${a.map { it.arc }} vs ${b.map { it.arc }}"
                    }
                    check(a.map { it.value } == b.map { it.value }) {
                        "`$shorthand` plays ${a.map { it.value }}, `$bracketed` plays ${b.map { it.value }}"
                    }
                }
                // The `.` grouping really is two halves: the third event starts exactly at 1/2.
                check(lane("bd sd . hh hh hh").cycle(0)[2].arc.start == PatQ.of(1, 2)) {
                    "the dot did not split the cycle in half"
                }
                // Polymeter: two layers of different length, so more events than either alone.
                check(lane("{bd sd stab, cp arpy cr}").cycle(0).size == 6) {
                    "polymeter layers: ${lane("{bd sd stab, cp arpy cr}").cycle(0).size}"
                }
                check(lane("{bd hh 808:4}%8").cycle(0).size == 8) {
                    "explicit %8 subdivision: ${lane("{bd hh 808:4}%8").cycle(0).size}"
                }
                // The sample-index suffix arrives as an `n` parameter without changing the atom.
                val indexed = lane("{bd hh 808:4}%8").cycle(0).filter { it.value == "808:4" }
                check(indexed.isNotEmpty() && indexed.all { it.annotations["n"] == "4" }) {
                    "the :n suffix did not become an n parameter: ${indexed.map { it.annotations }}"
                }
                // `?` degrades, differently per cycle, deterministically.
                val degraded = (0L until 2L).map { lane("bd*8?0.4").cycle(it).size }
                check(degraded.all { it in 1..7 }) { "?0.4 over eight slots left $degraded" }
            }
        ),

        // 10 ── Kolakoski: the self-describing figure, its gap reading, and the property euclid
        // has not got.
        Roll(
            name = "kolakoski",
            blurb = "A Kolakoski rhythm reads the word `1 2 2 1 1 2 …` as alternating on- and " +
                "off-runs, so the figure's own run lengths spell the word back. Two things are " +
                "meant to be *looked at* here rather than taken on trust. **Prefix stability:** " +
                "read the first half of the `kolakoski(32)` lane against the whole of " +
                "`kolakoski(16)` and it is the same figure at half the width, because turning the " +
                "step count up extends a Kolakoski figure instead of re-placing it. The two " +
                "`euclid` lanes are the counter-example — `euclid(3,16)`'s first half is not " +
                "`euclid(3,8)`, because the step count is part of the question a Euclidean rhythm " +
                "answers. **The gap reading:** `(g16)` treats each term as the distance to the " +
                "next onset instead of a run length, which fills the long gaps in and leaves at " +
                "most one rest between hits. The last lane is the same construction over the " +
                "alphabet {2,3}, whose word has no run shorter than two.",
            build = {
                PatRollSpec(
                    title = "Kolakoski rhythms, against the Euclidean ones",
                    subtitle = "kolakoski(16) — kolakoski(32) — (g16) — euclid(3,8) — euclid(3,16) — {2,3}",
                    cycles = 2,
                    lanes = listOf(
                        Lane("kolakoski(16)", struct(kolakoski(16), pat("x"))),
                        Lane("kolakoski(32)", struct(kolakoski(32), pat("x"))),
                        Lane("kolakoski(16) gaps", struct(kolakoski(16, KolakoskiReading.GAPS), pat("x"))),
                        Lane("euclid(3,8)", struct(euclid(3, 8), pat("x"))),
                        Lane("euclid(3,16)", struct(euclid(3, 16), pat("x"))),
                        Lane("kolakoski(16) a=2 b=3", struct(kolakoski(16, a = 2, b = 3), pat("x"))),
                    )
                )
            },
            verify = { lanes ->
                fun lane(label: String) = lanes.first { it.label == label }
                fun slots(label: String, steps: Int): List<Long> =
                    lane(label).cycle(0).map { qFloorDiv(it.arc.start, PatQ.of(1, steps)) }

                // The figure the file overview prints: x..xx.x..x..xx.x
                check(slots("kolakoski(16)", 16) == listOf(0L, 3L, 4L, 6L, 9L, 12L, 13L, 15L)) {
                    "kolakoski(16) landed on ${slots("kolakoski(16)", 16)}"
                }
                // Prefix stability, as the eye is asked to read it: the 32-step figure's first
                // sixteen steps are the 16-step figure.
                check(slots("kolakoski(32)", 32).filter { it < 16 } == slots("kolakoski(16)", 16)) {
                    "the first half of kolakoski(32) is ${slots("kolakoski(32)", 32).filter { it < 16 }}"
                }
                // And the counter-example the euclid lanes are here to be: the same comparison
                // fails for a Euclidean rhythm.
                check(slots("euclid(3,16)", 16).filter { it < 8 } != slots("euclid(3,8)", 8)) {
                    "euclid turned out to be prefix-stable, so this roll's blurb is wrong"
                }
                // The gap reading is denser, and never leaves two rests in a row.
                val gaps = slots("kolakoski(16) gaps", 16)
                check(gaps.size > slots("kolakoski(16)", 16).size) { "the gap reading is not denser: $gaps" }
                check(gaps.zipWithNext { a, b -> b - a }.all { it <= 2 }) { "a gap left two rests: $gaps" }
                // {2,3} has no run shorter than two, so no onset is alone on its own.
                val pairs = slots("kolakoski(16) a=2 b=3", 16)
                check(pairs.zipWithNext { a, b -> b - a }.any { it == 1L }) { "no adjacent pair in $pairs" }
                check(pairs.size >= 6) { "{2,3} over sixteen steps left only ${pairs.size} onsets" }
            }
        ),

        // 11 ── patCondense: a rhythm and its shortest spelling, drawn on top of each other.
        Roll(
            name = "condense",
            blurb = "Four pairs. The upper lane of each is a rhythm spelled out one slot per step; " +
                "the lower is whatever `patCondense` returned for it, played. **The two lanes of a " +
                "pair have to coincide exactly** — that is the whole claim, and it is drawn rather " +
                "than asserted, because a condensation that changes the rhythm is the one failure " +
                "worth being able to see at a glance. The labels say what the search found: eight " +
                "slots become `x(3,8)`, sixteen become `x(k16)`, and a grouped four-step figure " +
                "becomes `bd hh*2 sd cp*2` — fifteen characters against twenty-one, which is the " +
                "only reason the search prefers it. The last pair is the counter-example that stops " +
                "this being a tautology: eight atoms with no repeat at any split the grammar can " +
                "take, which come back **unchanged**. The search finds structure; it does not " +
                "manufacture it.",
            build = {
                val cases = listOf(
                    bjorklund(3, 8).joinToString(" ") { if (it) "x" else "~" },
                    kolakoskiMask(16).joinToString(" ") { if (it) "x" else "~" },
                    "bd [hh hh] sd [cp cp]",
                    "a b c d e c a d"
                )
                PatRollSpec(
                    title = "patCondense: the shortest notation that plays the same bar",
                    subtitle = "given (above) against condensed (below) — four pairs, each of which must coincide",
                    cycles = 1,
                    lanes = cases.flatMapIndexed { index, source ->
                        val condensed = patCondense(source)
                        val color = LANE_COLORS[index % LANE_COLORS.size]
                        listOf(
                            Lane(condenseLaneLabel(source), pat(source), color = color),
                            Lane("→ ${condensed.notation}", pat(condensed.notation), color = color)
                        )
                    }
                )
            },
            verify = { lanes ->
                check(lanes.size == 8) { "the condense roll drew ${lanes.size} lanes, expected four pairs" }
                for (index in lanes.indices step 2) {
                    val given = lanes[index]
                    val found = lanes[index + 1]
                    fun shape(events: List<PatEvent<String>>) =
                        events.map { "${it.arc.start}..${it.arc.end}|${it.value}" }.sorted()
                    check(shape(given.cycle(0)) == shape(found.cycle(0))) {
                        "${found.label} does not play ${given.label}: " +
                            "${shape(given.cycle(0))} against ${shape(found.cycle(0))}"
                    }
                    check(given.cycle(0).isNotEmpty()) { "${given.label} drew nothing at all" }
                }
                // The three structured pairs really did shorten, and the fourth really did not.
                check(lanes[1].label == "→ x(3,8)") { "the Euclidean pair reads ${lanes[1].label}" }
                check(lanes[3].label == "→ x(k16)") { "the Kolakoski pair reads ${lanes[3].label}" }
                check(lanes[5].label == "→ bd hh*2 sd cp*2") { "the grouped pair reads ${lanes[5].label}" }
                check(lanes[7].label == "→ a b c d e c a d") {
                    "the structureless pair was condensed to ${lanes[7].label}, so the search invented " +
                        "something that is not in the rhythm"
                }
            }
        ),
    )

    /** A given-rhythm lane's label: the `x`/`.` reading when it is a one-voice mask, else the source. */
    private fun condenseLaneLabel(source: String): String {
        val steps = source.trim().split(Regex("\\s+"))
        if (steps.size > 4 && steps.all { it == "~" || it == "x" }) {
            return steps.joinToString("") { if (it == "x") "x" else "." }
        }
        return source
    }

    /** Cycle-0 arcs of a lane, for the terse checks above. */
    private fun arcsOfLane(lane: LaneEvents): List<Pair<PatQ, PatQ>> =
        lane.cycle(0).map { it.arc.start to it.arc.end }

    private val byName: Map<String, Roll> = ROLLS.associateBy { it.name }

    // ── resolving a spec into real events ──────────────────────────────────────────────────

    /** Queries every lane over [PatRollSpec.cycles] whole cycles — the measured part of the render. */
    fun resolve(spec: PatRollSpec): List<LaneEvents> = spec.lanes.map { lane ->
        val events = lane.pattern.events(PatArc(PatQ.ZERO, PatQ.of(spec.cycles)))
            .sortedWith(compareBy({ it.arc.start }, { it.arc.end }))
        LaneEvents(lane.label, events, spec.cycles)
    }

    // ── SVG rendering ──────────────────────────────────────────────────────────────────────

    private const val WIDTH = 1100
    private const val LEFT = 190
    private const val RIGHT_PAD = 24
    private const val TOP = 62
    private const val LANE_H = 74
    private const val LANE_GAP = 10
    private const val BOTTOM = 34

    /** Renders [spec] to a standalone SVG document. */
    fun renderSvg(spec: PatRollSpec, resolved: List<LaneEvents>): String {
        val plotWidth = WIDTH - LEFT - RIGHT_PAD
        val height = TOP + spec.lanes.size * (LANE_H + LANE_GAP) + BOTTOM
        val sb = StringBuilder(1 shl 16)
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$WIDTH" height="$height" """)
        sb.append("""viewBox="0 0 $WIDTH $height" font-family="Iosevka, Menlo, monospace">""").append('\n')
        sb.append("""<rect x="0" y="0" width="$WIDTH" height="$height" fill="$BG"/>""").append('\n')
        sb.append(text(LEFT, 26, spec.title, TEXT, 17, weight = "600")).append('\n')
        sb.append(text(LEFT, 46, spec.subtitle, TEXT_DIM, 12)).append('\n')

        fun x(cycleTime: Double) = LEFT + cycleTime / spec.cycles * plotWidth

        spec.lanes.forEachIndexed { laneIndex, lane ->
            val laneEvents = resolved[laneIndex]
            val top = TOP + laneIndex * (LANE_H + LANE_GAP)
            val bottom = top + LANE_H
            val color = lane.color ?: LANE_COLORS[laneIndex % LANE_COLORS.size]

            sb.append("""<rect x="$LEFT" y="$top" width="$plotWidth" height="$LANE_H" fill="#191c24"/>""").append('\n')

            // sixteenth gridlines, with the cycle boundaries emphasised.
            for (sixteenth in 0..spec.cycles * 16) {
                val gx = fmt(x(sixteenth / 16.0))
                val strong = sixteenth % 16 == 0
                sb.append(
                    """<line x1="$gx" y1="$top" x2="$gx" y2="$bottom" stroke="${if (strong) GRID_STRONG else GRID}" """ +
                        """stroke-width="${if (strong) "1.2" else "0.5"}"/>"""
                ).append('\n')
            }

            sb.append(text(12, top + LANE_H / 2 + 4, lane.label, TEXT, 12)).append('\n')

            // A lane whose atoms are all numbers gets a value axis; anything else gets one row per
            // distinct atom. Getting this wrong is visible: laid out by row, `rand()` reads as a
            // smooth descending line, because rows are assigned in order of first appearance.
            val numbers = laneEvents.events.map { it.value.toDoubleOrNull() }
            val numeric = lane.paramKey == null && numbers.isNotEmpty() && numbers.all { it != null }
            val values = if (numeric) numbers.map { it!! } else emptyList()
            val loValue = values.minOrNull() ?: 0.0
            val hiValue = values.maxOrNull() ?: 1.0
            val atoms = if (numeric) emptyList() else orderAtoms(laneEvents.events.map { it.value }.distinct())

            for (event in laneEvents.events) {
                val x0 = x(event.arc.start.toDouble())
                val x1 = x(event.arc.end.toDouble())
                val w = max(1.5, x1 - x0 - 1.0)

                val rowTop: Double
                val rowHeight: Double
                var opacity = 0.92
                // Instrument colour applies where a lane is drawn as atom rows, because that is the
                // only layout whose rectangles stand for *things you hear*. A value-axis lane plots
                // one number and a parameter lane plots one measurement; colouring either per atom
                // would say a `gain` of 0.4 and a `gain` of 0.8 are different instruments.
                var fill = color
                when {
                    numeric -> {
                        val span = hiValue - loValue
                        val value = event.value.toDoubleOrNull() ?: loValue
                        val unit = if (span <= 0.0) 0.5 else (value - loValue) / span
                        rowHeight = if (lane.pitched) max(4.0, (LANE_H - 14.0) / max(6.0, span)) else 5.0
                        rowTop = bottom - 7.0 - unit * (LANE_H - 14.0 - rowHeight) - rowHeight
                    }
                    // `pan` is a position, not a magnitude: draw it as one, or a hard-left event
                    // would be an invisible sliver.
                    lane.paramKey == "pan" -> {
                        val unit = event.num("pan", 0.5).coerceIn(0.0, 1.0)
                        rowHeight = (LANE_H - 14.0) / 3.0
                        rowTop = top + 7.0 + unit * (LANE_H - 14.0 - rowHeight)
                    }
                    lane.paramKey != null -> {
                        val raw = event.num(lane.paramKey, 1.0)
                        val unit = paramUnit(lane.paramKey, raw)
                        rowHeight = max(3.0, unit * (LANE_H - 14.0))
                        rowTop = bottom - 7.0 - rowHeight
                        opacity = 0.45 + 0.5 * unit
                    }
                    else -> {
                        val row = max(0, atoms.indexOf(event.value))
                        val rows = max(1, atoms.size)
                        rowHeight = (LANE_H - 14.0) / rows
                        rowTop = top + 7.0 + row * rowHeight
                        fill = atomColor(event.value) ?: color
                    }
                }
                sb.append(
                    """<rect x="${fmt(x0 + 0.5)}" y="${fmt(rowTop)}" width="${fmt(w)}" height="${fmt(rowHeight)}" """ +
                        """rx="1.5" fill="$fill" fill-opacity="${fmt(opacity)}"/>"""
                ).append('\n')
            }

            // parameter polyline, drawn from the same events.
            if (lane.paramKey != null && laneEvents.events.isNotEmpty()) {
                val points = laneEvents.events.joinToString(" ") { event ->
                    val unit = paramUnit(lane.paramKey, event.num(lane.paramKey, if (lane.paramKey == "pan") 0.5 else 1.0))
                    val px = x((event.arc.start.toDouble() + event.arc.end.toDouble()) / 2.0)
                    val py = if (lane.paramKey == "pan") top + 7.0 + unit * (LANE_H - 14.0) else bottom - 7.0 - unit * (LANE_H - 14.0)
                    "${fmt(px)},${fmt(py)}"
                }
                sb.append("""<polyline points="$points" fill="none" stroke="#ffffff" stroke-opacity="0.55" stroke-width="1"/>""").append('\n')
                sb.append(text(LEFT + 6, top + 14, lane.paramKey, TEXT_DIM, 10)).append('\n')
            }

            // a few atom labels, so the roll is readable without the source next to it.
            if (!numeric && lane.paramKey == null && atoms.size <= 6) {
                atoms.forEachIndexed { row, atom ->
                    val rows = max(1, atoms.size)
                    val rowHeight = (LANE_H - 14.0) / rows
                    // Tinted to match the row's rectangles, which makes the roll its own legend.
                    sb.append(text(LEFT - 6, top + 7.0 + row * rowHeight + rowHeight * 0.8, atom, atomColor(atom) ?: TEXT_DIM, 9, anchor = "end")).append('\n')
                }
            }
            if (numeric) {
                val hiLabel = if (lane.pitched) hiValue.toInt().toString() else patFormatNumber(round3(hiValue))
                val loLabel = if (lane.pitched) loValue.toInt().toString() else patFormatNumber(round3(loValue))
                sb.append(text(LEFT - 6, top + 14, hiLabel, TEXT_DIM, 9, anchor = "end")).append('\n')
                sb.append(text(LEFT - 6, bottom - 4, loLabel, TEXT_DIM, 9, anchor = "end")).append('\n')
            }
        }

        // cycle axis.
        val axisY = TOP + spec.lanes.size * (LANE_H + LANE_GAP) + 16
        for (cycle in 0..spec.cycles) {
            sb.append(text(x(cycle.toDouble()), axisY, "cycle $cycle", TEXT_DIM, 10, anchor = if (cycle == spec.cycles) "end" else "start")).append('\n')
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    // ── compact rendering: one example, at the width of a paragraph ─────────────────────────

    private const val COMPACT_WIDTH = 720
    private const val COMPACT_LANE_H = 40
    private const val COMPACT_LANE_GAP = 6
    private const val COMPACT_AXIS_H = 15
    private const val COMPACT_PAD = 3

    /**
     * Height of the strip above a lane that carries its source, when a roll draws more than one.
     *
     * It is a strip rather than a corner of the lane itself because the first draft put the label
     * inside the plot at the top left, where it sat directly on top of the first event's rectangle
     * and its name — `bd sd . hh hh hh` overprinted the `bd` bar in every two-lane figure. There is
     * nowhere inside a plot that is reliably empty, so the label gets space of its own.
     */
    private const val COMPACT_LABEL_H = 13

    /**
     * The same roll at **card size**: one or two lanes, no title block, sized to sit under a
     * paragraph rather than to fill a figure.
     *
     * [renderSvg] is 1100px wide with a 190px label gutter, which is the right shape for nine lanes
     * of comparison and the wrong shape entirely for a single `bd [sd sd]` — the docs page's content
     * column is 52rem, so the big roll arrives scaled down and its 9px labels arrive unreadable.
     * This variant drops the gutter, puts each atom's name *inside* its own rectangle where it fits,
     * and leans on [ATOM_COLORS] for the rest. The consequence worth knowing: it draws atom rows
     * unconditionally, so it is for lanes of named instruments and not for value-axis or parameter
     * lanes, which have no atoms to name and are what [renderSvg] is for.
     *
     * [showLaneLabels] writes each lane's own source into its top-left corner. Off for a single
     * lane, where the card's heading already says it; on for a shorthand drawn against the
     * bracketed spelling it stands for, where which-is-which is the whole point of the figure.
     */
    fun renderCompactSvg(
        spec: PatRollSpec,
        resolved: List<LaneEvents>,
        showLaneLabels: Boolean = spec.lanes.size > 1
    ): String {
        require(spec.lanes.isNotEmpty()) { "a compact roll needs at least one lane" }
        val plotWidth = COMPACT_WIDTH - 2 * COMPACT_PAD
        val labelH = if (showLaneLabels) COMPACT_LABEL_H else 0
        val laneStride = COMPACT_LANE_H + labelH + COMPACT_LANE_GAP
        val height = spec.lanes.size * laneStride + COMPACT_AXIS_H
        val sb = StringBuilder(1 shl 13)
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$COMPACT_WIDTH" height="$height" """)
        sb.append("""viewBox="0 0 $COMPACT_WIDTH $height" font-family="Iosevka, Menlo, monospace">""").append('\n')
        sb.append("""<rect x="0" y="0" width="$COMPACT_WIDTH" height="$height" fill="$BG"/>""").append('\n')

        fun x(cycleTime: Double) = COMPACT_PAD + cycleTime / spec.cycles * plotWidth

        spec.lanes.forEachIndexed { laneIndex, lane ->
            val laneEvents = resolved[laneIndex]
            // The label strip sits above its own lane, so the lane's own top moves down past it.
            if (showLaneLabels) {
                sb.append(text(COMPACT_PAD + 1, laneIndex * laneStride + 10, lane.label, TEXT, 10, weight = "600")).append('\n')
            }
            val top = laneIndex * laneStride + labelH
            val bottom = top + COMPACT_LANE_H
            val laneColor = lane.color ?: LANE_COLORS[laneIndex % LANE_COLORS.size]

            sb.append("""<rect x="$COMPACT_PAD" y="$top" width="$plotWidth" height="$COMPACT_LANE_H" fill="#191c24"/>""").append('\n')

            // Sixteenths stay legible up to two cycles; past that they close into a grey wash, so
            // the grid coarsens to eighths and the cycle boundaries keep doing the real work.
            val division = if (spec.cycles <= 2) 16 else 8
            for (tick in 0..spec.cycles * division) {
                val gx = fmt(x(tick.toDouble() / division))
                val strong = tick % division == 0
                sb.append(
                    """<line x1="$gx" y1="$top" x2="$gx" y2="$bottom" stroke="${if (strong) GRID_STRONG else GRID}" """ +
                        """stroke-width="${if (strong) "1.2" else "0.5"}"/>"""
                ).append('\n')
            }

            val atoms = orderAtoms(laneEvents.events.map { it.value }.distinct())
            val rows = max(1, atoms.size)
            val rowHeight = (COMPACT_LANE_H - 8.0) / rows

            for (event in laneEvents.events) {
                val x0 = x(event.arc.start.toDouble())
                val x1 = x(event.arc.end.toDouble())
                val w = max(1.5, x1 - x0 - 1.0)
                val row = max(0, atoms.indexOf(event.value))
                val rowTop = top + 4.0 + row * rowHeight
                val fill = atomColor(event.value) ?: laneColor
                sb.append(
                    """<rect x="${fmt(x0 + 0.5)}" y="${fmt(rowTop)}" width="${fmt(w)}" height="${fmt(rowHeight)}" """ +
                        """rx="1.5" fill="$fill" fill-opacity="0.92"/>"""
                ).append('\n')
                // The name goes inside its own bar when the bar is wide enough to hold it, which is
                // what removes the need for a label gutter. 5.4px per char at font-size 9, plus the
                // inset either side; narrower than that and the colour is the only label.
                val label = event.value
                if (w > label.length * 5.4 + 8 && rowHeight >= 8.0) {
                    sb.append(
                        text(x0 + 4.0, rowTop + rowHeight / 2 + 3.2, label, "#14161c", 9, weight = "600")
                    ).append('\n')
                }
            }

        }

        // Cycle ruler. One tick per cycle boundary, the last one right-anchored so it stays inside.
        val axisY = spec.lanes.size * laneStride + 10
        for (cycle in 0..spec.cycles) {
            sb.append(
                text(x(cycle.toDouble()), axisY, "cycle $cycle", TEXT_DIM, 9, anchor = if (cycle == spec.cycles) "end" else "start")
            ).append('\n')
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    /** Maps a parameter value onto 0..1 for drawing. Known keys get their natural range. */
    private fun paramUnit(key: String, value: Double): Double = when (key) {
        "cutoff" -> (Math.log10(value.coerceAtLeast(20.0) / 20.0) / Math.log10(1000.0)).coerceIn(0.0, 1.0)
        else -> value.coerceIn(0.0, 1.0)
    }

    private fun text(
        x: Number,
        y: Number,
        content: String,
        fill: String,
        size: Int,
        weight: String = "400",
        anchor: String = "start"
    ): String =
        """<text x="${fmt(x.toDouble())}" y="${fmt(y.toDouble())}" fill="$fill" font-size="$size" """ +
            """font-weight="$weight" text-anchor="$anchor">${escape(content)}</text>"""

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private fun fmt(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return v.toLong().toString()
        return String.format(Locale.US, "%.2f", v)
    }

    // ── public entry points ────────────────────────────────────────────────────────────────

    /** Renders roll [name] and returns its SVG source. */
    fun svgFor(name: String): String {
        val roll = byName[name] ?: error("unknown PatPianoRollGallery roll '$name'; known: ${byName.keys.sorted()}")
        val spec = roll.build()
        return renderSvg(spec, resolve(spec))
    }

    /**
     * Renders an arbitrary set of lanes — the same renderer the gallery uses, for patterns that are
     * not in [ROLLS].
     *
     * This is what makes the harness usable live: a box can roll whatever it is currently editing,
     * with the same layout, grid and value-axis rules as the committed figures.
     */
    fun svgForLanes(
        lanes: List<Lane>,
        title: String = "pattern",
        subtitle: String = "",
        cycles: Int = 2
    ): String {
        val spec = PatRollSpec(title, subtitle, cycles.coerceAtLeast(1), lanes)
        return renderSvg(spec, resolve(spec))
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("PAT_ROLL_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "pat-piano-roll-gallery").path
        )
        outDir.mkdirs()

        ROLLS.forEach { roll ->
            val spec = roll.build()
            val resolved = resolve(spec)

            check(resolved.isNotEmpty()) { "${roll.name}: no lanes" }
            resolved.forEach { lane ->
                check(lane.events.isNotEmpty()) { "${roll.name}/${lane.label}: drew nothing" }
                lane.events.forEach { event ->
                    check(event.arc.start >= PatQ.ZERO && event.arc.end <= PatQ.of(spec.cycles)) {
                        "${roll.name}/${lane.label}: event outside the plotted window: ${event.arc}"
                    }
                }
            }
            roll.verify(resolved)

            val svg = renderSvg(spec, resolved)
            val totalEvents = resolved.sumOf { it.events.size }
            // The figure must contain exactly one rectangle per event, plus one lane backdrop per
            // lane, plus the one page background — so nothing is drawn that no event produced.
            val rects = Regex("<rect ").findAll(svg).count()
            val expectedRects = totalEvents + spec.lanes.size + 1
            check(rects == expectedRects) {
                "${roll.name}: SVG has $rects rects but $totalEvents events + ${spec.lanes.size} lanes + 1 background = $expectedRects"
            }
            check(svg.startsWith("<svg") && svg.trimEnd().endsWith("</svg>")) { "${roll.name}: malformed SVG" }
            check(!svg.contains("NaN") && !svg.contains("Infinity")) { "${roll.name}: SVG contains a non-finite coordinate" }

            val file = File(outDir, "pat-${roll.name}.svg")
            file.writeText(svg)
            println("wrote ${file.absolutePath} (${resolved.size} lanes, $totalEvents events, ${svg.length} bytes)")
        }

        println("pat piano-roll gallery rendered to ${outDir.absolutePath} (${ROLLS.size} rolls)")
    }
}

/** Renders a [PatPianoRollGallery] roll into this box's output as an inline SVG. */
fun afield.Box.showPatRoll(name: String, channel: String = "main") =
    display(channel, PatPianoRollGallery.svgFor(name))

/**
 * Rolls arbitrary patterns into this box's output — one lane per `label to pattern` pair.
 *
 * ```
 * showPatRollOf(
 *     "kick" to struct(euclid(3, 8), pat("bd")),
 *     "hats" to (pat("hh*16") with gain(sine())),
 *     title = "my groove", cycles = 2, paramKey = "gain",
 * )
 * ```
 *
 * A lane whose atoms all parse as numbers is drawn on a value axis; [paramKey] switches every lane
 * to drawing that named parameter instead. [pitched] labels the value axis as MIDI.
 */
fun afield.Box.showPatRollOf(
    vararg lanes: Pair<String, PatPattern<String>>,
    title: String = "pattern",
    subtitle: String = "",
    cycles: Int = 2,
    paramKey: String? = null,
    pitched: Boolean = false,
    channel: String = "main"
) = display(
    channel,
    PatPianoRollGallery.svgForLanes(
        lanes.map { (label, pattern) ->
            PatPianoRollGallery.Lane(label, pattern, paramKey = paramKey, pitched = pitched)
        },
        title = title,
        subtitle = subtitle,
        cycles = cycles
    )
)
