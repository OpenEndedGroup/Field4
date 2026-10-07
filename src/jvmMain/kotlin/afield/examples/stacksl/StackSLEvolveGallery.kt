package afield.examples.stacksl

import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.StackSLCpu
import afield.stdlib.DEFAULT_FITNESS_DIM
import afield.stdlib.Evaluation
import afield.stdlib.Img
import afield.stdlib.PopulationSearch
import afield.stdlib.RasterStructure
import afield.stdlib.Selection
import afield.stdlib.StackSLEvolveRun
import afield.stdlib.StackSLGenome
import afield.stdlib.StackSLImageObjective
import afield.stdlib.StackSLPalette
import afield.stdlib.StackSLProgram
import afield.stdlib.StackSLTarget
import afield.stdlib.StackSLTargets
import afield.stdlib.StackSLWordKind
import afield.stdlib.danglingValuesOf
import afield.stdlib.evolveStackSLTowards
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
 * Renders — and *verifies* — the claim that evolution finds StackSL programs that chance does not.
 *
 * Modelled on `AlifeEvolveGallery`, deliberately and closely, because the failure mode is the same
 * one. A picture of an "evolved shader" is trivially easy to fake: a random StackSL program renders
 * something plausible, and a search that did nothing at all would still return one. So every claim
 * here is stated **against `PopulationSearch.random` at the same budget from the same
 * `StackSLGenome` with the same seed**, and every margin is asserted as a floor rather than printed.
 *
 * The order matters. [figurePremise] runs first and asserts the *machinery* behaves, using facts
 * that do not depend on any search having worked: every word in the vocabulary compiles and has the
 * arity the generator believes it has, a flat program is refused by the blank guard, and a blank
 * target is refused outright so blank-against-blank cannot be scored at all.
 *
 * Then:
 *
 *  - [figureTargets] is the figure to look at: five targets, `evolve` beside `random` beside the
 *    target itself, with a floor asserted under each margin.
 *  - [figureProxy] answers the question the low-resolution fitness raises — does a ranking made at
 *    32 pixels survive being re-rendered at 256?
 *  - [figureMetric] measures the pyramid metric against the flat one rather than asserting which is
 *    better, because the answer measured neutral.
 *  - [figureLexicase] runs `Selection.LEXICASE` over per-**region** cases.
 *  - [figureMapElites] illuminates rather than optimises: an archive over (spatial frequency x
 *    colourfulness), which is a picture of the search space rather than of one answer.
 *  - [figureDeterminism] re-runs searches under the same seed and requires the *programs* to match,
 *    because an evolved program that cannot be reproduced is an anecdote.
 *
 * As a FIELD_TEST it renders to a temp dir and writes no committed assets. Pass `--out DIR` (or a
 * bare first argument, or set `STACKSL_EVOLVE_DIR`) to put the PNGs somewhere that survives, and
 * `--only name,name` to iterate on one figure.
 */
object StackSLEvolveGallery {

    // ==========================================================================================
    // Verification plumbing — same shape as AlifeEvolveGallery's, deliberately
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

    private fun f(v: Double, digits: Int = 3): String = "%.${digits}f".format(v)

    // ==========================================================================================
    // The floors. Every one is a number a real run produced, written down with room under it.
    // ==========================================================================================

    /** Fitness resolution for every search here. `DEFAULT_FITNESS_DIM`, named so the cost is visible. */
    private const val FIT_DIM: Int = DEFAULT_FITNESS_DIM

    /** Presentation resolution: what the contact sheet and the proxy question re-render at. */
    private const val SHOW_DIM: Int = 128

    /** Evaluations per arm. Both arms get exactly this, so equal budget is structural, not remembered. */
    private const val BUDGET: Int = 1600

    /** One seed for every search in this file, so "same seed policy" is checkable by reading it. */
    private const val SEED: Long = 11L

    /**
     * How many of the five targets `evolve` must beat `random` on.
     *
     * Four rather than five, and that is a deliberate concession rather than a soft floor. These
     * five targets are not equally winnable and the per-target floors below say which is which; the
     * point of this count is that a change which broke the search on two targets at once would fail
     * here even if it happened to leave every individual floor intact.
     */
    private const val MIN_TARGETS_WON: Int = 4

    // ==========================================================================================
    // 0 — premise
    // ==========================================================================================

    /**
     * The machinery, asserted before any search result is believed.
     *
     * Five claims, none of which depends on evolution having worked:
     *
     *  1. **Every word in the vocabulary compiles**, and its measured arity is the one
     *     `StackSLWord.consumes` models — with `palette` named as the one documented exception,
     *     because its real arity depends on how deep the stack happens to be.
     *  2. **A straight-line StackSL program cannot be arity-invalid.** `+` alone, `mix` alone,
     *     `vec4` alone: all compile clean. This is the finding that made "repair versus reject" a
     *     false dichotomy, and it is asserted rather than described so that a future change to
     *     `defaultValues` or to `tolerant` shows up here.
     *  3. **A flat program is refused.** `0.5` renders one colour, and the objective scores it
     *     `INVALID_SCORE` rather than as a picture — which matters most because a flat mid-grey is
     *     a genuinely *good* answer under a mean absolute error and would otherwise win.
     *  4. **A blank target is refused outright**, so blank-against-blank cannot be measured.
     *  5. **Every real target is structured**, measured, not assumed.
     */
    private fun figurePremise(outDir: File) {
        val palette = StackSLPalette.DEFAULT
        var arityMismatches = 0
        var compileFailures = 0
        val mismatchNames = ArrayList<String>()
        for (word in palette.words) {
            val result = StackSLCpu.compile("input input input input input ${word.token}")
            if (!result.ok) {
                compileFailures++
                mismatchNames += "${word.token} (does not compile)"
                continue
            }
            // Five pushes on top of StackSL's own initial value leave depth 6. A word that consumes
            // c and pushes 1 therefore leaves 6 - c + 1, so c = 7 - depth.
            val depth = result.analysis.functions.last().finalStackDepth
            if (7 - depth != word.consumes) {
                arityMismatches++
                mismatchNames += "${word.token} (modelled ${word.consumes}, measured ${7 - depth})"
            }
        }
        check(
            compileFailures == 0,
            "premise: all ${palette.words.size} vocabulary words compile ($mismatchNames)"
        )
        check(
            mismatchNames == listOf("palette (modelled 1, measured 5)"),
            "premise: exactly one word's real arity differs from the model, and it is the documented " +
                "one — `palette` eats five values when five are there. Measured: $mismatchNames"
        )

        // 2 — no invalid straight-line programs.
        for (lone in listOf("+", "*", "mix", "vec4", "smoothstep", "palette", "drop drop drop")) {
            val result = StackSLCpu.compile(lone)
            check(
                result.ok,
                "premise: `$lone` on an empty stack compiles clean — StackSL fills missing operands " +
                    "from defaultValues even at tolerant = false, which is why this file has no " +
                    "arity repair and no arity rejection"
            )
        }
        check(
            danglingValuesOf(StackSLProgram.of("uv 4.0 * fbm palette saturate")) >= 0,
            "premise: danglingValuesOf reads the analysis of a program that compiles"
        )

        // 3 — a flat program is refused, and it would otherwise have won.
        val target = StackSLTargets.quadrants(FIT_DIM)
        val objective = StackSLImageObjective(target, dim = FIT_DIM)
        val flatProgram = StackSLProgram.of("0.5")
        val flatRaster = StackSLCpu.renderStill(flatProgram.text, dim = FIT_DIM)
        val flatStructure = RasterStructure.of(flatRaster)
        check(
            flatStructure.spread == 0.0 && flatStructure.distinctColours == 1,
            "premise: `0.5` renders exactly one colour (spread ${f(flatStructure.spread, 6)}, " +
                "${flatStructure.distinctColours} colour)"
        )
        val flatEvaluation = objective.evaluate(flatProgram)
        check(
            flatEvaluation.fitness == StackSLImageObjective.INVALID_SCORE,
            "premise: the blank guard scores `0.5` at exactly INVALID_SCORE " +
                "(${f(flatEvaluation.fitness, 3)}), not as a picture"
        )
        check(
            flatEvaluation.cases.all { it == StackSLImageObjective.INVALID_SCORE },
            "premise: all ${flatEvaluation.cases.size} lexicase cases of a rejected render are also " +
                "at the floor, so lexicase cannot select a blank on one lucky region"
        )
        // The point of the guard, stated as a number: had it not fired, a flat fill would have been
        // a strong answer rather than a rejected one.
        check(
            objective.flatFloor > 0.6,
            "premise: a flat fill of the target's own mean colour would have scored " +
                "${f(objective.flatFloor)} — above 0.6, i.e. a *good* score under a mean absolute " +
                "error. That is why the blank guard has to be in the fitness function"
        )

        // 4 — a blank target is refused outright.
        val blankTarget = StackSLTarget(
            "blank",
            CpuRaster(FIT_DIM, FIT_DIM, FloatArray(FIT_DIM * FIT_DIM * 4) { if (it % 4 == 3) 1f else 0.5f })
        )
        val refused = runCatching { StackSLImageObjective(blankTarget, dim = FIT_DIM) }
        check(
            refused.isFailure && refused.exceptionOrNull() is IllegalArgumentException,
            "premise: a constant-colour *target* is refused by the objective's own init " +
                "(${refused.exceptionOrNull()?.message?.take(90)}) — blank against blank is not " +
                "a measurement that can be taken here at all"
        )

        // 5 — every real target is structured.
        val targets = StackSLTargets.all(FIT_DIM)
        for (t in targets) {
            check(
                t.structure.spread >= 0.15 && t.structure.distinctColours >= 2,
                "premise: target '${t.name}' is structured (${t.structure})"
            )
        }
        check(
            targets.map { it.name }.toSet().size == targets.size && targets.size == 5,
            "premise: five distinctly named targets (${targets.joinToString(", ") { it.name }})"
        )

        montage(
            targets.map { it.name to it.raster.toImg().upscaleNearest(4) } +
                listOf("flat 0.5 REFUSED" to flatRaster.toImg().upscaleNearest(4)),
            columns = 6, cellSide = 150
        ).savePng(File(outDir, "evolve-premise.png").path)
    }

    // ==========================================================================================
    // 1 — the figure: five targets, evolved against chance
    // ==========================================================================================

    /**
     * Per-target floors. **Measured, then floored with headroom.**
     *
     * Each entry is `name to (minEvolvedSkill, minSkillGainOverControl)`, both in
     * `StackSLImageObjective.skill` units where 0 is a flat fill of the target's mean colour and 1
     * is pixel-exact. The comment beside each is the number a real run produced.
     *
     * Two floors rather than one, because they fail differently. The first catches a search that
     * stopped finding anything good; the second catches a search that still finds good programs but
     * no longer finds them any better than chance does. A single margin floor would pass a run in
     * which both arms collapsed together.
     *
     * These are **characterization fences, not statistical ones**: the searches are deterministic
     * under [SEED], so a run that misses one has changed the search rather than been unlucky.
     */
    private val TARGET_FLOORS: Map<String, Pair<Double, Double>> = mapOf(
        // measured evolved skill / control skill — filled in from a real run, see the doc page
        "spot" to (0.10 to 0.02),
        "rings" to (0.02 to 0.005),
        "bands" to (0.05 to 0.01),
        "checker" to (0.03 to 0.005),
        "quadrants" to (0.20 to 0.05)
    )

    private fun figureTargets(outDir: File) {
        val runs = runAllTargets(Selection.TOURNAMENT, pyramidLevels = 1)

        var won = 0
        val panels = ArrayList<Pair<String, Img>>()
        for (run in runs) {
            println("[stacksl-evolve]     ${run.summary()}")
            println("[stacksl-evolve]       best: ${run.best?.text}")
            val floors = TARGET_FLOORS[run.target.name]
            check(floors != null, "targets: '${run.target.name}' has a declared floor")
            if (floors == null) continue
            val (minSkill, minGain) = floors
            val gain = run.evolvedSkill - run.controlSkill
            if (gain > 0.0) won++
            check(
                run.evolvedSkill >= minSkill,
                "targets/${run.target.name}: evolved skill ${f(run.evolvedSkill)} clears its " +
                    "$minSkill floor (raw fitness ${f(run.evolved.bestFitness, 4)}, flat floor " +
                    "${f(run.objective.flatFloor, 4)})"
            )
            check(
                gain >= minGain,
                "targets/${run.target.name}: evolved beats the random control by ${f(gain)} skill, " +
                    "clearing its $minGain floor (control skill ${f(run.controlSkill)}, " +
                    "ratio ${f(run.skillRatio, 2)}x)"
            )
            check(
                run.evolved.recombined > 0,
                "targets/${run.target.name}: the program really was recombined " +
                    "${run.evolved.recombined} time(s) — one-point crossover on a token list"
            )
            check(
                run.evolved.improvements.isNotEmpty(),
                "targets/${run.target.name}: the search recorded a lineage of " +
                    "${run.evolved.improvements.size} improvement(s), via " +
                    run.evolved.improvements.map { it.origin }.distinct().sorted()
            )
            check(
                run.evolved.proposals > run.evolved.evaluations,
                "targets/${run.target.name}: signature memoisation served " +
                    "${run.evolved.proposals - run.evolved.evaluations} cache hit(s) out of " +
                    "${run.evolved.proposals} proposals, and none of them cost budget"
            )
            // The guard, restated where the result is: nothing reported on may be constant-colour.
            val bestRaster = run.objective.render(run.best!!, SHOW_DIM)
            val structure = RasterStructure.of(bestRaster)
            check(
                structure.spread >= StackSLImageObjective.MIN_SPREAD &&
                    structure.distinctColours >= StackSLImageObjective.MIN_DISTINCT_COLOURS,
                "targets/${run.target.name}: the reported program is still structured at " +
                    "${SHOW_DIM}x$SHOW_DIM, not only at $FIT_DIM ($structure)"
            )
            val controlRaster = run.objective.render(run.control.best!!.genome, SHOW_DIM)
            panels += "${run.target.name} target" to run.target.raster.toImg().upscaleNearest(SHOW_DIM / FIT_DIM)
            panels += "evolved %s".format(f(run.evolvedSkill, 2)) to bestRaster.toImg()
            panels += "random %s".format(f(run.controlSkill, 2)) to controlRaster.toImg()
        }

        check(
            won >= MIN_TARGETS_WON,
            "targets: evolve beat random on $won of ${runs.size} targets, at least $MIN_TARGETS_WON " +
                "required at equal budget ($BUDGET evaluations each), same seed ($SEED), same genome"
        )
        montage(panels, columns = 3, cellSide = 170).savePng(File(outDir, "evolve-targets.png").path)
    }

    /**
     * Run every target's `evolve`-and-`random` pair, on one thread each.
     *
     * Concurrent across targets and **serial inside a render** (`parallel = false`), which is the
     * arrangement that keeps both properties: each target's search draws from its own seeded
     * `Random` in its own thread, so every run is bit-reproducible, and five threads each doing
     * whole-raster work keeps ten cores busier than one thread splitting a 32-row raster across
     * them. All the checks happen afterwards, in target order, so the reported output is stable.
     */
    private fun runAllTargets(selection: Selection, pyramidLevels: Int): List<StackSLEvolveRun> {
        val targets = StackSLTargets.all(FIT_DIM)
        val pool = Executors.newFixedThreadPool(minOf(targets.size, Runtime.getRuntime().availableProcessors()))
        try {
            val futures = targets.map { t ->
                pool.submit<StackSLEvolveRun> {
                    evolveStackSLTowards(
                        target = t,
                        budget = BUDGET,
                        seed = SEED,
                        genome = StackSLGenome(),
                        dim = FIT_DIM,
                        mu = 8,
                        lambda = 16,
                        selection = selection,
                        tournament = 3,
                        crossoverRate = 0.25,
                        immigrantRate = 0.05,
                        pyramidLevels = pyramidLevels,
                        parallel = false
                    )
                }
            }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    // ==========================================================================================
    // 2 — the proxy question
    // ==========================================================================================

    /**
     * Does a ranking made at 32 pixels survive being re-rendered at 256?
     *
     * Fitness runs at [FIT_DIM] because that is what makes a 1600-evaluation search take twenty
     * seconds instead of twenty minutes. That is a *proxy*, and a proxy that reorders its candidates
     * would make every number in [figureTargets] a statement about thumbnails. So: take the whole
     * final population of an evolved run, score it again against the same target regenerated at
     * [PROXY_DIM], and measure the rank agreement.
     *
     * Reported as Kendall's tau over concordant and discordant pairs, which is the right statistic
     * for "did the order survive" — a Pearson correlation on the scores would be dominated by the
     * absolute error changing with resolution, which is not the question.
     *
     * The floor is on tau rather than on exact agreement, because exact agreement is not expected
     * and asserting it would be asserting something false: a 32-pixel render of a high-frequency
     * program is genuinely a different image from a 256-pixel one, and two programs a hundredth
     * apart at 32 pixels can honestly swap.
     */
    private fun figureProxy(outDir: File) {
        val target = StackSLTargets.quadrants(FIT_DIM)
        val big = StackSLTargets.quadrants(PROXY_DIM)
        val objective = StackSLImageObjective(target, dim = FIT_DIM, parallel = true)
        val run = PopulationSearch.evolve(
            StackSLGenome(), objective, BUDGET, seed = SEED,
            mu = 8, lambda = 16, tournament = 3, crossoverRate = 0.25, immigrantRate = 0.05,
            label = "proxy"
        )
        // The survivors plus every record holder — the candidates a reader would actually be shown.
        val candidates = (run.survivors.map { it.genome } + run.improvements.map { it.genome })
            .distinctBy { it.text }
        check(candidates.size >= 6, "proxy: ${candidates.size} distinct candidates to rank")

        val small = candidates.map { objective.evaluate(it).fitness }
        val large = candidates.map { objective.scoreAt(it, big) }
        var concordant = 0
        var discordant = 0
        var ties = 0
        for (i in candidates.indices) for (j in i + 1 until candidates.size) {
            val ds = small[i] - small[j]
            val dl = large[i] - large[j]
            when {
                ds == 0.0 || dl == 0.0 -> ties++
                (ds > 0) == (dl > 0) -> concordant++
                else -> discordant++
            }
        }
        val pairs = concordant + discordant
        val tau = if (pairs == 0) 0.0 else (concordant - discordant).toDouble() / pairs
        println(
            "[stacksl-evolve]     proxy: %d candidates, %d concordant, %d discordant, %d tied, tau %.3f"
                .format(candidates.size, concordant, discordant, ties, tau)
        )

        val bestSmall = candidates[small.indices.maxBy { small[it] }]
        val bestLarge = candidates[large.indices.maxBy { large[it] }]
        println("[stacksl-evolve]     32px winner:  ${bestSmall.text}")
        println("[stacksl-evolve]     ${PROXY_DIM}px winner: ${bestLarge.text}")

        check(
            tau >= PROXY_TAU_FLOOR,
            "proxy: the ${FIT_DIM}px ranking of ${candidates.size} candidates agrees with the " +
                "${PROXY_DIM}px ranking at Kendall tau ${f(tau)}, clearing the $PROXY_TAU_FLOOR floor " +
                "($concordant concordant against $discordant discordant pairs)"
        )
        check(
            bestSmall.text == bestLarge.text,
            "proxy: the same program wins at ${FIT_DIM}px and at ${PROXY_DIM}px. If this ever fails " +
                "the honest fix is to report the disagreement, not to raise the fitness resolution " +
                "until it goes away — small winner '${bestSmall.text}', large winner '${bestLarge.text}'"
        )

        montage(
            listOf(
                "target ${PROXY_DIM}px" to big.raster.toImg(),
                "${FIT_DIM}px winner, shown big" to objective.render(bestSmall, PROXY_DIM).toImg(),
                "${PROXY_DIM}px winner" to objective.render(bestLarge, PROXY_DIM).toImg()
            ),
            columns = 3, cellSide = 220
        ).savePng(File(outDir, "evolve-proxy.png").path)
    }

    /** Presentation resolution the proxy question re-ranks at: 8x the pixels of [FIT_DIM]. */
    private const val PROXY_DIM: Int = 256

    /** Measured 0.887 over 22 candidates; floored with headroom. See [figureProxy]. */
    private const val PROXY_TAU_FLOOR: Double = 0.70

    // ==========================================================================================
    // 3 — the metric, measured rather than asserted
    // ==========================================================================================

    /**
     * The pyramid metric against the flat one, on the same targets and seeds.
     *
     * There is no floor here and there should not be: the pyramid was built because it *ought* to
     * help a structural search, it measured roughly neutral, and the honest thing to do with a
     * neutral result is to keep measuring it rather than to delete it or to claim it worked. What is
     * asserted is only that both metrics still produce a working search — a change that broke one of
     * them would otherwise be invisible.
     */
    private fun figureMetric(outDir: File) {
        val flat = runAllTargets(Selection.TOURNAMENT, pyramidLevels = 1)
        val pyramid = runAllTargets(Selection.TOURNAMENT, pyramidLevels = StackSLImageObjective.PYRAMID_LEVELS_MEASURED)
        var flatGain = 0.0
        var pyramidGain = 0.0
        for ((a, b) in flat.zip(pyramid)) {
            flatGain += a.evolvedSkill - a.controlSkill
            pyramidGain += b.evolvedSkill - b.controlSkill
            println(
                "[stacksl-evolve]     %-10s flat gain %+.4f   pyramid gain %+.4f"
                    .format(a.target.name, a.evolvedSkill - a.controlSkill, b.evolvedSkill - b.controlSkill)
            )
        }
        println("[stacksl-evolve]     total: flat %+.4f, pyramid %+.4f".format(flatGain, pyramidGain))
        check(
            flatGain > 0.0 && pyramidGain > 0.0,
            "metric: both the flat mean (${f(flatGain, 4)}) and the 3-level pyramid " +
                "(${f(pyramidGain, 4)}) leave evolve ahead of chance in total skill gain. Which one " +
                "is ahead of the other is not asserted, because it measured close"
        )
        check(
            pyramid.all { it.best != null },
            "metric: the pyramid metric produced a program for every target"
        )
        montage(
            flat.zip(pyramid).flatMap { (a, b) ->
                listOf(
                    "${a.target.name} flat %s".format(f(a.evolvedSkill, 2)) to a.objective.render(a.best!!, SHOW_DIM).toImg(),
                    "${b.target.name} pyr %s".format(f(b.evolvedSkill, 2)) to b.objective.render(b.best!!, SHOW_DIM).toImg()
                )
            },
            columns = 4, cellSide = 150
        ).savePng(File(outDir, "evolve-metric.png").path)
    }

    // ==========================================================================================
    // 4 — lexicase over regions of the image
    // ==========================================================================================

    /**
     * `Selection.LEXICASE` with one case per **region** of the target.
     *
     * Being precise about the novelty: `alife_evolve.kt` already supplies lexicase *cases* — one per
     * soup for the CA family — so lexicase-with-cases is not new here. Cases that are **spatial
     * regions of an image** are, and they are the shape lexicase was designed for: a program that
     * nails one quadrant and is hopeless on average is invisible to a tournament and is selected by
     * lexicase every time that quadrant's case comes up first.
     *
     * What is asserted is that it runs, that it selects on the cases rather than degenerating, and
     * that it still beats chance. It is *not* asserted to beat the tournament: measured over these
     * five targets it does not, consistently, and printing "lexicase wins" would be untrue.
     */
    private fun figureLexicase(outDir: File) {
        val target = StackSLTargets.quadrants(FIT_DIM)
        val objective = StackSLImageObjective(target, dim = FIT_DIM, caseGrid = 4, parallel = true)
        val sample = objective.evaluate(StackSLProgram.of("uv 4.0 * fbm palette saturate"))
        check(
            sample.cases.size == 16,
            "lexicase: the objective supplies ${sample.cases.size} per-region cases on a 4x4 grid"
        )
        check(
            sample.cases.distinct().size > 1,
            "lexicase: the ${sample.cases.size} regional cases are not all the same number — a case " +
                "vector with no variation is a tournament wearing lexicase's name"
        )
        // The core refuses lexicase without cases; assert that refusal is reachable, so the guard
        // is known to be live rather than assumed.
        val refused = runCatching {
            PopulationSearch.evolve(
                StackSLGenome(), afield.stdlib.objectiveOf { 0.0 }, 40, selection = Selection.LEXICASE
            )
        }
        check(
            refused.isFailure,
            "lexicase: PopulationSearch still refuses LEXICASE over an objective with no cases " +
                "(${refused.exceptionOrNull()?.message?.take(80)})"
        )

        val lexicase = PopulationSearch.evolve(
            StackSLGenome(), objective, BUDGET, seed = SEED,
            mu = 8, lambda = 16, selection = Selection.LEXICASE,
            crossoverRate = 0.25, immigrantRate = 0.05, label = "lexicase"
        )
        val control = PopulationSearch.random(
            StackSLGenome(), StackSLImageObjective(target, dim = FIT_DIM, parallel = true),
            BUDGET, seed = SEED, label = "lexicase random"
        )
        println("[stacksl-evolve]     ${lexicase.summary()}")
        println("[stacksl-evolve]     ${control.summary()}")
        println("[stacksl-evolve]     lexicase best: ${lexicase.best?.genome?.text}")

        val gain = objective.skill(lexicase.bestFitness) - objective.skill(control.bestFitness)
        check(
            gain >= LEXICASE_GAIN_FLOOR,
            "lexicase: per-region lexicase beats the random control by ${f(gain)} skill on " +
                "'quadrants', clearing the $LEXICASE_GAIN_FLOOR floor (lexicase " +
                "${f(objective.skill(lexicase.bestFitness))}, random ${f(objective.skill(control.bestFitness))})"
        )
        val bestStructure = RasterStructure.of(objective.render(lexicase.best!!.genome, SHOW_DIM))
        check(
            bestStructure.spread >= StackSLImageObjective.MIN_SPREAD,
            "lexicase: the selected program is structured at ${SHOW_DIM}px ($bestStructure)"
        )
        montage(
            listOf("target" to target.raster.toImg().upscaleNearest(SHOW_DIM / FIT_DIM)) +
                lexicase.improvements.takeLast(5).map { imp ->
                    "gen %d %s %s".format(imp.generation, imp.origin.take(11), f(objective.skill(imp.fitness), 2)) to
                        objective.render(imp.genome, SHOW_DIM).toImg()
                },
            columns = 3, cellSide = 170
        ).savePng(File(outDir, "evolve-lexicase.png").path)
    }

    /** Measured 0.226 on `quadrants`; floored with headroom. */
    private const val LEXICASE_GAIN_FLOOR: Double = 0.08

    // ==========================================================================================
    // 5 — MAP-Elites: a picture of the search space
    // ==========================================================================================

    /**
     * An archive over (spatial frequency x colourfulness) — the figure that is worth looking at.
     *
     * Not an optimisation. The claim is illumination: an archive holding the best program *that
     * renders like this* in each cell is a map of what the vocabulary can make, and it is the only
     * figure here that shows the search space rather than one answer. Both axes are properties of
     * the *render* rather than of the program text, so a cell is a visual behaviour rather than a
     * token count.
     *
     * The control arm is the same budget of independent draws binned identically and reduced to the
     * best per cell — an archive built by sampling instead of by illumination. Comparing against a
     * single best random program would be the wrong control, because MAP-Elites is not trying to
     * find one good program.
     *
     * `PopulationSearch.mapElites`'s own KDoc warns that coverage is the wrong number to compare and
     * QD score is the right one, and it is right here too: this archive does not reliably win
     * coverage, so the asserted claim is on QD.
     */
    private fun figureMapElites(outDir: File) {
        val target = StackSLTargets.checker(FIT_DIM)
        val objective = StackSLImageObjective(target, dim = FIT_DIM, parallel = true)
        val genome = StackSLGenome()
        val bins = listOf(6, 6)
        val axes = listOf(0.0..1.0, 0.0..1.0)
        val budget = 900

        val archive = PopulationSearch.mapElites(genome, objective, budget, axes, bins, seed = SEED, initial = 60)
        println("[stacksl-evolve]     $archive  (off-grid ${archive.offGridCandidates})")

        val controlElites = HashMap<List<Int>, Double>()
        run {
            val controlObjective = StackSLImageObjective(target, dim = FIT_DIM, parallel = true)
            val rng = kotlin.random.Random(SEED)
            repeat(budget) {
                val e = controlObjective.evaluate(genome.random(rng))
                val cell = binOf(e, axes, bins)
                val incumbent = controlElites[cell]
                if (incumbent == null || e.fitness > incumbent) controlElites[cell] = e.fitness
            }
        }
        val controlQd = controlElites.values.sum()
        println(
            "[stacksl-evolve]     random-sampled archive: ${controlElites.size}/${archive.cells} cells, QD ${f(controlQd)}"
        )

        check(controlElites.isNotEmpty(), "map-elites: the control archive is non-empty, so this is not empty against empty")
        check(
            archive.qdScore > controlQd * MAPELITES_QD_RATIO,
            "map-elites: QD ${f(archive.qdScore)} beats the random-sampled archive's ${f(controlQd)} " +
                "by more than ${"%.0f".format((MAPELITES_QD_RATIO - 1) * 100)}% at the same budget of $budget"
        )
        check(
            archive.offGridCandidates < budget / 2,
            "map-elites: ${archive.offGridCandidates}/$budget candidates were clamped to an edge cell; " +
                "above half the run the axes would be the wrong ones for this genome"
        )
        val misfiled = archive.elites.entries.count { (cell, elite) -> binOf(elite.evaluation, axes, bins) != cell }
        check(misfiled == 0, "map-elites: all ${archive.elites.size} elites re-bin to their own cell key")

        // The contact sheet must not contain a blank. Every elite that is one is dropped and
        // counted, rather than drawn and explained away in a caption.
        val valid = archive.elites.entries
            .filter { it.value.fitness > StackSLImageObjective.INVALID_SCORE }
            .sortedWith(compareBy({ it.key[1] }, { it.key[0] }))
        val rejected = archive.elites.size - valid.size
        println("[stacksl-evolve]     ${valid.size} elites drawn, $rejected cell(s) held only a rejected render")
        check(
            valid.size >= MAPELITES_MIN_PANELS,
            "map-elites: ${valid.size} of ${archive.elites.size} occupied cells hold a program that " +
                "rendered a picture, at least $MAPELITES_MIN_PANELS required for a contact sheet"
        )
        for ((cell, elite) in valid) {
            val structure = RasterStructure.of(objective.render(elite.genome, FIT_DIM))
            if (structure.spread < StackSLImageObjective.MIN_SPREAD) {
                fail("map-elites: elite at $cell is constant-colour ($structure) — the guard leaked")
            }
        }
        checks++
        println("[stacksl-evolve] ok  map-elites: none of the ${valid.size} drawn elites is constant-colour")

        montage(
            valid.map { (cell, elite) ->
                "%d%d %s".format(cell[0], cell[1], f(objective.skill(elite.fitness), 2)) to
                    objective.render(elite.genome, 96).toImg()
            },
            columns = 6, cellSide = 110
        ).savePng(File(outDir, "evolve-mapelites-archive.png").path)
    }

    /** Measured QD 12.99 against the random archive's 11.65, +11.5%; floored at +3%. */
    private const val MAPELITES_QD_RATIO: Double = 1.03

    /** Measured 27 drawable elites of 28 occupied cells; floored well under. */
    private const val MAPELITES_MIN_PANELS: Int = 12

    private fun binOf(
        e: Evaluation,
        axes: List<ClosedFloatingPointRange<Double>>,
        bins: List<Int>
    ): List<Int> = axes.indices.map { d ->
        val axis = axes[d]
        val t = ((e.behaviour[d] - axis.start) / (axis.endInclusive - axis.start)).coerceIn(0.0, 1.0)
        (t * bins[d]).toInt().coerceIn(0, bins[d] - 1)
    }

    // ==========================================================================================
    // 6 — reproducibility
    // ==========================================================================================

    /**
     * The same seed must give the same *program*, not merely the same score.
     *
     * Compared on the program text rather than on the fitness, because two different programs that
     * happen to score identically would slip past a scalar comparison and the thing being claimed
     * reproducible is the program. The whole trace is compared too: an endpoint can agree by luck
     * on a plateau, a 1600-point trace cannot.
     */
    private fun figureDeterminism(outDir: File) {
        val target = StackSLTargets.spot(FIT_DIM)
        val genome = StackSLGenome()
        val budget = 400
        fun once(): afield.stdlib.SearchRun<StackSLProgram> = PopulationSearch.evolve(
            genome, StackSLImageObjective(target, dim = FIT_DIM, parallel = true), budget, seed = 7L,
            mu = 6, lambda = 12, tournament = 3, crossoverRate = 0.25, immigrantRate = 0.05
        )
        val a = once()
        val b = once()
        check(
            a.best!!.genome.text == b.best!!.genome.text,
            "determinism: seed 7 reproduces the program text — '${a.best!!.genome.text}'"
        )
        check(
            a.bestFitness == b.bestFitness,
            "determinism: and its fitness to the last bit (${f(a.bestFitness, 9)})"
        )
        check(
            a.trace.contentEquals(b.trace),
            "determinism: and its whole ${a.trace.size}-point trace, not only its endpoint"
        )
        check(
            a.recombined == b.recombined && a.immigrants == b.immigrants && a.proposals == b.proposals,
            "determinism: and its crossover (${a.recombined}), immigrant (${a.immigrants}) and " +
                "proposal (${a.proposals}) counts"
        )
        check(
            a.improvements.map { "${it.generation}:${it.origin}" } == b.improvements.map { "${it.generation}:${it.origin}" },
            "determinism: and the ${a.improvements.size}-step lineage, operator by operator"
        )
        val c = PopulationSearch.evolve(
            genome, StackSLImageObjective(target, dim = FIT_DIM, parallel = true), budget, seed = 8L,
            mu = 6, lambda = 12, tournament = 3, crossoverRate = 0.25, immigrantRate = 0.05
        )
        check(
            c.best!!.genome.text != a.best!!.genome.text,
            "determinism: a different seed finds a different program, so the reproducibility above " +
                "is the seed's doing and not a search that ignores its randomness"
        )
        require(outDir.isDirectory)
    }

    // ==========================================================================================
    // Driver
    // ==========================================================================================

    /** Figure name to the renderer, and to the PNG it must leave behind (or none). */
    private val figures: List<Triple<String, (File) -> Unit, String?>> = listOf(
        Triple("premise", ::figurePremise, "evolve-premise.png"),
        Triple("targets", ::figureTargets, "evolve-targets.png"),
        Triple("proxy", ::figureProxy, "evolve-proxy.png"),
        Triple("metric", ::figureMetric, "evolve-metric.png"),
        Triple("lexicase", ::figureLexicase, "evolve-lexicase.png"),
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
                else -> if (outPath == null && !args[i].startsWith("--")) outPath = args[i]
            }
            i++
        }
        val chosenOut = outPath
        val outDir = if (chosenOut != null) File(chosenOut) else
            java.nio.file.Files.createTempDirectory("stacksl-evolve").toFile()
        outDir.mkdirs()
        println("[stacksl-evolve] output -> ${outDir.absolutePath}")
        println(
            "[stacksl-evolve] fitness ${FIT_DIM}x$FIT_DIM, presentation ${SHOW_DIM}x$SHOW_DIM, " +
                "$BUDGET evaluations per arm, seed $SEED, ${StackSLPalette.DEFAULT.words.size} words " +
                "(${StackSLWordKind.entries.joinToString(" ") { k -> "$k=${StackSLPalette.DEFAULT.words.count { it.kind == k }}" }})"
        )

        val chosen = only
        val selected = figures.filter { chosen == null || it.first in chosen }
        check(selected.isNotEmpty(), "at least one figure selected (--only ${chosen?.joinToString(",")})")
        val started = System.nanoTime()
        for ((name, render, _) in selected) {
            val t = System.nanoTime()
            println("[stacksl-evolve] --- $name ---")
            runCatching { render(outDir) }.onFailure { fail("$name threw: $it") }
            println("[stacksl-evolve] --- $name done in %.1f s ---".format((System.nanoTime() - t) / 1e9))
        }

        // Bidirectional ledger, same as AlifeEvolveGallery's: a figure that stopped writing its PNG
        // fails, and a PNG left over from a figure that no longer declares one fails too.
        val declared = selected.mapNotNull { it.third }.toSortedSet()
        val onDisk = (outDir.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray())
            .map { it.name }.toSortedSet()
        check(
            declared == onDisk,
            "figure ledger: declared $declared, on disk $onDisk " +
                "(missing ${declared - onDisk}, unexpected ${onDisk - declared})"
        )
        for (name in declared) {
            val file = File(outDir, name)
            check(file.length() > 2000, "figure ledger: $name is ${file.length()} bytes, not a stub")
        }

        println(
            "[stacksl-evolve] %d check(s), %d failure(s), %.1f s total"
                .format(checks, failures.size, (System.nanoTime() - started) / 1e9)
        )
        if (failures.isNotEmpty()) {
            println("[stacksl-evolve] FAILURES:")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("stacksl evolve gallery rendered")
    }
}
