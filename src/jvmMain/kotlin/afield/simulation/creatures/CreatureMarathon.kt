/**
 * Long, resumable creature evolutions that leave a file of finished creatures behind.
 *
 * [CreatureMarathon.run] works through a [MarathonPlan] in two stages. First come independent
 * exploration runs, each a whole [CreatureEvolution.evolve] from a fresh random population with its own
 * seed. Then the best exploration champions each start several refinement runs, which are evolutions
 * seeded with that champion alone ([EvolutionConfig.seedGenomes]) and given no immigrants, so every
 * refined creature is a descendant of its parent and scores at least as well as it.
 *
 * Every finished run is written straight away to `marathon.json` in the run's folder, as a
 * [MarathonCreature]: its score, its measurements and its whole [CreatureGenome]. Starting the same
 * folder again skips the runs already in it, so a marathon killed half way resumes where it stopped,
 * and starting it with a bigger plan (more runs, more champions, more refinements) only runs what is
 * new. [CreatureMarathon.load] reads the file back in a box; [MarathonState.best] picks creatures from
 * it and [MarathonCreature.simulate] plays one in the world it evolved in.
 *
 * The worlds are the two from the evolving creatures page, in [CreatureWorlds]: bugs on a water
 * surface (the page's 3D example) and crawlers on a floor.
 */

package afield.simulation.creatures

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * The surroundings the marathon's creatures evolve in, as a [ContactModel] and a fitness each. These
 * are the contact models written out on the evolving creatures page, kept here so that a creature read
 * back from a marathon file can be simulated in exactly the world that scored it.
 */
object CreatureWorlds {
    /**
     * Seen from above with no gravity: a foot that touches down grips the surface film where it landed
     * and is pulled back toward that spot, and a foot that is up drags through the water.
     */
    class WaterSurface : ContactModel {
        override fun foot(index: Int) = object : ContactModel.Foot {
            var planted: Float2? = null
            override var grip = 0f
            override val anchor
                get() = planted

            override fun force(step: FootStep): Float2 {
                val g = step.genome
                grip = step.contact
                if (step.contact <= 0.02f) {
                    planted = null
                    return step.velocity * -g.freeDrag
                }
                val at = planted ?: step.position.also { planted = it }
                val drag = max(g.freeDrag, g.footDrag * step.contact)
                return (at - step.position) * (g.footStickiness * step.contact) - step.velocity * drag
            }
        }
    }

    /**
     * Seen side-on under gravity: the floor pushes back anything below `y = 0`, a foot grips only when
     * its program says so and it is on the floor, and a point that is not gripping slides with
     * [friction]. [place] lifts the body onto the floor before the run starts.
     *
     * @param stiffness how hard the floor pushes back a point that has sunk into it
     * @param damping how much of a sinking point's speed the floor takes away
     * @param friction the drag on a point sliding along the floor without gripping
     */
    class Floor(val stiffness: Float = 900f, val damping: Float = 40f, val friction: Float = 1f) : ContactModel {
        override fun place(points: List<Float2>): List<Float2> {
            val lowest = points.minOf { it.y }
            return points.map { Float2(it.x, it.y - lowest) }
        }

        override fun foot(index: Int) = object : ContactModel.Foot {
            var planted: Float2? = null
            override var grip = 0f
            override val anchor
                get() = planted

            override fun force(step: FootStep): Float2 {
                val g = step.genome
                val p = step.position
                val v = step.velocity
                val onFloor = p.y <= 0.02f
                val up = if (p.y < 0f) -p.y * stiffness - min(v.y, 0f) * damping else 0f
                grip = if (onFloor) step.contact else 0f
                if (grip <= 0.02f) {
                    planted = null
                    val slide = if (onFloor) friction else g.freeDrag * 0.1f
                    return Float2(-v.x * slide, up - v.y * g.freeDrag * 0.1f)
                }
                val at = planted ?: Float2(p.x, 0f).also { planted = it }
                return Float2((at.x - p.x) * g.footStickiness * grip - v.x * g.footDrag * grip, up)
            }
        }
    }

    /** The water world and its fitness: distance, less springs stretched and motor effort spent. */
    fun water(measuredCycles: Int = 6): CreatureSimulationConfig =
        CreatureSimulationConfig(
            measuredCycles = measuredCycles,
            contact = WaterSurface(),
            fitness = { e -> e.netCentroidMotion - 2f * e.meanStretchError - 0.05f * e.meanActuationEffort },
        )

    /**
     * The floor world and its fitness: sustained travel either way along the floor, with motors strong
     * enough to lift a limb against gravity.
     */
    fun floor(measuredCycles: Int = 6): CreatureSimulationConfig =
        CreatureSimulationConfig(
            warmupCycles = 2,
            measuredCycles = measuredCycles,
            gravity = -9.8f,
            contact = Floor(),
            fitness = { e ->
                2f * max(e.sustainedTravel(Float2(1f, 0f)), e.sustainedTravel(Float2(-1f, 0f))) - e.meanStretchError
            },
            maxActuationBudgetPerCycle = 40f,
            maxJointImpulsePerStep = 0.2f,
            maxCenterSpeed = 4f,
        )

    /** The world called [name], `"water"` or `"floor"`, watched for [measuredCycles]. */
    fun named(name: String, measuredCycles: Int = 6): CreatureSimulationConfig =
        when (name) {
            "water" -> water(measuredCycles)
            "floor" -> floor(measuredCycles)
            else -> throw IllegalArgumentException("unknown creature world '$name' (expected water or floor)")
        }
}

/**
 * How big a marathon is. [exploreRuns] independent searches of [explorePopulation] creatures for
 * [exploreGenerations] generations each; then the best [champions] of their winners each get
 * [refinementsPerChampion] refinement runs of [refinePopulation] for [refineGenerations], seeded with
 * that champion. The defaults are fifty exploration runs and twenty champions refined five times each,
 * which leaves a hundred refined creatures.
 *
 * Raising any count and running the same folder again adds only the runs that are missing. Champions
 * are taken in order of score, so raising [champions] from 20 to 30 keeps the first twenty and refines
 * ten more.
 *
 * @param world `"water"` or `"floor"`, see [CreatureWorlds.named]
 * @param minPoints the fewest points a body may have
 * @param maxPoints the most points a body may have
 * @param targetEdgeCount how many springs a new body is grown with, or null to leave it to chance
 * @param alwaysUpFootFraction the share of points that are body only, see [EvolutionConfig]
 * @param cyclingFootFraction the share of points that must step, see [EvolutionConfig]
 * @param measuredCycles how many gait cycles each creature is scored over during the search
 * @param longMeasuredCycles how many cycles each finished creature is watched for, for
 *   [MarathonCreature.longFitness]
 * @param stagnationGenerations generations without improvement before an exploration run raises its
 *   mutation and immigration
 * @param baseSeed where the runs' random seeds start; run `k` uses `baseSeed + k`
 */
@Serializable
data class MarathonPlan(
    val world: String = "water",
    val exploreRuns: Int = 50,
    val explorePopulation: Int = 100,
    val exploreGenerations: Int = 300,
    val champions: Int = 20,
    val refinementsPerChampion: Int = 5,
    val refinePopulation: Int = 60,
    val refineGenerations: Int = 200,
    val minPoints: Int = 5,
    val maxPoints: Int = 8,
    val targetEdgeCount: Int? = 8,
    val alwaysUpFootFraction: Float = 0.5f,
    val cyclingFootFraction: Float = 0.5f,
    val measuredCycles: Int = 6,
    val longMeasuredCycles: Int = 24,
    val stagnationGenerations: Int = 40,
    val baseSeed: Int = 1,
) {
    /** The world every run of this plan is scored in. */
    fun simulation(cycles: Int = measuredCycles): CreatureSimulationConfig = CreatureWorlds.named(world, cycles)

    /** The settings of exploration run [run]: a fresh random population with its own seed. */
    fun exploreConfig(run: Int): EvolutionConfig =
        EvolutionConfig(
            populationSize = explorePopulation,
            generations = exploreGenerations,
            eliteCount = max(2, explorePopulation / 12),
            minPoints = minPoints,
            maxPoints = maxPoints,
            targetEdgeCount = targetEdgeCount,
            alwaysUpFootFraction = alwaysUpFootFraction,
            cyclingFootFraction = cyclingFootFraction,
            stagnationGenerations = stagnationGenerations,
            simulation = simulation(),
            randomSeed = baseSeed + run,
        )

    /**
     * The settings of refinement [index] of [parent]: a population of the parent and its mutants, no
     * immigrants, and gentler mutation, so the run explores around the parent instead of replacing it.
     */
    fun refineConfig(parent: MarathonCreature, index: Int): EvolutionConfig =
        EvolutionConfig(
            populationSize = refinePopulation,
            generations = refineGenerations,
            eliteCount = max(2, refinePopulation / 12),
            mutationSteps = 1..3,
            immigrantsPerGeneration = 0,
            immigrantFraction = 0f,
            stagnationImmigrantFraction = 0f,
            stagnationGenerations = stagnationGenerations,
            minPoints = minPoints,
            maxPoints = maxPoints,
            targetEdgeCount = targetEdgeCount,
            alwaysUpFootFraction = alwaysUpFootFraction,
            cyclingFootFraction = cyclingFootFraction,
            simulation = simulation(),
            randomSeed = 1_000_003 * (index + 1) + parent.seed * 7919 + baseSeed,
            seedGenomes = [parent.genome],
        )
}

/**
 * One finished creature from a marathon: which run made it, what it scored, and the genome to rebuild
 * it from.
 *
 * [fitness] is the score selection used, over [MarathonPlan.measuredCycles]. [longFitness] is the same
 * fitness measured over [MarathonPlan.longMeasuredCycles] and scaled back to the shorter length, so the
 * two compare directly; a creature whose long score falls well below its short one had a gait that
 * only worked for as long as it was being judged. [history] is the champion's score after every
 * generation of its run.
 *
 * @param id `explore-07`, or `explore-07/refine-3` for the fourth refinement of that champion
 * @param stage `"explore"` or `"refine"`
 * @param parent the id of the champion a refinement started from, null for an exploration run
 * @param seed the run's random seed
 * @param fitness the score selection used
 * @param longFitness the score over the longer watch, per [MarathonPlan.measuredCycles]
 * @param netCentroidMotion how far the body's centre went over the measured cycles
 * @param displacement where the centre ended up relative to where measurement began
 * @param meanStretchError how much the springs were stretched on average
 * @param meanActuationEffort how hard the joint motors worked on average
 * @param generations how many generations the run completed
 * @param seconds how long the run took
 * @param history the run's best score after each generation
 * @param genome the creature itself
 */
@Serializable
data class MarathonCreature(
    val id: String,
    val stage: String,
    val parent: String? = null,
    val seed: Int,
    val fitness: Float,
    val longFitness: Float,
    val netCentroidMotion: Float,
    val displacement: Float2,
    val meanStretchError: Float,
    val meanActuationEffort: Float,
    val generations: Int,
    val seconds: Double,
    val history: List<Float>,
    val genome: CreatureGenome,
) {
    /** Runs this creature for [cycles] in [plan]'s world, recording frames for playback. */
    fun simulate(plan: MarathonPlan, cycles: Int = plan.longMeasuredCycles): CreatureEvaluation =
        CreatureEvolution.simulate(genome, plan.simulation(cycles))
}

/**
 * Everything a marathon folder holds: the [plan] it was last run with and every [creatures] finished
 * so far, exploration and refinement alike.
 */
@Serializable
data class MarathonState(
    val plan: MarathonPlan,
    val creatures: List<MarathonCreature> = emptyList(),
    val updated: String = "",
) {
    /** The exploration winners, best first. */
    val explored: List<MarathonCreature>
        get() = creatures.filter { it.stage == "explore" }.sortedByDescending { it.fitness }

    /** The refined creatures, best first. */
    val refined: List<MarathonCreature>
        get() = creatures.filter { it.stage == "refine" }.sortedByDescending { it.fitness }

    /** The exploration winners chosen for refinement: the best [MarathonPlan.champions] of them. */
    fun champions(count: Int = plan.champions): List<MarathonCreature> = explored.take(count)

    /**
     * The best [count] creatures, best first: refined ones only by default, or every creature with
     * [includeExplored].
     */
    fun best(count: Int = Int.MAX_VALUE, includeExplored: Boolean = false): List<MarathonCreature> =
        (if (includeExplored) creatures.sortedByDescending { it.fitness } else refined).take(count)

    /** The creature called [id], or null. */
    operator fun get(id: String): MarathonCreature? = creatures.firstOrNull { it.id == id }
}

/**
 * Runs a [MarathonPlan] in a folder, resumably, and reads one back. See the file overview for the
 * two stages.
 */
object CreatureMarathon {
    /** The name of the state file inside a marathon folder. */
    const val STATE_FILE = "marathon.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Reads the marathon in [dir], or null when it has not started. */
    fun loadOrNull(dir: File): MarathonState? {
        val file = File(dir, STATE_FILE)
        if (!file.exists()) return null
        return json.decodeFromString<MarathonState>(file.readText())
    }

    /** Reads the marathon in [dir] (a path string, as box code usually has one). */
    fun load(dir: String): MarathonState =
        loadOrNull(File(dir)) ?: throw IllegalArgumentException("no ${STATE_FILE} in $dir")

    /** Writes [state] to [dir], replacing the file in one move so a crash never leaves half of one. */
    fun save(dir: File, state: MarathonState) {
        dir.mkdirs()
        val tmp = File(dir, "$STATE_FILE.tmp")
        tmp.writeText(json.encodeToString(state))
        Files.move(tmp.toPath(), File(dir, STATE_FILE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Works through [plan] in [dir] on [threads] threads and returns the finished state. Runs already
     * in the folder's file are skipped; with [plan] null the folder's own plan is used. [log] receives
     * a line per finished run and a progress line every [progressEvery] generations of each run.
     */
    fun run(
        dir: File,
        plan: MarathonPlan? = null,
        threads: Int = max(1, Runtime.getRuntime().availableProcessors() - 2),
        progressEvery: Int = 25,
        log: (String) -> Unit = ::println,
    ): MarathonState {
        val previous = loadOrNull(dir)
        val activePlan = plan ?: previous?.plan ?: MarathonPlan()
        var state = MarathonState(activePlan, previous?.creatures ?: emptyList(), Instant.now().toString())
        save(dir, state)
        val lock = Any()
        val started = System.nanoTime()

        fun record(creature: MarathonCreature) {
            synchronized(lock) {
                state = state.copy(
                    creatures = (state.creatures.filter { it.id != creature.id } + creature).sortedBy { it.id },
                    updated = Instant.now().toString(),
                )
                save(dir, state)
                val elapsed = (System.nanoTime() - started) / 1e9 / 60
                log(
                    "done ${creature.id}: fitness %.3f (long %.3f), %d gens in %.1f min; %d creatures saved, %.1f min elapsed"
                        .format(creature.fitness, creature.longFitness, creature.generations, creature.seconds / 60, state.creatures.size, elapsed)
                )
            }
        }

        val pool = Executors.newFixedThreadPool(threads)
        try {
            val have = state.creatures.map { it.id }.toSet()
            val exploreIds = (0 until activePlan.exploreRuns).map { "explore-%02d".format(it) to it }.filter { it.first !in have }
            log("marathon in ${dir.absolutePath}: ${activePlan.world}, ${exploreIds.size} of ${activePlan.exploreRuns} exploration runs to go on $threads threads")
            exploreIds.map { (id, run) ->
                pool.submit {
                    record(evolveOne(id, "explore", null, activePlan.exploreConfig(run), activePlan, progressEvery, log))
                }
            }.awaitAll()

            val champions = state.champions(activePlan.champions)
            val refineJobs = champions.flatMap { parent ->
                (0 until activePlan.refinementsPerChampion).map { k -> Triple("${parent.id}/refine-$k", parent, k) }
            }.filter { it.first !in state.creatures.map { c -> c.id }.toSet() }
            log("refining ${champions.size} champions: ${refineJobs.size} refinement runs to go")
            refineJobs.map { (id, parent, k) ->
                pool.submit {
                    record(evolveOne(id, "refine", parent.id, activePlan.refineConfig(parent, k), activePlan, progressEvery, log))
                }
            }.awaitAll()
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
        log("marathon finished: ${state.explored.size} explored, ${state.refined.size} refined")
        return state
    }

    private fun List<Future<*>>.awaitAll() = forEach { it.get() }

    private fun evolveOne(
        id: String,
        stage: String,
        parent: String?,
        config: EvolutionConfig,
        plan: MarathonPlan,
        progressEvery: Int,
        log: (String) -> Unit,
    ): MarathonCreature {
        val t0 = System.nanoTime()
        val history = mutableListOf<Float>()
        val result = CreatureEvolution.evolve(config.copy(simulation = config.simulation.copy(recordFrames = false))) { update ->
            history += update.champion.fitness
            val g = update.generation.generation + 1
            if (progressEvery > 0 && g % progressEvery == 0) {
                log("  $id gen $g/${config.generations}: best %.3f mean %.3f".format(update.champion.fitness, update.generation.meanFitness))
            }
        }
        val best = result.best
        val long = CreatureEvolution.simulate(best.genome, plan.simulation(plan.longMeasuredCycles).copy(recordFrames = false))
        return MarathonCreature(
            id = id,
            stage = stage,
            parent = parent,
            seed = config.randomSeed,
            fitness = best.fitness,
            longFitness = long.fitness * plan.measuredCycles / plan.longMeasuredCycles,
            netCentroidMotion = best.netCentroidMotion,
            displacement = best.displacement,
            meanStretchError = best.meanStretchError,
            meanActuationEffort = best.meanActuationEffort,
            generations = result.history.size,
            seconds = (System.nanoTime() - t0) / 1e9,
            history = history,
            genome = best.genome,
        )
    }
}

/**
 * The command line for a marathon: `--dir <folder>` and any [MarathonPlan] field as `--name value`
 * (`--exploreRuns 50 --champions 20`), plus `--threads N`. Run it with
 * `./gradlew runCreatureMarathon -Pmarathon="--dir <folder> ..."`.
 */
object CreatureMarathonRun {
    /** Parses the arguments, runs the marathon and appends every line it logs to `progress.log` beside the state. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
        val dir = File(options["dir"] ?: error("--dir <folder> is required"))
        val base = CreatureMarathon.loadOrNull(dir)?.plan ?: MarathonPlan()
        val planOptions = options - "dir" - "threads"
        val plan = if (planOptions.isEmpty()) base else {
            val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
            val tree = json.parseToJsonElement(json.encodeToString(base)).let { it as kotlinx.serialization.json.JsonObject }
            val merged = kotlinx.serialization.json.JsonObject(
                tree + planOptions.mapValues { (k, v) ->
                    require(k in tree) { "unknown plan option --$k (known: ${tree.keys.joinToString()})" }
                    v.toIntOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                        ?: v.toFloatOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                        ?: kotlinx.serialization.json.JsonPrimitive(v)
                }
            )
            json.decodeFromJsonElement(MarathonPlan.serializer(), merged)
        }
        dir.mkdirs()
        val logFile = File(dir, "progress.log")
        val threads = options["threads"]?.toInt() ?: max(1, Runtime.getRuntime().availableProcessors() - 2)
        CreatureMarathon.run(dir, plan, threads) { line ->
            val stamped = "${Instant.now()} $line"
            println(stamped)
            synchronized(logFile) { logFile.appendText(stamped + "\n") }
        }
    }
}
