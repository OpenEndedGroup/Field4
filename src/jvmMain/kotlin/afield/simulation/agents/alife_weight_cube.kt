package afield.simulation.agents

import afield.geometry.path.FLine
import afield.graphics.render.circle
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.fline
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.graphics.text.layoutText
import afield.graphics.text.toFLines
import afield.graphics.text.typeface
import afield.math.linalg.Vec2
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import java.awt.Font
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Learning a symmetric Braitenberg wiring by trial and error, small enough to draw.
 *
 * A symmetric vehicle has three numbers: the weight from each sensor to the motor on its own side
 * ([CubeWiring.same]), the weight to the motor on the other side ([CubeWiring.cross]), and a drive
 * added to both motor neurons ([CubeWiring.drive], the negative of [NeuronParams.bias]). Each motor
 * runs at `σ(gain · (same · b_own + cross · b_other + drive))`, so every vehicle in §2 of the
 * Braitenberg page is a point in the cube `[−3, 3]³` of these three, and a learner is a path through
 * it. [WeightCubeLearner] walks that path one trial at a time; [CubeView] draws the cube.
 */

/** A point in the weight cube: the uncrossed weight, the crossed weight and the motor drive. */
data class CubeWiring(val same: Double, val cross: Double, val drive: Double) {
    /** The four weights in [VehicleWorld.w] order: left←left, left←right, right←left, right←right. */
    fun weights(): DoubleArray = doubleArrayOf(same, cross, cross, same)

    /** Distance to [o] in the cube. */
    fun distanceTo(o: CubeWiring): Double {
        val a = same - o.same
        val b = cross - o.cross
        val c = drive - o.drive
        return sqrt(a * a + b * b + c * c)
    }

    override fun toString(): String = "CubeWiring(same %.2f, cross %.2f, drive %.2f)".format(same, cross, drive)
}

/** One trial's averages: [light] at the vehicles' bodies and their forward [speed]. */
data class CubeTrial(val light: Double, val speed: Double) {
    override fun toString(): String = "CubeTrial(light %.3f, speed %.3f)".format(light, speed)
}

/**
 * What a trial is scored on. Each is averaged over every vehicle and every step of the trial.
 */
enum class CubeReward {
    /** The light at the body: find a lamp and stay by it. */
    SEEK,

    /** Minus the light at the body: keep away from the lamps. */
    FLEE,

    /** Minus the forward speed: move as little as possible. */
    FREEZE,

    /** The light at the body plus the forward speed: be near lamps and fast. */
    CHARGE;

    /** The reward for one vehicle on one step, from the light at its body and its speed. */
    fun of(light: Double, speed: Double): Double = when (this) {
        SEEK -> light
        FLEE -> -light
        FREEZE -> -speed
        CHARGE -> light + speed
    }
}

/**
 * Weight perturbation in the three-number cube of a symmetric vehicle.
 *
 * Each [trial] adds Gaussian noise of size [sigma] to the current wiring, drops [vehicles] vehicles
 * wired that way at fresh random places among [lights], runs them for [trialSteps] steps and scores
 * them with [reward]. If the score beats the running [baseline] the wiring moves toward the
 * perturbation, otherwise away from it, by `rate · (score − baseline) · ε / sigma`; then the
 * baseline moves a fraction [baselineRate] of the way toward the score. This is REINFORCE with a
 * baseline applied to the weights directly, where §4's online learner perturbs the motor outputs.
 *
 * [path] holds the wiring before the first trial and after each one, so `path[n]` is the learner
 * after `n` tries.
 *
 * @param start where the learner begins
 * @param params body and neuron dynamics for every trial; its bias is replaced by `−drive` and its
 *   plasticity is ignored
 * @param seed seeds both the perturbations and the trial start positions
 */
class WeightCubeLearner(
    val lights: List<Light>,
    val reward: CubeReward = CubeReward.SEEK,
    start: CubeWiring = CubeWiring(0.0, 0.0, 0.0),
    /** Size of each perturbation, in weight units. */
    val sigma: Double = 0.3,
    /** Step size of the update. */
    val rate: Double = 0.25,
    /** Fraction of the gap to each score the baseline closes. */
    val baselineRate: Double = 0.2,
    /** Vehicles per trial, all wired alike. */
    val vehicles: Int = 10,
    /** Steps per trial. */
    val trialSteps: Int = 300,
    /** The weight cube is `[−limit, limit]³`; the wiring is clamped to it. */
    val limit: Double = 3.0,
    val params: VehicleParams = VehicleParams(plasticity = PlasticityParams(noise = 0.05)),
    val seed: Long = 1L,
) {
    init {
        require(sigma > 0.0) { "sigma must be > 0, got $sigma" }
        require(vehicles >= 1 && trialSteps >= 1) { "a trial needs vehicles and steps" }
    }

    private val rng = kotlin.random.Random(seed)

    /** The current wiring. */
    var wiring: CubeWiring = clamp(start)
        private set

    /** The wiring after 0, 1, 2, ... tries. */
    val path: MutableList<CubeWiring> = mutableListOf(wiring)

    /** Every trial's score, in order. */
    val scores: MutableList<Double> = mutableListOf()

    /** The running score the next trial must beat; starts at the score of [start] itself. */
    var baseline: Double = score(wiring, -1)
        private set

    /** Tries so far. */
    val tries: Int get() = scores.size

    /** Score [w] over one trial: the [reward] averaged over every vehicle and step. */
    fun score(w: CubeWiring, trialIndex: Int = tries): Double {
        val m = measure(w, trialIndex)
        return reward.of(m.light, m.speed)
    }

    /**
     * Run one trial of [w] and return the light at the body and the forward speed, each averaged
     * over every vehicle and step. Every [CubeReward] is linear in these two, so this is the trial.
     */
    fun measure(w: CubeWiring, trialIndex: Int = tries): CubeTrial {
        val world = VehicleWorld.random(vehicles, lights, wiring = w.weights(), seed = seed * 1_000_003L + trialIndex)
        val p = params.copy(neuron = params.neuron.copy(bias = -w.drive), plasticity = params.plasticity.copy(learningRate = 0.0))
        var light = 0.0
        var speed = 0.0
        repeat(trialSteps) {
            world.step(p)
            for (i in 0 until world.count) {
                light += world.light(world.x[i], world.y[i])
                speed += world.speed[i]
            }
        }
        val n = (trialSteps * world.count).toDouble()
        return CubeTrial(light / n, speed / n)
    }

    /** One perturbed trial and its update; returns the trial's score. */
    fun trial(): Double {
        val e = DoubleArray(3) { gaussian() * sigma }
        val probe = clamp(CubeWiring(wiring.same + e[0], wiring.cross + e[1], wiring.drive + e[2]))
        val s = score(probe)
        val k = rate * (s - baseline) / sigma
        wiring = clamp(CubeWiring(wiring.same + k * e[0], wiring.cross + k * e[1], wiring.drive + k * e[2]))
        baseline += baselineRate * (s - baseline)
        scores += s
        path += wiring
        return s
    }

    /** [trial] [n] times. */
    fun train(n: Int): WeightCubeLearner {
        repeat(n) { trial() }
        return this
    }

    private fun clamp(w: CubeWiring) = CubeWiring(
        w.same.coerceIn(-limit, limit), w.cross.coerceIn(-limit, limit), w.drive.coerceIn(-limit, limit),
    )

    private fun gaussian(): Double {
        val u1 = max(1e-12, rng.nextDouble())
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * rng.nextDouble())
    }

    override fun toString(): String = "WeightCubeLearner($reward, $tries tries, $wiring)"
}

/**
 * An oblique view of the weight cube `[−limit, limit]³`, for drawing learners' paths as FLines.
 *
 * [project] maps a [CubeWiring] to drawing coordinates (y down, as `lines(...)` draws them) inside a
 * square of side [size] whose top-left corner is ([x], [y]). [frame] is the cube's twelve edges, the
 * three axes through the centre, the four §2 wirings marked on the `drive = 0` plane and the axis
 * names. `same` runs left to right, `cross` back into the page and `drive` upward.
 */
class CubeView(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val size: Double = 300.0,
    val limit: Double = 3.0,
    /** Turn about the vertical axis, in radians. */
    val yaw: Double = 0.6,
    /** Tilt toward the viewer, in radians. */
    val pitch: Double = 0.45,
) {
    private val scale = size / (limit * 3.6)

    /** Where [w] lands in the drawing. */
    fun project(w: CubeWiring): Vec2 = project(w.same, w.cross, w.drive)

    /** Where the cube point ([same], [cross], [drive]) lands in the drawing. */
    fun project(same: Double, cross: Double, drive: Double): Vec2 {
        val a = same * cos(yaw) - cross * sin(yaw)
        val depth = same * sin(yaw) + cross * cos(yaw)
        val up = drive * cos(pitch) + depth * sin(pitch)
        return Vec2((x + size / 2 + a * scale).toFloat(), (y + size / 2 - up * scale).toFloat())
    }

    /** A polyline through [path], as one FLine. */
    fun path(path: List<CubeWiring>, color: Vec4, thickness: Double = 1.5): FLine = fline {
        path.forEachIndexed { i, w ->
            val p = project(w)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        this.color = color
        this.thickness = thickness
    }

    /** A filled dot of [radius] at [w]. */
    fun dot(w: CubeWiring, color: Vec4, radius: Double = 4.0): FLine = fline {
        val p = project(w)
        circle(p.x, p.y, radius)
        filled = true
        stroked = false
        this.color = color
    }

    /** The cube, its axes, the four named wirings and the axis labels; [title] goes above it. */
    fun frame(title: String = ""): List<FLine> {
        val l = limit
        val edge = vec(1.0, 1.0, 1.0, 0.25)
        val out = mutableListOf<FLine>()
        val box = fline {
            for (a in listOf(-l, l)) for (b in listOf(-l, l)) {
                seg(project(-l, a, b), project(l, a, b))
                seg(project(a, -l, b), project(a, l, b))
                seg(project(a, b, -l), project(a, b, l))
            }
            color = edge
            thickness = 1.0
        }
        out += box
        out += fline {
            seg(project(-l, 0.0, 0.0), project(l, 0.0, 0.0))
            seg(project(0.0, -l, 0.0), project(0.0, l, 0.0))
            seg(project(0.0, 0.0, -l), project(0.0, 0.0, l))
            color = vec(1.0, 1.0, 1.0, 0.12)
            thickness = 1.0
        }
        val muted = vec(1.0, 1.0, 1.0, 0.55)
        for ((name, w) in listOf(
            "lover" to CubeWiring(-2.0, 0.0, 0.0),
            "coward" to CubeWiring(2.0, 0.0, 0.0),
            "aggressor" to CubeWiring(0.0, 2.0, 0.0),
            "explorer" to CubeWiring(0.0, -2.0, 0.0),
        )) {
            val p = project(w)
            out += fline {
                circle(p.x, p.y, 2.5)
                color = muted
                thickness = 1.0
            }
            out += label(name, p.x + 5, p.y + 3, 9.0, muted)
        }
        val axisColour = vec(1.0, 1.0, 1.0, 0.8)
        project(l + 0.3, 0.0, 0.0).let { out += label("same", it.x, it.y + 4, 11.0, axisColour) }
        project(0.0, l + 0.3, 0.0).let { out += label("cross", it.x - 12, it.y - 4, 11.0, axisColour) }
        project(0.0, 0.0, l + 0.3).let { out += label("drive", it.x - 14, it.y - 4, 11.0, axisColour) }
        if (title.isNotBlank()) out += label(title, x + 8, y + 16, 13.0, vec(1.0, 1.0, 1.0, 0.9))
        return out
    }

    /** [text] in [color], placed to the right of [at]. */
    fun label(text: String, at: CubeWiring, color: Vec4, size: Double = 10.0): List<FLine> {
        val p = project(at)
        return label(text, p.x + 10, p.y + 4, size, color)
    }

    private fun FLine.seg(a: Vec2, b: Vec2) {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
    }

    // Filled per-glyph outlines placed with FLine.transform, and no label at all on a machine with
    // no fonts; the same choices as the chart labels in charts.kt.
    private fun label(text: String, px: Number, py: Number, size: Double, colour: Vec4): List<FLine> = runCatching {
        layoutText(text, typeface(Font.MONOSPACED, size))
            .toFLines(color = colour, thickness = 0.0, filled = true, stroked = false)
            .map { line -> line.transform { p -> vec(p.x + px.toDouble(), p.y + py.toDouble(), p.z) } }
    }.getOrElse { emptyList() }
}
