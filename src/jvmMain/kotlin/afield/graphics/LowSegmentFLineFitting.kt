package afield.graphics

import afield.stdlib.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Controls the conservative low-segment fitter used by [fitLowSegmentFLine].
 *
 * Error is measured geometrically against each primitive, not by assuming that
 * input point indices are uniformly spaced along that primitive.
 */
data class LowSegmentFLineFitConfig(
    val maxSegments: Int = 3,
    val minSplitRelativeImprovement: Double = 0.30,
    val minSplitAbsoluteImprovement: Double = 0.02,
    val complexityPenalty: Double = 0.015,
    val minArcRelativeImprovement: Double = 0.15,
    val minArcAbsoluteImprovement: Double = 0.005,
    val maxArcSweep: Double = PI * 1.75,
    val maxArcSweepPerCubic: Double = PI / 2.0,
    val maxArcRadiusToChordRatio: Double = 1_000.0,
    val arcCandidateCount: Int = 17
)

data class LowSegmentFLineFitResult(
    val line: FLine,
    val rmsError: Double,
    val maxError: Double,
    val segmentCount: Int,
    val primitives: List<LowSegmentFLineFitPrimitive>
)

sealed class LowSegmentFLineFitPrimitive {
    abstract val startIndex: Int
    abstract val endIndex: Int
    abstract val start: Vec3
    abstract val end: Vec3
    abstract val rmsError: Double
    abstract val maxError: Double

    data class Line(
        override val startIndex: Int,
        override val endIndex: Int,
        override val start: Vec3,
        override val end: Vec3,
        override val rmsError: Double,
        override val maxError: Double
    ) : LowSegmentFLineFitPrimitive()

    data class Arc(
        override val startIndex: Int,
        override val endIndex: Int,
        override val start: Vec3,
        override val end: Vec3,
        override val rmsError: Double,
        override val maxError: Double,
        val center: Vec3,
        val radius: Double,
        val sweep: Double
    ) : LowSegmentFLineFitPrimitive()
}

fun lowSegmentFLine(
    points: Iterable<Vec3>,
    config: LowSegmentFLineFitConfig = LowSegmentFLineFitConfig()
): FLine = points.fitLowSegmentFLine(config)

fun Iterable<Vec3>.fitLowSegmentFLine(
    config: LowSegmentFLineFitConfig = LowSegmentFLineFitConfig()
): FLine = fitLowSegmentFLineResult(config).line

fun Iterable<Vec3>.fitLowSegmentFLineResult(
    config: LowSegmentFLineFitConfig = LowSegmentFLineFitConfig()
): LowSegmentFLineFitResult {
    val source = dedupeConsecutive(map { Vec3(it) })
    require(source.all(::isFiniteVec3LowFit)) { "fitLowSegmentFLine: all points must be finite" }

    if (source.isEmpty()) {
        return LowSegmentFLineFitResult(FLine(), 0.0, 0.0, 0, emptyList())
    }
    if (source.size == 1) {
        val line = FLine().moveTo(source.first())
        return LowSegmentFLineFitResult(line, 0.0, 0.0, 0, emptyList())
    }

    val scale = pointScale(source)
    val tuned = config.copy(
        maxSegments = config.maxSegments.coerceAtLeast(1),
        maxArcSweep = config.maxArcSweep.coerceIn(1e-6, PI * 2.0 - 1e-6),
        maxArcSweepPerCubic = config.maxArcSweepPerCubic.coerceIn(1e-6, PI),
        arcCandidateCount = config.arcCandidateCount.coerceAtLeast(3)
    )
    val maxSegments = min(tuned.maxSegments, source.size - 1)
    val primitiveCache = Array(source.size) { arrayOfNulls<PrimitiveFit>(source.size) }
    for (start in 0 until source.lastIndex) {
        for (end in start + 1 until source.size) {
            primitiveCache[start][end] = fitPrimitive(source, start, end, tuned, scale)
        }
    }

    val models = fitSegmentModels(source.size, primitiveCache, maxSegments)
    val chosen = chooseConservativeModel(models, tuned, scale)
        ?: models.first()
    val line = chosen.toFLine(tuned)
    return LowSegmentFLineFitResult(
        line = line,
        rmsError = chosen.rms,
        maxError = chosen.maxError,
        segmentCount = chosen.primitives.size,
        primitives = chosen.primitives.map { it.toPublicPrimitive() }
    )
}

private enum class PrimitiveKind { Line, Arc }

private data class PrimitiveFit(
    val kind: PrimitiveKind,
    val startIndex: Int,
    val endIndex: Int,
    val start: Vec3,
    val end: Vec3,
    val sumSq: Double,
    val count: Int,
    val maxError: Double,
    val arc: ArcGeometry? = null
) {
    val rms: Double get() = if (count <= 0) 0.0 else sqrt(sumSq / count)
}

private data class ArcGeometry(
    val origin: Vec3,
    val xAxis: Vec3,
    val yAxis: Vec3,
    val centerX: Double,
    val centerY: Double,
    val radius: Double,
    val startAngle: Double,
    val sweep: Double
) {
    fun localPoint(angle: Double): Vec3 {
        val x = centerX + radius * cos(angle)
        val y = centerY + radius * sin(angle)
        return Vec3(origin)
            .add(Vec3(xAxis).mul(x.toFloat()))
            .add(Vec3(yAxis).mul(y.toFloat()))
    }

    fun centerPoint(): Vec3 = Vec3(origin)
        .add(Vec3(xAxis).mul(centerX.toFloat()))
        .add(Vec3(yAxis).mul(centerY.toFloat()))
}

private data class SegmentModel(
    val primitives: List<PrimitiveFit>,
    val sumSq: Double,
    val count: Int,
    val maxError: Double
) {
    val rms: Double get() = if (count <= 0) 0.0 else sqrt(sumSq / count)

    fun plus(next: PrimitiveFit): SegmentModel = SegmentModel(
        primitives = primitives + next,
        sumSq = sumSq + next.sumSq,
        count = count + next.count - 1,
        maxError = max(maxError, next.maxError)
    )
}

private fun fitSegmentModels(
    pointCount: Int,
    primitiveCache: Array<Array<PrimitiveFit?>>,
    maxSegments: Int
): List<SegmentModel> {
    val n = pointCount
    val dp = Array(maxSegments + 1) { arrayOfNulls<SegmentModel>(n) }
    for (end in 1 until n) {
        val primitive = primitiveCache[0][end] ?: continue
        dp[1][end] = SegmentModel(listOf(primitive), primitive.sumSq, primitive.count, primitive.maxError)
    }
    for (segments in 2..maxSegments) {
        for (end in segments until n) {
            var best: SegmentModel? = null
            for (split in segments - 1 until end) {
                val previous = dp[segments - 1][split] ?: continue
                val primitive = primitiveCache[split][end] ?: continue
                val candidate = previous.plus(primitive)
                if (best == null || candidate.rms < best.rms) best = candidate
            }
            dp[segments][end] = best
        }
    }
    return (1..maxSegments).mapNotNull { dp[it][n - 1] }
}

private fun chooseConservativeModel(
    models: List<SegmentModel>,
    config: LowSegmentFLineFitConfig,
    scale: Double
): SegmentModel? {
    if (models.isEmpty()) return null
    var accepted = models.first()
    for (candidate in models.drop(1)) {
        val improvement = accepted.rms - candidate.rms
        val relative = improvement / max(accepted.rms, scale * 1e-9)
        val absoluteOk = improvement >= config.minSplitAbsoluteImprovement.coerceAtLeast(0.0) * scale
        val relativeOk = relative >= config.minSplitRelativeImprovement.coerceAtLeast(0.0)
        val acceptedScore = accepted.rms + config.complexityPenalty.coerceAtLeast(0.0) * scale * (accepted.primitives.size - 1)
        val candidateScore = candidate.rms + config.complexityPenalty.coerceAtLeast(0.0) * scale * (candidate.primitives.size - 1)
        if (absoluteOk && relativeOk && candidateScore < acceptedScore) {
            accepted = candidate
        }
    }
    return accepted
}

private fun fitPrimitive(
    points: List<Vec3>,
    start: Int,
    end: Int,
    config: LowSegmentFLineFitConfig,
    scale: Double
): PrimitiveFit {
    val line = fitLinePrimitive(points, start, end)
    val arc = fitArcPrimitive(points, start, end, config, scale) ?: return line

    val improvement = line.rms - arc.rms
    val relative = improvement / max(line.rms, scale * 1e-9)
    val absoluteOk = improvement >= config.minArcAbsoluteImprovement.coerceAtLeast(0.0) * scale
    val relativeOk = relative >= config.minArcRelativeImprovement.coerceAtLeast(0.0)
    return if (absoluteOk && relativeOk && arc.maxError <= line.maxError) arc else line
}

private fun fitLinePrimitive(points: List<Vec3>, start: Int, end: Int): PrimitiveFit {
    val a = points[start]
    val b = points[end]
    var sumSq = 0.0
    var maxError = 0.0
    for (i in start..end) {
        val d = pointToSegmentDistance(points[i], a, b)
        sumSq += d * d
        maxError = max(maxError, d)
    }
    return PrimitiveFit(
        kind = PrimitiveKind.Line,
        startIndex = start,
        endIndex = end,
        start = Vec3(a),
        end = Vec3(b),
        sumSq = sumSq,
        count = end - start + 1,
        maxError = maxError
    )
}

private fun fitArcPrimitive(
    points: List<Vec3>,
    start: Int,
    end: Int,
    config: LowSegmentFLineFitConfig,
    scale: Double
): PrimitiveFit? {
    if (end - start < 2) return null
    val a = points[start]
    val b = points[end]
    val chord = Vec3(b).sub(a)
    val chordLength = chord.length().toDouble()
    if (chordLength <= scale * 1e-7) return null
    val xAxis = Vec3(chord).normalize()

    var bestPerp = Vec3()
    var bestPerpLength = 0.0
    for (i in start + 1 until end) {
        val ap = Vec3(points[i]).sub(a)
        val along = xAxis.dot(ap)
        val perp = ap.sub(Vec3(xAxis).mul(along))
        val length = perp.length().toDouble()
        if (length > bestPerpLength) {
            bestPerpLength = length
            bestPerp = perp
        }
    }
    if (bestPerpLength <= scale * 1e-5) return null

    val yAxis = bestPerp.normalize()
    val normal = Vec3(xAxis).cross(yAxis).normalize()
    if (normal.lengthSquared() <= 1e-12f) return null

    val projected = ArrayList<ProjectedPoint>(end - start + 1)
    for (i in start..end) {
        val ap = Vec3(points[i]).sub(a)
        projected += ProjectedPoint(
            x = xAxis.dot(ap).toDouble(),
            y = yAxis.dot(ap).toDouble(),
            z = normal.dot(ap).toDouble()
        )
    }

    val hCandidates = arcHeightCandidates(projected, chordLength, config)
    var best: PrimitiveFit? = null
    for (h in hCandidates) {
        val radius = sqrt(chordLength * chordLength * 0.25 + h * h)
        if (!radius.isFinite() || radius <= scale * 1e-7) continue
        val centerX = chordLength * 0.5
        val startAngle = atan2(-h, -centerX)
        val endAngle = atan2(-h, centerX)
        val positiveSweep = positiveAngle(endAngle - startAngle)
        val negativeSweep = -positiveAngle(startAngle - endAngle)
        val sweeps = doubleArrayOf(positiveSweep, negativeSweep)
        for (sweep in sweeps) {
            if (abs(sweep) > config.maxArcSweep) continue
            val scored = scoreArc(projected, centerX, h, radius, startAngle, sweep)
            val arc = ArcGeometry(
                origin = Vec3(a),
                xAxis = Vec3(xAxis),
                yAxis = Vec3(yAxis),
                centerX = centerX,
                centerY = h,
                radius = radius,
                startAngle = startAngle,
                sweep = sweep
            )
            val fit = PrimitiveFit(
                kind = PrimitiveKind.Arc,
                startIndex = start,
                endIndex = end,
                start = Vec3(a),
                end = Vec3(b),
                sumSq = scored.first,
                count = projected.size,
                maxError = scored.second,
                arc = arc
            )
            if (best == null || fit.rms < best.rms) best = fit
        }
    }
    return best
}

private data class ProjectedPoint(val x: Double, val y: Double, val z: Double)

private fun arcHeightCandidates(
    projected: List<ProjectedPoint>,
    chordLength: Double,
    config: LowSegmentFLineFitConfig
): List<Double> {
    val raw = ArrayList<Double>()
    val maxAbsH = chordLength * config.maxArcRadiusToChordRatio.coerceAtLeast(1.0)
    for (i in 1 until projected.lastIndex) {
        val p = projected[i]
        if (abs(p.y) <= chordLength * 1e-8) continue
        val h = (p.x * p.x - chordLength * p.x + p.y * p.y) / (2.0 * p.y)
        if (h.isFinite() && abs(h) <= maxAbsH) raw += h
    }
    if (raw.isEmpty()) return emptyList()
    raw.sort()
    if (raw.size <= config.arcCandidateCount) return raw.distinctBy { quantize(it, chordLength) }

    val out = ArrayList<Double>(config.arcCandidateCount)
    for (i in 0 until config.arcCandidateCount) {
        val t = if (config.arcCandidateCount == 1) 0.0 else i.toDouble() / (config.arcCandidateCount - 1).toDouble()
        val idx = (t * (raw.size - 1)).toInt().coerceIn(0, raw.lastIndex)
        out += raw[idx]
    }
    out += raw[raw.size / 2]
    return out.distinctBy { quantize(it, chordLength) }
}

private fun scoreArc(
    points: List<ProjectedPoint>,
    centerX: Double,
    centerY: Double,
    radius: Double,
    startAngle: Double,
    sweep: Double
): Pair<Double, Double> {
    var sumSq = 0.0
    var maxError = 0.0
    for (p in points) {
        val dx = p.x - centerX
        val dy = p.y - centerY
        val angle = atan2(dy, dx)
        val inArc = angleInSweep(startAngle, sweep, angle)
        val planarDistance = if (inArc) {
            abs(sqrt(dx * dx + dy * dy) - radius)
        } else {
            val dStart = sqrt(p.x * p.x + p.y * p.y)
            val dEnd = sqrt((p.x - points.last().x) * (p.x - points.last().x) + p.y * p.y)
            min(dStart, dEnd)
        }
        val d = sqrt(planarDistance * planarDistance + p.z * p.z)
        sumSq += d * d
        maxError = max(maxError, d)
    }
    return sumSq to maxError
}

private fun SegmentModel.toFLine(config: LowSegmentFLineFitConfig): FLine {
    val out = FLine()
    if (primitives.isEmpty()) return out
    out.moveTo(primitives.first().start)
    for (primitive in primitives) {
        when (primitive.kind) {
            PrimitiveKind.Line -> out.lineTo(primitive.end)
            PrimitiveKind.Arc -> appendArc(out, primitive, config)
        }
    }
    return out
}

private fun appendArc(out: FLine, primitive: PrimitiveFit, config: LowSegmentFLineFitConfig) {
    val arc = primitive.arc ?: run {
        out.lineTo(primitive.end)
        return
    }
    val chunks = ceil(abs(arc.sweep) / config.maxArcSweepPerCubic).toInt().coerceAtLeast(1)
    for (i in 0 until chunks) {
        val a0 = arc.startAngle + arc.sweep * i.toDouble() / chunks.toDouble()
        val a1 = arc.startAngle + arc.sweep * (i + 1).toDouble() / chunks.toDouble()
        val delta = a1 - a0
        val k = 4.0 / 3.0 * tan(delta / 4.0)
        val p0 = arc.localPoint(a0)
        val p3 = if (i == chunks - 1) primitive.end else arc.localPoint(a1)
        val t0 = localTangent(arc, a0)
        val t1 = localTangent(arc, a1)
        val c1 = Vec3(p0).add(t0.mul((k * arc.radius).toFloat()))
        val c2 = Vec3(p3).sub(t1.mul((k * arc.radius).toFloat()))
        out.cubicTo(c1, c2, p3)
    }
}

private fun localTangent(arc: ArcGeometry, angle: Double): Vec3 {
    return Vec3(arc.xAxis).mul((-sin(angle)).toFloat())
        .add(Vec3(arc.yAxis).mul(cos(angle).toFloat()))
}

private fun PrimitiveFit.toPublicPrimitive(): LowSegmentFLineFitPrimitive {
    return when (kind) {
        PrimitiveKind.Line -> LowSegmentFLineFitPrimitive.Line(
            startIndex = startIndex,
            endIndex = endIndex,
            start = Vec3(start),
            end = Vec3(end),
            rmsError = rms,
            maxError = maxError
        )
        PrimitiveKind.Arc -> LowSegmentFLineFitPrimitive.Arc(
            startIndex = startIndex,
            endIndex = endIndex,
            start = Vec3(start),
            end = Vec3(end),
            rmsError = rms,
            maxError = maxError,
            center = arc?.centerPoint() ?: Vec3(start),
            radius = arc?.radius ?: 0.0,
            sweep = arc?.sweep ?: 0.0
        )
    }
}

private fun pointToSegmentDistance(point: Vec3, start: Vec3, end: Vec3): Double {
    val dx = (end.x - start.x).toDouble()
    val dy = (end.y - start.y).toDouble()
    val dz = (end.z - start.z).toDouble()
    val lenSq = dx * dx + dy * dy + dz * dz
    if (lenSq <= 1e-18) return distance(point, start)
    val ax = (point.x - start.x).toDouble()
    val ay = (point.y - start.y).toDouble()
    val az = (point.z - start.z).toDouble()
    val t = ((ax * dx + ay * dy + az * dz) / lenSq)
        .coerceIn(0.0, 1.0)
    val px = start.x.toDouble() + t * dx
    val py = start.y.toDouble() + t * dy
    val pz = start.z.toDouble() + t * dz
    val ex = point.x.toDouble() - px
    val ey = point.y.toDouble() - py
    val ez = point.z.toDouble() - pz
    return sqrt(ex * ex + ey * ey + ez * ez)
}

private fun distance(a: Vec3, b: Vec3): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    val dz = (a.z - b.z).toDouble()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun pointScale(points: List<Vec3>): Double {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY
    for (p in points) {
        minX = min(minX, p.x.toDouble())
        minY = min(minY, p.y.toDouble())
        minZ = min(minZ, p.z.toDouble())
        maxX = max(maxX, p.x.toDouble())
        maxY = max(maxY, p.y.toDouble())
        maxZ = max(maxZ, p.z.toDouble())
    }
    val dx = maxX - minX
    val dy = maxY - minY
    val dz = maxZ - minZ
    return sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1.0)
}

private fun positiveAngle(angle: Double): Double {
    var a = angle % (PI * 2.0)
    if (a < 0.0) a += PI * 2.0
    return a
}

private fun angleInSweep(start: Double, sweep: Double, angle: Double): Boolean {
    val tolerance = 1e-8
    return if (sweep >= 0.0) {
        positiveAngle(angle - start) <= sweep + tolerance
    } else {
        positiveAngle(start - angle) <= -sweep + tolerance
    }
}

private fun quantize(value: Double, scale: Double): Long {
    val s = scale.coerceAtLeast(1e-9)
    return (value / (s * 1e-9)).toLong()
}

private fun dedupeConsecutive(points: List<Vec3>): List<Vec3> {
    if (points.size < 2) return points
    val out = ArrayList<Vec3>(points.size)
    for (point in points) {
        if (out.isEmpty() || distance(out.last(), point) > 1e-7) out += point
    }
    return out
}

private fun isFiniteVec3LowFit(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
