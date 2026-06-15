package afield.four.physics

import afield.four.util.Camera
import afield.stdlib.Vec3
import afield.stdlib.vec
import org.joml.Vector3f
import kotlin.math.tan

data class JoltFrustumPlane(
    val name: String,
    val normal: Vec3,
    val constant: Double
)

class JoltCameraFrustumPlanes(
    val world: JoltPhysicsWorld,
    var prefix: String = "camera-frustum",
    var nearDistance: Double? = null,
    var farDistance: Double? = null,
    var friction: Double = 0.8,
    var restitution: Double = 0.0,
    var tags: Set<String> = setOf("frustum", "bounds"),
    var updateEpsilon: Double = 1.0e-5,
    var wakeDynamicBodiesOnUpdate: Boolean = true,
    var wakeIncludeTagRegex: Regex? = null,
    var wakeExcludeTagRegex: Regex? = null
) {
    private val lastPlanesByName = LinkedHashMap<String, JoltFrustumPlane>()

    val names: List<String>
        get() = FACE_NAMES.map { "$prefix.$it" }

    fun add(camera: Camera, near: Double? = null, far: Double? = null): List<String> =
        update(camera, near, far)

    fun update(camera: Camera, near: Double? = null, far: Double? = null): List<String> {
        val planes = planes(camera, near, far)
        var changed = false
        for (plane in planes) {
            val previous = lastPlanesByName[plane.name]
            if (previous != null && world.bodyIdsByName.containsKey(plane.name) && previous.closeTo(plane, updateEpsilon)) {
                continue
            }
            world.plane(plane.name, normal = plane.normal, constant = plane.constant) {
                friction = this@JoltCameraFrustumPlanes.friction
                restitution = this@JoltCameraFrustumPlanes.restitution
                tag(*planeTags(plane).toTypedArray())
            }
            lastPlanesByName[plane.name] = plane
            changed = true
        }
        if (changed && wakeDynamicBodiesOnUpdate) {
            world.activateBodies(
                dynamicOnly = true,
                includeTagRegex = wakeIncludeTagRegex,
                excludeTagRegex = wakeExcludeTagRegex
            )
        }
        return planes.map { it.name }
    }

    fun remove(): Boolean {
        var removedAny = false
        for (name in names) {
            removedAny = world.removeBody(name) || removedAny
        }
        lastPlanesByName.clear()
        return removedAny
    }

    fun planes(camera: Camera, near: Double? = null, far: Double? = null): List<JoltFrustumPlane> {
        val n = (near ?: nearDistance ?: camera.p.zNear).coerceAtLeast(1.0e-6)
        val f = (far ?: farDistance ?: camera.p.zFar)
        require(f > n) { "far must be greater than near, got near=$n far=$f" }

        val eye = Vector3f(camera.at)
        val forward = camera.forward
        val right = camera.right
        val up = Vector3f(right).cross(forward).normalize()

        val nearCorners = frustumCornersAt(camera, eye, forward, right, up, n)
        val farCorners = frustumCornersAt(camera, eye, forward, right, up, f)
        val inside = frustumPointAt(eye, forward, right, up, (n + f) * 0.5, 0.0, 0.0)

        return listOf(
            planeFromPoints("$prefix.left", nearCorners.topLeft, nearCorners.bottomLeft, farCorners.bottomLeft, inside),
            planeFromPoints("$prefix.right", nearCorners.bottomRight, nearCorners.topRight, farCorners.topRight, inside),
            planeFromPoints("$prefix.top", nearCorners.topRight, nearCorners.topLeft, farCorners.topLeft, inside),
            planeFromPoints("$prefix.bottom", nearCorners.bottomLeft, nearCorners.bottomRight, farCorners.bottomRight, inside),
            planeFromPoints("$prefix.near", nearCorners.topLeft, nearCorners.topRight, nearCorners.bottomRight, inside),
            planeFromPoints("$prefix.far", farCorners.topLeft, farCorners.bottomLeft, farCorners.bottomRight, inside)
        )
    }

    private fun frustumCornersAt(
        camera: Camera,
        eye: Vector3f,
        forward: Vector3f,
        right: Vector3f,
        up: Vector3f,
        distance: Double
    ): FrustumCorners {
        val halfHeight = distance * tan(camera.p.fov * 0.5)
        val halfWidth = halfHeight * camera.p.aspect.toDouble()
        val dx = camera.p.ox * halfWidth
        val dy = camera.p.oy * halfHeight

        val left = -halfWidth + dx
        val rightExtent = halfWidth + dx
        val bottom = -halfHeight + dy
        val top = halfHeight + dy

        return FrustumCorners(
            topLeft = frustumPointAt(eye, forward, right, up, distance, left, top),
            topRight = frustumPointAt(eye, forward, right, up, distance, rightExtent, top),
            bottomLeft = frustumPointAt(eye, forward, right, up, distance, left, bottom),
            bottomRight = frustumPointAt(eye, forward, right, up, distance, rightExtent, bottom)
        )
    }

    private fun frustumPointAt(
        eye: Vector3f,
        forward: Vector3f,
        right: Vector3f,
        up: Vector3f,
        distance: Double,
        x: Double,
        y: Double
    ): Vector3f {
        return Vector3f(eye)
            .fma(distance.toFloat(), forward)
            .fma(x.toFloat(), right)
            .fma(y.toFloat(), up)
    }

    private fun planeFromPoints(name: String, a: Vector3f, b: Vector3f, c: Vector3f, inside: Vector3f): JoltFrustumPlane {
        val normal = Vector3f(b).sub(a).cross(Vector3f(c).sub(a)).normalize()
        var constant = -normal.dot(a).toDouble()
        if (normal.dot(inside).toDouble() + constant < 0.0) {
            normal.negate()
            constant = -constant
        }
        return JoltFrustumPlane(name, vec(normal.x, normal.y, normal.z), constant)
    }

    private fun planeTags(plane: JoltFrustumPlane): Set<String> =
        tags + prefix + plane.name.substringAfterLast('.')

    private fun JoltFrustumPlane.closeTo(other: JoltFrustumPlane, epsilon: Double): Boolean {
        return normal.distance(other.normal).toDouble() <= epsilon &&
            kotlin.math.abs(constant - other.constant) <= epsilon
    }

    private data class FrustumCorners(
        val topLeft: Vector3f,
        val topRight: Vector3f,
        val bottomLeft: Vector3f,
        val bottomRight: Vector3f
    )

    private companion object {
        val FACE_NAMES = listOf("left", "right", "top", "bottom", "near", "far")
    }
}
