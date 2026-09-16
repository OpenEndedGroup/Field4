package afield.four.physics

import afield.four.util.Camera
import afield.stdlib.Vec3
import afield.stdlib.vec
import afield.stdlib.linalg.Vector3f
import kotlin.math.tan

/**
 * Walls for what the camera can see: six static Jolt planes, kept on the view frustum as it moves.
 *
 * The motivation is a sketch where things are meant to stay on screen. Six plane bodies are created
 * in a [JoltPhysicsWorld], one per face and each named for its face under a shared `prefix`, with
 * their normals turned to face the interior. The region they close off is then exactly the volume
 * the camera is looking at, and a dynamic body inside it stays inside.
 *
 * The frustum is derived from the camera's `fov`, `aspect` and principal-point offsets, with near
 * and far from the camera unless overridden. It is a perspective frustum in every case: this reads
 * nothing from the projection's `orthoness`, so a camera part way to orthographic gets planes that
 * do not match its picture.
 *
 * Calling `update` every frame is cheap when nothing has moved. A plane is rebuilt only when its
 * normal or constant has shifted past `updateEpsilon`, or when its body has gone missing from the
 * world; `add` is the same function under a name that reads better the first time. After any rebuild
 * the world's dynamic bodies are woken, unless `wakeDynamicBodiesOnUpdate` is off, which they have
 * to be: a body asleep against a wall does not notice the wall moving.
 *
 * Nothing in the repository calls this, in `src` or in the docs pages.
 */

/**
 * One frustum face, as a Jolt plane: a name, a normal, and the plane constant that goes with it.
 *
 * The normal faces into the frustum, and the plane satisfies `normal · x + constant = 0` on its
 * surface. [name] is the body name the plane is created under, so it is what identifies the face in
 * [JoltPhysicsWorld] afterwards.
 */
data class JoltFrustumPlane(
    /** The body name this face is created under: the frustum's prefix and the face's own name. */
    val name: String,
    /** Unit normal, pointing into the frustum. */
    val normal: Vec3,
    /** The plane's constant term: its surface is where `normal · x + constant` comes to zero. */
    val constant: Double
)

/**
 * Keeps six static plane bodies on a camera's frustum, so that a dynamic body in shot stays in shot.
 *
 * Make one against the world the bodies live in, then call [update] with the camera whenever the
 * camera may have moved. It remembers the planes it last built and only touches the ones that have
 * actually changed, so a per-frame call on a still camera does nothing.
 *
 * The planes are as hard or as bouncy as [friction] and [restitution] make them, and they are static
 * bodies like any other plane: they can be picked out of the world by name from [names], and they
 * count towards its body budget.
 */
class JoltCameraFrustumPlanes(
    /** The world the six plane bodies are created in, and the one whose bodies get woken. */
    val world: JoltPhysicsWorld,
    /**
     * The stem of all six body names, which get a face name appended: `left`, `right`, `top`,
     * `bottom`, `near`, `far`.
     *
     * Changing it between updates orphans the planes built under the old stem, since [remove] and
     * the change detection both go by the current [names].
     */
    var prefix: String = "camera-frustum",
    /** Near distance to use in place of the camera's own, or null to take the camera's `zNear`. */
    var nearDistance: Double? = null,
    /** Far distance to use in place of the camera's own, or null to take the camera's `zFar`. */
    var farDistance: Double? = null,
    /** Friction for all six planes. The default is high, on the assumption that a wall should hold. */
    var friction: Double = 0.8,
    /** Restitution for all six planes. Zero, so the walls absorb rather than bounce. */
    var restitution: Double = 0.0,
    /**
     * Tags given to every plane, on top of the [prefix] and the face's own name.
     *
     * Those two are added automatically, so a plane always carries something that identifies both
     * the frustum it belongs to and which face it is, whatever this is set to.
     */
    var tags: Set<String> = setOf("frustum", "bounds"),
    /**
     * How far a plane has to move before it is worth rebuilding, as a distance on both the normal
     * and the constant.
     *
     * Rebuilding a plane means destroying and recreating a Jolt body, so this is the threshold that
     * makes a per-frame [update] on a nearly-still camera free. Set it to zero to rebuild on any
     * change at all.
     */
    var updateEpsilon: Double = 1.0e-5,
    /**
     * Whether to wake the world's dynamic bodies after a plane has been rebuilt.
     *
     * On by default, and the reason is that Jolt lets a resting body sleep: without this, a body
     * settled against a wall stays where it was when the wall moved out from under it. Turn it off
     * only if nothing in the scene is ever allowed to rest against a plane.
     */
    var wakeDynamicBodiesOnUpdate: Boolean = true,
    /** Wake only bodies carrying a tag this matches; null wakes them all. */
    var wakeIncludeTagRegex: Regex? = null,
    /** Never wake a body carrying a tag this matches, whatever [wakeIncludeTagRegex] says. */
    var wakeExcludeTagRegex: Regex? = null
) {
    private val lastPlanesByName = LinkedHashMap<String, JoltFrustumPlane>()

    /**
     * The six body names this would use, in face order: left, right, top, bottom, near, far.
     *
     * Derived from [prefix] on every read, so it answers for the current prefix and not for whatever
     * the planes in the world were actually built under.
     */
    val names: List<String>
        get() = FACE_NAMES.map { "$prefix.$it" }

    /** [update] under a name that reads better on the first call. */
    fun add(camera: Camera, near: Double? = null, far: Double? = null): List<String> =
        update(camera, near, far)

    /**
     * Brings the six planes into line with where the camera is now, and returns their names.
     *
     * Creates the ones that are missing and recreates the ones that have moved further than
     * [updateEpsilon]; leaves the rest untouched, so this is the call to make every frame. The
     * return value is all six names either way, not the subset that changed. [near] and [far]
     * override [nearDistance] and [farDistance] for this call only.
     *
     * Dynamic bodies are woken afterwards if anything at all was rebuilt.
     */
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

    /**
     * Takes all six planes out of the world, and reports whether any of them was there.
     *
     * Goes by [names], so it removes the planes belonging to the *current* [prefix]. Also forgets
     * what it had built, which means a later [update] starts from scratch.
     */
    fun remove(): Boolean {
        var removedAny = false
        for (name in names) {
            removedAny = world.removeBody(name) || removedAny
        }
        lastPlanesByName.clear()
        return removedAny
    }

    /**
     * Works out the six planes for this camera without touching the world.
     *
     * The geometry on its own, which is the useful half if you want to draw the frustum, test a
     * point against it, or feed the planes to something other than Jolt. Each normal already faces
     * inward, decided by which side of the plane the frustum's own midpoint falls on.
     *
     * The frustum's basis comes from the camera's position, forward and right, with up re-derived as
     * right cross forward so it is exactly perpendicular to the view direction even when the
     * camera's own up is not. The half-extents are the perspective ones at each distance,
     * `tan(fov / 2)` vertically and that times `aspect` horizontally, shifted by the projection's
     * `ox` and `oy`. Near is clamped above zero, and a far that is not beyond near is an error.
     *
     * The point that decides which way is inward sits on the view axis, unshifted, so an `ox` or `oy`
     * beyond one half-extent puts it outside the window it is supposed to be inside and the left and
     * right normals come out the wrong way round. Offsets that big are not what the field is for,
     * but nothing here rejects them.
     */
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
