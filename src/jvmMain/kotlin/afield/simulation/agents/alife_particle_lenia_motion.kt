@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.media.image.Img

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Measuring and naming what a Particle Lenia creature does, and the creatures of the article's demo.
 *
 * [ParticleLenia.measure] runs a population for a window of steps and records how far its centre of
 * mass travelled, how straight that path was, how much the particles turned about the centre, how its
 * spread changed and whether it broke into separate groups. [ParticleLeniaWindow.motion] turns those
 * numbers into one [LeniaMotion] with fixed thresholds ([LeniaMotionThresholds]), so a sweep over
 * rules can be drawn as a map of behaviours. [ParticleLeniaZoo] holds nine creatures from the
 * article's live demo (`znah.net/lenia`, file `zoo.js`), each with the rule and starting positions
 * the demo ships, as reference points the classification can be checked against.
 */

/** What a creature does over a window: the classes of [ParticleLeniaWindow.motion]. */
enum class LeniaMotion {
    /** Neither travels nor turns. */
    STATIC,

    /** Turns about its centre of mass faster than it travels in a straight line. */
    ROTATING,

    /** Travels in a straight line. */
    GLIDING,

    /** Travels, but along a bending path, and does not turn as a whole. */
    WANDERING,

    /** Breaks into separate groups, or keeps spreading. */
    DISPERSING,
}

/**
 * The thresholds [ParticleLeniaWindow.motion] classifies with. Travel and turning are compared in the
 * same units: the centroid's speed, and the rim speed of the turn (angular velocity times spread), both
 * world units per unit time.
 */
data class LeniaMotionThresholds(
    /** Speed at and above which a creature counts as travelling, or its rim as turning. */
    val speed: Double = 0.005,
    /** Net displacement over path length at and above which travel counts as a straight line. */
    val straightness: Double = 0.9,
    /** Spread growth over the window (end / start) above which a creature counts as dispersing. */
    val spreadGrowth: Double = 1.25,
)

/**
 * What a [ParticleLenia] did over one window of [steps] steps, sampled every few steps: the input to
 * [motion]. Build one with [ParticleLenia.measure].
 */
class ParticleLeniaWindow(
    /** Steps in the window. */
    val steps: Int,
    /** Time in the window, `steps · dt`. */
    val time: Double,
    /** Distance from the centroid at the window's start to the centroid at its end. */
    val displacement: Double,
    /** Length of the centroid's sampled path. */
    val pathLength: Double,
    /** Summed turn of the centred positions between samples, radians, anticlockwise positive. */
    val rotation: Double,
    /** Direction of the net displacement, radians from +x. */
    val heading: Double,
    /** Change of direction between the first and second half of the window, radians. */
    val headingDrift: Double,
    /** RMS distance from the centroid at the window's start. */
    val spreadStart: Double,
    /** RMS distance from the centroid at the window's end. */
    val spreadEnd: Double,
    /** Mean particle energy, averaged over the samples. */
    val meanEnergy: Double,
    /** Separate groups at the window's end ([ParticleLenia.groups]). */
    val groups: Int,
    /** The centroid at each sample, the window's start included: the path [pathLength] measures. */
    val track: List<Pair<Double, Double>> = emptyList(),
) {
    /** Centroid speed, [displacement] over [time]. */
    val speed: Double get() = if (time <= 0.0) 0.0 else displacement / time

    /** [displacement] over [pathLength]: 1 for a straight line, near 0 for a closed loop. */
    val straightness: Double get() = if (pathLength < 1e-12) 0.0 else displacement / pathLength

    /** [rotation] over [time], radians per unit time. */
    val angularVelocity: Double get() = if (time <= 0.0) 0.0 else rotation / time

    /** Rim speed of the turn: [angularVelocity] times [spreadEnd], in the units of [speed]. */
    val rimSpeed: Double get() = abs(angularVelocity) * spreadEnd

    /**
     * The class these numbers fall in under [thresholds]: [LeniaMotion.DISPERSING] if it ended in more
     * than one group or kept spreading; [LeniaMotion.GLIDING] if it travelled in a straight line faster
     * than its rim turned; [LeniaMotion.ROTATING] if its rim turned; [LeniaMotion.WANDERING] if it
     * travelled any other way; otherwise [LeniaMotion.STATIC].
     */
    fun motion(thresholds: LeniaMotionThresholds = LeniaMotionThresholds()): LeniaMotion = when {
        groups > 1 || spreadEnd > thresholds.spreadGrowth * spreadStart -> LeniaMotion.DISPERSING
        speed >= thresholds.speed && straightness >= thresholds.straightness && speed >= rimSpeed -> LeniaMotion.GLIDING
        rimSpeed >= thresholds.speed -> LeniaMotion.ROTATING
        speed >= thresholds.speed -> LeniaMotion.WANDERING
        else -> LeniaMotion.STATIC
    }

    /** The class under the default thresholds. */
    val motion: LeniaMotion get() = motion(LeniaMotionThresholds())

    override fun toString(): String =
        "%s: speed %.4f/t, straightness %.2f, heading drift %.3f rad, rotation %.4f rad/t (rim %.4f/t), spread %.2f -> %.2f, mean E %.3f, %d group%s".format(
            motion, speed, straightness, headingDrift, angularVelocity, rimSpeed, spreadStart, spreadEnd, meanEnergy,
            groups, if (groups == 1) "" else "s",
        )
}

/**
 * Steps this population [steps] times and measures what it did, sampling the centroid, the turn and
 * the energy every [every] steps (the turn between samples must stay well under half a revolution, which
 * it does for any creature here at the default).
 */
fun ParticleLenia.measure(steps: Int, every: Int = 50): ParticleLeniaWindow {
    require(steps > 0 && every > 0) { "steps and every must be positive" }
    val n = count
    var prev = centred()
    val (x0, y0) = centroid()
    val xs = mutableListOf(x0)
    val ys = mutableListOf(y0)
    val spreadStart = spread()
    var rotation = 0.0
    var energy = 0.0
    var samples = 0
    var done = 0
    while (done < steps) {
        val k = minOf(every, steps - done)
        stepTimes(k)
        done += k
        val now = centred()
        var cross = 0.0
        var dot = 0.0
        for (i in 0 until n) {
            cross += prev[2 * i] * now[2 * i + 1] - prev[2 * i + 1] * now[2 * i]
            dot += prev[2 * i] * now[2 * i] + prev[2 * i + 1] * now[2 * i + 1]
        }
        rotation += atan2(cross, dot)
        prev = now
        val (cx, cy) = centroid()
        xs += cx; ys += cy
        energy += energyHistory.last()
        samples++
    }
    var path = 0.0
    for (i in 1 until xs.size) path += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    val dx = xs.last() - xs.first()
    val dy = ys.last() - ys.first()
    val mid = xs.size / 2
    val h1 = atan2(ys[mid] - ys.first(), xs[mid] - xs.first())
    val h2 = atan2(ys.last() - ys[mid], xs.last() - xs[mid])
    var drift = h2 - h1
    while (drift > Math.PI) drift -= 2 * Math.PI
    while (drift < -Math.PI) drift += 2 * Math.PI
    return ParticleLeniaWindow(
        steps = steps,
        time = steps * params.dt,
        displacement = hypot(dx, dy),
        pathLength = path,
        rotation = rotation,
        heading = atan2(dy, dx),
        headingDrift = drift,
        spreadStart = spreadStart,
        spreadEnd = spread(),
        meanEnergy = energy / samples,
        groups = groups(),
        track = xs.indices.map { xs[it] to ys[it] },
    )
}

/**
 * Number of separate groups: particles are joined when they are closer than [link] (by default
 * `muK + sigmaK`, the distance at which one particle's kernel ring still reaches another), and a group
 * is a connected set.
 */
fun ParticleLenia.groups(link: Double = params.muK + params.sigmaK): Int {
    val n = count
    val parent = IntArray(n) { it }
    fun find(a: Int): Int {
        var x = a
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
        return x
    }
    val l2 = link * link
    for (i in 0 until n) for (j in i + 1 until n) {
        val dx = positions[2 * i] - positions[2 * j]
        val dy = positions[2 * i + 1] - positions[2 * j + 1]
        if (dx * dx + dy * dy < l2) parent[find(i)] = find(j)
    }
    return (0 until n).count { find(it) == it }
}

/**
 * One cell of a behaviour map from [leniaMotionMap]: the cell's coordinates [x] and [y], the population
 * as it stands after the window, and what it did in the window.
 */
class LeniaMapCell(
    /** The cell's value on the map's first axis. */
    val x: Double,
    /** The cell's value on the map's second axis. */
    val y: Double,
    /** The population at the end of the window. */
    val lenia: ParticleLenia,
    /** What it did in the window. */
    val window: ParticleLeniaWindow,
) {
    /** The class of the window under the default thresholds. */
    val motion: LeniaMotion get() = window.motion
}

/**
 * A behaviour map: for every pair of [xs] and [ys], [make] builds a population, which runs [settle]
 * steps unmeasured and then a measured window of [window] steps. The cells run in parallel on all cores,
 * and come back row by row, the last of [ys] first (so the list reads like the map drawn with the
 * largest y on top) and [xs] in order within a row.
 */
fun leniaMotionMap(
    xs: List<Double>,
    ys: List<Double>,
    settle: Int,
    window: Int,
    every: Int = 50,
    make: (x: Double, y: Double) -> ParticleLenia,
): List<LeniaMapCell> {
    val pairs = ys.reversed().flatMap { y -> xs.map { x -> x to y } }
    return pairs.parallelStream().map { (x, y) ->
        val pl = make(x, y)
        pl.stepTimes(settle)
        val w = pl.measure(window, every)
        LeniaMapCell(x, y, pl, w)
    }.toList()
}

/** A colour per [LeniaMotion], RGB in `0..1`, for drawing maps. */
fun LeniaMotion.colour(): FloatArray = when (this) {
    LeniaMotion.STATIC -> floatArrayOf(0.45f, 0.45f, 0.5f)
    LeniaMotion.ROTATING -> floatArrayOf(1.0f, 0.75f, 0.2f)
    LeniaMotion.GLIDING -> floatArrayOf(0.3f, 0.85f, 0.4f)
    LeniaMotion.WANDERING -> floatArrayOf(0.35f, 0.6f, 1.0f)
    LeniaMotion.DISPERSING -> floatArrayOf(0.9f, 0.3f, 0.35f)
}

/**
 * One map cell drawn as a [size]-pixel tile: the particles over `−E` framed [extent] world units round
 * their centroid, the window's centroid track as small dots, and a [frame]-pixel border in the colour of
 * the cell's [LeniaMotion].
 */
fun LeniaMapCell.tile(extent: Double = 24.0, size: Int = 160, frame: Int = 5): Img {
    val (cx, cy) = lenia.centroid()
    val img = splatDots(lenia.render(extent, size, cx = cx, cy = cy, dotRadius = 1.6), window.track, extent, cx, cy, dotRadius = 0.9)
    val c = motion.colour()
    val d = img.data
    for (y in 0 until size) for (x in 0 until size) {
        if (x >= frame && y >= frame && x < size - frame && y < size - frame) continue
        for (k in 0 until 3) d[(y * size + x) * 3 + k] = c[k]
    }
    return img
}

private fun ParticleLenia.centred(): DoubleArray {
    val (cx, cy) = centroid()
    return DoubleArray(positions.size) { if (it % 2 == 0) positions[it] - cx else positions[it] - cy }
}

/**
 * One creature from the article's live demo: its [name], its rule (with the demo's own `dt`) and its
 * starting positions, interleaved `x0, y0, x1, y1, …`.
 */
class ParticleLeniaCreature(
    /** The demo's name for it. */
    val name: String,
    /** Its rule, `dt` included. */
    val params: ParticleLeniaParams,
    /** Starting positions, interleaved. */
    val points: DoubleArray,
) {
    /** Number of particles. */
    val count: Int get() = points.size / 2

    /** A fresh population at the creature's starting positions, under its rule. */
    fun lenia(): ParticleLenia = ParticleLenia(points.copyOf(), params)

    override fun toString(): String = "%s (%d particles, muK %.2f, sigmaK %.2f, muG %.2f, sigmaG %.2f, cRep %.1f, dt %.2f)".format(
        name, count, params.muK, params.sigmaK, params.muG, params.sigmaG, params.cRep, params.dt,
    )
}

/**
 * Nine creatures from the zoo of the article's live demo (`znah.net/lenia/zoo.js`, by Alexander
 * Mordvintsev), with the rules and starting positions the demo ships. The demo integrates with
 * inertia 0.5 and cuts the kernel at `muK ± 3 sigmaK`; [ParticleLenia] uses the article's plain Euler
 * step and an uncut kernel, which changes nothing a steady creature does measurably.
 */
object ParticleLeniaZoo {
    /** The creatures, in the order the page lists them. */
    val creatures: List<ParticleLeniaCreature> by lazy {
        listOf(
            ParticleLeniaCreature(
                "Iguanmousauru",
                ParticleLeniaParams(
                    muK = 1.5, sigmaK = 0.69, wK = 0.08675273164901566,
                    muG = 0.17, sigmaG = 0.15, cRep = 1.0, dt = 0.1,
                ),
                doubleArrayOf(
                    -0.6461, -0.3028, 0.6037, -0.3804, 0.1058, 1.704, -0.06625, -1.066, 0.00283, 0.04532,
                ),
            ),
            ParticleLeniaCreature(
                "Agilienster",
                ParticleLeniaParams(
                    muK = 2.37, sigmaK = 0.54, wK = 0.07016378098350948,
                    muG = 0.2, sigmaG = 0.13, cRep = 1.0, dt = 0.1,
                ),
                doubleArrayOf(
                    -2.1141, 1.5161, 2.1141, 0.50157, -0.20154, 0.16996, 0.72311, -1.5161, -1.7837, -0.91304,
                ),
            ),
            ParticleLeniaCreature(
                "Tarbosaurolop",
                ParticleLeniaParams(
                    muK = 3.13, sigmaK = 2.63, wK = 0.010787660998384171,
                    muG = 0.23, sigmaG = 0.11, cRep = 0.4, dt = 0.09,
                ),
                doubleArrayOf(
                    1.8917, 1.1981, 0.68365, -3.286, 0.9575, 1.0531, 1.479, -3.2525, 0.22779, 2.6796,
                    1.6394, 3.0787, -0.61684, 1.7188, 1.7117, 1.6413, 1.3272, 1.3428, -0.072875, -3.2194,
                    0.49688, 1.2038, -2.3353, -2.3836, 0.90376, 3.286, 1.2609, 1.7797, -0.55707, 2.6876,
                    0.83416, 1.5087, 0.39859, 1.6773, 1.0255, 2.8463, -0.40486, 1.3254, 0.62406, 2.4855,
                    -0.091957, 1.8197, 0.61224, 0.73084, -0.23758, 2.2574, -1.5667, -2.7985, -0.76732, 2.2049,
                    -0.19259, 3.0154, 0.071969, 1.3753, 1.669, 2.1122, 0.18973, 2.1504, -0.81588, -3.0624,
                    0.2955, 3.2555, 1.5868, 2.5771, 2.1954, 1.82, 2.3353, -3.0798, 0.032679, 0.92716,
                    2.1323, 2.4538, 1.1404, 2.3159, 1.3672, 0.84501, 0.79119, 1.9647,
                ),
            ),
            ParticleLeniaCreature(
                "Coelopasaurop",
                ParticleLeniaParams(
                    muK = 4.26, sigmaK = 1.44, wK = 0.014638188318063633,
                    muG = 0.28, sigmaG = 0.07, cRep = 1.0, dt = 0.12,
                ),
                doubleArrayOf(
                    -1.6344, -3.5107, 2.5975, -1.2801, -0.80385, -3.7884, 2.5137, -0.37237, 0.071153, 3.793,
                    -0.28321, -2.2925, -1.7816, 2.6389, 1.8344, -0.99797, -4.2981, -0.57158, 1.5538, 3.4468,
                    3.2931, -0.6352, -0.9234, -0.54594, 3.0093, 1.0389, -0.96573, -2.8919, -1.733, -0.38199,
                    -2.8456, 0.5378, -0.19469, -3.1426, 2.2223, 3.0746, 3.44, -1.5173, -0.47033, -1.4092,
                    -0.035453, 1.3032, 0.13304, 0.39092, 0.82915, 3.6889, 0.41057, -0.9913, -2.3585, 2.085,
                    -3.0142, 1.4711, 3.0922, -3.04, -1.7326, -2.6284, 2.7972, 2.5618, -3.5703, -0.0074961,
                    1.1561, -1.3468, 2.8499, -2.1515, -3.6616, 0.85408, -0.69658, 3.7287, -1.3858, -1.215,
                    0.8103, 1.0254, -1.1506, -2.0027, -2.0663, 0.30408, 3.0534, 1.8254, -2.2332, 1.2126,
                    0.37794, -1.8039, -1.3439, 3.2938, 3.1379, 0.24966,
                ),
            ),
            ParticleLeniaCreature(
                "Sauropeltad",
                ParticleLeniaParams(
                    muK = 3.24, sigmaK = 1.0, wK = 0.027714693488486323,
                    muG = 0.23, sigmaG = 0.13, cRep = 0.5, dt = 0.1,
                ),
                doubleArrayOf(
                    -4.8521, 1.2751, 1.8935, -1.3549, -0.46362, -2.5122, 0.4698, -2.2804, 0.80496, 4.9521,
                    1.0858, -1.6703, 1.5812, 2.2025, -2.269, 0.23892, -1.0629, -1.8807, -2.0084, 1.0907,
                    2.1503, 0.20842, 0.70201, 2.1707, -2.7538, -1.4091, 2.4054, 1.9453, 2.7767, -1.4162,
                    -1.8294, 1.9809, -1.0686, 2.4763, -2.6803, -4.3551, -2.655, -0.55155, 2.4841, -0.59573,
                    -1.847, -1.4587, 2.0831, 1.0963, 5.0683, 0.68167, 0.16734, -3.1126, -0.18226, 2.2786,
                ),
            ),
            ParticleLeniaCreature(
                "Orodromic",
                ParticleLeniaParams(
                    muK = 2.32, sigmaK = 1.05, wK = 0.03685932294122277,
                    muG = 0.28, sigmaG = 0.07, cRep = 0.4, dt = 0.05,
                ),
                doubleArrayOf(
                    -1.5864, 0.72035, -2.0294, -0.0098085, 0.6504, 0.27926, 0.86813, -1.0413, 0.58176, 1.14,
                    -1.1711, 1.2574, 0.85764, -0.35371, -0.16782, -2.114, 1.1262, 0.75452, -0.3788, 1.7634,
                    0.8619, -1.4961, -2.0544, -1.1317, 2.6645, -0.33802, -0.22256, 0.56971,
                ),
            ),
            ParticleLeniaCreature(
                "Nedocernosa",
                ParticleLeniaParams(
                    muK = 1.7, sigmaK = 1.15, wK = 0.04578904711427168,
                    muG = 0.31, sigmaG = 0.12, cRep = 0.4, dt = 0.1,
                ),
                doubleArrayOf(
                    3.6895, -0.46982, -0.56321, -4.6872, 0.93358, 4.2823, 0.21502, -4.8823, 0.54061, -2.1066,
                    -3.736, 0.63214, -0.42562, 1.7426, -2.8948, -1.3175, -0.12185, 0.7984, 3.8521, -1.4106,
                    0.43134, -1.8445, -1.7497, -3.8312, 0.15707, 1.3958, -0.7973, 0.91225, -0.49648, 0.74032,
                    0.22438, 4.5183, -3.0508, 3.3474, -3.5492, -0.057562, -1.2384, 4.4983, -3.789, 1.3477,
                    2.7111, -4.0504, -2.4272, -2.6328, 3.0525, 2.0416, -0.06206, 1.6688, 3.3142, -3.2846,
                    -0.75374, 1.5739, 1.0688, -4.8604, -2.1473, -3.2603, -2.5373, 3.8593, 4.3924, 0.83565,
                    3.1414, 1.207, 2.1552, 3.3674, 3.6793, -2.371, 1.5852, 3.8926, -3.4416, 2.7384,
                    0.13482, 1.0628, -0.90008, 1.2389, 1.9308, -4.5866, -1.9241, 4.2496, 1.1438, -2.0352,
                    -0.511, 4.5907, 3.3199, 0.35765, 0.59869, -1.5902, -3.6915, 2.0611, 0.85224, -2.201,
                    2.6599, 2.7535, 1.1877, -1.7195, -2.6292, -1.9695, -1.2237, -4.3209, -3.2459, -0.70101,
                    0.93546, -1.5238,
                ),
            ),
            ParticleLeniaCreature(
                "Chungasto",
                ParticleLeniaParams(
                    muK = 2.73, sigmaK = 0.62, wK = 0.053051876936485554,
                    muG = 1.05, sigmaG = 0.26, cRep = 1.0, dt = 0.15,
                ),
                doubleArrayOf(
                    -5.0262, 0.68812, 4.1124, -4.6777, 0.47999, -0.35615, 1.0712, -4.7145, -1.1201, 0.63565,
                    -0.36983, -2.5331, 3.0851, 0.407, -4.5323, 3.0546, 0.20459, 4.9911, 2.9073, -2.6339,
                    -3.6026, -4.0156, -1.338, -6.0827, -3.8028, 0.13357, 0.60521, 4.1923, -4.9187, 3.3179,
                    6.2588, 0.70823, -4.222, 0.4257, 1.2399, -2.6137, -3.8594, -0.68902, -0.46392, 4.2341,
                    3.0655, -3.685, 2.5105, 2.2222, -3.3868, -2.5623, 4.9837, -2.1308, 2.0899, -3.3037,
                    2.4923, -3.4612, -4.2742, -4.8198, 2.3343, 3.0158, 5.7691, 0.19287, 3.2268, -2.3564,
                    -0.33921, -0.913, -2.0425, -3.5125, -4.6716, 4.0404, 2.7113, -2.9517, 5.2777, 0.26888,
                    5.6222, -2.5583, -3.9846, -1.5784, 4.0291, -0.83757, 1.2722, 3.8235, 2.7813, 2.7434,
                    0.77382, -6.1603, 4.8247, 3.7175, 3.1175, 1.0898, -1.137, -3.895, 0.75323, -0.13018,
                    0.301, -0.77938, -4.5748, -4.4542, -1.0988, -2.951, -3.182, 1.3153, -0.29716, -3.8686,
                    4.8268, 0.72141, 4.278, -1.6969, 3.1805, -4.187, -3.499, -3.6347, -3.8444, 0.84057,
                    -5.899, 1.349, -2.93, 2.5715, 2.3043, 0.92351, 0.094187, -1.2827, -4.6035, 0.97785,
                    1.4785, -5.1934, 0.65713, -5.0798, 0.27564, 4.4154, 3.2217, 5.101, 0.70518, 0.67282,
                    6.2986, 0.28858, -0.22597, -3.219, -2.4496, 5.979, -1.9447, -5.3322, -1.7306, -4.9213,
                    -2.16, 3.5805, 5.8387, 0.91074, -4.0446, 2.8308, -1.1143, -4.6525, 3.856, -1.9108,
                    -1.1235, 0.316, -2.304, 5.4016, 3.5194, 1.7495, 4.226, 0.67751, 1.6958, -5.7783,
                    -2.8425, -3.4285, -0.37581, -0.3443, 1.3308, -3.6024, 5.026, 2.6088, 0.17525, -3.7971,
                    1.0593, -3.9603, -5.5975, 0.6694, 5.5108, 3.0215, 2.6221, 5.0035, -4.2169, 3.7266,
                    -6.1284, 0.82331, -3.6999, -0.2719, -1.8007, 4.0141, 4.4641, 2.4904, -3.4589, 2.957,
                    1.4206, -6.272, -2.8231, 5.4174, -3.8853, 3.4152, -0.53898, 0.60756, 2.6293, 1.6057,
                    -1.5036, 3.8862, 1.2877, 0.81506, -5.8361, -1.7618, -1.1147, -5.139, -1.9904, 2.4378,
                    4.6639, -1.8502, 1.8013, 2.3655, -2.1173, 1.6876, -5.0381, 3.7889, 4.5774, 0.22141,
                    -4.5145, -1.5035, -4.9782, -1.6411, -3.9229, -4.482, 3.5561, -2.1465, -0.62383, -0.006592,
                    3.2118, 2.5169, -2.1076, 0.72511, -2.1157, -5.8628, 4.3293, 3.384, 2.4587, 3.4803,
                    2.7887, 4.4375, 1.7769, 1.7097, 0.26052, 5.5766, 3.6871, 1.3153, 3.5521, 2.1891,
                    5.4067, -2.1232, 3.6595, -4.8358, 2.988, 5.5494, 0.86765, 0.35857, -5.8497, -2.2024,
                    3.676, -4.2338, -2.3859, 3.3046, 4.0919, -1.2145, 5.3826, 3.588, 0.28373, 0.61351,
                    -2.4343, -3.3856, 0.93482, 3.9999, -0.027447, 6.4894, 5.9082, -2.2133, 2.0876, 3.7894,
                    -0.14412, 4.2954, -0.81462, 4.1199, -2.7453, 3.0267, -2.9303, 0.31107, -2.5103, 5.0059,
                    -0.69999, -3.9963, -3.3321, 2.2294, -1.7075, -3.7033, 4.0157, 2.8454, -4.1283, -4.1001,
                    3.9316, 0.92081, -2.618, 1.1919, -2.1995, 4.3036, -1.8964, -6.2298, -0.12914, 1.0499,
                    -3.7334, -1.8891, 0.67993, -3.223, -3.1002, -2.8815, -1.1338, 3.9852, -2.0806, 4.7618,
                    -3.2666, 1.8167, -2.8524, 5.8446, 5.3437, 0.82842, -5.3274, 1.2766, -4.0177, -1.0304,
                    -5.3447, -1.8735, -3.5536, 1.077, 4.1406, -0.056141, 0.45189, 6.3548, 0.56532, -3.9402,
                    0.14973, 5.9755, -3.2754, -3.1797, -1.1417, -5.6331, 1.646, 3.7471, 0.60889, -5.6003,
                    -3.5746, -2.2273, 1.6562, -3.361, 2.2826, 4.3406, 4.0356, -0.42383, 0.48471, -2.443,
                    -1.5227, -4.2388,
                ),
            ),
            ParticleLeniaCreature(
                "Bellusauropel",
                ParticleLeniaParams(
                    muK = 2.32, sigmaK = 1.05, wK = 0.03685932294122277,
                    muG = 0.28, sigmaG = 0.07, cRep = 0.4, dt = 0.05,
                ),
                doubleArrayOf(
                    -2.3615, 0.64267, -0.14795, -2.0351, 0.7838, 1.8578, -1.2082, 1.9847, 1.3579, 1.5422,
                    0.52447, -2.0283, 2.3618, -0.64248, 1.9183, 1.2057, -0.52415, 2.0283, -1.3579, -1.5423,
                    -0.78363, -1.8578, -2.1426, 1.285, 3.8701, -0.8429, 1.2085, -1.9849, 1.8428, -1.8684,
                    2.4088, 0.71415, 0.1482, 2.0351, -2.4912, -0.023577, 2.4915, 0.023745, -1.8427, 1.8685,
                    -2.4087, -0.714, -1.9183, -1.2058, -3.8701, 0.84187, 2.1428, -1.2848,
                ),
            ),
        )
    }

    /** The creature called [name]. */
    operator fun get(name: String): ParticleLeniaCreature =
        creatures.firstOrNull { it.name == name } ?: error("no creature '$name'; the zoo has " + creatures.joinToString { it.name })
}

/**
 * A Particle Lenia rule as a genome for [afield.study.search.PopulationSearch]: `[muK, sigmaK, muG,
 * sigmaG, cRep]`, each drawn uniformly from its range in [ranges], with `wK` set by
 * [ParticleLeniaParams.normalisedWK] so that `muG` means the same thing at every kernel size (the
 * article's convention and the demo's). [params] turns a genome into the rule.
 */
class ParticleLeniaRuleGenome(
    /** Range of each gene, in genome order. */
    val ranges: List<ClosedFloatingPointRange<Double>> = listOf(1.5..6.0, 0.5..2.5, 0.1..0.9, 0.05..0.25, 0.2..1.5),
    /** The Euler step every rule runs with. */
    val dt: Double = 0.1,
) : afield.study.search.GenomeOps<DoubleArray> {
    init { require(ranges.size == 5) { "a rule genome has five genes: muK, sigmaK, muG, sigmaG, cRep" } }

    override fun random(rng: kotlin.random.Random): DoubleArray =
        DoubleArray(5) { ranges[it].start + rng.nextDouble() * (ranges[it].endInclusive - ranges[it].start) }

    override val mutations: List<afield.study.search.Variation<DoubleArray>> = listOf(
        afield.study.search.Variation("nudgeOne") { g, rng ->
            g.copyOf().also { val i = rng.nextInt(5); it[i] = clamp(i, it[i] + (rng.nextDouble() * 2 - 1) * 0.1 * width(i)) }
        },
        afield.study.search.Variation("nudgeAll") { g, rng -> DoubleArray(5) { clamp(it, g[it] + (rng.nextDouble() * 2 - 1) * 0.04 * width(it)) } },
    )

    override fun crossover(a: DoubleArray, b: DoubleArray, rng: kotlin.random.Random): DoubleArray =
        DoubleArray(5) { if (rng.nextBoolean()) a[it] else b[it] }

    /** Rounded to three decimals. */
    override fun signature(g: DoubleArray): String = g.joinToString(",") { "%.3f".format(it) }

    /** The rule a genome stands for. */
    fun params(g: DoubleArray): ParticleLeniaParams =
        ParticleLeniaParams(muK = g[0], sigmaK = g[1], wK = ParticleLeniaParams.normalisedWK(g[0], g[1]), muG = g[2], sigmaG = g[3], cRep = g[4], dt = dt)

    private fun width(i: Int) = ranges[i].endInclusive - ranges[i].start
    private fun clamp(i: Int, v: Double) = v.coerceIn(ranges[i].start, ranges[i].endInclusive)
}

/**
 * "Travel in a straight line, steadily, and stay in one piece", from every one of [seeds]: [count]
 * particles from the seed square of [ParticleLenia.random] (side `12 · sqrt(count / 200)`, the
 * article's density) under the genome's rule run [settle] steps, then two consecutive measured
 * windows of [window] steps. A window scores its speed times its straightness; a seed scores the
 * smaller of its two windows, and the fitness is the smallest score over the seeds, so a rule has to
 * glide from all of them. A seed scores zero when either window ends in more than one group or changes
 * the spread by more than [maxSpreadChange] (a fraction), which rules out a population that is still
 * relaxing: a glider holds its shape, so its spread stays put while it travels. The
 * [afield.study.search.Evaluation.behaviour] is `[speed, straightness, angularVelocity, spread change]`
 * of the first seed's second window.
 */
fun particleLeniaGlideObjective(
    genome: ParticleLeniaRuleGenome,
    count: Int = 40,
    seeds: List<Long> = listOf(1L, 2L),
    settle: Int = 3000,
    window: Int = 1500,
    maxSpreadChange: Double = 0.02,
): afield.study.search.Objective<DoubleArray> = afield.study.search.Objective { g ->
    require(seeds.isNotEmpty()) { "the glide objective needs at least one seed" }
    fun change(w: ParticleLeniaWindow) = abs(w.spreadEnd / w.spreadStart - 1.0)
    var fitness = Double.POSITIVE_INFINITY
    var behaviour = DoubleArray(0)
    for (seed in seeds) {
        val pl = ParticleLenia.random(count, side = 12.0 * kotlin.math.sqrt(count / 200.0), seed = seed, params = genome.params(g))
        pl.stepTimes(settle)
        val a = pl.measure(window)
        val b = pl.measure(window)
        if (behaviour.isEmpty()) behaviour = doubleArrayOf(b.speed, b.straightness, b.angularVelocity, change(b))
        val steady = a.groups == 1 && b.groups == 1 && change(a) <= maxSpreadChange && change(b) <= maxSpreadChange
        fitness = minOf(fitness, if (steady) minOf(a.speed * a.straightness, b.speed * b.straightness) else 0.0)
        if (fitness == 0.0) break
    }
    afield.study.search.Evaluation(fitness = fitness, behaviour = behaviour)
}
