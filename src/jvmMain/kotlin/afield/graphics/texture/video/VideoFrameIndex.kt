/**
 * Frame-number arithmetic for constant-frame-rate video, shared by the code that asks for a frame
 * and the code that reports which frame a decoder produced.
 *
 * A timeline position belongs to the frame whose interval contains it, so [VideoFrameIndex.requested]
 * floors `seconds * fps`. A decoded presentation timestamp marks a frame's own start, which may
 * carry rounding error in either direction, so [VideoFrameIndex.decoded] rounds to the nearest frame.
 */

package afield.graphics.texture.video

import kotlin.math.floor
import kotlin.math.roundToLong

/** CFR frame identity: timeline positions floor; decoded presentation timestamps identify centres. */
object VideoFrameIndex {
    /** The frame shown at timeline position [seconds]: floor of `seconds * fps` (tolerating 1e-9 of floating-point noise at a boundary), with negative times clamped to frame 0. */
    fun requested(seconds: Double, fps: Double): Long {
        require(seconds.isFinite() && fps.isFinite() && fps > 0)
        // Tolerate only floating-point noise at an exact frame boundary, not half a source frame.
        return floor(seconds.coerceAtLeast(0.0) * fps + 1e-9).toLong()
    }

    /** The frame a decoder's presentation timestamp [seconds] identifies: `seconds * fps` rounded to the nearest frame. */
    fun decoded(seconds: Double, fps: Double): Long {
        require(seconds.isFinite() && fps.isFinite() && fps > 0)
        return (seconds * fps).roundToLong()
    }
}
