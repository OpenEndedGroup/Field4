package afield.four.texture.video

import kotlin.math.floor
import kotlin.math.roundToLong

/** CFR frame identity: timeline positions floor; decoded presentation timestamps identify centres. */
object VideoFrameIndex {
    fun requested(seconds: Double, fps: Double): Long {
        require(seconds.isFinite() && fps.isFinite() && fps > 0)
        // Tolerate only floating-point noise at an exact frame boundary, not half a source frame.
        return floor(seconds.coerceAtLeast(0.0) * fps + 1e-9).toLong()
    }

    fun decoded(seconds: Double, fps: Double): Long {
        require(seconds.isFinite() && fps.isFinite() && fps > 0)
        return (seconds * fps).roundToLong()
    }
}
