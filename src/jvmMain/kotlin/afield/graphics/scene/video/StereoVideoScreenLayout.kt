package afield.graphics.scene.video

import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import kotlin.math.*

/** Independent offsets/overrides; null radius or fill follows the carousel's global value. */
data class StereoVideoScreenLayout(
    val angleOffsetDegrees: Float = 0f,
    val radiusMeters: Float? = null,
    val fill: Float? = null,
) {
    init { require(listOfNotNull(angleOffsetDegrees, radiusMeters, fill).all { it.isFinite() }) }

    internal fun placement(index: Int, count: Int, origin: Vector3f, globalRadius: Float,
                           globalAngle: Float, globalFill: Float): StereoVideoPlacement {
        val radius = radiusMeters ?: globalRadius
        val arc = 2 * PI / count * (fill ?: globalFill)
        val degrees = globalAngle.toDouble() + index * 360.0 / count + angleOffsetDegrees
        val angle = Math.toRadians(degrees)
        return StereoVideoPlacement().apply {
            center.set(origin).add((radius * sin(angle)).toFloat(), 0f, (-radius * cos(angle)).toFloat())
            rotationDegrees.set(0f, (-degrees + if (radius < 0) 180.0 else 0.0).toFloat(), 0f)
            widthMeters = (abs(radius.toDouble()) * arc).toFloat()
            bendDegrees = Math.toDegrees(arc).toFloat()
            validate()
        }
    }
}

/** Centre-depth painter order from the current two eye views; ties retain screen-number order. */
internal fun stereoVideoBackToFront(placements: List<StereoVideoPlacement>, left: Matrix4f, right: Matrix4f): List<Int> =
    placements.indices.sortedByDescending { i ->
        val p = placements[i].center
        -(left.transformPosition(Vector3f(p)).z.toDouble() + right.transformPosition(Vector3f(p)).z) * .5
    }
