package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.vec
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Parametric 3D polynomial curve: x(t), y(t), z(t) for t in [0, 1].
 */
data class Polynomial3Curve(
    val xCoefficients: DoubleArray,
    val yCoefficients: DoubleArray,
    val zCoefficients: DoubleArray
) {
    init {
        require(xCoefficients.isNotEmpty()) { "xCoefficients must not be empty" }
        require(xCoefficients.size == yCoefficients.size && yCoefficients.size == zCoefficients.size) {
            "Coefficient arrays must have equal lengths"
        }
    }

    val order: Int
        get() = xCoefficients.size - 1

    fun evaluate(t: Double): Vec3 {
        val tc = t.coerceIn(0.0, 1.0)
        return vec(
            evalPolynomial(xCoefficients, tc).toFloat(),
            evalPolynomial(yCoefficients, tc).toFloat(),
            evalPolynomial(zCoefficients, tc).toFloat()
        )
    }

    fun evaluate(t: Float): Vec3 = evaluate(t.toDouble())

    fun sample(samples: Int = 64, tStart: Double = 0.0, tEnd: Double = 1.0): List<Vec3> {
        val count = samples.coerceAtLeast(2)
        val a = tStart.coerceIn(0.0, 1.0)
        val b = tEnd.coerceIn(0.0, 1.0)
        val out = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            val alpha = if (count == 1) 0.0 else i.toDouble() / (count - 1).toDouble()
            val t = a + (b - a) * alpha
            out += evaluate(t)
        }
        return out
    }

    fun toFLine(samples: Int = 64, tStart: Double = 0.0, tEnd: Double = 1.0): FLine {
        val pts = sample(samples = samples, tStart = tStart, tEnd = tEnd)
        val line = FLine()
        if (pts.isEmpty()) return line
        line.moveTo(pts.first())
        for (i in 1 until pts.size) line.lineTo(pts[i])
        return line
    }
}

data class PolynomialTreeNode(
    val id: Int,
    val depth: Int,
    val sourceRange: IntRange,
    val supportIndices: IntArray,
    val polynomial: Polynomial3Curve,
    val supportRmsError: Double,
    val supportMeanError: Double,
    val supportMaxError: Double,
    val splitPointIndex: Int? = null,
    val stopReason: String,
    val claimedIndices: IntArray = IntArray(0),
    val claimedRmsError: Double? = null,
    val claimedMaxError: Double? = null,
    val left: PolynomialTreeNode? = null,
    val right: PolynomialTreeNode? = null
) {
    val isLeaf: Boolean
        get() = left == null && right == null

    fun leaves(): List<PolynomialTreeNode> {
        if (isLeaf) return listOf(this)
        val out = ArrayList<PolynomialTreeNode>()
        fun walk(node: PolynomialTreeNode) {
            if (node.isLeaf) out += node
            else {
                node.left?.let(::walk)
                node.right?.let(::walk)
            }
        }
        walk(this)
        return out
    }
}

data class PolynomialTreeTraversalContext(
    val node: PolynomialTreeNode,
    val depth: Int,
    val indexInLevel: Int,
    val parent: PolynomialTreeNode?
)

data class PolynomialTreeTraversalDecision(
    val emit: Boolean = true,
    val descend: Boolean = true,
    val stopAll: Boolean = false
)

data class PolynomialTreeTraversalLevel(
    val depth: Int,
    val nodes: List<PolynomialTreeTraversalContext>
) {
    fun toFLines(samplesPerNode: Int = 64): List<FLine> = toFLines { it.node.polynomial.toFLine(samplesPerNode) }

    fun toFLines(mapper: (PolynomialTreeTraversalContext) -> FLine?): List<FLine> {
        val out = ArrayList<FLine>(nodes.size)
        for (node in nodes) {
            val mapped = mapper(node)
            if (mapped != null) out += mapped
        }
        return out
    }
}

fun interface PolynomialTreeTraversalController {
    fun decide(context: PolynomialTreeTraversalContext): PolynomialTreeTraversalDecision
}

fun PolynomialTreeNode.levelTraversal(
    controller: PolynomialTreeTraversalController = PolynomialTreeTraversalController { PolynomialTreeTraversalDecision() }
): Sequence<PolynomialTreeTraversalLevel> = sequence {
    data class QueuedNode(
        val node: PolynomialTreeNode,
        val depth: Int,
        val parent: PolynomialTreeNode?
    )

    var frontier = arrayListOf(QueuedNode(node = this@levelTraversal, depth = 0, parent = null))
    while (frontier.isNotEmpty()) {
        val depth = frontier.first().depth
        val emitted = ArrayList<PolynomialTreeTraversalContext>(frontier.size)
        val next = ArrayList<QueuedNode>(frontier.size * 2)
        var stopAll = false

        for ((indexInLevel, queued) in frontier.withIndex()) {
            val context = PolynomialTreeTraversalContext(
                node = queued.node,
                depth = queued.depth,
                indexInLevel = indexInLevel,
                parent = queued.parent
            )
            val decision = controller.decide(context)
            if (decision.emit) emitted += context
            if (decision.stopAll) {
                stopAll = true
                break
            }
            if (decision.descend) {
                queued.node.left?.let { next += QueuedNode(it, queued.depth + 1, queued.node) }
                queued.node.right?.let { next += QueuedNode(it, queued.depth + 1, queued.node) }
            }
        }

        if (emitted.isNotEmpty()) {
            yield(PolynomialTreeTraversalLevel(depth = depth, nodes = emitted))
        }
        if (stopAll) break
        frontier = next
    }
}

fun PolynomialTreeNode.approxCurveLength(samples: Int = 32): Double {
    val pts = polynomial.sample(samples = samples.coerceAtLeast(2))
    if (pts.size < 2) return 0.0
    var length = 0.0
    for (i in 1 until pts.size) {
        length += distance(pts[i - 1], pts[i])
    }
    return length
}

data class PolynomialTreeFitResult(
    val inputPoints: List<Vec3>,
    val config: PolynomialTreeFitConfig,
    val root: PolynomialTreeNode,
    val leaves: List<PolynomialTreeNode>,
    val pointErrors: DoubleArray,
    val nearestLeafIds: IntArray,
    val overallRmsError: Double,
    val overallMeanError: Double,
    val overallMaxError: Double
) {
    fun leafById(id: Int): PolynomialTreeNode? = leaves.firstOrNull { it.id == id }

    fun levelTraversal(
        controller: PolynomialTreeTraversalController = PolynomialTreeTraversalController { PolynomialTreeTraversalDecision() }
    ): Sequence<PolynomialTreeTraversalLevel> = root.levelTraversal(controller)

    fun toLeafFLines(samplesPerLeaf: Int = 64): List<FLine> =
        toLeafFLines(PolynomialLeafFLineRenderConfig(samplesPerLeaf = samplesPerLeaf))

    fun toLeafFLines(render: PolynomialLeafFLineRenderConfig): List<FLine> =
        buildLeafFLines(leaves, render)

    fun toLeafFLineMap(samplesPerLeaf: Int = 64): Map<String, List<FLine>> = leaves.associate { leaf ->
        "poly_leaf_${leaf.id}" to listOf(leaf.polynomial.toFLine(samplesPerLeaf))
    }

    fun toLeafFLineMap(
        render: PolynomialLeafFLineRenderConfig,
        keyPrefix: String = "poly_leaf"
    ): Map<String, List<FLine>> {
        val leafRender = render.copy(outputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES)
        val ordered = orderLeaves(leaves, leafRender.orderMode)
        return ordered.associate { leaf ->
            "${keyPrefix}_${leaf.id}" to buildLeafFLines(listOf(leaf), leafRender)
        }
    }

    fun incrementalUpdater(points: List<Vec3> = inputPoints): PolynomialTreeIncrementalUpdater =
        PolynomialTreeIncrementalUpdater(
            config = config,
            templateRoot = root,
            initialPoints = points
        )
}

enum class PolynomialLeafFLineOutputMode {
    SEPARATE_LINES,
    SINGLE_MULTIPATH,
    SINGLE_CONNECTED
}

enum class PolynomialLeafOrderMode {
    SOURCE_ASCENDING,
    SOURCE_DESCENDING,
    ERROR_DESCENDING,
    ERROR_ASCENDING,
    DEPTH_ASCENDING,
    DEPTH_DESCENDING
}

data class PolynomialLeafGapStyle(
    val enabled: Boolean = false,
    val absolute: Double = 0.0,
    val fractionOfSpan: Double = 0.06,
    val trimStart: Boolean = true,
    val trimEnd: Boolean = true,
    val minAbsolute: Double = 0.0,
    val maxAbsolute: Double = Double.POSITIVE_INFINITY
) {
    companion object {
        fun none(): PolynomialLeafGapStyle = PolynomialLeafGapStyle(enabled = false)
        fun symmetric(
            absolute: Double = 0.0,
            fractionOfSpan: Double = 0.06,
            minAbsolute: Double = 0.0,
            maxAbsolute: Double = Double.POSITIVE_INFINITY
        ): PolynomialLeafGapStyle = PolynomialLeafGapStyle(
            enabled = true,
            absolute = absolute,
            fractionOfSpan = fractionOfSpan,
            trimStart = true,
            trimEnd = true,
            minAbsolute = minAbsolute,
            maxAbsolute = maxAbsolute
        )
    }
}

data class PolynomialLeafOvershootStyle(
    val enabled: Boolean = false,
    val startAbsolute: Double = 0.0,
    val endAbsolute: Double = 0.0,
    val startFractionOfSpan: Double = 0.0,
    val endFractionOfSpan: Double = 0.08,
    val maxAbsolute: Double = Double.POSITIVE_INFINITY
) {
    companion object {
        fun none(): PolynomialLeafOvershootStyle = PolynomialLeafOvershootStyle(enabled = false)
        fun symmetric(
            absolute: Double = 0.0,
            fractionOfSpan: Double = 0.08,
            maxAbsolute: Double = Double.POSITIVE_INFINITY
        ): PolynomialLeafOvershootStyle = PolynomialLeafOvershootStyle(
            enabled = true,
            startAbsolute = absolute,
            endAbsolute = absolute,
            startFractionOfSpan = fractionOfSpan,
            endFractionOfSpan = fractionOfSpan,
            maxAbsolute = maxAbsolute
        )
    }
}

data class PolynomialLeafSketchStyle(
    val passes: Int = 1,
    val jitterAmplitude: Double = 0.0,
    val alongTangentFactor: Double = 0.25,
    val keepEndpoints: Boolean = true,
    val seed: Long = 1L
)

data class PolynomialLeafFLineRenderConfig(
    val samplesPerLeaf: Int = 64,
    val outputMode: PolynomialLeafFLineOutputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES,
    val orderMode: PolynomialLeafOrderMode = PolynomialLeafOrderMode.SOURCE_ASCENDING,
    val gap: PolynomialLeafGapStyle = PolynomialLeafGapStyle.none(),
    val overshoot: PolynomialLeafOvershootStyle = PolynomialLeafOvershootStyle.none(),
    val sketch: PolynomialLeafSketchStyle = PolynomialLeafSketchStyle()
)

object PolynomialLeafFLineStyles {
    fun clean(
        samplesPerLeaf: Int = 64,
        outputMode: PolynomialLeafFLineOutputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
    ): PolynomialLeafFLineRenderConfig = PolynomialLeafFLineRenderConfig(
        samplesPerLeaf = samplesPerLeaf,
        outputMode = outputMode,
        gap = PolynomialLeafGapStyle.none(),
        overshoot = PolynomialLeafOvershootStyle.none(),
        sketch = PolynomialLeafSketchStyle()
    )

    fun gapped(
        samplesPerLeaf: Int = 64,
        gapAbsolute: Double = 0.0,
        gapFractionOfSpan: Double = 0.08,
        outputMode: PolynomialLeafFLineOutputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
    ): PolynomialLeafFLineRenderConfig = PolynomialLeafFLineRenderConfig(
        samplesPerLeaf = samplesPerLeaf,
        outputMode = outputMode,
        gap = PolynomialLeafGapStyle.symmetric(
            absolute = gapAbsolute,
            fractionOfSpan = gapFractionOfSpan
        )
    )

    fun overshooting(
        samplesPerLeaf: Int = 64,
        overshootAbsolute: Double = 0.0,
        overshootFractionOfSpan: Double = 0.1,
        outputMode: PolynomialLeafFLineOutputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
    ): PolynomialLeafFLineRenderConfig = PolynomialLeafFLineRenderConfig(
        samplesPerLeaf = samplesPerLeaf,
        outputMode = outputMode,
        overshoot = PolynomialLeafOvershootStyle.symmetric(
            absolute = overshootAbsolute,
            fractionOfSpan = overshootFractionOfSpan
        )
    )

    fun sketchy(
        samplesPerLeaf: Int = 72,
        passes: Int = 3,
        jitterAmplitude: Double = 0.25,
        gapFractionOfSpan: Double = 0.05,
        overshootFractionOfSpan: Double = 0.09,
        outputMode: PolynomialLeafFLineOutputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
    ): PolynomialLeafFLineRenderConfig = PolynomialLeafFLineRenderConfig(
        samplesPerLeaf = samplesPerLeaf,
        outputMode = outputMode,
        gap = PolynomialLeafGapStyle.symmetric(fractionOfSpan = gapFractionOfSpan),
        overshoot = PolynomialLeafOvershootStyle.symmetric(fractionOfSpan = overshootFractionOfSpan),
        sketch = PolynomialLeafSketchStyle(
            passes = passes,
            jitterAmplitude = jitterAmplitude,
            alongTangentFactor = 0.35
        )
    )
}

fun interface PolynomialTreeSplitCriterion {
    fun chooseSplit(context: PolynomialTreeSplitContext): Int?
}

fun interface PolynomialTreeStopCriterion {
    fun shouldStop(context: PolynomialTreeStopContext): Boolean
}

fun interface PolynomialTreeClaimCriterion {
    fun accepts(context: PolynomialTreeClaimContext): Boolean
}

data class PolynomialTreeSplitContext(
    val points: List<Vec3>,
    val depth: Int,
    val supportIndices: IntArray,
    val supportRange: IntRange,
    val pointErrors: DoubleArray,
    val nearestT: DoubleArray,
    val rmsError: Double,
    val meanError: Double,
    val maxError: Double,
    val maxErrorLocalIndex: Int
) {
    val size: Int
        get() = supportIndices.size

    fun middleLocalIndex(): Int = supportIndices.lastIndex / 2

    fun globalIndexAt(localIndex: Int): Int = supportIndices[localIndex.coerceIn(0, supportIndices.lastIndex)]

    fun localIndexOf(globalPointIndex: Int): Int = supportIndices.indexOf(globalPointIndex)
}

data class PolynomialTreeStopContext(
    val split: PolynomialTreeSplitContext,
    val config: PolynomialTreeFitConfig
)

data class PolynomialTreeClaimContext(
    val pointIndex: Int,
    val point: Vec3,
    val leafId: Int,
    val leafRange: IntRange,
    val distance: Double,
    val rankByDistance: Int,
    val ownerLeafId: Int,
    val ownerDistance: Double,
    val bestDistance: Double
)

data class PolynomialTreeClaimConfig(
    val enabled: Boolean = false,
    val criterion: PolynomialTreeClaimCriterion = PolynomialTreeClaimCriteria.distanceBelow(0.75),
    val includeSequentialOwner: Boolean = false,
    val maxClaimsPerPoint: Int = 1,
    val maxClaimsPerLeaf: Int = Int.MAX_VALUE,
    val refitLeaves: Boolean = false,
    val claimRefitWeight: Double = 0.35
)

data class PolynomialTreeFitConfig(
    val order: Int = 3,
    val maxDepth: Int = 8,
    val maxLeaves: Int = 64,
    val minPointsPerLeaf: Int = order + 2,
    val targetRmsError: Double = 1.0,
    val targetMaxError: Double = 2.0,
    val splitCriterion: PolynomialTreeSplitCriterion = PolynomialTreeSplitStrategies.maxError(),
    val stopCriterion: PolynomialTreeStopCriterion = PolynomialTreeStopCriteria.byThresholds,
    val distanceSamples: Int = 48,
    val distanceRefinementSteps: Int = 8,
    val regularization: Double = 1e-8,
    val claims: PolynomialTreeClaimConfig = PolynomialTreeClaimConfig()
)

object PolynomialTreeSplitStrategies {
    fun maxError(): PolynomialTreeSplitCriterion = PolynomialTreeSplitCriterion { ctx ->
        if (ctx.pointErrors.isEmpty()) null else ctx.globalIndexAt(ctx.maxErrorLocalIndex)
    }

    fun midpoint(): PolynomialTreeSplitCriterion = PolynomialTreeSplitCriterion { ctx ->
        if (ctx.supportIndices.isEmpty()) null else ctx.globalIndexAt(ctx.middleLocalIndex())
    }

    fun hybrid(errorBias: Double = 0.7): PolynomialTreeSplitCriterion = PolynomialTreeSplitCriterion { ctx ->
        if (ctx.supportIndices.isEmpty()) return@PolynomialTreeSplitCriterion null
        val e = errorBias.coerceIn(0.0, 1.0)
        val maxI = ctx.maxErrorLocalIndex.toDouble()
        val midI = ctx.middleLocalIndex().toDouble()
        val mixed = (e * maxI + (1.0 - e) * midI).roundToInt()
        ctx.globalIndexAt(mixed)
    }

    fun curvatureAware(errorWeight: Double = 0.65, curvatureWeight: Double = 0.35): PolynomialTreeSplitCriterion =
        PolynomialTreeSplitCriterion { ctx ->
            if (ctx.supportIndices.size < 3) return@PolynomialTreeSplitCriterion midpoint().chooseSplit(ctx)
            val eW = errorWeight.coerceIn(0.0, 1.0)
            val cW = curvatureWeight.coerceIn(0.0, 1.0)
            val total = (eW + cW).coerceAtLeast(1e-9)
            val nwE = eW / total
            val nwC = cW / total

            val maxErr = ctx.pointErrors.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
            var maxCurv = 1e-9
            val curvatures = DoubleArray(ctx.supportIndices.size)
            for (i in 1 until ctx.supportIndices.lastIndex) {
                val prev = ctx.points[ctx.supportIndices[i - 1]]
                val cur = ctx.points[ctx.supportIndices[i]]
                val next = ctx.points[ctx.supportIndices[i + 1]]
                val curvature = turningAngle(prev, cur, next)
                curvatures[i] = curvature
                maxCurv = max(maxCurv, curvature)
            }

            var bestLocal = ctx.middleLocalIndex()
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 1 until ctx.supportIndices.lastIndex) {
                val e = ctx.pointErrors[i] / maxErr
                val c = curvatures[i] / maxCurv
                val score = nwE * e + nwC * c
                if (score > bestScore) {
                    bestScore = score
                    bestLocal = i
                }
            }
            ctx.globalIndexAt(bestLocal)
        }
}

object PolynomialTreeStopCriteria {
    val byThresholds = PolynomialTreeStopCriterion { ctx ->
        ctx.split.rmsError <= ctx.config.targetRmsError && ctx.split.maxError <= ctx.config.targetMaxError
    }

    fun maxErrorOnly(maxError: Double): PolynomialTreeStopCriterion = PolynomialTreeStopCriterion { ctx ->
        ctx.split.maxError <= maxError
    }

    fun rmsOnly(rmsError: Double): PolynomialTreeStopCriterion = PolynomialTreeStopCriterion { ctx ->
        ctx.split.rmsError <= rmsError
    }

    val never = PolynomialTreeStopCriterion { false }
}

object PolynomialTreeClaimCriteria {
    fun distanceBelow(maxDistance: Double): PolynomialTreeClaimCriterion = PolynomialTreeClaimCriterion { ctx ->
        ctx.distance <= maxDistance
    }

    fun closeToBest(maxDistance: Double = 1.0, ratioFromBest: Double = 1.2): PolynomialTreeClaimCriterion =
        PolynomialTreeClaimCriterion { ctx ->
            ctx.distance <= maxDistance && ctx.distance <= ctx.bestDistance * ratioFromBest
        }

    fun betterThanOwner(maxDistance: Double = 1.0, minImprovement: Double = 0.25): PolynomialTreeClaimCriterion =
        PolynomialTreeClaimCriterion { ctx ->
            ctx.distance <= maxDistance && (ctx.ownerDistance - ctx.distance) >= minImprovement
        }
}

/**
 * Fit a recursive (possibly unbalanced) tree of 3D polynomial segments to ordered points.
 *
 * Nodes are fit in parametric form (x(t), y(t), z(t)); split locations and stop conditions are configurable.
 * Error is measured as approximate minimum distance from each support point to the node polynomial.
 */
fun fitPolynomialTree3DFromPoints(
    points: List<Vec3>,
    config: PolynomialTreeFitConfig = PolynomialTreeFitConfig()
): PolynomialTreeFitResult = points.fitPolynomialTree3D(config)

fun List<Vec3>.fitPolynomialTree3D(
    config: PolynomialTreeFitConfig = PolynomialTreeFitConfig()
): PolynomialTreeFitResult {
    require(isNotEmpty()) { "fitPolynomialTree3D: points must not be empty" }
    require(config.order >= 1) { "fitPolynomialTree3D: order must be >= 1, found ${config.order}" }
    require(config.maxDepth >= 0) { "fitPolynomialTree3D: maxDepth must be >= 0, found ${config.maxDepth}" }
    require(config.maxLeaves >= 1) { "fitPolynomialTree3D: maxLeaves must be >= 1, found ${config.maxLeaves}" }
    require(config.distanceSamples >= 4) { "fitPolynomialTree3D: distanceSamples must be >= 4" }
    require(config.distanceRefinementSteps >= 0) { "fitPolynomialTree3D: distanceRefinementSteps must be >= 0" }
    require(config.regularization > 0.0) { "fitPolynomialTree3D: regularization must be > 0" }
    require(config.claims.claimRefitWeight >= 0.0) { "fitPolynomialTree3D: claimRefitWeight must be >= 0" }

    val copiedPoints = this.map { Vec3(it) }
    require(copiedPoints.all { it.isFinite() }) { "fitPolynomialTree3D: all points must be finite" }

    val effectiveMinPoints = max(config.minPointsPerLeaf, config.order + 1).coerceAtLeast(2)
    val tunedConfig = config.copy(
        minPointsPerLeaf = effectiveMinPoints,
        distanceSamples = config.distanceSamples.coerceAtLeast(4),
        maxLeaves = config.maxLeaves.coerceAtLeast(1)
    )

    val rootIndices = IntArray(copiedPoints.size) { it }
    val buildState = BuildState(nextId = 1, splitsRemaining = tunedConfig.maxLeaves - 1)
    val root = buildNode(
        points = copiedPoints,
        supportIndices = rootIndices,
        depth = 0,
        config = tunedConfig,
        state = buildState
    )

    if (tunedConfig.claims.enabled) {
        applyNonSequentialClaims(root, copiedPoints, tunedConfig)
    }

    val mutableLeaves = collectLeaves(root)
    val globalSummary = computeGlobalSummary(
        points = copiedPoints,
        leaves = mutableLeaves,
        config = tunedConfig
    )

    val immutableRoot = root.freeze()
    val immutableLeaves = immutableRoot.leaves()
    return PolynomialTreeFitResult(
        inputPoints = copiedPoints,
        config = tunedConfig,
        root = immutableRoot,
        leaves = immutableLeaves,
        pointErrors = globalSummary.errors,
        nearestLeafIds = globalSummary.nearestLeafIds,
        overallRmsError = globalSummary.rms,
        overallMeanError = globalSummary.mean,
        overallMaxError = globalSummary.max
    )
}

/**
 * Convenience wrapper for fitting a polynomial tree to points sampled from an [FLine].
 */
fun FLine.fitPolynomialTree3D(
    config: PolynomialTreeFitConfig = PolynomialTreeFitConfig(),
    samples: Int = 128,
    sampler: FLineSampler? = null
): PolynomialTreeFitResult {
    val pts = samplePoints(samples = samples, sampler = sampler)
    return pts.fitPolynomialTree3D(config)
}

fun PolynomialTreeNode.toFLine(samples: Int = 64): FLine = polynomial.toFLine(samples)

fun PolynomialTreeFitResult.toIncrementalUpdater(
    points: List<Vec3> = inputPoints
): PolynomialTreeIncrementalUpdater = incrementalUpdater(points)

fun List<Vec3>.fitPolynomialTree3DIncremental(
    config: PolynomialTreeFitConfig = PolynomialTreeFitConfig()
): PolynomialTreeIncrementalUpdater = fitPolynomialTree3D(config).toIncrementalUpdater()

fun FLine.fitPolynomialTree3DIncremental(
    config: PolynomialTreeFitConfig = PolynomialTreeFitConfig(),
    samples: Int = 128,
    sampler: FLineSampler? = null
): PolynomialTreeIncrementalUpdater = fitPolynomialTree3D(config = config, samples = samples, sampler = sampler).toIncrementalUpdater()

data class PolynomialTreeIncrementalSummary(
    val pointCount: Int,
    val overallRmsError: Double,
    val overallMeanError: Double,
    val overallMaxError: Double
)

/**
 * Mutable updater that keeps a fixed tree topology and reusable buffers while refitting against new points.
 *
 * Node allocations are stable after construction; [update] reuses existing node objects and resizable buffers.
 */
class PolynomialTreeIncrementalUpdater internal constructor(
    private val config: PolynomialTreeFitConfig,
    templateRoot: PolynomialTreeNode,
    initialPoints: List<Vec3>
) {
    private val points = ArrayList<Vec3>()
    private var pointCountInternal = 0

    private val root: MutableIncrementalTreeNode
    private val nodesInBuildOrder: List<MutableIncrementalTreeNode>
    private val leaves: List<MutableIncrementalTreeNode>
    private val distanceScratch: MutableList<MutableLeafDistance>

    private val sequentialScratch = ArrayList<WeightedSample>()
    private val claimRefitScratch = ArrayList<WeightedSample>()

    private var pointErrorsBuffer = DoubleArray(initialPoints.size.coerceAtLeast(1))
    private var nearestLeafIdsBuffer = IntArray(initialPoints.size.coerceAtLeast(1))

    var overallRmsError: Double = 0.0
        private set
    var overallMeanError: Double = 0.0
        private set
    var overallMaxError: Double = 0.0
        private set

    val pointCount: Int
        get() = pointCountInternal

    val leafCount: Int
        get() = leaves.size

    init {
        require(initialPoints.isNotEmpty()) { "PolynomialTreeIncrementalUpdater: initialPoints must not be empty" }
        require(initialPoints.all { it.isFinite() }) { "PolynomialTreeIncrementalUpdater: initialPoints must be finite" }

        val baseLastIndex = (initialPoints.size - 1).coerceAtLeast(1)
        root = buildIncrementalNode(templateRoot, baseLastIndex)
        nodesInBuildOrder = collectIncrementalNodesPreOrder(root)
        leaves = nodesInBuildOrder.filter { it.left == null && it.right == null }
        distanceScratch = leaves.map { MutableLeafDistance(it, 0.0) }.toMutableList()

        update(initialPoints)
    }

    fun summary(): PolynomialTreeIncrementalSummary = PolynomialTreeIncrementalSummary(
        pointCount = pointCountInternal,
        overallRmsError = overallRmsError,
        overallMeanError = overallMeanError,
        overallMaxError = overallMaxError
    )

    fun pointErrorsCopy(): DoubleArray = pointErrorsBuffer.copyOf(pointCountInternal)

    fun nearestLeafIdsCopy(): IntArray = nearestLeafIdsBuffer.copyOf(pointCountInternal)

    fun snapshot(): PolynomialTreeFitResult {
        val frozenRoot = freezeIncrementalNode(root, pointCountInternal)
        return PolynomialTreeFitResult(
            inputPoints = points.subList(0, pointCountInternal).map(::Vec3),
            config = config,
            root = frozenRoot,
            leaves = frozenRoot.leaves(),
            pointErrors = pointErrorsBuffer.copyOf(pointCountInternal),
            nearestLeafIds = nearestLeafIdsBuffer.copyOf(pointCountInternal),
            overallRmsError = overallRmsError,
            overallMeanError = overallMeanError,
            overallMaxError = overallMaxError
        )
    }

    fun update(newPoints: List<Vec3>): PolynomialTreeIncrementalSummary {
        require(newPoints.isNotEmpty()) { "PolynomialTreeIncrementalUpdater.update: points must not be empty" }
        require(newPoints.all { it.isFinite() }) { "PolynomialTreeIncrementalUpdater.update: points must be finite" }

        overwritePoints(newPoints)
        refitAllNodes()
        if (config.claims.enabled) {
            applyNonSequentialClaimsIncremental()
        }
        recomputeGlobalSummary()
        return summary()
    }

    private fun overwritePoints(newPoints: List<Vec3>) {
        val targetCount = newPoints.size
        while (points.size < targetCount) points += vec(0f, 0f, 0f)
        for (i in 0 until targetCount) {
            points[i].set(newPoints[i])
        }
        if (points.size > targetCount) {
            points.subList(targetCount, points.size).clear()
        }
        pointCountInternal = targetCount
        ensureGlobalCapacity(targetCount)
    }

    private fun ensureGlobalCapacity(required: Int) {
        if (pointErrorsBuffer.size < required) {
            pointErrorsBuffer = DoubleArray(nextCapacity(pointErrorsBuffer.size, required))
        }
        if (nearestLeafIdsBuffer.size < required) {
            nearestLeafIdsBuffer = IntArray(nextCapacity(nearestLeafIdsBuffer.size, required))
        }
    }

    private fun refitAllNodes() {
        for (node in nodesInBuildOrder) {
            val range = mapNormalizedRangeToIndices(
                startNorm = node.sourceStartNorm,
                endNorm = node.sourceEndNorm,
                pointCount = pointCountInternal
            )
            node.supportStart = range.first
            node.supportEnd = range.last

            val supportCount = (range.last - range.first + 1).coerceAtLeast(1)
            node.ensureSupportCapacity(supportCount)
            node.clearClaims()

            sequentialScratch.clear()
            buildSequentialSamplesFromRange(
                points = points,
                startIndex = node.supportStart,
                endIndex = node.supportEnd,
                destination = sequentialScratch
            )
            node.polynomial = fitPolynomialCurve(
                samples = sequentialScratch,
                requestedOrder = config.order,
                regularization = config.regularization
            )

            val stats = computeErrorsIntoBuffers(
                points = points,
                startIndex = node.supportStart,
                endIndex = node.supportEnd,
                polynomial = node.polynomial,
                distanceSamples = config.distanceSamples,
                refinementSteps = config.distanceRefinementSteps,
                errorsBuffer = node.supportErrors,
                nearestTBuffer = node.supportNearestT
            )
            node.supportCount = stats.count
            node.supportRmsError = stats.rms
            node.supportMeanError = stats.mean
            node.supportMaxError = stats.max
            node.supportMaxErrorLocalIndex = stats.maxLocalIndex
        }
    }

    private fun applyNonSequentialClaimsIncremental() {
        val claimCfg = config.claims
        if (claimCfg.maxClaimsPerLeaf <= 0 || claimCfg.maxClaimsPerPoint <= 0) return
        if (leaves.size < 2) return

        for (leaf in leaves) leaf.clearClaims()

        for (pointIndex in 0 until pointCountInternal) {
            val point = points[pointIndex]
            for (entry in distanceScratch) {
                val probe = closestDistance(
                    polynomial = entry.leaf.polynomial,
                    point = point,
                    distanceSamples = config.distanceSamples,
                    refinementSteps = config.distanceRefinementSteps
                )
                entry.distance = sqrt(probe.distanceSq)
            }
            distanceScratch.sortBy { it.distance }

            val owner = chooseSequentialOwnerIncremental(pointIndex, distanceScratch)
            val ownerDistance = distanceScratch.firstOrNull { it.leaf.id == owner.id }?.distance ?: distanceScratch.first().distance
            val bestDistance = distanceScratch.first().distance

            var acceptedForPoint = 0
            for ((rank, candidate) in distanceScratch.withIndex()) {
                val leaf = candidate.leaf
                if (!claimCfg.includeSequentialOwner && leaf.id == owner.id) continue
                if (pointIndex in leaf.currentSourceRange()) continue
                if (acceptedForPoint >= claimCfg.maxClaimsPerPoint) break
                if (leaf.claimedCount >= claimCfg.maxClaimsPerLeaf) continue

                val claimContext = PolynomialTreeClaimContext(
                    pointIndex = pointIndex,
                    point = point,
                    leafId = leaf.id,
                    leafRange = leaf.currentSourceRange(),
                    distance = candidate.distance,
                    rankByDistance = rank,
                    ownerLeafId = owner.id,
                    ownerDistance = ownerDistance,
                    bestDistance = bestDistance
                )
                if (claimCfg.criterion.accepts(claimContext)) {
                    leaf.addClaim(pointIndex)
                    acceptedForPoint++
                }
            }
        }

        for (leaf in leaves) {
            if (leaf.claimedCount == 0) {
                leaf.claimedRmsError = null
                leaf.claimedMaxError = null
                continue
            }

            if (claimCfg.refitLeaves && claimCfg.claimRefitWeight > 0.0) {
                refitLeafWithClaimsIncremental(leaf)
            }

            var sumSq = 0.0
            var maxError = 0.0
            for (i in 0 until leaf.claimedCount) {
                val pointIndex = leaf.claimedIndices[i]
                val probe = closestDistance(
                    polynomial = leaf.polynomial,
                    point = points[pointIndex],
                    distanceSamples = config.distanceSamples,
                    refinementSteps = config.distanceRefinementSteps
                )
                val error = sqrt(probe.distanceSq)
                sumSq += error * error
                maxError = max(maxError, error)
            }
            leaf.claimedRmsError = sqrt(sumSq / leaf.claimedCount.toDouble())
            leaf.claimedMaxError = maxError
        }
    }

    private fun refitLeafWithClaimsIncremental(leaf: MutableIncrementalTreeNode) {
        claimRefitScratch.clear()
        buildSequentialSamplesFromRange(
            points = points,
            startIndex = leaf.supportStart,
            endIndex = leaf.supportEnd,
            destination = claimRefitScratch
        )
        for (i in 0 until leaf.claimedCount) {
            val claimedIndex = leaf.claimedIndices[i]
            val probe = closestDistance(
                polynomial = leaf.polynomial,
                point = points[claimedIndex],
                distanceSamples = config.distanceSamples,
                refinementSteps = config.distanceRefinementSteps
            )
            claimRefitScratch += WeightedSample(
                t = probe.t,
                point = points[claimedIndex],
                weight = config.claims.claimRefitWeight
            )
        }

        leaf.polynomial = fitPolynomialCurve(
            samples = claimRefitScratch,
            requestedOrder = config.order,
            regularization = config.regularization
        )

        val stats = computeErrorsIntoBuffers(
            points = points,
            startIndex = leaf.supportStart,
            endIndex = leaf.supportEnd,
            polynomial = leaf.polynomial,
            distanceSamples = config.distanceSamples,
            refinementSteps = config.distanceRefinementSteps,
            errorsBuffer = leaf.supportErrors,
            nearestTBuffer = leaf.supportNearestT
        )
        leaf.supportCount = stats.count
        leaf.supportRmsError = stats.rms
        leaf.supportMeanError = stats.mean
        leaf.supportMaxError = stats.max
        leaf.supportMaxErrorLocalIndex = stats.maxLocalIndex
    }

    private fun recomputeGlobalSummary() {
        var sum = 0.0
        var sumSq = 0.0
        var maxError = 0.0

        for (i in 0 until pointCountInternal) {
            var bestDistance = Double.POSITIVE_INFINITY
            var bestLeafId = -1

            for (leaf in leaves) {
                val probe = closestDistance(
                    polynomial = leaf.polynomial,
                    point = points[i],
                    distanceSamples = config.distanceSamples,
                    refinementSteps = config.distanceRefinementSteps
                )
                val distance = sqrt(probe.distanceSq)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestLeafId = leaf.id
                }
            }

            pointErrorsBuffer[i] = bestDistance
            nearestLeafIdsBuffer[i] = bestLeafId
            sum += bestDistance
            sumSq += bestDistance * bestDistance
            maxError = max(maxError, bestDistance)
        }

        overallMeanError = if (pointCountInternal == 0) 0.0 else sum / pointCountInternal.toDouble()
        overallRmsError = if (pointCountInternal == 0) 0.0 else sqrt(sumSq / pointCountInternal.toDouble())
        overallMaxError = maxError
    }
}

private data class MutableLeafDistance(
    val leaf: MutableIncrementalTreeNode,
    var distance: Double
)

private class MutableIncrementalTreeNode(
    val id: Int,
    val depth: Int,
    val sourceStartNorm: Double,
    val sourceEndNorm: Double,
    val splitNorm: Double?,
    var polynomial: Polynomial3Curve,
    val stopReason: String
) {
    var left: MutableIncrementalTreeNode? = null
    var right: MutableIncrementalTreeNode? = null

    var supportStart: Int = 0
    var supportEnd: Int = 0
    var supportCount: Int = 0

    var supportErrors: DoubleArray = DoubleArray(0)
    var supportNearestT: DoubleArray = DoubleArray(0)
    var supportRmsError: Double = 0.0
    var supportMeanError: Double = 0.0
    var supportMaxError: Double = 0.0
    var supportMaxErrorLocalIndex: Int = 0

    var claimedIndices: IntArray = IntArray(0)
    var claimedCount: Int = 0
    var claimedRmsError: Double? = null
    var claimedMaxError: Double? = null

    fun currentSourceRange(): IntRange = supportStart..supportEnd

    fun clearClaims() {
        claimedCount = 0
        claimedRmsError = null
        claimedMaxError = null
    }

    fun ensureSupportCapacity(required: Int) {
        if (supportErrors.size < required) {
            val cap = nextCapacity(supportErrors.size, required)
            supportErrors = DoubleArray(cap)
            supportNearestT = DoubleArray(cap)
        } else if (supportNearestT.size < required) {
            supportNearestT = DoubleArray(nextCapacity(supportNearestT.size, required))
        }
    }

    fun ensureClaimCapacity(required: Int) {
        if (claimedIndices.size < required) {
            claimedIndices = IntArray(nextCapacity(claimedIndices.size, required))
        }
    }

    fun addClaim(pointIndex: Int) {
        ensureClaimCapacity(claimedCount + 1)
        claimedIndices[claimedCount++] = pointIndex
    }
}

private data class MutableRangeErrorStats(
    val count: Int,
    val rms: Double,
    val mean: Double,
    val max: Double,
    val maxLocalIndex: Int
)

private fun buildIncrementalNode(
    template: PolynomialTreeNode,
    baseLastIndex: Int
): MutableIncrementalTreeNode {
    val denom = baseLastIndex.toDouble().coerceAtLeast(1.0)
    val sourceStartNorm = template.sourceRange.first.toDouble() / denom
    val sourceEndNorm = template.sourceRange.last.toDouble() / denom
    val splitNorm = template.splitPointIndex?.toDouble()?.div(denom)

    val node = MutableIncrementalTreeNode(
        id = template.id,
        depth = template.depth,
        sourceStartNorm = sourceStartNorm,
        sourceEndNorm = sourceEndNorm,
        splitNorm = splitNorm,
        polynomial = template.polynomial,
        stopReason = template.stopReason
    )
    node.left = template.left?.let { buildIncrementalNode(it, baseLastIndex) }
    node.right = template.right?.let { buildIncrementalNode(it, baseLastIndex) }
    return node
}

private fun collectIncrementalNodesPreOrder(root: MutableIncrementalTreeNode): List<MutableIncrementalTreeNode> {
    val out = ArrayList<MutableIncrementalTreeNode>()
    fun walk(node: MutableIncrementalTreeNode) {
        out += node
        node.left?.let(::walk)
        node.right?.let(::walk)
    }
    walk(root)
    return out
}

private fun chooseSequentialOwnerIncremental(
    pointIndex: Int,
    rankedByDistance: List<MutableLeafDistance>
): MutableIncrementalTreeNode {
    val inRange = rankedByDistance.filter { pointIndex in it.leaf.currentSourceRange() }
    return if (inRange.isNotEmpty()) inRange.minBy { it.distance }.leaf else rankedByDistance.first().leaf
}

private fun freezeIncrementalNode(
    node: MutableIncrementalTreeNode,
    pointCount: Int
): PolynomialTreeNode {
    val supportIndices = IntArray(node.supportCount) { node.supportStart + it }
    val splitIndex = node.splitNorm?.let { norm ->
        val last = (pointCount - 1).coerceAtLeast(0)
        if (pointCount <= 1) 0 else (norm * last.toDouble()).roundToInt().coerceIn(0, last)
    }
    val claimed = node.claimedIndices.copyOf(node.claimedCount)

    return PolynomialTreeNode(
        id = node.id,
        depth = node.depth,
        sourceRange = node.currentSourceRange(),
        supportIndices = supportIndices,
        polynomial = node.polynomial,
        supportRmsError = node.supportRmsError,
        supportMeanError = node.supportMeanError,
        supportMaxError = node.supportMaxError,
        splitPointIndex = splitIndex,
        stopReason = node.stopReason,
        claimedIndices = claimed,
        claimedRmsError = node.claimedRmsError,
        claimedMaxError = node.claimedMaxError,
        left = node.left?.let { freezeIncrementalNode(it, pointCount) },
        right = node.right?.let { freezeIncrementalNode(it, pointCount) }
    )
}

private fun mapNormalizedRangeToIndices(
    startNorm: Double,
    endNorm: Double,
    pointCount: Int
): IntRange {
    if (pointCount <= 1) return 0..0
    val last = pointCount - 1

    var start = floor(startNorm.coerceIn(0.0, 1.0) * last.toDouble()).toInt()
    var end = ceil(endNorm.coerceIn(0.0, 1.0) * last.toDouble()).toInt()
    start = start.coerceIn(0, last)
    end = end.coerceIn(0, last)
    if (end < start) end = start

    if (start == end) {
        if (end < last) end++
        else if (start > 0) start--
    }
    return start..end
}

private fun computeErrorsIntoBuffers(
    points: List<Vec3>,
    startIndex: Int,
    endIndex: Int,
    polynomial: Polynomial3Curve,
    distanceSamples: Int,
    refinementSteps: Int,
    errorsBuffer: DoubleArray,
    nearestTBuffer: DoubleArray
): MutableRangeErrorStats {
    val count = (endIndex - startIndex + 1).coerceAtLeast(1)
    var sum = 0.0
    var sumSq = 0.0
    var maxError = Double.NEGATIVE_INFINITY
    var maxLocal = 0

    var local = 0
    for (pointIndex in startIndex..endIndex) {
        val probe = closestDistance(
            polynomial = polynomial,
            point = points[pointIndex],
            distanceSamples = distanceSamples,
            refinementSteps = refinementSteps
        )
        val error = sqrt(probe.distanceSq)
        errorsBuffer[local] = error
        nearestTBuffer[local] = probe.t

        sum += error
        sumSq += error * error
        if (error > maxError) {
            maxError = error
            maxLocal = local
        }
        local++
    }

    if (maxError == Double.NEGATIVE_INFINITY) maxError = 0.0
    val mean = sum / count.toDouble()
    val rms = sqrt(sumSq / count.toDouble())
    return MutableRangeErrorStats(
        count = count,
        rms = rms,
        mean = mean,
        max = maxError,
        maxLocalIndex = maxLocal
    )
}

private fun buildSequentialSamplesFromRange(
    points: List<Vec3>,
    startIndex: Int,
    endIndex: Int,
    destination: MutableList<WeightedSample>
) {
    destination.clear()
    if (endIndex <= startIndex) {
        destination += WeightedSample(t = 0.0, point = points[startIndex], weight = 1.0)
        return
    }

    val count = endIndex - startIndex + 1
    val cumulative = DoubleArray(count)
    var length = 0.0
    cumulative[0] = 0.0
    for (i in 1 until count) {
        val a = points[startIndex + i - 1]
        val b = points[startIndex + i]
        length += distance(a, b)
        cumulative[i] = length
    }

    if (length <= 1e-9) {
        val denom = (count - 1).toDouble().coerceAtLeast(1.0)
        for (i in 0 until count) {
            destination += WeightedSample(
                t = i.toDouble() / denom,
                point = points[startIndex + i],
                weight = 1.0
            )
        }
    } else {
        for (i in 0 until count) {
            destination += WeightedSample(
                t = cumulative[i] / length,
                point = points[startIndex + i],
                weight = 1.0
            )
        }
    }
}

private fun nextCapacity(current: Int, required: Int): Int {
    if (required <= current) return current
    if (current <= 0) return required.coerceAtLeast(4)
    var cap = current
    while (cap < required) cap = cap * 2
    return cap
}

private data class LeafPolylineRecord(
    val leaf: PolynomialTreeNode,
    val points: List<Vec3>
)

private fun buildLeafFLines(
    leaves: List<PolynomialTreeNode>,
    render: PolynomialLeafFLineRenderConfig
): List<FLine> {
    if (leaves.isEmpty()) return emptyList()

    val orderedLeaves = orderLeaves(leaves, render.orderMode)
    val samplesPerLeaf = render.samplesPerLeaf.coerceAtLeast(2)

    val basePolylines = ArrayList<LeafPolylineRecord>(orderedLeaves.size)
    for (leaf in orderedLeaves) {
        var points = leaf.polynomial.sample(samples = samplesPerLeaf)
        points = applyGapTrim(points, render.gap)
        points = applyOvershoot(points, render.overshoot)
        if (points.size >= 2 && polylineLength(points) > 1e-9) {
            basePolylines += LeafPolylineRecord(leaf = leaf, points = points)
        }
    }

    if (basePolylines.isEmpty()) return emptyList()

    val passes = render.sketch.passes.coerceAtLeast(1)
    return when (render.outputMode) {
        PolynomialLeafFLineOutputMode.SEPARATE_LINES -> {
            val out = ArrayList<FLine>(basePolylines.size * passes)
            for (pass in 0 until passes) {
                for (polyline in basePolylines) {
                    val jittered = applySketchJitter(polyline.points, render.sketch, polyline.leaf.id, pass)
                    out += polylineToFLine(jittered)
                }
            }
            out
        }

        PolynomialLeafFLineOutputMode.SINGLE_MULTIPATH -> {
            val out = ArrayList<FLine>(passes)
            for (pass in 0 until passes) {
                val line = combinePolylines(
                    polylines = basePolylines.map {
                        applySketchJitter(it.points, render.sketch, it.leaf.id, pass)
                    },
                    connected = false
                )
                if (line.nodes.isNotEmpty()) out += line
            }
            out
        }

        PolynomialLeafFLineOutputMode.SINGLE_CONNECTED -> {
            val out = ArrayList<FLine>(passes)
            for (pass in 0 until passes) {
                val line = combinePolylines(
                    polylines = basePolylines.map {
                        applySketchJitter(it.points, render.sketch, it.leaf.id, pass)
                    },
                    connected = true
                )
                if (line.nodes.isNotEmpty()) out += line
            }
            out
        }
    }
}

private fun orderLeaves(
    leaves: List<PolynomialTreeNode>,
    orderMode: PolynomialLeafOrderMode
): List<PolynomialTreeNode> {
    return when (orderMode) {
        PolynomialLeafOrderMode.SOURCE_ASCENDING ->
            leaves.sortedWith(compareBy<PolynomialTreeNode> { it.sourceRange.first }.thenBy { it.id })

        PolynomialLeafOrderMode.SOURCE_DESCENDING ->
            leaves.sortedWith(compareByDescending<PolynomialTreeNode> { it.sourceRange.first }.thenByDescending { it.id })

        PolynomialLeafOrderMode.ERROR_DESCENDING ->
            leaves.sortedWith(compareByDescending<PolynomialTreeNode> { it.supportMaxError }.thenBy { it.sourceRange.first })

        PolynomialLeafOrderMode.ERROR_ASCENDING ->
            leaves.sortedWith(compareBy<PolynomialTreeNode> { it.supportMaxError }.thenBy { it.sourceRange.first })

        PolynomialLeafOrderMode.DEPTH_ASCENDING ->
            leaves.sortedWith(compareBy<PolynomialTreeNode> { it.depth }.thenBy { it.sourceRange.first })

        PolynomialLeafOrderMode.DEPTH_DESCENDING ->
            leaves.sortedWith(compareByDescending<PolynomialTreeNode> { it.depth }.thenBy { it.sourceRange.first })
    }
}

private fun applyGapTrim(points: List<Vec3>, gap: PolynomialLeafGapStyle): List<Vec3> {
    val copy = points.map(::Vec3)
    if (!gap.enabled || copy.size < 2) return copy

    val span = distance(copy.first(), copy.last())
    var trim = gap.absolute + span * gap.fractionOfSpan
    trim = trim.coerceAtLeast(gap.minAbsolute).coerceAtMost(gap.maxAbsolute)

    val startTrim = if (gap.trimStart) trim else 0.0
    val endTrim = if (gap.trimEnd) trim else 0.0
    if (startTrim <= 1e-9 && endTrim <= 1e-9) return copy

    return trimPolyline(copy, startTrim, endTrim)
}

private fun applyOvershoot(points: List<Vec3>, overshoot: PolynomialLeafOvershootStyle): List<Vec3> {
    val out = points.map(::Vec3).toMutableList()
    if (!overshoot.enabled || out.size < 2) return out

    val span = distance(out.first(), out.last())
    var startDistance = overshoot.startAbsolute + span * overshoot.startFractionOfSpan
    var endDistance = overshoot.endAbsolute + span * overshoot.endFractionOfSpan
    startDistance = startDistance.coerceAtLeast(0.0).coerceAtMost(overshoot.maxAbsolute)
    endDistance = endDistance.coerceAtLeast(0.0).coerceAtMost(overshoot.maxAbsolute)

    if (startDistance > 1e-9) {
        val dir = directionFromStart(out)
        if (dir != null) {
            out[0] = Vec3(out[0]).sub(Vec3(dir).mul(startDistance.toFloat()))
        }
    }

    if (endDistance > 1e-9) {
        val dir = directionFromEnd(out)
        if (dir != null) {
            out[out.lastIndex] = Vec3(out.last()).add(Vec3(dir).mul(endDistance.toFloat()))
        }
    }

    return out
}

private fun applySketchJitter(
    points: List<Vec3>,
    sketch: PolynomialLeafSketchStyle,
    leafId: Int,
    pass: Int
): List<Vec3> {
    val amplitude = sketch.jitterAmplitude.coerceAtLeast(0.0)
    if (amplitude <= 0.0) return points.map(::Vec3)

    val random = Random(mixSeed(sketch.seed, leafId, pass))
    val alongFactor = sketch.alongTangentFactor.coerceAtLeast(0.0)
    val out = ArrayList<Vec3>(points.size)

    for (i in points.indices) {
        val isEndpoint = i == 0 || i == points.lastIndex
        if (isEndpoint && sketch.keepEndpoints) {
            out += Vec3(points[i])
            continue
        }

        val tangent = estimateTangent(points, i)
        val normal = randomUnitPerpendicular(tangent, random)

        val offMag = signedUnitRandom(random) * amplitude
        val alongMag = signedUnitRandom(random) * amplitude * alongFactor

        val jitter = Vec3(normal).mul(offMag.toFloat()).add(Vec3(tangent).mul(alongMag.toFloat()))
        out += Vec3(points[i]).add(jitter)
    }

    return out
}

private fun mixSeed(seed: Long, leafId: Int, pass: Int): Long {
    var x = seed
    x = x xor (leafId.toLong() * -7046029254386353131L)
    x = x xor (pass.toLong() * -4417276706812531889L)
    return x
}

private fun signedUnitRandom(random: Random): Double = random.nextDouble() * 2.0 - 1.0

private fun estimateTangent(points: List<Vec3>, index: Int): Vec3 {
    if (points.size < 2) return vec(1f, 0f, 0f)

    val last = points.lastIndex
    val prev = points[max(0, index - 1)]
    val next = points[min(last, index + 1)]
    val tangent = Vec3(next).sub(prev)

    if (tangent.lengthSquared() < 1e-12f && index > 0) {
        tangent.set(Vec3(points[index]).sub(points[index - 1]))
    }
    if (tangent.lengthSquared() < 1e-12f && index < last) {
        tangent.set(Vec3(points[index + 1]).sub(points[index]))
    }
    if (tangent.lengthSquared() < 1e-12f) {
        tangent.set(1f, 0f, 0f)
    } else {
        tangent.normalize()
    }
    return tangent
}

private fun randomUnitPerpendicular(tangentInput: Vec3, random: Random): Vec3 {
    val tangent = Vec3(tangentInput)
    if (tangent.lengthSquared() < 1e-12f) return vec(0f, 1f, 0f)
    tangent.normalize()

    val randomVec = vec(
        signedUnitRandom(random).toFloat(),
        signedUnitRandom(random).toFloat(),
        signedUnitRandom(random).toFloat()
    )
    val projected = Vec3(tangent).mul(randomVec.dot(tangent))
    val normal = Vec3(randomVec).sub(projected)

    if (normal.lengthSquared() < 1e-12f) {
        val axis = if (abs(tangent.x) < 0.8f) vec(1f, 0f, 0f) else vec(0f, 1f, 0f)
        normal.set(Vec3(tangent).cross(axis, Vec3()))
    }
    if (normal.lengthSquared() < 1e-12f) {
        normal.set(0f, 0f, 1f)
    } else {
        normal.normalize()
    }
    return normal
}

private fun directionFromStart(points: List<Vec3>): Vec3? {
    for (i in 1 until points.size) {
        val d = Vec3(points[i]).sub(points[i - 1])
        if (d.lengthSquared() > 1e-12f) return d.normalize()
    }
    return null
}

private fun directionFromEnd(points: List<Vec3>): Vec3? {
    for (i in points.lastIndex downTo 1) {
        val d = Vec3(points[i]).sub(points[i - 1])
        if (d.lengthSquared() > 1e-12f) return d.normalize()
    }
    return null
}

private fun trimPolyline(points: List<Vec3>, startTrim: Double, endTrim: Double): List<Vec3> {
    if (points.size < 2) return points.map(::Vec3)

    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
    }
    val totalLength = cumulative.last()
    if (totalLength <= 1e-9) return points.map(::Vec3)

    val startDistance = startTrim.coerceIn(0.0, totalLength)
    val endDistance = (totalLength - endTrim).coerceIn(0.0, totalLength)

    if (endDistance <= startDistance + 1e-9) {
        val mid = (startDistance + endDistance) * 0.5
        val p = pointAtDistance(points, cumulative, mid)
        return listOf(p, Vec3(p))
    }

    val startPoint = pointAtDistance(points, cumulative, startDistance)
    val endPoint = pointAtDistance(points, cumulative, endDistance)

    val out = ArrayList<Vec3>(points.size)
    out += startPoint
    for (i in 1 until points.lastIndex) {
        val d = cumulative[i]
        if (d > startDistance && d < endDistance) out += Vec3(points[i])
    }
    out += endPoint
    if (out.size < 2) out += Vec3(endPoint)
    return out
}

private fun pointAtDistance(points: List<Vec3>, cumulative: DoubleArray, distanceAlong: Double): Vec3 {
    val total = cumulative.last()
    val d = distanceAlong.coerceIn(0.0, total)
    if (d <= 0.0) return Vec3(points.first())
    if (d >= total) return Vec3(points.last())

    var hi = cumulative.binarySearch(d)
    if (hi >= 0) return Vec3(points[hi])
    hi = -hi - 1
    val lo = (hi - 1).coerceAtLeast(0)

    val d0 = cumulative[lo]
    val d1 = cumulative[hi]
    if (d1 - d0 <= 1e-9) return Vec3(points[lo])

    val alpha = ((d - d0) / (d1 - d0)).toFloat()
    val p0 = points[lo]
    val p1 = points[hi]
    return vec(
        p0.x + (p1.x - p0.x) * alpha,
        p0.y + (p1.y - p0.y) * alpha,
        p0.z + (p1.z - p0.z) * alpha
    )
}

private fun combinePolylines(polylines: List<List<Vec3>>, connected: Boolean): FLine {
    val out = FLine()
    var started = false

    for (points in polylines) {
        if (points.isEmpty()) continue
        if (!started) {
            out.moveTo(points.first())
            started = true
        } else if (connected) {
            out.lineTo(points.first())
        } else {
            out.moveTo(points.first())
        }
        for (i in 1 until points.size) out.lineTo(points[i])
    }

    return out
}

private fun polylineToFLine(points: List<Vec3>): FLine {
    val line = FLine()
    if (points.isEmpty()) return line
    line.moveTo(points.first())
    for (i in 1 until points.size) line.lineTo(points[i])
    return line
}

private fun polylineLength(points: List<Vec3>): Double {
    if (points.size < 2) return 0.0
    var len = 0.0
    for (i in 1 until points.size) {
        len += distance(points[i - 1], points[i])
    }
    return len
}

private data class WeightedSample(val t: Double, val point: Vec3, val weight: Double = 1.0)

private data class ClosestDistanceProbe(val t: Double, val distanceSq: Double)

private data class ErrorStats(
    val errors: DoubleArray,
    val nearestT: DoubleArray,
    val rms: Double,
    val mean: Double,
    val max: Double,
    val maxLocalIndex: Int
)

private data class GlobalSummary(
    val errors: DoubleArray,
    val nearestLeafIds: IntArray,
    val rms: Double,
    val mean: Double,
    val max: Double
)

private data class BuildState(
    var nextId: Int,
    var splitsRemaining: Int
)

private class MutableTreeNode(
    val id: Int,
    val depth: Int,
    val sourceRange: IntRange,
    val supportIndices: IntArray,
    var polynomial: Polynomial3Curve,
    var supportErrors: ErrorStats,
    var splitPointIndex: Int? = null,
    var stopReason: String,
    var claimedIndices: IntArray = IntArray(0),
    var claimedRmsError: Double? = null,
    var claimedMaxError: Double? = null,
    var left: MutableTreeNode? = null,
    var right: MutableTreeNode? = null
) {
    fun freeze(): PolynomialTreeNode = PolynomialTreeNode(
        id = id,
        depth = depth,
        sourceRange = sourceRange,
        supportIndices = supportIndices.copyOf(),
        polynomial = polynomial,
        supportRmsError = supportErrors.rms,
        supportMeanError = supportErrors.mean,
        supportMaxError = supportErrors.max,
        splitPointIndex = splitPointIndex,
        stopReason = stopReason,
        claimedIndices = claimedIndices.copyOf(),
        claimedRmsError = claimedRmsError,
        claimedMaxError = claimedMaxError,
        left = left?.freeze(),
        right = right?.freeze()
    )
}

private data class LeafDistance(
    val leaf: MutableTreeNode,
    val distance: Double
)

private fun buildNode(
    points: List<Vec3>,
    supportIndices: IntArray,
    depth: Int,
    config: PolynomialTreeFitConfig,
    state: BuildState
): MutableTreeNode {
    val id = state.nextId++
    val samples = buildSequentialSamples(points, supportIndices)
    val polynomial = fitPolynomialCurve(
        samples = samples,
        requestedOrder = config.order,
        regularization = config.regularization
    )
    val supportErrors = computeErrors(
        points = points,
        supportIndices = supportIndices,
        polynomial = polynomial,
        distanceSamples = config.distanceSamples,
        refinementSteps = config.distanceRefinementSteps
    )
    val node = MutableTreeNode(
        id = id,
        depth = depth,
        sourceRange = supportIndices.first()..supportIndices.last(),
        supportIndices = supportIndices,
        polynomial = polynomial,
        supportErrors = supportErrors,
        stopReason = ""
    )

    val minPoints = config.minPointsPerLeaf
    if (depth >= config.maxDepth) {
        node.stopReason = "maxDepth"
        return node
    }
    if (state.splitsRemaining <= 0) {
        node.stopReason = "maxLeavesReached"
        return node
    }
    if (supportIndices.size < (2 * minPoints - 1)) {
        node.stopReason = "insufficientPointsToSplit"
        return node
    }

    val splitContext = PolynomialTreeSplitContext(
        points = points,
        depth = depth,
        supportIndices = supportIndices,
        supportRange = supportIndices.first()..supportIndices.last(),
        pointErrors = supportErrors.errors,
        nearestT = supportErrors.nearestT,
        rmsError = supportErrors.rms,
        meanError = supportErrors.mean,
        maxError = supportErrors.max,
        maxErrorLocalIndex = supportErrors.maxLocalIndex
    )

    if (config.stopCriterion.shouldStop(PolynomialTreeStopContext(split = splitContext, config = config))) {
        node.stopReason = "errorThreshold"
        return node
    }

    val requestedSplit = config.splitCriterion.chooseSplit(splitContext)
    val splitLocal = chooseValidSplitLocal(
        requestedSplit = requestedSplit,
        context = splitContext,
        minPointsPerLeaf = minPoints
    )
    if (splitLocal == null) {
        node.stopReason = "splitCriterionDeclined"
        return node
    }

    val splitGlobal = supportIndices[splitLocal]
    val leftIndices = supportIndices.copyOfRange(0, splitLocal + 1)
    val rightIndices = supportIndices.copyOfRange(splitLocal, supportIndices.size)
    if (leftIndices.size < minPoints || rightIndices.size < minPoints) {
        node.stopReason = "splitInvalid"
        return node
    }

    state.splitsRemaining--
    node.splitPointIndex = splitGlobal
    node.stopReason = "split"
    node.left = buildNode(points, leftIndices, depth + 1, config, state)
    node.right = buildNode(points, rightIndices, depth + 1, config, state)
    return node
}

private fun chooseValidSplitLocal(
    requestedSplit: Int?,
    context: PolynomialTreeSplitContext,
    minPointsPerLeaf: Int
): Int? {
    val minLocal = minPointsPerLeaf - 1
    val maxLocal = context.supportIndices.size - minPointsPerLeaf
    if (maxLocal < minLocal) return null

    val candidates = ArrayList<Int>(4)
    if (requestedSplit != null) {
        val local = context.localIndexOf(requestedSplit)
        if (local >= 0) candidates += local
    }
    candidates += context.maxErrorLocalIndex
    candidates += context.middleLocalIndex()
    candidates += ((minLocal + maxLocal) / 2)

    for (candidate in candidates) {
        val clamped = candidate.coerceIn(minLocal, maxLocal)
        if (clamped in minLocal..maxLocal) return clamped
    }
    return null
}

private fun applyNonSequentialClaims(
    root: MutableTreeNode,
    points: List<Vec3>,
    config: PolynomialTreeFitConfig
) {
    val claimCfg = config.claims
    if (!claimCfg.enabled) return
    if (claimCfg.maxClaimsPerPoint <= 0 || claimCfg.maxClaimsPerLeaf <= 0) return

    val leaves = collectLeaves(root)
    if (leaves.size < 2) return

    val claimsByLeaf = leaves.associate { it.id to linkedSetOf<Int>() }.toMutableMap()
    for (pointIndex in points.indices) {
        val distances = leaves.map { leaf ->
            val probe = closestDistance(
                polynomial = leaf.polynomial,
                point = points[pointIndex],
                distanceSamples = config.distanceSamples,
                refinementSteps = config.distanceRefinementSteps
            )
            LeafDistance(leaf, sqrt(probe.distanceSq))
        }.sortedBy { it.distance }
        if (distances.isEmpty()) continue

        val ownerLeaf = chooseSequentialOwner(pointIndex, distances)
        val ownerDistance = distances.firstOrNull { it.leaf.id == ownerLeaf.id }?.distance ?: distances.first().distance
        val bestDistance = distances.first().distance

        var acceptedForPoint = 0
        for ((rank, leafDistance) in distances.withIndex()) {
            if (!claimCfg.includeSequentialOwner && leafDistance.leaf.id == ownerLeaf.id) continue
            if (acceptedForPoint >= claimCfg.maxClaimsPerPoint) break

            val claimSet = claimsByLeaf[leafDistance.leaf.id] ?: continue
            if (claimSet.size >= claimCfg.maxClaimsPerLeaf) continue

            val claimContext = PolynomialTreeClaimContext(
                pointIndex = pointIndex,
                point = points[pointIndex],
                leafId = leafDistance.leaf.id,
                leafRange = leafDistance.leaf.sourceRange,
                distance = leafDistance.distance,
                rankByDistance = rank,
                ownerLeafId = ownerLeaf.id,
                ownerDistance = ownerDistance,
                bestDistance = bestDistance
            )

            if (claimCfg.criterion.accepts(claimContext)) {
                claimSet += pointIndex
                acceptedForPoint++
            }
        }
    }

    for (leaf in leaves) {
        val claimed = claimsByLeaf[leaf.id]
            ?.filterNot { it in leaf.sourceRange }
            ?.sorted()
            ?.toIntArray()
            ?: IntArray(0)

        leaf.claimedIndices = claimed
        if (claimed.isEmpty()) {
            leaf.claimedRmsError = null
            leaf.claimedMaxError = null
            continue
        }

        if (claimCfg.refitLeaves && claimCfg.claimRefitWeight > 0.0) {
            refitLeafWithClaims(
                leaf = leaf,
                points = points,
                config = config
            )
        }

        val claimErrors = claimed.map { idx ->
            val probe = closestDistance(
                polynomial = leaf.polynomial,
                point = points[idx],
                distanceSamples = config.distanceSamples,
                refinementSteps = config.distanceRefinementSteps
            )
            sqrt(probe.distanceSq)
        }
        leaf.claimedMaxError = claimErrors.maxOrNull()
        leaf.claimedRmsError = if (claimErrors.isEmpty()) null else {
            sqrt(claimErrors.sumOf { it * it } / claimErrors.size.toDouble())
        }
    }
}

private fun chooseSequentialOwner(pointIndex: Int, rankedByDistance: List<LeafDistance>): MutableTreeNode {
    val inRange = rankedByDistance.filter { pointIndex in it.leaf.sourceRange }
    return if (inRange.isNotEmpty()) inRange.minBy { it.distance }.leaf else rankedByDistance.first().leaf
}

private fun refitLeafWithClaims(
    leaf: MutableTreeNode,
    points: List<Vec3>,
    config: PolynomialTreeFitConfig
) {
    if (leaf.claimedIndices.isEmpty()) return

    val samples = ArrayList<WeightedSample>()
    samples += buildSequentialSamples(points, leaf.supportIndices)
    for (claimedIndex in leaf.claimedIndices) {
        val probe = closestDistance(
            polynomial = leaf.polynomial,
            point = points[claimedIndex],
            distanceSamples = config.distanceSamples,
            refinementSteps = config.distanceRefinementSteps
        )
        samples += WeightedSample(
            t = probe.t,
            point = points[claimedIndex],
            weight = config.claims.claimRefitWeight
        )
    }
    leaf.polynomial = fitPolynomialCurve(
        samples = samples,
        requestedOrder = config.order,
        regularization = config.regularization
    )
    leaf.supportErrors = computeErrors(
        points = points,
        supportIndices = leaf.supportIndices,
        polynomial = leaf.polynomial,
        distanceSamples = config.distanceSamples,
        refinementSteps = config.distanceRefinementSteps
    )
}

private fun collectLeaves(root: MutableTreeNode): List<MutableTreeNode> {
    val out = ArrayList<MutableTreeNode>()
    fun walk(node: MutableTreeNode) {
        val left = node.left
        val right = node.right
        if (left == null && right == null) out += node
        else {
            left?.let(::walk)
            right?.let(::walk)
        }
    }
    walk(root)
    return out
}

private fun computeGlobalSummary(
    points: List<Vec3>,
    leaves: List<MutableTreeNode>,
    config: PolynomialTreeFitConfig
): GlobalSummary {
    val errors = DoubleArray(points.size)
    val nearestLeaf = IntArray(points.size)

    var sum = 0.0
    var sumSq = 0.0
    var maxError = 0.0

    for (i in points.indices) {
        var bestLeafId = leaves.firstOrNull()?.id ?: -1
        var bestDistance = Double.POSITIVE_INFINITY

        for (leaf in leaves) {
            val probe = closestDistance(
                polynomial = leaf.polynomial,
                point = points[i],
                distanceSamples = config.distanceSamples,
                refinementSteps = config.distanceRefinementSteps
            )
            val distance = sqrt(probe.distanceSq)
            if (distance < bestDistance) {
                bestDistance = distance
                bestLeafId = leaf.id
            }
        }

        errors[i] = bestDistance
        nearestLeaf[i] = bestLeafId
        sum += bestDistance
        sumSq += bestDistance * bestDistance
        maxError = max(maxError, bestDistance)
    }

    val mean = if (errors.isEmpty()) 0.0 else sum / errors.size.toDouble()
    val rms = if (errors.isEmpty()) 0.0 else sqrt(sumSq / errors.size.toDouble())
    return GlobalSummary(
        errors = errors,
        nearestLeafIds = nearestLeaf,
        rms = rms,
        mean = mean,
        max = maxError
    )
}

private fun computeErrors(
    points: List<Vec3>,
    supportIndices: IntArray,
    polynomial: Polynomial3Curve,
    distanceSamples: Int,
    refinementSteps: Int
): ErrorStats {
    val errors = DoubleArray(supportIndices.size)
    val nearestT = DoubleArray(supportIndices.size)

    var sum = 0.0
    var sumSq = 0.0
    var maxError = Double.NEGATIVE_INFINITY
    var maxLocal = 0

    for (local in supportIndices.indices) {
        val point = points[supportIndices[local]]
        val probe = closestDistance(
            polynomial = polynomial,
            point = point,
            distanceSamples = distanceSamples,
            refinementSteps = refinementSteps
        )
        val error = sqrt(probe.distanceSq)
        errors[local] = error
        nearestT[local] = probe.t

        sum += error
        sumSq += error * error
        if (error > maxError) {
            maxError = error
            maxLocal = local
        }
    }

    if (maxError == Double.NEGATIVE_INFINITY) maxError = 0.0
    val mean = if (errors.isEmpty()) 0.0 else sum / errors.size.toDouble()
    val rms = if (errors.isEmpty()) 0.0 else sqrt(sumSq / errors.size.toDouble())
    return ErrorStats(
        errors = errors,
        nearestT = nearestT,
        rms = rms,
        mean = mean,
        max = maxError,
        maxLocalIndex = maxLocal
    )
}

private fun buildSequentialSamples(points: List<Vec3>, supportIndices: IntArray): List<WeightedSample> {
    if (supportIndices.isEmpty()) return emptyList()
    if (supportIndices.size == 1) {
        return listOf(WeightedSample(t = 0.0, point = points[supportIndices[0]], weight = 1.0))
    }

    val cumulative = DoubleArray(supportIndices.size)
    var length = 0.0
    cumulative[0] = 0.0
    for (i in 1 until supportIndices.size) {
        val a = points[supportIndices[i - 1]]
        val b = points[supportIndices[i]]
        length += distance(a, b)
        cumulative[i] = length
    }

    val out = ArrayList<WeightedSample>(supportIndices.size)
    if (length <= 1e-9) {
        for (i in supportIndices.indices) {
            val t = i.toDouble() / supportIndices.lastIndex.toDouble().coerceAtLeast(1.0)
            out += WeightedSample(t = t, point = points[supportIndices[i]], weight = 1.0)
        }
    } else {
        for (i in supportIndices.indices) {
            out += WeightedSample(
                t = cumulative[i] / length,
                point = points[supportIndices[i]],
                weight = 1.0
            )
        }
    }
    return out
}

private fun fitPolynomialCurve(
    samples: List<WeightedSample>,
    requestedOrder: Int,
    regularization: Double
): Polynomial3Curve {
    if (samples.isEmpty()) {
        return Polynomial3Curve(
            xCoefficients = doubleArrayOf(0.0, 0.0),
            yCoefficients = doubleArrayOf(0.0, 0.0),
            zCoefficients = doubleArrayOf(0.0, 0.0)
        )
    }

    val maxFittableOrder = (samples.size - 1).coerceAtLeast(1)
    var activeOrder = requestedOrder.coerceAtMost(maxFittableOrder).coerceAtLeast(1)

    while (activeOrder >= 1) {
        val solved = solvePolynomialOrder(samples, activeOrder, regularization)
        if (solved != null) {
            return Polynomial3Curve(
                xCoefficients = promoteCoefficients(solved.first, requestedOrder),
                yCoefficients = promoteCoefficients(solved.second, requestedOrder),
                zCoefficients = promoteCoefficients(solved.third, requestedOrder)
            )
        }
        activeOrder--
    }

    val first = samples.first()
    val last = samples.last()
    val dt = (last.t - first.t)
    val inv = if (kotlin.math.abs(dt) > 1e-9) 1.0 / dt else 0.0
    val dx = (last.point.x - first.point.x).toDouble() * inv
    val dy = (last.point.y - first.point.y).toDouble() * inv
    val dz = (last.point.z - first.point.z).toDouble() * inv
    val x0 = first.point.x.toDouble() - dx * first.t
    val y0 = first.point.y.toDouble() - dy * first.t
    val z0 = first.point.z.toDouble() - dz * first.t

    val x = DoubleArray(requestedOrder + 1)
    val y = DoubleArray(requestedOrder + 1)
    val z = DoubleArray(requestedOrder + 1)
    x[0] = x0
    y[0] = y0
    z[0] = z0
    x[1] = dx
    y[1] = dy
    z[1] = dz
    return Polynomial3Curve(x, y, z)
}

private fun solvePolynomialOrder(
    samples: List<WeightedSample>,
    order: Int,
    regularization: Double
): Triple<DoubleArray, DoubleArray, DoubleArray>? {
    val terms = order + 1
    val normal = Array(terms) { DoubleArray(terms) }
    val bx = DoubleArray(terms)
    val by = DoubleArray(terms)
    val bz = DoubleArray(terms)

    for (sample in samples) {
        val t = sample.t.coerceIn(0.0, 1.0)
        val w = sample.weight.coerceAtLeast(0.0)
        val powers = DoubleArray(2 * order + 1)
        powers[0] = 1.0
        for (i in 1 until powers.size) powers[i] = powers[i - 1] * t

        for (i in 0 until terms) {
            val wi = w * powers[i]
            bx[i] += wi * sample.point.x.toDouble()
            by[i] += wi * sample.point.y.toDouble()
            bz[i] += wi * sample.point.z.toDouble()

            for (j in 0 until terms) {
                normal[i][j] += w * powers[i + j]
            }
        }
    }

    for (i in 0 until terms) {
        normal[i][i] += regularization
    }

    val x = solveLinearSystem(copyMatrix(normal), bx) ?: return null
    val y = solveLinearSystem(copyMatrix(normal), by) ?: return null
    val z = solveLinearSystem(copyMatrix(normal), bz) ?: return null
    return Triple(x, y, z)
}

private fun closestDistance(
    polynomial: Polynomial3Curve,
    point: Vec3,
    distanceSamples: Int,
    refinementSteps: Int
): ClosestDistanceProbe {
    val samples = distanceSamples.coerceAtLeast(4)
    val step = 1.0 / (samples - 1).toDouble()

    var bestT = 0.0
    var bestSq = Double.POSITIVE_INFINITY
    for (i in 0 until samples) {
        val t = i * step
        val sq = squaredDistanceAt(polynomial, point, t)
        if (sq < bestSq) {
            bestSq = sq
            bestT = t
        }
    }

    var left = max(0.0, bestT - step)
    var right = min(1.0, bestT + step)
    repeat(refinementSteps.coerceAtLeast(0)) {
        val t1 = left + (right - left) / 3.0
        val t2 = right - (right - left) / 3.0
        val d1 = squaredDistanceAt(polynomial, point, t1)
        val d2 = squaredDistanceAt(polynomial, point, t2)
        if (d1 <= d2) right = t2 else left = t1
    }

    val refinedT = (left + right) * 0.5
    val refinedSq = squaredDistanceAt(polynomial, point, refinedT)
    return if (refinedSq < bestSq) {
        ClosestDistanceProbe(t = refinedT, distanceSq = refinedSq)
    } else {
        ClosestDistanceProbe(t = bestT, distanceSq = bestSq)
    }
}

private fun squaredDistanceAt(polynomial: Polynomial3Curve, point: Vec3, t: Double): Double {
    val tt = t.coerceIn(0.0, 1.0)
    val dx = evalPolynomial(polynomial.xCoefficients, tt) - point.x.toDouble()
    val dy = evalPolynomial(polynomial.yCoefficients, tt) - point.y.toDouble()
    val dz = evalPolynomial(polynomial.zCoefficients, tt) - point.z.toDouble()
    return dx * dx + dy * dy + dz * dz
}

private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val n = matrix.size
    if (rhs.size != n) return null

    val b = rhs.copyOf()
    for (pivot in 0 until n) {
        var maxRow = pivot
        var maxValue = kotlin.math.abs(matrix[pivot][pivot])
        for (row in pivot + 1 until n) {
            val value = kotlin.math.abs(matrix[row][pivot])
            if (value > maxValue) {
                maxValue = value
                maxRow = row
            }
        }
        if (maxValue < 1e-12) return null

        if (maxRow != pivot) {
            val tmp = matrix[pivot]
            matrix[pivot] = matrix[maxRow]
            matrix[maxRow] = tmp
            val tb = b[pivot]
            b[pivot] = b[maxRow]
            b[maxRow] = tb
        }

        val diag = matrix[pivot][pivot]
        for (col in pivot until n) matrix[pivot][col] /= diag
        b[pivot] /= diag

        for (row in 0 until n) {
            if (row == pivot) continue
            val factor = matrix[row][pivot]
            if (kotlin.math.abs(factor) < 1e-18) continue
            for (col in pivot until n) {
                matrix[row][col] -= factor * matrix[pivot][col]
            }
            b[row] -= factor * b[pivot]
        }
    }
    return b
}

private fun copyMatrix(source: Array<DoubleArray>): Array<DoubleArray> {
    return Array(source.size) { row -> source[row].copyOf() }
}

private fun promoteCoefficients(coefficients: DoubleArray, targetOrder: Int): DoubleArray {
    val out = DoubleArray(targetOrder + 1)
    val limit = min(out.size, coefficients.size)
    for (i in 0 until limit) out[i] = coefficients[i]
    return out
}

private fun evalPolynomial(coefficients: DoubleArray, t: Double): Double {
    var acc = 0.0
    for (i in coefficients.lastIndex downTo 0) {
        acc = acc * t + coefficients[i]
    }
    return acc
}

private fun distance(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt((dx * dx + dy * dy + dz * dz).toDouble())
}

private fun turningAngle(prev: Vec3, cur: Vec3, next: Vec3): Double {
    val ax = (cur.x - prev.x).toDouble()
    val ay = (cur.y - prev.y).toDouble()
    val az = (cur.z - prev.z).toDouble()
    val bx = (next.x - cur.x).toDouble()
    val by = (next.y - cur.y).toDouble()
    val bz = (next.z - cur.z).toDouble()

    val la = sqrt(ax * ax + ay * ay + az * az)
    val lb = sqrt(bx * bx + by * by + bz * bz)
    if (la < 1e-9 || lb < 1e-9) return 0.0

    val cos = ((ax * bx + ay * by + az * bz) / (la * lb)).coerceIn(-1.0, 1.0)
    return acos(cos)
}

