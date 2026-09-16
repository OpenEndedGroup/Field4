package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Quat
import afield.stdlib.Vec3
import afield.stdlib.color
import afield.stdlib.vec
import afield.stdlib.linalg.Vector4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Wireframes for the Jolt collision shapes: what `JoltPhysicsWorld.debugWireLines3D` is made of.
 *
 * Nothing here is public, which is why this page lists no declarations for it. The file holds one
 * builder per case of [JoltShape], an axes gizmo, and the transform and colour steps that follow,
 * all of them `internal` to [JoltPhysicsWorld]'s use. Each builder draws in the shape's own local
 * space, centred on the origin, and `transformLines` is what carries the result out to a body's
 * pose.
 *
 * The drawings are outlines rather than meshes, because they are there to be seen through while
 * something else is rendered inside them. A sphere is three great circles, not a tessellation. A
 * capsule is a ring at each end, a ring at the waist when there is a cylinder to have one, and two
 * stadium profiles at right angles. An infinite plane becomes a square patch around the point on it
 * nearest the origin, with a stub along the normal, which points away from the solid side.
 *
 * `colorForBodyName` hashes the name and lifts each channel away from black, which is what keeps a
 * body the same colour from run to run without anyone choosing one.
 */

private const val TAU = 2.0 * PI

internal fun wireCuboid(width: Double, height: Double, depth: Double): List<FLine> {
    val hx = (width * 0.5).toFloat()
    val hy = (height * 0.5).toFloat()
    val hz = (depth * 0.5).toFloat()

    val minX = -hx
    val minY = -hy
    val minZ = -hz
    val maxX = hx
    val maxY = hy
    val maxZ = hz

    val line = FLine()

    line.moveTo(minX, minY, minZ)
    line.lineTo(maxX, minY, minZ)
    line.lineTo(maxX, maxY, minZ)
    line.lineTo(minX, maxY, minZ)
    line.lineTo(minX, minY, minZ)

    line.moveTo(minX, minY, maxZ)
    line.lineTo(maxX, minY, maxZ)
    line.lineTo(maxX, maxY, maxZ)
    line.lineTo(minX, maxY, maxZ)
    line.lineTo(minX, minY, maxZ)

    line.moveTo(minX, minY, minZ)
    line.lineTo(minX, minY, maxZ)
    line.moveTo(maxX, minY, minZ)
    line.lineTo(maxX, minY, maxZ)
    line.moveTo(maxX, maxY, minZ)
    line.lineTo(maxX, maxY, maxZ)
    line.moveTo(minX, maxY, minZ)
    line.lineTo(minX, maxY, maxZ)

    return listOf(line)
}

internal fun wireSphereRings(radius: Double, sphereSegments: Int = 24): List<FLine> {
    val segments = sphereSegments.coerceAtLeast(8)
    val r = radius.toFloat()
    val line = FLine()

    appendRing(line, segments) { theta ->
        vec((r * cos(theta)).toFloat(), (r * sin(theta)).toFloat(), 0f)
    }
    appendRing(line, segments) { theta ->
        vec((r * cos(theta)).toFloat(), 0f, (r * sin(theta)).toFloat())
    }
    appendRing(line, segments) { theta ->
        vec(0f, (r * cos(theta)).toFloat(), (r * sin(theta)).toFloat())
    }

    return listOf(line)
}

internal fun wireCapsule(radius: Double, height: Double, capsuleSegments: Int = 24): List<FLine> {
    val segments = capsuleSegments.coerceAtLeast(12)
    val r = radius.toFloat()
    val halfHeightCylinder = max(0.0, (height - 2.0 * radius) * 0.5).toFloat()
    val line = FLine()

    appendRing(line, segments) { theta ->
        vec((r * cos(theta)).toFloat(), halfHeightCylinder, (r * sin(theta)).toFloat())
    }
    appendRing(line, segments) { theta ->
        vec((r * cos(theta)).toFloat(), -halfHeightCylinder, (r * sin(theta)).toFloat())
    }
    if (halfHeightCylinder > 1e-6f) {
        appendRing(line, segments) { theta ->
            vec((r * cos(theta)).toFloat(), 0f, (r * sin(theta)).toFloat())
        }
    }

    appendStadiumOnXyPlane(line, r, halfHeightCylinder, segments / 2)
    appendStadiumOnYzPlane(line, r, halfHeightCylinder, segments / 2)

    return listOf(line)
}

internal fun wirePlanePatch(
    nx: Double,
    ny: Double,
    nz: Double,
    constant: Double,
    halfExtent: Float = 6f
): List<FLine> {
    val normalRaw = vec(nx, ny, nz)
    val normal = if (normalRaw.lengthSquared() > 1e-8f) {
        Vec3(normalRaw).normalize()
    } else {
        vec(0f, 1f, 0f)
    }

    val normalLenSq = normalRaw.lengthSquared().coerceAtLeast(1e-8f)
    val centerScale = (-constant.toFloat()) / normalLenSq
    val center = Vec3(normalRaw).mul(centerScale)

    val reference = if (abs(normal.y) < 0.95f) vec(0f, 1f, 0f) else vec(1f, 0f, 0f)
    val tangent = Vec3(normal).cross(reference).normalize()
    val bitangent = Vec3(normal).cross(tangent).normalize()

    val u = Vec3(tangent).mul(halfExtent)
    val v = Vec3(bitangent).mul(halfExtent)

    val c0 = Vec3(center).sub(u).sub(v)
    val c1 = Vec3(center).add(u).sub(v)
    val c2 = Vec3(center).add(u).add(v)
    val c3 = Vec3(center).sub(u).add(v)

    val outline = FLine()
        .moveTo(c0)
        .lineTo(c1)
        .lineTo(c2)
        .lineTo(c3)
        .lineTo(c0)

    val normalLine = FLine()
        .moveTo(center)
        .lineTo(Vec3(center).add(Vec3(normal).mul(halfExtent * 0.8f)))

    return listOf(outline, normalLine)
}

internal fun wireAxes(length: Float = 0.5f): List<FLine> {
    if (length <= 0f) return emptyList()

    val xAxis = FLine().moveTo(0f, 0f, 0f).lineTo(length, 0f, 0f).apply { color = vec(1f, 0.2f, 0.2f, 1f) }
    val yAxis = FLine().moveTo(0f, 0f, 0f).lineTo(0f, length, 0f).apply { color = vec(0.2f, 1f, 0.2f, 1f) }
    val zAxis = FLine().moveTo(0f, 0f, 0f).lineTo(0f, 0f, length).apply { color = vec(0.2f, 0.6f, 1f, 1f) }

    return listOf(xAxis, yAxis, zAxis)
}

internal fun colorForBodyName(name: String): Vector4f {
    val hash = name.hashCode()

    var r = ((hash ushr 16) and 0xFF) / 255f
    var g = ((hash ushr 8) and 0xFF) / 255f
    var b = (hash and 0xFF) / 255f

    val floor = 0.22f
    if (r < floor) r = floor + (1f - floor) * r
    if (g < floor) g = floor + (1f - floor) * g
    if (b < floor) b = floor + (1f - floor) * b

    return vec(r, g, b, 1f)
}

internal fun transformLines(lines: List<FLine>, position: Vec3, rotation: Quat): List<FLine> {
    return lines.map { it * rotation + position }
}

internal fun colorizeLines(lines: List<FLine>, colorValue: Vector4f): List<FLine> {
    return lines.map { line ->
        line.copy {
            color = vec(colorValue.x, colorValue.y, colorValue.z, colorValue.w)
        }
    }
}

private fun appendRing(line: FLine, segments: Int, pointAtAngle: (Double) -> Vec3) {
    val safeSegments = segments.coerceAtLeast(8)
    var first: Vec3? = null

    for (i in 0 until safeSegments) {
        val angle = (i.toDouble() / safeSegments) * TAU
        val p = pointAtAngle(angle)
        if (i == 0) {
            first = p
            line.moveTo(p)
        } else {
            line.lineTo(p)
        }
    }

    first?.let { line.lineTo(it) }
}

private fun appendStadiumOnXyPlane(line: FLine, radius: Float, halfHeightCylinder: Float, arcSegments: Int) {
    val segments = arcSegments.coerceAtLeast(6)

    val start = vec(radius, -halfHeightCylinder, 0f)
    line.moveTo(start)
    line.lineTo(radius, halfHeightCylinder, 0f)

    for (i in 1..segments) {
        val t = i.toDouble() / segments
        val angle = t * PI
        val x = (radius * cos(angle)).toFloat()
        val y = halfHeightCylinder + (radius * sin(angle)).toFloat()
        line.lineTo(x, y, 0f)
    }

    line.lineTo(-radius, -halfHeightCylinder, 0f)

    for (i in 1..segments) {
        val t = i.toDouble() / segments
        val angle = PI + t * PI
        val x = (radius * cos(angle)).toFloat()
        val y = -halfHeightCylinder + (radius * sin(angle)).toFloat()
        line.lineTo(x, y, 0f)
    }
}

private fun appendStadiumOnYzPlane(line: FLine, radius: Float, halfHeightCylinder: Float, arcSegments: Int) {
    val segments = arcSegments.coerceAtLeast(6)

    val start = vec(0f, -halfHeightCylinder, radius)
    line.moveTo(start)
    line.lineTo(0f, halfHeightCylinder, radius)

    for (i in 1..segments) {
        val t = i.toDouble() / segments
        val angle = t * PI
        val z = (radius * cos(angle)).toFloat()
        val y = halfHeightCylinder + (radius * sin(angle)).toFloat()
        line.lineTo(0f, y, z)
    }

    line.lineTo(0f, -halfHeightCylinder, -radius)

    for (i in 1..segments) {
        val t = i.toDouble() / segments
        val angle = PI + t * PI
        val z = (radius * cos(angle)).toFloat()
        val y = -halfHeightCylinder + (radius * sin(angle)).toFloat()
        line.lineTo(0f, y, z)
    }
}
