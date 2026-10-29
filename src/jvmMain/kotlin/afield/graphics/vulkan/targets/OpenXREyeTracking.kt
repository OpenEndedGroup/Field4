package afield.graphics.vulkan.targets

import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import afield.math.linalg.Vector2f
import org.lwjgl.openxr.XR10.*
import org.lwjgl.openxr.XrSpaceLocation

/**
 * Plain state holders for OpenXR eye tracking: the combined gaze of `XR_EXT_eye_gaze_interaction` and
 * the per-eye gaze, pupil and geometry streams of `XR_HTC_eye_tracker`.
 *
 * Each holder is filled in place on the renderer's queue thread and carries its own validity flags,
 * so read [EyeGazeState.poseValid] or the per-stream flags of [HtcEyeState] before using a value.
 */

/**
 * Combined gaze from `XR_EXT_eye_gaze_interaction`, in the target's LOCAL reference space
 * (metres, +Y up, -Z forward). Updated in place on the renderer's queue thread.
 * Numeric pose values retain the last valid observation; check [poseValid] before using them.
 */
class EyeGazeState {
    /** Where the combined gaze ray starts, in LOCAL space, in metres. Only written while [positionValid]. */
    val position = Vec3()

    /** Which way the gaze points, as a rotation of local -Z. Only written while [orientationValid]. */
    val orientation = Quat()

    /** False when the runtime reported no gaze action at all this frame, so nothing else here is current. */
    var isActive = false
        internal set

    /** The runtime gave a usable [position] in the last frame it was asked. */
    var positionValid = false
        internal set

    /** The runtime gave a usable [orientation] in the last frame it was asked. */
    var orientationValid = false
        internal set

    /**
     * The [position] is being actively tracked rather than inferred. Valid but untracked is the
     * runtime's best guess from an older observation, which is usable and should be trusted less.
     */
    var positionTracked = false
        internal set

    /** The [orientation] is being actively tracked rather than inferred — see [positionTracked]. */
    var orientationTracked = false
        internal set

    /** OpenXR time in nanoseconds at which the pose is expressed; zero when unavailable. */
    var sampleTime: Long = 0
        internal set

    /** [isActive] with both halves of the pose valid — the one check to make before reading the pose. */
    val poseValid: Boolean get() = isActive && positionValid && orientationValid

    /** False for a valid but degraded/untracked pose. */
    val isTracked: Boolean get() = poseValid && positionTracked && orientationTracked

    /** Copies the gaze's forward (-Z) direction into [out], only when the whole pose is valid. */
    fun direction(out: Vec3): Boolean {
        if (!poseValid) return false
        orientation.transform(out.set(0f, 0f, -1f))
        return true
    }

    internal fun invalidate() {
        isActive = false
        positionValid = false
        orientationValid = false
        positionTracked = false
        orientationTracked = false
        sampleTime = 0
    }

    internal fun update(location: XrSpaceLocation, time: Long) {
        val flags = location.locationFlags()
        positionValid = flags and XR_SPACE_LOCATION_POSITION_VALID_BIT.toLong() != 0L
        orientationValid = flags and XR_SPACE_LOCATION_ORIENTATION_VALID_BIT.toLong() != 0L
        positionTracked = positionValid && flags and XR_SPACE_LOCATION_POSITION_TRACKED_BIT.toLong() != 0L
        orientationTracked = orientationValid && flags and XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT.toLong() != 0L
        val pose = location.pose()
        if (positionValid) position.set(pose.`position$`().x(), pose.`position$`().y(), pose.`position$`().z())
        if (orientationValid) orientation.set(
            pose.orientation().x(), pose.orientation().y(), pose.orientation().z(), pose.orientation().w()
        )
        sampleTime = if (poseValid) time else 0
    }
}

/** One eye's independent observations from `XR_HTC_eye_tracker`. Check each validity flag. */
class HtcEyeState {
    /** Gaze pose in the target's LOCAL reference space, in metres. Forward is local -Z. */
    val position = Vec3()

    /** Which way this eye points, as a rotation of local -Z. Written together with [position], under [gazeValid]. */
    val orientation = Quat()

    /** This eye's [position] and [orientation] came through in the last gaze query. */
    var gazeValid = false
        internal set

    /** [pupilDiameter] came through in the last pupil query. Independent of [pupilPositionValid]. */
    var pupilDiameterValid = false
        internal set

    /** [pupilPosition] came through in the last pupil query. Independent of [pupilDiameterValid]. */
    var pupilPositionValid = false
        internal set
    /** Pupil diameter in millimetres (unlike the gaze position). */
    var pupilDiameter = 0f
        internal set
    /** Normalized sensor coordinates [0, 1], +X right, +Y up. */
    val pupilPosition = Vector2f()
    /** [eyeOpenness], [eyeWide] and [eyeSqueeze] came through in the last geometry query, as one group. */
    var geometricValid = false
        internal set

    /** How open the eye is, `0` shut to `1` open. Written under [geometricValid]. */
    var eyeOpenness = 0f
        internal set

    /** How wide the eye is held open past its resting opening, `0` to `1`. Written under [geometricValid]. */
    var eyeWide = 0f
        internal set

    /** How tightly the eye is squeezed shut, `0` to `1`. Written under [geometricValid]. */
    var eyeSqueeze = 0f
        internal set

    internal fun invalidate() {
        gazeValid = false
        pupilDiameterValid = false
        pupilPositionValid = false
        geometricValid = false
    }
}

/**
 * HTC per-eye gaze, pupil and geometry observations, updated in place on the renderer's queue
 * thread. The three streams have independent validity and OpenXR timestamps (nanoseconds).
 * A failed stream does not suppress the other streams. Numeric values retain the last valid
 * observation; timestamps reset to zero when a query fails or a frame is skipped.
 */
class HtcEyeTrackingState {
    /** The left eye's observations. Each of its three streams carries its own validity flag. */
    val left = HtcEyeState()

    /** The right eye's observations, read from the same three queries as [left]. */
    val right = HtcEyeState()

    /** OpenXR time in nanoseconds of the gaze poses in [left] and [right]; zero when that query failed. */
    var gazeTime: Long = 0
        internal set

    /** OpenXR time in nanoseconds of the pupil diameters and positions; zero when that query failed. */
    var pupilTime: Long = 0
        internal set

    /** OpenXR time in nanoseconds of the openness, wide and squeeze values; zero when that query failed. */
    var geometricTime: Long = 0
        internal set

    internal fun invalidate() {
        left.invalidate()
        right.invalidate()
        gazeTime = 0
        pupilTime = 0
        geometricTime = 0
    }
}
