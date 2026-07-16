package afield.graphics

import afield.stdlib.*
import afield.stdlib.linalg.Quaternionf
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Uniform scale normalization used during interpolation setup.
 */
enum class FLineInterpolationScaleMode {
    NONE,
    RMS_RADIUS,
    BOUNDS_DIAGONAL
}

/**
 * Pre-alignment rotation used during interpolation setup.
 */
enum class FLineInterpolationRotationMode {
    NONE,
    FIT_LINE_2D,
    FIT_LINE_3D
}

/**
 * Evaluation-time envelope for spin-style motion modifiers.
 */
enum class FLineInterpolationSpinMode {
    /**
     * Ramp from zero at the start to full value at the end.
     */
    LINEAR,

    /**
     * Reach full value at t = 0.5, then return to zero so the final keyframe is hit exactly.
     */
    MIDPOINT_RETURN;

    fun envelope(t: Float): Float {
        val tt = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> tt
            MIDPOINT_RETURN -> (1f - abs(2f * tt - 1f)).coerceIn(0f, 1f)
        }
    }
}

/**
 * Geometric normalization before matching.
 */
data class FLineInterpolationAlignment(
    val translateToCenter: Boolean = true,
    val scaleMode: FLineInterpolationScaleMode = FLineInterpolationScaleMode.RMS_RADIUS,
    val rotationMode: FLineInterpolationRotationMode = FLineInterpolationRotationMode.NONE
)

/**
 * Setup-time matching and resampling controls.
 */
data class FLineInterpolationMatching(
    val denseSampleCount: Int = 256,
    val extraAnchorSamplesPerLine: Int = 0,
    val minimumBasisCount: Int = 64,
    val maximumBasisCount: Int = 1024,
    val basisDensityMultiplier: Int = 4,
    val unmatchedCost: Double = 4.0,
    val assignmentOrderPenalty: Double = 0.15,
    val projectionOrderPenalty: Double = 0.25,
    val assignmentConstraintWeight: Float = 2f,
    val projectionConstraintWeight: Float = 1f,
    val boundaryConstraintWeight: Float = 8f,
    val allowReverseOrder: Boolean = true,
    val allowClosedOrderRotation: Boolean = true,
    val closedOrderRotationSteps: Int? = null
)

/**
 * Fast evaluation-time motion controls applied after the cached basis blend.
 */
data class FLineInterpolationMotion(
    val spatialSpinAngle: Float = 0f,
    val spatialSpinAxis: Vec3 = vec(0f, 0f, 1f),
    val spatialSpinMode: FLineInterpolationSpinMode = FLineInterpolationSpinMode.LINEAR,
    val orderSpin: Float = 0f,
    val orderSpinMode: FLineInterpolationSpinMode = FLineInterpolationSpinMode.LINEAR,
    val wrapOrderSpin: Boolean = false
)

/**
 * Configuration for [FLineInterpolator].
 */
data class FLineInterpolationConfig(
    val alignment: FLineInterpolationAlignment = FLineInterpolationAlignment(),
    val matching: FLineInterpolationMatching = FLineInterpolationMatching(),
    val motion: FLineInterpolationMotion = FLineInterpolationMotion(),
    val samplerA: FLineSampler = FLineSamplers.curvatureAdaptive(gain = 3f, oversample = 4, minBaseSamples = 48),
    val samplerB: FLineSampler = samplerA,
    val closed: Boolean? = null
)

/**
 * Reparameterization applied to the second line while solving the correspondence.
 *
 * `shift` is only meaningful for closed lines and moves the seam in normalized contour space.
 */
data class FLineInterpolationOrderTransform(
    val reverse: Boolean = false,
    val shift: Float = 0f
) {
    fun forward(rawU: Float, closed: Boolean): Float {
        val oriented = if (reverse) 1f - rawU else rawU
        return if (closed) wrap01(oriented - shift) else oriented.coerceIn(0f, 1f)
    }

    fun inverse(orderedU: Float, closed: Boolean): Float {
        val shifted = if (closed) wrap01(orderedU + shift) else orderedU.coerceIn(0f, 1f)
        return if (reverse) 1f - shifted else shifted
    }
}

/**
 * Introspection for the chosen interpolation setup.
 */
data class FLineInterpolationDiagnostics(
    val orderTransform: FLineInterpolationOrderTransform,
    val assignmentCost: Double,
    val warpKnots: List<Vec2>,
    val basisCount: Int,
    val denseSampleCountA: Int,
    val denseSampleCountB: Int,
    val outputClosed: Boolean
)

/**
 * Slow-to-configure, fast-to-evaluate single-contour FLine morphing primitive.
 *
 * The setup pipeline is:
 * 1. Resample each source line into a dense single-contour polyline.
 * 2. Normalize the two lines for matching (translate / scale / optional rotation).
 * 3. Search over reversal + closed-contour seam shifts using a Hungarian assignment on anchors.
 * 4. Build a monotone parameter warp from matched nodes plus mutual node-to-segment projections.
 * 5. Cache a common basis of paired points for cheap repeated evaluation.
 *
 * This currently targets one contour only (`MoveTo` once at most). Multi-contour interpolation can
 * be built later by composing multiple instances of this primitive.
 */
class FLineInterpolator(
    val from: FLine,
    val to: FLine,
    val config: FLineInterpolationConfig = FLineInterpolationConfig()
) {

    private val basisA: Array<Vec3>
    private val basisB: Array<Vec3>
    private val outputClosed: Boolean
    val diagnostics: FLineInterpolationDiagnostics

    init {
        from.requireSingleContour("from")
        to.requireSingleContour("to")

        val setup = FLineInterpolationSetup.build(from, to, config)
        basisA = setup.basisA
        basisB = setup.basisB
        outputClosed = setup.outputClosed
        diagnostics = setup.diagnostics
    }

    /**
     * Number of cached basis points used by this interpolator.
     */
    val basisCount: Int
        get() = basisA.size

    /**
     * Return the cached source basis lines used for evaluation.
     */
    fun sourceBasis(): Pair<FLine, FLine> {
        return polylineFrom(basisA.map { Vec3(it) }, closed = outputClosed) to
            polylineFrom(basisB.map { Vec3(it) }, closed = outputClosed)
    }

    /**
     * Generate the interpolated point sequence at [t].
     */
    fun pointsAt(t: Float, motion: FLineInterpolationMotion = config.motion): List<Vec3> {
        if (basisA.isEmpty() || basisB.isEmpty()) return emptyList()
        val tt = t.coerceIn(0f, 1f)
        val points = ArrayList<Vec3>(basisA.size)
        for (i in basisA.indices) {
            points += Vec3(basisA[i]).lerp(basisB[i], tt)
        }

        val orderSpinEnvelope = motion.orderSpinMode.envelope(tt)
        val wrapOrder = outputClosed || motion.wrapOrderSpin
        if (wrapOrder && abs(motion.orderSpin) > EPSILON && abs(orderSpinEnvelope) > EPSILON && points.size > 1) {
            val shift = motion.orderSpin * orderSpinEnvelope * points.size.toFloat()
            rotatePointOrderInPlace(points, shift)
        }

        val spatialSpinEnvelope = motion.spatialSpinMode.envelope(tt)
        if (abs(motion.spatialSpinAngle) > EPSILON && abs(spatialSpinEnvelope) > EPSILON && points.isNotEmpty()) {
            val axis = normalizedAxis(motion.spatialSpinAxis)
            val center = points.center()
            val rotation = Quaternionf().setAngleAxis(
                motion.spatialSpinAngle * spatialSpinEnvelope,
                axis.x,
                axis.y,
                axis.z
            )
            for (i in points.indices) {
                points[i].sub(center)
                rotation.transform(points[i])
                points[i].add(center)
            }
        }

        return points
    }

    /**
     * Write the interpolated geometry into [target], clearing it first.
     */
    fun writeInto(target: FLine, t: Float, motion: FLineInterpolationMotion = config.motion): FLine {
        target.nodes.clear()
        target.properties.clear()
        target.rev++

        val points = pointsAt(t, motion)
        if (points.isEmpty()) return target

        target.properties.putAll(from.properties)
        target.properties["interpolationT"] = t.coerceIn(0f, 1f)

        target.moveTo(points.first())
        for (i in 1 until points.size) {
            target.lineTo(points[i])
        }
        if (outputClosed && points.size > 2) {
            target.close()
        }
        return target
    }

    /**
     * Generate a new [FLine] at [t].
     */
    fun lineAt(t: Float, motion: FLineInterpolationMotion = config.motion): FLine {
        return writeInto(FLine(), t, motion)
    }
}

/**
 * Create a reusable interpolator to [other].
 */
fun FLine.interpolatorTo(
    other: FLine,
    config: FLineInterpolationConfig = FLineInterpolationConfig()
): FLineInterpolator = FLineInterpolator(this, other, config)

/**
 * One-shot convenience wrapper around [FLineInterpolator].
 *
 * If you are evaluating more than once, prefer [interpolatorTo] and reuse the returned object.
 */
fun FLine.byInterpolatingTo(
    other: FLine,
    t: Float,
    config: FLineInterpolationConfig = FLineInterpolationConfig(),
    motion: FLineInterpolationMotion = config.motion
): FLine = FLineInterpolator(this, other, config).lineAt(t, motion)

private object FLineInterpolationSetup {
    fun build(from: FLine, to: FLine, config: FLineInterpolationConfig): SetupResult {
        val fromClosed = from.isExplicitlyClosed()
        val toClosed = to.isExplicitlyClosed()
        val outputClosed = config.closed ?: (fromClosed && toClosed)

        val denseA = DensePolyline.fromLine(from, config.matching.denseSampleCount, config.samplerA, fromClosed)
        val denseB = DensePolyline.fromLine(to, config.matching.denseSampleCount, config.samplerB, toClosed)

        if (denseA.points.isEmpty() && denseB.points.isEmpty()) {
            return SetupResult(
                basisA = emptyArray(),
                basisB = emptyArray(),
                outputClosed = outputClosed,
                diagnostics = FLineInterpolationDiagnostics(
                    orderTransform = FLineInterpolationOrderTransform(),
                    assignmentCost = 0.0,
                    warpKnots = listOf(vec(0f, 0f), vec(1f, 1f)),
                    basisCount = 0,
                    denseSampleCountA = 0,
                    denseSampleCountB = 0,
                    outputClosed = outputClosed
                )
            )
        }

        if (denseA.points.isEmpty() || denseB.points.isEmpty()) {
            return buildDegenerate(denseA, denseB, outputClosed, config)
        }

        val anchorsA = extractAnchors(from, denseA, config.matching.extraAnchorSamplesPerLine)
        val anchorsB = extractAnchors(to, denseB, config.matching.extraAnchorSamplesPerLine)

        val normA = SimilarityNormalization.fromPoints(denseA.points, config.alignment)
        val normB = SimilarityNormalization.fromPoints(denseB.points, config.alignment)

        val alignedA = denseA.transformed(normA::apply)
        val alignedB = denseB.transformed(normB::apply)

        val alignedAnchorsA = anchorsA.map { it.withAligned(normA.apply(it.point)) }
        val alignedAnchorsB = anchorsB.map { it.withAligned(normB.apply(it.point)) }

        val candidate = chooseBestCandidate(
            anchorsA = alignedAnchorsA,
            anchorsB = alignedAnchorsB,
            targetClosed = toClosed,
            matching = config.matching
        )

        val orderedRawB = denseB.reparameterized(candidate.transform, denseB.points.size)
        val orderedAlignedB = alignedB.reparameterized(candidate.transform, alignedB.points.size)
        val orderedAnchorsB = alignedAnchorsB.map { it.ordered(candidate.transform, toClosed) }

        val constraints = buildConstraints(
            anchorsA = alignedAnchorsA,
            anchorsB = orderedAnchorsB,
            alignedA = alignedA,
            orderedAlignedB = orderedAlignedB,
            candidate = candidate,
            matching = config.matching
        )

        val warp = ParamWarp.fromConstraints(constraints)
        val basisCount = chooseBasisCount(denseA, denseB, constraints, config.matching, outputClosed)
        val grid = sampleGrid(basisCount, outputClosed)

        val basisA = Array(grid.size) { i -> denseA.sample(grid[i]) }
        val basisB = Array(grid.size) { i -> orderedRawB.sample(warp.map(grid[i])) }

        return SetupResult(
            basisA = basisA,
            basisB = basisB,
            outputClosed = outputClosed,
            diagnostics = FLineInterpolationDiagnostics(
                orderTransform = candidate.transform,
                assignmentCost = candidate.cost,
                warpKnots = warp.knots.map { Vec2(it) },
                basisCount = basisCount,
                denseSampleCountA = denseA.points.size,
                denseSampleCountB = denseB.points.size,
                outputClosed = outputClosed
            )
        )
    }

    private fun buildDegenerate(
        denseA: DensePolyline,
        denseB: DensePolyline,
        outputClosed: Boolean,
        config: FLineInterpolationConfig
    ): SetupResult {
        val nonEmpty = if (denseA.points.isNotEmpty()) denseA else denseB
        val count = if (nonEmpty.points.size <= 1) 1 else config.matching.minimumBasisCount.coerceAtLeast(2)
        val grid = sampleGrid(count, outputClosed && nonEmpty.closed)
        val actual = Array(grid.size) { i -> nonEmpty.sample(grid[i]) }
        val anchor = actual.firstOrNull()?.let { Vec3(it) } ?: Vec3()
        val collapsed = Array(actual.size) { Vec3(anchor) }

        val basisA = if (denseA.points.isEmpty()) collapsed else actual
        val basisB = if (denseB.points.isEmpty()) collapsed else actual

        return SetupResult(
            basisA = basisA,
            basisB = basisB,
            outputClosed = outputClosed && nonEmpty.closed,
            diagnostics = FLineInterpolationDiagnostics(
                orderTransform = FLineInterpolationOrderTransform(),
                assignmentCost = 0.0,
                warpKnots = listOf(vec(0f, 0f), vec(1f, 1f)),
                basisCount = count,
                denseSampleCountA = denseA.points.size,
                denseSampleCountB = denseB.points.size,
                outputClosed = outputClosed && nonEmpty.closed
            )
        )
    }
}

private data class SetupResult(
    val basisA: Array<Vec3>,
    val basisB: Array<Vec3>,
    val outputClosed: Boolean,
    val diagnostics: FLineInterpolationDiagnostics
)

private data class Anchor(
    val rawU: Float,
    val point: Vec3,
    val weight: Float,
    val aligned: Vec3 = Vec3()
) {
    fun withAligned(value: Vec3): Anchor = copy(aligned = Vec3(value))

    fun ordered(transform: FLineInterpolationOrderTransform, closed: Boolean): OrderedAnchor {
        return OrderedAnchor(
            rawU = rawU,
            orderedU = transform.forward(rawU, closed),
            point = Vec3(point),
            aligned = Vec3(aligned),
            weight = weight
        )
    }
}

private data class OrderedAnchor(
    val rawU: Float,
    val orderedU: Float,
    val point: Vec3,
    val aligned: Vec3,
    val weight: Float
)

private data class ParamConstraint(
    val ua: Float,
    val vb: Float,
    val weight: Float
)

private data class CandidateSelection(
    val transform: FLineInterpolationOrderTransform,
    val assignment: IntArray,
    val cost: Double
)

private data class PolylineProjection(
    val u: Float,
    val point: Vec3,
    val cost: Double
)

private class DensePolyline(
    points: List<Vec3>,
    val closed: Boolean
) {
    val points: List<Vec3> = canonicalizePoints(points, closed)
    private val segmentCount: Int = when {
        this.points.size <= 1 -> 0
        this.closed -> this.points.size
        else -> this.points.size - 1
    }
    private val cumulative: FloatArray = FloatArray(segmentCount + 1)
    private val totalLength: Float

    init {
        var acc = 0f
        cumulative[0] = 0f
        for (segment in 0 until segmentCount) {
            acc += segmentLength(segment)
            cumulative[segment + 1] = acc
        }
        totalLength = acc
    }

    fun transformed(by: (Vec3) -> Vec3): DensePolyline {
        return DensePolyline(points.map { by(it) }, closed)
    }

    fun reparameterized(
        transform: FLineInterpolationOrderTransform,
        sampleCount: Int = points.size
    ): DensePolyline {
        if (points.isEmpty()) return DensePolyline(emptyList(), closed)
        if (points.size == 1) return DensePolyline(listOf(Vec3(points.first())), false)

        val count = if (closed) sampleCount.coerceAtLeast(3) else sampleCount.coerceAtLeast(2)
        val out = ArrayList<Vec3>(count)
        if (closed) {
            for (i in 0 until count) {
                val orderedU = i.toFloat() / count.toFloat()
                out += sample(transform.inverse(orderedU, true))
            }
        } else {
            for (i in 0 until count) {
                val orderedU = if (count == 1) 0f else i.toFloat() / (count - 1).toFloat()
                out += sample(transform.inverse(orderedU, false))
            }
        }
        return DensePolyline(out, closed)
    }

    fun sample(u: Float): Vec3 {
        if (points.isEmpty()) return Vec3()
        if (points.size == 1 || segmentCount == 0) return Vec3(points.first())

        if (totalLength <= EPSILON) {
            return sampleByIndex(u)
        }

        val normalized = if (closed) wrap01(u) else u.coerceIn(0f, 1f)
        val target = normalized * totalLength
        if (!closed && target >= totalLength - EPSILON) {
            return Vec3(points.last())
        }

        var segment = 0
        while (segment < segmentCount - 1 && cumulative[segment + 1] < target) {
            segment++
        }

        val start = cumulative[segment]
        val end = cumulative[segment + 1]
        val alpha = if (end <= start + EPSILON) 0f else ((target - start) / (end - start)).coerceIn(0f, 1f)
        return pointOnSegment(segment, alpha)
    }

    fun project(
        point: Vec3,
        preferredU: Float? = null,
        orderPenalty: Double = 0.0
    ): PolylineProjection {
        if (points.isEmpty()) return PolylineProjection(0f, Vec3(), 0.0)
        if (points.size == 1 || segmentCount == 0) {
            val p = Vec3(points.first())
            return PolylineProjection(0f, p, p.distanceSquared(point).toDouble())
        }

        var best = PolylineProjection(0f, Vec3(points.first()), Double.POSITIVE_INFINITY)
        for (segment in 0 until segmentCount) {
            val a = points[segment]
            val b = points[nextIndex(segment)]
            val ab = Vec3(b).sub(a)
            val lenSq = ab.lengthSquared()
            val alpha = if (lenSq <= EPSILON * EPSILON) {
                0f
            } else {
                (((point.x - a.x) * ab.x) + ((point.y - a.y) * ab.y) + ((point.z - a.z) * ab.z)) / lenSq
            }.coerceIn(0f, 1f)

            val projected = Vec3(a).lerp(b, alpha)
            val spatial = projected.distanceSquared(point).toDouble()
            val u = parameterAt(segment, alpha)
            val order = if (preferredU == null || orderPenalty <= 0.0) {
                0.0
            } else {
                val delta = (u - preferredU).toDouble()
                delta * delta * orderPenalty
            }
            val cost = spatial + order
            if (cost < best.cost) {
                best = PolylineProjection(u = u, point = projected, cost = cost)
            }
        }
        return best
    }

    private fun sampleByIndex(u: Float): Vec3 {
        val normalized = if (closed) wrap01(u) else u.coerceIn(0f, 1f)
        if (closed) {
            val x = normalized * points.size.toFloat()
            val index = floor(x).toInt().floorMod(points.size)
            val alpha = x - floor(x)
            return pointOnSegment(index, alpha)
        }
        val x = normalized * (points.size - 1).toFloat()
        val index = floor(x).toInt().coerceIn(0, points.size - 1)
        val next = min(index + 1, points.size - 1)
        val alpha = (x - floor(x)).coerceIn(0f, 1f)
        return Vec3(points[index]).lerp(points[next], alpha)
    }

    private fun pointOnSegment(segment: Int, alpha: Float): Vec3 {
        val a = points[segment]
        val b = points[nextIndex(segment)]
        return Vec3(a).lerp(b, alpha)
    }

    private fun parameterAt(segment: Int, alpha: Float): Float {
        if (segmentCount == 0) return 0f
        if (totalLength <= EPSILON) {
            val denom = if (closed) segmentCount.toFloat() else max(1, segmentCount).toFloat()
            return ((segment.toFloat() + alpha) / denom).coerceIn(0f, 1f)
        }
        val d = cumulative[segment] + (cumulative[segment + 1] - cumulative[segment]) * alpha
        return (d / totalLength).coerceIn(0f, 1f)
    }

    private fun nextIndex(segment: Int): Int {
        return if (closed) (segment + 1).floorMod(points.size) else segment + 1
    }

    private fun segmentLength(segment: Int): Float {
        val a = points[segment]
        val b = points[nextIndex(segment)]
        return a.distance(b)
    }

    companion object {
        fun fromLine(line: FLine, requested: Int, sampler: FLineSampler, closed: Boolean): DensePolyline {
            val sampleCount = requested.coerceAtLeast(if (closed) 3 else 2)
            val raw = line.samplePoints(sampleCount, sampler = sampler)
            val cleaned = if (closed && raw.size > 1 && raw.first().distance(raw.last()) <= 1e-3f) {
                raw.dropLast(1)
            } else raw
            return DensePolyline(cleaned.map { Vec3(it) }, closed)
        }
    }
}

private data class SimilarityNormalization(
    val center: Vec3,
    val scale: Float,
    val rotation: Quat
) {
    fun apply(point: Vec3): Vec3 {
        val out = Vec3(point).sub(center)
        out.mul(scale)
        rotation.transform(out)
        return out
    }

    companion object {
        fun fromPoints(points: List<Vec3>, config: FLineInterpolationAlignment): SimilarityNormalization {
            val center = if (config.translateToCenter) points.center() else Vec3()
            val scale = when (config.scaleMode) {
                FLineInterpolationScaleMode.NONE -> 1f
                FLineInterpolationScaleMode.RMS_RADIUS -> {
                    val rms = rmsRadius(points, center)
                    if (rms <= EPSILON) 1f else 1f / rms
                }
                FLineInterpolationScaleMode.BOUNDS_DIAGONAL -> {
                    val diag = points.aabbBounds().size.length()
                    if (diag <= EPSILON) 1f else 1f / diag
                }
            }

            val rotation = when (config.rotationMode) {
                FLineInterpolationRotationMode.NONE -> Quaternionf()
                FLineInterpolationRotationMode.FIT_LINE_2D -> {
                    val dir = principalDirection2D(points)
                    Quaternionf().setAngleAxis(-atan2(dir.y, dir.x), 0f, 0f, 1f)
                }
                FLineInterpolationRotationMode.FIT_LINE_3D -> {
                    val dir = principalDirection3D(points)
                    Quaternionf().rotateTo(dir, vec(1f, 0f, 0f))
                }
            }

            return SimilarityNormalization(center = center, scale = scale, rotation = rotation)
        }

        private fun rmsRadius(points: List<Vec3>, center: Vec3): Float {
            if (points.isEmpty()) return 1f
            val mean = points.sumOf { Vec3(it).sub(center).lengthSquared().toDouble() } / points.size.toDouble()
            return sqrt(mean).toFloat()
        }
    }
}

private class ParamWarp private constructor(val knots: List<Vec2>) {
    fun map(u: Float): Float {
        if (knots.isEmpty()) return u.coerceIn(0f, 1f)
        if (knots.size == 1) return knots.first().y.coerceIn(0f, 1f)

        val x = u.coerceIn(0f, 1f)
        if (x <= knots.first().x) return knots.first().y.coerceIn(0f, 1f)
        for (i in 1 until knots.size) {
            val b = knots[i]
            if (x <= b.x) {
                val a = knots[i - 1]
                val dx = b.x - a.x
                if (dx <= EPSILON) return b.y.coerceIn(0f, 1f)
                val alpha = ((x - a.x) / dx).coerceIn(0f, 1f)
                return (a.y + (b.y - a.y) * alpha).coerceIn(0f, 1f)
            }
        }
        return knots.last().y.coerceIn(0f, 1f)
    }

    companion object {
        fun fromConstraints(constraints: List<ParamConstraint>): ParamWarp {
            if (constraints.isEmpty()) return ParamWarp(listOf(vec(0f, 0f), vec(1f, 1f)))

            val sorted = constraints
                .map {
                    ParamConstraint(
                        ua = it.ua.coerceIn(0f, 1f),
                        vb = it.vb.coerceIn(0f, 1f),
                        weight = it.weight.coerceAtLeast(EPSILON)
                    )
                }
                .sortedBy { it.ua }

            val epsilon = (1f / max(256, sorted.size * 8)).coerceAtLeast(1e-5f)
            val binned = binConstraints(sorted, epsilon)
            val values = FloatArray(binned.size) { i -> binned[i].vb }
            val weights = FloatArray(binned.size) { i -> binned[i].weight.coerceAtLeast(EPSILON) }
            val adjusted = isotonicNondecreasing(values, weights)

            val knots = ArrayList<Vec2>(binned.size + 2)
            for (i in binned.indices) {
                knots += vec(binned[i].ua, adjusted[i].coerceIn(0f, 1f))
            }

            if (knots.isEmpty() || knots[0].x > epsilon) {
                knots.add(0, vec(0f, 0f))
            } else {
                knots[0].set(0f, 0f)
            }

            if (knots[knots.lastIndex].x < 1f - epsilon) {
                knots += vec(1f, 1f)
            } else {
                knots[knots.lastIndex].set(1f, 1f)
            }

            for (i in 1 until knots.size) {
                if (knots[i].y < knots[i - 1].y) {
                    knots[i].y = knots[i - 1].y
                }
            }

            return ParamWarp(condenseKnots(knots, epsilon))
        }

        private fun binConstraints(sorted: List<ParamConstraint>, epsilon: Float): List<ParamConstraint> {
            if (sorted.isEmpty()) return emptyList()
            val out = ArrayList<ParamConstraint>()

            var uaWeight = 0.0
            var vbWeight = 0.0
            var weight = 0.0
            var currentU = sorted.first().ua

            fun flush() {
                if (weight <= 0.0) return
                out += ParamConstraint(
                    ua = (uaWeight / weight).toFloat(),
                    vb = (vbWeight / weight).toFloat(),
                    weight = weight.toFloat()
                )
            }

            for (constraint in sorted) {
                val w = constraint.weight.toDouble()
                if (weight > 0.0 && abs(constraint.ua - currentU) > epsilon) {
                    flush()
                    uaWeight = 0.0
                    vbWeight = 0.0
                    weight = 0.0
                }
                uaWeight += constraint.ua.toDouble() * w
                vbWeight += constraint.vb.toDouble() * w
                weight += w
                currentU = (uaWeight / weight).toFloat()
            }
            flush()

            return out
        }

        private fun condenseKnots(knots: List<Vec2>, epsilon: Float): List<Vec2> {
            if (knots.isEmpty()) return emptyList()
            val out = ArrayList<Vec2>(knots.size)
            for (knot in knots) {
                if (out.isNotEmpty() && abs(knot.x - out[out.lastIndex].x) <= epsilon) {
                    out[out.lastIndex].set(knot.x, knot.y)
                } else {
                    out += Vec2(knot)
                }
            }
            return out
        }

        private fun isotonicNondecreasing(values: FloatArray, weights: FloatArray): FloatArray {
            data class Block(var start: Int, var end: Int, var weight: Double, var mean: Double)

            val stack = ArrayDeque<Block>()
            for (i in values.indices) {
                var block = Block(
                    start = i,
                    end = i,
                    weight = weights[i].toDouble().coerceAtLeast(EPSILON.toDouble()),
                    mean = values[i].toDouble()
                )
                while (stack.isNotEmpty() && stack.last().mean > block.mean) {
                    val previous = stack.removeLast()
                    val total = previous.weight + block.weight
                    block = Block(
                        start = previous.start,
                        end = block.end,
                        weight = total,
                        mean = ((previous.mean * previous.weight) + (block.mean * block.weight)) / total
                    )
                }
                stack.addLast(block)
            }

            val out = FloatArray(values.size)
            for (block in stack) {
                for (i in block.start..block.end) {
                    out[i] = block.mean.toFloat()
                }
            }
            return out
        }
    }
}

private fun chooseBestCandidate(
    anchorsA: List<Anchor>,
    anchorsB: List<Anchor>,
    targetClosed: Boolean,
    matching: FLineInterpolationMatching
): CandidateSelection {
    if (anchorsA.isEmpty() && anchorsB.isEmpty()) {
        return CandidateSelection(FLineInterpolationOrderTransform(), IntArray(0), 0.0)
    }

    val reverseOptions = if (matching.allowReverseOrder) listOf(false, true) else listOf(false)
    val shiftOptions = if (targetClosed && matching.allowClosedOrderRotation) {
        buildShiftCandidates(anchorsB, matching.closedOrderRotationSteps)
    } else {
        listOf(0f)
    }

    var best: CandidateSelection? = null
    for (reverse in reverseOptions) {
        for (shift in shiftOptions) {
            val transform = FLineInterpolationOrderTransform(reverse = reverse, shift = if (targetClosed) shift else 0f)
            val orderedB = anchorsB.map { it.ordered(transform, targetClosed) }
            val cost = Array(anchorsA.size) { row ->
                DoubleArray(orderedB.size) { col ->
                    assignmentCost(anchorsA[row], orderedB[col], matching.assignmentOrderPenalty)
                }
            }

            val assignment = RectHungarianAssignment.solve(cost, matching.unmatchedCost)
            val total = assignmentTotalCost(cost, assignment, orderedB.size, matching.unmatchedCost)
            val candidate = CandidateSelection(transform, assignment, total)

            val currentBest = best
            if (currentBest == null || total < currentBest.cost - 1e-9) {
                best = candidate
            }
        }
    }

    return best ?: CandidateSelection(FLineInterpolationOrderTransform(), IntArray(anchorsA.size) { -1 }, Double.POSITIVE_INFINITY)
}

private fun buildConstraints(
    anchorsA: List<Anchor>,
    anchorsB: List<OrderedAnchor>,
    alignedA: DensePolyline,
    orderedAlignedB: DensePolyline,
    candidate: CandidateSelection,
    matching: FLineInterpolationMatching
): List<ParamConstraint> {
    val constraints = ArrayList<ParamConstraint>()
    constraints += ParamConstraint(0f, 0f, matching.boundaryConstraintWeight)
    constraints += ParamConstraint(1f, 1f, matching.boundaryConstraintWeight)

    for (row in anchorsA.indices) {
        val col = candidate.assignment.getOrNull(row) ?: -1
        if (col in anchorsB.indices) {
            val weight = matching.assignmentConstraintWeight * ((anchorsA[row].weight + anchorsB[col].weight) * 0.5f)
            constraints += ParamConstraint(anchorsA[row].rawU, anchorsB[col].orderedU, weight)
        }
    }

    for (anchor in anchorsA) {
        val projection = orderedAlignedB.project(
            point = anchor.aligned,
            preferredU = anchor.rawU,
            orderPenalty = matching.projectionOrderPenalty
        )
        constraints += ParamConstraint(anchor.rawU, projection.u, matching.projectionConstraintWeight * anchor.weight)
    }

    for (anchor in anchorsB) {
        val projection = alignedA.project(
            point = anchor.aligned,
            preferredU = anchor.orderedU,
            orderPenalty = matching.projectionOrderPenalty
        )
        constraints += ParamConstraint(projection.u, anchor.orderedU, matching.projectionConstraintWeight * anchor.weight)
    }

    return constraints
}

private fun chooseBasisCount(
    denseA: DensePolyline,
    denseB: DensePolyline,
    constraints: List<ParamConstraint>,
    matching: FLineInterpolationMatching,
    outputClosed: Boolean
): Int {
    val structural = max(denseA.points.size, denseB.points.size)
    if (structural <= 1) return 1

    val desired = max(
        matching.minimumBasisCount,
        max(structural * matching.basisDensityMultiplier, constraints.size * 2)
    )
    val minimum = if (outputClosed) 3 else 2
    return desired.coerceAtLeast(minimum).coerceAtMost(matching.maximumBasisCount)
}

private fun extractAnchors(line: FLine, dense: DensePolyline, extraSamples: Int): List<Anchor> {
    val rawPoints = line.nodes
        .filter { it.storage.isNotEmpty() }
        .map { Vec3(it.to) }

    val anchors = ArrayList<Anchor>()
    dedupePoints(rawPoints, dense.closed).forEach { point ->
        val projection = dense.project(point)
        anchors += Anchor(rawU = projection.u, point = point, weight = 1f)
    }

    if (extraSamples > 0 && dense.points.size > 1) {
        if (dense.closed) {
            for (i in 0 until extraSamples) {
                val u = (i.toFloat() + 0.5f) / extraSamples.toFloat()
                anchors += Anchor(rawU = u, point = dense.sample(u), weight = 0.5f)
            }
        } else {
            for (i in 0 until extraSamples) {
                val u = (i.toFloat() + 1f) / (extraSamples + 1).toFloat()
                anchors += Anchor(rawU = u, point = dense.sample(u), weight = 0.5f)
            }
        }
    }

    if (anchors.isEmpty() && dense.points.isNotEmpty()) {
        anchors += Anchor(rawU = 0f, point = Vec3(dense.points[0]), weight = 1f)
    }

    return dedupeAnchors(anchors)
}

private fun dedupePoints(points: List<Vec3>, closed: Boolean): List<Vec3> {
    if (points.isEmpty()) return emptyList()
    val out = ArrayList<Vec3>(points.size)
    for (point in points) {
        if (out.isEmpty() || out[out.lastIndex].distance(point) > 1e-4f) {
            out += Vec3(point)
        }
    }
    if (closed && out.size > 1 && out[0].distance(out[out.lastIndex]) <= 1e-4f) {
        out.removeAt(out.lastIndex)
    }
    return out
}

private fun dedupeAnchors(anchors: List<Anchor>): List<Anchor> {
    if (anchors.isEmpty()) return emptyList()
    val sorted = anchors.sortedBy { it.rawU }
    val out = ArrayList<Anchor>(sorted.size)
    val epsilon = 1e-4f
    for (anchor in sorted) {
        if (out.isEmpty() || abs(anchor.rawU - out.last().rawU) > epsilon) {
            out += anchor
        }
    }
    return out
}

private fun buildShiftCandidates(
    anchors: List<Anchor>,
    steps: Int?
): List<Float> {
    if (steps != null) {
        val n = steps.coerceAtLeast(1)
        return (0 until n).map { i -> i.toFloat() / n.toFloat() }
    }
    if (anchors.isEmpty()) return listOf(0f)

    val sorted = anchors.map { wrap01(it.rawU) }.sorted()
    val out = ArrayList<Float>(sorted.size + 1)
    out += 0f
    for (candidate in sorted) {
        if (out.none { abs(it - candidate) <= 1e-4f }) {
            out += candidate
        }
    }
    return out
}

private fun assignmentCost(
    a: Anchor,
    b: OrderedAnchor,
    orderPenalty: Double
): Double {
    return squaredDistance(a.aligned, b.aligned) +
        orderPenalty * (a.rawU - b.orderedU).toDouble() * (a.rawU - b.orderedU).toDouble()
}

private fun assignmentTotalCost(
    cost: Array<DoubleArray>,
    assignment: IntArray,
    columnCount: Int,
    unmatchedCost: Double
): Double {
    if (cost.isEmpty()) return columnCount * unmatchedCost
    var total = 0.0
    val used = BooleanArray(columnCount)
    for (row in cost.indices) {
        val col = assignment.getOrNull(row) ?: -1
        if (col in 0 until columnCount) {
            total += cost[row][col]
            used[col] = true
        } else {
            total += unmatchedCost
        }
    }
    for (col in 0 until columnCount) {
        if (!used[col]) total += unmatchedCost
    }
    return total
}

private object RectHungarianAssignment {
    fun solve(cost: Array<DoubleArray>, unmatchedCost: Double): IntArray {
        val rows = cost.size
        val cols = cost.firstOrNull()?.size ?: 0
        if (rows == 0) return IntArray(0)
        if (cols == 0) return IntArray(rows) { -1 }

        val size = max(rows, cols)
        val square = Array(size) { DoubleArray(size) { unmatchedCost } }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                square[row][col] = cost[row][col]
            }
        }

        val assignment = solveSquare(square)
        return IntArray(rows) { row ->
            val col = assignment[row]
            if (col in 0 until cols) col else -1
        }
    }

    private fun solveSquare(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(n + 1)

            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0

                for (j in 1..n) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }

                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val assignment = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] != 0) assignment[p[j] - 1] = j - 1
        }
        return assignment
    }
}

private fun FLine.requireSingleContour(label: String) {
    val moveCount = nodes.count { it is FLine.MoveTo }
    require(moveCount <= 1) {
        "FLineInterpolator currently supports a single contour only; $label has $moveCount MoveTo instructions"
    }
}

private fun FLine.isExplicitlyClosed(): Boolean = nodes.lastOrNull() is FLine.Close

private fun sampleGrid(count: Int, closed: Boolean): FloatArray {
    if (count <= 0) return FloatArray(0)
    if (count == 1) return floatArrayOf(0f)
    return if (closed) {
        FloatArray(count) { i -> i.toFloat() / count.toFloat() }
    } else {
        FloatArray(count) { i -> i.toFloat() / (count - 1).toFloat() }
    }
}

private fun rotatePointOrderInPlace(points: MutableList<Vec3>, shift: Float) {
    if (points.size < 2) return
    val wrapped = ((shift % points.size.toFloat()) + points.size.toFloat()) % points.size.toFloat()
    val whole = floor(wrapped).toInt()
    val fraction = wrapped - floor(wrapped)
    val copy = points.map { Vec3(it) }

    for (i in points.indices) {
        val a = copy[(i + whole).floorMod(copy.size)]
        val b = copy[(i + whole + 1).floorMod(copy.size)]
        points[i].set(Vec3(a).lerp(b, fraction))
    }
}

private fun normalizedAxis(axis: Vec3): Vec3 {
    val out = Vec3(axis)
    if (out.lengthSquared() <= EPSILON * EPSILON) return vec(0f, 0f, 1f)
    return out.normalize()
}

private fun principalDirection2D(points: List<Vec3>): Vec3 {
    if (points.size < 2) return vec(1f, 0f, 0f)
    val center = points.center()
    var sxx = 0f
    var sxy = 0f
    var syy = 0f
    for (point in points) {
        val dx = point.x - center.x
        val dy = point.y - center.y
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }

    val trace = sxx + syy
    val det = sxx * syy - sxy * sxy
    val lambda = trace * 0.5f + sqrt((trace * trace * 0.25f - det).coerceAtLeast(0f))
    val dir = when {
        abs(sxy) > EPSILON -> vec(lambda - syy, sxy, 0f)
        sxx >= syy -> vec(1f, 0f, 0f)
        else -> vec(0f, 1f, 0f)
    }

    if (dir.lengthSquared() <= EPSILON * EPSILON) dir.set(1f, 0f, 0f)
    dir.normalize()

    val order = Vec3(points.last()).sub(points.first())
    if (order.lengthSquared() > EPSILON * EPSILON && dir.dot(order) < 0f) {
        dir.negate()
    }
    return dir
}

private fun principalDirection3D(points: List<Vec3>): Vec3 {
    if (points.size < 2) return vec(1f, 0f, 0f)
    val center = points.center()
    var sxx = 0f
    var sxy = 0f
    var sxz = 0f
    var syy = 0f
    var syz = 0f
    var szz = 0f

    for (point in points) {
        val dx = point.x - center.x
        val dy = point.y - center.y
        val dz = point.z - center.z
        sxx += dx * dx
        sxy += dx * dy
        sxz += dx * dz
        syy += dy * dy
        syz += dy * dz
        szz += dz * dz
    }

    val dir = vec(1f, 1f, 1f).normalize()
    repeat(24) {
        val x = sxx * dir.x + sxy * dir.y + sxz * dir.z
        val y = sxy * dir.x + syy * dir.y + syz * dir.z
        val z = sxz * dir.x + syz * dir.y + szz * dir.z
        dir.set(x, y, z)
        if (dir.lengthSquared() <= EPSILON * EPSILON) {
            dir.set(1f, 0f, 0f)
        } else {
            dir.normalize()
        }
    }

    val order = Vec3(points.last()).sub(points.first())
    if (order.lengthSquared() > EPSILON * EPSILON && dir.dot(order) < 0f) {
        dir.negate()
    }
    return dir
}

private fun canonicalizePoints(points: List<Vec3>, closed: Boolean): List<Vec3> {
    if (points.isEmpty()) return emptyList()
    val out = ArrayList<Vec3>(points.size)
    for (point in points) {
        if (out.isEmpty() || out[out.lastIndex].distance(point) > 1e-6f) {
            out += Vec3(point)
        }
    }
    if (closed && out.size > 1 && out[0].distance(out[out.lastIndex]) <= 1e-3f) {
        out.removeAt(out.lastIndex)
    }
    return out
}

private fun wrap01(value: Float): Float {
    var out = value % 1f
    if (out < 0f) out += 1f
    return out
}

private fun Int.floorMod(modulus: Int): Int {
    if (modulus <= 0) return this
    val out = this % modulus
    return if (out < 0) out + modulus else out
}

private fun squaredDistance(a: Vec3, b: Vec3): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    val dz = (a.z - b.z).toDouble()
    return dx * dx + dy * dy + dz * dz
}

private const val EPSILON = 1e-6f
