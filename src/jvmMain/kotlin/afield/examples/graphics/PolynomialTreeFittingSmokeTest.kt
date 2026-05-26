package afield.examples.graphics

import afield.graphics.*
import afield.stdlib.Vec3
import afield.stdlib.vec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
FIELD_TEST
id: graphics.polynomial-tree-fitting.smoke
runner: jvm-main
entrypoint: afield.examples.graphics.PolynomialTreeFittingSmokeTest
tags: [smoke, graphics, polynomial, fitting]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "PolynomialTreeFitting smoke tests passed"
*/
/**
 * Smoke tests for polynomial-tree fitting + leaf rendering styles.
 *
 * Covers:
 * - recursive fitting behavior
 * - optional non-sequential claims
 * - converting leaves to FLines with gap/overshoot/sketch options
 */
object PolynomialTreeFittingSmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running PolynomialTreeFitting smoke tests...")
            testFitProducesTreeAndFiniteErrors()
            testClaimsCanCaptureNonSequentialPoints()
            testLeafRenderingStylesAndModes()
            testFLineEntryPoint()
            testBranchControlledLevelTraversal()
            testIncrementalUpdaterKeepsTopologyAndRefits()
            println("PolynomialTreeFitting smoke tests passed")
            true
        } catch (t: Throwable) {
            println("PolynomialTreeFitting smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "PolynomialTreeFitting smoke tests failed" }
    }

    private fun testFitProducesTreeAndFiniteErrors() {
        val points = figureEightRibbonPoints(180)
        val fit = points.fitPolynomialTree3D(
            PolynomialTreeFitConfig(
                order = 3,
                maxDepth = 7,
                maxLeaves = 18,
                minPointsPerLeaf = 8,
                targetRmsError = 0.10,
                targetMaxError = 0.20,
                splitCriterion = PolynomialTreeSplitStrategies.curvatureAware(
                    errorWeight = 0.75,
                    curvatureWeight = 0.25
                ),
                stopCriterion = PolynomialTreeStopCriteria.byThresholds
            )
        )

        require(fit.leaves.isNotEmpty()) { "Expected at least one leaf" }
        require(fit.leaves.size > 1) { "Expected multiple leaves on nontrivial input" }
        require(fit.root.leaves().size == fit.leaves.size) {
            "Leaf count mismatch between root traversal and result cache"
        }
        require(fit.overallRmsError.isFinite() && fit.overallRmsError >= 0.0) {
            "RMS error should be finite and non-negative, got ${fit.overallRmsError}"
        }
        require(fit.overallMaxError.isFinite() && fit.overallMaxError >= 0.0) {
            "Max error should be finite and non-negative, got ${fit.overallMaxError}"
        }
        require(fit.pointErrors.size == points.size) {
            "Expected ${points.size} point errors, got ${fit.pointErrors.size}"
        }
        require(fit.nearestLeafIds.size == points.size) {
            "Expected ${points.size} nearest-leaf ids, got ${fit.nearestLeafIds.size}"
        }
    }

    private fun testClaimsCanCaptureNonSequentialPoints() {
        val points = figureEightRibbonPoints(160)
        val fit = fitPolynomialTree3DFromPoints(
            points = points,
            config = PolynomialTreeFitConfig(
                order = 3,
                maxDepth = 5,
                maxLeaves = 10,
                minPointsPerLeaf = 7,
                splitCriterion = PolynomialTreeSplitStrategies.midpoint(),
                stopCriterion = PolynomialTreeStopCriteria.never,
                claims = PolynomialTreeClaimConfig(
                    enabled = true,
                    criterion = PolynomialTreeClaimCriteria.distanceBelow(Double.POSITIVE_INFINITY),
                    includeSequentialOwner = false,
                    maxClaimsPerPoint = 2,
                    maxClaimsPerLeaf = 256,
                    refitLeaves = true,
                    claimRefitWeight = 0.4
                )
            )
        )

        val claimedCounts = fit.leaves.map { it.claimedIndices.size }
        require(claimedCounts.any { it > 0 }) { "Expected at least one leaf to claim non-sequential points" }

        fit.leaves.forEach { leaf ->
            leaf.claimedIndices.forEach { idx ->
                require(idx in points.indices) { "Claimed index out of bounds: $idx" }
                require(idx !in leaf.sourceRange) {
                    "Claimed index $idx should not be inside leaf sequential source range ${leaf.sourceRange}"
                }
            }
            val rms = leaf.claimedRmsError
            val max = leaf.claimedMaxError
            if (leaf.claimedIndices.isNotEmpty()) {
                require(rms != null && rms.isFinite()) { "Claim RMS should be finite for claimed leaf ${leaf.id}" }
                require(max != null && max.isFinite()) { "Claim max should be finite for claimed leaf ${leaf.id}" }
            }
        }
    }

    private fun testLeafRenderingStylesAndModes() {
        val points = figureEightRibbonPoints(160)
        val fit = points.fitPolynomialTree3D(
            PolynomialTreeFitConfig(
                order = 3,
                maxDepth = 5,
                maxLeaves = 10,
                minPointsPerLeaf = 7,
                splitCriterion = PolynomialTreeSplitStrategies.midpoint(),
                stopCriterion = PolynomialTreeStopCriteria.never
            )
        )

        val clean = fit.toLeafFLines(
            PolynomialLeafFLineStyles.clean(
                samplesPerLeaf = 56,
                outputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
            )
        )
        val gapped = fit.toLeafFLines(
            PolynomialLeafFLineStyles.gapped(
                samplesPerLeaf = 56,
                gapFractionOfSpan = 0.14,
                outputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
            )
        )
        val overshoot = fit.toLeafFLines(
            PolynomialLeafFLineStyles.overshooting(
                samplesPerLeaf = 56,
                overshootFractionOfSpan = 0.16,
                outputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
            )
        )
        val sketchy = fit.toLeafFLines(
            PolynomialLeafFLineStyles.sketchy(
                samplesPerLeaf = 56,
                passes = 3,
                jitterAmplitude = 0.35,
                gapFractionOfSpan = 0.08,
                overshootFractionOfSpan = 0.12,
                outputMode = PolynomialLeafFLineOutputMode.SEPARATE_LINES
            )
        )

        require(clean.isNotEmpty()) { "Clean render should produce lines" }
        require(gapped.isNotEmpty()) { "Gapped render should produce lines" }
        require(overshoot.isNotEmpty()) { "Overshoot render should produce lines" }
        require(sketchy.isNotEmpty()) { "Sketchy render should produce lines" }

        val cleanLength = totalLength(clean)
        val gappedLength = totalLength(gapped)
        val overshootLength = totalLength(overshoot)
        require(gappedLength < cleanLength) {
            "Expected gapped total length < clean (gapped=$gappedLength clean=$cleanLength)"
        }
        require(overshootLength > cleanLength) {
            "Expected overshoot total length > clean (overshoot=$overshootLength clean=$cleanLength)"
        }
        require(sketchy.size == clean.size * 3) {
            "Expected sketchy line count ${clean.size * 3}, got ${sketchy.size}"
        }

        val multipath = fit.toLeafFLines(
            PolynomialLeafFLineRenderConfig(
                samplesPerLeaf = 48,
                outputMode = PolynomialLeafFLineOutputMode.SINGLE_MULTIPATH
            )
        )
        val connected = fit.toLeafFLines(
            PolynomialLeafFLineRenderConfig(
                samplesPerLeaf = 48,
                outputMode = PolynomialLeafFLineOutputMode.SINGLE_CONNECTED
            )
        )
        require(multipath.size == 1) { "Expected a single multipath FLine, got ${multipath.size}" }
        require(connected.size == 1) { "Expected a single connected FLine, got ${connected.size}" }
        require(connected.first().nodes.count { it is FLine.MoveTo } == 1) {
            "Connected output should have exactly one MoveTo"
        }

        val map = fit.toLeafFLineMap(
            render = PolynomialLeafFLineStyles.sketchy(
                samplesPerLeaf = 44,
                passes = 2,
                jitterAmplitude = 0.2
            ),
            keyPrefix = "smoke_leaf"
        )
        require(map.size == fit.leaves.size) {
            "Expected map size ${fit.leaves.size}, got ${map.size}"
        }
        require(map.values.all { it.size == 2 }) {
            "Expected exactly two sketch passes per leaf in map output"
        }
    }

    private fun testFLineEntryPoint() {
        val base = FLine()
        val points = figureEightRibbonPoints(120)
        base.moveTo(points.first())
        points.drop(1).forEach { base.lineTo(it) }

        val fit = base.fitPolynomialTree3D(
            config = PolynomialTreeFitConfig(
                order = 2,
                maxDepth = 4,
                maxLeaves = 8,
                minPointsPerLeaf = 6,
                splitCriterion = PolynomialTreeSplitStrategies.hybrid(0.6)
            ),
            samples = 96
        )

        require(fit.inputPoints.size >= 32) { "Expected sampled input points from FLine entry point" }
        require(fit.leaves.isNotEmpty()) { "FLine entry point should produce leaves" }
    }

    private fun testBranchControlledLevelTraversal() {
        val fit = figureEightRibbonPoints(180).fitPolynomialTree3D(
            PolynomialTreeFitConfig(
                order = 3,
                maxDepth = 6,
                maxLeaves = 16,
                minPointsPerLeaf = 8,
                splitCriterion = PolynomialTreeSplitStrategies.curvatureAware(0.7, 0.3),
                stopCriterion = PolynomialTreeStopCriteria.never
            )
        )

        val levels = fit.levelTraversal(
            PolynomialTreeTraversalController { ctx ->
                val shortCurve = ctx.node.approxCurveLength(samples = 20) < 8.0
                val lowError = ctx.node.supportMaxError < 0.14
                PolynomialTreeTraversalDecision(
                    emit = !shortCurve,
                    descend = !(shortCurve || lowError)
                )
            }
        ).toList()

        require(levels.isNotEmpty()) { "Traversal should emit at least one level" }
        require(levels.first().depth == 0) { "First traversal level should be depth 0" }
        require(levels.zipWithNext().all { (a, b) -> b.depth > a.depth }) {
            "Traversal levels should be strictly increasing in depth"
        }

        val rendered = levels.flatMap { level ->
            level.toFLines { ctx ->
                if (ctx.node.supportMaxError > 0.04) ctx.node.toFLine(samples = 20) else null
            }
        }
        require(rendered.isNotEmpty()) { "Traversal-filtered levels should render at least one FLine" }
    }

    private fun testIncrementalUpdaterKeepsTopologyAndRefits() {
        val initial = figureEightRibbonPoints(140)
        val fit = initial.fitPolynomialTree3D(
            PolynomialTreeFitConfig(
                order = 3,
                maxDepth = 5,
                maxLeaves = 12,
                minPointsPerLeaf = 7,
                splitCriterion = PolynomialTreeSplitStrategies.hybrid(0.65),
                stopCriterion = PolynomialTreeStopCriteria.never
            )
        )
        val updater = fit.toIncrementalUpdater()
        val initialTopology = collectTopology(fit.root)

        val updatedPoints = figureEightRibbonPoints(210).mapIndexed { i, p ->
            val wobble = kotlin.math.sin(i * 0.17).toFloat() * 1.2f
            val lift = kotlin.math.cos(i * 0.11).toFloat() * 0.8f
            vec(p.x + wobble, p.y - wobble * 0.35f, p.z + lift)
        }

        val summary = updater.update(updatedPoints)
        require(summary.pointCount == updatedPoints.size) {
            "Updater should use updated point count ${updatedPoints.size}, got ${summary.pointCount}"
        }
        require(summary.overallRmsError.isFinite()) { "Updated RMS should be finite" }
        require(summary.overallMaxError.isFinite()) { "Updated max error should be finite" }

        val snapshot = updater.snapshot()
        val updatedTopology = collectTopology(snapshot.root)
        require(updatedTopology == initialTopology) {
            "Incremental update should preserve topology and node ids"
        }
        require(snapshot.leaves.size == fit.leaves.size) {
            "Leaf count should remain stable in incremental mode"
        }
        require(snapshot.pointErrors.size == updatedPoints.size) {
            "Updated point error array should match updated points"
        }
        require(snapshot.nearestLeafIds.size == updatedPoints.size) {
            "Updated nearest-leaf array should match updated points"
        }
    }

    fun figureEightRibbonPoints(count: Int): List<Vec3> {
        require(count >= 8) { "count must be >= 8" }
        val out = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            val t = i.toDouble() / (count - 1).toDouble()
            val angle = t * 2.0 * PI
            val x = 40.0 * sin(angle)
            val y = 22.0 * sin(2.0 * angle) + 6.0 * cos(5.0 * angle)
            val z = 8.0 * cos(angle) + 2.0 * sin(3.0 * angle)
            out += vec(x.toFloat(), y.toFloat(), z.toFloat())
        }
        return out
    }

    private fun totalLength(lines: List<FLine>): Double = lines.sumOf(::lengthOf)

    private fun collectTopology(root: PolynomialTreeNode): List<Triple<Int, Int, Pair<Int?, Int?>>> {
        val out = ArrayList<Triple<Int, Int, Pair<Int?, Int?>>>()
        fun walk(node: PolynomialTreeNode) {
            out += Triple(node.id, node.depth, Pair(node.left?.id, node.right?.id))
            node.left?.let(::walk)
            node.right?.let(::walk)
        }
        walk(root)
        return out.sortedBy { it.first }
    }

    private fun lengthOf(line: FLine): Double {
        if (line.nodes.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until line.nodes.size) {
            val a = line.nodes[i - 1].to
            val b = line.nodes[i].to
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val dz = (b.z - a.z).toDouble()
            sum += kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        return sum
    }
}
