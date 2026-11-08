package afield.graphics.scene.video

import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import kotlin.math.*

/** A cylindrical movie screen. Width is arc length; zero bend is an ordinary flat rectangle. */
class StereoVideoPlacement {
    val center = Vector3f(0f, 0f, -2.5f)
    val rotationDegrees = Vector3f()
    @Volatile
    var widthMeters = 3f
    /** Per-eye displayed width/height. Native full-resolution SBS uses (file width / 2) / height. */
    @Volatile
    var aspect = 16f / 9f
    /** Positive bends bring the edges toward the viewer; negative bends take them away. */
    @Volatile
    var bendDegrees = 35f

    fun validate() {
        require(widthMeters.isFinite() && widthMeters > 0f)
        require(aspect.isFinite() && aspect > 0f)
        require(bendDegrees.isFinite() && abs(bendDegrees) <= 180f)
        require(
            listOf(center.x, center.y, center.z, rotationDegrees.x, rotationDegrees.y, rotationDegrees.z)
                .all {
                    it.isFinite()
                },
        )
    }

    fun model(): Matrix4f =
        Matrix4f()
            .translate(center)
            .rotateY(Math.toRadians(rotationDegrees.y.toDouble()).toFloat())
            .rotateX(Math.toRadians(rotationDegrees.x.toDouble()).toFloat())
            .rotateZ(Math.toRadians(rotationDegrees.z.toDouble()).toFloat())

    /** Reference geometry in screen-local metres, with top-left texture coordinates. */
    fun point(u: Float, v: Float): Vector3f {
        validate()
        val bend = Math.toRadians(bendDegrees.toDouble()).toFloat()
        val x = (u - .5f) * widthMeters
        val theta = (u - .5f) * bend
        return if (abs(bend) < 1e-4f) Vector3f(x, (.5f - v) * widthMeters / aspect, 0f)
        else
            Vector3f(
                widthMeters / bend * sin(theta),
                (.5f - v) * widthMeters / aspect,
                widthMeters / bend * 2f * sin(theta / 2f).pow(2),
            )
    }
}
