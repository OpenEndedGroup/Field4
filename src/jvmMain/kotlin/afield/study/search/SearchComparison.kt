package afield.study.search

import afield.graphics.color.Color4
import afield.media.image.Img
import afield.media.image.withLabel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Equal-budget comparison of search drivers for any [GenomeOps] and [Objective]: "does search beat
 * random sampling here?"
 *
 * Every arm gets exactly the same number of objective calls at every seed, and the result keeps each
 * run's per-evaluation [SearchRun.trace], so the arms can be compared at any budget up to the one they
 * ran at. [compareSearch] runs it; `braitenberg_vehicles.md` §10 and `alife_examples.md` ("Does search
 * beat random?") read it.
 */

/**
 * One search driver as an arm of a [SearchComparison]: a [name] and how to run it for a budget at a seed.
 */
class SearchArm<G>(
    /** Short name for tables, plots and [SearchComparison.of]. */
    val name: String,
    /** Run the driver on `ops` and `objective` for exactly `budget` evaluations from `seed`. */
    val run: (ops: GenomeOps<G>, objective: Objective<G>, budget: Int, seed: Long) -> SearchRun<G>,
) {
    /** The [name]. */
    override fun toString(): String = name

    companion object {
        /** The control: independent draws from [GenomeOps.random]. */
        fun <G> random(): SearchArm<G> = SearchArm("random") { o, f, b, s -> PopulationSearch.random(o, f, b, seed = s) }

        /** [PopulationSearch.hillClimb], restarting after [patience] non-improving steps. */
        fun <G> hillClimb(patience: Int = 30): SearchArm<G> =
            SearchArm("hillclimb") { o, f, b, s -> PopulationSearch.hillClimb(o, f, b, seed = s, patience = patience) }

        /** [PopulationSearch.evolve], (µ + λ) with tournament 2. */
        fun <G> evolve(
            mu: Int = 6,
            lambda: Int = 12,
            crossoverRate: Double = 0.3,
            immigrantRate: Double = 0.0,
            name: String = if (immigrantRate > 0.0) "immigrants" else "evolve",
        ): SearchArm<G> = SearchArm(name) { o, f, b, s ->
            PopulationSearch.evolve(o, f, b, seed = s, mu = mu, lambda = lambda, crossoverRate = crossoverRate, immigrantRate = immigrantRate)
        }

        /**
         * The four arms `braitenberg_vehicles.md` §10 settled on: random, hill climbing, µ6/λ12 evolution,
         * and µ12/λ24 evolution with 25% immigrants (the one variant that beat random on vehicle brains).
         */
        fun <G> standard(): List<SearchArm<G>> = listOf(
            random(), hillClimb(), evolve(),
            evolve(mu = 12, lambda = 24, immigrantRate = 0.25),
        )
    }
}

/** One arm's run at one seed. */
class SearchComparisonCell<G>(
    /** The arm's name. */
    val arm: String,
    /** The search seed. */
    val seed: Long,
    /** The run, with its per-evaluation [SearchRun.trace]. */
    val run: SearchRun<G>,
)

/**
 * [compareSearch]'s result: every arm at every seed, all at [budget] evaluations.
 */
class SearchComparison<G>(
    /** What was searched, for the table header. */
    val label: String,
    /** Evaluations each run was allowed. */
    val budget: Int,
    /** Arm names, in run order; the first is the control the others are scored against. */
    val arms: List<String>,
    /** Every run, seed-major. */
    val cells: List<SearchComparisonCell<G>>,
) {
    /** The runs of [arm], in seed order. */
    fun of(arm: String): List<SearchComparisonCell<G>> = cells.filter { it.arm == arm }

    /** Seeds per arm. */
    val seeds: Int get() = of(arms.first()).size

    /** Best fitness of each seed's run of [arm]. */
    fun best(arm: String): DoubleArray = of(arm).map { it.run.bestFitness }.toDoubleArray()

    /** Best-so-far of each seed's run of [arm] after [evaluations] evaluations (≤ [budget]). */
    fun bestAt(arm: String, evaluations: Int): DoubleArray =
        of(arm).map { it.run.trace[min(evaluations, it.run.trace.size) - 1] }.toDoubleArray()

    /** Seeds at which [a] ended strictly above [b], optionally after only [evaluations]. */
    fun wins(a: String, b: String, evaluations: Int = budget): Int {
        val x = bestAt(a, evaluations)
        val y = bestAt(b, evaluations)
        return x.indices.count { x[it] > y[it] }
    }

    /** Median per-seed best of [arm] after [evaluations]. */
    fun median(arm: String, evaluations: Int = budget): Double = quantile(bestAt(arm, evaluations).sortedArray(), 0.5)

    /** The arm with the highest median after [evaluations]. */
    fun leader(evaluations: Int = budget): String = arms.maxBy { median(it, evaluations) }

    /** The best genome any run of [arm] found. */
    fun champion(arm: String): G = of(arm).maxBy { it.run.bestFitness }.run.best!!.genome

    /** One line per arm: median, IQR, min, max of the per-seed bests at [evaluations], and wins against the control. */
    fun rows(evaluations: Int = budget): String = buildString {
        val control = arms.first()
        for (a in arms) {
            val b = bestAt(a, evaluations).sortedArray()
            append("  %-10s median %.4f  IQR %.4f..%.4f  range %.4f..%.4f".format(
                a, quantile(b, 0.5), quantile(b, 0.25), quantile(b, 0.75), b.first(), b.last()))
            if (a != control) append("  beats %s at %d/%d".format(control, wins(a, control, evaluations), b.size))
            append('\n')
        }
    }

    /** [rows] under a header naming the label, budget and seed count. */
    fun table(evaluations: Int = budget): String = "%s, %d evals x %d seeds\n".format(label, evaluations, seeds) + rows(evaluations)

    /**
     * The same runs read at several budgets (each ≤ [budget]): one row per budget, each arm's median and
     * wins against the control. Equal to running each budget separately (pinned by `SearchComparisonTest`),
     * because none of the drivers schedules around its budget.
     */
    fun budgetTable(budgets: List<Int>): String = buildString {
        append("%-7s".format("evals"))
        for (a in arms) append("  %20s".format(a))
        append('\n')
        for (n in budgets) {
            append("%-7d".format(n))
            for (a in arms) {
                val cell = if (a == arms.first()) "%.4f".format(median(a, n)) else "%.4f (%d/%d)".format(median(a, n), wins(a, arms.first(), n), seeds)
                append("  %20s".format(cell))
            }
            append('\n')
        }
    }

    /** Per-seed bests, one row per seed. */
    fun perSeed(): String = buildString {
        append("seed")
        for (a in arms) append("  %10s".format(a))
        append('\n')
        val byArm = arms.map { of(it) }
        for (i in byArm[0].indices) {
            append("%4d".format(byArm[0][i].seed))
            for (r in byArm) append("  %10.4f".format(r[i].run.bestFitness))
            append('\n')
        }
    }

    /** Best-so-far of [arm] after each evaluation across seeds: `[q25, median, q75]` rows. */
    fun curve(arm: String): Array<DoubleArray> {
        val runs = of(arm)
        return Array(budget) { t ->
            val v = runs.map { it.run.trace[min(t, it.run.trace.size - 1)] }.sorted().toDoubleArray()
            doubleArrayOf(quantile(v, 0.25), quantile(v, 0.5), quantile(v, 0.75))
        }
    }

    /** The run of [arm] whose best fitness is the median over seeds (the lower middle for an even count). */
    fun medianRun(arm: String): SearchRun<G> {
        val runs = of(arm).sortedBy { it.run.bestFitness }
        return runs[(runs.size - 1) / 2].run
    }

    /** The fitness range [plot] uses when none is given: every arm's quartile band. */
    fun fitnessRange(): ClosedFloatingPointRange<Double> {
        val all = arms.flatMap { a -> curve(a).flatMap { it.toList() } }.filter { it.isFinite() }
        val lo = all.minOrNull() ?: 0.0
        val hi = max(lo + 1e-9, all.maxOrNull() ?: 1.0)
        return lo..hi
    }

    /**
     * Best-so-far fitness against evaluations: each arm's median as a line, its interquartile range as a
     * band, its final median written at the right. Arms take [palette] in order (grey for the control,
     * then blue, orange, green). Pass the same [yRange] to two plots to put them on one scale.
     */
    fun plot(width: Int = 300, height: Int = 300, yRange: ClosedFloatingPointRange<Double> = fitnessRange()): Img {
        var out = Img.filled(width, height, 3, 0.06f)
        val curves = arms.associateWith { curve(it) }
        val lo = yRange.start
        val hi = max(lo + 1e-9, yRange.endInclusive)
        val colours = arms.withIndex().associate { (i, a) -> a to palette[i % palette.size] }
        val left = 32
        val right = width - 40
        val top = 8
        val bottom = height - 18
        fun px(t: Int) = (left + t.toDouble() / (budget - 1).coerceAtLeast(1) * (right - left)).roundToInt()
        fun py(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).roundToInt().coerceIn(top, bottom)
        fun put(x: Int, y: Int, c: FloatArray, a: Float) {
            if (x !in 0 until width || y !in 0 until height) return
            val o = (y * width + x) * 3
            for (k in 0..2) out.data[o + k] = out.data[o + k] * (1 - a) + c[k] * a
        }
        val axis = floatArrayOf(0.45f, 0.46f, 0.5f)
        for (y in top..bottom) put(left - 1, y, axis, 1f)
        for (x in left..right) put(x, bottom + 1, axis, 1f)
        val labels = ArrayList<Triple<String, Int, Int>>()
        for (k in 0..4) {
            val v = lo + (hi - lo) * k / 4
            val y = py(v)
            for (x in left - 4 until left - 1) put(x, y, axis, 1f)
            for (x in left..right step 4) put(x, y, axis, 0.25f)
            labels += Triple("%.2f".format(v), 1, y - 3)
        }
        for (n in listOf(0, budget / 2, budget)) {
            val x = px((n - 1).coerceAtLeast(0))
            for (y in bottom + 2..bottom + 4) put(x, y, axis, 1f)
            val text = n.toString()
            labels += Triple(text, (x - 3 * text.length).coerceIn(0, width - 6 * text.length), bottom + 7)
        }
        for ((a, c) in curves) for (t in 0 until budget) {
            val x = px(t)
            for (y in py(c[t][2])..py(c[t][0])) put(x, y, colours[a]!!, 0.12f)
        }
        for ((a, c) in curves) for (t in 0 until budget) {
            val y0 = py(c[t][1])
            val y1 = if (t + 1 < budget) py(c[t + 1][1]) else y0
            for (y in min(y0, y1)..max(y0, y1)) for (dx in 0..1) put(px(t) + dx, y, colours[a]!!, 1f)
        }
        for (l in labels) out = out.withLabel(l.first, l.second, l.third, scale = 1, color = Color4(0.6, 0.62, 0.68))
        // End-of-run medians, nudged apart so they never overprint.
        val ends = curves.map { (a, c) -> Triple(a, c[budget - 1][1], py(c[budget - 1][1])) }.sortedBy { it.third }
        var lastY = -100
        for ((a, v, y) in ends) {
            val yy = max(y - 3, lastY + 9)
            lastY = yy
            val c = colours[a]!!
            out = out.withLabel("%.2f".format(v), right + 4, yy, scale = 1, color = Color4(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()))
        }
        return out
    }

    /** [table]. */
    override fun toString(): String = table()

    companion object {
        /** Arm colours in order: grey (the control), blue, orange, green, then pink and yellow. */
        val palette: List<FloatArray> = listOf(
            floatArrayOf(0.7f, 0.7f, 0.7f),
            floatArrayOf(0.3f, 0.6f, 1f),
            floatArrayOf(1f, 0.55f, 0.15f),
            floatArrayOf(0.35f, 0.85f, 0.4f),
            floatArrayOf(0.95f, 0.4f, 0.7f),
            floatArrayOf(0.95f, 0.85f, 0.3f),
        )

        /** Linear-interpolated quantile of an ascending array; NaN when empty. */
        fun quantile(sorted: DoubleArray, q: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            val p = q * (sorted.size - 1)
            val i = p.toInt()
            val f = p - i
            return if (i + 1 < sorted.size) sorted[i] * (1 - f) + sorted[i + 1] * f else sorted[i]
        }
    }
}

/**
 * Run every arm in [arms] on [ops] and [objective] for exactly [budget] objective calls at every seed in
 * [seeds]. The first arm is the control the others' wins are counted against.
 *
 * Seeds run in parallel on the common pool and are gathered in seed order; each run is a pure function of
 * its seed, so the numbers do not depend on the parallelism — provided [objective] is safe to call from
 * several threads at once (every objective in `alife_evolve.kt` and `alife_vehicle_brains.kt` is: each
 * call builds its own world). Pass `parallel = false` for one that is not.
 *
 * Reading the result at a smaller budget ([SearchComparison.bestAt], [SearchComparison.budgetTable]) is
 * exact for random sampling and the hill climber, whose draws do not depend on the budget; and for
 * evolution, because [PopulationSearch.evolve] does not schedule around its budget either.
 */
fun <G> compareSearch(
    ops: GenomeOps<G>,
    objective: Objective<G>,
    budget: Int = 300,
    seeds: List<Long> = (1L..12L).toList(),
    arms: List<SearchArm<G>> = SearchArm.standard(),
    label: String = "search",
    parallel: Boolean = true,
): SearchComparison<G> {
    require(budget >= 1) { "budget must be >= 1" }
    require(arms.isNotEmpty()) { "need at least one arm" }
    require(arms.map { it.name }.toSet().size == arms.size) { "arm names must be distinct" }
    val runSeed = { s: Long -> arms.map { a -> SearchComparisonCell(a.name, s, a.run(ops, objective, budget, s)) } }
    val cells = if (parallel) seeds.parallelStream().map(runSeed).toList().flatten() else seeds.flatMap(runSeed)
    return SearchComparison(label, budget, arms.map { it.name }, cells)
}
