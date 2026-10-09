package afield.examples.stacksl

import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.DEFAULT_FITNESS_DIM
import afield.stdlib.Evaluation
import afield.stdlib.Img
import afield.stdlib.PopulationSearch
import afield.stdlib.RasterStructure
import afield.stdlib.SearchRun
import afield.stdlib.Selection
import afield.stdlib.StackSLGenome
import afield.stdlib.StackSLImageObjective
import afield.stdlib.StackSLMetric
import afield.stdlib.StackSLPalette
import afield.stdlib.StackSLProgram
import afield.stdlib.StackSLScoreCache
import afield.stdlib.StackSLLayout
import afield.stdlib.StackSLTarget
import afield.stdlib.StackSLTargets
import afield.stdlib.StackSLWordKind
import afield.stdlib.danglingValuesOf
import afield.stdlib.montage
import afield.stdlib.savePng
import afield.stdlib.toImg
import afield.stdlib.upscaleNearest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/*
FIELD_TEST
id: stacksl.evolve.gallery
runner: jvm-main
entrypoint: afield.examples.stacksl.StackSLEvolveGallery
tags: [smoke, stacksl, search, capture, cpu]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "stacksl evolve gallery rendered"
*/

/**
 * Verifies that evolving a StackSL program toward a picture finds programs that look like the
 * picture, and that chance does not.
 *
 * Modelled on `AlifeEvolveGallery`. Every search claim is stated against `PopulationSearch.random`
 * at the same budget, from the same `StackSLGenome`, with the same seed, and every margin is
 * asserted as a floor rather than printed.
 *
 * Two metrics are compared throughout, because the first version of this search showed that
 * beating the control is not the same as resembling the target. Under [StackSLMetric.PIXEL]
 * (`1 -` mean absolute error) evolution beat chance on four of five targets while producing a
 * dark-streak texture for a yellow disc and a vertical gradient for four quadrants: the error
 * rewards getting the colour balance right, and colour balance is the cheapest thing to get right.
 * [StackSLMetric.LPIPS] scores with a learned perceptual distance instead, here with lexicase
 * selection over the four quadrants' LPIPS, so the right texture in the wrong place is not enough.
 * Resemblance is then checked with a third measurement neither arm optimised,
 * [StackSLLayout.layoutAgreement], at four times the search's resolution — and by looking at the
 * contact sheet. `--arms` runs other combinations (`lpips`, `mix`, `mixlex`).
 *
 *  - [figurePremise]: the machinery, before any search is believed — vocabulary arity, the blank
 *    guard, both metrics and the layout measure on known inputs, and a hand-written program per
 *    target to show the vocabulary can draw each one.
 *  - [figureTargets]: the figure. Per target, `evolve` under PIXEL and under LPIPS with lexicase,
 *    and one random control scored both ways, with floors on skill, on the margin over chance, and
 *    on layout.
 *  - [figureProxy]: does a ranking made at 32 pixels survive at 256?
 *  - [figureMapElites]: an archive over (spatial frequency x colourfulness).
 *  - [figureDeterminism]: the same seed gives the same program.
 *
 * As a FIELD_TEST it renders to a temp dir and writes no committed assets. Pass `--out DIR` (or set
 * `STACKSL_EVOLVE_DIR`) to keep the PNGs, `--only name,name` to run some figures,
 * `--budget N` and `--targets a,b` to run [figureTargets] at another size. The floors are for the
 * defaults; a run at any other size reports and draws but does not assert them.
 */
object StackSLEvolveGallery {

    // ==========================================================================================
    // Verification plumbing — same shape as AlifeEvolveGallery's
    // ==========================================================================================

    private val failures = ArrayList<String>()
    private var checks = 0

    private fun fail(message: String) {
        failures.add(message)
        println("[stacksl-evolve] FAIL $message")
    }

    private fun check(condition: Boolean, message: String) {
        checks++
        if (!condition) fail(message) else println("[stacksl-evolve] ok  $message")
    }

    /** A check that only counts at the default size; at any other size it is reported, not asserted. */
    private fun floor(condition: Boolean, message: String) {
        if (atDefaults) check(condition, message)
        else println("[stacksl-evolve] (not asserted at this size) ${if (condition) "ok" else "below"}  $message")
    }

    private fun f(v: Double, digits: Int = 3): String = "%.${digits}f".format(v)

    // ==========================================================================================
    // Sizes. Every floor below was measured at these, and is asserted only at these.
    // ==========================================================================================

    /** Fitness resolution for every search here. */
    private const val FIT_DIM: Int = DEFAULT_FITNESS_DIM

    /** Presentation resolution: what the contact sheet and the layout check re-render at. */
    private const val SHOW_DIM: Int = 128

    /** Evaluations per arm in [figureTargets] by default. */
    private const val DEFAULT_BUDGET: Int = 700

    /** The targets [figureTargets] runs by default. */
    private val DEFAULT_TARGETS: List<String> = listOf("spot", "bands", "quadrants")

    private var budget: Int = DEFAULT_BUDGET
    private var targetNames: List<String> = DEFAULT_TARGETS

    /** Fitness supersampling for [figureTargets]; see `StackSLImageObjective.supersample`. */
    private const val DEFAULT_SUPERSAMPLE: Int = 1
    private var supersample: Int = DEFAULT_SUPERSAMPLE
    private val atDefaults: Boolean
        get() = budget == DEFAULT_BUDGET && targetNames == DEFAULT_TARGETS && supersample == DEFAULT_SUPERSAMPLE &&
            armLabels == DEFAULT_ARMS

    /** One seed for every search in this file. */
    private const val SEED: Long = 11L

    // ==========================================================================================
    // Programs this file needs by name
    // ==========================================================================================

    /**
     * A hand-written program per target that draws its layout in its colours.
     *
     * Proof that the vocabulary can express each target at all, in at most 21 tokens, which
     * matters for reading a failure: a search that cannot find a picture its language cannot make
     * has not failed. `x+y` is not in [StackSLPalette.DEFAULT] (the others' words all are), so the
     * checker oracle is one word the generator could not have drawn.
     */
    private val ORACLES: Map<String, String> = mapOf(
        "spot" to "[0.03,0.04,0.12] [1,0.82,0.25] p length 0.28 threshold 1- mix",
        "rings" to "p length 40 * sin 0 threshold",
        "bands" to "[0.05,0.1,0.55] [0.98,0.9,0.1] uv x 6 * floor 2 mod 1- mix",
        "checker" to "[0.9,0.1,0.7] [0.05,0.45,0.4] uv 4 * floor x+y 2 mod 1- mix",
        "quadrants" to "[0.98,0.9,0.1] [0.05,0.06,0.15] uv x 0.5 threshold mix " +
            "[0.9,0.1,0.1] [0.1,0.5,0.95] uv x 0.5 threshold mix uv y 0.5 threshold mix"
    )

    /**
     * What the 2026-09-06 version of this search returned under [StackSLMetric.PIXEL] (budget
     * 1600, seed 11, five threads), reproduced exactly on 2026-10-07. Kept as fixtures: they are the
     * pictures that showed the metric was wrong, so the new metric is asked to rank them below a
     * program that draws the target's layout in grey.
     */
    private val PIXEL_WINNERS_2026_09_06: Map<String, String> = mapOf(
        "spot" to "vmin x rotate p rotate p p vmin sin rotate p rotate p rotate rotate cart average average",
        "rings" to "1.5s4 vec2 p white 1.0 input smoothstep tile 4.68 posterize 5.134 12.0 black distance sqrt " +
            "atan2 twist min fract min fract xy ]wr8 min",
        "bands" to "yellow magenta sigmoid sigmoid sigmoid 0.741 orange fbm >y bias uv uv posterize abs cyan bias " +
            "input bias cyan bias uv mix",
        "checker" to "input median magenta rgb fbm rgb fbm 0.939 bias neg 1.077 xy magenta fbm input median bias " +
            "cyan smoothstep ]fbm4",
        "quadrants" to "0.784 0.879 distance input twist mirror x x input twist tau input twist twist ryb magenta " +
            "pulse xx xx"
    )

    private fun target(name: String, dim: Int): StackSLTarget = when (name) {
        "spot" -> StackSLTargets.spot(dim)
        "rings" -> StackSLTargets.rings(dim)
        "bands" -> StackSLTargets.bands(dim)
        "checker" -> StackSLTargets.checker(dim)
        "quadrants" -> StackSLTargets.quadrants(dim)
        else -> error("no target named '$name' (have ${StackSLTargets.all(2).map { it.name }})")
    }

    /** Layout agreement of [program] against [name]'s target, both at [SHOW_DIM]. */
    private fun layoutAt(program: String, name: String, dim: Int = SHOW_DIM): Double =
        StackSLLayout.layoutAgreement(StackSLCpu.renderStill(program, dim = dim), target(name, dim).raster)

    // ==========================================================================================
    // 0 — premise
    // ==========================================================================================

    /**
     * The machinery, asserted before any search result is believed.
     *
     *  1. Every vocabulary word compiles with the arity the generator models (`palette` the one
     *     documented exception), and a straight-line program cannot be arity-invalid.
     *  2. The blank guard refuses a flat render and a flat target.
     *  3. LPIPS and the layout measure behave on inputs whose answer is known: a target against
     *     itself, against a flat fill, against its own negative.
     *  4. Each target has a hand-written program that draws it ([ORACLES]).
     *  5. The metric question, on fixed programs: does a program that draws the target's layout in
     *     grey outrank the 2026-09-06 PIXEL winner? Asserted for LPIPS, reported for PIXEL —
     *     which, measured, also ranks them the right way round. PIXEL's failure is in the landscape
     *     it gives the search, not in how it orders these two pictures.
     */
    private fun figurePremise(outDir: File) {
        val palette = StackSLPalette.DEFAULT
        val mismatchNames = ArrayList<String>()
        var compileFailures = 0
        for (word in palette.words) {
            val result = StackSLCpu.compile("input input input input input ${word.token}")
            if (!result.ok) {
                compileFailures++
                mismatchNames += "${word.token} (does not compile)"
                continue
            }
            // Five pushes on top of StackSL's initial value leave depth 6; a word that consumes c
            // and pushes 1 leaves 6 - c + 1, so c = 7 - depth.
            val depth = result.analysis.functions.last().finalStackDepth
            if (7 - depth != word.consumes) mismatchNames += "${word.token} (modelled ${word.consumes}, measured ${7 - depth})"
        }
        check(compileFailures == 0, "premise: all ${palette.words.size} vocabulary words compile")
        check(
            mismatchNames == listOf("palette (modelled 1, measured 5)"),
            "premise: the one word whose arity differs from the model is `palette`, which eats five " +
                "values when five are there. Measured: $mismatchNames"
        )
        for (lone in listOf("+", "mix", "vec4", "smoothstep", "drop drop drop")) {
            check(
                StackSLCpu.compile(lone).ok,
                "premise: `$lone` on an empty stack compiles — StackSL fills missing operands from " +
                    "defaultValues, so a straight-line program cannot be arity-invalid"
            )
        }
        check(
            danglingValuesOf(StackSLProgram.of("uv 4.0 * fbm palette saturate")) >= 0,
            "premise: danglingValuesOf reads the analysis of a program that compiles"
        )

        check(
            runCatching {
                PopulationSearch.evolve(StackSLGenome(), afield.stdlib.objectiveOf { 0.0 }, 40, selection = Selection.LEXICASE)
            }.isFailure,
            "premise: PopulationSearch refuses LEXICASE over an objective with no cases"
        )
        val withRegions = StackSLImageObjective(StackSLTargets.quadrants(FIT_DIM), dim = FIT_DIM, lpipsRegions = true)
            .evaluate(StackSLProgram.of("uv 4.0 * fbm palette saturate"))
        check(
            withRegions.cases.size == 4 && withRegions.cases.distinct().size > 1,
            "premise: with lpipsRegions the LPIPS objective supplies 4 quadrant cases for lexicase, and they differ"
        )

        // 2 — the blank guard.
        val quadrants = StackSLTargets.quadrants(FIT_DIM)
        val flatProgram = StackSLProgram.of("0.5")
        val flatRaster = StackSLCpu.renderStill(flatProgram.text, dim = FIT_DIM)
        for (metric in StackSLMetric.entries) {
            val objective = StackSLImageObjective(quadrants, dim = FIT_DIM, metric = metric)
            val e = objective.evaluate(flatProgram)
            check(
                e.fitness == StackSLImageObjective.INVALID_SCORE && e.cases.all { it == StackSLImageObjective.INVALID_SCORE },
                "premise/$metric: `0.5` renders one colour and scores INVALID_SCORE, every lexicase " +
                    "case included (flat-fill reference would have scored ${f(objective.flatFloor)})"
            )
        }
        val blankTarget = StackSLTarget(
            "blank", CpuRaster(FIT_DIM, FIT_DIM, FloatArray(FIT_DIM * FIT_DIM * 4) { if (it % 4 == 3) 1f else 0.5f })
        )
        check(
            runCatching { StackSLImageObjective(blankTarget, dim = FIT_DIM) }.exceptionOrNull() is IllegalArgumentException,
            "premise: a constant-colour target is refused outright"
        )

        // 3 — LPIPS and layout on known inputs.
        val names = StackSLTargets.all(2).map { it.name }
        for (name in names) {
            val t = target(name, FIT_DIM)
            check(t.structure.spread >= 0.15, "premise: target '$name' is structured (${t.structure})")
            val o = StackSLImageObjective(t, dim = FIT_DIM, metric = StackSLMetric.LPIPS)
            val self = o.scores(t.raster).lpipsFitness
            val negative = CpuRaster(FIT_DIM, FIT_DIM, FloatArray(FIT_DIM * FIT_DIM * 4) { if (it % 4 == 3) 1f else 1f - t.raster.rgba[it] })
            // The negative is reported, not asserted: LPIPS compares normalised features, and a
            // picture's negative has nearly the same edges. Measured 0.98 for rings, 0.43 for
            // quadrants. Colour is the PIXEL term's job, which is why layoutAgreement checks it.
            check(
                self == 1.0 && o.flatFloor < 0.5,
                "premise/$name: under LPIPS the target scores exactly 1 against itself (${f(self)}) and a flat " +
                    "fill of its mean colour ${f(o.flatFloor)}; its own negative scores ${f(o.scores(negative).lpipsFitness)}"
            )
            val layoutSelf = StackSLLayout.layoutAgreement(t.raster, t.raster)
            val layoutFlat = StackSLLayout.layoutAgreement(t.flatRaster(), t.raster)
            val layoutNegative = StackSLLayout.layoutAgreement(negative, t.raster)
            check(
                layoutSelf == 1.0 && layoutFlat == 0.0 && layoutNegative < 0.0,
                "premise/$name: layout agreement is 1 against itself, 0 for a flat fill, negative " +
                    "for its own negative (${f(layoutSelf)}, ${f(layoutFlat)}, ${f(layoutNegative)})"
            )
        }

        // 4 — every target can be drawn.
        val panels = ArrayList<Pair<String, Img>>()
        for (name in names) {
            val oracle = ORACLES.getValue(name)
            val layout = layoutAt(oracle, name)
            check(
                layout >= 0.9,
                "premise/$name: the hand-written program draws the target, layout ${f(layout)} at " +
                    "${SHOW_DIM}px (${StackSLProgram.of(oracle).size} tokens: $oracle)"
            )
            panels += name to target(name, FIT_DIM).raster.toImg().upscaleNearest(SHOW_DIM / FIT_DIM)
            panels += "drawn" to StackSLCpu.renderStill(oracle, dim = SHOW_DIM).toImg()
        }

        // 5 — the metric question on fixed programs. Only the targets whose two colours differ in
        // luma: drawn in grey, checker's magenta and teal (0.05 apart in luma) are one colour, and
        // quadrants' red is nearer its dark corner than its own colour.
        var lpipsPrefers = 0
        var pixelPrefers = 0
        val greyable = listOf("spot", "rings", "bands")
        for (name in greyable) {
            val t = target(name, FIT_DIM)
            val grey = StackSLProgram.of(ORACLES.getValue(name) + " luma")
            val old = StackSLProgram.of(PIXEL_WINNERS_2026_09_06.getValue(name))
            val line = StringBuilder("[stacksl-evolve]     $name: layout grey ${f(layoutAt(grey.text, name))}, " +
                "old winner ${f(layoutAt(old.text, name))};")
            for (metric in StackSLMetric.entries) {
                val o = StackSLImageObjective(t, dim = FIT_DIM, metric = metric)
                val g = o.evaluate(grey).fitness
                val w = o.evaluate(old).fitness
                line.append("  $metric grey ${f(g, 4)} vs old ${f(w, 4)}")
                if (g > w) { if (metric == StackSLMetric.LPIPS) lpipsPrefers++ else if (metric == StackSLMetric.PIXEL) pixelPrefers++ }
            }
            println(line)
        }
        println("[stacksl-evolve]     the grey layout outranks the old winner on $pixelPrefers/${greyable.size} " +
            "targets under PIXEL and $lpipsPrefers/${greyable.size} under LPIPS")
        check(
            lpipsPrefers == greyable.size,
            "premise: under LPIPS, the target's layout drawn in grey outranks the 2026-09-06 PIXEL " +
                "winner on all of ${greyable.joinToString()} ($lpipsPrefers)"
        )

        montage(panels, columns = 4, cellSide = SHOW_DIM + 10).savePng(File(outDir, "evolve-premise.png").path)
    }

    // ==========================================================================================
    // 1 — the figure: both metrics, against chance, on several targets
    // ==========================================================================================

    /** One way of searching: a metric and a selection scheme. */
    private class Arm(val label: String, val metric: StackSLMetric, val selection: Selection)

    /** Every arm [figureTargets] knows; `--arms` picks some by label. */
    private val ALL_ARMS: List<Arm> = listOf(
        Arm("pixel", StackSLMetric.PIXEL, Selection.TOURNAMENT),
        Arm("lpips", StackSLMetric.LPIPS, Selection.TOURNAMENT),
        Arm("lex", StackSLMetric.LPIPS, Selection.LEXICASE),
        Arm("mix", StackSLMetric.LPIPS_PIXEL, Selection.TOURNAMENT),
        Arm("mixlex", StackSLMetric.LPIPS_PIXEL, Selection.LEXICASE)
    )

    /** The arms run by default; the first is the 2026-09-06 search, the last the one asserted on. */
    private val DEFAULT_ARMS: List<String> = listOf("pixel", "lex")
    private var armLabels: List<String> = DEFAULT_ARMS

    /** One target's results: each arm's search, and one random control scored under every metric. */
    private class TargetResult(
        val target: StackSLTarget,
        val runs: Map<String, SearchRun<StackSLProgram>>,
        val randoms: Map<StackSLMetric, SearchRun<StackSLProgram>>,
        val objectives: Map<StackSLMetric, StackSLImageObjective>,
        val renders: Int,
        val seconds: Double
    ) {
        val name: String get() = target.name

        fun layout(run: SearchRun<StackSLProgram>): Double {
            val g = run.best?.genome ?: return 0.0
            if (run.bestFitness <= StackSLImageObjective.INVALID_SCORE) return 0.0
            return StackSLLayout.layoutAgreement(StackSLCpu.renderStill(g.text, dim = SHOW_DIM), StackSLTargetsAt.show(name))
        }
    }

    private object StackSLTargetsAt {
        private val cache = HashMap<String, CpuRaster>()
        @Synchronized
        fun show(name: String): CpuRaster = cache.getOrPut(name) { target(name, SHOW_DIM).raster }
    }

    /**
     * Per-target floors at the default size for the asserted arm, `name to (minSkill,
     * minGainOverControl)`, in [StackSLImageObjective.skill] units under that arm's metric: 0 is a
     * flat fill of the target's mean colour, 1 is exact. Measured, then floored with headroom; the
     * searches are deterministic under [SEED], so a miss means the search changed.
     */
    private val FLOORS: Map<String, Pair<Double, Double>> = mapOf(
        // measured 2026-10-07: skill 0.403, random control 0.280
        "spot" to (0.30 to 0.06),
        // 0.808 against 0.623
        "bands" to (0.65 to 0.09),
        // 0.678 against 0.400
        "quadrants" to (0.55 to 0.14)
    )

    /**
     * Targets on which the asserted arm's winner must out-layout PIXEL's, at the default size.
     *
     * One, because that is what was measured, and it is the honest number. On `quadrants` the
     * LPIPS-lexicase winner is three flat blocks in roughly the right places (layout 0.66, PIXEL's
     * a horizontal gradient at 0.30). On `bands` it draws vertical bands, but three of them rather
     * than six and out of phase, which a position-exact measure scores at -0.70: it reads as bands
     * and is not the target's bands. On `spot` neither arm draws a disc.
     */
    private const val MIN_LAYOUT_WINS: Int = 1

    private var lastResults: List<TargetResult> = emptyList()

    private fun figureTargets(outDir: File) {
        val arms = armLabels.map { label -> ALL_ARMS.firstOrNull { it.label == label } ?: error("no arm '$label'") }
        val asserted = arms.last()
        val results = runTargets(targetNames, budget, arms)
        lastResults = results
        var won = 0
        var layoutWins = 0
        val panels = ArrayList<Pair<String, Img>>()
        for (r in results) {
            val line = StringBuilder("[stacksl-evolve]     %-9s".format(r.name))
            for (arm in arms) {
                val run = r.runs.getValue(arm.label)
                val o = r.objectives.getValue(arm.metric)
                line.append("  %s skill %+.3f (random %+.3f) layout %.2f".format(
                    arm.label, o.skill(run.bestFitness), o.skill(r.randoms.getValue(arm.metric).bestFitness), r.layout(run)
                ))
            }
            val randomShown = r.randoms.getValue(asserted.metric)
            line.append("  random layout %.2f  %d renders %.0fs".format(r.layout(randomShown), r.renders, r.seconds))
            println(line)
            for (arm in arms) println("[stacksl-evolve]       %-10s %s".format(arm.label, r.runs.getValue(arm.label).best?.genome?.text))

            val run = r.runs.getValue(asserted.label)
            val o = r.objectives.getValue(asserted.metric)
            val skill = o.skill(run.bestFitness)
            val control = o.skill(randomShown.bestFitness)
            if (skill > control) won++
            val layout = r.layout(run)
            if (layout > r.layout(r.runs.getValue(arms.first().label))) layoutWins++
            FLOORS[r.name]?.let { (minSkill, minGain) ->
                floor(skill >= minSkill, "targets/${r.name}: ${asserted.label} skill ${f(skill)} clears its $minSkill floor")
                floor(
                    skill - control >= minGain,
                    "targets/${r.name}: ${asserted.label} beats its random control by ${f(skill - control)} skill, " +
                        "clearing the $minGain floor (control ${f(control)})"
                )
            }
            check(run.recombined > 0, "targets/${r.name}: crossover ran (${run.recombined} children)")
            check(
                run.proposals > run.evaluations,
                "targets/${r.name}: signature memoisation served ${run.proposals - run.evaluations} repeat proposal(s) at no cost"
            )
            val shown = StackSLCpu.renderStill(run.best!!.genome.text, dim = SHOW_DIM)
            val shownStructure = RasterStructure.of(shown)
            check(
                shownStructure.spread >= StackSLImageObjective.MIN_SPREAD,
                "targets/${r.name}: the ${asserted.label} winner is still a picture at ${SHOW_DIM}px ($shownStructure)"
            )
            panels += r.name to r.target.raster.toImg().upscaleNearest(SHOW_DIM / FIT_DIM)
            for (arm in arms) {
                val armRun = r.runs.getValue(arm.label)
                panels += "%s %.2f".format(arm.label, r.layout(armRun)) to
                    StackSLCpu.renderStill(armRun.best!!.genome.text, dim = SHOW_DIM).toImg()
            }
            panels += "rnd %.2f".format(r.layout(randomShown)) to
                StackSLCpu.renderStill(randomShown.best!!.genome.text, dim = SHOW_DIM).toImg()
        }
        println(
            "[stacksl-evolve]     ${asserted.label} beat its control on $won/${results.size}; " +
                "out-layouts ${arms.first().label} on $layoutWins/${results.size}"
        )
        floor(
            won == results.size,
            "targets: ${asserted.label} beats the random control on $won of ${results.size} targets at equal " +
                "budget ($budget each), same seed, same genome"
        )
        floor(
            layoutWins >= MIN_LAYOUT_WINS,
            "targets: ${asserted.label}'s winner has the target's layout more nearly than ${arms.first().label}'s " +
                "on $layoutWins of ${results.size} targets (at least $MIN_LAYOUT_WINS)"
        )
        montage(panels, columns = arms.size + 2, cellSide = SHOW_DIM + 32).savePng(File(outDir, "evolve-targets.png").path)
    }

    /**
     * Every target's arms, one thread per target, serial inside each render.
     *
     * Per target: each arm's `evolve`, then one `random` draw scored under every metric the arms
     * use. All of them share one [StackSLScoreCache], and every render is scored under every
     * metric, so the random control's extra metrics — the same draws under the same seed — cost no
     * renders at all. Concurrent across targets for wall-clock, and bit-reproducible, because each
     * search draws from its own seeded `Random` and nothing it reads is shared across targets.
     */
    private fun runTargets(names: List<String>, budget: Int, arms: List<Arm>): List<TargetResult> {
        val pool = Executors.newFixedThreadPool(minOf(names.size, Runtime.getRuntime().availableProcessors()))
        try {
            return names.map { name ->
                pool.submit<TargetResult> {
                    val started = System.nanoTime()
                    val t = target(name, FIT_DIM)
                    val cache = StackSLScoreCache()
                    // Regions always on, so every arm can share one cache whatever it selects by.
                    fun objective(metric: StackSLMetric) = StackSLImageObjective(
                        t, dim = FIT_DIM, parallel = false, metric = metric, cache = cache, supersample = supersample,
                        lpipsRegions = true
                    )
                    val runs = arms.associate { arm ->
                        arm.label to PopulationSearch.evolve(
                            StackSLGenome(), objective(arm.metric), budget, seed = SEED,
                            mu = 8, lambda = 16, tournament = 3, selection = arm.selection,
                            crossoverRate = 0.25, immigrantRate = 0.05, label = "$name ${arm.label}"
                        )
                    }
                    val metrics = arms.map { it.metric }.distinct()
                    val randoms = metrics.associateWith { PopulationSearch.random(StackSLGenome(), objective(it), budget, seed = SEED) }
                    TargetResult(t, runs, randoms, metrics.associateWith { objective(it) }, cache.size, (System.nanoTime() - started) / 1e9)
                }
            }.map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    // ==========================================================================================
    // 2 — the proxy question
    // ==========================================================================================

    /**
     * Does a ranking made at 32 pixels survive being re-scored at 256?
     *
     * Takes the final population and record holders of a LPIPS search on `quadrants` (the one
     * [figureTargets] already ran, when it ran) and scores them again against the same target
     * generated at [PROXY_DIM]. Kendall's tau over concordant and discordant pairs, because the
     * question is whether the order survives, not whether the scores are equal.
     *
     * The argmax is reported, not asserted: it moved between resolutions in the 2026-09-06 run
     * (PIXEL metric), and two programs a hundredth apart at 32 pixels can honestly swap.
     */
    private fun figureProxy(outDir: File) {
        val small = StackSLTargets.quadrants(FIT_DIM)
        val big = StackSLTargets.quadrants(PROXY_DIM)
        val objective = StackSLImageObjective(small, dim = FIT_DIM)
        val run = lastResults.firstOrNull { it.name == "quadrants" }?.runs?.values?.lastOrNull() ?: PopulationSearch.evolve(
            StackSLGenome(), objective, 600, seed = SEED,
            mu = 8, lambda = 16, tournament = 3, crossoverRate = 0.25, immigrantRate = 0.05, label = "proxy"
        )
        val candidates = (run.survivors.map { it.genome } + run.improvements.map { it.genome }).distinctBy { it.text }
        check(candidates.size >= 6, "proxy: ${candidates.size} distinct candidates to rank")
        val lo = candidates.map { objective.evaluate(it).fitness }
        val hi = candidates.map { objective.scoreAt(it, big) }
        var concordant = 0
        var discordant = 0
        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            val a = lo[i] - lo[j]
            val b = hi[i] - hi[j]
            if (a == 0.0 || b == 0.0) continue
            if ((a > 0) == (b > 0)) concordant++ else discordant++
        }
        val tau = if (concordant + discordant == 0) 0.0 else (concordant - discordant).toDouble() / (concordant + discordant)
        val bestLo = candidates[lo.indices.maxBy { lo[it] }]
        val bestHi = candidates[hi.indices.maxBy { hi[it] }]
        println("[stacksl-evolve]     proxy: ${candidates.size} candidates, $concordant concordant, $discordant discordant, tau ${f(tau)}")
        println("[stacksl-evolve]     the same program wins at both sizes: ${bestLo.text == bestHi.text}")
        check(
            tau >= PROXY_TAU_FLOOR,
            "proxy: the ${FIT_DIM}px ranking agrees with the ${PROXY_DIM}px one at Kendall tau ${f(tau)}, " +
                "clearing $PROXY_TAU_FLOOR"
        )
        montage(
            listOf(
                "target" to big.raster.toImg(),
                "32px best" to objective.render(bestLo, PROXY_DIM).toImg(),
                "256px best" to objective.render(bestHi, PROXY_DIM).toImg()
            ),
            columns = 3, cellSide = 220
        ).savePng(File(outDir, "evolve-proxy.png").path)
    }

    private const val PROXY_DIM: Int = 256

    /** 0.884 under PIXEL on 2026-09-06; floored with headroom. */
    private const val PROXY_TAU_FLOOR: Double = 0.70

    // ==========================================================================================
    // 4 — MAP-Elites: a picture of the search space
    // ==========================================================================================

    /**
     * An archive over (spatial frequency x colourfulness): the best program *that renders like
     * this* per cell, which is a map of what the vocabulary makes rather than one answer.
     *
     * The control is the same budget of independent draws binned the same way. Compared on QD score
     * (the sum of elite fitnesses), because coverage alone is the wrong number.
     */
    private fun figureMapElites(outDir: File) {
        val target = StackSLTargets.checker(FIT_DIM)
        val cache = StackSLScoreCache()
        val objective = StackSLImageObjective(target, dim = FIT_DIM, cache = cache)
        val genome = StackSLGenome()
        val bins = listOf(6, 6)
        val axes = listOf(0.0..1.0, 0.0..1.0)
        val archive = PopulationSearch.mapElites(genome, objective, MAPELITES_BUDGET, axes, bins, seed = SEED, initial = 60)
        // Shift fitness so an elite always adds a positive amount: LPIPS can be negative.
        fun qd(values: Collection<Double>) = values.sumOf { it - StackSLImageObjective.INVALID_SCORE }
        val controlElites = HashMap<List<Int>, Double>()
        val controlObjective = StackSLImageObjective(target, dim = FIT_DIM, cache = cache)
        val rng = kotlin.random.Random(SEED)
        repeat(MAPELITES_BUDGET) {
            val e = controlObjective.evaluate(genome.random(rng))
            val cell = binOf(e, axes, bins)
            val incumbent = controlElites[cell]
            if (incumbent == null || e.fitness > incumbent) controlElites[cell] = e.fitness
        }
        val archiveQd = qd(archive.elites.values.map { it.fitness })
        val controlQd = qd(controlElites.values)
        println("[stacksl-evolve]     archive ${archive.elites.size}/${archive.cells} cells, QD ${f(archiveQd)}; random ${controlElites.size} cells, QD ${f(controlQd)}")
        check(
            archiveQd > controlQd,
            "map-elites: QD ${f(archiveQd)} beats the random-sampled archive's ${f(controlQd)} at the same budget"
        )
        val misfiled = archive.elites.entries.count { (cell, elite) -> binOf(elite.evaluation, axes, bins) != cell }
        check(misfiled == 0, "map-elites: all ${archive.elites.size} elites re-bin to their own cell")
        val valid = archive.elites.entries
            .filter { it.value.fitness > StackSLImageObjective.INVALID_SCORE }
            .sortedWith(compareBy({ it.key[1] }, { it.key[0] }))
        check(valid.size >= 12, "map-elites: ${valid.size} occupied cells hold a picture, at least 12")
        montage(
            valid.map { (cell, elite) -> "${cell[0]}${cell[1]}" to objective.render(elite.genome, 96).toImg() },
            columns = 6, cellSide = 106
        ).savePng(File(outDir, "evolve-mapelites-archive.png").path)
    }

    private const val MAPELITES_BUDGET: Int = 400

    private fun binOf(e: Evaluation, axes: List<ClosedFloatingPointRange<Double>>, bins: List<Int>): List<Int> =
        axes.indices.map { d ->
            val axis = axes[d]
            val t = ((e.behaviour[d] - axis.start) / (axis.endInclusive - axis.start)).coerceIn(0.0, 1.0)
            (t * bins[d]).toInt().coerceIn(0, bins[d] - 1)
        }

    // ==========================================================================================
    // 5 — reproducibility
    // ==========================================================================================

    /**
     * The same seed gives the same *program* and the same whole trace; a different seed does not.
     * No score cache here, so this exercises the render path itself.
     */
    private fun figureDeterminism(outDir: File) {
        val target = StackSLTargets.spot(FIT_DIM)
        fun once(seed: Long) = PopulationSearch.evolve(
            StackSLGenome(), StackSLImageObjective(target, dim = FIT_DIM), 160, seed = seed,
            mu = 6, lambda = 12, tournament = 3, crossoverRate = 0.25, immigrantRate = 0.05
        )
        val a = once(7L)
        val b = once(7L)
        val c = once(8L)
        check(a.best!!.genome.text == b.best!!.genome.text, "determinism: seed 7 reproduces the program '${a.best.genome.text}'")
        check(a.trace.contentEquals(b.trace), "determinism: and its whole ${a.trace.size}-point trace")
        check(
            a.improvements.map { "${it.generation}:${it.origin}" } == b.improvements.map { "${it.generation}:${it.origin}" },
            "determinism: and its ${a.improvements.size}-step record, operator by operator"
        )
        check(c.best!!.genome.text != a.best.genome.text, "determinism: seed 8 finds a different program")
        require(outDir.isDirectory)
    }

    // ==========================================================================================
    // Driver
    // ==========================================================================================

    private val figures: List<Triple<String, (File) -> Unit, String?>> = listOf(
        Triple("premise", ::figurePremise, "evolve-premise.png"),
        Triple("targets", ::figureTargets, "evolve-targets.png"),
        Triple("proxy", ::figureProxy, "evolve-proxy.png"),
        Triple("mapelites", ::figureMapElites, "evolve-mapelites-archive.png"),
        Triple("determinism", ::figureDeterminism, null)
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var outPath: String? = System.getenv("STACKSL_EVOLVE_DIR")
        var only: Set<String>? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--out" -> { outPath = args.getOrNull(i + 1); i++ }
                "--only" -> { only = args.getOrNull(i + 1)?.split(',')?.map { it.trim() }?.toSet(); i++ }
                "--budget" -> { budget = args.getOrNull(i + 1)?.toIntOrNull() ?: DEFAULT_BUDGET; i++ }
                "--supersample" -> { supersample = args.getOrNull(i + 1)?.toIntOrNull() ?: DEFAULT_SUPERSAMPLE; i++ }
                "--arms" -> { armLabels = args.getOrNull(i + 1)?.split(',')?.map { it.trim() } ?: DEFAULT_ARMS; i++ }
                "--targets" -> { targetNames = args.getOrNull(i + 1)?.split(',')?.map { it.trim() } ?: DEFAULT_TARGETS; i++ }
                else -> if (outPath == null && !args[i].startsWith("--")) outPath = args[i]
            }
            i++
        }
        val chosenOut = outPath
        val outDir = if (chosenOut != null) File(chosenOut) else java.nio.file.Files.createTempDirectory("stacksl-evolve").toFile()
        outDir.mkdirs()
        println("[stacksl-evolve] output -> ${outDir.absolutePath}")
        println(
            "[stacksl-evolve] fitness ${FIT_DIM}x$FIT_DIM, presentation ${SHOW_DIM}x$SHOW_DIM, $budget evaluations " +
                "per arm on ${targetNames.joinToString(",")}, arms ${armLabels.joinToString(",")}, supersample $supersample, seed $SEED, ${StackSLPalette.DEFAULT.words.size} words (" +
                StackSLWordKind.entries.joinToString(" ") { k -> "$k=${StackSLPalette.DEFAULT.words.count { it.kind == k }}" } +
                ")" + if (atDefaults) "" else " — NOT the default size, floors reported but not asserted"
        )

        val chosen = only
        val selected = figures.filter { chosen == null || it.first in chosen }
        check(selected.isNotEmpty(), "at least one figure selected (--only ${chosen?.joinToString(",")})")
        val started = System.nanoTime()
        for ((name, render, _) in selected) {
            val t = System.nanoTime()
            println("[stacksl-evolve] --- $name ---")
            runCatching { render(outDir) }.onFailure { fail("$name threw: $it"); it.printStackTrace() }
            println("[stacksl-evolve] --- $name done in %.1f s ---".format((System.nanoTime() - t) / 1e9))
        }

        // A figure that stopped writing its PNG fails, and so does a PNG no figure declares.
        val declared = selected.mapNotNull { it.third }.toSortedSet()
        val onDisk = (outDir.listFiles { file -> file.name.endsWith(".png") } ?: emptyArray()).map { it.name }.toSortedSet()
        check(declared == onDisk, "figure ledger: declared $declared, on disk $onDisk")
        for (name in declared) {
            val file = File(outDir, name)
            check(file.length() > 2000, "figure ledger: $name is ${file.length()} bytes, not a stub")
        }

        println("[stacksl-evolve] %d check(s), %d failure(s), %.1f s total".format(checks, failures.size, (System.nanoTime() - started) / 1e9))
        if (failures.isNotEmpty()) {
            println("[stacksl-evolve] FAILURES:")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("stacksl evolve gallery rendered")
    }
}
