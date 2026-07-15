package afield.stdlib.linalg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Closest point on the finite line segment [a, b]. Safe for a zero-length segment. */
fun closestPointOnSegment(point: Vector2fc, a: Vector2fc, b: Vector2fc, dest: Vector2f): Vector2f {
    val abx = b.x() - a.x()
    val aby = b.y() - a.y()
    val denominator = abx * abx + aby * aby
    val t = if (denominator == 0f) 0f else
        ((point.x() - a.x()) * abx + (point.y() - a.y()) * aby) / denominator
    val clamped = t.coerceIn(0f, 1f)
    return dest.set(a.x() + abx * clamped, a.y() + aby * clamped)
}

/** Closest point on the finite line segment [a, b]. Safe for a zero-length segment. */
fun closestPointOnSegment(point: Vector3fc, a: Vector3fc, b: Vector3fc, dest: Vector3f): Vector3f {
    val abx = b.x() - a.x()
    val aby = b.y() - a.y()
    val abz = b.z() - a.z()
    val denominator = abx * abx + aby * aby + abz * abz
    val t = if (denominator == 0f) 0f else
        ((point.x() - a.x()) * abx + (point.y() - a.y()) * aby + (point.z() - a.z()) * abz) / denominator
    val clamped = t.coerceIn(0f, 1f)
    return dest.set(a.x() + abx * clamped, a.y() + aby * clamped, a.z() + abz * clamped)
}

/**
 * Writes the closest pair on segments `[a0, a1]` and `[b0, b1]`, returning their squared distance.
 * Degenerate point-segments are supported and both destinations may alias input heap vectors.
 */
fun closestPointsBetweenSegments(
    a0: Vector3fc,
    a1: Vector3fc,
    b0: Vector3fc,
    b1: Vector3fc,
    destA: Vector3f,
    destB: Vector3f,
    epsilon: Float = 1e-12f,
): Float {
    val d1x = a1.x() - a0.x(); val d1y = a1.y() - a0.y(); val d1z = a1.z() - a0.z()
    val d2x = b1.x() - b0.x(); val d2y = b1.y() - b0.y(); val d2z = b1.z() - b0.z()
    val rx = a0.x() - b0.x(); val ry = a0.y() - b0.y(); val rz = a0.z() - b0.z()
    val a = d1x * d1x + d1y * d1y + d1z * d1z
    val e = d2x * d2x + d2y * d2y + d2z * d2z
    val f = d2x * rx + d2y * ry + d2z * rz
    var s: Float
    var t: Float
    if (a <= epsilon && e <= epsilon) {
        s = 0f; t = 0f
    } else if (a <= epsilon) {
        s = 0f; t = (f / e).coerceIn(0f, 1f)
    } else {
        val c = d1x * rx + d1y * ry + d1z * rz
        if (e <= epsilon) {
            t = 0f; s = (-c / a).coerceIn(0f, 1f)
        } else {
            val b = d1x * d2x + d1y * d2y + d1z * d2z
            val denominator = a * e - b * b
            s = if (abs(denominator) > epsilon) ((b * f - c * e) / denominator).coerceIn(0f, 1f) else 0f
            t = (b * s + f) / e
            if (t < 0f) { t = 0f; s = (-c / a).coerceIn(0f, 1f) }
            else if (t > 1f) { t = 1f; s = ((b - c) / a).coerceIn(0f, 1f) }
        }
    }
    val ax = a0.x() + d1x * s; val ay = a0.y() + d1y * s; val az = a0.z() + d1z * s
    val bx = b0.x() + d2x * t; val by = b0.y() + d2y * t; val bz = b0.z() + d2z * t
    destA.set(ax, ay, az); destB.set(bx, by, bz)
    val dx = ax - bx; val dy = ay - by; val dz = az - bz
    return dx * dx + dy * dy + dz * dz
}

/** Single-point 2D segment intersection. Collinear overlap returns false because it has no unique point. */
fun intersectSegments(
    a0: Vector2fc,
    a1: Vector2fc,
    b0: Vector2fc,
    b1: Vector2fc,
    dest: Vector2f,
    epsilon: Float = 1e-7f,
): Boolean {
    val adx = a1.x() - a0.x(); val ady = a1.y() - a0.y()
    val bdx = b1.x() - b0.x(); val bdy = b1.y() - b0.y()
    val denominator = adx * bdy - ady * bdx
    if (abs(denominator) <= epsilon) return false
    val rx = b0.x() - a0.x(); val ry = b0.y() - a0.y()
    val t = (rx * bdy - ry * bdx) / denominator
    val u = (rx * ady - ry * adx) / denominator
    if (t !in 0f..1f || u !in 0f..1f) return false
    dest.set(a0.x() + adx * t, a0.y() + ady * t)
    return true
}

/**
 * Writes barycentric coordinates `(u, v, w)` for point [point] relative to triangle [a], [b], [c].
 * Returns false for a degenerate triangle and leaves [dest] unchanged.
 */
fun barycentricCoordinates(
    point: Vector3fc,
    a: Vector3fc,
    b: Vector3fc,
    c: Vector3fc,
    dest: Vector3f,
    epsilon: Float = 1e-12f,
): Boolean {
    val v0x = b.x() - a.x(); val v0y = b.y() - a.y(); val v0z = b.z() - a.z()
    val v1x = c.x() - a.x(); val v1y = c.y() - a.y(); val v1z = c.z() - a.z()
    val v2x = point.x() - a.x(); val v2y = point.y() - a.y(); val v2z = point.z() - a.z()
    val d00 = v0x * v0x + v0y * v0y + v0z * v0z
    val d01 = v0x * v1x + v0y * v1y + v0z * v1z
    val d11 = v1x * v1x + v1y * v1y + v1z * v1z
    val d20 = v2x * v0x + v2y * v0y + v2z * v0z
    val d21 = v2x * v1x + v2y * v1y + v2z * v1z
    val denominator = d00 * d11 - d01 * d01
    if (abs(denominator) <= epsilon) return false
    val v = (d11 * d20 - d01 * d21) / denominator
    val w = (d00 * d21 - d01 * d20) / denominator
    return dest.set(1f - v - w, v, w).let { true }
}

fun containsPointInTriangle(
    point: Vector3fc,
    a: Vector3fc,
    b: Vector3fc,
    c: Vector3fc,
    tolerance: Float = 1e-6f,
): Boolean {
    // Expanded barycentric calculation avoids allocating a temporary vector.
    val v0x = b.x() - a.x(); val v0y = b.y() - a.y(); val v0z = b.z() - a.z()
    val v1x = c.x() - a.x(); val v1y = c.y() - a.y(); val v1z = c.z() - a.z()
    val v2x = point.x() - a.x(); val v2y = point.y() - a.y(); val v2z = point.z() - a.z()
    val d00 = v0x * v0x + v0y * v0y + v0z * v0z
    val d01 = v0x * v1x + v0y * v1y + v0z * v1z
    val d11 = v1x * v1x + v1y * v1y + v1z * v1z
    val d20 = v2x * v0x + v2y * v0y + v2z * v0z
    val d21 = v2x * v1x + v2y * v1y + v2z * v1z
    val denominator = d00 * d11 - d01 * d01
    if (abs(denominator) <= 1e-12f) return false
    val nx = v0y * v1z - v0z * v1y
    val ny = v0z * v1x - v0x * v1z
    val nz = v0x * v1y - v0y * v1x
    val planeNumerator = v2x * nx + v2y * ny + v2z * nz
    if (planeNumerator * planeNumerator > tolerance * tolerance * (nx * nx + ny * ny + nz * nz)) return false
    val v = (d11 * d20 - d01 * d21) / denominator
    val w = (d00 * d21 - d01 * d20) / denominator
    val u = 1f - v - w
    return u >= -tolerance && v >= -tolerance && w >= -tolerance
}

/** Ericson-style closest point on a triangle, including degenerate edge regions. */
fun closestPointOnTriangle(
    point: Vector3fc,
    a: Vector3fc,
    b: Vector3fc,
    c: Vector3fc,
    dest: Vector3f,
): Vector3f {
    val abx = b.x() - a.x(); val aby = b.y() - a.y(); val abz = b.z() - a.z()
    val acx = c.x() - a.x(); val acy = c.y() - a.y(); val acz = c.z() - a.z()
    val crossX = aby * acz - abz * acy
    val crossY = abz * acx - abx * acz
    val crossZ = abx * acy - aby * acx
    if (crossX * crossX + crossY * crossY + crossZ * crossZ <= 1e-20f) {
        return closestPointOnDegenerateTriangle(point, a, b, c, dest)
    }
    val apx = point.x() - a.x(); val apy = point.y() - a.y(); val apz = point.z() - a.z()
    val d1 = abx * apx + aby * apy + abz * apz
    val d2 = acx * apx + acy * apy + acz * apz
    if (d1 <= 0f && d2 <= 0f) return dest.set(a)

    val bpx = point.x() - b.x(); val bpy = point.y() - b.y(); val bpz = point.z() - b.z()
    val d3 = abx * bpx + aby * bpy + abz * bpz
    val d4 = acx * bpx + acy * bpy + acz * bpz
    if (d3 >= 0f && d4 <= d3) return dest.set(b)

    val vc = d1 * d4 - d3 * d2
    if (vc <= 0f && d1 >= 0f && d3 <= 0f) {
        val v = d1 / (d1 - d3)
        return dest.set(a.x() + v * abx, a.y() + v * aby, a.z() + v * abz)
    }

    val cpx = point.x() - c.x(); val cpy = point.y() - c.y(); val cpz = point.z() - c.z()
    val d5 = abx * cpx + aby * cpy + abz * cpz
    val d6 = acx * cpx + acy * cpy + acz * cpz
    if (d6 >= 0f && d5 <= d6) return dest.set(c)

    val vb = d5 * d2 - d1 * d6
    if (vb <= 0f && d2 >= 0f && d6 <= 0f) {
        val w = d2 / (d2 - d6)
        return dest.set(a.x() + w * acx, a.y() + w * acy, a.z() + w * acz)
    }

    val va = d3 * d6 - d5 * d4
    if (va <= 0f && d4 - d3 >= 0f && d5 - d6 >= 0f) {
        val bcx = c.x() - b.x(); val bcy = c.y() - b.y(); val bcz = c.z() - b.z()
        val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
        return dest.set(b.x() + w * bcx, b.y() + w * bcy, b.z() + w * bcz)
    }

    val denominator = va + vb + vc
    val inverse = 1f / denominator
    val v = vb * inverse
    val w = vc * inverse
    return dest.set(
        a.x() + abx * v + acx * w,
        a.y() + aby * v + acy * w,
        a.z() + abz * v + acz * w,
    )
}

private fun closestPointOnDegenerateTriangle(
    point: Vector3fc,
    a: Vector3fc,
    b: Vector3fc,
    c: Vector3fc,
    dest: Vector3f,
): Vector3f {
    closestPointOnSegment(point, a, b, dest)
    var bestX = dest.x; var bestY = dest.y; var bestZ = dest.z
    var bestDistance = dest.distanceSquared(point)
    closestPointOnSegment(point, b, c, dest)
    var distance = dest.distanceSquared(point)
    if (distance < bestDistance) {
        bestDistance = distance; bestX = dest.x; bestY = dest.y; bestZ = dest.z
    }
    closestPointOnSegment(point, c, a, dest)
    distance = dest.distanceSquared(point)
    if (distance < bestDistance) {
        bestX = dest.x; bestY = dest.y; bestZ = dest.z
    }
    return dest.set(bestX, bestY, bestZ)
}

fun closestPointOnAabb(point: Vector3fc, minimum: Vector3fc, maximum: Vector3fc, dest: Vector3f): Vector3f =
    dest.set(
        point.x().coerceIn(minimum.x(), maximum.x()),
        point.y().coerceIn(minimum.y(), maximum.y()),
        point.z().coerceIn(minimum.z(), maximum.z()),
    )

fun containsPointAabb(point: Vector3fc, minimum: Vector3fc, maximum: Vector3fc): Boolean =
    point.x() in minimum.x()..maximum.x() &&
        point.y() in minimum.y()..maximum.y() &&
        point.z() in minimum.z()..maximum.z()

fun intersectsAabb(aMin: Vector3fc, aMax: Vector3fc, bMin: Vector3fc, bMax: Vector3fc): Boolean =
    aMax.x() >= bMin.x() && aMin.x() <= bMax.x() &&
        aMax.y() >= bMin.y() && aMin.y() <= bMax.y() &&
        aMax.z() >= bMin.z() && aMin.z() <= bMax.z()

fun containsPointSphere(point: Vector3fc, center: Vector3fc, radius: Float): Boolean =
    point.distanceSquared(center) <= radius * radius

fun intersectsSphere(aCenter: Vector3fc, aRadius: Float, bCenter: Vector3fc, bRadius: Float): Boolean {
    val radius = aRadius + bRadius
    return aCenter.distanceSquared(bCenter) <= radius * radius
}

fun intersectsAabbSphere(minimum: Vector3fc, maximum: Vector3fc, center: Vector3fc, radius: Float): Boolean {
    val dx = when { center.x() < minimum.x() -> minimum.x() - center.x(); center.x() > maximum.x() -> center.x() - maximum.x(); else -> 0f }
    val dy = when { center.y() < minimum.y() -> minimum.y() - center.y(); center.y() > maximum.y() -> center.y() - maximum.y(); else -> 0f }
    val dz = when { center.z() < minimum.z() -> minimum.z() - center.z(); center.z() > maximum.z() -> center.z() - maximum.z(); else -> 0f }
    return dx * dx + dy * dy + dz * dz <= radius * radius
}

/** Returns the forward ray distance, or [Float.NaN] when the ray misses or the triangle is degenerate. */
fun rayTriangleDistance(
    origin: Vector3fc,
    direction: Vector3fc,
    a: Vector3fc,
    b: Vector3fc,
    c: Vector3fc,
    epsilon: Float = 1e-7f,
): Float {
    val e1x = b.x() - a.x(); val e1y = b.y() - a.y(); val e1z = b.z() - a.z()
    val e2x = c.x() - a.x(); val e2y = c.y() - a.y(); val e2z = c.z() - a.z()
    val px = direction.y() * e2z - direction.z() * e2y
    val py = direction.z() * e2x - direction.x() * e2z
    val pz = direction.x() * e2y - direction.y() * e2x
    val determinant = e1x * px + e1y * py + e1z * pz
    if (abs(determinant) <= epsilon) return Float.NaN
    val inverse = 1f / determinant
    val tx = origin.x() - a.x(); val ty = origin.y() - a.y(); val tz = origin.z() - a.z()
    val u = (tx * px + ty * py + tz * pz) * inverse
    if (u < 0f || u > 1f) return Float.NaN
    val qx = ty * e1z - tz * e1y
    val qy = tz * e1x - tx * e1z
    val qz = tx * e1y - ty * e1x
    val v = (direction.x() * qx + direction.y() * qy + direction.z() * qz) * inverse
    if (v < 0f || u + v > 1f) return Float.NaN
    val distance = (e2x * qx + e2y * qy + e2z * qz) * inverse
    return if (distance >= 0f) distance else Float.NaN
}

fun intersectRayTriangle(
    origin: Vector3fc,
    direction: Vector3fc,
    a: Vector3fc,
    b: Vector3fc,
    c: Vector3fc,
    dest: Vector3f,
    epsilon: Float = 1e-7f,
): Boolean {
    val distance = rayTriangleDistance(origin, direction, a, b, c, epsilon)
    if (distance.isNaN()) return false
    dest.set(
        origin.x() + direction.x() * distance,
        origin.y() + direction.y() * distance,
        origin.z() + direction.z() * distance,
    )
    return true
}

fun intersectRayPlane(
    origin: Vector3fc,
    direction: Vector3fc,
    pointOnPlane: Vector3fc,
    normal: Vector3fc,
    dest: Vector3f,
    epsilon: Float = 1e-7f,
): Boolean {
    val denominator = direction.dot(normal)
    if (abs(denominator) <= epsilon) return false
    val t = ((pointOnPlane.x() - origin.x()) * normal.x() +
        (pointOnPlane.y() - origin.y()) * normal.y() +
        (pointOnPlane.z() - origin.z()) * normal.z()) / denominator
    if (t < 0f) return false
    dest.set(origin.x() + direction.x() * t, origin.y() + direction.y() * t, origin.z() + direction.z() * t)
    return true
}

/** Writes forward `(near, far)` ray distances. Direction need not be normalized. */
fun intersectRaySphere(
    origin: Vector3fc,
    direction: Vector3fc,
    center: Vector3fc,
    radius: Float,
    nearFar: Vector2f,
    epsilon: Float = 1e-12f,
): Boolean {
    val mx = origin.x() - center.x(); val my = origin.y() - center.y(); val mz = origin.z() - center.z()
    val a = direction.lengthSquared()
    if (a <= epsilon) return false
    val b = mx * direction.x() + my * direction.y() + mz * direction.z()
    val c = mx * mx + my * my + mz * mz - radius * radius
    val discriminant = b * b - a * c
    if (discriminant < 0f) return false
    val root = sqrt(discriminant)
    val near = (-b - root) / a
    val far = (-b + root) / a
    if (far < 0f) return false
    nearFar.set(max(0f, near), far)
    return true
}

/** Writes forward `(near, far)` ray distances. */
fun intersectRayAabb(
    origin: Vector3fc,
    direction: Vector3fc,
    minimum: Vector3fc,
    maximum: Vector3fc,
    nearFar: Vector2f,
    epsilon: Float = 1e-12f,
): Boolean = intersectParametricAabb(origin, direction, minimum, maximum, 0f, Float.POSITIVE_INFINITY, nearFar, epsilon)

/** Writes segment parameters `(near, far)` in `[0, 1]`. */
fun intersectSegmentAabb(
    start: Vector3fc,
    end: Vector3fc,
    minimum: Vector3fc,
    maximum: Vector3fc,
    nearFar: Vector2f,
    epsilon: Float = 1e-12f,
): Boolean {
    val dx = end.x() - start.x(); val dy = end.y() - start.y(); val dz = end.z() - start.z()
    nearFar.set(0f, 1f)
    return clipSlab(start.x(), dx, minimum.x(), maximum.x(), nearFar, epsilon) &&
        clipSlab(start.y(), dy, minimum.y(), maximum.y(), nearFar, epsilon) &&
        clipSlab(start.z(), dz, minimum.z(), maximum.z(), nearFar, epsilon)
}

private fun intersectParametricAabb(
    origin: Vector3fc,
    direction: Vector3fc,
    minimum: Vector3fc,
    maximum: Vector3fc,
    initialNear: Float,
    initialFar: Float,
    nearFar: Vector2f,
    epsilon: Float,
): Boolean {
    nearFar.set(initialNear, initialFar)
    return clipSlab(origin.x(), direction.x(), minimum.x(), maximum.x(), nearFar, epsilon) &&
        clipSlab(origin.y(), direction.y(), minimum.y(), maximum.y(), nearFar, epsilon) &&
        clipSlab(origin.z(), direction.z(), minimum.z(), maximum.z(), nearFar, epsilon)
}

private fun clipSlab(
    origin: Float,
    direction: Float,
    low: Float,
    high: Float,
    nearFar: Vector2f,
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
