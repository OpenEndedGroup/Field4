@file:afield.CompletionCategory("Simulation")
package afield.kratos

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One parameter study over a heated part, in the three sizes a demo has to exist in.
 *
 * [KratosStudyDemo] is the experiment as a spec: the design space, the objective, the two reported
 * metrics and a closed-form check on the answer. It owns no runner of its own, because three things
 * have to agree on the same experiment: the full run below, the catalog smoke beside it, and the
 * loadable document in `dev_docs/agents/transcripts/kratos_parameter_study.yaml`. [KratosStudyRun]
 * is that full run, driving a real Kratos worker through a coarse grid, a refinement seeded from
 * it, a global search against a random control, and finally the fine grid the figures are drawn
 * from. [KratosStudySmokeTest] is the same code path at catalog size, and is the version the test
 * catalog runs.
 *
 * The two design axes are one geometric (how far the arm extends) and one material (its
 * conductivity), and the study treats them identically. Because `thermal.stationary` is embedded,
 * moving the geometric axis reclassifies the same background grid rather than remeshing it: both
 * the full run and the smoke assert that the grid is built exactly once across a whole sweep, so a
 * sweep over a shape costs what a sweep over a coefficient costs.
 *
 * The objective is one whose right answer is known in advance. Stationary conduction is
 * linear in the source and inverse in the conductivity, so the designs that put a target
 * temperature at a chosen point form a curve with a closed form, and
 * [KratosStudyDemo.verifyValley] holds every column of the sampled grid against it.
 */

/**
 * The flagship study, as a reusable spec: **which arm length and which conductivity put a chosen
 * temperature at a chosen point in the part?**
 *
 * An object rather than a script, because three things need to agree on the same experiment: the
 * `jvm-main` verification run in this file, the catalog smoke beside it, and the loadable Field
 * document in `dev_docs/agents/transcripts/kratos_parameter_study.yaml`. A demo whose numbers drift
 * from the numbers in the docs is worse than no demo.
 *
 * # Why this question, and how its answer is checkable
 *
 * Stationary conduction is linear in the source and inverse in the conductivity, so at any point
 * `T = T_boundary + (q / k) · φ(geometry)`, where `φ` solves `−∇²φ = 1` with `φ = 0` on the skin. A
 * *target* temperature is therefore the level set
 *
 * ```
 * k = q · φ(armEnd) / (T_target − T_boundary)
 * ```
 *
 * — a curve through the design plane, and one bent **only** by the geometric axis. That gives the
 * landscape a valley whose shape is the geometric response `φ(armEnd)` drawn directly, and it makes
 * every column of the grid checkable in closed form against a single solve at that `armEnd`:
 * [verifyValley] does exactly that, and it is the reason this objective was chosen over a prettier
 * one that could only be admired.
 *
 * The geometric axis is not decoration either. `thermal.stationary` is embedded, so a new `armEnd`
 * is a **reclassification of the same background grid** — `buildCount` stays 1 across the whole
 * sweep, which the run asserts. A sweep over a shape costs exactly what a sweep over a coefficient
 * costs, and that is the property that makes geometry an ordinary study axis.
 */
object KratosStudyDemo {

    /** Metres. Inside the arm for every `armEnd` in [ARM], and clear of the base, whose x ends at 0.23. */
    val PROBE = SimulationProbe("arm", 0.30, 0.0, 0.0)

    /** W/m³, held fixed: with `k` on an axis, moving `q` too would only rescale the same landscape. */
    const val SOURCE = 100.0

    /** K, the Nitsche skin temperature. */
    const val BOUNDARY = 293.15

    /**
     * K, the temperature the design is asked to deliver at [PROBE] — one kelvin above the skin.
     *
     * Chosen after the exploratory corner sweep (`--explore`) reported what is even reachable, so
     * that the level set crosses the **interior** of the grid at every arm length rather than
     * running off an edge. Measured on the container worker, 2026-09-09:
     *
     * | arm | k = 0.8 | k = 6.0 |
     * | --- | --- | --- |
     * | 0.35 m | 294.677 K | 293.354 K |
     * | 0.85 m | 299.228 K | 293.960 K |
     *
     * The valley is interior at every arm length exactly when the target lies above the hottest
     * temperature the stiffest conductivity can still reach (293.960 K, the long arm) and below the
     * coolest the softest one produces (294.677 K, the short arm) — a window barely three quarters
     * of a kelvin wide, worth knowing before choosing a round number. 294.15 K sits inside it and is
     * exactly [BOUNDARY] + 1 K, so the question reads as
     * "which designs raise this point exactly one kelvin above its skin?" and the required
     * conductivity `k* = q·φ / 1 K` sweeps 1.22 → 4.86 W/(m·K) across the arm axis — most of the
     * width of the material axis, which is what makes the figure worth looking at.
     *
     * Picking a target blind and then discovering the valley was off the map would have been the
     * same experiment with a worse figure, so the exploratory pass is part of the method and not a
     * false start.
     */
    const val TARGET = 294.15

    val ARM: ClosedFloatingPointRange<Double> = 0.35..0.85
    val CONDUCTIVITY: ClosedFloatingPointRange<Double> = 0.8..6.0

    /** Domain and discretisation: the configuration `VERIFICATION.md` already measured. */
    val BOUNDS = SimulationBounds(-1.0, -1.0, -1.0, 1.0, 1.0, 1.0)
    const val RESOLUTION = 12

    /** The design object. Two numbers, one geometric and one material, treated identically. */
    data class Design(val armEnd: Double = 0.6, val conductivity: Double = 2.0)

    fun space(): StudySpace<Design> = StudySpace(
        Design(),
        StudyVariable("arm end", ARM, "m") { d, v -> d.copy(armEnd = v) },
        StudyVariable("conductivity", CONDUCTIVITY, "w per m k") { d, v -> d.copy(conductivity = v) },
    )

    /**
     * What one design means to the solver: this body, these coefficients, a geometric edit.
     *
     * Separate from [study] so that a test — or a box that only wants to *draw* a candidate — can
     * ask what would be sent without owning a worker.
     */
    fun plan(design: Design): StudyCase = StudyCase(
        parameters = ThermalParameters(
            conductivity = design.conductivity, source = SOURCE, boundaryTemperature = BOUNDARY),
        geometry = KratosDemo.body(design.armEnd),
    )

    fun objectiveName(target: Double = TARGET): String = "miss from ${"%.2f".format(target)} k"

    /** The two scalars recorded beside the objective. Reported, never optimised. */
    fun metrics(): Map<String, (Design, SimulationResult) -> Double> = mapOf(
        "probe" to { _, result -> result.sample(PROBE).value ?: Double.NaN },
        "peak" to { _, result -> result.range("temperature").second },
    )

    /**
     * The study: minimise `|T(probe) − target|`.
     *
     * A design whose probe samples `null` — outside the domain, or with no active support — is
     * **infeasible**, not zero. That cannot happen for this body over this range, and the study says
     * so honestly rather than by never testing for it.
     */
    fun study(
        session: KratosSession,
        target: Double = TARGET,
        onSample: ((StudySample) -> Unit)? = null,
    ): KratosStudy<Design> = KratosStudy(
        session = session,
        space = space(),
        objectiveName = objectiveName(target),
        goal = StudyGoal.MINIMISE,
        plan = ::plan,
        measure = { _, result -> result.sample(PROBE).value?.let { abs(it - target) } },
        metrics = metrics(),
        onSample = onSample,
    )

    /**
     * Recover the geometric response `φ` from one sample and predict where that column's valley is.
     *
     * `φ = (T_probe − T_boundary) · k / q`, and the conductivity that hits the target is then
     * `k* = q · φ / (T_target − T_boundary)`. Both come out of numbers the sweep already recorded,
     * so this is a closed-form cross-check of the figure and not a second simulation to be trusted.
     */
    fun predictedConductivity(sample: StudySample, target: Double = TARGET): Double? {
        val probe = sample.metrics["probe"] ?: return null
        if (!probe.isFinite()) return null
        val conductivity = sample.coordinates[1]
        val phi = (probe - BOUNDARY) * conductivity / SOURCE
        return SOURCE * phi / (target - BOUNDARY)
    }

    /**
     * Check every column's measured minimum against the closed form, and report the worst gap.
     *
     * Reported in **grid cells**, because the grid cannot resolve a valley finer than its own
     * spacing: a prediction landing half a cell from the sampled minimum is the best agreement the
     * measurement can express, and quoting it in W/(m·K) would hide that ceiling.
     */
    fun verifyValley(landscape: StudyLandscape, target: Double = TARGET): List<String> {
        require(landscape.dimension == 2)
        val arms = landscape.axes[0]
        val conductivities = landscape.axes[1]
        val step = if (conductivities.size < 2) 1.0
        else (conductivities.values.last() - conductivities.values.first()) / (conductivities.size - 1)
        return (0 until arms.size).map { r ->
            val column = (0 until conductivities.size).map { c -> landscape.at(r, c) }
            val measured = column.filter { it.feasible }.minByOrNull { it.objective!! }
            val predicted = column.firstOrNull { it.feasible }?.let { predictedConductivity(it, target) }
            if (measured == null || predicted == null) {
                "arm %.3f: no feasible sample".format(arms.values[r])
            } else {
                val gap = abs(measured.coordinates[1] - predicted) / step
                "arm %.3f  measured k*=%.3f  closed form k*=%.3f  gap %.2f cells".format(
                    arms.values[r], measured.coordinates[1], predicted, gap)
            }
        }
    }
}

/**
 * Drive [KratosStudyDemo] through a real worker and write the figures.
 *
 * Not in the FIELD_TEST catalog: a full 11x11 grid plus an optimiser and its control is several
 * hundred solves, which is a nightly experiment and not a smoke. [KratosStudySmokeTest] beside it is
 * the catalog-sized version of the same code path.
 *
 * ```
 * ./gradlew jvmMainRun -PmainClass=afield.kratos.KratosStudyRun --args="--grid 11 11 --budget 40"
 * ```
 *
 * Arguments, all optional: `--out <dir>` (default `build/kratos-studies`), `--grid <rows> <cols>`,
 * `--budget <n>` for the global optimiser (0 disables it), `--coarse <n>` for the cheap grid the
 * refinement starts from, `--refine <n>` for that refinement (0 disables either), `--target
 * <kelvin>`, and `--explore` to solve
 * only the four corners and report the achievable probe temperatures, which is how [KratosStudyDemo.TARGET]
 * was chosen.
 */
object KratosStudyRun {
    /** Green, distinct from the search's blue and the control's orange. */
    private const val REFINE_COLOUR = 0xFF7BE38B.toInt()

    @JvmStatic fun main(args: Array<String>) {
        val options = Options(args)
        val found = Kratos.probeAny().get(600, TimeUnit.SECONDS)
        println("launch=${found.config.resolvedLaunch()}")
        if (!found.available) {
            println("Kratos study skipped (SKIPPED: optional environment absent): ${found.capability.message}")
            return
        }
        val budget = if (found.config.resolvedLaunch() is KratosLaunch.Container) 900L else 120L
        Files.createDirectories(options.out)

        KratosSession.openAsync(KratosStudyDemo.BOUNDS, KratosStudyDemo.RESOLUTION, found.config)
            .get(budget, TimeUnit.SECONDS).use { session ->
                println("background ${session.domain.id} · ${session.domain.nodeCount} nodes · " +
                    "${session.domain.cellCount} tetrahedra · builds ${session.domain.buildCount}")
                if (options.explore) { explore(session, options); return }
                sweep(session, options)
            }
    }

    /** Four corners only: what probe temperature is even reachable, and therefore what target to ask for. */
    private fun explore(session: KratosSession, options: Options) {
        val study = KratosStudyDemo.study(session, options.target)
        val arms = listOf(KratosStudyDemo.ARM.start, KratosStudyDemo.ARM.endInclusive)
        val ks = listOf(KratosStudyDemo.CONDUCTIVITY.start, KratosStudyDemo.CONDUCTIVITY.endInclusive)
        val probes = ArrayList<Double>()
        for (arm in arms) for (k in ks) {
            val sample = study.evaluate(doubleArrayOf(arm, k))
            val probe = sample.metrics["probe"] ?: Double.NaN
            probes += probe
            println("corner arm=%.3f k=%.3f  probe=%.4f K  peak=%.4f K  %.0f ms"
                .format(arm, k, probe, sample.metrics["peak"] ?: Double.NaN, sample.solveMs))
        }
        val finite = probes.filter { it.isFinite() }
        if (finite.isNotEmpty()) {
            println("achievable probe temperature %.3f .. %.3f K".format(finite.min(), finite.max()))
            println("a target in the interior would be about %.1f K"
                .format((finite.min() + finite.max()) / 2))
        }
        println("mean solve %.0f ms over ${study.solveCount} solves".format(study.meanSolveMs))
        println("Kratos study explore done")
    }

    private fun sweep(session: KratosSession, options: Options) {
        var index = 0
        val study = KratosStudyDemo.study(session, options.target) { sample ->
            index++
            if (index % 10 == 0 || !sample.feasible) println("  [$index] $sample")
        }
        // ------------------------------------------------------------------------------------
        // Stage 1: a coarse grid, then a refinement seeded from it.
        //
        // This is the comparison the whole exercise is for, and it is run FIRST so that neither the
        // coarse grid nor the refinement can see an answer the fine grid already found. The claim
        // being tested is that a study does not have to tile finely to answer finely.
        // ------------------------------------------------------------------------------------
        val coarse = if (options.coarse <= 0) null else {
            println("COARSE GRID ${options.coarse}x${options.coarse}")
            study.grid(options.coarse, options.coarse).also { println("  ${it.summary()}") }
        }
        val coarseSolves = study.solveCount

        // mu = 1 on purpose: a (1+lambda) climb spends every evaluation near the seed. A larger mu
        // would fill its initial population with uniform draws from the whole space, which is the
        // opposite of what "refine around this point" means.
        val refinement = if (options.refine <= 0 || coarse?.best == null) null else {
            println()
            println("REFINEMENT ${options.refine} evaluations seeded at the coarse grid's best")
            study.optimise(options.refine, seed = 11L, mu = 1, lambda = 4, sigma = 0.03,
                control = false, start = coarse.best!!.coordinates, immigrantRate = 0.0)
                .also { println("  ${it.run.summary()}"); println("  best: ${it.best}") }
        }
        val refineSolves = study.solveCount

        // ------------------------------------------------------------------------------------
        // Stage 2: a search with no prior information at all, and its random control.
        // ------------------------------------------------------------------------------------
        val optimisation = if (options.budget <= 0) null else {
            println()
            println("GLOBAL SEARCH ${options.budget} evaluations, against a random control")
            study.optimise(options.budget, seed = 7L).also {
                println("  ${it.run.summary()}")
                it.control?.let { c -> println("  ${c.summary()}") }
                println("  advantage over the control: %+.6g (positive means the search won)"
                    .format(it.advantage ?: Double.NaN))
            }
        }
        val searchSolves = study.solveCount

        // ------------------------------------------------------------------------------------
        // Stage 3: the fine grid — the reference answer, and the landscape the figure draws.
        // ------------------------------------------------------------------------------------
        println()
        val startedGrid = System.nanoTime()
        val landscape = study.grid(options.rows, options.cols)
        val gridSeconds = (System.nanoTime() - startedGrid) / 1e9
        println("FINE GRID ${landscape.summary()}")
        println("  wall %.1f s · %d solves so far · mean %.0f ms".format(
            gridSeconds, study.solveCount, study.meanSolveMs))
        check(session.domain.buildCount == 1) { "the background grid was rebuilt during the sweep" }
        println("NO_REMESH background=${session.domain.id} buildCount=${session.domain.buildCount} " +
            "over ${study.solveCount} solves")

        println()
        println("CLOSED FORM CHECK — the valley against k* = q phi / (T - Tb)")
        val checks = KratosStudyDemo.verifyValley(landscape, options.target)
        checks.forEach { println("  $it") }

        val solvesBeforeReplay = study.solveCount
        val hitsBeforeReplay = study.cacheHits
        val replay = study.grid(options.rows, options.cols)
        check(study.solveCount == solvesBeforeReplay) { "a repeated grid re-solved something" }
        println("CACHE re-running the same grid cost ${study.solveCount - solvesBeforeReplay} " +
            "new solves and ${study.cacheHits - hitsBeforeReplay} hits; " +
            "best unchanged at ${replay.best?.objective}")

        // The table the whole run exists to print: what each method reached, and what it cost.
        //
        // Budgeted in **evaluations**, because that is what the methods were given and what makes
        // them comparable; distinct solves are reported once at the bottom, and differ only by the
        // cache hits (the refinement's seed, for one, was already solved by the coarse grid).
        println()
        println("METHOD COMPARISON — objective (lower is better) against evaluations spent")
        fun row(label: String, value: Double?, evaluations: Int) =
            println("  %-30s %12s  %4d evaluations".format(
                label, value?.let { "%.6g".format(it) } ?: "-", evaluations))
        val coarseCells = options.coarse * options.coarse
        coarse?.let { row("coarse ${options.coarse}x${options.coarse} grid", it.best?.objective, coarseCells) }
        refinement?.let { row("  + refinement", it.best?.objective, coarseCells + options.refine) }
        optimisation?.let {
            row("global search, no prior", it.best?.objective, options.budget)
            it.control?.let { c ->
                row("random control, same budget", it.trace(c).lastOrNull(), options.budget)
            }
        }
        row("fine ${options.rows}x${options.cols} grid", landscape.best?.objective, options.rows * options.cols)
        println("  coarse+refine spent $refineSolves distinct solves of the " +
            "${coarseCells + options.refine} evaluations it was given; the two search arms together " +
            "spent ${searchSolves - refineSolves}")
        println("  ${study.solveCount} distinct designs solved in all, ${study.cacheHits} served from cache")

        // Stretched, and captioned as such: the miss spans 5 K over the whole space but under 1 K
        // along the short-arm edge, and a linear ramp renders that edge as one flat shade with the
        // valley invisible inside it.
        val scale = ColourScale.STRETCHED
        val extra = listOfNotNull(refinement?.let {
            StudyTrace("refine", it.trace(it.run), REFINE_COLOUR)
        })
        val sheet = KratosStudyFigure.sheet(landscape, optimisation, scale = scale, extra = extra)
        val landscapeOnly = KratosStudyFigure.landscape(
            landscape, refinement ?: optimisation, scale = scale)
        write(options.out.resolve("kratos-study-sheet.png"), sheet)
        write(options.out.resolve("kratos-study-landscape.png"), landscapeOnly)
        optimisation?.let {
            write(options.out.resolve("kratos-study-convergence.png"),
                KratosStudyFigure.convergence(it, landscape.best?.objective, extra = extra))
        }

        // The winner, as geometry and as a field, which is the other half of what a study owes:
        // not only "this design is best" but "here is the design, and here is what it does".
        landscape.best?.let { winner ->
            val design = study.designAt(winner.coordinates)
            println()
            println("BEST $design  ${landscape.objectiveName}=%.6g  probe=%.4f K  peak=%.4f K".format(
                winner.objective, winner.metrics["probe"], winner.metrics["peak"]))
            val result = study.resultAt(winner.coordinates)
            val slice = result.sliceMesh(samples = 96)
            println("best slice: ${slice.vertexCount} vertices, ${slice.indices.size / 3} triangles; " +
                "temperature ${"%.3f".format(result.range("temperature").first)}-" +
                "${"%.3f".format(result.range("temperature").second)} K")
            val skin = KratosDemo.body(design.armEnd).surface()
            println("best geometry: ${skin.vertexCount} vertices, ${skin.indices.size / 3} triangles")
        }
        println()
        println("total solver time %.1f s over ${study.solveCount} solves (${study.cacheHits} cache hits)"
            .format(study.solveMs / 1000.0))
        println("Kratos study sweep done")
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes)
        println("wrote $path (${bytes.size} bytes)")
    }

    private class Options(args: Array<String>) {
        var out: Path = Path.of("build/kratos-studies").toAbsolutePath()
        var rows = 11
        var cols = 11
        var budget = 40
        var coarse = 5
        var refine = 30
        var target = KratosStudyDemo.TARGET
        var explore = false

        init {
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--out" -> { out = Path.of(args[++i]).toAbsolutePath() }
                    "--grid" -> { rows = args[++i].toInt(); cols = args[++i].toInt() }
                    "--budget" -> { budget = args[++i].toInt() }
                    "--refine" -> { refine = args[++i].toInt() }
                    "--coarse" -> { coarse = args[++i].toInt() }
                    "--target" -> { target = args[++i].toDouble() }
                    "--explore" -> explore = true
                    else -> error("unknown argument ${args[i]}")
                }
                i++
            }
        }
    }
}

/*
FIELD_TEST
id: kratos.study.parameter-sweep
runner: jvm-main
entrypoint: afield.kratos.KratosStudySmokeTest
tags: [smoke, kratos, optional, simulation, study]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "Kratos study smoke passed"
*/
/**
 * The catalog-sized version of [KratosStudyRun]: a 3x3 grid and a budget-6 search on a real worker.
 *
 * What it is actually pinning, beyond "it ran": that a multi-solve sweep never rebuilds the
 * background grid, that the cache serves a repeated grid without a single new solve, that a
 * landscape with more than one distinct value produces a figure with more than one distinct colour,
 * and that the search's best agrees with the grid's to within a grid cell. Every one of those is a
 * property of the study layer rather than of Kratos, and every one of them fails silently if
 * nothing checks it.
 */
object KratosStudySmokeTest {
    @JvmStatic fun main(args: Array<String>) {
        val found = Kratos.probeAny().get(600, TimeUnit.SECONDS)
        println("launch=${found.config.resolvedLaunch()}")
        if (!found.available) {
            println("Kratos study smoke passed (SKIPPED: optional environment absent)")
            return
        }
        val budget = if (found.config.resolvedLaunch() is KratosLaunch.Container) 600L else 60L
        // Never a committed path: a FIELD_TEST that writes into the tree is a dirty working copy.
        val out = Files.createTempDirectory("field-kratos-study-")
        KratosSession.openAsync(KratosStudyDemo.BOUNDS, KratosStudyDemo.RESOLUTION, found.config)
            .get(budget, TimeUnit.SECONDS).use { session ->
                val study = KratosStudyDemo.study(session)
                val landscape = study.grid(3, 3)
                println("GRID ${landscape.summary()}")
                check(landscape.feasible.size == 9) { "every corner of this space should be feasible" }
                check(session.domain.buildCount == 1) { "the sweep rebuilt the background grid" }

                val solved = study.solveCount
                study.grid(3, 3)
                check(study.solveCount == solved) { "a repeated grid re-solved ${study.solveCount - solved}" }
                check(study.cacheHits == 9) { "expected 9 cache hits, got ${study.cacheHits}" }

                // Physics, not plumbing: at a fixed geometry, a higher conductivity is a colder probe.
                val cold = landscape.at(1, 2).metrics.getValue("probe")
                val hot = landscape.at(1, 0).metrics.getValue("probe")
                check(hot > cold + 1e-6) { "raising conductivity must lower the probe: $hot vs $cold" }

                val optimisation = study.optimise(6, seed = 3L, mu = 2, lambda = 3, control = false)
                println(optimisation.summary())
                check(optimisation.best != null) { "the search found nothing" }

                val raster = KratosStudyFigure.sheetRaster(landscape, optimisation)
                val distinct = raster.pixels.toHashSet().size
                check(distinct > 12) { "the figure has only $distinct distinct colours; it is blank" }
                val png = out.resolve("smoke.png")
                Files.write(png, raster.png())
                check(Files.size(png) > 4096) { "the figure is suspiciously small" }
                println("figure $distinct distinct colours, ${Files.size(png)} bytes at $png")
                println("solves ${study.solveCount} · hits ${study.cacheHits} · " +
                    "mean %.0f ms".format(study.meanSolveMs))
                println("Kratos study smoke passed")
            }
    }
}
