package afield.four.imgui

import afield.four.targets.OpenXRHmdTarget.ControllerState
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs

/**
 * A world-space rectangular panel that shows an [ImGuiRenderToTexture] and can be raycast against.
 *
 * The panel lives in its local XY plane (z = 0), sized [halfWidth] × [halfHeight] metres, with
 * local +x = right, +y = up, +z = the front-facing normal. Two things you do with it:
 *
 *  - **Position it** with [place] (from a position + right/up axes) or [faceToward] (aim at an eye).
 *    Binding it to the back of a hand is just `panel.place(handPos, handRight, handUp)` each frame.
 *  - **Send a ray into it** with [raycast] (returns the plane hit + panel UV) or [interact] (also
 *    injects the hit into ImGui as a pointer). Build the ray from a controller with [controllerRay].
 *
 * Use [panelMvp] to get the per-eye model-view-projection for drawing the quad.
 */
class ImGuiPanel(
    val imgui: ImGuiRenderToTexture,
    var halfWidth: Float,
    var halfHeight: Float,
) {
    /** World transform of the panel. Assign directly, or set it with [place] / [faceToward]. */
    val model = Matrix4f()

    data class Hit(
        /** World-space point where the ray met the panel plane. */
        val worldPoint: Vector3f,
        /** Panel UV of the hit (0..1, origin top-left). Meaningful only when [onPanel]. */
        val u: Float,
        val v: Float,
        /** True when the hit falls inside the panel rectangle. */
        val onPanel: Boolean,
    )

    /**
     * Orient the panel at [position] with the given [right] and [up] axes; the front-facing normal is
     * `right × up`, so place the viewer on the +normal side to read it. [right] and [up] need not be
     * unit length or exactly orthogonal — they are re-orthonormalized (up is rebuilt as normal × right).
     */
    fun place(position: Vector3f, right: Vector3f, up: Vector3f): Matrix4f {
        val r = Vector3f(right).normalize()
        val n = Vector3f(r).cross(up).normalize()   // normal = right × up
        val u = Vector3f(n).cross(r).normalize()     // re-orthogonalized up = normal × right
        return model.set(
            r.x, r.y, r.z, 0f,
            u.x, u.y, u.z, 0f,
            n.x, n.y, n.z, 0f,
            position.x, position.y, position.z, 1f,
        )
    }

    /** Place the panel at [position] with its front facing [eye], kept upright against [worldUp]. */
    fun faceToward(position: Vector3f, eye: Vector3f, worldUp: Vector3f = Vector3f(0f, 1f, 0f)): Matrix4f {
        val n = Vector3f(eye).sub(position).normalize() // normal points at the eye
        val r = Vector3f(worldUp).cross(n).normalize()  // right = worldUp × normal
        val u = Vector3f(n).cross(r).normalize()
        return place(position, r, u)
    }

    /** Model-view-projection for drawing the quad, given an eye's view and projection. */
    fun panelMvp(view: Matrix4f, projection: Matrix4f, dest: Matrix4f = Matrix4f()): Matrix4f =
        dest.set(projection).mul(view.mul(Matrix4f(model)))

    /**
     * Intersect a world ray with the panel plane. Returns null when the ray is parallel to the plane
     * or the intersection is behind [origin]; otherwise a [Hit] whose [Hit.onPanel] says whether it
     * landed inside the rectangle.
     */
    fun raycast(origin: Vector3f, direction: Vector3f): Hit? {
        val inv = Matrix4f(model).invert()
        val localOrigin = inv.transformPosition(Vector3f(origin))
        val localDir = inv.transformDirection(Vector3f(direction))
        if (abs(localDir.z) <= 1e-6f) return null
        val t = -localOrigin.z / localDir.z
        if (t <= 0f) return null
        val hit = Vector3f(localOrigin).fma(t, localDir) // local hit, z ≈ 0
        val world = model.transformPosition(hit, Vector3f())
        val u = hit.x / (halfWidth * 2f) + 0.5f
        val v = 0.5f - hit.y / (halfHeight * 2f)
        val onPanel = abs(hit.x) <= halfWidth && abs(hit.y) <= halfHeight
        return Hit(world, u, v, onPanel)
    }

    /**
     * [raycast] plus ImGui pointer injection: when the ray lands on the panel the hit UV is injected as
     * the mouse position (with [pressed] as the left button); otherwise the pointer is parked far
     * off-panel so ImGui registers no hover. Returns the same [Hit] as [raycast].
     */
    fun interact(origin: Vector3f, direction: Vector3f, pressed: Boolean): Hit? {
        val hit = raycast(origin, direction)
        if (hit != null && hit.onPanel) {
            imgui.injectPointer(hit.u * imgui.width, hit.v * imgui.height, pressed)
        } else {
            imgui.injectPointer(imgui.width * 2f, imgui.height * 2f, false)
        }
        return hit
    }

    companion object {
        /**
         * Build a world ray `(origin, direction)` from a controller pose. The controller's local
         * forward defaults to +Z, matching the convention used by Field's mock and OpenXR controllers
         * across the examples (`direction = rotation · localForward`).
         */
        fun controllerRay(
            state: ControllerState,
            localForward: Vector3f = Vector3f(0f, 0f, 1f),
        ): Pair<Vector3f, Vector3f> {
            val dir = Vector3f(localForward)
            state.rotation.transform(dir)
            return Vector3f(state.position) to dir
        }
    }
}
