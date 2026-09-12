@file:afield.CompletionCategory("Simulation")
package afield.kratos

import afield.stdlib.Evaluation
import afield.stdlib.GenomeOps
import afield.stdlib.Objective
import afield.stdlib.PopulationSearch
import afield.stdlib.SearchRun
import afield.stdlib.Variation
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Run one Kratos session many times over a declared design space, and compare the answers.
 *
 * [KratosSession] and the worker below it answer exactly one question: *given this geometry and
 * these coefficients, what is the field?* That is a simulator. A **study** is the layer that turns
 * it into a design tool. It varies the question, reduces each answer to one number, and reports the
 * shape of the resulting landscape and where its optimum lies.
 *
 * A caller supplies three pieces:
 *
 * - a [StudySpace] — a base design object of any type `D` and the [StudyVariable]s that move it;
 * - a `plan`, mapping a design to the [StudyCase] the solver runs (geometry and coefficients);
 * - a `measure`, reducing a [SimulationResult] to the scalar being optimised.
 *
 * Keeping `plan` separate from `measure` is what lets **geometry be an ordinary axis**. For an
 * embedded formulation a geometric change is a reclassification of the same background grid, with
 * no remesh and no new session, so a sweep over an arm length costs what a sweep over a
 * conductivity costs and the two can be axes of one grid. The domain's `buildCount` stays 1 across
 * a whole sweep, and `KratosStudySmokeTest` fails if it does not. A body-fitted formulation has no
 * reclassification to do instead: [evaluate] refuses a [StudyCase] that carries a geometry there,
 * and a geometric axis needs a session per point rather than a study.
 *
 * # One session solves one design at a time
 *
 * [KratosSession] is a newest-wins preview engine: a second `solveAsync` increments the simulation
 * revision and the first future then completes `null` rather than with a stale field. That is right
 * for a slider and wrong for a study, which needs *every* answer. So [evaluate] awaits each solve
 * before submitting the next, and the study's wall time is the sum of its solves. Submitting in
 * parallel against one session does not shorten it: the session obsoletes all but the last, and
 * each obsoleted solve arrives back here as a failed sample.
 *
 * Two sessions do run two solves at once, at the cost of two workers and two background grids.
 *
 * # Memoisation
 *
 * Every evaluation is cached on a quantised coordinate signature, twelve significant figures per
 * coordinate, so re-running an overlapping grid (a refinement around the optimum, say) re-solves
 * only the points it has not seen. The cache is shared with [optimise], and the search's own budget
 * accounting agrees with it because the [GenomeOps] implementation hands `PopulationSearch` that
 * same key.
 *
 * # An infeasible design is a sample, not an exception
 *
 * A design whose solve throws, or whose `measure` returns null because the probe fell outside the
 * solved region, becomes an **infeasible sample** carrying the reason instead of abandoning the
 * rest of the sweep. The [StudySample] holds that message in its `failure`, and [fitnessOf] scores
 * such a sample as negative infinity, so a search treats it as the worst design it has seen rather
 * than stopping at it.
 */

/**
 * Holds a session, a [StudySpace], and the `plan`/`measure` pair that turns a design into a scalar.
 *
 * One instance is one question. The sample cache lives here, so [grid] and [optimise] on the same
 * study share their solves, and [solveCount] against [cacheHits] says how many the cache saved.
 */
class KratosStudy<D>(
    val session: KratosSession,
    val space: StudySpace<D>,
    /** What the scalar is called, for the figure's caption and the summary line. */
    val objectiveName: String,
    /** What a design means to the solver. */
    val plan: (D) -> StudyCase,
    /** The scalar to optimise, or null for "this design is infeasible". */
    val measure: (D, SimulationResult) -> Double?,
    val goal: StudyGoal = StudyGoal.MINIMISE,
    /** Extra scalars recorded beside the objective — reported, never optimised. */
    val metrics: Map<String, (D, SimulationResult) -> Double> = emptyMap(),
    /** Per-solve deadline. A container worker under x86-64 emulation is an order of magnitude slow. */
    val timeoutSeconds: Long = 300,
    /**
     * Called as each sample lands, cached ones included. Progress for a long sweep.
     *
     * Throwing from here **aborts the sweep**, and deliberately so: it is called outside
     * [evaluate]'s own failure handling, so a throw propagates out of [grid] instead of being
     * recorded as an infeasible point. That is how a UI cancels a sweep in progress without the
     * cancellation being mistaken for a hundred failed designs. Everything solved before the throw
     * stays in the cache and is not re-solved on the next attempt.
     */
    val onSample: ((StudySample) -> Unit)? = null,
) {
    private val cache = LinkedHashMap<String, StudySample>()
    private var solves = 0
    private var hits = 0
    private var solveMillis = 0.0

    /** Distinct designs actually sent to the worker. */
    val solveCount: Int get() = solves

    /** Evaluations served from the cache instead of the solver. */
    val cacheHits: Int get() = hits

    /** Wall time spent inside [KratosSession.solveAsync], milliseconds. */
    val solveMs: Double get() = solveMillis

    /** Mean cost of one solve, milliseconds, or 0 before the first. */
    val meanSolveMs: Double get() = if (solves == 0) 0.0 else solveMillis / solves

    /** The design at these coordinates — clamped into the space, so this never throws on range. */
    fun designAt(coordinates: DoubleArray): D = space.at(coordinates)

    /** What the solver would be asked at these coordinates. The best design's *geometry* lives here. */
    fun caseAt(coordinates: DoubleArray): StudyCase = plan(space.at(coordinates))

    /**
     * Solve one design point, or return the cached sample for it.
     *
     * Coordinates outside a variable's range are clamped rather than rejected: a mutation that
     * steps off the edge of the space is ordinary, and clamping is what makes the boundary of the
     * space a place a search can sit rather than a place it dies.
     */
    fun evaluate(coordinates: DoubleArray): StudySample {
        val clamped = space.clamp(coordinates)
        val key = signature(clamped)
        cache[key]?.let { hit ->
            hits++
            val sample = hit.asCacheHit()
            onSample?.invoke(sample)
            return sample
        }
        val design = space.at(clamped)
        // Counted once, whatever happens after. Incrementing in both the try and the catch would
        // double-count a design whose solve succeeded and whose `measure` then threw.
        var counted = false
        val sample = try {
            val case = plan(design)
            require(case.parameters.formulation == session.formulation) {
                "study plans ${case.parameters.formulation} but the session runs ${session.formulation}"
            }
            val started = System.nanoTime()
            val future = when {
                session.isBodyFitted -> {
                    require(case.geometry == null) {
                        "${session.formulation} solves the mesh the session was opened with; a " +
                            "StudyCase for it must not carry a geometry, and a geometric axis needs " +
                            "a new session per point rather than a study"
                    }
                    session.solveAsync(case.parameters, edit = case.edit)
                }
                else -> {
                    val geometry = case.geometry ?: error(
                        "${session.formulation} classifies geometry against the background grid, so " +
                            "every StudyCase must carry one")
                    session.solveAsync(geometry, case.parameters, edit = case.edit)
                }
            }
            val result = future.get(timeoutSeconds, TimeUnit.SECONDS) ?: error(
                "the session obsoleted this solve; something else is driving the same session")
            val elapsed = (System.nanoTime() - started) / 1e6
            solves++
            counted = true
            solveMillis += elapsed
            val value = measure(design, result)
            StudySample(
                coordinates = clamped,
                objective = value,
                metrics = metrics.mapValues { (_, of) -> of(design, result) },
                solveMs = elapsed,
                cached = false,
                failure = if (value == null) "measure declined this design" else null,
                diagnostics = result.diagnostics,
            )
        } catch (failure: Exception) {
            if (!counted) solves++
            StudySample(
                coordinates = clamped, objective = null, metrics = emptyMap(), solveMs = 0.0,
                cached = false, failure = failure.message ?: failure.toString(), diagnostics = emptyMap(),
            )
        }
        cache[key] = sample
        onSample?.invoke(sample)
        return sample
    }

    /**
     * Solve one design point and hand back the whole field, bypassing the cache.
     *
     * A study keeps scalars, not fields — 121 results at 2,197 nodes is a lot of arrays to hold for
     * two numbers each. When the sweep is done and you want to *look* at the winner, this re-solves
     * it. One extra solve is the honest price of not having hoarded 120 you never opened.
     */
    fun resultAt(coordinates: DoubleArray): SimulationResult {
        val case = caseAt(coordinates)
        val future = if (session.isBodyFitted) session.solveAsync(case.parameters, edit = case.edit)
        else session.solveAsync(case.geometry!!, case.parameters, edit = case.edit)
        return future.get(timeoutSeconds, TimeUnit.SECONDS)
            ?: error("the session obsoleted this solve")
    }

    /**
     * Sample the space on a regular grid, [steps] points along each variable, and report the
     * landscape.
     *
     * Row-major over the variables, last axis fastest, which is the order the samples arrive in and
     * the order [StudyLandscape.samples] holds them.
     */
    fun grid(vararg steps: Int): StudyLandscape = grid(steps.toList())

    fun grid(steps: List<Int>): StudyLandscape {
        require(steps.size == space.dimension) {
            "this space has ${space.dimension} variables; grid needs one step count each, got ${steps.size}"
        }
        require(steps.all { it >= 1 }) { "every step count must be >= 1, got $steps" }
        val axes = space.variables.mapIndexed { i, variable ->
            StudyAxis(variable.name, variable.unit, variable.samples(steps[i]))
        }
        val total = steps.fold(1) { a, b -> a * b }
        val samples = ArrayList<StudySample>(total)
        val index = IntArray(steps.size)
        repeat(total) {
            samples += evaluate(DoubleArray(steps.size) { d -> axes[d].values[index[d]] })
            for (d in steps.indices.reversed()) {
                index[d]++
                if (index[d] < steps[d]) break
                index[d] = 0
            }
        }
        return StudyLandscape(objectiveName, goal, axes, samples)
    }

    /**
     * Search the space rather than tile it, and report the run **against a random control at the
     * same budget**.
     *
     * The control arm is not optional politeness. `PopulationSearch.random` draws from exactly the
     * distribution the evolutionary run mutates within, so the difference between the two is a
     * difference in *selection*; without it, "the optimiser found 0.6 K" says only that the space
     * contains a point worth 0.6 K. This is the same reporting discipline the rest of the
     * repository's searches use.
     *
     * Budget is **evaluations**, and a cache hit costs none — so a converged search that keeps
     * re-proposing its incumbent spends its budget on new designs instead of re-solving one.
     *
     * @param sigma mutation step as a fraction of each variable's range. The default is a global
     *   search; a tenth of that, with [start], is a refinement around a point a grid already found.
     * @param start where to seed the population, or null to draw it. Passing the grid's best with a
     *   small [sigma] is the two-stage workflow a study is actually for — tile coarsely to locate
     *   the interesting region, then search finely inside it. Note that a seeded run's control arm
     *   still draws uniformly from the whole space, which is the comparison worth making: it says
     *   whether the refinement did better than spending the same budget looking again.
     * @param immigrantRate fresh random draws per child. Zero for a refinement, whose whole premise
     *   is that the interesting region is already known.
     */
    fun optimise(
        budget: Int,
        seed: Long = 1L,
        mu: Int = 4,
        lambda: Int = 6,
        sigma: Double = 0.18,
        control: Boolean = true,
        start: DoubleArray? = null,
        immigrantRate: Double = 0.15,
    ): StudyOptimisation<D> {
        require(budget >= 2) { "an optimiser needs a budget of at least 2, got $budget" }
        require(sigma > 0.0 && sigma <= 1.0) { "sigma must lie in (0, 1], got $sigma" }
        val ops = CoordinateOps(sigma)
        val objective = Objective<DoubleArray> { Evaluation(fitnessOf(evaluate(it))) }
        val searched = PopulationSearch.evolve(
            ops, objective, budget, seed = seed, mu = mu, lambda = lambda,
            crossoverRate = 0.3, immigrantRate = immigrantRate,
            seeds = start?.let { listOf(space.clamp(it)) } ?: emptyList(),
            label = if (start == null) "evolve" else "refine",
        )
        val drawn = if (!control) null else PopulationSearch.random(
            ops, objective, budget, seed = seed + 1, label = "random",
        )
        val best = searched.best?.genome?.let { cache[signature(space.clamp(it))] }
        return StudyOptimisation(objectiveName, goal, searched, drawn, best, best?.let { space.at(it.coordinates) })
    }

    /** Higher is better, always: the sense flip lives here and nowhere else. */
    fun fitnessOf(sample: StudySample): Double {
        val value = sample.objective ?: return Double.NEGATIVE_INFINITY
        return if (goal == StudyGoal.MINIMISE) -value else value
    }

    /**
     * The cache key: coordinates at twelve significant figures.
     *
     * Not the raw bits. Two designs that differ in the last ulp are the same design as far as a
     * 2,197-node solve is concerned, and a signature that could tell them apart would make the
     * cache useless to a search that reaches the same point by two routes.
     */
    private fun signature(coordinates: DoubleArray): String =
        coordinates.joinToString(",") { "%.12g".format(it) }

    /** [PopulationSearch] over the coordinate vector, clamped into the space at every step. */
    private inner class CoordinateOps(val sigma: Double) : GenomeOps<DoubleArray> {
        override fun random(rng: Random): DoubleArray = DoubleArray(space.dimension) { d ->
            val v = space.variables[d]
            v.range.start + rng.nextDouble() * (v.range.endInclusive - v.range.start)
        }

        override val mutations: List<Variation<DoubleArray>> = listOf(
            Variation("one axis") { g, rng ->
                val d = rng.nextInt(space.dimension)
                g.copyOf().also { it[d] = step(it[d], d, rng) }
            },
            Variation("all axes") { g, rng ->
                DoubleArray(g.size) { d -> step(g[d], d, rng) }
            },
        )

        override fun crossover(a: DoubleArray, b: DoubleArray, rng: Random): DoubleArray =
            DoubleArray(a.size) { if (rng.nextBoolean()) a[it] else b[it] }

        override fun signature(g: DoubleArray): String = this@KratosStudy.signature(space.clamp(g))

        private fun step(value: Double, d: Int, rng: Random): Double {
            val v = space.variables[d]
            val span = v.range.endInclusive - v.range.start
            return v.clamp(value + rng.nextDouble(-1.0, 1.0) * sigma * span)
        }
    }
}

/** Which end of the objective the study is looking for. */
enum class StudyGoal { MINIMISE, MAXIMISE }

/**
 * One continuous design variable: what to call it, what it ranges over, and how it moves a design.
 *
 * `apply` takes the design object rather than the parameters object because a variable is as
 * entitled to move a geometry as a coefficient. `armEnd` and `conductivity` are the same kind of
 * thing to a study, and that is the point.
 */
class StudyVariable<D>(
    val name: String,
    val range: ClosedFloatingPointRange<Double>,
    val unit: String = "",
    val apply: (D, Double) -> D,
) {
    init {
        require(name.isNotBlank()) { "a study variable needs a name" }
        require(range.start.isFinite() && range.endInclusive.isFinite()) { "$name has a non-finite range" }
        require(range.endInclusive > range.start) { "$name must be an increasing range, got $range" }
    }

    fun clamp(value: Double): Double =
        if (value.isFinite()) value.coerceIn(range.start, range.endInclusive) else range.start

    /** [steps] values across the range, both ends included; a single step is the midpoint. */
    fun samples(steps: Int): List<Double> {
        require(steps >= 1) { "$name needs at least one sample, got $steps" }
        if (steps == 1) return listOf((range.start + range.endInclusive) / 2)
        return (0 until steps).map {
            range.start + (range.endInclusive - range.start) * it / (steps - 1.0)
        }
    }

    val label: String get() = if (unit.isBlank()) name else "$name ($unit)"
}

/** A base design and the variables that move it. `D` is the caller's own type; nothing here inspects it. */
class StudySpace<D>(val base: D, val variables: List<StudyVariable<D>>) {
    init {
        require(variables.isNotEmpty()) { "a study space needs at least one variable" }
        val duplicates = variables.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "study variables must have distinct names: $duplicates" }
    }

    constructor(base: D, vararg variables: StudyVariable<D>) : this(base, variables.toList())

    val dimension: Int get() = variables.size

    fun clamp(coordinates: DoubleArray): DoubleArray {
        require(coordinates.size == dimension) {
            "this space has $dimension variables, got ${coordinates.size} coordinates"
        }
        return DoubleArray(dimension) { variables[it].clamp(coordinates[it]) }
    }

    /** The design at these coordinates, applying every variable in declaration order. */
    fun at(coordinates: DoubleArray): D {
        var design = base
        val clamped = clamp(coordinates)
        for (i in variables.indices) design = variables[i].apply(design, clamped[i])
        return design
    }

    /** The middle of the space — a reasonable place to start a hill climb. */
    fun centre(): DoubleArray =
        DoubleArray(dimension) { (variables[it].range.start + variables[it].range.endInclusive) / 2 }
}

/**
 * What one design means to the solver.
 *
 * [geometry] is null exactly when the session is body-fitted, because there the mesh *is* the
 * geometry and there is nothing to classify.
 */
class StudyCase(
    val parameters: SimulationParameters,
    val geometry: SimulationGeometry? = null,
    val edit: SimulationEdit =
        if (geometry == null) SimulationEdit.MATERIAL else SimulationEdit.GEOMETRY,
)

/** One evaluated design point. [objective] is null when the point is infeasible; [failure] says why. */
class StudySample(
    val coordinates: DoubleArray,
    val objective: Double?,
    val metrics: Map<String, Double>,
    val solveMs: Double,
    val cached: Boolean,
    val failure: String?,
    val diagnostics: Map<String, Any?> = emptyMap(),
) {
    val feasible: Boolean get() = objective != null

    internal fun asCacheHit(): StudySample =
        StudySample(coordinates, objective, metrics, solveMs, true, failure, diagnostics)

    override fun toString(): String = buildString {
        append(coordinates.joinToString(", ", "[", "]") { "%.4g".format(it) })
        append(if (objective == null) " infeasible: $failure" else " -> %.6g".format(objective))
        if (cached) append(" (cached)")
    }
}

/** One axis of a [StudyLandscape]: what it is and the values it was sampled at. */
class StudyAxis(val name: String, val unit: String, val values: List<Double>) {
    val label: String get() = if (unit.isBlank()) name else "$name ($unit)"
    val size: Int get() = values.size
}

/**
 * A regularly sampled design space, row-major over [axes] with the last axis fastest.
 *
 * Any dimension is allowed as data. [matrix] is the two-dimensional reading a heat map needs and
 * says so rather than guessing when the study has some other rank.
 */
class StudyLandscape(
    val objectiveName: String,
    val goal: StudyGoal,
    val axes: List<StudyAxis>,
    val samples: List<StudySample>,
) {
    init {
        val expected = axes.fold(1) { a, b -> a * b.size }
        require(samples.size == expected) { "expected $expected samples for these axes, got ${samples.size}" }
    }

    val dimension: Int get() = axes.size
    val feasible: List<StudySample> get() = samples.filter { it.feasible }

    /** The sample at these axis indices. */
    fun at(vararg index: Int): StudySample {
        require(index.size == dimension) { "expected $dimension indices, got ${index.size}" }
        var flat = 0
        for (d in axes.indices) {
            require(index[d] in 0 until axes[d].size) { "index ${index[d]} out of range on ${axes[d].name}" }
            flat = flat * axes[d].size + index[d]
        }
        return samples[flat]
    }

    /** Best by [goal] over the feasible samples, or null when every point failed. */
    val best: StudySample?
        get() = feasible.let { f ->
            if (goal == StudyGoal.MINIMISE) f.minByOrNull { it.objective!! } else f.maxByOrNull { it.objective!! }
        }

    /** `[row = axis 0][col = axis 1]`, NaN where a point was infeasible. Two-dimensional studies only. */
    fun matrix(): Array<DoubleArray> {
        require(dimension == 2) { "matrix() is the 2-D reading; this landscape has $dimension axes" }
        return Array(axes[0].size) { r ->
            DoubleArray(axes[1].size) { c -> at(r, c).objective ?: Double.NaN }
        }
    }

    /** Extremes over the feasible samples, or `0..1` when there are none. */
    fun range(): Pair<Double, Double> {
        val values = feasible.map { it.objective!! }
        return if (values.isEmpty()) 0.0 to 1.0 else values.min() to values.max()
    }

    fun summary(): String = buildString {
        append("$objectiveName over ")
        append(axes.joinToString(" x ") { "${it.size} ${it.name}" })
        append(": ${feasible.size}/${samples.size} feasible")
        val winner = best
        if (winner != null) {
            append(", best %.6g at ".format(winner.objective))
            append(axes.indices.joinToString(", ") { "${axes[it].name}=%.4g".format(winner.coordinates[it]) })
        }
        append(", %.1f s of solver".format(samples.sumOf { it.solveMs } / 1000.0))
    }

    override fun toString(): String = summary()
}

/**
 * What a search over the space did, and what a random draw of the same size did instead.
 *
 * [run] and [control] carry `PopulationSearch`'s own per-evaluation best-so-far trace, which is
 * what the convergence panel plots and what makes the two directly comparable.
 */
class StudyOptimisation<D>(
    val objectiveName: String,
    val goal: StudyGoal,
    val run: SearchRun<DoubleArray>,
    val control: SearchRun<DoubleArray>?,
    val best: StudySample?,
    val bestDesign: D?,
) {
    /** The trace in the objective's own units and sense, rather than as "higher is better" fitness. */
    fun trace(of: SearchRun<DoubleArray>): DoubleArray =
        DoubleArray(of.trace.size) { if (goal == StudyGoal.MINIMISE) -of.trace[it] else of.trace[it] }

    /**
     * How much better the search finished than the control, in objective units; negative is worse.
     *
     * A difference of fitnesses is a difference of objectives up to the sense flip, and both traces
     * carry the same flip, so this subtraction is already in the caller's units.
     */
    val advantage: Double?
        get() {
            val other = control ?: return null
            val gap = run.bestFitness - other.bestFitness
            return if (gap.isFinite()) gap else null
        }

    fun summary(): String = buildString {
        append(run.summary())
        control?.let { append("\n"); append(it.summary()) }
        best?.let { append("\nbest: $it") }
    }

    override fun toString(): String = summary()
}
