@file:afield.CompletionCategory("Patterns")

package afield.four.synth

import afield.stdlib.linalg.Vector3f
import java.io.File
import java.util.Locale

/*
FIELD_TEST
id: synth.marf.gallery
runner: jvm-main
entrypoint: afield.four.synth.MarfGallery
tags: [smoke, synth, pattern, marf, plot, svg, capture, cpu]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "marf gallery rendered"
*/

/**
 * Headless **figure gallery** for the function-generation layer in `Marf.kt`, and the check that
 * every figure on `marf.md` is a measurement of the reader it claims to draw.
 *
 * Output is **SVG**, like the piano rolls in `PatPianoRollGallery.kt` and for the same reason: a
 * stage plot is line art, so drawing it as vector geometry makes it diffable — a changed curve is a
 * changed polyline in the file rather than a changed PNG blob. No AWT, no browser, no GPU, no Field
 * window.
 *
 * A MARF figure is not a piano roll, which is why it has its own renderer. A roll draws events; a
 * reader has a *continuous output* and a *stage structure*, and the interesting thing is how the two
 * relate. So a reader's lane draws four things at one shared time scale:
 *
 * - the **output**, as a polyline densely sampled through [MarfReader.at];
 * - the **stage boundaries**, as dashed verticals, one per stage the reader actually entered;
 * - the **address** in force during each stage, printed in the stage's own span (`~` when the
 *   address fell between two slots);
 * - a **pulse** tick where the stage emitted one, taken from [MarfReader.triggers].
 *
 * Reading down a column therefore compares the same instant across every lane, and a figure with
 * three lanes over one bank is the architecture's whole claim in one picture. A lane may also hold a
 * plain signal rather than a reader — that is how the `hold` / `ramp` / `interp` figure is drawn,
 * since those conversions produce signals and have no stages to show.
 *
 * #### What each figure is checked against
 *
 * As a FIELD_TEST (`synth.marf.gallery`) every figure renders to a temp dir and is asserted against
 * the readers that produced it: one boundary vertical per stage, one polyline point per sample, one
 * pulse tick per triggering stage, no stage starting past the plotted window, and no non-finite
 * output. So a figure can neither invent a stage nor lose one.
 *
 * Two checks are about the layer rather than the drawing:
 *
 * - **the output is continuous across every interior stage boundary whose arriving stage does not
 *   step** — exactly, comparing the departing stage's value at phase 1 against the arriving stage's
 *   at phase 0, with no tolerance. This is what makes a bank of stages an envelope generator rather
 *   than a list of disconnected ramps, and it is invisible at the resolution the figure draws. A
 *   stage steps exactly when its curve's shape is non-zero at phase 0, which is what `hold` is, so
 *   those boundaries are skipped by that test rather than by hand;
 * - **a figure whose point is that two readers differ really does draw two different curves**, so
 *   "one bank, three journeys" cannot become three copies of one journey.
 *
 * Each figure then runs its own `verify` over the stages it drew, which is where the specific claim
 * lives — that the patterned durations really are 1, 4, ¼ and 2 cycles exactly; that
 * `every(4) { rev() }` reverses the first cycle's addresses and leaves the next three; that a rest
 * in a euclidean address holds the previous slot rather than snapping to zero.
 *
 * It writes no committed assets during the test. Pass an output dir as `args[0]` (or set
 * `MARF_FIGURE_DIR`) to regenerate the committed figures under `public/docs/assets/marf/`.
 *
 * Add a figure by appending one [Figure] to [FIGURES]; `MarfDocsGuardTest` then requires
 * `public/docs/marf.md` to reference it, and requires every committed SVG to belong to one.
 */

/**
 * Renders every [Figure] in [FIGURES] to SVG and asserts each one against the readers that drew it.
 *
 * [bank] is the five-stage scalar bank the figures read; [vectorPath] is the `Vector3f` reader the
 * vector figure needs.
 */
object MarfGallery {

    /** Output samples per cycle. Fine enough that an `osc:3` stage reads as three oscillations. */
    const val SAMPLES_PER_CYCLE = 96

    // ── the model ─────────────────────────────────────────────────────────────────────────

    /**
     * One lane of a figure: something to plot, and the label the figure puts beside it.
     *
     * Exactly one of [reader] and [signal] is set. A reader lane also draws stage boundaries,
     * addresses and pulses; a signal lane draws only its curve, because a signal has no stages.
     *
     * @param label the left-hand caption. By convention this is the *expression*, so a figure reads
     *   without the page next to it.
     * @param key the name this lane's measurements are filed under, and the one an assertion names.
     */
    class Lane private constructor(
        val label: String,
        val key: String,
        val reader: MarfReader<Double>?,
        val signal: PatPattern<Double>?,
        val stagesFrom: MarfReader<*>?
    ) {
        /** Whoever supplies this lane's stage structure, when anything does. */
        val structure: MarfReader<*>? get() = reader ?: stagesFrom

        companion object {
            /** A lane plotting a scalar reader, with its own stages, addresses and pulses. */
            fun of(label: String, reader: MarfReader<Double>): Lane =
                Lane(label, reader.name, reader, null, null)

            /**
             * A lane plotting a bare signal.
             *
             * [stagesFrom] is for the case where the signal is *one component of* a reader whose
             * value is not a number: the curve comes from the component and the boundaries,
             * addresses and pulses come from the reader, so the figure shows one stage structure
             * carrying several values. Left null, the lane is a plain signal with no stages —
             * which is what `hold`/`ramp`/`interp` are.
             */
            fun ofSignal(
                label: String,
                key: String,
                signal: PatPattern<Double>,
                stagesFrom: MarfReader<*>? = null
            ): Lane = Lane(label, key, null, signal, stagesFrom)
        }
    }

    /**
     * One figure of the gallery, and everything published about it.
     *
     * @param id the asset stem — `marf-<id>.svg`, and the name `marf.md` references.
     * @param title the figure's own heading, drawn into the SVG.
     * @param subtitle one line saying what to look at.
     * @param cycles how many cycles to draw.
     * @param lanes what to plot, top to bottom.
     * @param distinctLanes when true, no two lanes may draw the same curve — for the figures whose
     *   entire point is that two readings of one bank differ.
     * @param verify a claim about the stages this figure actually drew, beyond the generic checks.
     * @param snippet the code `marf.md` publishes beside this figure, as one string, or null where
     *   the page shows no code for it. See [Figure.pageClocks] for why this lives here.
     */
    class Figure(
        val id: String,
        val title: String,
        val subtitle: String,
        val cycles: Int,
        val lanes: List<Lane>,
        val distinctLanes: Boolean = false,
        val verify: (Map<String, List<MarfResolvedStage<*>>>) -> Unit = {},
        val snippet: String? = null
    ) {
        /**
         * The field clocks this figure's lanes were actually built with.
         *
         * The reason a figure carries its published [snippet] rather than the page carrying it
         * alone: `MarfDocsGuardTest` already re-renders every SVG and pins figure-to-page
         * correspondence, but it could not see a *snippet*, and that is the gap two false claims
         * went through. The "Banks, and the readers over them" listing dropped
         * `fields = MarfFieldClock.STAGES` from all three of its reads while [MarfReader.fieldClock]
         * on all three lanes stayed `STAGES` — so the page printed code that cannot produce the
         * picture above it, and the prose under it went on describing the behaviour of the argument
         * it had just deleted. With both halves here, the guard can compare them.
         */
        val pageClocks: Set<MarfFieldClock>
            get() = lanes.mapNotNull { it.structure?.fieldClock }.toSet()
    }

    // ── the shared material ───────────────────────────────────────────────────────────────

    /**
     * The bank every bank-reading figure on the page uses.
     *
     * Five stages, unequal in both length and shape, because a bank of five equal ramps
     * would make the separation of content from timing invisible.
     */
    fun bank(): MarfBank<Double> = marfBank(
        rampTo(0.1, 1.0, label = "a"),
        rampTo(0.9, 0.5, label = "b"),
        holdAt(0.4, 2.0, label = "c"),
        oscTo(0.7, 1.0, cycles = 3.0, label = "d"),
        curveTo(0.2, 0.25, amount = 4.0, label = "e")
    )

    // ── the catalogue ─────────────────────────────────────────────────────────────────────

    val FIGURES: List<Figure> by lazy { buildFigures() }

    private fun buildFigures(): List<Figure> {
        val quarters = constant(0.25)

        return listOf(

            // 1 ── duration is first class, and the grid is gone.
            Figure(
                id = "stage-durations",
                title = "A stage's duration is patterned, like everything else",
                subtitle = "Four values and four durations, read one per stage. Nothing here is a grid.",
                cycles = 8,
                lanes = listOf(
                    Lane.of(
                        "equal stages",
                        marf(value = pat("<0 .5 .2 1>").nums(), fields = MarfFieldClock.STAGES, name = "equal")
                    ),
                    Lane.of(
                        "duration \"<1 4 .25 2>\"",
                        marf(
                            value = pat("<0 .5 .2 1>").nums(),
                            duration = pat("<1 4 .25 2>").nums(),
                            fields = MarfFieldClock.STAGES,
                            name = "uneven"
                        )
                    ),
                    Lane.of(
                        "curve \"<linear exp hold osc>\"",
                        marf(
                            value = pat("<0 .5 .2 1>").nums(),
                            duration = pat("<1 4 .25 2>").nums(),
                            curve = curves("<linear exp hold osc>"),
                            fields = MarfFieldClock.STAGES,
                            name = "shaped"
                        )
                    )
                ),
                verify = { stages ->
                    val spans = stages.getValue("uneven").take(4).map { it.duration }
                    check(spans == listOf(PatQ.ONE, PatQ.of(4), PatQ.of(1, 4), PatQ.of(2))) {
                        "the four durations should be 1, 4, 1/4 and 2 cycles exactly, saw $spans"
                    }
                    val targets = stages.getValue("uneven").take(4).map { it.target as Double }
                    check(targets == listOf(0.0, 0.5, 0.2, 1.0)) { "the four values drifted: $targets" }
                },
                snippet = """
                    val env = marf(
                        value    = pat("<0 .5 .2 1>").nums(),
                        duration = pat("<1 4 .25 2>").nums(),
                        curve    = curves("<linear exp hold osc>"),
                        fields   = MarfFieldClock.STAGES
                    )
                """.trimIndent()
            ),

            // 2 ── the curve vocabulary, one stage each.
            Figure(
                id = "curves",
                title = "The curve of a stage",
                subtitle = "One stage per cycle, alternating between a target of 1 and a target of 0. Only the curve differs.",
                cycles = 6,
                lanes = listOf("hold", "linear", "smooth", "exp", "log", "step:5", "osc", "osc:3").map { name ->
                    Lane.of(
                        name,
                        marf(
                            value = pat("<1 0>").nums(),
                            curve = marfCurvePattern(marfCurve(name)),
                            fields = MarfFieldClock.STAGES,
                            name = name
                        )
                    )
                },
                verify = { stages ->
                    check(stages.getValue("linear").size == 6) { "one stage per cycle expected" }
                }
            ),

            // 3 ── the architecture: several readers, one bank.
            Figure(
                id = "two-readers",
                title = "One bank, three readers",
                subtitle = "The same five stages, three trajectories. The bank has no position of its own.",
                cycles = 8,
                // STAGES, so each reader's address pattern is read as a per-stage sequence. The
                // bank's own durations are uneven, so in the default CYCLES reading a `<…>` address
                // would be latched wherever a stage happened to land and would not walk the bank
                // one slot at a time — which is the thing this figure is about.
                lanes = bank().let { shared ->
                    val perStage = MarfFieldClock.STAGES
                    listOf(
                        Lane.of(
                            "read \"<0 1 2 1 3 4>\"",
                            shared.read(pat("<0 1 2 1 3 4>").nums(), fields = perStage, name = "A")
                        ),
                        Lane.of(
                            "read slow 3 \"<0 2 4 1 3>\"",
                            shared.read(pat("<0 2 4 1 3>").nums().slow(3), fields = perStage, name = "B")
                        ),
                        Lane.of(
                            "read \"<0 4>\", speed 4",
                            shared.read(
                                pat("<0 4>").nums(),
                                speed = constant(4.0),
                                fields = perStage,
                                name = "C"
                            )
                        )
                    )
                },
                distinctLanes = true,
                verify = { stages ->
                    val a = stages.getValue("A").take(6).map { it.address }
                    check(a == listOf(0.0, 1.0, 2.0, 1.0, 3.0, 4.0)) { "reader A's trajectory changed: $a" }
                    // Six stages fill the eight-cycle window: three of slot 0 at a cycle each,
                    // then three of slot 2 at two cycles each.
                    val b = stages.getValue("B").map { it.address }
                    check(b == listOf(0.0, 0.0, 0.0, 2.0, 2.0, 2.0)) {
                        "slow(3) should hold each address for three stages: $b"
                    }
                    val c = stages.getValue("C").take(4).map { it.address }
                    check(c == listOf(0.0, 4.0, 0.0, 4.0)) { "reader C should alternate two slots: $c" }
                },
                snippet = """
                    val b = marfBank(
                        rampTo(0.1, 1.0), rampTo(0.9, 0.5), holdAt(0.4, 2.0),
                        oscTo(0.7, 1.0, cycles = 3.0), curveTo(0.2, 0.25, amount = 4.0)
                    )

                    val a = b.read(pat("<0 1 2 1 3 4>").nums(), fields = MarfFieldClock.STAGES)
                    val c = b.read(pat("<0 2 4 1 3>").nums().slow(3), fields = MarfFieldClock.STAGES)
                    val d = b.read(pat("<0 4>").nums(), speed = constant(4.0), fields = MarfFieldClock.STAGES)
                """.trimIndent()
            ),

            // 4 ── the trajectory is compositional material.
            Figure(
                id = "address-transforms",
                title = "The trajectory is a pattern, so the pattern algebra steers it",
                subtitle = "One bank, four stages a cycle. Only the address pattern differs.",
                cycles = 8,
                lanes = bank().let { shared ->
                    listOf(
                        Lane.of("\"0 1 2 3\"", shared.read(pat("0 1 2 3").nums(), duration = quarters, name = "run")),
                        Lane.of(
                            "every 4 rev",
                            shared.read(
                                pat("0 1 2 3").nums().every(4) { it.rev() },
                                duration = quarters,
                                name = "reversed"
                            )
                        ),
                        Lane.of(
                            "\"0 1 2 3 2 1\"",
                            shared.read(pat("0 1 2 3 2 1").nums(), duration = constant(1.0 / 6.0), name = "pingpong")
                        ),
                        Lane.of(
                            "struct euclid 5 8",
                            shared.read(
                                struct(euclid(5, 8), pat("0 4 2 3").nums()),
                                duration = constant(0.125),
                                name = "euclid"
                            )
                        )
                    )
                },
                distinctLanes = true,
                verify = { stages ->
                    val first = stages.getValue("reversed").take(4).map { it.address }
                    check(first == listOf(3.0, 2.0, 1.0, 0.0)) { "cycle 0 should be reversed: $first" }
                    val second = stages.getValue("reversed").drop(4).take(4).map { it.address }
                    check(second == listOf(0.0, 1.0, 2.0, 3.0)) { "cycle 1 should be untouched: $second" }
                    // The euclidean lane's rests hold rather than snapping to slot 0.
                    val euclid = stages.getValue("euclid").take(8).map { it.address }
                    check(euclid == listOf(0.0, 0.0, 4.0, 4.0, 4.0, 2.0, 3.0, 3.0)) {
                        "a rest in the address should hold the previous slot: $euclid"
                    }
                },
                // No `fields` argument anywhere, and that is the point of this figure rather than
                // an omission: these are CYCLES readers, so the address is a control voltage and a
                // four-step-per-cycle address is exactly what the mode is for. The guard checks
                // that absence as hard as it checks the presences above.
                snippet = """
                    b.read(pat("0 1 2 3").nums(), duration = constant(0.25))                    // forward
                    b.read(pat("0 1 2 3").nums().every(4) { it.rev() }, duration = constant(0.25)) // periodic reversal
                    b.read(pat("0 1 2 3 2 1").nums(), duration = constant(1.0 / 6.0))           // ping-pong
                    b.read(struct(euclid(5, 8), pat("0 4 2 3").nums()), duration = constant(0.125))
                """.trimIndent()
            ),

            // 5 ── a fractional address, and the aliasing that comes with latching one.
            Figure(
                id = "continuous-address",
                title = "A fractional address blends the stages it lies between",
                subtitle = "The bank stops being five slots and becomes a sampled family of functions.",
                cycles = 8,
                lanes = bank().let { shared ->
                    listOf(
                        Lane.of("read \"0 1 2 3\"", shared.read(pat("0 1 2 3").nums(), duration = quarters, name = "stepped")),
                        Lane.of(
                            "read saw slow 8",
                            shared.read(saw().slow(8).range(0.0, 4.0), duration = quarters, name = "glide")
                        ),
                        Lane.of(
                            "read sine",
                            shared.read(sine().range(0.0, 4.0), duration = quarters, name = "aliased")
                        )
                    )
                },
                distinctLanes = true,
                verify = { stages ->
                    val glide = stages.getValue("glide").take(32).map { it.address }
                    check(glide.count { it % 1.0 > 1e-4 } >= 24) { "a gliding address should land between slots" }
                    check(glide.zipWithNext().all { (p, q) -> q > p }) { "a rising address should rise: $glide" }
                    // An address latched once per stage is only ever a sequence; when its period and
                    // the stage period are commensurate that sequence is short. Count rounded: the
                    // repeats agree to parts in 10^4, not bit-exactly.
                    val aliased = stages.getValue("aliased").take(32)
                        .map { String.format(Locale.US, "%.3f", it.address) }
                    check(aliased.distinct().size <= 6) {
                        "the commensurate address should alias to a handful of slots: ${aliased.distinct()}"
                    }
                }
            ),

            // 6 ── a reader whose value is not a number.
            Figure(
                id = "vector-stages",
                title = "A stage's value need not be a number",
                subtitle = "One Vector3f reader over one bank, drawn as its three components.",
                cycles = 6,
                lanes = vectorPath().let { path ->
                    listOf(
                        Lane.ofSignal("signal { it.x }", "x", path.signal { it.x.toDouble() }, path),
                        Lane.ofSignal("signal { it.y }", "y", path.signal { it.y.toDouble() }, path),
                        Lane.ofSignal("signal { it.z }", "z", path.signal { it.z.toDouble() }, path)
                    )
                },
                distinctLanes = true,
                snippet = """
                    val path = marfBankIn(
                        MarfVector3f,
                        stageTo(Vector3f(0f, 0f, 0f), 1.0),
                        stageTo(Vector3f(1f, 0f, 0f), 0.5, MarfCurve.Smooth),
                        stageTo(Vector3f(1f, 1f, 0f), 1.5, MarfCurve.Exp(3.0)),
                        stageTo(Vector3f(0f, 0.25f, 1f), 1.0, MarfCurve.Osc(2.0))
                    ).read(pat("<0 1 2 3 2 1>").nums(), fields = MarfFieldClock.STAGES)

                    path.at(3.5).value               // a Vector3f
                    path.signal { it.x.toDouble() }  // any component, as an ordinary signal
                """.trimIndent()
            ),

            // 7 ── the conversion that needs no bank at all.
            Figure(
                id = "discrete-to-continuous",
                title = "An event stream read as a continuous function",
                subtitle = "`hold`, `ramp` and `interp` over one pattern — no bank, no reader, no stages.",
                cycles = 4,
                lanes = listOf(
                    Lane.ofSignal("hold()", "held", pat("0 .5 1 .25").nums().hold()),
                    Lane.ofSignal("ramp()", "ramped", pat("0 .5 1 .25").nums().ramp()),
                    Lane.ofSignal(
                        "interp(Smooth)",
                        "smoothed",
                        pat("0 .5 1 .25").nums().interp(MarfCurve.Smooth)
                    )
                ),
                distinctLanes = true
            )
        )
    }

    /**
     * A `Vector3f` reader over four vector stages, for the vector figure.
     *
     * The three lanes of that figure are this one reader read three times through
     * [MarfReader.signal], which is also how a vector reader reaches the rest of the language. The
     * figure therefore needs no vector-aware renderer: the components are ordinary signals.
     */
    fun vectorPath(): MarfReader<Vector3f> = marfBankIn(
        MarfVector3f,
        stageTo(Vector3f(0f, 0f, 0f), 1.0, label = "origin"),
        stageTo(Vector3f(1f, 0f, 0f), 0.5, MarfCurve.Smooth, label = "x"),
        stageTo(Vector3f(1f, 1f, 0f), 1.5, MarfCurve.Exp(3.0), label = "xy"),
        stageTo(Vector3f(0f, 0.25f, 1f), 1.0, MarfCurve.Osc(2.0), label = "z")
    ).read(pat("<0 1 2 3 2 1>").nums(), fields = MarfFieldClock.STAGES, name = "path")

    // ── rendering ─────────────────────────────────────────────────────────────────────────

    private const val BG = "#14161c"
    private const val LANE_BG = "#191c24"
    private const val GRID = "#2a2f3a"
    private const val GRID_STRONG = "#454d5e"
    private const val BOUND = "#5c6478"
    private const val TEXT = "#c9d1e0"
    private const val TEXT_DIM = "#7d879b"
    private const val CURVE = "#7fd4ff"
    private const val PULSE = "#ffb347"

    private const val WIDTH = 1100
    private const val LEFT = 220
    private const val RIGHT_PAD = 24
    private const val TOP = 62
    private const val LANE_H = 74
    private const val LANE_GAP = 10
    private const val BOTTOM = 34

    /** The geometry a figure drew, so the checks can count it rather than trust it. */
    class Drawn(
        val svg: String,
        val stages: Map<String, List<MarfResolvedStage<*>>>,
        val samples: Map<String, List<Double>>,
        val boundaries: Map<String, Int>,
        val pulses: Map<String, Int>
    )

    /** Samples [lane] at cycle [t] — through the reader, or through the signal's own query. */
    private fun sample(lane: Lane, t: Double): Double {
        lane.reader?.let { return it.value(t) }
        val signal = lane.signal!!
        val q = PatQ.fromDouble(t, MARF_DURATION_DENOM)
        return signal.events(PatArc(q, q + PatQ.of(1, 65536))).firstOrNull()?.value ?: 0.0
    }

    fun render(figure: Figure): Drawn {
        val plotWidth = WIDTH - LEFT - RIGHT_PAD
        val height = TOP + figure.lanes.size * (LANE_H + LANE_GAP) + BOTTOM
        val window = PatArc(PatQ.ZERO, PatQ.of(figure.cycles.toLong()))

        val sb = StringBuilder(1 shl 16)
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$WIDTH" height="$height" """)
        sb.append("""viewBox="0 0 $WIDTH $height" font-family="Iosevka, Menlo, monospace">""").append('\n')
        sb.append("""<rect x="0" y="0" width="$WIDTH" height="$height" fill="$BG"/>""").append('\n')
        sb.append(text(LEFT, 26, figure.title, TEXT, 17, weight = "600")).append('\n')
        sb.append(text(LEFT, 46, figure.subtitle, TEXT_DIM, 12)).append('\n')

        fun x(cycleTime: Double) = LEFT + cycleTime / figure.cycles * plotWidth

        val stages = mutableMapOf<String, List<MarfResolvedStage<*>>>()
        val samples = mutableMapOf<String, List<Double>>()
        val boundaries = mutableMapOf<String, Int>()
        val pulses = mutableMapOf<String, Int>()

        // Sample every lane before drawing any of it, so all the lanes of one figure can share a
        // value axis. Per-lane autoscaling makes two readers of one bank look like they cover the
        // same range when one of them covers a fifth of it, which is the opposite of what a
        // "one bank, several readers" figure is for. Time was already shared; now amplitude is.
        val sampleCount = figure.cycles * SAMPLES_PER_CYCLE
        for (lane in figure.lanes) {
            stages[lane.key] = lane.structure?.stagesIn(window) ?: emptyList()
            samples[lane.key] = (0..sampleCount).map { sample(lane, it.toDouble() / SAMPLES_PER_CYCLE) }
        }
        val allValues = samples.values.flatten()
        val lo = allValues.minOrNull() ?: 0.0
        val hi = allValues.maxOrNull() ?: 1.0
        val span = if (hi - lo < 1e-9) 1.0 else hi - lo

        figure.lanes.forEachIndexed { laneIndex, lane ->
            val top = TOP + laneIndex * (LANE_H + LANE_GAP)
            val bottom = top + LANE_H
            val inner = LANE_H - 18.0

            sb.append("""<rect x="$LEFT" y="$top" width="$plotWidth" height="$LANE_H" fill="$LANE_BG"/>""").append('\n')

            // quarter-cycle gridlines, so reading down a column compares the same instant.
            for (step in 0..figure.cycles * 4) {
                val gx = fmt(x(step / 4.0))
                val strong = step % 4 == 0
                sb.append(
                    """<line x1="$gx" y1="$top" x2="$gx" y2="$bottom" stroke="${if (strong) GRID_STRONG else GRID}" """ +
                        """stroke-width="${if (strong) "1.2" else "0.5"}"/>"""
                ).append('\n')
            }

            sb.append(text(12, top + LANE_H / 2 + 4, lane.label, TEXT, 12)).append('\n')

            val laneStages = stages.getValue(lane.key)
            val values = samples.getValue(lane.key)
            fun y(v: Double) = bottom - 9.0 - ((v - lo) / span) * inner

            // stage boundaries first, so the curve draws over them.
            for (stage in laneStages) {
                val bx = fmt(x(stage.start.toDouble()))
                sb.append(
                    """<line x1="$bx" y1="${top + 2}" x2="$bx" y2="${bottom - 2}" stroke="$BOUND" """ +
                        """stroke-width="1" stroke-dasharray="2 2"/>"""
                ).append('\n')
            }
            boundaries[lane.key] = laneStages.size

            val points = values.indices.joinToString(" ") { i ->
                "${fmt(x(i.toDouble() / SAMPLES_PER_CYCLE))},${fmt(y(values[i]))}"
            }
            sb.append("""<polyline points="$points" fill="none" stroke="$CURVE" stroke-width="1.6"/>""").append('\n')

            // The address in force, printed in each stage wide enough to hold it — but only when
            // it says something. A reader built by `marf(...)` has no bank, and reports the stage
            // index as its address, so a row of 0 1 2 3 there would read as a trajectory through
            // material that does not exist.
            val addressIsJustTheIndex = laneStages.isNotEmpty() &&
                laneStages.all { it.address == it.index.toDouble() }
            for (stage in if (addressIsJustTheIndex) emptyList() else laneStages) {
                val x0 = x(stage.start.toDouble())
                val x1 = x(stage.end.toDouble())
                if (x1 - x0 < 22.0) continue
                val label = if (stage.address % 1.0 > 1e-4) "~" else stage.address.toInt().toString()
                sb.append(text(x0 + 3, top + 13, label, TEXT_DIM, 9)).append('\n')
            }

            // pulses, taken from the reader's own trigger pattern.
            val triggerEvents = lane.structure?.triggers()?.events(window)?.filter { it.hasOnset } ?: emptyList()
            for (event in triggerEvents) {
                val px = fmt(x(event.extent.start.toDouble()))
                sb.append(
                    """<line x1="$px" y1="${bottom - 6}" x2="$px" y2="${bottom - 1}" stroke="$PULSE" stroke-width="1.6"/>"""
                ).append('\n')
            }
            pulses[lane.key] = triggerEvents.size

            sb.append(text(LEFT - 6, top + 14, patFormatNumber(round3(hi)), TEXT_DIM, 9, anchor = "end")).append('\n')
            sb.append(text(LEFT - 6, bottom - 4, patFormatNumber(round3(lo)), TEXT_DIM, 9, anchor = "end")).append('\n')
        }

        val axisY = TOP + figure.lanes.size * (LANE_H + LANE_GAP) + 16
        for (cycle in 0..figure.cycles) {
            sb.append(
                text(
                    x(cycle.toDouble()), axisY, "cycle $cycle", TEXT_DIM, 10,
                    anchor = if (cycle == figure.cycles) "end" else "start"
                )
            ).append('\n')
        }
        sb.append("</svg>\n")

        return Drawn(sb.toString(), stages, samples, boundaries, pulses)
    }

    // ── checks ────────────────────────────────────────────────────────────────────────────

    /**
     * Asserts a rendered figure against the readers and signals that produced it.
     *
     * Everything here is a count or a comparison over geometry the renderer actually emitted, so it
     * fails on a figure that illustrates rather than measures.
     */
    fun checkFigure(figure: Figure, drawn: Drawn) {
        val end = PatQ.of(figure.cycles.toLong())

        for (lane in figure.lanes) {
            val key = lane.key
            val laneStages = drawn.stages.getValue(key)
            val values = drawn.samples.getValue(key)

            check(values.size == figure.cycles * SAMPLES_PER_CYCLE + 1) {
                "${figure.id}/$key: ${values.size} samples for ${figure.cycles} cycles"
            }
            check(values.all { it.isFinite() }) { "${figure.id}/$key: a non-finite output" }
            check(values.distinct().size > 1) { "${figure.id}/$key: drew a flat line" }

            check(drawn.boundaries.getValue(key) == laneStages.size) {
                "${figure.id}/$key: ${drawn.boundaries[key]} boundary lines for ${laneStages.size} stages"
            }
            check(drawn.pulses.getValue(key) == laneStages.count { it.trigger }) {
                "${figure.id}/$key: ${drawn.pulses[key]} pulses for ${laneStages.count { it.trigger }} triggering stages"
            }

            if (lane.structure != null) {
                check(laneStages.isNotEmpty()) { "${figure.id}/$key: a reader lane drew no stages" }
                check(laneStages.all { it.start < end }) { "${figure.id}/$key: a stage starts past the window" }
                check(laneStages.map { it.index } == laneStages.indices.map { laneStages[0].index + it }) {
                    "${figure.id}/$key: the stages are not consecutive"
                }
            }

            // The claim the figure cannot show at this resolution: handing over from one stage to
            // the next does not move the output. Exact — the departing stage's value at phase 1
            // against the arriving stage's at phase 0, no tolerance.
            //
            // Except where the arriving stage *steps*, which is what `hold` is for. A stage steps
            // exactly when its curve's shape is non-zero at phase 0, so that is the condition
            // rather than a per-lane switch: a `holdAt` slot anywhere in a bank would otherwise
            // turn the check off for every reader over it, which is most of this page.
            // Re-derived from the reader rather than read out of `drawn`, so the values are
            // typed. A component lane's continuity is its vector reader's, and is asserted in
            // MarfVectorSpaceTest instead.
            val scalarStages = lane.reader?.stagesIn(PatArc(PatQ.ZERO, PatQ.of(figure.cycles.toLong()))).orEmpty()
            for (i in 1 until scalarStages.size) {
                val arrivingStage = scalarStages[i]
                val steps = arrivingStage.components.any { marfCurveShape(it.stage.curve, 0.0) != 0.0 }
                if (steps) continue
                val leaving = scalarStages[i - 1].valueAt(1.0)
                val arriving = arrivingStage.valueAt(0.0)
                check(leaving == arriving) {
                    "${figure.id}/$key: output jumps $leaving -> $arriving entering stage ${arrivingStage.index}"
                }
            }
        }

        if (figure.distinctLanes) {
            val curves = figure.lanes.map { drawn.samples.getValue(it.key) }
            for (i in curves.indices) {
                for (j in i + 1 until curves.size) {
                    check(curves[i] != curves[j]) {
                        "${figure.id}: lanes '${figure.lanes[i].label}' and '${figure.lanes[j].label}' drew the same curve"
                    }
                }
            }
        }

        figure.verify(drawn.stages)
    }

    // ── entry point ───────────────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val target = args.firstOrNull() ?: System.getenv("MARF_FIGURE_DIR")
        val dir = if (target != null) File(target) else File(System.getProperty("java.io.tmpdir"), "marf-gallery")
        dir.mkdirs()

        for (figure in FIGURES) {
            val drawn = render(figure)
            checkFigure(figure, drawn)
            val file = File(dir, "marf-${figure.id}.svg")
            file.writeText(drawn.svg)
            val stageCount = drawn.stages.values.sumOf { it.size }
            println("  ${figure.id}: ${figure.lanes.size} lane(s), $stageCount stages -> ${file.name}")
        }

        println("marf gallery rendered ${FIGURES.size} figure(s) into $dir")
    }

    // ── small helpers ─────────────────────────────────────────────────────────────────────

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

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

    private fun fmt(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return v.toLong().toString()
        return String.format(Locale.US, "%.2f", v)
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
