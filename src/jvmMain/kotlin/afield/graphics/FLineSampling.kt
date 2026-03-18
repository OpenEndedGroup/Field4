package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.prettyNumber
import afield.stdlib.prettySummary
import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * A sampled point on an [FLine], with normalized parameter [t] in [0, 1].
 */
data class FLineSample(val t: Float, val point: Vec3) {
    override fun toString(): String = "FLineSample(t=${prettyNumber(t)}, point=$point)"
}

/**
 * Sampling request context for [FLineSampler].
 */
data class FLineSampleContext(
    val requestedCount: Int = 100,
    val seed: Long? = null,
    val pointSetAware: Boolean = true
) {
    override fun toString(): String = prettySummary(
        "FLineSampleContext",
        "requestedCount" to requestedCount,
        "seed" to seed,
        "pointSetAware" to pointSetAware
    )
}

/**
 * Composable sampler interface for extracting point-series data from an [FLine].
 */
fun interface FLineSampler {
    fun sample(line: FLine, context: FLineSampleContext): List<FLineSample>
}

/**
 * Built-in sampler factories.
 */
object FLineSamplers {

    /**
     * Uniform parameter sampling (equivalent to legacy sampleByNumber for non point-set lines).
     */
    fun uniform(): FLineSampler = FLineSampler { line, context ->
        val count = context.requestedCount.coerceAtLeast(1)
        sampleAtParams(line, uniformParams(count), context.pointSetAware)
    }

    /**
     * Return instruction points (node endpoints) in order.
     */
    fun points(): FLineSampler = FLineSampler { line, _ ->
        val pts = line.pointLikeNodes()
        if (pts.isEmpty()) emptyList()
        else if (pts.size == 1) listOf(FLineSample(0f, Vec3(pts[0])))
        else pts.mapIndexed { i, p -> FLineSample(i.toFloat() / (pts.size - 1).toFloat(), Vec3(p)) }
    }

    /**
     * Arc-length sampling by fixed spacing. For point-set lines this returns point nodes.
     */
    fun byDistance(spacing: Float): FLineSampler = FLineSampler { line, context ->
        if (context.pointSetAware && line.isPointSetLike()) return@FLineSampler points().sample(line, context)
        val pts = line.sampleByDistance(spacing.coerceAtLeast(1e-6f))
        if (pts.isEmpty()) emptyList()
        else if (pts.size == 1) listOf(FLineSample(0f, Vec3(pts[0])))
        else pts.mapIndexed { i, p -> FLineSample(i.toFloat() / (pts.size - 1).toFloat(), Vec3(p)) }
    }

    /**
     * Random parameter sampling (optionally stratified). Endpoints are included by default.
     */
    fun random(
        stratified: Boolean = true,
        includeEnds: Boolean = true,
        seed: Long? = null
    ): FLineSampler = FLineSampler { line, context ->
        val count = context.requestedCount.coerceAtLeast(1)
        if (context.pointSetAware && line.isPointSetLike()) {
            return@FLineSampler randomPointSetSamples(line, count, seed ?: context.seed)
        }
        if (count == 1) return@FLineSampler sampleAtParams(line, floatArrayOf(0f), context.pointSetAware)

        val rng = if (seed != null || context.seed != null) Random(seed ?: context.seed!!) else Random.Default
        val params = FloatArray(count)
        for (i in 0 until count) {
            params[i] = if (stratified) {
                val lo = i.toFloat() / count.toFloat()
                val hi = (i + 1).toFloat() / count.toFloat()
                lo + (hi - lo) * rng.nextFloat()
            } else {
                rng.nextFloat()
            }
        }
        params.sort()
        if (includeEnds) {
            params[0] = 0f
            params[count - 1] = 1f
        }
        sampleAtParams(line, params, context.pointSetAware)
    }

    /**
     * Curvature-adaptive sampling: spends more samples where turning is higher.
     */
    fun curvatureAdaptive(
        curvaturePower: Float = 1f,
        gain: Float = 2f,
        oversample: Int = 4,
        minBaseSamples: Int = 32
    ): FLineSampler = FLineSampler { line, context ->
        val count = context.requestedCount.coerceAtLeast(1)
        if (count <= 2 || (context.pointSetAware && line.isPointSetLike())) {
            return@FLineSampler uniform().sample(line, context)
        }
        val baseCount = max(minBaseSamples, count * oversample.coerceAtLeast(1))
        val base = uniform().sample(line, context.copy(requestedCount = baseCount))
        if (base.size <= 2) return@FLineSampler base

        val n = base.size
        val vertexWeight = FloatArray(n) { 1f }
        var maxCurvature = 0f
        for (i in 1 until n - 1) {
            val a = Vec3(base[i].point).sub(base[i - 1].point)
            val b = Vec3(base[i + 1].point).sub(base[i].point)
            val la = a.length()
            val lb = b.length()
            if (la <= 1e-6f || lb <= 1e-6f) continue
            val cosA = (a.dot(b) / (la * lb)).coerceIn(-1f, 1f)
            val angle = acos(cosA)
            val curvature = angle / ((la + lb) * 0.5f).coerceAtLeast(1e-6f)
            vertexWeight[i] = curvature
            if (curvature > maxCurvature) maxCurvature = curvature
        }
        if (maxCurvature > 1e-6f) {
            for (i in vertexWeight.indices) {
                val normalized = (vertexWeight[i] / maxCurvature).coerceIn(0f, 1f)
                vertexWeight[i] = 1f + gain * normalized.toDouble().pow(curvaturePower.toDouble()).toFloat()
            }
        }

        val cumulative = FloatArray(n)
        for (i in 1 until n) {
            val segLen = base[i].point.distance(base[i - 1].point)
            val segWeight = 0.5f * (vertexWeight[i - 1] + vertexWeight[i])
            cumulative[i] = cumulative[i - 1] + segLen * segWeight
        }
        val total = cumulative.last()
        if (total <= 1e-6f) return@FLineSampler downsampleSamples(base, count)

        val out = ArrayList<FLineSample>(count)
        for (i in 0 until count) {
            val target = if (count == 1) 0f else i.toFloat() / (count - 1).toFloat() * total
            val idx = cumulative.binarySearch(target).let { if (it >= 0) it else (-it - 1).coerceIn(1, n - 1) }
            val c0 = cumulative[idx - 1]
            val c1 = cumulative[idx]
            val alpha = if (abs(c1 - c0) <= 1e-6f) 0f else ((target - c0) / (c1 - c0)).coerceIn(0f, 1f)
            val t = base[idx - 1].t + (base[idx].t - base[idx - 1].t) * alpha
            val p = Vec3(base[idx - 1].point).lerp(base[idx].point, alpha)
            out += FLineSample(t, p)
        }
        out
    }

    /**
     * Ramer-Douglas-Peucker simplification on a dense sample.
     */
    fun douglasPeucker(
        epsilon: Float,
        denseOversample: Int = 4,
        minDenseSamples: Int = 64
    ): FLineSampler = FLineSampler { line, context ->
        val denseCount = max(minDenseSamples, context.requestedCount.coerceAtLeast(2) * denseOversample.coerceAtLeast(1))
        val dense = uniform().sample(line, context.copy(requestedCount = denseCount))
        if (dense.size < 3) return@FLineSampler dense
        val simplified = rdpSimplifySamples(dense, epsilon.coerceAtLeast(1e-7f))
        if (context.requestedCount > 0 && simplified.size > context.requestedCount) {
            downsampleSamples(simplified, context.requestedCount)
        } else simplified
    }

    /**
     * Patterned spacing with repeating interval weights (e.g. [1,2,1,3]).
     */
    fun patterned(
        pattern: List<Float>,
        includeEnds: Boolean = true
    ): FLineSampler = FLineSampler { line, context ->
        val count = context.requestedCount.coerceAtLeast(1)
        if (count == 1) return@FLineSampler sampleAtParams(line, floatArrayOf(0f), context.pointSetAware)
        val normalizedPattern = if (pattern.isEmpty()) listOf(1f) else pattern.map { it.coerceAtLeast(1e-6f) }
        val increments = FloatArray(count - 1) { i -> normalizedPattern[i % normalizedPattern.size] }
        var total = 0f
        for (v in increments) total += v
        if (total <= 1e-6f) return@FLineSampler uniform().sample(line, context)
        val params = FloatArray(count)
        var acc = 0f
        params[0] = 0f
        for (i in 1 until count) {
            acc += increments[i - 1]
            params[i] = (acc / total).coerceIn(0f, 1f)
        }
        if (includeEnds) {
            params[0] = 0f
            params[count - 1] = 1f
        }
        sampleAtParams(line, params, context.pointSetAware)
    }

    /**
     * Compose several samplers by applying each to the output polyline of the previous.
     */
    fun compose(vararg samplers: FLineSampler): FLineSampler {
        if (samplers.isEmpty()) return uniform()
        var acc = samplers[0]
        for (i in 1 until samplers.size) acc = acc.then(samplers[i])
        return acc
    }
}

/**
 * Compose two samplers in sequence.
 */
infix fun FLineSampler.then(next: FLineSampler): FLineSampler = FLineSampler { line, context ->
    val a = this.sample(line, context)
    if (a.isEmpty()) return@FLineSampler a
    val temp = samplesToPolyline(a)
    next.sample(temp, context)
}

/**
 * Sample an [FLine] into [FLineSample] values.
 */
fun FLine.sampleSeries(
    samples: Int,
    sampler: FLineSampler? = null,
    seed: Long? = null,
    pointSetAware: Boolean = true
): List<FLineSample> {
    val count = samples.coerceAtLeast(1)
    val context = FLineSampleContext(requestedCount = count, seed = seed, pointSetAware = pointSetAware)
    val chosen = sampler ?: FLineSamplers.uniform()
    val out = chosen.sample(this, context)
    if (out.isNotEmpty() || this.nodes.isEmpty()) return out
    return FLineSamplers.uniform().sample(this, context)
}

/**
 * Sample an [FLine] into plain points.
 */
fun FLine.samplePoints(
    samples: Int,
    sampler: FLineSampler? = null,
    seed: Long? = null,
    pointSetAware: Boolean = true
): List<Vec3> {
    return sampleSeries(samples, sampler, seed, pointSetAware).map { Vec3(it.point) }
}

private fun uniformParams(count: Int): FloatArray {
    val c = count.coerceAtLeast(1)
    if (c == 1) return floatArrayOf(0f)
    return FloatArray(c) { i -> i.toFloat() / (c - 1).toFloat() }
}

private fun sampleAtParams(line: FLine, params: FloatArray, pointSetAware: Boolean): List<FLineSample> {
    if (params.isEmpty()) return emptyList()
    if (line.nodes.isEmpty()) return emptyList()
    if (pointSetAware && line.isPointSetLike()) {
        val points = line.pointLikeNodes()
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return params.map { FLineSample(it.coerceIn(0f, 1f), Vec3(points[0])) }
        return params.map { raw ->
            val t = raw.coerceIn(0f, 1f)
            val idx = (t * (points.size - 1).toFloat()).roundToInt().coerceIn(0, points.size - 1)
            FLineSample(t, Vec3(points[idx]))
        }
    }

    val cursor = line.cursor(0.01f)
    val maxT = (line.nodes.size - 1).toFloat().coerceAtLeast(0f)
    return params.map { raw ->
        val t = raw.coerceIn(0f, 1f)
        cursor.setT(t * maxT)
        FLineSample(t, Vec3(cursor.position()))
    }
}

private fun randomPointSetSamples(line: FLine, count: Int, seed: Long?): List<FLineSample> {
    val points = line.pointLikeNodes()
    if (points.isEmpty()) return emptyList()
    if (count <= 1) return listOf(FLineSample(0f, Vec3(points.first())))
    val rng = if (seed != null) Random(seed) else Random.Default
    val out = ArrayList<FLineSample>(count)
    for (i in 0 until count) {
        val idx = rng.nextInt(points.size)
        out += FLineSample(i.toFloat() / (count - 1).toFloat(), Vec3(points[idx]))
    }
    return out
}

private fun downsampleSamples(input: List<FLineSample>, targetCount: Int): List<FLineSample> {
    if (input.isEmpty()) return input
    val count = targetCount.coerceAtLeast(1)
    if (count >= input.size) return input.map { FLineSample(it.t, Vec3(it.point)) }
    if (count == 1) return listOf(FLineSample(input.first().t, Vec3(input.first().point)))
    val out = ArrayList<FLineSample>(count)
    for (i in 0 until count) {
        val u = i.toFloat() / (count - 1).toFloat()
        val idx = (u * (input.size - 1).toFloat()).roundToInt().coerceIn(0, input.size - 1)
        val s = input[idx]
        out += FLineSample(s.t, Vec3(s.point))
    }
    return out
}

private fun samplesToPolyline(samples: List<FLineSample>): FLine {
    val out = FLine()
    if (samples.isEmpty()) return out
    out.moveTo(samples.first().point)
    for (i in 1 until samples.size) out.lineTo(samples[i].point)
    return out
}

private fun FLine.isPointSetLike(): Boolean {
    if (nodes.isEmpty()) return false
    return nodes.none { it is FLine.LineTo || it is FLine.CubicTo }
}

private fun FLine.pointLikeNodes(): List<Vec3> {
    return nodes
        .filter { it.storage.isNotEmpty() }
        .map { Vec3(it.to) }
}

private fun rdpSimplifySamples(samples: List<FLineSample>, epsilon: Float): List<FLineSample> {
    if (samples.size < 3) return samples.map { FLineSample(it.t, Vec3(it.point)) }
    var maxDist = 0f
    var maxIndex = 0
    for (i in 1 until samples.size - 1) {
        val d = perpendicularDistance(samples[i].point, samples.first().point, samples.last().point)
        if (d > maxDist) {
            maxDist = d
            maxIndex = i
        }
    }
    return if (maxDist > epsilon) {
        val left = rdpSimplifySamples(samples.subList(0, maxIndex + 1), epsilon)
        val right = rdpSimplifySamples(samples.subList(maxIndex, samples.size), epsilon)
        left.dropLast(1) + right
    } else {
        listOf(
            FLineSample(samples.first().t, Vec3(samples.first().point)),
            FLineSample(samples.last().t, Vec3(samples.last().point))
        )
    }
}

private fun perpendicularDistance(point: Vec3, lineStart: Vec3, lineEnd: Vec3): Float {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val dz = lineEnd.z - lineStart.z
    val lenSq = dx * dx + dy * dy + dz * dz
    if (lenSq <= 1e-12f) return point.distance(lineStart)
    val t = ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy + (point.z - lineStart.z) * dz) / lenSq
    val tc = t.coerceIn(0f, 1f)
    val proj = vec(
        lineStart.x + tc * dx,
        lineStart.y + tc * dy,
        lineStart.z + tc * dz
    )
    return point.distance(proj)
}

private fun Float.roundToInt(): Int = floor(this + 0.5f).toInt()
