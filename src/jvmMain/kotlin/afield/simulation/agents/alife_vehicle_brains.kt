@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.media.image.Img
import afield.study.search.Evaluation
import afield.study.search.GenomeOps
import afield.study.search.Objective
import afield.study.search.PopulationSearch
import afield.study.search.SearchArm
import afield.study.search.SearchComparison
import afield.study.search.SearchRun
import afield.study.search.compareSearch
import afield.study.search.Variation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A larger Braitenberg vehicle genome, and an equal-budget comparison of the searches that look for one.
 *
 * [VehicleWiringGenome] is four numbers. A [VehicleBrainGenome] adds what the vehicle model can
 * express besides its wires: where the sensors point, how sensitive they are, a bias on each motor,
 * and [VehicleBrainGenome.hidden] recurrent hidden neurons between the sensors and the motors. With no
 * hidden neurons it is 8 numbers; with 8 it is 112, with 16 it is 344. [compareVehicleSearch] runs random sampling, a
 * hill climber and the (µ+λ) evolver of [PopulationSearch] at the same evaluation budget over many
 * seeds and reports distributions, which is how `braitenberg_vehicles.md` §10 asks whether evolution
 * beats random sampling and at what genome size the answer changes.
 */

// ==============================================================================================
// The brain
// ==============================================================================================

/**
 * A decoded vehicle controller. Every gene is in `-1..1`; [decode] scales them.
 *
 * Layout of the gene vector, for `H` hidden neurons:
 * `[angle, gain, biasL, biasR, d00, d01, d10, d11]` then, per hidden neuron `j`,
 * `[fromLeft, fromRight, bias, toLeft, toRight, recurrent from hidden 0..H-1]`.
 * `dms` is the direct sensor `s` → motor `m` weight, laid out as [VehicleWorld.w] lays out a vehicle.
 */
class VehicleBrain(
    /** Hidden neurons. */
    val hidden: Int,
    /** Sensor half-angle in radians, `0.1..1.5`. */
    val sensorAngle: Double,
    /** Multiplier on what the sensors read, `e^-1..e^1.5`. */
    val sensorGain: Double,
    /** Bias added to each motor neuron's input. */
    val motorBias: DoubleArray,
    /** Direct sensor→motor weights, `[2m + s]`. */
    val direct: DoubleArray,
    /** Sensor→hidden weights, `[2j + s]`. */
    val inHidden: DoubleArray,
    /** Hidden biases. */
    val hiddenBias: DoubleArray,
    /** Hidden→motor weights, `[2j + m]`. */
    val outHidden: DoubleArray,
    /** Hidden→hidden weights, `[H * to + from]`. */
    val recurrent: DoubleArray,
) {
    companion object {
        /** Genes per brain with [hidden] hidden neurons. */
        fun genes(hidden: Int): Int = 8 + hidden * (5 + hidden)

        /** Decode a gene vector; [maxWeight] scales every weight and bias. */
        fun decode(g: DoubleArray, hidden: Int, maxWeight: Double = 3.0): VehicleBrain {
            require(g.size == genes(hidden)) { "a $hidden-hidden brain has ${genes(hidden)} genes, got ${g.size}" }
            val h = hidden
            val inH = DoubleArray(2 * h)
            val bH = DoubleArray(h)
            val outH = DoubleArray(2 * h)
            val rec = DoubleArray(h * h)
            for (j in 0 until h) {
                val o = 8 + j * (5 + h)
                inH[2 * j] = g[o] * maxWeight
                inH[2 * j + 1] = g[o + 1] * maxWeight
                bH[j] = g[o + 2] * maxWeight
                outH[2 * j] = g[o + 3] * maxWeight
                outH[2 * j + 1] = g[o + 4] * maxWeight
                for (k in 0 until h) rec[h * j + k] = g[o + 5 + k] * maxWeight
            }
            return VehicleBrain(
                h,
                sensorAngle = 0.8 + 0.7 * g[0],
                sensorGain = exp(0.25 + 1.25 * g[1]),
                motorBias = doubleArrayOf(g[2] * maxWeight, g[3] * maxWeight),
                direct = DoubleArray(4) { g[4 + it] * maxWeight },
                inHidden = inH, hiddenBias = bH, outHidden = outH, recurrent = rec,
            )
        }

        /**
         * The gene vector that decodes to a classic four-weight vehicle with [VehicleWorld]'s default
         * sensor angle and the docs page's motor bias of −1, so [VehicleWorld.LOVER] can sit in the
         * same table as a searched brain.
         */
        fun classic(wiring: DoubleArray, hidden: Int = 0, maxWeight: Double = 3.0): DoubleArray {
            require(wiring.size == 4) { "wiring must be four weights" }
            val g = DoubleArray(genes(hidden))
            g[0] = (0.6 - 0.8) / 0.7
            g[1] = -0.2
            g[2] = -1.0 / maxWeight
            g[3] = -1.0 / maxWeight
            for (i in 0..3) g[4 + i] = (wiring[i] / maxWeight).coerceIn(-1.0, 1.0)
            return g
        }
    }
}

private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

/**
 * A population of [VehicleBrain] vehicles on the same torus, kinematics and lights as [VehicleWorld].
 *
 * Every vehicle carries the same brain. Motor neurons are leaky (`τ = 4`, gain 6, sigmoid) like
 * [VehicleWorld]'s; hidden neurons are leaky (`τ = 3`) sigmoid units with full recurrence, so a brain
 * with hidden neurons has memory a four-wire vehicle does not. Noise-free: a run is a function of the
 * brain and the start positions.
 *
 * @param seed seed for start positions and headings
 */
class VehicleBrainWorld(
    /** The controller every vehicle runs. */
    val brain: VehicleBrain,
    lights: List<Light>,
    /** How many vehicles. */
    val count: Int,
    seed: Long = 1L,
    /** Domain width. */
    val width: Double = 240.0,
    /** Domain height. */
    val height: Double = 240.0,
    /** Kinematics; only the body fields (`sensorDistance`, `baseSpeed`, `motorSpeed`, `axle`) are read. */
    val params: VehicleParams = VehicleParams(),
) {
    /** Lights at their current positions. */
    var lights: List<Light> = lights
        private set

    /** Body x. */
    val x: DoubleArray
    /** Body y. */
    val y: DoubleArray
    /** Headings. */
    val heading: DoubleArray
    /** Forward speed on the last step. */
    val speed: DoubleArray = DoubleArray(count)
    private val motorU = DoubleArray(2 * count)
    private val hidU = DoubleArray(brain.hidden * count)
    private val hidR = DoubleArray(brain.hidden * count)

    init {
        require(count >= 1) { "count must be >= 1" }
        val rng = Random(seed)
        x = DoubleArray(count) { rng.nextDouble() * width }
        y = DoubleArray(count) { rng.nextDouble() * height }
        heading = DoubleArray(count) { rng.nextDouble() * 2.0 * PI }
    }

    /** Brightness at a point, summed over [lights] on the torus. */
    fun light(px: Double, py: Double): Double {
        var s = 0.0
        for (l in lights) {
            val dx = wrapD(px - l.x, width)
            val dy = wrapD(py - l.y, height)
            s += l.brightnessAt(sqrt(dx * dx + dy * dy))
        }
        return s
    }

    /** Distance from vehicle [i] to the nearest light. */
    fun nearestLightDistance(i: Int): Double {
        var best = Double.MAX_VALUE
        for (l in lights) {
            val dx = wrapD(x[i] - l.x, width)
            val dy = wrapD(y[i] - l.y, height)
            best = min(best, dx * dx + dy * dy)
        }
        return sqrt(best)
    }

    /** Mean light at the bodies. */
    fun meanLight(): Double = (0 until count).sumOf { light(x[it], y[it]) } / count

    /** Mean forward speed. */
    fun meanSpeed(): Double = speed.average()

    /** Advance every vehicle one step. */
    fun step() {
        val b = brain
        val h = b.hidden
        val p = params
        val next = DoubleArray(h)
        for (i in 0 until count) {
            val hd = heading[i]
            val sl = b.sensorGain * light(x[i] + cos(hd + b.sensorAngle) * p.sensorDistance, y[i] + sin(hd + b.sensorAngle) * p.sensorDistance)
            val sr = b.sensorGain * light(x[i] + cos(hd - b.sensorAngle) * p.sensorDistance, y[i] + sin(hd - b.sensorAngle) * p.sensorDistance)
            val o = h * i
            for (j in 0 until h) {
                var inp = b.inHidden[2 * j] * sl + b.inHidden[2 * j + 1] * sr + b.hiddenBias[j]
                for (k in 0 until h) inp += b.recurrent[h * j + k] * hidR[o + k]
                next[j] = hidU[o + j] + (-hidU[o + j] + inp) / 3.0
            }
            for (j in 0 until h) { hidU[o + j] = next[j]; hidR[o + j] = sigmoid(next[j]) }
            val rates = DoubleArray(2)
            for (m in 0..1) {
                var inp = b.direct[2 * m] * sl + b.direct[2 * m + 1] * sr + b.motorBias[m]
                for (j in 0 until h) inp += b.outHidden[2 * j + m] * hidR[o + j]
                val k = 2 * i + m
                motorU[k] += (-motorU[k] + inp) / 4.0
                rates[m] = sigmoid(6.0 * motorU[k])
            }
            val vl = p.baseSpeed + p.motorSpeed * rates[0]
            val vr = p.baseSpeed + p.motorSpeed * rates[1]
            val v = 0.5 * (vl + vr)
            speed[i] = v
            heading[i] = hd + (vr - vl) / p.axle
            x[i] = wrapP(x[i] + cos(heading[i]) * v, width)
            y[i] = wrapP(y[i] + sin(heading[i]) * v, height)
        }
        if (lights.any { it.moving }) {
            lights = lights.map { if (it.moving) it.copy(x = wrapP(it.x + it.vx, width), y = wrapP(it.y + it.vy, height)) else it }
        }
    }

    /** [step] [n] times. */
    fun stepTimes(n: Int) { repeat(n) { step() } }

    /**
     * Trails of [steps] steps from here, drawn over the light field as it stands at the end, at
     * [scale] pixels per unit. Moving lights leave a faint track of their own.
     */
    fun trails(steps: Int, scale: Double = 1.2): Img {
        val w = max(1, (width * scale).roundToInt())
        val hgt = max(1, (height * scale).roundToInt())
        val trail = FloatArray(w * hgt * 3)
        fun dot(buf: FloatArray, wx: Double, wy: Double, r: Float, g: Float, bl: Float, rad: Int) {
            val px = (wx * scale).roundToInt()
            val py = (wy * scale).roundToInt()
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val o = (Math.floorMod(py + dy, hgt) * w + Math.floorMod(px + dx, w)) * 3
                buf[o] = min(1f, buf[o] + r); buf[o + 1] = min(1f, buf[o + 1] + g); buf[o + 2] = min(1f, buf[o + 2] + bl)
            }
        }
        repeat(steps) { t ->
            step()
            val a = 0.3f + 0.7f * t / steps
            for (i in 0 until count) dot(trail, x[i], y[i], a, 0.8f * a, 0.35f * a, 0)
            for (l in lights) if (l.moving) dot(trail, l.x, l.y, 0f, 0.05f, 0.12f, 0)
        }
        val out = FloatArray(w * hgt * 3)
        for (py in 0 until hgt) for (px in 0 until w) {
            val l = min(1.0, light(px / scale, py / scale)).toFloat()
            val o = (py * w + px) * 3
            out[o] = min(1f, 0.05f + 0.30f * l * l + trail[o])
            out[o + 1] = min(1f, 0.05f + 0.22f * l * l + trail[o + 1])
            out[o + 2] = min(1f, 0.08f + 0.10f * l + trail[o + 2])
        }
        for (i in 0 until count) dot(out, x[i], y[i], 1f, 1f, 1f, 1)
        return Img(w, hgt, 3, out)
    }

    private fun wrapD(d: Double, size: Double): Double {
        var v = d % size
        if (v > size / 2) v -= size
        if (v < -size / 2) v += size
        return v
    }

    private fun wrapP(v: Double, size: Double): Double {
        val r = v % size
        return if (r < 0) r + size else r
    }
}

// ==============================================================================================
// The genome
// ==============================================================================================

/**
 * A [VehicleBrain] as a [GenomeOps]: [VehicleBrain.genes]`(hidden)` numbers in `-1..1`, drawn uniformly.
 *
 * Mutations perturb one gene, or every gene a little, or redraw one gene outright. [crossover] is
 * uniform over blocks: the 8 body-and-wire genes as one block and each hidden neuron's genes as one,
 * since a neuron's weights only mean something together.
 */
class VehicleBrainGenome(val hidden: Int = 0) : GenomeOps<DoubleArray> {
    init {
        require(hidden in 0..32) { "hidden must be in 0..32, got $hidden" }
    }

    /** Genes per genome. */
    val size: Int = VehicleBrain.genes(hidden)

    override fun random(rng: Random): DoubleArray = DoubleArray(size) { rng.nextDouble() * 2.0 - 1.0 }

    override val mutations: List<Variation<DoubleArray>> = listOf(
        Variation("jitterOne") { g, rng ->
            g.copyOf().also { val i = rng.nextInt(size); it[i] = clamp(it[i] + gauss(rng) * 0.25) }
        },
        Variation("jitterAll") { g, rng -> DoubleArray(size) { clamp(g[it] + gauss(rng) * 0.08) } },
        Variation("redrawOne") { g, rng -> g.copyOf().also { it[rng.nextInt(size)] = rng.nextDouble() * 2.0 - 1.0 } },
    )

    override fun crossover(a: DoubleArray, b: DoubleArray, rng: Random): DoubleArray {
        val out = (if (rng.nextBoolean()) a else b).copyOf(size)
        for (j in 0 until hidden) {
            val src = if (rng.nextBoolean()) a else b
            val o = 8 + j * (5 + hidden)
            for (k in o until o + 5 + hidden) out[k] = src[k]
        }
        return out
    }

    override fun signature(g: DoubleArray): String = g.joinToString(",") { "%.3f".format(it) }

    private fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)
    private fun gauss(rng: Random): Double {
        val u1 = max(1e-12, rng.nextDouble())
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * rng.nextDouble())
    }
}

// ==============================================================================================
// Tasks
// ==============================================================================================

/**
 * A scoring task for a [VehicleBrain]: [count] vehicles from [initialSeed] among [lights] for [steps]
 * steps, scored over the last [scored] by [score], which sees the world after each step and returns
 * that step's mean per-vehicle score.
 */
class VehicleBrainTask(
    /** Short name for tables. */
    val name: String,
    /** Lights at the start of every run. */
    val lights: List<Light>,
    /** Per-step score, averaged over the scored window. */
    val score: (VehicleBrainWorld) -> Double,
    /** Vehicles per evaluation. */
    val count: Int = 10,
    /** Steps per evaluation. */
    val steps: Int = 1500,
    /** Steps at the end that are scored. */
    val scored: Int = 500,
    /** Start positions. */
    val initialSeed: Long = 1L,
) {
    init {
        require(scored in 1..steps) { "scored must be in 1..steps" }
    }

    /** A fresh world for [genes] decoded with `hidden` hidden neurons. */
    fun world(genes: DoubleArray, hidden: Int, seed: Long = initialSeed, vehicles: Int = count): VehicleBrainWorld =
        VehicleBrainWorld(VehicleBrain.decode(genes, hidden), lights, vehicles, seed)

    /** The task as an [Objective] for a [hidden]-neuron genome. Behaviour is `[meanLight, meanSpeed]`. */
    fun objective(hidden: Int): Objective<DoubleArray> = Objective { g ->
        val w = world(g, hidden)
        repeat(steps - scored) { w.step() }
        var s = 0.0
        var light = 0.0
        var speed = 0.0
        repeat(scored) {
            w.step()
            s += score(w)
            light += w.meanLight()
            speed += w.meanSpeed()
        }
        Evaluation(s / scored, behaviour = doubleArrayOf(light / scored, speed / scored))
    }

    companion object {
        private val fourLights = listOf(Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0))

        /** Hold the light, on §9's four lamps, with §6's price on speed: light − 0.5 × speed. */
        fun forage(speedCost: Double = 0.5): VehicleBrainTask =
            VehicleBrainTask("forage", fourLights, { it.meanLight() - speedCost * it.meanSpeed() })

        /**
         * Circle a single lamp at [radius]: per vehicle, `exp(−((d − radius)/6)²)` times its speed as a
         * fraction of 0.5, so parking on the ring scores nothing.
         */
        fun orbit(radius: Double = 20.0): VehicleBrainTask = VehicleBrainTask(
            "orbit", listOf(Light(120.0, 120.0)),
            { w ->
                var s = 0.0
                for (i in 0 until w.count) {
                    val e = (w.nearestLightDistance(i) - radius) / 6.0
                    s += exp(-e * e) * min(1.0, w.speed[i] / 0.5)
                }
                s / w.count
            },
        )

        /** Track two lamps drifting across the torus (§8's moving lights): mean light at the bodies. */
        fun track(): VehicleBrainTask = VehicleBrainTask(
            "track", listOf(Light(60.0, 80.0, vx = 0.25, vy = 0.1), Light(180.0, 160.0, vx = -0.15, vy = 0.2)),
            { it.meanLight() },
        )
    }
}

// ==============================================================================================
// The comparison
// ==============================================================================================
// The comparison — a thin caller of the generic afield.study.search.compareSearch
// ==============================================================================================

/** The three searches [compareVehicleSearch] runs, by their [SearchArm] names. */
enum class VehicleSearchDriver(
    /** The [SearchArm.name] this driver runs as. */
    val arm: String,
) { RANDOM("random"), HILL_CLIMB("hillclimb"), EVOLVE("evolve") }

/** One driver's result for one seed. */
class VehicleSearchCell(
    /** Which driver. */
    val driver: VehicleSearchDriver,
    /** The search seed. */
    val seed: Long,
    /** The run, with its per-evaluation [SearchRun.trace]. */
    val run: SearchRun<DoubleArray>,
)

/**
 * [compareVehicleSearch]'s result: every driver at every seed, all at [budget] evaluations. The
 * statistics and the plot are [general]'s, the [SearchComparison] any genome gets.
 */
class VehicleSearchComparison(
    /** The task. */
    val task: String,
    /** Hidden neurons. */
    val hidden: Int,
    /** Genes. */
    val genes: Int,
    /** The generic comparison this wraps. */
    val general: SearchComparison<DoubleArray>,
) {
    /** Evaluations each run was allowed. */
    val budget: Int get() = general.budget

    /** Every run. */
    val cells: List<VehicleSearchCell> = general.cells.map { c ->
        VehicleSearchCell(VehicleSearchDriver.values().first { it.arm == c.arm }, c.seed, c.run)
    }

    /** The runs of one driver, in seed order. */
    fun of(d: VehicleSearchDriver): List<VehicleSearchCell> = cells.filter { it.driver == d }

    /** Best fitness of each seed's run of [d]. */
    fun best(d: VehicleSearchDriver): DoubleArray = general.best(d.arm)

    /** Seeds at which [a] ended strictly above [b]. */
    fun wins(a: VehicleSearchDriver, b: VehicleSearchDriver): Int = general.wins(a.arm, b.arm)

    /** The best genome any run of [d] found. */
    fun champion(d: VehicleSearchDriver): DoubleArray = general.champion(d.arm)

    /** One line per driver: median, IQR, min, max of the per-seed bests, and wins against random. */
    fun table(): String =
        "%s, %d hidden (%d genes), %d evals x %d seeds\n".format(task, hidden, genes, budget, general.seeds) + general.rows()

    /** Per-seed bests, one row per seed. */
    fun perSeed(): String = general.perSeed()

    /** Median best-so-far of [d] after each evaluation, with its quartiles: `[q25, median, q75]` rows. */
    fun curve(d: VehicleSearchDriver): Array<DoubleArray> = general.curve(d.arm)

    /** The run of [d] whose best fitness is the median over seeds (the lower middle for an even count). */
    fun medianRun(d: VehicleSearchDriver): SearchRun<DoubleArray> = general.medianRun(d.arm)

    /** The fitness range [plot] uses when none is given: every arm's quartile band. */
    fun fitnessRange(): ClosedFloatingPointRange<Double> = general.fitnessRange()

    /**
     * Best-so-far fitness against evaluations: each driver's median as a line, its interquartile range
     * as a band, and its final median written at the right. Random is grey, hill climbing blue,
     * evolution orange. Pass the same [yRange] to two plots to put them on one scale.
     */
    fun plot(width: Int = 300, height: Int = 300, yRange: ClosedFloatingPointRange<Double> = fitnessRange()): Img =
        general.plot(width, height, yRange)

    /** [table]. */
    override fun toString(): String = table()
}

/**
 * Random sampling, hill climbing and evolution on [task] with a [hidden]-neuron [VehicleBrainGenome],
 * each for exactly [budget] objective calls at every seed in [seeds], through [compareSearch].
 * Deterministic given its arguments. Evolution is (µ=[mu] + λ=[lambda]) with tournament 2,
 * [crossoverRate] and [immigrantRate] (the fraction of children that are fresh random draws); the hill
 * climber restarts after [patience] non-improving steps. For a fourth arm, or another genome, call
 * [compareSearch] directly.
 */
fun compareVehicleSearch(
    task: VehicleBrainTask,
    hidden: Int,
    budget: Int = 300,
    seeds: List<Long> = (1L..12L).toList(),
    mu: Int = 6,
    lambda: Int = 12,
    crossoverRate: Double = 0.3,
    immigrantRate: Double = 0.0,
    patience: Int = 30,
): VehicleSearchComparison {
    require(budget >= 1) { "budget must be >= 1" }
    val ops = VehicleBrainGenome(hidden)
    val arms = listOf(
        SearchArm.random<DoubleArray>(),
        SearchArm.hillClimb(patience),
        SearchArm.evolve(mu, lambda, crossoverRate, immigrantRate, name = "evolve"),
    )
    val general = compareSearch(ops, task.objective(hidden), budget, seeds, arms, label = "${task.name}, $hidden hidden")
    return VehicleSearchComparison(task.name, hidden, ops.size, general)
}
