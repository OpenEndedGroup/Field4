package afield.stdlib

import afield.stdlib.linalg.Vector3f
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
class InterpolantsTest {

    @Test
    fun frameClockAdvancesOncePerAuthoritativeFrame() {
        var frame = 0L
        val clock = frameClock(24.0) { frame }

        assertEquals(0.0, clock(), 0.0)
        assertEquals(0.0, clock(), 0.0, "re-reading one frame must not advance time")
        frame = 12
        assertEquals(0.5, clock(), 1e-12)
        frame = 24
        assertEquals(1.0, clock(), 1e-12)
    }

    @Test
    fun timeInterpolationCanRunOnRenderedFramesInsteadOfWallTime() {
        var frame = 100L
        val eased = smoothTo<Float>(
            time = 1.0,
            steps = null,
            clock = frameClock(4.0) { frame },
        ) { 1.0f }
        (eased as NeedsStartingValue<Float>).startAt(0.0f)

        assertEquals(0.0f, eased(), 0.0f)
        assertEquals(0.0f, eased(), 0.0f, "two evaluations in one frame must agree")
        frame = 101
        assertEquals(0.25f, eased(), 1e-6f)
        frame = 102
        assertEquals(0.5f, eased(), 1e-6f)
        frame = 104
        assertEquals(1.0f, eased(), 0.0f)
    }

    @Test
    fun startAtRestartsFromTheCurrentFrame() {
        var frame = 0
        val eased = smoothTo<Float>(
            time = 1.0,
            steps = null,
            clock = frameClock(10.0) { frame },
        ) { 1.0f }
        val restartable = eased as NeedsStartingValue<Float>

        restartable.startAt(0.0f)
        assertEquals(0.0f, eased(), 0.0f)
        frame = 5
        assertEquals(0.5f, eased(), 1e-6f)

        restartable.startAt(0.25f)
        assertEquals(0.25f, eased(), 0.0f)
        frame = 10
        assertEquals(0.625f, eased(), 1e-6f)
    }

    @Test
    fun stepModeDoesNotReadAnInjectedClock() {
        val eased = smoothTo<Float>(steps = 2, clock = { error("step mode read the clock") }) { 1.0f }
        (eased as NeedsStartingValue<Float>).startAt(0.0f)

        assertEquals(0.0f, eased(), 0.0f)
        assertEquals(0.5f, eased(), 1e-6f)
        assertEquals(1.0f, eased(), 0.0f)
    }

    @Test
    fun frameClockRejectsAnInvalidRate() {
        assertFailsWith<IllegalArgumentException> { frameClock(0.0) { 0 } }
        assertFailsWith<IllegalArgumentException> { frameClock(Double.NaN) { 0 } }
    }

    @Test
    fun solvingFromRestIsAPlainEase() {
        val solved = InertialEasing.solve(x = 3.0, v = 0.0)

        assertEquals(0.0, solved.t, 0.0, "a standstill starts at the beginning of the curve")
        assertEquals(3.0, solved.s, 0.0)
        assertEquals(3.0, InertialEasing.smoothstep(1.0, solved.s), 1e-12, "and arrives on the target")
    }

    @Test
    fun aSolvedCurveIsAtTheGivenOffsetAndVelocity() {
        val cases = listOf(
            1.0 to 0.5, 1.0 to 3.0, 1.0 to -2.0, 0.25 to 6.0,
            -1.0 to 0.5, -1.0 to -3.0, -4.0 to 9.0, 1.0 to 100.0,
        )

        cases.forEach { (x, v) ->
            val solved = InertialEasing.solve(x, v)
            assertEquals(x, solved.s - InertialEasing.smoothstep(solved.t, solved.s), 1e-9, "distance left for x=$x v=$v")
            assertEquals(v, InertialEasing.smoothstepDt(solved.t, solved.s), 1e-9, "velocity for x=$x v=$v")
            assertEquals(solved.s, InertialEasing.smoothstep(1.0, solved.s), 1e-12, "arrival for x=$x v=$v")
            assertEquals(0.0, InertialEasing.smoothstepDt(1.0, solved.s), 1e-12, "arrives at rest for x=$x v=$v")
        }
    }

    @Test
    fun overshootIsOnlyForSpeedsThatCouldNotStopInTime() {
        // Twenty units of speed into a two-unit gap: the honest answer is to fly past and come back.
        val overshooting = InertialEasing.solve(x = 2.0, v = 200.0)
        assertTrue(overshooting.t < -0.5, "expected the overshooting root, got t=${overshooting.t}")
        assertTrue(overshooting.t > -0.55)

        // A speed the curve can absorb takes the ordinary root, and so does anything if overshoot is off.
        val decelerating = InertialEasing.solve(x = 2.0, v = 2.0)
        assertTrue(decelerating.t in 0.0..1.0, "expected the decelerating root, got t=${decelerating.t}")
        assertTrue(InertialEasing.solve(x = 2.0, v = 200.0, overshoot = 0.0).t in 0.0..1.0)
    }

    @Test
    fun inertialEasingArrivesAndStays() {
        val eased = inertialTo<Float>(steps = 4) { 1.0f }
        (eased as NeedsStartingValue<Float>).startAt(0.0f)

        // One quarter of a blend time per evaluation, along 3t² - 2t³.
        assertEquals(0.15625f, eased(), 1e-6f)
        assertEquals(0.5f, eased(), 1e-6f)
        assertEquals(0.84375f, eased(), 1e-6f)
        assertEquals(1.0f, eased(), 1e-6f)
        assertEquals(1.0f, eased(), 1e-6f)
    }

    @Test
    fun retargetingKeepsTheVelocityItAlreadyHad() {
        var target = 1.0
        val eased = inertialTo<Double>(steps = 100) { target }
        (eased as NeedsStartingValue<Double>).startAt(0.0)

        repeat(28) { eased() }
        val twoAgo = eased()
        val before = eased()
        val stepBefore = before - twoAgo

        target = 2.0
        val after = eased()
        val stepAfter = after - before

        assertTrue(after > before, "a re-aim must not stall the value")
        assertEquals(stepBefore, stepAfter, stepBefore * 0.2, "the speed carries through the re-aim")

        // For scale: what a standing start into the same gap would have managed.
        val fromRest = inertialTo<Double>(steps = 100) { 2.0 }
        (fromRest as NeedsStartingValue<Double>).startAt(before)
        val standstillStep = fromRest() - before
        assertTrue(
            stepAfter > 5 * standstillStep,
            "inertia should dwarf a standing start: $stepAfter vs $standstillStep"
        )
    }

    @Test
    fun aMovingTargetIsTrackedRatherThanRestarted() {
        var target = 0.0
        val eased = inertialTo<Double>(steps = 20) { target }
        (eased as NeedsStartingValue<Double>).startAt(0.0)

        // A ramp: the target moves a twentieth of the blend distance every evaluation, forever.
        var value = 0.0
        var previous = 0.0
        repeat(200) {
            target += 0.05
            previous = value
            value = eased()
        }

        val speed = value - previous
        assertEquals(0.05, speed, 0.05 * 0.2, "should settle into the target's own speed")
        assertTrue(target - value > 0.0, "and trail behind it")
        assertTrue(target - value < 0.5, "by a bounded lag, not falling ever further behind: ${target - value}")
    }

    @Test
    fun vectorComponentsEaseOnTheirOwnCurves() {
        val eased = inertialTo<Vector3f>(steps = 4) { Vector3f(1f, 2f, 0.5f) }
        (eased as NeedsStartingValue<Vector3f>).startAt(Vector3f(0f, 0f, 0f))

        val first = eased()
        assertEquals(0.15625f, first.x, 1e-6f)
        assertEquals(0.3125f, first.y, 1e-6f)
        assertEquals(0.078125f, first.z, 1e-6f)

        repeat(3) { eased() }
        val arrived = eased()
        assertEquals(1f, arrived.x, 1e-6f)
        assertEquals(2f, arrived.y, 1e-6f)
        assertEquals(0.5f, arrived.z, 1e-6f)
    }

    @Test
    fun inertialEasingRunsOnRenderedFramesAndOnlyOnDeltas() {
        var frame = 100L
        val eased = inertialTo<Float>(
            time = 1.0,
            steps = null,
            clock = frameClock(4.0) { frame },
        ) { 1.0f }
        (eased as NeedsStartingValue<Float>).startAt(0.0f)

        assertEquals(0.0f, eased(), 0.0f, "no elapsed time, no motion")
        assertEquals(0.0f, eased(), 0.0f, "two evaluations in one frame must agree")
        frame = 101
        assertEquals(0.15625f, eased(), 1e-6f)
        assertEquals(0.15625f, eased(), 1e-6f)
        frame = 102
        assertEquals(0.5f, eased(), 1e-6f)
        frame = 104
        assertEquals(1.0f, eased(), 1e-6f)
    }

    @Test
    fun inertialEasingStartsWhereItFirstFindsItsTargetIfNobodySaysOtherwise() {
        var target = 5.0f
        val eased = inertialTo<Float>(steps = 4) { target }

        assertEquals(5.0f, eased(), 0.0f, "no starting value means no journey to make")
        target = 6.0f
        assertEquals(5.15625f, eased(), 1e-6f, "and it eases from there when the target moves")
    }

    /**
     * The claim the `inertial_movement` transcript prints before it starts animating: `inertialTo` is
     * the hand-driven solve, packaged. Driven off the same clock at the same blend time the two are
     * the same arithmetic, so any disagreement at all is a bug in one of them.
     */
    @Test
    fun theHandDrivenSolveIsExactlyWhatInertialToPackages() {
        val blend = 0.35
        val dt = 1.0 / 60.0

        var seconds = 0.0
        var goal = 0.0
        val packaged = inertialTo<Double>(time = blend, steps = null, clock = { seconds }) { goal }

        var t = 0.0
        var s = 0.0
        var offset = 0.0
        var value = 0.0
        var aimedAt = 0.0
        var worst = 0.0

        packaged()                                     // prime its clock; nothing has moved yet
        repeat(180) { i ->
            goal = if (i < 30) 0.0 else if (i < 90) 1.0 else -0.5
            if (goal != aimedAt) {
                val solved = InertialEasing.solve(goal - value, InertialEasing.smoothstepDt(t, s))
                t = solved.t
                s = solved.s
                offset = value - InertialEasing.smoothstep(t, s)
                aimedAt = goal
            }
            seconds += dt
            t += dt / blend
            value = offset + InertialEasing.smoothstep(t, s)
            worst = maxOf(worst, abs(packaged() - value))
        }

        assertTrue(value > 0.0 || value < 0.0, "the fixture has to actually move something")
        assertEquals(0.0, worst, 1e-12, "worst disagreement over 180 frames was $worst")
    }

    @Test
    fun aTypeItCannotEaseArrivesImmediately() {
        val eased = inertialTo<String>(steps = 4) { "there" }
        (eased as NeedsStartingValue<String>).startAt("here")

        assertEquals("there", eased())
    }
}
