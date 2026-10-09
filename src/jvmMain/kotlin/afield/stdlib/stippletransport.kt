@file:afield.CompletionCategory("Machine learning")

package afield.stdlib

import afield.graphics.FLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Loads the optional PyTorch stipple trainer's portable `field.transport-flow.v1` model.
 *
 * Coordinates and velocities are normalized image coordinates with y pointing down. Transport time
 * is the ODE variable; movie time is held fixed through each solve. Neither time is clamped, so
 * reverse integration and extrapolation remain available as experiments. This is CPU inference,
 * intended for moderate geometry counts, not a claim of real-time GPU particle performance.
 * See `optional/transport-flow` for training, density sampling, comparison figures and field baking.
 */
fun loadStippleTransport(file: File): StippleTransport = StippleTransport.fromJson(file.readText())

/** Convenience spelling of [loadStippleTransport] for a filename. */
fun loadStippleTransport(path: String): StippleTransport = loadStippleTransport(File(path))

/**
 * A reusable density-conditioned coordinate warp, independent of its training stipples.
 *
 * [transport] preserves list order and each point's z coordinate. For movie playback, draw source
 * points once and re-evaluate those same points at each movie time; repeatedly advancing the previous
 * frame would integrate a different dynamical system. [velocity] is velocity in transport time,
 * not motion with respect to movie time. Query scratch buffers belong to each call, so immutable
 * models can be shared across threads.
 */
class StippleTransport private constructor(private val data: StippleTransportData) {
    /** Whether the network has a fourth input for movie time. */
    val temporal: Boolean get() = data.temporal
    /** Discrete movie times used for training, copied so callers cannot alter the model. */
    val frameTimes: List<Double> get() = data.frameTimes.toList()
    /** Number of learned scalar weights and biases. */
    val parameterCount: Int get() = data.weights.size
    /** Half-width and half-height of the uniform canonical source rectangle. */
    val sourceHalfExtent: List<Double> get() = data.sourceHalfExtent.toList()

    private val widths = data.widths.toIntArray()
    private val weights = data.weights.toDoubleArray()
    private val offsets = IntArray(widths.size - 1)
    private val scratchWidth = widths.max()

    init {
        require(data.format == "field.transport-flow.v1") { "unsupported stipple transport format: ${data.format}" }
        require(data.activation == "silu" && data.coordinates == "normalized-y-down") {
            "stipple transports require SiLU and normalized-y-down coordinates"
        }
        require(widths.size >= 3 && widths.first() == (if (temporal) 4 else 3) && widths.last() == 2 && widths.all { it > 0 }) {
            "expected (x,y,transportTime[,movieTime]) -> hidden layers -> (vx,vy)"
        }
        require(data.sourceHalfExtent.size == 2 && data.sourceHalfExtent.all { it.isFinite() && it > 0 }) {
            "sourceHalfExtent must contain two finite positive values"
        }
        require(data.frameTimes.isNotEmpty() && data.frameTimes.all { it.isFinite() && it in 0.0..1.0 } &&
            data.frameTimes.zipWithNext().all { (a, b) -> a < b } &&
            (if (temporal) data.frameTimes.size >= 2 else data.frameTimes.size == 1)) {
            "frameTimes must be sorted training conditions in 0..1"
        }
        var count = 0L
        for (layer in offsets.indices) {
            require(count <= Int.MAX_VALUE) { "network is too large" }
            offsets[layer] = count.toInt()
            count += widths[layer].toLong() * widths[layer + 1] + widths[layer + 1]
        }
        require(count == weights.size.toLong() && weights.all { it.isFinite() }) { "incorrect weight count or nonfinite weights" }
    }

    /** Predicts `(vx,vy,0)` at any point, at two independent times. */
    fun velocity(at: Vec3, transportTime: Double, movieTime: Double = 0.0): Vec3 {
        require(transportTime.isFinite() && movieTime.isFinite()) { "times must be finite" }
        val query = Query()
        val out = query.evaluate(at.x.toDouble(), at.y.toDouble(), transportTime, movieTime)
        return vec(out[0], out[1], 0.0)
    }

    /**
     * Integrates arbitrary particles, curve vertices or lattice samples. No training-point lookup.
     * [fromTransportTime] can exceed [transportTime] for inversion; both can overshoot 0..1.
     */
    fun transport(
        points: List<Vec3>,
        transportTime: Double = 1.0,
        movieTime: Double = 0.0,
        steps: Int = 32,
        fromTransportTime: Double = 0.0,
        integrator: FlowIntegrator = FlowIntegrator.MIDPOINT,
    ): List<Vec3> {
        validateTimes(transportTime, movieTime, fromTransportTime, steps)
        val query = Query()
        return points.map { query.integrate(it, fromTransportTime, transportTime, movieTime, steps, integrator) }
    }

    /** Single-point flow map, with the same semantics as [transport]. */
    fun flowMap(at: Vec3, transportTime: Double = 1.0, movieTime: Double = 0.0, steps: Int = 32): Vec3 =
        transport(listOf(at), transportTime, movieTime, steps).single()

    /** Traces supplied geometry in transport time, one [FLine] per input point. */
    fun pathsFrom(
        points: List<Vec3>,
        transportTime: Double = 1.0,
        movieTime: Double = 0.0,
        steps: Int = 32,
        fromTransportTime: Double = 0.0,
        integrator: FlowIntegrator = FlowIntegrator.MIDPOINT,
    ): List<FLine> {
        validateTimes(transportTime, movieTime, fromTransportTime, steps)
        val query = Query()
        return points.map { point ->
            val line = FLine().moveTo(point)
            query.integrate(point, fromTransportTime, transportTime, movieTime, steps, integrator) { line.lineTo(it) }
            line
        }
    }

    /** Draw once and retain the returned list for persistent movie identity. */
    fun sourceParticles(count: Int, seed: Long = 1L, grid: Boolean = false): List<Vec3> {
        require(count >= 0) { "particle count must be nonnegative" }
        val random = Random(seed)
        val side = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        return List(count) { i ->
            val x = if (grid) ((i % side) + .5) * 2 / side - 1 else random.nextDouble(-1.0, 1.0)
            val y = if (grid) ((i / side) + .5) * 2 / side - 1 else random.nextDouble(-1.0, 1.0)
            vec(x * data.sourceHalfExtent[0], y * data.sourceHalfExtent[1], 0.0)
        }
    }

    /**
     * Deliberately unsafe weight interpolation; matching metadata does not align hidden neurons.
     * Prefer conditioning one shared model on movie time. Sequential fine-tuning gives checkpoints
     * shared ancestry but still supplies no guarantee. [amount] is not clamped.
     */
    fun unsafeBlendWeights(other: StippleTransport, amount: Double): StippleTransport {
        require(amount.isFinite()) { "blend amount must be finite" }
        require(data.widths == other.data.widths && data.temporal == other.data.temporal &&
            data.frameTimes == other.data.frameTimes && data.sourceHalfExtent == other.data.sourceHalfExtent) {
            "weight interpolation requires matching architecture, times and coordinate domain"
        }
        return StippleTransport(data.copy(weights = weights.indices.map { weights[it] * (1 - amount) + other.weights[it] * amount }))
    }

    private fun validateTimes(t: Double, tau: Double, from: Double, steps: Int) {
        require(steps > 0) { "integration steps must be positive" }
        require(t.isFinite() && tau.isFinite() && from.isFinite()) { "times must be finite" }
    }

    private inner class Query {
        private val a = DoubleArray(scratchWidth)
        private val b = DoubleArray(scratchWidth)

        fun evaluate(x: Double, y: Double, t: Double, tau: Double): DoubleArray {
            require(x.isFinite() && y.isFinite()) { "positions must be finite" }
            a[0] = x; a[1] = y; a[2] = t
            if (temporal) a[3] = tau
            var input = a
            var output = b
            for (layer in offsets.indices) {
                val n = widths[layer + 1]
                val p = widths[layer]
                val offset = offsets[layer]
                for (o in 0 until n) {
                    var sum = weights[offset + n * p + o]
                    for (i in 0 until p) sum += weights[offset + o * p + i] * input[i]
                    output[o] = if (layer == offsets.lastIndex) sum else sum / (1 + exp(-sum))
                }
                val swap = input; input = output; output = swap
            }
            return input
        }

        fun integrate(point: Vec3, from: Double, to: Double, tau: Double, steps: Int,
                      integrator: FlowIntegrator, vertex: ((Vec3) -> Unit)? = null): Vec3 {
            var x = point.x.toDouble()
            var y = point.y.toDouble()
            val h = (to - from) / steps
            for (step in 0 until steps) {
                val t = from + h * step
                val v = evaluate(x, y, t, tau)
                val vx = v[0]; val vy = v[1]
                if (integrator == FlowIntegrator.EULER) {
                    x += h * vx; y += h * vy
                } else {
                    val mid = evaluate(x + .5 * h * vx, y + .5 * h * vy, t + .5 * h, tau)
                    x += h * mid[0]; y += h * mid[1]
                }
                vertex?.invoke(vec(x, y, point.z.toDouble()))
            }
            return vec(x, y, point.z.toDouble())
        }
    }

    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true }

        /** Parses a portable model without requiring a filesystem path. */
        fun fromJson(json: String): StippleTransport =
            StippleTransport(lenientJson.decodeFromString<StippleTransportData>(json))
    }
}

/** Wire representation of the optional trainer's row-major SiLU network export. */
@Serializable
private data class StippleTransportData(
    val format: String,
    val temporal: Boolean,
    val activation: String,
    val coordinates: String,
    val sourceHalfExtent: List<Double>,
    val frameTimes: List<Double>,
    val widths: List<Int>,
    val weights: List<Double>,
)
