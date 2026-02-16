package afield.examples.graphics

import afield.graphics.FLine
import afield.graphics.FLineSideways
import afield.stdlib.Vec4
import afield.stdlib.vec
import kotlin.math.abs

/**
 * Lightweight smoke tests for [FLineInstructionTrajectoryTracker].
 */
object FLineInstructionTrajectoryTrackerSmokeTest {

    fun runTests(): Boolean {
        return try {
            testStableTopologyAccumulates()
            testStableTopologyFastPathKeepsInstructionIdentityWhenClose()
            testStableTopologyFastPathFallsBackWhenAboveThreshold()
            testDroppedIntermediateInstructionDoesNotShiftEverything()
            testDisappearAndReappearCreatesGap()
            testEffectiveInstructionColorIsCopied()
            println("✅ FLineInstructionTrajectoryTracker smoke tests passed")
            true
        } catch (t: Throwable) {
            println("❌ FLineInstructionTrajectoryTracker smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "FLineInstructionTrajectoryTracker smoke tests failed" }
    }

    private fun testStableTopologyAccumulates() {
        val tracker = FLineSideways(maxMatchDistance = 5f)

        tracker.update(listOf(polyline(0f, 1f, 2f, y = 0f)))
        tracker.update(listOf(polyline(0f, 1f, 2f, y = 1f)))

        val withTwoSamples = tracker.trajectories().filter { it.nodes.size == 2 }
        require(withTwoSamples.size == 3) { "Expected 3 two-sample trajectories, got ${withTwoSamples.size}" }

        withTwoSamples.forEach { t ->
            val end = t.nodes.last().to
            require(abs(end.y - 1f) < 1e-5f) { "Expected second sample y=1, got ${end.y}" }
        }
    }

    private fun testDroppedIntermediateInstructionDoesNotShiftEverything() {
        val tracker = FLineSideways(maxMatchDistance = 2f)

        tracker.update(listOf(polyline(0f, 1f, 2f, 3f)))
        tracker.update(listOf(polyline(0f, 2f, 3f)))

        val trajectories = tracker.trajectories()
        val x2 = findTrajectoryStartingNear(trajectories, 2f)
        val x3 = findTrajectoryStartingNear(trajectories, 3f)

        val x2End = x2.nodes.last().to.x
        val x3End = x3.nodes.last().to.x

        require(abs(x2End - 2f) < 1e-5f) { "Expected x=2 trajectory to remain at x=2, got $x2End" }
        require(abs(x3End - 3f) < 1e-5f) { "Expected x=3 trajectory to remain at x=3, got $x3End" }
    }

    private fun testStableTopologyFastPathKeepsInstructionIdentityWhenClose() {
        val tracker = FLineSideways(
            maxMatchDistance = 2f,
            stableTopologyDistanceThreshold = 1f,
            instructionIndexWeight = 0.0
        )

        tracker.update(listOf(polyline(0f, 1f)))
        tracker.update(listOf(polyline(0.9f, 0.1f)))

        val trajectories = tracker.trajectories()
        val firstTrack = findTrajectoryStartingNear(trajectories, 0f)
        val secondTrack = findTrajectoryStartingNear(trajectories, 1f)

        val firstEnd = firstTrack.nodes.last().to.x
        val secondEnd = secondTrack.nodes.last().to.x
        require(abs(firstEnd - 0.9f) < 1e-5f) {
            "Expected first instruction track to stay with instruction index 0, got $firstEnd"
        }
        require(abs(secondEnd - 0.1f) < 1e-5f) {
            "Expected second instruction track to stay with instruction index 1, got $secondEnd"
        }
    }

    private fun testStableTopologyFastPathFallsBackWhenAboveThreshold() {
        val tracker = FLineSideways(
            maxMatchDistance = 2f,
            stableTopologyDistanceThreshold = 0.5f,
            instructionIndexWeight = 0.0
        )

        tracker.update(listOf(polyline(0f, 1f)))
        tracker.update(listOf(polyline(0.9f, 0.1f)))

        val trajectories = tracker.trajectories()
        val firstTrack = findTrajectoryStartingNear(trajectories, 0f)
        val secondTrack = findTrajectoryStartingNear(trajectories, 1f)

        val firstEnd = firstTrack.nodes.last().to.x
        val secondEnd = secondTrack.nodes.last().to.x
        require(abs(firstEnd - 0.1f) < 1e-5f) {
            "Expected fallback matching to choose nearest point for first track, got $firstEnd"
        }
        require(abs(secondEnd - 0.9f) < 1e-5f) {
            "Expected fallback matching to choose nearest point for second track, got $secondEnd"
        }
    }

    private fun testDisappearAndReappearCreatesGap() {
        val tracker = FLineSideways(
            maxMatchDistance = 1f,
            maxMissedFrames = 3,
            breakOnGap = true
        )

        tracker.update(listOf(polyline(0f)))
        tracker.update(emptyList())
        tracker.update(listOf(polyline(0.2f)))

        val trajectories = tracker.trajectories()
        require(trajectories.size == 1) { "Expected one trajectory, got ${trajectories.size}" }

        val t = trajectories.first()
        require(t.nodes.size == 2) { "Expected 2 nodes in trajectory, got ${t.nodes.size}" }
        require(t.nodes[0] is FLine.MoveTo) { "Expected first node to be MoveTo" }
        require(t.nodes[1] is FLine.MoveTo) { "Expected second node to be MoveTo after gap" }
    }

    private fun testEffectiveInstructionColorIsCopied() {
        val tracker = FLineSideways(maxMatchDistance = 5f)
        val f = FLine()
        f.properties["color"] = vec(0.2, 0.3, 0.4, 1.0)
        f.moveTo(0f, 0f, 0f)
        f.here.properties["color"] = vec(1.0, 0.0, 0.0, 1.0)
        f.lineTo(1f, 0f, 0f)
        f.here.properties["color"] = vec(0.0, 1.0, 0.0, 1.0)

        tracker.update(listOf(f))

        val trajectories = tracker.trajectories()
        val redTrack = findTrajectoryStartingNear(trajectories, 0f)
        val greenTrack = findTrajectoryStartingNear(trajectories, 1f)

        val red = (redTrack.properties["color"] as? Vec4) ?: error("Missing color on red track")
        val green = (greenTrack.properties["color"] as? Vec4) ?: error("Missing color on green track")
        require(abs(red.x - 1f) < 1e-5f && abs(red.y - 0f) < 1e-5f && abs(red.z - 0f) < 1e-5f) {
            "Unexpected red track color: $red"
        }
        require(abs(green.x - 0f) < 1e-5f && abs(green.y - 1f) < 1e-5f && abs(green.z - 0f) < 1e-5f) {
            "Unexpected green track color: $green"
        }

        val redNodeColor = redTrack.nodes.first().properties["color"]
        val greenNodeColor = greenTrack.nodes.first().properties["color"]
        require(redNodeColor != null) { "Expected node-level color on red track" }
        require(greenNodeColor != null) { "Expected node-level color on green track" }
    }

    private fun polyline(vararg xs: Float, y: Float = 0f): FLine {
        require(xs.isNotEmpty()) { "Need at least one x coordinate" }
        val f = FLine().moveTo(xs.first(), y, 0f)
        for (i in 1 until xs.size) {
            f.lineTo(xs[i], y, 0f)
        }
        return f
    }

    private fun findTrajectoryStartingNear(trajectories: List<FLine>, x: Float, eps: Float = 1e-4f): FLine {
        return trajectories.firstOrNull { t ->
            t.nodes.isNotEmpty() && abs(t.nodes.first().to.x - x) <= eps
        } ?: error("No trajectory starts near x=$x")
    }
}
