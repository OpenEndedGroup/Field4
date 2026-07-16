package afield.stdlib.linalg.arena

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Allocation-free arena form of `afield.stdlib.linalg.closestPointOnSegment`. */
fun FrameScope.closestPointOnSegment(
    point: ArenaVec3f,
    a: ArenaVec3f,
    b: ArenaVec3f,
    dest: ArenaVec3f,
): ArenaVec3f {
    val abx = b.x - a.x; val aby = b.y - a.y; val abz = b.z - a.z
    val denominator = abx * abx + aby * aby + abz * abz
    val t = if (denominator == 0f) 0f else
        ((point.x - a.x) * abx + (point.y - a.y) * aby + (point.z - a.z) * abz) / denominator
    val clamped = t.coerceIn(0f, 1f)
    return dest.set(a.x + abx * clamped, a.y + aby * clamped, a.z + abz * clamped)
}

fun FrameScope.closestPointOnAabb(
    point: ArenaVec3f,
    minimum: ArenaVec3f,
    maximum: ArenaVec3f,
    dest: ArenaVec3f,
): ArenaVec3f = dest.set(
    point.x.coerceIn(minimum.x, maximum.x),
    point.y.coerceIn(minimum.y, maximum.y),
    point.z.coerceIn(minimum.z, maximum.z),
)

fun FrameScope.containsPointAabb(
    point: ArenaVec3f,
    minimum: ArenaVec3f,
    maximum: ArenaVec3f,
): Boolean = point.x in minimum.x..maximum.x &&
    point.y in minimum.y..maximum.y &&
    point.z in minimum.z..maximum.z

/** Writes forward `(near, far)` ray distances without allocating heap vectors. */
fun FrameScope.intersectRayAabb(
    origin: ArenaVec3f,
    direction: ArenaVec3f,
    minimum: ArenaVec3f,
    maximum: ArenaVec3f,
    nearFar: ArenaVec2f,
    epsilon: Float = 1e-12f,
): Boolean {
    nearFar.set(0f, Float.POSITIVE_INFINITY)
    return clipSlab(origin.x, direction.x, minimum.x, maximum.x, nearFar, epsilon) &&
        clipSlab(origin.y, direction.y, minimum.y, maximum.y, nearFar, epsilon) &&
        clipSlab(origin.z, direction.z, minimum.z, maximum.z, nearFar, epsilon)
}

private fun FrameScope.clipSlab(
    origin: Float,
    direction: Float,
    low: Float,
    high: Float,
    nearFar: ArenaVec2f,
    epsilon: Float,
): Boolean {
    if (abs(direction) <= epsilon) return origin in low..high
    var a = (low - origin) / direction
    var b = (high - origin) / direction
    if (a > b) { val swap = a; a = b; b = swap }
    nearFar.x = max(nearFar.x, a)
    nearFar.y = min(nearFar.y, b)
    return nearFar.x <= nearFar.y
}
