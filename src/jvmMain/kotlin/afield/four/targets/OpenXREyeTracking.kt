package afield.four.targets

import afield.stdlib.Quat
import afield.stdlib.Vec3
import afield.stdlib.linalg.Vector2f
import org.lwjgl.openxr.XR10.*
import org.lwjgl.openxr.XrSpaceLocation

/**
 * Combined gaze from `XR_EXT_eye_gaze_interaction`, in the target's LOCAL reference space
 * (metres, +Y up, -Z forward). Updated in place on the renderer's queue thread.
 * Numeric pose values retain the last valid observation; check [poseValid] before using them.
 */
class EyeGazeState {
    val position = Vec3()
    val orientation = Quat()
    var isActive = false
        internal set
    var positionValid = false
        internal set
    var orientationValid = false
        internal set
    var positionTracked = false
        internal set
    var orientationTracked = false
        internal set

    /** OpenXR time in nanoseconds at which the pose is expressed; zero when unavailable. */
    var sampleTime: Long = 0
        internal set

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
    val orientation = Quat()
    var gazeValid = false
        internal set
    var pupilDiameterValid = false
        internal set
    var pupilPositionValid = false
        internal set
    /** Pupil diameter in millimetres (unlike the gaze position). */
    var pupilDiameter = 0f
        internal set
    /** Normalized sensor coordinates [0, 1], +X right, +Y up. */
    val pupilPosition = Vector2f()
    var geometricValid = false
        internal set
    /** Eye openness, wide opening, and tight closure, each in [0, 1]. */
    var eyeOpenness = 0f
        internal set
    var eyeWide = 0f
        internal set
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
    val left = HtcEyeState()
    val right = HtcEyeState()
    var gazeTime: Long = 0
        internal set
    var pupilTime: Long = 0
        internal set
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
