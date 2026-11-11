package afield.graphics.scene.reading

import afield.math.linalg.Vector3f
import kotlin.math.*

/** Gaze-relative floor movement in metres: radial dead zone, analog response, bounded frame step. */
fun readingWalk(x: Float, y: Float, gaze: Vector3f, seconds: Double, speed: Float = .65f): Vector3f {
    if (!x.isFinite() || !y.isFinite() || !seconds.isFinite() || !speed.isFinite() || speed <= 0f) return Vector3f()
    val magnitude = sqrt(x * x + y * y)
    if (magnitude <= .18f) return Vector3f()
    val forward = Vector3f(gaze.x, 0f, gaze.z)
    if (!forward.lengthSquared().isFinite() || forward.lengthSquared() < 1e-6f) return Vector3f()
    forward.normalize()
    val right = Vector3f(-forward.z, 0f, forward.x)
    val gain = ((min(magnitude, 1f) - .18f) / .82f).pow(1.5f)
    return forward.mul(y / magnitude).fma(x / magnitude, right)
        .mul(speed * seconds.coerceIn(0.0, .05).toFloat() * gain)
}
