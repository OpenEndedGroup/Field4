@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/*
FIELD_TEST
id: synth.head-tracked-spatial.smoke
runner: jvm-main
entrypoint: afield.four.synth.HeadTrackedSpatialSmokeTest
tags: [smoke, audio, synth, steamaudio, openxr, cpu]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "HEAD_TRACKED_SPATIAL_SMOKE_PASSED"
*/

/**
 * The listener half of `patSpatial`'s audio check: the pattern holds still and the head moves.
 *
 * [PatSpatialSmokeTest] varies the tag and leaves the listener at the origin. Here the tag is fixed
 * and a head pose of the shape OpenXR reports drives the HRTF, over six cases: an identity pose
 * against no pose at all, a yaw, a step sideways, a walk towards the source, a tracking dropout,
 * and a [VirtualHead] on its own thread writing poses while the render thread reads them.
 *
 * Nothing here is asserted against the wall clock. [PatSpatialRoom]'s render loop has no pacing
 * beyond sleeping when its ring is at target fill, so a capture that drains the ring as fast as
 * blocks appear lets the whole chain free-run at whatever rate the machine allows. Every case
 * prints the factor it reached and none of them depends on it; [PatSpatialBudgetProbe] owns the
 * timing questions.
 *
 * Run it with `tools/run_field_tests.py --id synth.head-tracked-spatial.smoke`.
 */

/**
 * Proves that a head pose of the shape OpenXR reports aims Steam Audio's HRTF, on real audio.
 *
 * Everything here is measured off the samples [PatSpatialLaunch] produced, because the two claims
 * worth testing are both the kind that compile perfectly while being wrong:
 *
 *  1. **The frames agree.** OpenXR and Steam Audio are both right-handed, +Y up, -Z ahead, so
 *     [HeadPose.ofOpenXR] converts nothing. If that were wrong -- if either frame were the mirror of
 *     the other -- levels, distance, occlusion and air absorption would all still be right and only
 *     the listener's ears would be swapped. Nothing but rendered audio catches that.
 *  2. **A head that moves is not a head that turns.** [PatSpatialRoom] used to subtract an azimuth,
 *     which cannot express a head leaning, walking or nodding. The placements are now anchors in the
 *     room, so translation has to reach the ears; `strafing right` and `walking in` are the cases
 *     that hold it.
 *
 * Case 1 exists to catch the other half of that rewrite: with the listener at the origin and level,
 * the new arithmetic has to agree with the old to the sample, and it is the only case here that a
 * regression in `patSpatial`'s existing behaviour would break.
 *
 * Every case renders through the real render thread, real Dough engines and real Steam Audio, and
 * measures the samples. It is not paced by wall clock, though: `patSpatial`'s render thread sleeps
 * only when its ring is at target fill, so a consumer that drains the ring as fast as blocks appear
 * -- which is what [capture] does -- lets the whole chain free-run. Every case prints the factor it
 * achieved, and the spread is the point: 5.5x to 18.8x realtime across one run of eight captures.
 * Nothing in this test depends on that number, which is deliberate and was learned the hard way --
 * case 6's first version asserted a pose count "at 90 Hz" and failed the moment a capture ran fast.
 * [PatSpatialBudgetProbe] owns the timing questions.
 */
object HeadTrackedSpatialSmokeTest {

    private const val SR = 44_100
    private const val BLOCK = 512

    private class Capture(
        val rmsL: Double,
        val rmsR: Double,
        val underruns: Long,
        /**
         * Audio seconds pulled per wall second, excluding the room's boot.
         *
         * Printed rather than asserted. Nothing here should depend on it -- see case 6 -- but it is
         * the number that explains why: a capture drains the ring as fast as blocks appear, so this
         * says how far from realtime that ran, and a tracker on the wall clock is off by this factor.
         */
        val timesRealtime: Double,
    ) {
        /** Above 1 the sound is in the left ear, below 1 the right. */
        val ratio: Double get() = rmsL / rmsR.coerceAtLeast(1e-9)
        val rms: Double get() = sqrt((rmsL * rmsL + rmsR * rmsR) / 2.0)
    }

    /**
     * Play [pattern] for [seconds] with the listener driven by [drive], and measure the ears.
     *
     * [drive] runs once the room is ready and before anything is measured, so a case states its
     * head pose the way a caller would rather than racing the first block.
     */
    private fun capture(
        pattern: PatPattern<String>,
        seconds: Double = 1.1,
        skipSeconds: Double = 0.4,
        drive: (PatSpatialLaunch) -> AutoCloseable? = { null },
    ): Capture {
        val launch = patSpatial(pattern, cps = 2.0, sampleRate = SR, maxSources = 2)
        try {
            check(launch.awaitReady()) { "spatial room never became ready" }
            val driver = drive(launch)
            val startedAt = System.nanoTime()
            try {
                val ctx = SynthContext(SR.toFloat(), BLOCK)
                val l = FloatArray(BLOCK)
                val r = FloatArray(BLOCK)
                var cycleId = 0L
                var sumL = 0.0
                var sumR = 0.0
                var counted = 0
                var at = 0
                val skipFrames = (skipSeconds * SR).toInt()
                val totalFrames = ((seconds + skipSeconds) * SR).toInt()
                while (at < totalFrames) {
                    // The block does not exist until the render thread has made it, and
                    // waiting for one is not an underrun.
                    while (launch.room.ring.fill < BLOCK) {
                        launch.room.failure?.let { throw IllegalStateException("render thread failed", it) }
                        Thread.sleep(1L)
                    }
                    java.util.Arrays.fill(l, 0f)
                    java.util.Arrays.fill(r, 0f)
                    ctx.cycleId = ++cycleId
                    ctx.time = at.toFloat() / SR
                    launch.bank.render(l, r, BLOCK, ctx)
                    if (at >= skipFrames) {
                        for (i in 0 until BLOCK) {
                            sumL += l[i].toDouble() * l[i]
                            sumR += r[i].toDouble() * r[i]
                            counted++
                        }
                    }
                    at += BLOCK
                }
                val wallSeconds = (System.nanoTime() - startedAt) / 1e9
                return Capture(
                    rmsL = sqrt(sumL / counted),
                    rmsR = sqrt(sumR / counted),
                    underruns = launch.room.ring.underruns,
                    timesRealtime = (totalFrames / SR.toDouble()) / wallSeconds.coerceAtLeast(1e-9),
                )
            } finally {
                driver?.close()
            }
        } finally {
            launch.close()
        }
    }

    private fun report(label: String, c: Capture) {
        println("  %-30s rmsL %.5f  rmsR %.5f  L/R %6.2f  underruns %d  %5.1fx realtime"
            .format(label, c.rmsL, c.rmsR, c.ratio, c.underruns, c.timesRealtime))
    }

    /** Hand the room one pose and leave it there, the way a seated wearer would. */
    private fun still(pose: HeadPose): (PatSpatialLaunch) -> AutoCloseable? = { launch ->
        launch.followHead(VirtualHead.fixed(pose), hz = 30.0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("OpenXR head pose -> Steam Audio HRTF, $SR Hz")
        println()

        val notes = pat("60 64 67 71")

        // ── 1. an identity pose is the same as no pose ───────────────────────────────────────────
        // The world-anchor arithmetic reduces to the azimuth subtraction it replaced when the head
        // is at the origin and level. This is the regression guard on that reduction.
        val ahead = notes.with(param("az", -90))
        val untracked = capture(ahead)
        report("az -90, no pose source", untracked)
        val identity = capture(ahead, drive = still(HeadPose.IDENTITY))
        report("az -90, identity pose", identity)
        check(untracked.ratio > 1.4) { "az -90 should be a left-ear source, got L/R ${untracked.ratio}" }
        val agreement = identity.ratio / untracked.ratio
        check(agreement > 0.8 && agreement < 1.25) {
            "an identity head pose changed the image: L/R ${untracked.ratio} untracked vs " +
                "${identity.ratio} tracked"
        }

        // ── 2. a head turned right moves a source ahead into the left ear ────────────────────────
        // The handedness pin. A source dead ahead, a head yawed 90 degrees to the right: the source
        // has to end up on the left. Both frames' conventions are in this one number, and a mirror
        // in either of them inverts it.
        val front = notes.with(param("az", 0), param("dist", 2.0))
        val level = capture(front, drive = still(HeadPose.IDENTITY))
        report("az 0, head level", level)
        check(level.ratio > 0.8 && level.ratio < 1.25) {
            "a source dead ahead of a level head should be centred, got L/R ${level.ratio}"
        }
        val turned = capture(front, drive = still(HeadPose.turnedRight(90.0)))
        report("az 0, head turned right 90", turned)
        check(turned.ratio > 1.4) {
            "turning the head right should put a source that was ahead in the LEFT ear; " +
                "got L/R ${turned.ratio}. If this is inverted the OpenXR and Steam Audio frames " +
                "have been assumed to agree when they do not"
        }

        // ── 3. a head that steps sideways, without turning ───────────────────────────────────────
        // The capability the azimuth-only listener did not have. The head looks straight ahead the
        // whole time and only its position changes, so an azimuth subtraction would report no
        // change at all.
        val strafed = capture(front, drive = still(HeadPose(x = 1.6)))
        report("az 0, head 1.6 m right", strafed)
        check(strafed.ratio > 1.3) {
            "stepping 1.6 m to the right of a source 2 m ahead should move it left, " +
                "got L/R ${strafed.ratio}"
        }

        // ── 4. walking in makes it louder ───────────────────────────────────────────────────────
        // Distance attenuation now reads the head's position, not just the tag. -Z is forward, so a
        // head at z = -1.2 is 0.8 m from a source the pattern placed 2 m out.
        val near = capture(front, drive = still(HeadPose(z = -1.2)))
        report("az 0, head 1.2 m forward", near)
        check(near.rms > level.rms * 1.4) {
            "walking from 2 m to 0.8 m from the source should be audibly louder: " +
                "rms ${level.rms} standing still vs ${near.rms} walked in"
        }

        // ── 5. tracking loss freezes the field, it does not recentre it ──────────────────────────
        // The pose source stops reporting mid-capture. The last good pose has to stay applied: a
        // frozen sound field reads as a tracking dropout, where a snap back to centre reads as a
        // bug in the piece.
        var listener: HeadTrackedListener? = null
        val frozen = capture(front, seconds = 1.2) { launch ->
            val head = VirtualHead.fixed(HeadPose.turnedRight(90.0))
            launch.followHead(head, hz = 30.0).also {
                listener = it
                // Let a pose land, then pull the headset off the wearer's face.
                Thread.sleep(120L)
                head.tracking = false
            }
        }
        report("az 0, tracking lost turned", frozen)
        val tracker = listener ?: error("no listener was created")
        println("  tracker: $tracker")
        check(tracker.updates > 0L) { "the tracker never read a pose" }
        check(tracker.dropouts > 0L) { "tracking was turned off and no dropout was counted" }
        check(frozen.ratio > 1.4) {
            "losing tracking should hold the last pose, not recentre the listener; " +
                "got L/R ${frozen.ratio} where the held pose gives ${turned.ratio}"
        }

        // ── 6. a head that is actually moving, on its own thread ─────────────────────────────────
        // The cases above hold single poses. This one holds the concurrency: a tracker thread
        // writing poses while the render thread reads them, which is the shape a live headset
        // produces and the only case here where the two threads race.
        //
        // Nothing asserted below is a function of the tracker's *rate*, and that took a failure to
        // get right: the first version of this case required 20 updates over "1.6 s at 90 Hz" and
        // failed on a machine that rendered the 1.6 s of audio in 0.18 s of wall time. The tracker
        // is on the wall clock and the capture is not on any clock at all, so their ratio is a
        // property of the machine. What is a property of the code is that the poses arrive whole
        // and that the head genuinely swept -- a torn pose, position from one read with orientation
        // from another, is the failure this shape invites, and publishing each pose as one
        // immutable reference is what rules it out.
        val positionsSeen = java.util.Collections.synchronizedSet(LinkedHashSet<Double>())
        var moving: HeadTrackedListener? = null
        val walking = capture(front, seconds = 1.6) { launch ->
            val sweep = VirtualHead.walking(Vec3(1f, 0f, 0f), metres = 1.5, periodSeconds = 1.6)
            val recording = VirtualHead { t -> sweep.pose(t).also { positionsSeen.add(it.x) } }
            launch.followHead(recording).also { moving = it }
        }
        report("az 0, head walking past", walking)
        val movingTracker = moving ?: error("no tracker was created")
        println("  tracker: $movingTracker, ${positionsSeen.size} distinct head positions")
        check(walking.rms > 1e-4) { "the walking capture was silent" }
        check(movingTracker.dropouts == 0L) {
            "a VirtualHead always reports, so ${movingTracker.dropouts} dropouts means the tracker " +
                "itself failed: ${movingTracker.failure}"
        }
        // Whatever the rate was, the head has to have been somewhere more than once, or this case
        // is case 2 again with extra threads.
        check(positionsSeen.size >= 4) {
            "the head barely moved: ${positionsSeen.size} distinct positions over the capture"
        }
        check(positionsSeen.any { abs(it) > 0.3 }) {
            "every pose was within 30 cm of the origin, so the 1.5 m walk did not happen: " +
                positionsSeen.take(8)
        }
        // The room only ever sees whole poses, so the last one applied has to be one this head
        // actually held: somewhere inside its 1.5 m walk, and level, because it never turns.
        val landed = movingTracker.lastPose ?: error("the tracker read no pose")
        check(abs(landed.azimuthDeg) < 1e-3) {
            "a head that only walks should never report a heading, got ${landed.azimuthDeg} degrees"
        }
        check(abs(landed.x) <= 1.5 + 1e-6 && landed.y == 0.0 && landed.z == 0.0) {
            "the last pose applied is not on the walk this head was given: $landed"
        }

        println()
        println("HEAD_TRACKED_SPATIAL_SMOKE_PASSED")
    }
}
