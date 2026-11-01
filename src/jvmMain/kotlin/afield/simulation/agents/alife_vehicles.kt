package afield.simulation.agents

import afield.media.image.Img
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Braitenberg vehicles whose neurons have internal state: two light sensors, two motor neurons,
 * two wheels, and between them a small dynamical system rather than a wire.
 *
 * In Braitenberg's book a vehicle's wiring is its personality: crossed excitatory wires make an
 * "aggressor" that speeds into a light, uncrossed inhibitory wires make a "lover" that slows down and
 * parks in front of it. Those vehicles are memoryless — the wheel speed is a function of what the
 * sensors read *now*. Here each motor neuron carries three kinds of memory on three timescales:
 *
 * - **membrane** (a few steps): a leaky integrator, so the vehicle responds to a smoothed input;
 * - **hysteresis** (state-dependent): self-excitation, which makes a steep activation bistable, so a
 *   neuron that switched on at one input level switches off at a lower one;
 * - **homeostasis** (hundreds of steps, [NeuronParams.adaptationRate]): the threshold integrates the
 *   difference between the neuron's rate and a target rate, so a vehicle held in constant light gets
 *   used to it — a parked lover eventually drives off;
 * - **plasticity** (thousands of steps, [PlasticityParams]): the four sensor→motor weights learn from
 *   reward-modulated eligibility traces of the motor noise, so a randomly wired population can
 *   discover, on its own, that crossed wiring brings it into the light.
 *
 * Each of these is switched off by one parameter, which is how the measurements on the docs page
 * make their claims: same seed, one ingredient removed, a number that changes.
 *
 * Mutable, like [BoidFlock]: [VehicleWorld.step] advances the population in place.
 */

// ==============================================================================================
// Neurons
// ==============================================================================================

/**
 * The nonlinearity a motor neuron applies to its membrane potential. Each maps to roughly `0..1`, so
 * the wheel speed reads the same way whichever one is chosen.
 */
enum class Activation {
    /** The logistic `1 / (1 + e^-x)`: a smooth switch. */
    SIGMOID,

    /** `(tanh x + 1) / 2`: the logistic at twice the slope. */
    TANH,

    /** `clamp(x, 0, 1)`: dead below zero, linear, saturating. */
    RELU,

    /**
     * `e^(-x²)`: tuned to a *preferred* level, quiet both above and below it. Wired to light it
     * makes a vehicle that circles at a set distance instead of approaching or fleeing.
     */
    BUMP;

    /** Apply this activation to [x]. */
    fun apply(x: Double): Double = when (this) {
        SIGMOID -> 1.0 / (1.0 + exp(-x))
        TANH -> 0.5 * (tanh(x) + 1.0)
        RELU -> x.coerceIn(0.0, 1.0)
        BUMP -> exp(-x * x)
    }
}

/**
 * The internal dynamics of one motor neuron.
 *
 * Per step, with input `I`, rate `r` and threshold `θ`:
 * ```
 * u += (−u + I + selfExcitation·r − θ − bias) / tauMembrane
 * r  = activation(gain · u)
 * θ += adaptationRate · (r − targetRate),  clamped to ±thresholdLimit
 * ```
 */
data class NeuronParams(
    /** The nonlinearity; see [Activation]. */
    val activation: Activation = Activation.SIGMOID,
    /** Slope applied before the activation. */
    val gain: Double = 6.0,
    /** Membrane time constant in steps; `1.0` is no smoothing at all. */
    val tauMembrane: Double = 4.0,
    /**
     * Weight of the neuron's own output fed back into its membrane. For the sigmoid the neuron is
     * bistable — and so has a hysteresis loop — once `selfExcitation · gain / 4 > 1`. Zero is the
     * memoryless control.
     */
    val selfExcitation: Double = 0.0,
    /** A fixed offset subtracted from the membrane drive. */
    val bias: Double = 0.0,
    /**
     * How fast the threshold integrates `r − targetRate`, per step. Zero switches homeostasis off; a
     * typical value is `0.002`, a time constant of a few hundred steps.
     */
    val adaptationRate: Double = 0.0,
    /** The firing rate homeostasis pulls the neuron toward. */
    val targetRate: Double = 0.35,
    /** How far the threshold may wander either way. */
    val thresholdLimit: Double = 3.0,
) {
    init {
        require(tauMembrane >= 1.0) { "tauMembrane must be >= 1 step, got $tauMembrane" }
        require(adaptationRate >= 0.0) { "adaptationRate must be >= 0, got $adaptationRate" }
        require(thresholdLimit >= 0.0) { "thresholdLimit must be >= 0, got $thresholdLimit" }
    }
}

/**
 * The slow learning level: reward-modulated plasticity of the four sensor→motor weights.
 *
 * Each motor neuron's output carries exploration noise `ξ`. A trace `e[m][s]` low-passes `ξ_m · s_s` —
 * "this neuron fired more than it meant to while that sensor was lit" — over [tauTrace] steps. The
 * reward is the light at the vehicle's body, `R`, and `R̄` is its running average over [tauBaseline]
 * steps. The update is `w += learningRate · (R − R̄) · e`: noise that was followed by more light than
 * usual is written into the wiring. This is node perturbation (Williams' REINFORCE with a baseline), the
 * simplest rule that needs neither a gradient nor a model of the world.
 */
data class PlasticityParams(
    /** Zero is the control: the weights never move. Around `3.0` learns in ten thousand steps; much above `10` overshoots. */
    val learningRate: Double = 0.0,
    /** Standard deviation of the motor noise, which is both the exploration and what is learned from. */
    val noise: Double = 0.15,
    /** Eligibility-trace time constant in steps: how long after a twitch its consequence still counts. */
    val tauTrace: Double = 30.0,
    /** Reward-baseline time constant in steps. */
    val tauBaseline: Double = 50.0,
    /** Weights are clamped to `±maxWeight`. */
    val maxWeight: Double = 3.0,
    /**
     * Price of speed in the reward: `R = light − speedCost · speed`. Zero is the plain light reward;
     * with a cost, a vehicle that holds the same light while driving slower is the better one.
     */
    val speedCost: Double = 0.0,
) {
    init {
        require(learningRate >= 0.0) { "learningRate must be >= 0, got $learningRate" }
        require(noise >= 0.0) { "noise must be >= 0, got $noise" }
        require(tauTrace >= 1.0 && tauBaseline >= 1.0) { "time constants must be >= 1 step" }
        require(speedCost >= 0.0) { "speedCost must be >= 0, got $speedCost" }
    }
}

/**
 * Body, sensors and wheels of a vehicle, plus its two [NeuronParams] layers of dynamics.
 */
data class VehicleParams(
    /** Angle of each sensor off the heading, in radians. */
    val sensorAngle: Double = 0.6,
    /** Distance of each sensor ahead of the body centre. */
    val sensorDistance: Double = 3.0,
    /** Wheel speed with the motor neuron silent. */
    val baseSpeed: Double = 0.15,
    /** Extra wheel speed at a rate of 1. */
    val motorSpeed: Double = 1.2,
    /** Distance between the wheels; smaller turns harder. */
    val axle: Double = 2.5,
    /** Motor-neuron dynamics. */
    val neuron: NeuronParams = NeuronParams(),
    /** Weight learning. */
    val plasticity: PlasticityParams = PlasticityParams(),
)

/**
 * The memoryless input→output steady state is the wrong question for a bistable neuron, so this
 * measures what the neuron actually does: sweep the input from [lo] to [hi] and back over [steps] steps
 * each way, slowly enough for the membrane to follow, and record the rate.
 *
 * Returns the inputs and rates of the up-sweep and the down-sweep, each [steps] long.
 */
fun hysteresisSweep(
    p: NeuronParams,
    lo: Double = -2.5,
    hi: Double = 2.0,
    steps: Int = 2000,
): HysteresisSweep {
    require(steps >= 2) { "steps must be >= 2, got $steps" }
    val noAdapt = p.copy(adaptationRate = 0.0)
    val state = DoubleArray(3)
    val inUp = DoubleArray(steps) { lo + (hi - lo) * it / (steps - 1) }
    val inDown = inUp.reversedArray()
    state[0] = lo
    state[1] = noAdapt.activation.apply(noAdapt.gain * lo)
    repeat(200) { neuronStep(noAdapt, state, lo) }
    val up = DoubleArray(steps) { neuronStep(noAdapt, state, inUp[it]) }
    val down = DoubleArray(steps) { neuronStep(noAdapt, state, inDown[it]) }
    return HysteresisSweep(inUp, up, inDown, down)
}

/**
 * The result of [hysteresisSweep]: the same neuron driven up and then back down.
 */
class HysteresisSweep(
    /** Inputs of the rising sweep. */
    val inputsUp: DoubleArray,
    /** Rates on the rising sweep. */
    val ratesUp: DoubleArray,
    /** Inputs of the falling sweep (the rising ones reversed). */
    val inputsDown: DoubleArray,
    /** Rates on the falling sweep. */
    val ratesDown: DoubleArray,
) {
    /** The input at which the rising sweep first crosses a rate of 0.5, or NaN if it never does. */
    val switchOn: Double get() = crossing(inputsUp, ratesUp) { it >= 0.5 }

    /** The input at which the falling sweep first drops below 0.5, or NaN if it never does. */
    val switchOff: Double get() = crossing(inputsDown, ratesDown) { it < 0.5 }

    /** `switchOn − switchOff`: zero for a memoryless neuron, the loop's width for a bistable one. */
    val width: Double get() = switchOn - switchOff

    /** The area between the two branches, in input × rate units. */
    val area: Double
        get() {
            var a = 0.0
            val n = inputsUp.size
            for (i in 1 until n) {
                val dx = inputsUp[i] - inputsUp[i - 1]
                a += dx * (ratesDown[n - 1 - i] - ratesUp[i])
            }
            return abs(a)
        }

    private fun crossing(x: DoubleArray, r: DoubleArray, past: (Double) -> Boolean): Double {
        for (i in r.indices) if (past(r[i])) return x[i]
        return Double.NaN
    }

    /**
     * Both branches plotted into a small RGB image: rising in orange, falling in blue, input across,
     * rate up.
     */
    fun toImg(width: Int = 300, height: Int = 200): Img {
        val out = Img.filled(width, height, 3, 0.06f)
        val lo = inputsUp.first()
        val hi = inputsUp.last()
        fun plot(x: DoubleArray, r: DoubleArray, c: FloatArray, dy: Int) {
            for (i in x.indices) {
                val px = ((x[i] - lo) / (hi - lo) * (width - 9) + 4).roundToInt()
                val py = ((1.0 - r[i]) * (height - 9) + 4).roundToInt() + dy
                for (oy in -1..1) for (ox in -1..1) {
                    val xx = (px + ox).coerceIn(0, width - 1)
                    val yy = (py + oy).coerceIn(0, height - 1)
                    for (k in 0 until 3) out.data[(yy * width + xx) * 3 + k] = c[k]
                }
            }
        }
        plot(inputsUp, ratesUp, floatArrayOf(1f, 0.55f, 0.15f), -1)
        plot(inputsDown, ratesDown, floatArrayOf(0.3f, 0.65f, 1f), 1)
        return out
    }
}

/** One neuron step on `state = [u, r, θ]`; returns the new rate. */
internal fun neuronStep(p: NeuronParams, state: DoubleArray, input: Double, noise: Double = 0.0): Double {
    val u = state[0] + (-state[0] + input + p.selfExcitation * state[1] - state[2] - p.bias) / p.tauMembrane
    val r = (p.activation.apply(p.gain * u) + noise).coerceIn(0.0, 1.0)
    var theta = state[2]
    if (p.adaptationRate > 0.0) {
        theta = (theta + p.adaptationRate * (r - p.targetRate)).coerceIn(-p.thresholdLimit, p.thresholdLimit)
    }
    state[0] = u
    state[1] = r
    state[2] = theta
    return r
}

// ==============================================================================================
// The world
// ==============================================================================================

/**
 * A light: brightness `intensity / (1 + d²/radius²)` at distance `d`. A light with a velocity
 * ([vx], [vy], in distance per step) drifts across the torus as the world steps.
 */
data class Light(
    val x: Double,
    val y: Double,
    val intensity: Double = 1.0,
    val radius: Double = 18.0,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
) {
    /** Brightness at distance [d] from this light. */
    fun brightnessAt(d: Double): Double = intensity / (1.0 + d * d / (radius * radius))

    /** Whether this light moves. */
    val moving: Boolean get() = vx != 0.0 || vy != 0.0

    /** Speed in distance per step. */
    val speed: Double get() = sqrt(vx * vx + vy * vy)
}

/**
 * The [NeuronParams.bias] that makes an [Activation.BUMP] neuron fire hardest when a sensor wired with
 * [weight] reads the brightness [distance] away from [light]. Wired crossed and excitatory
 * ([VehicleWorld.AGGRESSOR]), such a vehicle turns toward the light when it is farther than [distance]
 * and away from it when nearer, so it circles at about that radius.
 */
fun orbitBias(distance: Double, weight: Double = 2.0, light: Light = Light(0.0, 0.0)): Double {
    require(distance > 0.0) { "distance must be > 0, got $distance" }
    return weight * light.brightnessAt(distance)
}

/**
 * A population of vehicles among [lights] on a torus.
 *
 * Per-vehicle state is in flat arrays. Weight `w[4i + 2m + s]` connects sensor `s` (0 left, 1 right) to
 * motor neuron `m` (0 drives the left wheel, 1 the right), so *crossed* wiring is `m ≠ s`.
 *
 * @param seed seed for the motor noise; the run is deterministic given it
 */
class VehicleWorld(
    /** Domain width. */
    val width: Double,
    /** Domain height. */
    val height: Double,
    lights: List<Light>,
    /** Body x positions. */
    val x: DoubleArray,
    /** Body y positions. */
    val y: DoubleArray,
    /** Headings in radians. */
    val heading: DoubleArray,
    /** Sensor→motor weights, four per vehicle. */
    val w: DoubleArray,
    /** Neuron state, three per motor neuron (`u`, `r`, `θ`), six per vehicle. */
    val neurons: DoubleArray,
    /** Eligibility traces, four per vehicle. */
    val traces: DoubleArray,
    /** Running reward average, one per vehicle. */
    val baseline: DoubleArray,
    seed: Long = 1L,
) {
    init {
        require(width > 0.0 && height > 0.0) { "domain must be positive, got ${width}x$height" }
        val n = x.size
        require(n >= 1) { "a world needs at least one vehicle" }
        require(y.size == n && heading.size == n && baseline.size == n) { "per-vehicle arrays must match" }
        require(w.size == 4 * n && traces.size == 4 * n && neurons.size == 6 * n) { "per-synapse arrays must match" }
    }

    private val rng = kotlin.random.Random(seed)

    /** The light sources, at their current positions; lights with a velocity move every [step]. */
    var lights: List<Light> = lights
        private set

    /** Each vehicle's forward speed on the last step. */
    val speed: DoubleArray = DoubleArray(x.size)

    /** How many vehicles. */
    val count: Int get() = x.size

    /** Steps taken so far. */
    var steps: Int = 0
        private set

    /** Brightness at a point, summed over [lights] under the toroidal metric. */
    fun light(px: Double, py: Double): Double {
        var s = 0.0
        for (l in lights) {
            val dx = wrapDelta(px - l.x, width)
            val dy = wrapDelta(py - l.y, height)
            s += l.intensity / (1.0 + (dx * dx + dy * dy) / (l.radius * l.radius))
        }
        return s
    }

    /** The index of the light within [within] of vehicle [i], or -1. */
    fun nearestLightWithin(i: Int, within: Double): Int {
        var best = -1
        var bestD = within * within
        for ((k, l) in lights.withIndex()) {
            val dx = wrapDelta(x[i] - l.x, width)
            val dy = wrapDelta(y[i] - l.y, height)
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Distance from vehicle [i] to the nearest light, under the toroidal metric. */
    fun nearestLightDistance(i: Int): Double {
        var best = Double.MAX_VALUE
        for (l in lights) {
            val dx = wrapDelta(x[i] - l.x, width)
            val dy = wrapDelta(y[i] - l.y, height)
            best = min(best, dx * dx + dy * dy)
        }
        return sqrt(best)
    }

    /**
     * How far vehicles near moving lights lag behind them: for every vehicle within [within] of a
     * moving light, its offset from that light projected on the light's direction of travel, averaged.
     * Negative is trailing, positive leading. Returns NaN when no vehicle is near a moving light.
     * Divide by the light's speed for the lag in steps.
     */
    fun trailAlong(within: Double = 20.0): Double {
        var sum = 0.0
        var n = 0
        for (i in 0 until count) {
            val k = nearestLightWithin(i, within)
            if (k < 0) continue
            val l = lights[k]
            if (!l.moving) continue
            val dx = wrapDelta(x[i] - l.x, width)
            val dy = wrapDelta(y[i] - l.y, height)
            sum += (dx * l.vx + dy * l.vy) / l.speed
            n++
        }
        return if (n == 0) Double.NaN else sum / n
    }

    /** Mean forward speed over the population on the last step. */
    fun meanSpeed(): Double = speed.average()

    /** The rate of motor neuron [m] of vehicle [i]. */
    fun rate(i: Int, m: Int): Double = neurons[6 * i + 3 * m + 1]

    /** The threshold of motor neuron [m] of vehicle [i]. */
    fun threshold(i: Int, m: Int): Double = neurons[6 * i + 3 * m + 2]

    /**
     * How *crossed-excitatory* vehicle [i] is: `(w_LR + w_RL − w_LL − w_RR) / 2`. Positive steers toward
     * light (Braitenberg's 2b), negative away (2a).
     */
    fun crossing(i: Int): Double = (w[4 * i + 1] + w[4 * i + 2] - w[4 * i] - w[4 * i + 3]) / 2.0

    /** Mean [crossing] over the population. */
    fun meanCrossing(): Double = (0 until count).sumOf { crossing(it) } / count

    /** Mean light at the vehicles' bodies right now. */
    fun meanLight(): Double = (0 until count).sumOf { light(x[it], y[it]) } / count

    /** Advance every vehicle by one step. */
    fun step(p: VehicleParams) {
        val pl = p.plasticity
        val learning = pl.learningRate > 0.0
        val sense = DoubleArray(2)
        val xi = DoubleArray(2)
        val st = DoubleArray(3)
        for (i in 0 until count) {
            val h = heading[i]
            for (s in 0..1) {
                val a = h + if (s == 0) p.sensorAngle else -p.sensorAngle
                sense[s] = light(x[i] + cos(a) * p.sensorDistance, y[i] + sin(a) * p.sensorDistance)
            }
            val rates = DoubleArray(2)
            for (m in 0..1) {
                val input = w[4 * i + 2 * m] * sense[0] + w[4 * i + 2 * m + 1] * sense[1]
                xi[m] = if (pl.noise > 0.0) gaussian() * pl.noise else 0.0
                val o = 6 * i + 3 * m
                st[0] = neurons[o]; st[1] = neurons[o + 1]; st[2] = neurons[o + 2]
                rates[m] = neuronStep(p.neuron, st, input, xi[m])
                neurons[o] = st[0]; neurons[o + 1] = st[1]; neurons[o + 2] = st[2]
            }
            // Left wheel from motor 0, right wheel from motor 1; a faster left wheel turns right (−).
            val vl = p.baseSpeed + p.motorSpeed * rates[0]
            val vr = p.baseSpeed + p.motorSpeed * rates[1]
            val v = 0.5 * (vl + vr)
            speed[i] = v
            heading[i] = h + (vr - vl) / p.axle
            x[i] = wrap(x[i] + cos(heading[i]) * v, width)
            y[i] = wrap(y[i] + sin(heading[i]) * v, height)

            if (learning) {
                val reward = light(x[i], y[i]) - pl.speedCost * v
                val adv = reward - baseline[i]
                baseline[i] += (reward - baseline[i]) / pl.tauBaseline
                for (m in 0..1) for (s in 0..1) {
                    val k = 4 * i + 2 * m + s
                    traces[k] += (xi[m] * sense[s] - traces[k]) / pl.tauTrace
                    w[k] = (w[k] + pl.learningRate * adv * traces[k]).coerceIn(-pl.maxWeight, pl.maxWeight)
                }
            }
        }
        if (lights.any { it.moving }) {
            lights = lights.map { if (it.moving) it.copy(x = wrap(it.x + it.vx, width), y = wrap(it.y + it.vy, height)) else it }
        }
        steps++
    }

    /** [step] [n] times. */
    fun stepTimes(p: VehicleParams, n: Int) { repeat(n) { step(p) } }

    /** An independent copy with the same noise seed, so a run can be forked into two arms. */
    fun copy(seed: Long = 1L): VehicleWorld = VehicleWorld(
        width, height, lights, x.copyOf(), y.copyOf(), heading.copyOf(), w.copyOf(),
        neurons.copyOf(), traces.copyOf(), baseline.copyOf(), seed,
    )

    /**
     * An independent copy of the vehicles (positions, wiring, neuron state) among a different set of
     * [lights] — a trained population moved onto moving lamps, say.
     */
    fun withLights(lights: List<Light>, seed: Long = 1L): VehicleWorld = VehicleWorld(
        width, height, lights, x.copyOf(), y.copyOf(), heading.copyOf(), w.copyOf(),
        neurons.copyOf(), traces.copyOf(), baseline.copyOf(), seed,
    )

    private fun gaussian(): Double {
        val u1 = max(1e-12, rng.nextDouble())
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)
    }

    override fun toString(): String =
        "VehicleWorld($count vehicles, ${lights.size} lights, step $steps, mean light %.3f)".format(meanLight())

    companion object {
        /** Crossed excitatory: Braitenberg's aggressor, 2b. */
        val AGGRESSOR = doubleArrayOf(0.0, 2.0, 2.0, 0.0)

        /** Uncrossed excitatory: Braitenberg's coward, 2a. */
        val COWARD = doubleArrayOf(2.0, 0.0, 0.0, 2.0)

        /** Uncrossed inhibitory: Braitenberg's lover, 3a — slows down facing a light and parks. */
        val LOVER = doubleArrayOf(-2.0, 0.0, 0.0, -2.0)

        /** Crossed inhibitory: Braitenberg's explorer, 3b — slows near a light but turns away from it. */
        val EXPLORER = doubleArrayOf(0.0, -2.0, -2.0, 0.0)

        /**
         * [count] vehicles at random positions and headings, all wired with [wiring] (four weights), or
         * each wired uniformly at random in `±randomWeight` when [wiring] is null.
         */
        fun random(
            count: Int,
            lights: List<Light>,
            width: Double = 240.0,
            height: Double = 240.0,
            wiring: DoubleArray? = null,
            randomWeight: Double = 2.0,
            seed: Long = 1L,
        ): VehicleWorld {
            require(count >= 1) { "count must be >= 1, got $count" }
            require(wiring == null || wiring.size == 4) { "wiring must be four weights" }
            val rng = kotlin.random.Random(seed)
            val x = DoubleArray(count) { rng.nextDouble() * width }
            val y = DoubleArray(count) { rng.nextDouble() * height }
            val h = DoubleArray(count) { rng.nextDouble() * 2.0 * PI }
            val w = DoubleArray(4 * count) {
                wiring?.get(it % 4) ?: ((rng.nextDouble() * 2.0 - 1.0) * randomWeight)
            }
            return VehicleWorld(
                width, height, lights, x, y, h, w,
                DoubleArray(6 * count), DoubleArray(4 * count), DoubleArray(count), seed + 1,
            )
        }
    }
}

private fun wrap(v: Double, size: Double): Double {
    val r = v % size
    return if (r < 0) r + size else r
}

private fun wrapDelta(d: Double, size: Double): Double {
    var r = d % size
    if (r > size / 2) r -= size
    if (r < -size / 2) r += size
    return r
}

/**
 * Counts, per vehicle, how many times it *arrives* at a light other than the last one it was at —
 * the number that tells a vehicle that parks from one that forages. Call [observe] once per step.
 */
class LightVisits(world: VehicleWorld, private val within: Double = 8.0) {
    private val last = IntArray(world.count) { -1 }

    /** Arrivals so far, per vehicle. */
    val visits = IntArray(world.count)

    /** Record the current positions. */
    fun observe(world: VehicleWorld) {
        for (i in 0 until world.count) {
            val k = world.nearestLightWithin(i, within)
            if (k >= 0 && k != last[i]) { visits[i]++; last[i] = k }
        }
    }

    /** Mean arrivals per vehicle. */
    fun mean(): Double = visits.average()
}

/**
 * Collects every vehicle's distance to its nearest light, once per [observe], for the distribution of
 * radii an orbiting population keeps.
 */
class OrbitRadii {
    private val samples = ArrayList<Double>()

    /** Record each vehicle's current distance to its nearest light. */
    fun observe(world: VehicleWorld) {
        for (i in 0 until world.count) samples += world.nearestLightDistance(i)
    }

    /** How many distances have been recorded. */
    val size: Int get() = samples.size

    /** The [q] quantile (`0..1`) of the recorded distances. */
    fun quantile(q: Double): Double {
        require(samples.isNotEmpty()) { "no samples yet" }
        val sorted = samples.sorted()
        return sorted[((sorted.size - 1) * q.coerceIn(0.0, 1.0)).roundToInt()]
    }

    /** Median recorded distance. */
    fun median(): Double = quantile(0.5)

    /** Fraction of recorded distances inside `lo..hi`. */
    fun fractionWithin(lo: Double, hi: Double): Double = samples.count { it in lo..hi }.toDouble() / max(1, samples.size)

    /**
     * A histogram of the distances from 0 to [maxDistance] in [bins] bars, as a small RGB image, with
     * an optional [mark] drawn as a vertical line (the radius the vehicles were tuned for).
     */
    fun toImg(maxDistance: Double = 60.0, bins: Int = 60, mark: Double = Double.NaN, width: Int = 300, height: Int = 160): Img {
        val counts = IntArray(bins)
        for (d in samples) if (d < maxDistance) counts[(d / maxDistance * bins).toInt().coerceIn(0, bins - 1)]++
        val top = max(1, counts.max())
        val out = Img.filled(width, height, 3, 0.06f)
        for (px in 0 until width) {
            val b = (px * bins / width).coerceIn(0, bins - 1)
            val barH = (counts[b].toDouble() / top * (height - 6)).roundToInt()
            for (py in height - barH until height) {
                val o = (py * width + px) * 3
                out.data[o] = 1f; out.data[o + 1] = 0.6f; out.data[o + 2] = 0.2f
            }
        }
        if (!mark.isNaN() && mark < maxDistance) {
            val px = (mark / maxDistance * width).roundToInt().coerceIn(0, width - 1)
            for (py in 0 until height) {
                val o = (py * width + px) * 3
                out.data[o] = 0.4f; out.data[o + 1] = 0.75f; out.data[o + 2] = 1f
            }
        }
        return out
    }
}

/**
 * A fading trail buffer for drawing a [VehicleWorld]: the light field as a dim backdrop, each vehicle's
 * recent path as a streak coloured by its motor rates (red the left neuron, green the right, so a
 * vehicle driving straight at full tilt is yellow), and the bodies on top.
 *
 * Call [record] once per step and [image] whenever a frame is wanted.
 */
class VehicleCanvas(val world: VehicleWorld, val scale: Double = 1.5, val fade: Float = 0.992f) {
    /** Pixels across. */
    val w = max(1, (world.width * scale).roundToInt())

    /** Pixels down. */
    val h = max(1, (world.height * scale).roundToInt())
    private val trails = FloatArray(w * h * 3)
    private var backdropFor: List<Light>? = null
    private var backdropCache = FloatArray(0)

    /** The light field, recomputed whenever the lights have moved since it was last drawn. */
    private val backdrop: FloatArray
        get() {
            if (backdropFor != world.lights) {
                backdropCache = drawBackdrop()
                backdropFor = world.lights
            }
            return backdropCache
        }

    private fun drawBackdrop(): FloatArray {
        val b = FloatArray(w * h * 3)
        for (py in 0 until h) for (px in 0 until w) {
            val l = min(1.0, world.light(px / scale, py / scale)).toFloat()
            val o = (py * w + px) * 3
            b[o] = 0.05f + 0.30f * l * l
            b[o + 1] = 0.05f + 0.22f * l * l
            b[o + 2] = 0.08f + 0.10f * l
        }
        return b
    }

    /** Fade the trails and add the vehicles' current positions. */
    fun record() {
        for (k in trails.indices) trails[k] *= fade
        for (i in 0 until world.count) {
            val px = (world.x[i] * scale).roundToInt()
            val py = (world.y[i] * scale).roundToInt()
            val r = world.rate(i, 0).toFloat()
            val g = world.rate(i, 1).toFloat()
            splat(trails, px, py, 0.25f + 0.75f * r, 0.25f + 0.75f * g, 0.55f, 0.5f, 0)
        }
    }

    /** Backdrop + trails + bodies. */
    fun image(): Img {
        val out = FloatArray(w * h * 3)
        val bd = backdrop
        for (k in out.indices) out[k] = min(1f, bd[k] + trails[k])
        for (i in 0 until world.count) {
            splat(out, (world.x[i] * scale).roundToInt(), (world.y[i] * scale).roundToInt(), 1f, 1f, 1f, 1f, 1)
        }
        return Img(w, h, 3, out)
    }

    private fun splat(buf: FloatArray, px: Int, py: Int, r: Float, g: Float, b: Float, a: Float, radius: Int) {
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val xx = Math.floorMod(px + dx, w)
            val yy = Math.floorMod(py + dy, h)
            val o = (yy * w + xx) * 3
            buf[o] = min(1f, buf[o] + r * a)
            buf[o + 1] = min(1f, buf[o + 1] + g * a)
            buf[o + 2] = min(1f, buf[o + 2] + b * a)
        }
    }
}
