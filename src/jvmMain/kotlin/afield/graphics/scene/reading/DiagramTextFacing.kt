package afield.graphics.scene.reading

import afield.math.linalg.*
import kotlin.math.*

/** A gravity-up text plane whose +Z faces the reader. Very near the origin, keep the last direction. */
internal fun readingFacingTarget(position: Vector3f, reader: Vector3f, previous: Quaternionf?): Quaternionf {
    val z = Vector3f(reader).sub(position)
    if (z.lengthSquared() < .0025f) return Quaternionf(previous ?: Quaternionf())
    z.normalize()
    val x = Vector3f(0f, 1f, 0f).cross(z)
    if (x.lengthSquared() < 1e-8f) x.set(1f, 0f, 0f) else x.normalize()
    val y = Vector3f(z).cross(x).normalize()
    return Quaternionf().setFromNormalized(Matrix3f().set(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z))
}

/** Half-life smoothing with an angular speed limit, independent of the display frame rate. */
internal fun readingTurn(current: Quaternionf, target: Quaternionf, dt: Float, halfLife: Float, maximumRate: Float) {
    val angle = 2f * acos(abs(current.dot(target)).coerceIn(0f, 1f))
    if (angle < 1e-6f) return
    val amount = if (halfLife == 0f) 1f else 1f - exp(-ln(2f) * dt / halfLife)
    current.slerp(target, min(amount, maximumRate * dt / angle).coerceIn(0f, 1f)).normalize()
}
