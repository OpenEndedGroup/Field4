@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.targets.OpenXRHmdTarget
import afield.four.targets.StereoPoseSource
import afield.stdlib.Quat
import afield.stdlib.Vec3
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The seam between a tracked head and [patSpatial]'s listener.
 *
 * Field already had both halves of head-tracked binaural audio and no join between them:
 * [OpenXRHmdTarget.headPose] reports where the wearer's head is, and [PatSpatialRoom] renders a
 * pattern through Steam Audio's HRTF from wherever it is told the listener is. What was missing is
 * one interface narrow enough that the audio side cannot tell an HMD from a script.
 *
 * # The conversion is a copy
 *
 * OpenXR and Steam Audio use the **same** frame: right-handed, +X right, +Y up, -Z ahead, metres.
 * So [HeadPose.ofOpenXR] copies seven numbers and converts nothing, and that is worth saying out
 * loud because the alternative is invisible. A handedness error between these two frames leaves
 * level, distance, occlusion and reverb all correct and silently swaps the listener's ears -- the
 * failure [afield.four.audio.steamaudio.World2D] exists to prevent on the geometry side, and the one
 * `World2DHandednessTest` caught during the bindings' development. `HeadTrackedSpatialSmokeTest`
 * holds this side of it on rendered audio rather than on the claim above.
 *
 * # Where to pump from
 *
 * [OpenXRHmdTarget]'s pose comes from `xrLocateViews` on the renderer's queue thread and is stored
 * as loose floats, so a reader on another thread can in principle see a position from one frame
 * with an orientation from the next. That is why [HeadTrackedListener] can be driven two ways, and
 * why the OpenXR one is the manual one: construct it with `hz = 0.0` and call
 * [HeadTrackedListener.pump] once per frame from inside the frame callback, where the pose is whole.
 * A [StereoPoseSource] has no such problem -- it latches by contract -- and is fine on the timer.
 *
 * @see HeadTrackedListener
 * @see VirtualHead
 */
fun interface HeadPoseSource {

    /**
     * Fill [outPosition] and [outOrientation] with the head's current pose, in the room's frame.
     *
     * Returns false when there is no usable pose: nothing has arrived yet, or tracking is invalid.
     * A false is not an error and not silence -- [HeadTrackedListener] keeps the last good pose, so
     * the sound field freezes where it was rather than snapping to face forward. A frozen field is
     * recognisable as a tracking dropout; a snap sounds like a bug in the piece.
     */
    fun headPose(outPosition: Vec3, outOrientation: Quat): Boolean
}

/** This headset's pose, as something [PatSpatialRoom] can follow. */
fun OpenXRHmdTarget.asHeadPoseSource(): HeadPoseSource =
    HeadPoseSource { p, q -> headPose(p, q) }

/** This pose source's *latched* pose. Call [StereoPoseSource.latch] on the frame, as usual. */
fun StereoPoseSource.asHeadPoseSource(): HeadPoseSource =
    HeadPoseSource { p, q -> headPose(p, q) }

/**
 * A head that moves the way a function says, for when there is no runtime to ask.
 *
 * Not a mock in the testing sense -- it is the only head available on a machine with no OpenXR
 * runtime, which includes every macOS box in this project, so it is what the demo and the smoke test
 * actually run against. The code path it drives is the one an HMD drives; only the seven numbers'
 * provenance differs.
 *
 * ```kotlin
 * val launch = patSpatial(pat("bd*2, hh*8").with(param("az", "-60 60")), cps = 2.0)
 * launch.followHead(VirtualHead.turning(degreesPerSecond = 45.0))
 * ```
 *
 * # Which clock
 *
 * [clock] defaults to the wall clock, which is what a live listener wants and what makes this
 * interchangeable with a headset. It is a parameter because **rendering is not paced by the wall
 * clock**. [PatSpatialRoom]'s render thread sleeps only when the ring is at target fill, so when an
 * audio device drains that ring the chain runs in realtime, and when a caller drains it as fast as
 * it is produced -- an offline render, a test -- the whole chain free-runs, and by a wide and not
 * very predictable margin: measured between 5.5x and 18.8x realtime across
 * `HeadTrackedSpatialSmokeTest`'s one-source captures, and between 9.7x and 42x across
 * `HeadTrackedPatternDemo`'s three-source movements, the low end of each being the one that paid for
 * the JIT warmup.
 *
 * A head on the wall clock therefore moves *slower, relative to the music*, in a file than it does
 * live -- by a factor that depends on warmup, source count and load, so it is not one a caller can
 * correct for after the fact. So a render whose head motion has to line
 * up with the audio should drive the clock from [PatSpatialRoom.framesRendered] -- the timeline of
 * the blocks actually being spatialised, rather than of the ones the caller has pulled, which trail
 * it by the ring's depth:
 *
 * ```kotlin
 * val origin = launch.room.framesRendered
 * val head = VirtualHead.turning(45.0, clock = {
 *     (launch.room.framesRendered - origin) / sampleRate.toDouble()
 * })
 * val tracker = launch.followHead(head, hz = 0.0)   // no timer thread
 * // ... in the pull loop, once per block:
 * tracker.pump()
 * ```
 *
 * `HeadTrackedPatternDemo` does exactly this, and its own docs record what went wrong before it did.
 *
 * @param clock the seconds this head is at, or null for the wall clock since [start].
 * @param pose the head's pose at a time in seconds.
 */
class VirtualHead(
    val clock: (() -> Double)? = null,
    val pose: (seconds: Double) -> HeadPose,
) : HeadPoseSource {

    private var origin = System.nanoTime()

    /**
     * Whether this head reports tracking. Set false to exercise a consumer's dropout path -- which
     * is a real path, not a hypothetical one: an OpenXR runtime returns an invalid view state
     * whenever the headset is off the wearer's face.
     */
    @Volatile
    var tracking: Boolean = true

    /** Restart the wall clock, so the next [headPose] is the pose at zero. No-op with a [clock]. */
    fun start() {
        origin = System.nanoTime()
    }

    /** Seconds on this head's [clock], or since [start] when it has none. */
    val seconds: Double get() = clock?.invoke() ?: ((System.nanoTime() - origin) / 1e9)

    override fun headPose(outPosition: Vec3, outOrientation: Quat): Boolean {
        if (!tracking) return false
        val at = pose(seconds)
        outPosition.set(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
        outOrientation.set(at.qx.toFloat(), at.qy.toFloat(), at.qz.toFloat(), at.qw.toFloat())
        return true
    }

    companion object {

        /** A head fixed at [pose]. */
        fun fixed(pose: HeadPose): VirtualHead = VirtualHead { pose }

        /** A head at the origin, turning steadily to the right at [degreesPerSecond]. */
        fun turning(degreesPerSecond: Double, clock: (() -> Double)? = null): VirtualHead =
            VirtualHead(clock) { t -> HeadPose.turnedRight(degreesPerSecond * t) }

        /**
         * A head looking steadily ahead while walking [metres] along [axis] and back, once every
         * [periodSeconds]. The axis is the room's: `Vec3(1, 0, 0)` walks to the right.
         */
        fun walking(
            axis: Vec3,
            metres: Double,
            periodSeconds: Double,
            clock: (() -> Double)? = null,
        ): VirtualHead {
            val n = axis.length().toDouble().coerceAtLeast(1e-9)
            val ax = axis.x / n; val ay = axis.y / n; val az = axis.z / n
            return VirtualHead(clock) { t ->
                val s = sin(2.0 * PI * t / periodSeconds) * metres
                HeadPose(x = ax * s, y = ay * s, z = az * s)
            }
        }

        /** A head at the origin, nodding [degrees] up and down once every [periodSeconds]. */
        fun nodding(
            degrees: Double,
            periodSeconds: Double,
            clock: (() -> Double)? = null,
        ): VirtualHead = VirtualHead(clock) { t ->
            // Pitch is a rotation about +X; looking up is the positive one in a right-handed frame
            // whose forward is -Z.
            val half = Math.toRadians(degrees * sin(2.0 * PI * t / periodSeconds)) * 0.5
            HeadPose(qx = sin(half), qw = cos(half))
        }
    }
}

/**
 * Follows a [HeadPoseSource] and keeps a [PatSpatialRoom]'s listener where the head is.
 *
 * Deliberately not on the room's render thread. That thread owes the audio thread a block every
 * 2.9 ms and stalls on this machine for tens of milliseconds at a time ([PatSpatialRoom] documents
 * the measurements); a pose read on it is one more thing that can be late, and the room is already
 * built to take the *newest* pose at the top of a block rather than to be handed every one. So this
 * is either its own timer thread or the caller's frame -- see [pump].
 *
 * The rate does not need to be high. Poses reach the spatialisation at the block rate, 345 Hz at
 * 44.1 kHz, and Steam Audio interpolates its HRTF between blocks, so the ceiling on usefulness is
 * the headset's own tracking rate. [DEFAULT_HZ] is 90 because that is a common display rate and
 * there is nothing to gain from asking faster than the runtime updates.
 *
 * @param hz poll rate for the internal thread, or 0.0 for a listener the caller [pump]s itself.
 */
class HeadTrackedListener(
    val room: PatSpatialRoom,
    val head: HeadPoseSource,
    val hz: Double = DEFAULT_HZ,
) : AutoCloseable {

    init {
        require(hz >= 0.0 && hz.isFinite()) { "hz must be finite and >= 0, got $hz" }
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // Reused across polls: a pose source fills these in place, so following a head allocates one
    // HeadPose per poll and nothing else.
    private val scratchPosition = Vec3()
    private val scratchOrientation = Quat()

    /** Poses read and handed to the room. */
    @Volatile
    var updates: Long = 0L
        private set

    /**
     * Polls where the head reported no usable pose and the last one was held.
     *
     * Worth printing next to [updates] rather than ignoring: a run whose dropouts outnumber its
     * updates is a tracking problem wearing the costume of an audio problem.
     */
    @Volatile
    var dropouts: Long = 0L
        private set

    /** The last pose handed to the room, or null before the first one. */
    @Volatile
    var lastPose: HeadPose? = null
        private set

    /**
     * Read the head once and hand the room the result. Returns false on a dropout.
     *
     * The whole listener, for a caller that has its own clock. For [OpenXRHmdTarget] this is the
     * right entry point and the frame callback is the right place to call it from, because that is
     * where the pose is coherent; see the note on [HeadPoseSource].
     */
    fun pump(): Boolean {
        val ok = try {
            head.headPose(scratchPosition, scratchOrientation)
        } catch (t: Throwable) {
            failure = t
            false
        }
        if (!ok) {
            dropouts++
            return false
        }
        val pose = HeadPose.ofOpenXR(scratchPosition, scratchOrientation)
        lastPose = pose
        updates++
        room.setListenerPose(pose)
        return true
    }

    /** What the pose source threw, if it threw. Polling continues; a tracker is not worth a crash. */
    @Volatile
    var failure: Throwable? = null
        private set

    /** Start the timer thread. A no-op when [hz] is 0, since there is nothing to start. */
    fun start() {
        if (hz == 0.0) return
        if (!running.compareAndSet(false, true)) return
        val periodMs = (1000.0 / hz).coerceAtLeast(1.0).toLong()
        thread = kotlin.concurrent.thread(isDaemon = true, name = "pat-spatial-head") {
            while (running.get()) {
                pump()
                try {
                    Thread.sleep(periodMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    val isRunning: Boolean get() = running.get()

    /** Stop following. The room keeps the last pose it was given. */
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    override fun toString(): String = synthPrettyObject(
        this, "hz" to hz, "updates" to updates, "dropouts" to dropouts,
        "azimuthDeg" to (lastPose?.azimuthDeg ?: 0.0),
    )

    companion object {
        const val DEFAULT_HZ = 90.0
    }
}
