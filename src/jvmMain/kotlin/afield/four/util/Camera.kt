package afield.four.util

import afield.four.Renderer
import afield.four.buffers.HasUniform
import afield.four.modules.fromContext
import afield.stdlib.d
import afield.stdlib.f
import afield.stdlib.set
import afield.stdlib.vec
import afield.stdlib.x
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs

class Camera(val renderer: Renderer = fromContext()) {
    var at = vec(0, 0, -10)
    var up = vec(0, 1, 0)
    var target = vec(0, 0, 0)

    /**
     * Forward direction (normalized) from camera position toward target.
     * Falls back to -Z if at == target.
     */
    val forward: Vector3f
        get() {
            val dir = Vector3f(target).sub(at)
            if (dir.lengthSquared() < 1e-12f) {
                return Vector3f(0f, 0f, -1f)
            }
            return dir.normalize()
        }

    /**
     * Right direction (normalized), derived from forward x up.
     * If up is degenerate, this will be recomputed in a stable way.
     */
    val right: Vector3f
        get() {
            val f = forward
            val u = Vector3f(up).normalize()
            val r = f.cross(u, Vector3f())
            if (r.lengthSquared() < 1e-12f) {
                // Fallback to some orthogonal vector
                return if (abs(f.y) > 0.9f) {
                    Vector3f(1f, 0f, 0f)
                } else {
                    Vector3f(f.z, 0f, -f.x).normalize()
                }
            }
            return r.normalize()
        }

    var p = SuperProjection(renderer = renderer, autoMatchDistance = { distanceToTarget().d})

    /** Manually set projection aspect ratio (width/height). */
    fun setAspect(aspect: Float) {
        p.setAspect(aspect)
    }

    /**
     * Model-view matrix uniform wrapper.
     */
    var m = object : HasUniform<Matrix4f> {
        override fun evaluateUniform() = modelView()
    }

    /**
     * Compute model-view matrix from at/target/up.
     */
    fun modelView(): Matrix4f {
        val f = forward
        var u = Vector3f(up).normalize()
        val s = (f x u)
        u = (s x f).normalize()

        val m = Matrix4f()

        // Basis (right, up, -forward) — camera looks down -Z in view space
        m[0 * 4 + 0] = s.x
        m[1 * 4 + 0] = s.y
        m[2 * 4 + 0] = s.z

        m[0 * 4 + 1] = u.x
        m[1 * 4 + 1] = u.y
        m[2 * 4 + 1] = u.z

        m[0 * 4 + 2] = -f.x
        m[1 * 4 + 2] = -f.y
        m[2 * 4 + 2] = -f.z

        m[3 * 4 + 3] = 1f

        m.transpose()
        // View = R^T * T(-eye)
        m.translate(-at.x, -at.y, -at.z)

        return m
    }

    // -------------------------------------------------------------------------
    // High-level programmatic control
    // -------------------------------------------------------------------------

    /**
     * Distance from camera to target.
     */
    fun distanceToTarget(): Float = at.distance(target)

    /**
     * Set camera pose using a classical look-at description.
     */
    fun lookAt(
        eye: Vector3f,
        center: Vector3f,
        up: Vector3f = this.up,
        reorthogonalizeUp: Boolean = true
    ) {
        this.at.set(eye)
        this.target.set(center)
        this.up.set(up)
        if (reorthogonalizeUp) {
            reorthogonalizeUp()
        }
    }

    /**
     * Look from [position] in the given [direction].
     * [direction] does not need to be normalized.
     */
    fun lookAlong(
        position: Vector3f,
        direction: Vector3f,
        upHint: Vector3f = this.up,
        reorthogonalizeUp: Boolean = true
    ) {
        val dir = Vector3f(direction)
        if (dir.lengthSquared() < 1e-12f) return
        dir.normalize()

        at.set(position)
        target.set(Vector3f(position).add(dir))
        up.set(upHint)
        if (reorthogonalizeUp) {
            reorthogonalizeUp()
        }
    }

    // --- Translation ---------------------------------------------------------

    /**
     * Translate in world space.
     *
     * If [moveTarget] is true, both [at] and [target] move by [delta] (pan/truck).
     * Otherwise only the camera position moves (dolly keeping the same target).
     */
    fun moveWorld(delta: Vector3f, moveTarget: Boolean = true) {
        at.add(delta)
        if (moveTarget) {
            target.add(delta)
        }
    }

    fun moveWorld(dx: Double, dy: Double, dz: Double, moveTarget: Boolean = true) {
        moveWorld(Vector3f(dx.toFloat(), dy.toFloat(), dz.toFloat()), moveTarget)
    }

    /**
     * Move in camera-local space.
     *
     * - [forward] is along +view direction
     * - [right] is along camera right
     * - [upAmount] is along camera up
     *
     * If [moveTarget] is true, both at/target are translated (pan).
     * Otherwise only the camera position moves (dolly).
     */
    fun moveLocal(
        forward: Double = 0.0,
        right: Double = 0.0,
        upAmount: Double = 0.0,
        moveTarget: Boolean = true
    ) {
        if (forward == 0.0 && right == 0.0 && upAmount == 0.0) return

        val f = this.forward
        val r = this.right
        val u = Vector3f(up).normalize()

        val delta = Vector3f()
        if (forward != 0.0) {
            delta.fma(forward.toFloat(), f)
        }
        if (right != 0.0) {
            delta.fma(right.toFloat(), r)
        }
        if (upAmount != 0.0) {
            delta.fma(upAmount.toFloat(), u)
        }

        moveWorld(delta, moveTarget)
    }

    /**
     * Dolly along the view direction.
     *
     * - [amount] > 0 moves forward.
     * - If [moveTarget] is true, both at/target move (like a push in).
     *   Otherwise only the camera moves, changing distance to target.
     */
    fun dolly(amount: Double, moveTarget: Boolean = false) {
        moveLocal(forward = amount, moveTarget = moveTarget)
    }

    /**
     * Pan in screen space: right/left and up/down in camera-local space.
     * [right] > 0 moves camera to the right, [upAmount] > 0 moves upward.
     */
    fun pan(right: Double, upAmount: Double, moveTarget: Boolean = true) {
        moveLocal(forward = 0.0, right = right, upAmount = upAmount, moveTarget = moveTarget)
    }

    // --- Rotation helpers ----------------------------------------------------

    /**
     * General rotation of view around the camera position ([at]).
     *
     * Rotates the vector (target - at) around [axis] by [angle] radians.
     * If [rotateUp] is true, rotates [up] as well.
     */
    fun rotateAroundAt(
        angle: Double,
        axis: Vector3f,
        rotateUp: Boolean = true,
        reorthogonalizeUp: Boolean = true
    ) {
        val ax = Vector3f(axis)
        if (ax.lengthSquared() < 1e-12f || angle == 0.0) return
        ax.normalize()

        val q = Quaternionf().fromAxisAngleRad(ax, angle.toFloat())

        val dir = Vector3f(target).sub(at)
        q.transform(dir)
        target.set(at).add(dir)

        if (rotateUp) {
            q.transform(up)
        }
        if (reorthogonalizeUp) {
            reorthogonalizeUp()
        }
    }

    /**
     * General orbit of the camera position around the target.
     *
     * Rotates the vector (at - target) around [axis] by [angle] radians.
     * If [rotateUp] is true, rotates [up] as well.
     */
    fun rotateAroundTarget(
        angle: Double,
        axis: Vector3f,
        rotateUp: Boolean = true,
        reorthogonalizeUp: Boolean = true
    ) {
        val ax = Vector3f(axis)
        if (ax.lengthSquared() < 1e-12f || angle == 0.0) return
        ax.normalize()

        val q = Quaternionf().fromAxisAngleRad(ax, angle.toFloat())

        val dir = Vector3f(at).sub(target)
        q.transform(dir)
        at.set(target).add(dir)

        if (rotateUp) {
            q.transform(up)
        }
        if (reorthogonalizeUp) {
            reorthogonalizeUp()
        }
    }

    /**
     * Yaw around camera up axis.
     *
     * - [angle] in radians, positive is left-handed around +up.
     * - If [aroundTarget] is true, orbit camera around target.
     *   Otherwise rotate view direction around camera position.
     */
    fun yaw(
        angle: Double,
        aroundTarget: Boolean = false,
        rotateUp: Boolean = true,
        reorthogonalizeUp: Boolean = true
    ) {
        val axis = Vector3f(up)
        if (axis.lengthSquared() < 1e-12f) {
            axis.set(0f, 1f, 0f)
        }
        if (aroundTarget) {
            rotateAroundTarget(angle, axis, rotateUp, reorthogonalizeUp)
        } else {
            rotateAroundAt(angle, axis, rotateUp, reorthogonalizeUp)
        }
    }

    /**
     * Pitch around camera right axis.
     *
     * - [angle] in radians; positive pitches up.
     * - If [aroundTarget] is true, orbit camera around target.
     *   Otherwise rotate view direction around camera position.
     */
    fun pitch(
        angle: Double,
        aroundTarget: Boolean = false,
        rotateUp: Boolean = true,
        reorthogonalizeUp: Boolean = true
    ) {
        val f = Vector3f(target).sub(at)
        if (f.lengthSquared() < 1e-12f) return
        f.normalize()

        val u = Vector3f(up).normalize()
        val axis = f.cross(u, Vector3f()).normalize()
        if (axis.lengthSquared() < 1e-12f) return

        if (aroundTarget) {
            rotateAroundTarget(angle, axis, rotateUp, reorthogonalizeUp)
        } else {
            rotateAroundAt(angle, axis, rotateUp, reorthogonalizeUp)
        }
    }

    /**
     * Roll around the view direction, rotating [up] but leaving
     * [at] and [target] fixed.
     */
    fun roll(
        angle: Double,
        reorthogonalizeUp: Boolean = false
    ) {
        val f = Vector3f(target).sub(at)
        if (f.lengthSquared() < 1e-12f) return
        f.normalize()

        val q = Quaternionf().fromAxisAngleRad(f, angle.toFloat())
        q.transform(up)

        if (reorthogonalizeUp) {
            reorthogonalizeUp()
        }
    }

    /**
     * Make [up] orthogonal to the current forward direction, and
     * ensure a stable, non-degenerate up vector.
     */
    fun reorthogonalizeUp() {
        val f = Vector3f(target).sub(at)
        if (f.lengthSquared() < 1e-12f) {
            return
        }
        f.normalize()

        val upVec = Vector3f(up)
        val proj = upVec.dot(f)
        upVec.fma(-proj, f) // up -= proj * f

        if (upVec.lengthSquared() < 1e-6f) {
            if (abs(f.y) > 0.9f) {
                upVec.set(1f, 0f, 0f)
            } else {
                upVec.set(0f, 1f, 0f)
                val p2 = upVec.dot(f)
                upVec.fma(-p2, f)
            }
        }

        upVec.normalize()
        up.set(upVec)
    }
}
