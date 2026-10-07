@file:afield.CompletionCategory("Search")

package afield.stdlib

import afield.stacksl.cpu.AdAnnealing
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLFit
import afield.stacksl.cpu.StackSLFitLoss
import afield.stacksl.cpu.StackSLWires
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A StackSL search you can stop, look at, steer and resume: [evolveStackSLTowards] taken apart.
 *
 * [evolveStackSLTowards] is one call that runs to its budget and returns. This is the same search
 * held as an object, so that something outside the loop can take part in it between generations:
 *
 *  - **wider:** several [islands] evolving apart, with the best few migrating round a ring every
 *    [StackSLEvolveSettings.migrateEvery] generations, and a larger µ and λ;
 *  - **deeper:** programs up to [StackSLGenome.maxTokens] long (32 here, against 24);
 *  - **a schedule** ([StackSLStage]): the resolution, the metric and the LPIPS region grid can change
 *    as the search goes, coarse and cheap first, native 64 px LPIPS later. The population is
 *    re-scored when a stage begins, since a score from another stage is not comparable;
 *  - **a gradient step** ([fit]): a round of Adam on a program's own numeric literals, through
 *    `StackSLFit`'s exact reverse-mode gradient, re-scored under the search's metric;
 *  - **a watchmaker** ([watchmaker]): a language model shown the target and the leaders' renders,
 *    asked for edits. See [StackSLWatchmaker];
 *  - **proposals from anyone** ([inject]): a person, or the AI4 chat through [stackSLEvolveStudio];
 *  - **crossbreeding** ([crossbreed]): survivors of another saved population, and recombinants of
 *    them with this one's leaders.
 *
 * Every candidate carries its **origin**, and [credit] counts, per origin, how many were offered,
 * how many survived into the population and how many took the lead. That table is what says whether
 * the watchmaker or the gradient step is doing anything, at the same render budget the blind
 * operators get.
 *
 * The state is a [snapshot]: settings, every island's programs, the history of the best score and
 * the event notes, as JSON. [restore] puts it back. The AI4 studio saves one before and after every
 * chat turn, which is how rewinding the conversation rewinds the population.
 *
 * As in [evolveStackSLTowards], the held-out check [StackSLLayout.layoutAgreement] is **never**
 * shown to the search, to the watchmaker or to the chat: [layoutOf] is for reports.
 */
class StackSLEvolution(
    /** The target at a given fitness resolution. Asked once per resolution and kept. */
    private val targetAt: (dim: Int) -> StackSLTarget,
    settings: StackSLEvolveSettings = StackSLEvolveSettings(),
    val seed: Long = 1L,
    /** Split each render's rows across cores. */
    val parallel: Boolean = true,
    /** The render time every candidate is drawn at; `StackSLCpu.stillTemplate`'s default. */
    val time: Float = 1.75f
) {
    /** A search toward one fixed [target]; other resolutions are area-averaged from it. */
    constructor(target: StackSLTarget, settings: StackSLEvolveSettings = StackSLEvolveSettings(), seed: Long = 1L, parallel: Boolean = true) :
        this(resample(target), settings, seed, parallel)

    /** The current settings. Change them with [configure], which validates them first. */
    var settings: StackSLEvolveSettings = settings.also { it.validate() }
        private set

    /** The target's name, the same at every resolution. */
    val targetName: String get() = targetAt(stage.dim).name

    /** Generations run so far, over every island. */
    var generation: Int = 0
        private set

    /** Programs rendered for fitness so far: the budget. Cache hits, thumbnails and fits are not in it. */
    var renders: Int = 0
        private set

    /** Wall time spent in [step], [fit] and the watchmaker, by what spent it. */
    val millis: MutableMap<String, Double> = linkedMapOf()

    /** The language model, when one takes part. `null` is the blind search. */
    var watchmaker: StackSLProposer? = null

    /** Each island's population, best first. */
    val islands: List<List<Individual<StackSLProgram>>> get() = pools.map { it.toList() }

    /** Every island's members, best first, programs distinct. */
    val ranked: List<Individual<StackSLProgram>>
        get() = pools.flatten().sortedByDescending { it.fitness }.distinctBy { it.genome.text }

    /** The best program in any island. */
    val best: Individual<StackSLProgram>? get() = ranked.firstOrNull()

    /** One line per generation: the leader's score and where it came from. */
    val history: MutableList<StackSLGenerationRecord> = ArrayList()

    /** Every time the overall leader improved, with the program, so the lineage can be drawn. */
    val improvements: MutableList<StackSLImprovement> = ArrayList()

    /** What happened besides ordinary generations: stage changes, watchmaker rounds, fits, crossbreeds. */
    val notes: MutableList<String> = ArrayList()

    /** Per origin: offered, survived into a population, took the overall lead. */
    val credit: MutableMap<String, StackSLOriginCredit> = sortedMapOf()

    /** What the watchmaker has cost so far. */
    var usage: StackSLModelUsage = StackSLModelUsage()
        private set

    /** The last watchmaker round's outcome per proposal, shown to it next time. */
    private var lastRound: List<StackSLProposalOutcome> = emptyList()

    private var pools: MutableList<MutableList<Individual<StackSLProgram>>> = ArrayList()
    private var dealt = 0
    private val targets = HashMap<Int, StackSLTarget>()
    private val objectives = HashMap<String, StackSLImageObjective>()
    private var stageKey: String? = null
    private var sinceImprovement = 0
    private var lastAsked = Int.MIN_VALUE / 2
    private var pending: Future<StackSLProposalReply>? = null
    private var pendingFor: List<Individual<StackSLProgram>> = emptyList()
    private val asker = Executors.newSingleThreadExecutor { r -> Thread(r, "stacksl-watchmaker").apply { isDaemon = true } }

    /** The genome the blind operators use: the default vocabulary at [StackSLEvolveSettings.maxTokens]. */
    val genome: StackSLGenome get() = StackSLGenome(maxTokens = settings.maxTokens)

    /** The stage the next generation runs under. */
    val stage: StackSLStage get() = settings.stageAt(generation)

    /** The objective of the current [stage]. */
    val objective: StackSLImageObjective get() = objectiveFor(stage)

    /** The current stage's target. */
    val target: StackSLTarget get() = targetOf(stage.dim)

    /** [fitness] rescaled so 0 is a flat fill of the target's mean colour and 1 is exact. */
    fun skill(fitness: Double): Double = objective.skill(fitness)

    // ------------------------------------------------------------------------------------------
    // running
    // ------------------------------------------------------------------------------------------

    /** Run [generations] generations, or fewer if [maxRenders] is reached first. */
    fun step(generations: Int = 1, maxRenders: Int = Int.MAX_VALUE): StackSLEvolution {
        require(generations >= 0) { "generations must be >= 0, got $generations" }
        timed("evolve") { ensureStartedQuietly() }
        repeat(generations) {
            if (renders >= maxRenders) return this
            generationOnce(maxRenders)
        }
        return this
    }

    /** Run until [budget] programs have been rendered for fitness. */
    fun runTo(budget: Int): StackSLEvolution {
        timed("evolve") { ensureStartedQuietly() }
        var stuck = 0
        while (renders < budget && stuck < 50) {
            val before = renders
            generationOnce(budget)
            stuck = if (renders == before) stuck + 1 else 0
        }
        return this
    }

    private fun ensureStarted() {
        enterStage()
        if (pools.size != settings.islands) {
            while (pools.size < settings.islands) pools.add(ArrayList())
            while (pools.size > settings.islands) pools.last().let { last -> pools.removeAt(pools.size - 1); pools[0] += last }
        }
        for ((i, pool) in pools.withIndex()) {
            val rng = rngFor(-1, i)
            var tries = 0
            while (pool.size < settings.mu && tries++ < settings.mu * PopulationSearch.PROPOSAL_LIMIT) {
                consider(genome.random(rng), "random")?.let { pool += it }
            }
            trim(pool)
        }
        if (leaderFitness == Double.NEGATIVE_INFINITY) noteLeader("random")
    }

    private fun generationOnce(maxRenders: Int) {
        timed("evolve") {
            enterStage()
            val s = settings
            val genome = genome
            val tournamentSelection = if (s.selection == Selection.LEXICASE && !casesAvailable()) Selection.TOURNAMENT else s.selection
            for ((i, pool) in pools.withIndex()) {
                if (pool.isEmpty()) continue
                val rng = rngFor(generation, i)
                val children = ArrayList<Individual<StackSLProgram>>(s.lambda)
                val present = pool.mapTo(HashSet()) { it.genome.text }
                var offered = 0
                while (offered < s.lambda * PopulationSearch.PROPOSAL_LIMIT && children.size < s.lambda && renders < maxRenders) {
                    offered++
                    val child: Pair<StackSLProgram, String> = if (s.immigrantRate > 0.0 && rng.nextDouble() < s.immigrantRate) {
                        genome.random(rng) to "immigrant"
                    } else {
                        val parent = PopulationSearch.select(pool, s.tournament, tournamentSelection, rng)
                        val recombine = s.crossoverRate > 0.0 && pool.size > 1 && rng.nextDouble() < s.crossoverRate
                        val base = if (recombine) genome.crossover(parent.genome, PopulationSearch.select(pool, s.tournament, tournamentSelection, rng).genome, rng) else parent.genome
                        val v = genome.mutations[rng.nextInt(genome.mutations.size)]
                        (runCatching { v.apply(base, rng) }.getOrDefault(base)) to ((if (recombine) "crossover+" else "") + v.name)
                    }
                    if (!present.add(child.first.text)) continue
                    consider(child.first, child.second)?.let { children += it }
                }
                pool += children
                trim(pool)
            }
            generation++
            if (s.islands > 1 && s.migrateEvery > 0 && generation % s.migrateEvery == 0) migrate()
        }
        if (settings.fitEvery > 0 && generation % settings.fitEvery == 0 && renders < maxRenders) {
            best?.let { leader -> fit(leader.genome, iterations = settings.fitIterations, origin = "fit") }
        }
        watchmakerTurn()
        noteLeader(null)
    }

    /** Whether the current stage's objective gives lexicase anything to select on. */
    private fun casesAvailable(): Boolean = stage.metric != StackSLMetric.LPIPS || stage.lpipsGrid >= 2

    private fun migrate() {
        val movers = pools.map { pool -> pool.take(settings.migrants) }
        for (i in pools.indices) {
            val into = pools[(i + 1) % pools.size]
            val have = into.map { it.genome.text }.toSet()
            movers[i].filter { it.genome.text !in have }.forEach { into += Individual(it.genome, it.evaluation, "migrant") }
            trim(into)
        }
    }

    // ------------------------------------------------------------------------------------------
    // candidates from outside the blind loop
    // ------------------------------------------------------------------------------------------

    /**
     * Offer [programs] to the population, as [origin]. Each is checked ([StackSLProposals.check]),
     * rendered, scored and put into [island] (0 by default; `-1` deals them round the islands),
     * where truncation decides whether it stays. Costs one render each, like any candidate.
     */
    fun inject(programs: List<String>, origin: String = "proposed", island: Int = 0): List<StackSLProposalOutcome> {
        ensureStartedQuietly()
        val out = programs.map { text ->
            val problem = StackSLProposals.check(text, settings.maxTokens)
            if (problem != null) return@map StackSLProposalOutcome(text, origin, null, null, null, problem)
            val program = StackSLProposals.program(text)
            val individual = consider(program, origin)
                ?: return@map StackSLProposalOutcome(program.text, origin, null, null, null, "already in the cache: scored before")
            if (!individual.evaluation.fitness.isFinite() || individual.fitness <= StackSLImageObjective.INVALID_SCORE) {
                return@map StackSLProposalOutcome(program.text, origin, individual.fitness, null, null, "renders a blank or flat picture, or throws")
            }
            val pool = pools[if (island < 0) dealt++ % pools.size else island.coerceIn(0, pools.size - 1)]
            pool += individual
            trim(pool)
            val rank = ranked.indexOfFirst { it.genome.text == program.text }.takeIf { it >= 0 }?.plus(1)
            StackSLProposalOutcome(program.text, origin, individual.fitness, skill(individual.fitness), rank, null)
        }
        noteLeader(null)
        return out
    }

    /**
     * A round of Adam on [program]'s numeric literals, toward the current stage's target, then
     * scored under the search's own metric and offered to the population as [origin].
     *
     * The descent's loss follows the stage's metric: the mean squared error of the clamped render
     * under PIXEL, and under LPIPS and LPIPS_PIXEL the mean of LPIPS (through its exported
     * gradient, the stage's region grid averaged in) and that squared error. Not pure LPIPS under
     * LPIPS: measured on 2026-10-07 (`StackSLLpipsFitMeasurements`), a pure-LPIPS descent never
     * turned an inverted seed the right way round and on a `bands` seed with the wrong stripe
     * frequency drifted from polarity +0.25 to -0.13, while the mean gained the most skill under
     * LPIPS of the three and never ended inverted. [loss] overrides it. Always with `step`-family
     * annealing, so a `threshold` or `select` edge can move. A fit can still
     * lower its own loss and score worse under the search's; the population decides. [literals]
     * picks which literals may move by **token index** (0-based), all of them when `null`.
     */
    fun fit(
        program: StackSLProgram,
        literals: List<Int>? = null,
        iterations: Int = settings.fitIterations,
        learningRate: Double = settings.fitLearningRate,
        origin: String = "fit",
        loss: StackSLFitLoss? = null
    ): StackSLFitOutcome = timed("fit") {
        ensureStartedQuietly()
        val dim = stage.dim
        val descent = loss ?: when (stage.metric) {
            StackSLMetric.PIXEL -> StackSLFitLoss.MSE
            StackSLMetric.LPIPS, StackSLMetric.LPIPS_PIXEL -> StackSLFitLoss.LPIPS_PIXEL
        }
        val result = runCatching {
            val wired = StackSLWires.lift(program.text, "program")
            val wires = wired.literalWires
            val columns = StackSLProposals.tokenColumns(program.text)
            val chosen = if (literals == null) wires.map { it.index } else {
                val wanted = literals.mapNotNull { columns.getOrNull(it) }.toSet()
                wires.filter { (it.column ?: -1) in wanted || ((it.column ?: -1) - 1) in wanted }.map { it.index }
            }
            require(chosen.isNotEmpty()) { "no literal to move" + if (literals != null) " at token(s) $literals" else "" }
            StackSLFit.fit(
                program.text, targetOf(dim).raster, template = StackSLCpu.stillTemplate(time), dim = dim,
                iterations = iterations, learningRate = learningRate, indices = chosen, parallel = parallel,
                annealing = AdAnnealing.stepOnly(iterations), loss = descent, lpipsSide = stage.lpipsSide(),
                lpipsGrid = if (descent != StackSLFitLoss.MSE && stage.lpipsGrid >= 2) stage.lpipsGrid else 0
            )
        }
        val fitted = result.getOrElse { e ->
            return@timed StackSLFitOutcome(program.text, null, null, null, null, null, e.message ?: e.toString())
        }
        val before = objective.evaluate(program).fitness
        val outcome = inject(listOf(fitted.fittedProgram), origin).single()
        notes += "gen $generation: fit ${fitted.fittedIndices.size} literal(s) of a %.3f program, ${descent.label} %.4f -> %.4f, skill %s".format(
            skill(before), fitted.seedLoss, fitted.fittedLoss, outcome.skill?.let { "%.3f".format(it) } ?: outcome.problem
        )
        StackSLFitOutcome(program.text, fitted.fittedProgram, fitted.seedLoss, fitted.fittedLoss, skill(before), outcome.skill, outcome.problem)
    }

    /**
     * Bring in [other]'s survivors: its top [count] programs as they are, and [count] one-point
     * recombinants of its leaders with this population's. A rewound conversation can breed with
     * the branch it left this way.
     */
    fun crossbreed(other: StackSLEvolutionSnapshot, count: Int = max(2, settings.mu / 2)): List<StackSLProposalOutcome> {
        ensureStartedQuietly()
        val theirs = other.islands.flatten().sortedByDescending { it.fitness }.map { it.program }.distinct().take(count)
        val mine = ranked.take(count).map { it.genome }
        val rng = rngFor(generation, 997)
        val recombinants = if (mine.isEmpty() || theirs.isEmpty()) emptyList() else (0 until count).map {
            val a = mine[rng.nextInt(mine.size)]
            val b = StackSLProgram.of(theirs[rng.nextInt(theirs.size)])
            (if (rng.nextBoolean()) genome.crossover(a, b, rng) else genome.crossover(b, a, rng)).text
        }
        val label = other.label.ifBlank { "another population" }
        val out = inject(theirs, "crossbreed", island = -1) + inject(recombinants, "crossbreed+crossover")
        notes += "gen $generation: crossbred with '$label': ${out.count { it.rank != null }} of ${out.size} entered"
        return out
    }

    // ------------------------------------------------------------------------------------------
    // the watchmaker
    // ------------------------------------------------------------------------------------------

    /**
     * One watchmaker round now: show the model the target and the top [parents], take [proposals]
     * programs back, and offer them as `watchmaker`. Waits for the reply.
     */
    fun askWatchmaker(proposals: Int = settings.proposals, parents: Int = settings.parents): List<StackSLProposalOutcome> {
        val model = watchmaker ?: throw IllegalStateException("no watchmaker: set evolution.watchmaker first")
        ensureStartedQuietly()
        val leaders = ranked.take(parents)
        val reply = timed("watchmaker") { model.propose(request(leaders, proposals)) }
        return absorb(reply, leaders)
    }

    private fun watchmakerTurn() {
        val model = watchmaker ?: return
        val s = settings
        pending?.let { f ->
            if (f.isDone || s.watchmakerWait) {
                pending = null
                val reply = timed("watchmaker") { runCatching { f.get() } }
                reply.onSuccess { absorb(it, pendingFor) }
                    .onFailure { notes += "gen $generation: watchmaker failed: ${(it.cause ?: it).message}" }
            }
        }
        if (pending != null) return
        val due = (s.watchmakerEvery > 0 && generation % s.watchmakerEvery == 0) ||
            (s.watchmakerOnStall > 0 && sinceImprovement >= s.watchmakerOnStall && generation - lastAsked >= s.watchmakerOnStall)
        if (!due) return
        lastAsked = generation
        val leaders = ranked.take(s.parents)
        val request = timed("watchmaker") { request(leaders, s.proposals) }
        pendingFor = leaders
        pending = asker.submit<StackSLProposalReply> { model.propose(request) }
        if (s.watchmakerWait) watchmakerTurn()
    }

    private fun absorb(reply: StackSLProposalReply, leaders: List<Individual<StackSLProgram>>): List<StackSLProposalOutcome> {
        usage = usage + reply.usage
        val outcomes = reply.proposals.map { p ->
            val parent = p.parent?.let { leaders.getOrNull(it - 1) }
            inject(listOf(p.program), if (parent == null) "watchmaker:new" else "watchmaker:edit", island = -1).single().copy(why = p.why)
        }
        lastRound = outcomes
        val entered = outcomes.count { it.rank != null }
        val bestSkill = outcomes.mapNotNull { it.skill }.maxOrNull()
        notes += "gen $generation: watchmaker (${reply.usage.model}) proposed ${reply.proposals.size}, $entered entered" +
            (bestSkill?.let { ", best skill %.3f".format(it) } ?: "") +
            (reply.problem?.let { "; $it" } ?: "") +
            "; ${reply.usage.inputTokens} in (${reply.usage.cachedTokens} cached), ${reply.usage.outputTokens} out, %.1fs".format(reply.usage.millis / 1000.0)
        return outcomes
    }

    /** What the watchmaker is shown. Public so a person can read exactly what a model would see. */
    fun request(leaders: List<Individual<StackSLProgram>> = ranked.take(settings.parents), proposals: Int = settings.proposals): StackSLProposalRequest {
        ensureStartedQuietly()
        val t = target
        val st = stage
        val sheet = thumbnailSheet(leaders.map { it.genome })
        return StackSLProposalRequest(
            targetName = t.name,
            targetColours = StackSLProposals.describeColours(t),
            metric = st.describe(),
            caseLabels = caseLabels(st),
            generation = generation,
            stalled = sinceImprovement,
            parents = leaders.mapIndexed { i, ind ->
                StackSLParentView(i + 1, ind.genome.text, ind.fitness, skill(ind.fitness), ind.evaluation.cases.toList(), ind.origin)
            },
            proposals = proposals,
            maxTokens = settings.maxTokens,
            lastRound = lastRound,
            sheetPng = sheet.toPngBytes()
        )
    }

    private fun caseLabels(st: StackSLStage): List<String> {
        val lpips = if (st.lpipsGrid >= 2 && st.metric != StackSLMetric.PIXEL) gridLabels(st.lpipsGrid).map { "lpips $it" } else emptyList()
        val pixel = if (st.metric != StackSLMetric.LPIPS) gridLabels(4).map { "pixel $it" } else emptyList()
        return lpips + pixel
    }

    // ------------------------------------------------------------------------------------------
    // looking
    // ------------------------------------------------------------------------------------------

    /** The target and the [top] programs, rendered at [side], labelled by rank and skill. */
    fun contactSheet(top: Int = 6, side: Int = 128): Img {
        ensureStartedQuietly()
        val panels = ArrayList<Pair<String, Img>>()
        panels += "target" to target.raster.toImg().upscaleNearest(max(1, side / target.raster.width))
        ranked.take(top).forEachIndexed { i, ind ->
            panels += "#${i + 1} %.2f".format(skill(ind.fitness)) to StackSLCpu.renderStill(ind.genome.text, dim = side, time = time, parallel = parallel).toImg()
        }
        return montage(panels, columns = min(panels.size, 4), cellSide = side + 8)
    }

    /** What the watchmaker sees: the target and [programs] at 64 px, labelled `target`, `#1`, `#2`... */
    fun thumbnailSheet(programs: List<StackSLProgram>, side: Int = THUMBNAIL): Img {
        val panels = ArrayList<Pair<String, Img>>()
        panels += "target" to target.raster.toImg().upscaleNearest(max(1, side / target.raster.width)).let {
            if (it.width == side) it else it.resize(side, side)
        }
        programs.forEachIndexed { i, p ->
            val img = runCatching { StackSLCpu.renderStill(p.text, dim = side, time = time, parallel = parallel).toImg() }
                .getOrElse { Img(side, side, 3, FloatArray(side * side * 3)) }
            panels += "#${i + 1}" to img
        }
        return montage(panels, columns = min(panels.size, 5), cellSide = side + 4, gap = 6)
    }

    /**
     * The held-out check: [StackSLLayout.layoutAgreement] of [program] against the target at [dim].
     * For reports only; nothing in the search reads it.
     */
    fun layoutOf(program: StackSLProgram, dim: Int = stage.dim): Double =
        StackSLLayout.layoutAgreement(StackSLCpu.renderStill(program.text, dim = dim, time = time, parallel = parallel), targetOf(dim).raster)

    /** A few lines: where the search is, its leader, and which origins have earned their keep. */
    fun summary(): String = buildString {
        val b = best
        appendLine("%s: generation %d, %d renders, stage %s, %d island(s) of %d".format(
            targetName, generation, renders, stage.describe(), pools.size, settings.mu))
        if (b != null) {
            appendLine("best skill %.3f (fitness %.4f) via %s, layout %.2f".format(skill(b.fitness), b.fitness, b.origin, layoutOf(b.genome)))
            appendLine("  ${b.genome.text}")
        }
        val usageLine = usage.takeIf { it.calls > 0 }?.toString()
        if (usageLine != null) appendLine("watchmaker: $usageLine")
        appendLine("origin            offered survived   leads")
        credit.forEach { (k, c) -> appendLine("%-16s %8d %8d %7d".format(k, c.offered, c.survived, c.leads)) }
    }

    override fun toString(): String = summary()

    // ------------------------------------------------------------------------------------------
    // settings, snapshot and restore
    // ------------------------------------------------------------------------------------------

    /** Change the settings between generations. A changed stage re-scores the population. */
    fun configure(change: (StackSLEvolveSettings) -> StackSLEvolveSettings): StackSLEvolution {
        val next = change(settings)
        next.validate()
        settings = next
        notes += "gen $generation: settings changed"
        return this
    }

    /** Everything needed to put this search back as it is now, as plain data. */
    fun snapshot(label: String = ""): StackSLEvolutionSnapshot = StackSLEvolutionSnapshot(
        label = label,
        target = targetName,
        seed = seed,
        generation = generation,
        renders = renders,
        settings = settings,
        islands = pools.map { pool -> pool.map { StackSLSnapshotMember(it.genome.text, it.origin, it.fitness, skill(it.fitness)) } },
        history = history.toList(),
        improvements = improvements.toList(),
        notes = notes.toList(),
        credit = credit.mapValues { it.value.copy() },
        usage = usage,
        lastRound = lastRound,
        savedAt = System.currentTimeMillis()
    )

    /** Put the search back to [snapshot]. Programs are re-scored, which costs nothing for one already seen. */
    fun restore(snapshot: StackSLEvolutionSnapshot): StackSLEvolution {
        pending = null
        settings = snapshot.settings.also { it.validate() }
        generation = snapshot.generation
        renders = snapshot.renders
        history.clear(); history += snapshot.history
        improvements.clear(); improvements += snapshot.improvements
        notes.clear(); notes += snapshot.notes
        credit.clear(); credit += snapshot.credit.mapValues { it.value.copy() }
        usage = snapshot.usage
        lastRound = snapshot.lastRound
        pools = ArrayList()
        stageKey = null
        enterStage()
        pools = snapshot.islands.map { members ->
            members.mapNotNull { m -> score(StackSLProposals.program(m.program))?.let { Individual(it.genome, it.evaluation, m.origin) } }
                .sortedByDescending { it.fitness }.toMutableList()
        }.toMutableList()
        survivedCounted.clear()
        survivedCounted += pools.flatten()
        leaderFitness = best?.fitness ?: Double.NEGATIVE_INFINITY
        rebase = false
        sinceImprovement = history.reversed().takeWhile { it.leaderOrigin == null }.size
        return this
    }

    /** The search as it would be before its first generation: nothing in it, the same settings. */
    fun reset(): StackSLEvolution = restore(StackSLEvolutionSnapshot(label = "fresh", target = targetName, seed = seed, settings = settings))

    // ------------------------------------------------------------------------------------------
    // bookkeeping
    // ------------------------------------------------------------------------------------------

    private fun ensureStartedQuietly() {
        if (pools.size != settings.islands || pools.any { it.size < min(settings.mu, 2) }) ensureStarted() else enterStage()
    }

    private fun targetOf(dim: Int): StackSLTarget = targets.getOrPut(dim) { targetAt(dim) }

    private fun objectiveFor(st: StackSLStage): StackSLImageObjective = objectives.getOrPut(st.key) {
        val t = targetOf(st.dim)
        StackSLImageObjective(
            t, dim = st.dim, time = time, parallel = parallel, metric = st.metric, cache = StackSLScoreCache(),
            lpipsSide = st.lpipsSide(), lpipsRegions = st.lpipsGrid >= 2 && st.metric != StackSLMetric.PIXEL,
            lpipsGrid = max(2, st.lpipsGrid)
        )
    }

    /** Re-score every member when the stage has changed since the last generation; costs renders. */
    private fun enterStage() {
        val st = stage
        if (stageKey == st.key) return
        val first = stageKey == null
        stageKey = st.key
        if (pools.isEmpty()) return
        if (!first) notes += "gen $generation: stage ${st.describe()}"
        for (i in pools.indices) {
            pools[i] = pools[i].mapNotNull { m ->
                consider(m.genome, m.origin, countOrigin = false)?.let { Individual(it.genome, it.evaluation, m.origin) }
            }.sortedByDescending { it.fitness }.toMutableList()
        }
        survivedCounted.clear()
        survivedCounted += pools.flatten()
        // A new stage's scores are on a new scale: whoever leads now leads, and earns no credit for it.
        rebase = true
    }

    private var leaderFitness = Double.NEGATIVE_INFINITY
    private var rebase = false

    /** Score without spending budget: for a restore, where the render already happened once. */
    private fun score(program: StackSLProgram): Individual<StackSLProgram>? =
        runCatching { Individual(program, objective.evaluate(program), "restored") }.getOrNull()

    /** Score a candidate, charging a render when it was not in the cache. `null` when evaluation threw. */
    private fun consider(program: StackSLProgram, origin: String, countOrigin: Boolean = true): Individual<StackSLProgram>? {
        val o = objective
        val cache = o.cache!!
        val before = cache.size
        val evaluation = runCatching { o.evaluate(program) }.getOrNull() ?: return null
        renders += cache.size - before
        if (countOrigin) creditOf(origin).offered++
        return Individual(program, evaluation, origin)
    }

    /** Sort, drop duplicates, keep µ, and count each newcomer that stays, once, by origin. */
    private fun trim(pool: MutableList<Individual<StackSLProgram>>) {
        pool.sortWith(compareByDescending { it.fitness })
        val kept = PopulationSearch.truncate(genome, pool, settings.mu)
        pool.clear()
        pool += kept
        kept.forEach { if (survivedCounted.add(it) && it.origin !in UNCREDITED) creditOf(it.origin).survived++ }
        if (survivedCounted.size > 4 * settings.mu * pools.size + 64) survivedCounted.retainAll(pools.flatten().toSet())
    }

    // Identity, not equality: Individual is not a data class, so a re-scored member is a newcomer
    // only if it was never counted, which enterStage and restore see to.
    private val survivedCounted = HashSet<Individual<StackSLProgram>>()

    private fun noteLeader(initialOrigin: String?) {
        val b = best ?: return
        if (rebase) {
            rebase = false
            leaderFitness = b.fitness
            improvements += StackSLImprovement(generation, renders, b.fitness, skill(b.fitness), "stage ${stage.key}", b.genome.text)
            return
        }
        val improved = b.fitness > leaderFitness + 1e-12
        if (improved) {
            leaderFitness = b.fitness
            sinceImprovement = 0
            if (initialOrigin == null) creditOf(b.origin).leads++
            improvements += StackSLImprovement(generation, renders, b.fitness, skill(b.fitness), b.origin, b.genome.text)
        } else sinceImprovement++
        if (initialOrigin == null) {
            history += StackSLGenerationRecord(generation, renders, stage.key, b.fitness, skill(b.fitness), if (improved) b.origin else null)
        }
    }

    private fun creditOf(origin: String): StackSLOriginCredit = credit.getOrPut(originFamily(origin)) { StackSLOriginCredit() }

    private fun rngFor(generation: Int, island: Int): Random = Random(seed * 1_000_003L + generation * 7_919L + island)

    private inline fun <T> timed(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            millis[what] = (millis[what] ?: 0.0) + (System.nanoTime() - t0) / 1e6
        }
    }

    companion object {
        /** The side the watchmaker sees each render at. */
        const val THUMBNAIL: Int = 64

        /** Origins that are bookkeeping rather than variation, and so earn no survival credit. */
        private val UNCREDITED = setOf("random", "restored", "migrant")

        /** Group origins the way [credit] reports them: `crossover+point` is `crossover`. */
        fun originFamily(origin: String): String = when {
            origin.startsWith("crossover+") -> "crossover"
            else -> origin
        }

        /** Labels for an n by n grid of cases, row by row from the top left: `r1c1`, `r1c2`... */
        fun gridLabels(n: Int): List<String> = (0 until n * n).map { "r${it / n + 1}c${it % n + 1}" }

        /** A target at other resolutions: area-averaged from [base], or [base] itself at its own size. */
        fun resample(base: StackSLTarget): (Int) -> StackSLTarget = { dim ->
            if (dim == base.raster.width) base
            else StackSLTargets.byName(base.name, dim) ?: StackSLTarget.of(base.name, base.raster.toImg(), dim)
        }
    }
}

/**
 * One stage of a schedule: how many generations it lasts and what fitness means during it.
 *
 * @property lpipsGrid regions per side scored as lexicase cases under LPIPS: 2 is the quadrants, 3
 *   the ninths, 0 none. Ignored under [StackSLMetric.PIXEL], whose cases are its own 4 by 4 grid.
 */
data class StackSLStage(
    val generations: Int = Int.MAX_VALUE,
    val dim: Int = DEFAULT_FITNESS_DIM,
    val metric: StackSLMetric = StackSLMetric.LPIPS,
    val lpipsGrid: Int = 2
) {
    /** What identifies the stage's objective and score cache. */
    val key: String get() = "${metric.name.lowercase()}@$dim/g$lpipsGrid"

    /** The side LPIPS sees: the smallest multiple of [dim] that fits the network and the grid. */
    fun lpipsSide(): Int {
        var side = dim
        while (side < StackSLImageObjective.DEFAULT_LPIPS_SIDE || (lpipsGrid >= 2 && (side < lpipsGrid * afield.onnx.Lpips.MIN_SIDE || side % lpipsGrid != 0))) side += dim
        return side
    }

    fun describe(): String = buildString {
        append(metric.name)
        append(" at ${dim}px")
        if (metric != StackSLMetric.PIXEL && lpipsGrid >= 2) append(", ${lpipsGrid}x$lpipsGrid region cases")
    }

    fun validate() {
        require(generations >= 1) { "a stage lasts at least one generation, got $generations" }
        require(dim in 8..256 && dim % 4 == 0) { "stage dim must be a multiple of 4 in 8..256, got $dim" }
        require(lpipsGrid == 0 || lpipsGrid in 2..4) { "lpipsGrid must be 0 or 2..4, got $lpipsGrid" }
    }
}

/** Everything about how a [StackSLEvolution] searches. Plain data, so a snapshot carries it. */
data class StackSLEvolveSettings(
    val mu: Int = 12,
    val lambda: Int = 24,
    val islands: Int = 1,
    val migrateEvery: Int = 8,
    val migrants: Int = 2,
    val selection: Selection = Selection.LEXICASE,
    val tournament: Int = 3,
    val crossoverRate: Double = 0.25,
    val immigrantRate: Double = 0.05,
    val maxTokens: Int = 32,
    /** The schedule. The last stage runs for ever, whatever its [StackSLStage.generations] says. */
    val stages: List<StackSLStage> = listOf(StackSLStage()),
    /** Every this many generations, a round of Adam on the leader's literals; 0 never. */
    val fitEvery: Int = 0,
    val fitIterations: Int = 16,
    val fitLearningRate: Double = 0.05,
    /** Every this many generations, a watchmaker round; 0 never (unless [watchmakerOnStall]). */
    val watchmakerEvery: Int = 0,
    /** A watchmaker round once the leader has not improved for this many generations; 0 never. */
    val watchmakerOnStall: Int = 0,
    /** Wait for the watchmaker's reply before the next generation (else it lands when it lands). */
    val watchmakerWait: Boolean = true,
    /** Programs asked of the watchmaker per round. */
    val proposals: Int = 8,
    /** Leaders it is shown per round. */
    val parents: Int = 4
) {
    /** The stage generation [g] runs under. */
    fun stageAt(g: Int): StackSLStage {
        var start = 0
        for ((i, st) in stages.withIndex()) {
            if (i == stages.size - 1 || g < start + st.generations) return st
            start += st.generations
        }
        return stages.last()
    }

    fun validate() {
        require(mu >= 1 && lambda >= 1) { "mu and lambda must be >= 1" }
        require(islands in 1..16) { "islands must be in 1..16, got $islands" }
        require(crossoverRate in 0.0..1.0 && immigrantRate in 0.0..1.0) { "rates must be in 0..1" }
        require(maxTokens in 3..96) { "maxTokens must be in 3..96, got $maxTokens" }
        require(stages.isNotEmpty()) { "a schedule needs at least one stage" }
        stages.forEach { it.validate() }
        require(proposals in 1..32 && parents in 1..9) { "proposals must be in 1..32 and parents in 1..9" }
    }

    companion object {
        /**
         * Wider and deeper than [evolveStackSLTowards]: three islands of 12, programs up to 32 words,
         * a coarse 16 px pixel-error stage, then LPIPS on the quadrants, then LPIPS on ninths.
         */
        fun wide(): StackSLEvolveSettings = StackSLEvolveSettings(
            islands = 3,
            stages = listOf(
                StackSLStage(generations = 6, dim = 16, metric = StackSLMetric.LPIPS_PIXEL, lpipsGrid = 2),
                StackSLStage(generations = 20, dim = 32, metric = StackSLMetric.LPIPS, lpipsGrid = 2),
                StackSLStage(dim = 32, metric = StackSLMetric.LPIPS, lpipsGrid = 3)
            )
        )
    }
}

/** One generation's leader. [leaderOrigin] is set only in a generation where the leader improved. */
data class StackSLGenerationRecord(
    val generation: Int = 0,
    val renders: Int = 0,
    val stage: String = "",
    val bestFitness: Double = 0.0,
    val bestSkill: Double = 0.0,
    val leaderOrigin: String? = null
)

/** A new overall leader. */
data class StackSLImprovement(
    val generation: Int = 0,
    val renders: Int = 0,
    val fitness: Double = 0.0,
    val skill: Double = 0.0,
    val origin: String = "",
    val program: String = ""
)

/**
 * What a [StackSLEvolution.fit] did: the loss the descent ran on before and after, and the skill
 * under the search's own metric before and after. [problem] says why there is no fitted program.
 */
data class StackSLFitOutcome(
    val program: String,
    val fitted: String?,
    val lossBefore: Double?,
    val lossAfter: Double?,
    val skillBefore: Double?,
    val skillAfter: Double?,
    val problem: String?
)

/** How one origin of candidates has done. */
data class StackSLOriginCredit(var offered: Int = 0, var survived: Int = 0, var leads: Int = 0)

/** A member of a saved island. */
data class StackSLSnapshotMember(val program: String = "", val origin: String = "", val fitness: Double = 0.0, val skill: Double = 0.0)

/** A [StackSLEvolution] as plain data. [StackSLEvolutionSnapshot.write] and [read] are its JSON. */
data class StackSLEvolutionSnapshot(
    val version: Int = 1,
    val label: String = "",
    val target: String = "",
    val seed: Long = 1L,
    val generation: Int = 0,
    val renders: Int = 0,
    val settings: StackSLEvolveSettings = StackSLEvolveSettings(),
    val islands: List<List<StackSLSnapshotMember>> = emptyList(),
    val history: List<StackSLGenerationRecord> = emptyList(),
    val improvements: List<StackSLImprovement> = emptyList(),
    val notes: List<String> = emptyList(),
    val credit: Map<String, StackSLOriginCredit> = emptyMap(),
    val usage: StackSLModelUsage = StackSLModelUsage(),
    val lastRound: List<StackSLProposalOutcome> = emptyList(),
    val savedAt: Long = 0L
) {
    /** The best saved program and its skill, or `null` for an empty snapshot. */
    val best: StackSLSnapshotMember? get() = islands.flatten().maxByOrNull { it.fitness }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, this)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        fun read(file: File): StackSLEvolutionSnapshot = mapper.readValue(file)
        fun fromJson(json: String): StackSLEvolutionSnapshot = mapper.readValue(json)
        fun toJson(s: StackSLEvolutionSnapshot): String = mapper.writeValueAsString(s)
    }
}

/**
 * Saved populations by id, append-only, in a directory: what [StackSLEvolution.crossbreed] reads.
 *
 * The AI4 studio files a snapshot here at the end of every chat turn, so a population from a branch
 * the conversation has since left, or from before a rewind, is still here to breed with.
 */
class StackSLPopulationArchive(val directory: File) {
    /** File [snapshot] under a new id and return the id. */
    fun add(snapshot: StackSLEvolutionSnapshot): String {
        directory.mkdirs()
        var n = (directory.list()?.count { it.endsWith(".json") } ?: 0) + 1
        var id: String
        do { id = "p%03d".format(n++) } while (File(directory, "$id.json").exists())
        snapshot.copy(label = snapshot.label.ifBlank { id }).write(File(directory, "$id.json"))
        return id
    }

    /** The snapshot filed as [id], or `null`. */
    fun get(id: String): StackSLEvolutionSnapshot? =
        File(directory, "${id.removeSuffix(".json")}.json").takeIf { it.isFile }?.let { runCatching { StackSLEvolutionSnapshot.read(it) }.getOrNull() }

    /** Every id, oldest first, with its label, generation and best skill. */
    fun list(): List<Pair<String, StackSLEvolutionSnapshot>> =
        directory.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { f.nameWithoutExtension to StackSLEvolutionSnapshot.read(f) }.getOrNull() }
}

/** An [Img] as a PNG data URL, for a model or an `<img>`. */
fun Img.toPngDataUrl(): String = "data:image/png;base64," + Base64.getEncoder().encodeToString(toPngBytes())
