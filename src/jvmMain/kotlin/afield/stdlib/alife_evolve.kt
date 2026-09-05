@file:afield.CompletionCategory("ALife")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The search half of this family: rule sets as **genomes**, the measurements in `alife.kt` as
 * **fitness**, and `PopulationSearch` as the engine that connects them.
 *
 * Until this file existed every figure on the ALife page was "run a fixed rule and measure it". The
 * measuring vocabulary was already here and was already good — `stateEntropyBits`,
 * `massRadiusDimension`, `blobShapes`, `translationBetween`, `LeniaRun.centroidDrift` — and each of
 * those is a fitness function with nobody calling it. This file supplies the missing halves: a
 * [GenomeOps] for three families ([LifeRuleGenome], [LeniaGenome], [LSystemGenome]) and an
 * [Objective] for each, assembled out of statistics that were already in the file next door.
 *
 * There is **no search code here**. Every driver comes from [PopulationSearch], which is also what
 * `FunctionEvolve.evolve` runs on. That is the point: one engine, several genomes, rather than a
 * third private optimizer.
 *
 * **Every objective in this file evaluates every candidate from the same fixed initial condition.**
 * A Lenia rule is seeded from `leniaSeed(..., seed = initialSeed)`, a CA rule from the same
 * [randomSoup], an L-system from the same axiom. A search that re-randomised the initial condition
 * per candidate would be selecting on the luck of the draw, and the resulting "improvement" would be
 * mostly the search rediscovering that some soups are easier than others. Where robustness across
 * initial conditions *is* the question, [caEdgeOfChaosObjective] takes several soups and reports one
 * [Evaluation.cases] entry each, which is what makes [Selection.LEXICASE] meaningful here.
 */

// ==============================================================================================
// A shared initial condition
// ==============================================================================================

/**
 * An i.i.d. random grid — the initial condition every CA fitness in this file starts from.
 *
 * Deliberately *not* [smoothNoiseImg] thresholded: a smooth field gives spatially correlated blobs,
 * and a life-like rule's early behaviour is dominated by the correlation length of what it was
 * handed rather than by the rule. Independent per cell is the standard "primordial soup" and is what
 * `cyclicAdvanceFraction`'s "fresh random soup" language in `alife_ca.kt` already means.
 *
 * @param density probability a cell is non-zero, for a two-state grid
 * @param states number of states; with `states > 2` a live cell is drawn uniformly from `1..states-1`
 */
fun randomSoup(
    width: Int,
    height: Int,
    density: Double = 0.35,
    seed: Long = 1L,
    states: Int = 2,
    wrap: Boolean = true
): IntGrid {
    require(density in 0.0..1.0) { "density must be in 0..1, got $density" }
    require(states >= 2) { "states must be >= 2, got $states" }
    val rng = Random(seed)
    val cells = IntArray(width * height) {
        if (rng.nextDouble() >= density) 0
        else if (states == 2) 1 else 1 + rng.nextInt(states - 1)
    }
    return IntGrid(width, height, cells, wrap)
}

// ==============================================================================================
// 1 — Life-like CA rule tables
// ==============================================================================================

/**
 * A life-like [LifeRule] as a genome: the birth and survival sets, over a fixed neighbourhood.
 *
 * The neighbourhood ([radius], [vonNeumann]) is held **outside** the genome. Mutating the radius
 * would change how many neighbour counts exist and so change the length of the genome mid-search,
 * and the whole point of a rule table as a genome is that it is a fixed-length bit string that two
 * parents can be recombined across.
 *
 * `B0` is excluded from the birth set on purpose. Under a rule that is born on zero neighbours the
 * empty background flips alive every step, so density and activity measure the *background* rather
 * than any pattern in it, and every fitness in this file would be reading the wrong thing. That is
 * a statement about the measurements, not about B0 rules being uninteresting.
 *
 * [crossover] is a genuine two-parent recombination and not a graft: a uniform crossover over the
 * `2 * neighborhoodSize + 1` independent bits of the rule table. The repository had exactly one
 * other two-parent crossover before this one (`afield.morpho.MorphoGenerator.crossover`).
 */
class LifeRuleGenome(
    val radius: Int = 1,
    val vonNeumann: Boolean = false,
    /** Probability a given count is in the birth set of a freshly drawn genome. */
    val birthDensity: Double = 0.25,
    /** Probability a given count is in the survival set of a freshly drawn genome. */
    val surviveDensity: Double = 0.4
) : GenomeOps<LifeRule> {

    /** Highest neighbour count the fixed neighbourhood can produce. */
    val maxCount: Int = LifeRule(emptySet(), emptySet(), radius, vonNeumann).neighborhoodSize

    /** Counts a birth bit may be set at: `1..maxCount`, excluding 0. See the class KDoc. */
    val birthCounts: IntRange = 1..maxCount

    /** Counts a survival bit may be set at: `0..maxCount`. */
    val surviveCounts: IntRange = 0..maxCount

    override fun random(rng: Random): LifeRule = LifeRule(
        birth = birthCounts.filter { rng.nextDouble() < birthDensity }.toSet(),
        survive = surviveCounts.filter { rng.nextDouble() < surviveDensity }.toSet(),
        radius = radius,
        vonNeumann = vonNeumann
    )

    override val mutations: List<Variation<LifeRule>> = listOf(
        Variation("toggleBirth") { rule, rng -> rule.withBirthToggled(birthCounts.random(rng)) },
        Variation("toggleSurvive") { rule, rng -> rule.withSurviveToggled(surviveCounts.random(rng)) },
        Variation("toggleEither") { rule, rng ->
            if (rng.nextBoolean()) rule.withBirthToggled(birthCounts.random(rng))
            else rule.withSurviveToggled(surviveCounts.random(rng))
        },
        Variation("toggleTwo") { rule, rng ->
            rule.withBirthToggled(birthCounts.random(rng)).withSurviveToggled(surviveCounts.random(rng))
        }
    )

    /** Uniform crossover: every bit of the table comes from one parent or the other, independently. */
    override fun crossover(a: LifeRule, b: LifeRule, rng: Random): LifeRule = LifeRule(
        birth = birthCounts.filter { if (rng.nextBoolean()) it in a.birth else it in b.birth }.toSet(),
        survive = surviveCounts.filter { if (rng.nextBoolean()) it in a.survive else it in b.survive }.toSet(),
        radius = radius,
        vonNeumann = vonNeumann
    )

    override fun signature(g: LifeRule): String = lifeRuleSignature(g)

    private fun LifeRule.withBirthToggled(n: Int): LifeRule =
        LifeRule(birth.toggle(n), survive, radius, vonNeumann)

    private fun LifeRule.withSurviveToggled(n: Int): LifeRule =
        LifeRule(birth, survive.toggle(n), radius, vonNeumann)

    private fun Set<Int>.toggle(n: Int): Set<Int> = if (n in this) this - n else this + n
}

/** `B…/S…` plus the neighbourhood, unique per distinct rule — the memoisation key for a rule table. */
fun lifeRuleSignature(rule: LifeRule): String =
    "B${rule.birth.sorted().joinToString("")}/S${rule.survive.sorted().joinToString("")}" +
        "@r${rule.radius}${if (rule.vonNeumann) "v" else ""}"

/**
 * What one CA rule did to one soup: where its density settled and how much it was still changing.
 *
 * @property density fraction of live cells at the end
 * @property activity mean fraction of cells that changed state per step, over the scored window
 * @property entropyBits [IntGrid.stateEntropyBits] of the final grid
 * @property extinct `true` if the grid emptied
 * @property finalGrid the grid the run ended on, so a figure can show what was selected
 */
class CaRunSummary(
    val density: Double,
    val activity: Double,
    val entropyBits: Double,
    val extinct: Boolean,
    val finalGrid: IntGrid
) {
    override fun toString(): String =
        "CaRunSummary(density %.3f, activity %.3f, %.3f bits%s)"
            .format(density, activity, entropyBits, if (extinct) ", extinct" else "")
}

/**
 * Run [rule] from [soup] and summarise it.
 *
 * @param warmup steps to run before scoring starts, so the transient from the soup is not measured
 * @param scored steps to run and measure after the warmup
 */
fun caRun(soup: IntGrid, rule: LifeRule, warmup: Int = 30, scored: Int = 40): CaRunSummary {
    require(warmup >= 0) { "warmup must be >= 0, got $warmup" }
    require(scored >= 1) { "scored must be >= 1, got $scored" }
    var g = soup.stepTimes(rule, warmup)
    var changeSum = 0.0
    for (i in 0 until scored) {
        val next = g.step(rule)
        changeSum += cyclicAdvanceFraction(g, next)
        g = next
    }
    val population = g.population()
    return CaRunSummary(
        density = population.toDouble() / g.size,
        activity = changeSum / scored,
        entropyBits = g.stateEntropyBits(2),
        extinct = population == 0,
        finalGrid = g
    )
}

/**
 * "Alive but not saturated, and still changing" — the edge-of-chaos objective, as a number.
 *
 * `activity * band(density)`, where `band` is a Gaussian centred on [targetDensity]. Both factors
 * are needed and each one alone is trivially gamed: a rule that fills the grid and stays filled
 * scores 1 on density and 0 on activity, a rule that flickers every cell every step scores ~1 on
 * activity and ~0 on the band, and an extinct rule scores 0 on both. The product is only large for
 * a rule that keeps a moderate population *and* keeps rearranging it, which is what everybody means
 * by an interesting life-like rule and what nobody can state by looking.
 *
 * The result is in `0..1` and an extinct rule scores exactly [EXTINCT_SCORE] (a negative number, so
 * "died" sorts strictly below every rule that survived rather than tying with "saturated and inert").
 *
 * @param soups initial conditions. Each contributes one [Evaluation.cases] entry and the scalar
 *   [Evaluation.fitness] is their mean, so [Selection.LEXICASE] can select a rule that is uniquely
 *   good on one soup — the case structure lexicase needs, and the reason this takes a list.
 * @return an [Evaluation] whose [Evaluation.behaviour] is `[density, activity]`, the two axes a
 *   quality-diversity archive over this family wants.
 */
fun caEdgeOfChaosObjective(
    soups: List<IntGrid>,
    targetDensity: Double = 0.20,
    bandWidth: Double = 0.12,
    warmup: Int = 30,
    scored: Int = 40
): Objective<LifeRule> {
    require(soups.isNotEmpty()) { "caEdgeOfChaosObjective needs at least one soup" }
    require(targetDensity > 0.0 && targetDensity < 1.0) { "targetDensity must be in (0, 1)" }
    require(bandWidth > 0.0) { "bandWidth must be > 0, got $bandWidth" }
    return Objective { rule ->
        val cases = DoubleArray(soups.size)
        var densitySum = 0.0
        var activitySum = 0.0
        for (i in soups.indices) {
            val s = caRun(soups[i], rule, warmup, scored)
            densitySum += s.density
            activitySum += s.activity
            cases[i] = if (s.extinct) EXTINCT_SCORE else s.activity * densityBand(s.density, targetDensity, bandWidth)
        }
        Evaluation(
            fitness = cases.average(),
            cases = cases,
            behaviour = doubleArrayOf(densitySum / soups.size, activitySum / soups.size)
        )
    }
}

/** What an extinct run scores: strictly below any survivor, and the same number everywhere. */
const val EXTINCT_SCORE: Double = -1.0

/** A Gaussian band on density, 1 at [target] and falling off over [width]. */
internal fun densityBand(density: Double, target: Double, width: Double): Double {
    val z = (density - target) / width
    return exp(-0.5 * z * z)
}

// ==============================================================================================
// 2 — Lenia rules
// ==============================================================================================

/**
 * A [LeniaRule] as a genome: `mu`, `sigma` and the kernel's shell weights, over a fixed radius and
 * time step.
 *
 * `radius` and `dt` stay outside the genome because they set the *cost* of an evaluation, not its
 * character — a search allowed to mutate the radius spends its budget discovering that a bigger
 * kernel is slower, and every candidate becomes incomparable in wall time to every other.
 *
 * [crossover] is per-parameter uniform crossover, which is meaningful here because `mu`, `sigma` and
 * the shell weights are close to independent: a promising growth window found by one lineage and a
 * promising kernel shape found by another can be combined without either being destroyed.
 */
class LeniaGenome(
    val radius: Int = 8,
    val dt: Double = 0.1,
    val shells: Int = 3,
    val muRange: ClosedFloatingPointRange<Double> = 0.05..0.40,
    val sigmaRange: ClosedFloatingPointRange<Double> = 0.006..0.070
) : GenomeOps<LeniaRule> {

    init {
        require(radius >= 2) { "radius must be >= 2, got $radius" }
        require(shells >= 1) { "shells must be >= 1, got $shells" }
        require(muRange.start > 0.0) { "mu must stay positive" }
        require(sigmaRange.start > 0.0) { "sigma must stay positive" }
    }

    override fun random(rng: Random): LeniaRule = build(
        mu = draw(rng, muRange),
        sigma = draw(rng, sigmaRange),
        peaks = DoubleArray(shells) { 0.05 + 0.95 * rng.nextDouble() }
    )

    override val mutations: List<Variation<LeniaRule>> = listOf(
        Variation("jitterMu") { r, rng -> rebuild(r, mu = jitter(rng, r.mu, muRange, 0.12)) },
        Variation("jitterSigma") { r, rng -> rebuild(r, sigma = jitter(rng, r.sigma, sigmaRange, 0.20)) },
        Variation("jitterShell") { r, rng ->
            val peaks = r.shellPeaks.copyOf()
            val i = rng.nextInt(peaks.size)
            peaks[i] = (peaks[i] + (rng.nextDouble() * 2.0 - 1.0) * 0.30).coerceIn(0.02, 1.0)
            rebuild(r, peaks = peaks)
        },
        Variation("resampleShell") { r, rng ->
            val peaks = r.shellPeaks.copyOf()
            peaks[rng.nextInt(peaks.size)] = 0.05 + 0.95 * rng.nextDouble()
            rebuild(r, peaks = peaks)
        },
        Variation("jitterBoth") { r, rng ->
            rebuild(r, mu = jitter(rng, r.mu, muRange, 0.10), sigma = jitter(rng, r.sigma, sigmaRange, 0.15))
        }
    )

    override fun crossover(a: LeniaRule, b: LeniaRule, rng: Random): LeniaRule = build(
        mu = if (rng.nextBoolean()) a.mu else b.mu,
        sigma = if (rng.nextBoolean()) a.sigma else b.sigma,
        peaks = DoubleArray(shells) { i ->
            val from = if (rng.nextBoolean()) a.shellPeaks else b.shellPeaks
            from.getOrElse(i) { 1.0 }
        }
    )

    /**
     * Rounded to five decimals. Two Lenia rules whose parameters agree to that precision produce
     * the same kernel taps to well inside float precision, so calling them the same phenotype is a
     * statement about the simulation and not a convenience.
     */
    override fun signature(g: LeniaRule): String =
        "mu%.5f/s%.5f/%s".format(g.mu, g.sigma, g.shellPeaks.joinToString(",") { "%.5f".format(it) })

    private fun build(mu: Double, sigma: Double, peaks: DoubleArray) =
        LeniaRule(radius = radius, dt = dt, mu = mu, sigma = sigma, shellPeaks = peaks)

    private fun rebuild(
        r: LeniaRule,
        mu: Double = r.mu,
        sigma: Double = r.sigma,
        peaks: DoubleArray = r.shellPeaks
    ) = build(mu, sigma, peaks)

    private fun draw(rng: Random, range: ClosedFloatingPointRange<Double>) =
        range.start + rng.nextDouble() * (range.endInclusive - range.start)

    private fun jitter(
        rng: Random,
        value: Double,
        range: ClosedFloatingPointRange<Double>,
        fraction: Double
    ): Double {
        val span = range.endInclusive - range.start
        return (value + (rng.nextDouble() * 2.0 - 1.0) * fraction * span)
            .coerceIn(range.start, range.endInclusive)
    }
}

/**
 * "Stays alive, stays localised, and travels" — the soliton objective.
 *
 * This is the one thing continuous Lenia is famous for and the one thing the discrete Life family
 * cannot do smoothly, and every term of it is read straight off [leniaRun], which already classifies
 * a run into [LeniaPhase] and already reports a wrapped centroid drift.
 *
 * The two failure modes are given a **gradient**, not a flat penalty. A run that died scores
 * `-1 + finalMass` and a run that saturated scores `-1 + (1 - finalMass)`, so a rule that only just
 * died sorts above one that died instantly and the search has something to climb from the moment it
 * starts. A flat `-1` for both would make the whole death region a plateau, which is how a Lenia
 * search gets stuck at generation one.
 *
 * A surviving run scores `1 + drift/driftScale + localised`, where `localised` is [blobBonus] when
 * exactly one connected feature is left. Travel and localisation together are what "soliton" means;
 * drift alone is also earned by a field that smears across the torus.
 *
 * @return an [Evaluation] whose [Evaluation.behaviour] is `[finalMass, drift]`.
 */
fun leniaSolitonObjective(
    width: Int = 64,
    height: Int = 64,
    steps: Int = 120,
    initialSeed: Long = 1L,
    blobRadii: Double = 1.4,
    driftScale: Double = 12.0,
    blobBonus: Double = 0.5
): Objective<LeniaRule> {
    require(driftScale > 0.0) { "driftScale must be > 0, got $driftScale" }
    return Objective { rule ->
        val run = leniaRun(
            leniaSeed(width, height, rule, blobRadii = blobRadii, seed = initialSeed),
            rule,
            steps = steps,
            sampleEvery = max(1, steps / 12)
        )
        val fitness = when (run.phase) {
            LeniaPhase.DEATH -> -1.0 + run.finalMass
            LeniaPhase.SATURATION -> -1.0 + (1.0 - run.finalMass)
            LeniaPhase.BOUNDED ->
                1.0 + min(1.0, run.centroidDrift / driftScale) + (if (run.blobCount == 1) blobBonus else 0.0)
        }
        Evaluation(fitness, behaviour = doubleArrayOf(run.finalMass, run.centroidDrift))
    }
}

// ==============================================================================================
// 3 — L-system grammars
// ==============================================================================================

/**
 * An [LSystem] as a genome: one production per rewritten symbol, over a fixed turtle alphabet.
 *
 * The axiom and the set of rewritten symbols are fixed; what evolves is the replacement strings.
 * [crossover] is single-point string crossover per production, the textbook recombination for a
 * grammar and the reason an L-system is the family in this file where recombination earns its keep.
 *
 * Replacements are **repaired, not rejected**: [balanceBrackets] closes any bracket left open and
 * drops any close with no matching open, so every genome in the population is drawable. A search
 * that rejected unbalanced strings would spend most of its budget on candidates it threw away, and
 * the reject rate would rise with string length exactly as the strings got interesting.
 */
class LSystemGenome(
    val axiom: String = "F",
    val symbols: String = "F",
    /** The characters a replacement may be built from. */
    val alphabet: String = "FF+-[]",
    val minLength: Int = 3,
    val maxLength: Int = 14
) : GenomeOps<LSystem> {

    init {
        require(axiom.isNotEmpty()) { "axiom must be non-empty" }
        require(symbols.isNotEmpty()) { "at least one symbol must be rewritten" }
        require(alphabet.isNotEmpty()) { "alphabet must be non-empty" }
        require(minLength in 1..maxLength) { "need 1 <= minLength <= maxLength" }
    }

    override fun random(rng: Random): LSystem = LSystem(
        axiom,
        symbols.map { LProduction(it, balanceBrackets(randomBody(rng))) }
    )

    override val mutations: List<Variation<LSystem>> = listOf(
        Variation("point") { s, rng -> s.editOne(rng) { body -> body.replaceAt(rng.nextInt(body.length), alphabet.random(rng)) } },
        Variation("insert") { s, rng -> s.editOne(rng) { body -> body.insertAt(rng.nextInt(body.length + 1), alphabet.random(rng)) } },
        Variation("delete") { s, rng -> s.editOne(rng) { body -> if (body.length <= minLength) body else body.removeRange(rng.nextInt(body.length), rng.nextInt(body.length) + 1) } },
        Variation("duplicate") { s, rng ->
            s.editOne(rng) { body ->
                val i = rng.nextInt(body.length)
                val j = min(body.length, i + 1 + rng.nextInt(3))
                body.insertAt(j, body.substring(i, j))
            }
        },
        Variation("swap") { s, rng ->
            s.editOne(rng) { body ->
                if (body.length < 2) body else {
                    val i = rng.nextInt(body.length)
                    val j = rng.nextInt(body.length)
                    val c = body.toCharArray()
                    val t = c[i]; c[i] = c[j]; c[j] = t
                    String(c)
                }
            }
        }
    )

    /** Single-point crossover per production: a prefix from one parent, a suffix from the other. */
    override fun crossover(a: LSystem, b: LSystem, rng: Random): LSystem = LSystem(
        axiom,
        symbols.map { sym ->
            val left = a.bodyOf(sym)
            val right = b.bodyOf(sym)
            val cutA = rng.nextInt(left.length + 1)
            val cutB = rng.nextInt(right.length + 1)
            LProduction(sym, clampBody(balanceBrackets(left.take(cutA) + right.drop(cutB))))
        }
    )

    override fun signature(g: LSystem): String =
        g.axiom + "|" + symbols.toList().joinToString("|") { sym -> "$sym>" + g.bodyOf(sym) }

    private fun LSystem.bodyOf(symbol: Char): String =
        productions.firstOrNull { it.symbol == symbol }?.replacement ?: ""

    private fun LSystem.editOne(rng: Random, edit: (String) -> String): LSystem {
        val target = symbols.random(rng)
        return LSystem(
            axiom,
            symbols.map { sym ->
                val body = bodyOf(sym)
                LProduction(sym, if (sym == target) clampBody(balanceBrackets(edit(body))) else body)
            }
        )
    }

    private fun randomBody(rng: Random): String {
        val n = minLength + rng.nextInt(maxLength - minLength + 1)
        return String(CharArray(n) { alphabet[rng.nextInt(alphabet.length)] })
    }

    private fun clampBody(body: String): String = when {
        body.length > maxLength -> balanceBrackets(body.take(maxLength))
        body.isEmpty() -> "F"
        else -> body
    }

    private fun String.replaceAt(index: Int, c: Char): String = substring(0, index) + c + substring(index + 1)
    private fun String.insertAt(index: Int, s: String): String = substring(0, index) + s + substring(index)
    private fun String.insertAt(index: Int, c: Char): String = insertAt(index, c.toString())
}

/**
 * Close every bracket left open and drop every close with no matching open.
 *
 * A repair rather than a rejection. Every string over an alphabet that contains `[` and `]` is one
 * point mutation away from being unbalanced, so a search that rejected unbalanced genomes would
 * reject most of its own proposals and would reject a rising fraction of them as its strings grew.
 * `turtleDraw` itself does not crash on an unbalanced string — it drops the unmatched pop — but the
 * *genome* would then not mean what it says, and a crossover between two strings whose brackets do
 * not correspond would produce a child unrelated to either parent.
 */
fun balanceBrackets(s: String): String {
    val out = StringBuilder(s.length + 4)
    var depth = 0
    for (c in s) {
        when (c) {
            '[' -> { depth++; out.append(c) }
            ']' -> if (depth > 0) { depth--; out.append(c) }
            else -> out.append(c)
        }
    }
    repeat(depth) { out.append(']') }
    return out.toString()
}

/**
 * What one L-system grammar drew: the branching structure and the two numbers it is scored on.
 *
 * @property dimension mass–radius dimension of the drawn points, from [massRadiusDimension]
 * @property rSquared how straight the log–log fit was. Below ~0.95 the object has no scaling regime
 *   over this range and [dimension] is a slope through a curve, which is not a dimension.
 * @property totalLength [flineTotalLength] of the drawing
 * @property branches how many separate drawn runs the turtle produced
 * @property raster the drawing, rasterised, ready for a contact sheet
 * @property drawable `false` when the grammar drew too little to measure; every number above is then
 *   meaningless and the objective says so instead of fitting three points.
 */
class LSystemDrawing(
    val dimension: Double,
    val rSquared: Double,
    val totalLength: Double,
    val branches: Int,
    val segments: Int,
    val raster: Img,
    val drawable: Boolean
) {
    override fun toString(): String = if (!drawable) "LSystemDrawing(not drawable)" else
        "LSystemDrawing(D %.3f, r2 %.3f, %d branch(es), %d segment(s))"
            .format(dimension, rSquared, branches, segments)
}

/**
 * Derive [system], draw it, rasterise it and measure it.
 *
 * @param minSegments below this many drawn moves the drawing is declared not measurable. A
 *   mass–radius fit over a handful of points returns a number, and the number is noise.
 */
fun lSystemDrawing(
    system: LSystem,
    generations: Int = 4,
    params: TurtleParams = TurtleParams(angle = Math.PI / 7.0, scaleOnBranch = true),
    side: Int = 128,
    minSegments: Int = 24,
    maxSymbols: Int = 60_000
): LSystemDrawing {
    val blank = Img.zeros(side, side, 1)
    val derived = runCatching { system.derive(generations, Random(0), maxLength = maxSymbols) }
        .getOrElse { return LSystemDrawing(0.0, 0.0, 0.0, 0, 0, blank, false) }
    val drawn = turtleDraw(derived, params)
    if (drawn.drawnSegments < minSegments || drawn.lines.isEmpty()) {
        return LSystemDrawing(0.0, 0.0, 0.0, drawn.lines.size, drawn.drawnSegments, blank, false)
    }
    val raster = rasterizeFLines(drawn.lines, side, side, margin = 4)
    val pts = ArrayList<Vec2>()
    for (y in 0 until side) for (x in 0 until side) {
        if (raster.data[y * side + x] > 0.5f) pts.add(Vec2(x.toFloat(), y.toFloat()))
    }
    if (pts.size < minSegments) {
        return LSystemDrawing(0.0, 0.0, 0.0, drawn.lines.size, drawn.drawnSegments, raster, false)
    }
    var cx = 0.0; var cy = 0.0
    for (p in pts) { cx += p.x; cy += p.y }
    val centre = Vec2((cx / pts.size).toFloat(), (cy / pts.size).toFloat())
    val fit = runCatching {
        massRadiusDimension(pts, centre, rMin = 3.0, rMax = side / 3.0, minCount = 12)
    }.getOrElse { return LSystemDrawing(0.0, 0.0, 0.0, drawn.lines.size, drawn.drawnSegments, raster, false) }
    return LSystemDrawing(
        dimension = fit.dimension,
        rSquared = fit.rSquared,
        totalLength = flineTotalLength(drawn.lines),
        branches = drawn.lines.size,
        segments = drawn.drawnSegments,
        raster = raster,
        drawable = true
    )
}

/**
 * "Draw something with this fractal dimension" — the L-system objective.
 *
 * `-|D - target|` plus a small reward for the fit actually being a straight line, because a slope
 * through a curve will hit any target you like and mean nothing. An undrawable grammar scores
 * [EXTINCT_SCORE], the same floor an extinct CA rule gets, so "produced nothing" sorts strictly
 * below every grammar that produced something.
 *
 * The target is the interesting knob. `1.0` asks for a curve, `2.0` for something that fills the
 * plane, and `1.7` for something in between — which is where DLA sits, and the one value in this
 * family with a literature number attached to it (see `alife_examples.md` section 8).
 *
 * @return an [Evaluation] whose [Evaluation.behaviour] is `[dimension, ln(1 + totalLength)]`, the
 *   two axes a quality-diversity archive over grammars wants: what shape, and how much of it.
 */
fun lSystemDimensionObjective(
    targetDimension: Double = 1.70,
    generations: Int = 4,
    params: TurtleParams = TurtleParams(angle = Math.PI / 7.0, scaleOnBranch = true),
    side: Int = 128,
    straightnessWeight: Double = 0.25
): Objective<LSystem> = Objective { system ->
    val d = lSystemDrawing(system, generations, params, side)
    if (!d.drawable) {
        Evaluation(EXTINCT_SCORE, behaviour = doubleArrayOf(0.0, 0.0))
    } else {
        Evaluation(
            fitness = -abs(d.dimension - targetDimension) + straightnessWeight * d.rSquared,
            behaviour = doubleArrayOf(d.dimension, ln(1.0 + d.totalLength))
        )
    }
}
